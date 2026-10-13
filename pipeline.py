from __future__ import annotations

import json
import queue
import re
import threading
import time
from dataclasses import replace
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

from PIL import Image, ImageChops, ImageStat

from core import APP_VERSION, comparison, parse_question, validate_answer, spatial_rows, input_line
from network import Expired, RetryableError
from ocr import WindowsOCR
from windows import capture, SourceClosedError


from reader import prepare, read_question

def blank_context(question, hints=()):
    text = question.context.lower()
    # OCR may put part of the entered word just outside the detected field.
    # Ignore input text adjacent to the blank, keeping surrounding words.
    for prefix in {question.prefix, *question.prefix_alternatives, *hints}:
        if prefix:
            word = re.escape(prefix.lower())+r'[a-z]*'
            text = re.sub(r'(____)\s*'+word+r'\b',r'\1',text)
            text = re.sub(r'\b'+word+r'\s*(?=____)','',text)
    return re.sub(r'[^a-z]','',text)


def same_blank(old, question):
    if old is None or old.kind != 'blank' or question.kind != 'blank':
        return False
    before, after = comparison(old.prompt), comparison(question.prompt)
    same_prompt = before == after
    hints = (old.prefix, *old.prefix_alternatives, question.prefix, *question.prefix_alternatives)
    return same_prompt and blank_context(old,hints) == blank_context(question,hints)


def blank_visual(image, result):
    """Compare static content in row-local coordinates, excluding typed input.

    Row-local samples survive window resize and keyboard-induced movement.
    Never treat a changed thumbnail of the entire phone as question evidence.
    """
    if image is None or not result:
        return None
    rows = spatial_rows(result)
    located = None
    if result.get('input_bbox'):
        left,top,right,bottom=result['input_bbox']
        candidates=[row for row in rows if re.search('[A-Za-z]',row['text']) and top <= row['y']+row['height']*.5 <= bottom]
        if candidates:
            row=min(candidates,key=lambda r:abs(r['y']+r['height']-bottom))
            located=(row,(left,right,bottom-4))
        else:
            # Short pale prefixes can be completely absent from full-screen OCR.
            # Reader's pixel-detected field geometry is still valid evidence.
            height=max(10,(bottom-top)*.6)
            located=({'text':'','y':bottom-4-height,'height':height,'words':[]},(left,right,bottom-4))
    for row in rows:
        if located:
            break
        line = input_line(image, row)
        if line and re.search(r'[A-Za-z]', row['text']):
            located = (row, line)
            break
    if located is None:
        return None
    row, (left, right, underline) = located
    height = max(10, row['height'])
    top, bottom = max(0, int(row['y']-height*.2)), min(image.height, int(row['y']+height*1.15))
    def sample(box):
        box = tuple(map(int, box))
        if box[2] <= box[0] or box[3] <= box[1]:
            return Image.new('L', (96, 24), 255)
        return image.crop(box).convert('L').point(lambda v: 0 if v < 160 else 255).resize((96,24))
    # Tight bounding boxes prevent margins or different app placement changing the signature.
    left_words = [w for w in row['words'] if w['x']+w['width'] <= left+2]
    right_words = [w for w in row['words'] if w['x'] >= right-2]
    outside = []
    for words in (left_words, right_words):
        if words:
            outside.append(sample((min(w['x'] for w in words),top,max(w['x']+w['width'] for w in words),bottom)))
        else:
            outside.append(Image.new('L',(96,24),255))
    input_area = image.crop((int(left),top,int(right),max(top+1,int(underline-height*.15)))).convert('L')
    dark = sum(value < 125 for value in input_area.getdata()) / max(1,input_area.width*input_area.height)
    chinese = [r for r in rows if r['y'] > underline and re.search(r'[\u4e00-\u9fff]',r['text']) and not re.search(r'提交|返回|回答|答案',r['text'])]
    prompt = None
    if chinese:
        first = chinese[0]
        words = first['words']
        if words:
            prompt = sample((min(w['x'] for w in words),first['y']-3,max(w['x']+w['width'] for w in words),first['y']+first['height']+3))
    return {'outside':outside, 'prompt':prompt, 'typing':dark > .018, 'confirmed':bool(result.get('hint_confirmed'))}


def visual_distance(before, after, field='outside'):
    if before is None or after is None:
        return None
    left, right = before.get(field), after.get(field)
    if left is None or right is None:
        return None
    if field != 'outside':
        left, right = [left], [right]
    return max(ImageStat.Stat(ImageChops.difference(a,b)).mean[0] for a,b in zip(left,right))


class Pipeline:
    def __init__(self, root: Path, data: Path, events: queue.Queue):
        self.root, self.data, self.events = root, data, events
        self.stop = threading.Event()
        self.retry_event = threading.Event()
        self.lock = threading.RLock()
        self.generation = 0
        self.cancel = threading.Event()
        self.current_key = None
        self.current_question = None
        self.candidate_key = None
        self.candidate_count = 0
        self.candidate_started = 0
        self.thread = None
        self.ocr = WindowsOCR(root, data / 'temporary')
        self.pool = ThreadPoolExecutor(max_workers=3, thread_name_prefix='answer')
        self.cache = {}
        self.last_diagnostic = 0
        self.last_preview = 0
        self.current_visual = None
        self.control_epoch = 0
        self.manual_busy = False
        self.active_source = None
        self.uncertain = False
        self.manual_hint = False
        self.answered_generation = None
        try:
            self.cache = json.loads((data / 'answers.json').read_text(encoding='utf-8'))
        except (OSError, ValueError):
            pass

    def emit(self, event, **values):
        if event=='held':
            values['answer_ready']=self.answered_generation==values.get('generation')
            if not values['answer_ready'] and not values.get('previous'):
                values['previous']=True
                values['text']='正在取得当前题答案；上一条答案仅供参考，请勿填写'
        self.events.put({'event': event, **values})

    def start(self, api, source: dict, forced='auto', crop=True):
        if self.thread and self.thread.is_alive():
            raise RuntimeError('识别还在运行')
        self.stop.clear()
        self.retry_event.clear()
        self.active_source = dict(source)
        self.thread = threading.Thread(target=self._watch, args=(api, dict(source), forced, crop), daemon=True)
        self.thread.start()

    def invalidate(self):
        with self.lock:
            self.generation += 1
            self.cancel.set()
            self.cancel = threading.Event()
            self.current_key = None
            self.current_question = None
            self.current_visual = None
            self.control_epoch += 1
            self.manual_busy = False
            self.candidate_key = None
            self.candidate_count = 0
            generation = self.generation
        self.emit('clear', generation=generation)

    def halt(self, preserve_answer=True):
        self.stop.set()
        self.retry_event.clear()
        if not preserve_answer:
            self.invalidate()
            return
        with self.lock:
            self.generation += 1
            self.cancel.set()
            self.current_key = None
            self.current_question = None
            self.current_visual = None
            self.control_epoch += 1
            self.manual_busy = False
            self.candidate_key = None
            self.candidate_count = 0
            generation = self.generation
        self.emit('paused',generation=generation)

    def close(self):
        self.halt(preserve_answer=False)
        if self.thread:
            self.thread.join(timeout=3)
        self.ocr.close()
        self.pool.shutdown(wait=False, cancel_futures=True)

    def _watch(self, api, source, forced, crop):
        previous, last_ocr, misses = None, 0, 0
        try:
            self.emit('status', text='正在预热本地识别…')
            while not self.stop.is_set():
                started = time.monotonic()
                try:
                    with self.lock:
                        epoch = self.control_epoch
                        busy = self.manual_busy
                    if busy:
                        self.stop.wait(.1)
                        continue
                    image = self.capture_source(source, crop)
                    if started-self.last_preview >= 1:
                        self.last_preview = started
                        self.emit('capture',image=image.copy(),source=dict(source))
                    thumb = image.resize((96, 64)).convert('L')
                    changed = previous is None or ImageStat.Stat(ImageChops.difference(previous, thumb)).mean[0] > .8
                    previous = thumb
                    if not changed and not self.retry_event.is_set() and started-last_ocr < .4:
                        self.stop.wait(.1)
                        continue
                    question, result = read_question(self.ocr, image, forced)
                    if self.stop.is_set():
                        return
                    with self.lock:
                        if epoch != self.control_epoch or self.manual_busy:
                            continue
                    last_ocr = time.monotonic()
                    if last_ocr-self.last_diagnostic >= 2:
                        self.last_diagnostic = last_ocr
                        try:
                            record = {'version':APP_VERSION,'size':image.size,'ocr_ms':result.get('ocr_ms',0),'text':result.get('text',''),'question':question.payload() if question else None}
                            (self.data/'last-read.json').write_text(json.dumps(record,ensure_ascii=False),encoding='utf-8')
                            image.save(self.data/'last-read.png')
                        except OSError:
                            pass
                    if question is None:
                        misses += 1
                        if self.current_question and self.current_question.kind == 'blank':
                            self.candidate_key = None
                            self.candidate_count = 0
                            self.uncertain = True
                            self.emit('held',text='当前画面未读完整；显示的是上一条答案，请勿据此填写新题',previous=True,generation=self.generation)
                        elif misses >= 2 and self.current_key is not None:
                            self.invalidate()
                        if self.current_key is None:
                            self.emit('unread',text=result.get('text',''),ocr_ms=result.get('ocr_ms',0),generation=self.generation)
                        self.emit('status', text='未读到完整题目，正在自动重试识别…')
                    else:
                        misses = 0
                        self.observe(question, api, started, result.get('ocr_ms', 0), image=image, result=result, epoch=epoch)
                except SourceClosedError as error:
                    self.halt()
                    self.emit('error',text=f'{error}；识别已停止，请重新选择来源')
                    return
                except Exception as error:
                    misses += 1
                    if self.current_question and self.current_question.kind == 'blank':
                        self.uncertain = True
                        self.emit('held',text='识别失败；显示的是上一条答案，请核对或手动重新读取',previous=True,generation=self.generation)
                    elif misses >= 2 and self.current_key is not None:
                        self.invalidate()
                    self.emit('status', text=f'识别暂时失败，自动重试：{error}')
                self.stop.wait(max(.05, .15-(time.monotonic()-started)))
        except Exception as error:
            self.emit('error', text=str(error))
        finally:
            self.emit('stopped')

    @staticmethod
    def capture_source(source, crop=True):
        # New capture sources support other mirror windows and normalized crops.
        image = capture(**{k:v for k,v in source.items() if k in ('hwnd','bbox','pid','crop_rel')})
        return prepare(image,crop)

    def observe(self, question, api, started, ocr_ms=0, repeat=False, force_api=False, image=None, result=None, epoch=None):
        with self.lock:
            if epoch is not None and (epoch != self.control_epoch or self.manual_busy):
                return
            self._observe(question,api,started,ocr_ms,repeat,force_api,image,result)

    def _observe(self, question, api, started, ocr_ms, repeat, force_api, image, result):
        old = self.current_question
        visual = blank_visual(image,result) if question.kind == 'blank' else None
        if not repeat and old and old.kind == 'blank':
            if same_blank(old,question):
                confirmed=bool(result and result.get('hint_confirmed'))
                changed_hint=(old.prefix,old.length)!=(question.prefix,question.length)
                if confirmed and question.prefix and question.length and changed_hint and not self.manual_hint:
                    # A newly recovered original pale hint can correct an earlier
                    # unconstrained candidate. Typed dark letters never qualify.
                    self.current_visual=visual
                    self.uncertain=False
                    self.candidate_key=None
                    self.candidate_count=0
                    self.submit(question,api,started,ocr_ms,repeat=True,force_api=True,observing=True)
                    return
                self.candidate_key = None
                self.candidate_count = 0
                self.uncertain = False
                self.emit('held',text='填空答案已固定显示；输入字母不会重新请求',generation=self.generation)
                return
            hints=(old.prefix,*old.prefix_alternatives,question.prefix,*question.prefix_alternatives)
            static_text_same = question.kind=='blank' and blank_context(old,hints)==blank_context(question,hints)
            outside_difference=visual_distance(self.current_visual,visual)
            prompt_difference=visual_distance(self.current_visual,visual,'prompt')
            outside_same=outside_difference is not None and outside_difference < 9
            prompt_same=prompt_difference is not None and prompt_difference < 9
            prompt_before,prompt_after=comparison(old.prompt),comparison(question.prompt)
            partial_prompt=bool(prompt_before and prompt_after and (prompt_before in prompt_after or prompt_after in prompt_before))
            typing=bool(visual and visual['typing'])
            confirmed_new_hint=bool(visual and visual['confirmed'] and (old.prefix,old.length)!=(question.prefix,question.length))
            strong_new=question.kind=='blank' and not partial_prompt and confirmed_new_hint and prompt_difference is not None and prompt_difference >= 18
            # An OCR change to stable visible text is not evidence of a new question.
            # In-field typing/tooltip occlusion must not become hard prefix constraints.
            if not strong_new and question.kind=='blank' and ((outside_same and prompt_same) or (typing and (outside_same or static_text_same)) or (partial_prompt and static_text_same)):
                self.candidate_key=None
                self.candidate_count=0
                self.uncertain = not (outside_same and prompt_same)
                self.emit('held',text='输入或遮挡导致识别变化；保留原答案，请核对题目' if self.uncertain else '画面文字未换题，保留当前填空答案',previous=self.uncertain,generation=self.generation)
                return
            # The prompt can be mostly obscured by a floating keyboard hint. If
            # surrounding English still matches, require a fresh pale hint or a
            # clear static prompt change before accepting this as a new question.
            if question.kind=='blank' and static_text_same and visual and not visual['confirmed'] and (prompt_difference is None or prompt_difference < 18):
                self.uncertain=True
                self.emit('held',text='换题证据不足；当前为上一条答案，请手动重新读取',previous=True,generation=self.generation)
                return
            # Three consecutive readings reduce false switches from repeated OCR noise.
            key = (question.kind,comparison(question.prompt),blank_context(question) if question.kind == 'blank' else question.options)
            if key != self.candidate_key:
                self.candidate_key = key
                self.candidate_count = 1
                self.candidate_started = started
            else:
                self.candidate_count += 1
            if self.candidate_count < 3:
                self.uncertain=True
                self.emit('held',text='正在确认换题；当前显示上一条答案，请勿填写',previous=True,generation=self.generation)
                return
            started = self.candidate_started
        self.candidate_key = None
        self.candidate_count = 0
        self.uncertain=False
        self.manual_hint=False
        self.current_visual=visual
        self.submit(question,api,started,ocr_ms,repeat,force_api,observing=True)

    def submit(self, question, api, started, ocr_ms=0, repeat=False, force_api=False, observing=False):
        with self.lock:
            if observing and self.stop.is_set():
                return
            old = self.current_question
            if not repeat and same_blank(old,question):
                question = old
            if self.current_key == question.key and not repeat:
                return
            self.generation += 1
            generation = self.generation
            self.cancel.set()
            self.cancel = threading.Event()
            cancel = self.cancel
            self.current_key, self.current_question = question.key, question
            if force_api:
                self.cache.pop(question.key, None)
        # Accuracy passes and new-question confirmation are local work. Give the
        # API its full waiting budget after that, while elapsed includes OCR.
        deadline = time.monotonic() + question.budget
        self.emit('question', question=question, generation=generation, deadline=deadline, ocr_ms=ocr_ms)
        cache_allowed = question.kind=='choice' or bool(question.prefix and question.length)
        cached = None if force_api or not cache_allowed else self.cache.get(question.key)
        if cached:
            try:
                answer = validate_answer(question, json.dumps(cached, ensure_ascii=False))
                self.answered_generation=generation
                self.emit('answer', answer=answer, generation=generation, elapsed=time.monotonic()-started, source='本地缓存', ocr_ms=ocr_ms,tentative=question.kind=='blank' and (not question.prefix or not question.length),previous=self.uncertain)
                return
            except (ValueError, TypeError):
                self.cache.pop(question.key, None)
        if api is None:
            self.emit('failure', text='题目已识别。请先填写并保存 API Key。', generation=generation)
            return
        self.pool.submit(self._request, api, question, generation, cancel, started, deadline, ocr_ms)

    def _request(self, api, question, generation, cancel, started, deadline, ocr_ms):
        for attempt in range(1, 4):
            try:
                if cancel.is_set() or time.monotonic() >= deadline:
                    raise Expired()
                answer = api.answer(question, deadline, cancel)
                with self.lock:
                    if cancel.is_set() or self.generation != generation or time.monotonic() >= deadline:
                        return
                    self.answered_generation=generation
                    if question.kind=='choice' or (question.prefix and question.length):
                        self.cache[question.key] = {'answer': answer.text, **({'option': answer.option} if answer.option else {})}
                        if len(self.cache) > 2000:
                            self.cache.pop(next(iter(self.cache)))
                        try:
                            path = self.data / 'answers.json'
                            temporary = path.with_suffix('.new')
                            temporary.write_text(json.dumps(self.cache, ensure_ascii=False), encoding='utf-8')
                            temporary.replace(path)
                        except OSError:
                            pass
                self.emit('answer', answer=answer, generation=generation, elapsed=time.monotonic()-started, source=getattr(api, 'label', 'DeepSeek'), ocr_ms=ocr_ms, attempts=attempt,tentative=question.kind=='blank' and (not question.prefix or not question.length),previous=self.uncertain)
                return
            except Expired:
                if not cancel.is_set():
                    self.emit('failure', text='本题等待超时，可点击手动重试', generation=generation)
                return
            except (RetryableError, ValueError) as error:
                if cancel.is_set():
                    return
                if attempt < 3 and deadline-time.monotonic() > .6:
                    self.emit('retry', text=f'自动重试 {attempt+1}/3 · {error}', generation=generation)
                    if cancel.wait(.12):
                        return
                else:
                    self.emit('failure', text=f'{error}；可点击手动重试', generation=generation)
                    return
            except Exception as error:
                self.emit('failure', text=str(error), generation=generation)
                return

    def retry_current(self, api, source=None, forced='auto', crop=True, file_path=None,hint_override=None):
        source=source or self.active_source
        if file_path:
            self.recognize_file(file_path, api, forced, force_api=True,hint_override=hint_override)
            return
        if not source:
            self.emit('error', text='请选择投屏窗口或先导入截图')
            return
        self._read_once(lambda: self.capture_source(source,crop), api, forced, True,hint_override,source)

    def _read_once(self, get_image, api, forced, force_api,hint_override=None,source=None):
        # A manual read is a transaction. Cancel the previous request immediately,
        # and discard any monitor OCR that began before this operation.
        with self.lock:
            if self.manual_busy:
                self.emit('status',text='正在重新读取，请等待本次读取完成')
                return
            self.manual_busy=True
            self.control_epoch+=1
            epoch=self.control_epoch
            self.generation+=1
            generation=self.generation
            self.cancel.set()
            self.uncertain=True
        self.emit('rereading',generation=generation,text='正在重新截取屏幕并识别；上一条答案仅供核对')
        result={}
        try:
            for attempt in range(1, 4):
                started = time.monotonic()
                try:
                    image = get_image()
                    self.emit('capture',image=image.copy(),source=source or {})
                    question, result = read_question(self.ocr, image, forced)
                    if question is None:
                        raise ValueError('未识别到完整题目')
                    fresh_visual=blank_visual(image,result) if question.kind=='blank' else None
                    with self.lock:
                        old=self.current_question
                        # Preserve only a previously confirmed on-screen hint for
                        # exactly the same static question while its input is typed.
                        if same_blank(old,question) and self.current_visual and self.current_visual['confirmed'] and fresh_visual and fresh_visual['typing'] and not fresh_visual['confirmed']:
                            question=replace(question,prefix=old.prefix,length=old.length,prefix_alternatives=old.prefix_alternatives)
                    # Only explicitly supplied values override this freshly read
                    # question. Overrides never mutate global defaults or cache keys.
                    values={}
                    override=hint_override or {}
                    prefix=override.get('prefix')
                    length=override.get('length')
                    if prefix:
                        if not re.fullmatch('[A-Za-z]{1,30}',str(prefix).strip()):
                            raise ValueError('手动前缀只能包含英文字母')
                        values['prefix']=str(prefix).strip().lower()
                        values['prefix_alternatives']=()
                    if length not in (None,''):
                        length=int(length)
                        if not 1<=length<=40:
                            raise ValueError('手动长度应为1至40')
                        values['length']=length
                    if values and question.kind=='blank':
                        question=replace(question,**values)
                        if question.length and len(question.prefix)>question.length:
                            raise ValueError('前缀不能长于总长度')
                    with self.lock:
                        if epoch!=self.control_epoch:
                            return
                        self.uncertain=False
                        self.current_visual=fresh_visual
                        self.submit(question, api, started, result.get('ocr_ms', 0), repeat=True, force_api=force_api)
                        self.manual_hint=bool(values)
                    return
                except Exception as error:
                    if attempt == 3:
                        raise
                    self.emit('status', text=f'自动重试识别 {attempt+1}/3 · {error}')
                    time.sleep(.12)
        except Exception as error:
            with self.lock:
                if epoch!=self.control_epoch:
                    return
                self.current_question=None
                self.current_key=None
                self.current_visual=None
                self.uncertain=True
            self.emit('unread',text=result.get('text',''),ocr_ms=result.get('ocr_ms',0),generation=generation,previous=True)
            self.emit('failure', text=f'重新读取失败：{error}；未使用上一题，调整范围后重试',generation=generation,previous=True)
        finally:
            with self.lock:
                if epoch==self.control_epoch:
                    self.manual_busy=False

    def recognize_file(self, path: str, api=None, forced='auto', force_api=False,hint_override=None):
        def get_image():
            with Image.open(path) as picture:
                return prepare(picture.convert('RGB'))
        self._read_once(get_image, api, forced, force_api,hint_override)
