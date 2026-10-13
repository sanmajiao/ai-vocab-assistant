"""Standalone local OCR/environment check; no API credentials or screenshots."""
from __future__ import annotations

import json
import os
import queue
import struct
import sys
import threading
import tkinter as tk
from pathlib import Path
from tkinter import ttk

from PIL import Image, ImageDraw, ImageFont, ImageTk
from core import APP_VERSION, comparison
from ocr import WindowsOCR

ROOT=Path(__file__).resolve().parent


def check_local(root=ROOT):
    report={'version':APP_VERSION,'python':sys.version.split()[0],'bits':struct.calcsize('P')*8,'checks':[]}
    image=Image.new('RGB',(1000,330),'white')
    font_dir=Path(os.environ.get('SystemRoot',r'C:\Windows'))/'Fonts'
    font_path=next((font_dir/name for name in ('msyh.ttc','msyhbd.ttc','simhei.ttf') if (font_dir/name).exists()),None)
    if not font_path:
        report['checks'].append({'name':'测试字体','ok':False,'detail':'没有找到中文测试字体，请检查 Windows 中文语言组件。'})
        return report,image
    draw=ImageDraw.Draw(image)
    draw.text((70,50),'区域 地带',fill='black',font=ImageFont.truetype(str(font_path),55))
    draw.text((70,170),'content zone',fill='black',font=ImageFont.truetype(str(font_path),58))
    data=root/'data'
    data.mkdir(parents=True,exist_ok=True)
    engine=WindowsOCR(root,data/'check-temporary')
    try:
        result=engine.recognize(image)
        text=result.get('text','')
        normalized=comparison(text)
        report['ocr_info']=getattr(engine,'info',{})
        report['checks'].extend([
            {'name':'OCR 进程','ok':True,'detail':'本地文字识别进程已启动。'},
            {'name':'中文文字','ok':'区域' in normalized and '地带' in normalized,'detail':text},
            {'name':'英文文字','ok':'content' in normalized and 'zone' in normalized,'detail':text},
        ])
    except Exception as error:
        report['checks'].append({'name':'OCR 进程','ok':False,'detail':str(error)})
    finally:
        engine.close()
    report['ok']=bool(report['checks']) and all(item['ok'] for item in report['checks'])
    (data/'local-check.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8')
    return report,image


def main():
    root=tk.Tk()
    root.title('本地识别检查 · '+APP_VERSION)
    root.geometry('650x630')
    ttk.Label(root,text='检查这台电脑能否识别中文和英文',font=('Microsoft YaHei UI',16,'bold')).pack(padx=18,pady=(18,8),anchor='w')
    ttk.Label(root,text='只读取下方生成的测试文字，不调用 API，不读取密钥。').pack(padx=18,anchor='w')
    preview=ttk.Label(root)
    preview.pack(padx=18,pady=12)
    status=tk.StringVar(value='点击开始检查。')
    ttk.Label(root,textvariable=status,wraplength=605).pack(padx=18,anchor='w')
    output=tk.Text(root,height=13,wrap='word',font=('Microsoft YaHei UI',10))
    output.pack(padx=18,pady=12,fill='both',expand=True)
    events=queue.Queue()
    def start():
        button.configure(state='disabled')
        status.set('正在检查本地识别…')
        threading.Thread(target=lambda:events.put(check_local()),daemon=True).start()
    button=ttk.Button(root,text='开始检查',command=start)
    button.pack(pady=(0,16))
    def poll():
        try:
            report,image=events.get_nowait()
        except queue.Empty:
            root.after(80,poll)
            return
        image.thumbnail((600,198))
        photo=ImageTk.PhotoImage(image)
        preview.configure(image=photo)
        preview.image=photo
        status.set('本地中英文识别可用。若实际题目不对，请在助手预览中核对捕获的画面。' if report.get('ok') else '本地识别未通过。请先解决下方问题，再开始答题。')
        lines=[('通过' if item['ok'] else '未通过')+' · '+item['name']+'\n'+item['detail'] for item in report['checks']]
        if not report.get('ok'):
            lines.append('本包包含离线备用识别模型。请确认完整解压了 ocr_dependencies，查看上方引擎错误；不要仅重复请求 API。')
        lines.append('报告保存在本程序 data/local-check.json，不含密钥。')
        output.delete('1.0','end')
        output.insert('end','\n\n'.join(lines))
        button.configure(state='normal')
        root.after(80,poll)
    root.after(80,poll)
    root.mainloop()


if __name__=='__main__':
    if '--headless' in sys.argv:
        sys.stdout.reconfigure(encoding='utf-8')
        report,_=check_local()
        print(json.dumps(report,ensure_ascii=False))
        raise SystemExit(0 if report.get('ok') else 1)
    main()
