import json
import sys
import tempfile
import threading
import time
import unittest
from pathlib import Path
from queue import Queue
from unittest.mock import patch

import httpx
from PIL import Image, ImageDraw

from core import Question, parse_question, validate_answer
from network import DeepSeek, Expired, RetryableError
from ocr import WindowsOCR
from pipeline import Pipeline, prepare, read_question
from windows import protect, unprotect

ROOT = Path(__file__).resolve().parent
sys.stdout.reconfigure(encoding='utf-8')


class Semantics(unittest.TestCase):
    def setUp(self):
        self.choice = Question('choice', 'n.区域;地带', ('version', 'zone', 'nest', 'employment'))
        self.blank = Question('blank', '满足于……', context='be con____ with ...', prefix='con', length=7)

    def test_choice_index_and_text(self):
        self.assertEqual(validate_answer(self.choice, '{"option":2,"answer":"zone"}').display(), '第 2 项 · zone')

    def test_index_mismatch_rejected(self):
        with self.assertRaises(ValueError):
            validate_answer(self.choice, '{"option":1,"answer":"zone"}')

    def test_boolean_index_rejected(self):
        with self.assertRaises(ValueError):
            validate_answer(self.choice, '{"option":true,"answer":"version"}')

    def test_fill_prefix_and_length(self):
        self.assertEqual(validate_answer(self.blank, '{"answer":"content"}').text, 'content')
        for text in ('happy', 'contentment', 'be content with', '<html>content</html>'):
            with self.assertRaises(ValueError):
                validate_answer(self.blank, json.dumps({'answer':text}))

    def test_cache_key_tracks_option_order_and_hint(self):
        self.assertNotEqual(self.choice.key, Question('choice', self.choice.prompt, tuple(reversed(self.choice.options))).key)
        self.assertNotEqual(self.blank.key, Question('blank', self.blank.prompt, context=self.blank.context, prefix='con', length=8).key)

    def test_streamed_api_request(self):
        def handle(request):
            body = json.loads(request.content)
            self.assertEqual(body['thinking'], {'type':'disabled'})
            self.assertTrue(body['stream'])
            self.assertEqual(body['max_tokens'], 80)
            data = '\n\n'.join('data: '+json.dumps({'choices':[{'delta':{'content':text}}]}) for text in ('{"option":', '2,"answer":"zone"}')) + '\n\ndata: [DONE]\n\n'
            return httpx.Response(200, content=data, headers={'content-type':'text/event-stream'})
        api = DeepSeek('test-not-a-real-key', transport=httpx.MockTransport(handle))
        try:
            self.assertEqual(api.answer(self.choice, time.monotonic()+4, threading.Event()).option, 2)
        finally:
            api.close()

    def test_api_status_without_secret_echo(self):
        for status in (401, 402, 429, 500):
            api = DeepSeek('sensitive-test-value', transport=httpx.MockTransport(lambda r: httpx.Response(status, content='sensitive-test-value')))
            try:
                with self.assertRaises(RuntimeError) as error:
                    api.answer(self.choice, time.monotonic()+4, threading.Event())
                self.assertNotIn('sensitive-test-value', str(error.exception))
            finally:
                api.close()

    def test_expired_request_never_sent(self):
        def handle(request):
            self.fail('Expired request must not reach transport')
        api = DeepSeek('test', transport=httpx.MockTransport(handle))
        try:
            with self.assertRaises(Expired):
                api.answer(self.choice, time.monotonic()-1, threading.Event())
        finally:
            api.close()

    def test_cancel_before_request(self):
        event = threading.Event()
        event.set()
        api = DeepSeek('test', transport=httpx.MockTransport(lambda r: self.fail('Canceled request reached transport')))
        try:
            with self.assertRaises(Expired):
                api.answer(self.choice, time.monotonic()+3, event)
        finally:
            api.close()

    def test_late_old_answer_is_not_shown_or_cached(self):
        ready, release = threading.Event(), threading.Event()
        class SlowAPI:
            def answer(self, question, deadline, cancel):
                ready.set()
                release.wait(2)
                return validate_answer(question, '{"option":2,"answer":"zone"}')
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            events = Queue()
            pipeline = Pipeline(ROOT, Path(directory), events)
            pipeline.submit(self.choice, SlowAPI(), time.monotonic())
            self.assertTrue(ready.wait(1))
            pipeline.invalidate()
            release.set()
            time.sleep(.08)
            items = []
            while not events.empty():
                items.append(events.get())
            self.assertNotIn('answer', [item['event'] for item in items])
            self.assertFalse(pipeline.cache)
            pipeline.close()

    def test_dpapi_round_trip(self):
        value = protect('local-test-only')
        self.assertNotIn('local-test-only', value)
        self.assertEqual(unprotect(value), 'local-test-only')

    def test_three_choice_bounds(self):
        question = Question('choice', '易冲动的', ('relative', 'sophisticated', 'impulsive'))
        self.assertEqual(validate_answer(question, '{"option":3,"answer":"impulsive"}').option, 3)
        with self.assertRaises(ValueError):
            validate_answer(question, '{"option":4,"answer":"impulsive"}')

    def test_transient_api_failure_retries_same_deadline(self):
        done = threading.Event()
        class RecoveringAPI:
            calls = []
            def answer(inner, question, deadline, cancel):
                inner.calls.append(deadline)
                if len(inner.calls) == 1:
                    raise RetryableError('temporary test failure')
                done.set()
                return validate_answer(question, '{"option":2,"answer":"zone"}')
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline = Pipeline(ROOT, Path(directory), Queue())
            api = RecoveringAPI()
            pipeline.submit(self.choice, api, time.monotonic())
            self.assertTrue(done.wait(2))
            pipeline.pool.shutdown(wait=True)
            self.assertEqual(len(api.calls), 2)
            self.assertEqual(api.calls[0], api.calls[1])
            self.assertIn(self.choice.key, pipeline.cache)
            pipeline.close()

    def test_permanent_api_failure_not_retried(self):
        class BrokenAPI:
            calls = 0
            def answer(inner, *args):
                inner.calls += 1
                raise RuntimeError('API Key 无效')
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline = Pipeline(ROOT, Path(directory), Queue())
            api = BrokenAPI()
            pipeline.submit(self.choice, api, time.monotonic())
            pipeline.pool.shutdown(wait=True)
            self.assertEqual(api.calls, 1)
            pipeline.close()

    def test_manual_retry_bypasses_cache(self):
        class FreshAPI:
            calls = 0
            def answer(inner, question, *args):
                inner.calls += 1
                return validate_answer(question, '{"option":2,"answer":"zone"}')
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline = Pipeline(ROOT, Path(directory), Queue())
            pipeline.cache[self.choice.key] = {'option':2,'answer':'zone'}
            api = FreshAPI()
            with patch('pipeline.capture', return_value=Image.new('RGB', (1080, 900))), patch('pipeline.read_question', return_value=(self.choice, {'ocr_ms':1})), patch.object(pipeline.ocr, 'start'):
                pipeline.retry_current(api, source={'hwnd':1})
            pipeline.pool.shutdown(wait=True)
            self.assertEqual(api.calls, 1)
            pipeline.close()

    def test_animation_does_not_cancel_same_question(self):
        ready, release, done = threading.Event(), threading.Event(), threading.Event()
        frames = [Image.new('RGB',(1080,900), color) for color in ('white', '#dddddd')]
        class WaitingAPI:
            calls = 0
            def answer(inner, question, deadline, cancel):
                inner.calls += 1
                ready.set()
                release.wait(2)
                self.assertFalse(cancel.is_set())
                done.set()
                return validate_answer(question, '{"option":2,"answer":"zone"}')
        counter = [0]
        def frame(*args,**kwargs):
            counter[0] += 1
            return frames[counter[0] % 2]
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline = Pipeline(ROOT, Path(directory), Queue())
            api = WaitingAPI()
            with patch('pipeline.capture', side_effect=frame), patch('pipeline.read_question', return_value=(self.choice, {'ocr_ms':1})), patch.object(pipeline.ocr, 'start'):
                pipeline.start(api, {'hwnd':1})
                self.assertTrue(ready.wait(1))
                time.sleep(.4)
                release.set()
                self.assertTrue(done.wait(1))
                pipeline.halt()
                pipeline.thread.join(2)
            self.assertEqual(api.calls, 1)
            pipeline.close()

    def test_ocr_timeout_automatically_recovers(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            events = Queue()
            pipeline = Pipeline(ROOT, Path(directory), events)
            with patch.object(pipeline.ocr, 'start'), patch('pipeline.read_question', side_effect=[TimeoutError('OCR test timeout'), (self.choice, {'ocr_ms':1})]):
                pipeline._read_once(lambda: Image.new('RGB',(1080,900)), None, 'auto', False)
            kinds = [events.get()['event'] for _ in range(events.qsize())]
            self.assertIn('question', kinds)
            self.assertNotIn('error', kinds)
            pipeline.close()

    def test_focus_movement_preserves_confirmed_blank(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline = Pipeline(ROOT, Path(directory), Queue())
            pipeline.submit(self.blank, None, time.monotonic())
            generation = pipeline.generation
            moved = Question('blank', self.blank.prompt, context='be ____ with ...')
            pipeline.submit(moved, None, time.monotonic())
            self.assertEqual(pipeline.generation, generation)
            self.assertEqual(pipeline.current_question.prefix, 'con')
            self.assertEqual(pipeline.current_question.length, 7)
            pipeline.close()

    def test_incomplete_choices_do_not_become_blank(self):
        image = Image.new('RGB',(1080,900),'#f6f6f6')
        draw = ImageDraw.Draw(image)
        for y in (230,380,530,680):
            draw.rounded_rectangle((40,y,1040,y+105),radius=8,outline='#dfe1e4',width=2)
        def row(text,x,y,width=200):
            return {'text':text,'words':[{'text':text,'x':x,'y':y,'width':width,'height':40}]}
        for lines in ([row('impulsive',350,70),row('冲动的',400,260),row('相对的',400,410)],
                      [row('易冲动的',350,70),row('relative',400,260),row('impulsive',400,410)]):
            self.assertIsNone(parse_question({'lines':lines},image))

    def test_english_prompt_with_chinese_choices(self):
        def row(text,y):
            return {'text':text,'words':[{'text':text,'x':300,'y':y,'width':250,'height':40}]}
        question=parse_question({'lines':[row('impulsive',50),row('相对的',200),row('成熟的',350),row('冲动的',500)]})
        self.assertEqual(question.kind,'choice')
        self.assertEqual(len(question.options),3)

    def test_ambiguous_pale_hint_keeps_bounded_candidates(self):
        question=Question('blank','冲动的决定',context='an ____ decision',prefix='imp',length=9,prefix_alternatives=('lrnp',))
        self.assertEqual(validate_answer(question,'{"answer":"impulsive"}').text,'impulsive')
        with self.assertRaises(ValueError):
            validate_answer(question,'{"answer":"importance"}')

    def test_occluded_definition_does_not_restart_same_blank(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline=Pipeline(ROOT,Path(directory),Queue())
            question=Question('blank','势均力敌的对手',context='a worthy ____',prefix='opp',length=8)
            pipeline.submit(question,None,time.monotonic())
            generation=pipeline.generation
            moved=Question('blank','势均力敌',context='a worthy ____',prefix='opp',length=8)
            pipeline.observe(moved,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            pipeline.close()

    def test_typing_all_letters_keeps_existing_answer(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            events=Queue()
            pipeline=Pipeline(ROOT,Path(directory),events)
            original=Question('blank','势均力敌的对手',context='a worthy ____',prefix='opp',length=8)
            pipeline.cache[original.key]={'answer':'opponent'}
            pipeline.submit(original,None,time.monotonic())
            generation=pipeline.generation
            while not events.empty(): events.get()
            for text in ('','o','op','opp','oppo','oppon','oppone','opponen','opponent','oppox'):
                typed=Question('blank',original.prompt,context=original.context,prefix=text)
                pipeline.observe(typed,None,time.monotonic())
                self.assertEqual(pipeline.generation,generation)
            kinds=[events.get()['event'] for _ in range(events.qsize())]
            self.assertTrue(all(kind=='held' for kind in kinds))
            pipeline.close()

    def test_real_new_question_needs_three_observations(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            events=Queue()
            pipeline=Pipeline(ROOT,Path(directory),events)
            pipeline.submit(self.blank,None,time.monotonic())
            generation=pipeline.generation
            pipeline.observe(self.choice,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            pipeline.observe(self.blank,None,time.monotonic())
            pipeline.observe(self.choice,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            pipeline.observe(self.choice,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            pipeline.observe(self.choice,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation+1)
            self.assertEqual(pipeline.current_question.kind,'choice')
            pipeline.close()

    def test_manual_retry_can_replace_pinned_blank(self):
        class API:
            calls=0
            def answer(inner,question,*args):
                inner.calls+=1
                return validate_answer(question,'{"answer":"opponent"}')
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline=Pipeline(ROOT,Path(directory),Queue())
            original=Question('blank','势均力敌的对手',context='a worthy ____',prefix='opp',length=8)
            pipeline.cache[original.key]={'answer':'opponent'}
            pipeline.submit(original,None,time.monotonic())
            api=API()
            typed=Question('blank',original.prompt,context=original.context,prefix='opponent')
            pipeline.observe(typed,api,time.monotonic(),repeat=True,force_api=True)
            pipeline.pool.shutdown(wait=True)
            self.assertEqual(api.calls,1)
            self.assertEqual(pipeline.current_question.prefix,'opponent')
            pipeline.close()

    def test_short_occluded_definition_keeps_blank(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline=Pipeline(ROOT,Path(directory),Queue())
            pipeline.submit(self.blank,None,time.monotonic())
            generation=pipeline.generation
            partial=Question('blank','满足',context='be ____ with ...',prefix='content')
            pipeline.observe(partial,None,time.monotonic())
            pipeline.observe(partial,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            pipeline.close()

    def test_paused_monitor_cannot_submit_another_request(self):
        with tempfile.TemporaryDirectory(dir=ROOT) as directory:
            pipeline=Pipeline(ROOT,Path(directory),Queue())
            pipeline.halt()
            generation=pipeline.generation
            pipeline.observe(self.choice,None,time.monotonic())
            self.assertEqual(pipeline.generation,generation)
            self.assertIsNone(pipeline.current_question)
            pipeline.close()

if __name__ == "__main__":
    unittest.main()
