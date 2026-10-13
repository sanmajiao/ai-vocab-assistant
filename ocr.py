from __future__ import annotations

import json
import os
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

from PIL import Image


class WindowsOCR:
    """A warmed persistent system OCR process; no cloud OCR or packages needed."""
    def __init__(self, root: Path, temp: Path):
        self.root, self.temp = root, temp
        self.process = None
        self.output = queue.Queue()
        self.lock = threading.RLock()
        self.reader = None
        self.info = {}
        self.rapid = None
        self.backend = 'windows'

    def _load_rapid(self):
        if self.rapid is not None:
            return
        dependency = self.root / 'ocr_dependencies'
        if not (dependency / 'rapidocr_onnxruntime').is_dir():
            raise RuntimeError('本地备用 OCR 未安装，请在项目目录按 README 安装 requirements-ocr.txt 到 ocr_dependencies')
        if str(dependency) not in sys.path:
            sys.path.insert(0,str(dependency))
        try:
            from rapidocr_onnxruntime import RapidOCR
            from rapidocr_onnxruntime import utils
            original=utils.SessionOptions
            def options():
                result=original()
                result.intra_op_num_threads=2
                result.inter_op_num_threads=1
                return result
            utils.SessionOptions=options
            try:
                self.rapid=RapidOCR()
            finally:
                utils.SessionOptions=original
            self.info['fallback_available']=True
        except Exception as error:
            raise RuntimeError('本地备用 OCR 无法加载，请检查 OCR 依赖安装及微软 VC++ x64 运行库：'+str(error)) from None

    def recognize_line(self, image):
        """Offline recognition-only pass, returning text and confidence."""
        with self.lock:
            self._load_rapid()
            import numpy as np
            started=time.monotonic()
            values,_=self.rapid.text_recognizer([np.array(image.convert('RGB'))[:,:,::-1]])
            text,score=values[0] if values else ('',0)
            return {'text':text,'confidence':float(score),'ocr_ms':round((time.monotonic()-started)*1000),'ocr_language':'ch_PP-OCRv3'}

    def _rapid_recognize(self,image):
        self._load_rapid()
        import numpy as np
        started=time.monotonic()
        values,_=self.rapid(np.array(image.convert('RGB')))
        lines=[]
        for polygon,text,score in values or []:
            xs=[p[0] for p in polygon];ys=[p[1] for p in polygon]
            word={'text':text,'x':min(xs),'y':min(ys),'width':max(xs)-min(xs),'height':max(ys)-min(ys),'confidence':float(score)}
            lines.append({'text':text,'words':[word]})
        return {'ok':True,'text':'\n'.join(r['text'] for r in lines),'lines':lines,'ocr_ms':round((time.monotonic()-started)*1000),'ocr_language':'ch_PP-OCRv3'}

    def start(self):
        with self.lock:
            self._start()

    def _start(self):
        if self.backend=='rapid':
            self._load_rapid()
            return
        if self.process is not None and self.process.poll() is None:
            return
        self.output = queue.Queue()
        script = self.root / 'ocr_worker.ps1'
        # PS5 needs BOM for source containing non-ASCII text. Worker protocol
        # itself uses ASCII JSON escaping to avoid console-codepage differences.
        self.process = subprocess.Popen(
            [str(Path(os.environ.get('SystemRoot', r'C:\Windows')) / 'System32/WindowsPowerShell/v1.0/powershell.exe'), '-NoProfile', '-NonInteractive', '-ExecutionPolicy', 'Bypass', '-File', str(script)],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
            encoding='utf-8', creationflags=subprocess.CREATE_NO_WINDOW,
        )
        process = self.process
        output = self.output
        def read():
            for line in process.stdout:
                output.put(line)
            output.put(None)
        self.reader = threading.Thread(target=read, daemon=True)
        self.reader.start()
        try:
            line = output.get(timeout=8)
            ready = json.loads(line) if line else {}
            self.info = ready
            if not ready.get('ready'):
                raise RuntimeError(ready.get('error') or 'Windows OCR 无法启动，请安装简体中文 OCR 语言组件')
            if not str(ready.get('language','')).lower().startswith('zh'):
                raise RuntimeError('Windows OCR 仅有非中文语言，已改用程序附带中文离线模型')
        except (queue.Empty, ValueError):
            self.close()
            raise RuntimeError('Windows OCR 启动暂时失败，正在准备重试') from None
        except RuntimeError:
            self.close()
            self._load_rapid()
            self.backend='rapid'
            self.info.update(ready=True,language='RapidOCR 中文英文离线模型',backend='rapid',fallback_available=True)

    def recognize(self, image: Image.Image) -> dict:
        with self.lock:
            return self._recognize(image)

    def _recognize(self, image: Image.Image) -> dict:
        self.start()
        if self.backend=='rapid':
            return self._rapid_recognize(image)
        self.temp.mkdir(parents=True, exist_ok=True)
        path = self.temp / f'ocr-{time.monotonic_ns()}.png'
        image.save(path)
        self.process.stdin.write(json.dumps({'path': str(path)}) + '\n')
        self.process.stdin.flush()
        try:
            line = self.output.get(timeout=2.5)
            if line is None:
                self.close()
                raise RuntimeError('OCR 进程退出')
            result = json.loads(line)
            if not result.get('ok'):
                raise RuntimeError(result.get('error') or 'OCR 无法识别当前截图')
            result['ocr_language'] = self.info.get('language', '')
            return result
        except queue.Empty:
            self.close()
            raise RuntimeError('本地 OCR 超时，已准备重启识别进程') from None
        finally:
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass

    def close(self):
        if self.process is not None:
            process = self.process
            self.process = None
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=2)
            if self.reader:
                self.reader.join(timeout=1)
            process.stdin.close()
            process.stdout.close()
