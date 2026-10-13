"""State regressions. Fake API/capture only; never reads user credentials."""
from pathlib import Path
from queue import Queue
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw
from core import Question, Answer
from pipeline import Pipeline, same_blank, blank_visual, visual_distance
from windows import SourceClosedError

ROOT=Path(__file__).parent


def evidence(ink=0,typing=False,confirmed=False):
    image=Image.new('L',(96,24),ink)
    return {'outside':[image,image], 'prompt':image,'typing':typing,'confirmed':confirmed}


class API:
    label='测试接口'
    def __init__(self):
        self.questions=[]
    def answer(self,question,deadline,cancel):
        self.questions.append(question)
        return Answer('trim' if question.prompt=='削减成本' else 'pack')


class StateV5(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT)
        self.events=Queue()
        self.pipe=Pipeline(ROOT,Path(self.temp.name),self.events)
        self.old=Question('blank','把东西包装好',context='____ sth. up',prefix='pac',length=4)
        self.new=Question('blank','削减成本',context='____ the costs',prefix='tr',length=4)
        self.pipe.submit(self.old,None,time.monotonic())
        self.pipe.current_visual=evidence(0,confirmed=True)
        self.generation=self.pipe.generation
    def tearDown(self):
        self.pipe.close()
        self.temp.cleanup()
    def messages(self):
        out=[]
        while not self.events.empty():
            out.append(self.events.get_nowait())
        return out
    def test_partial_containment_is_not_same_question(self):
        self.assertFalse(same_blank(self.old,Question('blank','包装',context='____ sth. up')))
    def test_repeated_tooltip_ocr_change_during_typing_never_resubmits(self):
        corrupted=Question('blank','天字包装仔',context='____ sth. up',prefix='pack')
        obscured=evidence(0,typing=True)
        obscured['prompt']=Image.new('L',(96,24),255)
        with patch('pipeline.blank_visual',return_value=obscured):
            for _ in range(8):
                self.pipe.observe(corrupted,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={})
        self.assertEqual(self.pipe.generation,self.generation)
        self.assertEqual(self.pipe.current_question.prefix,'pac')
        self.assertEqual(self.pipe.current_question.length,4)
        self.assertTrue(any(e.get('previous') for e in self.messages() if e['event']=='held'))
    def test_stable_pixels_with_different_ocr_are_not_a_new_question(self):
        corrupted=Question('blank','包装好东西',context='____ stli. up')
        with patch('pipeline.blank_visual',return_value=evidence(0)):
            for _ in range(5):
                self.pipe.observe(corrupted,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={})
        self.assertEqual(self.pipe.generation,self.generation)
    def test_true_new_static_question_requires_three_confirmations(self):
        with patch('pipeline.blank_visual',return_value=evidence(255,confirmed=True)):
            for _ in range(2):
                self.pipe.observe(self.new,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={})
                self.assertEqual(self.pipe.generation,self.generation)
            self.pipe.observe(self.new,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={})
        self.assertEqual(self.pipe.current_question,self.new)
    def test_manual_retry_recaptures_and_bypasses_cache(self):
        api=API()
        self.pipe.cache[self.new.key]={'answer':'trim'}
        image=Image.new('RGB',(1080,900),'white')
        with patch('pipeline.capture',return_value=image) as camera,patch('pipeline.read_question',return_value=(self.new,{'text':'new','ocr_ms':50})):
            self.pipe.retry_current(api,{'hwnd':123,'pid':77,'crop_rel':[0,0,1,1]})
        self.pipe.pool.shutdown(wait=True)
        camera.assert_called_once_with(hwnd=123,pid=77,crop_rel=[0,0,1,1])
        self.assertEqual(api.questions,[self.new])
        self.assertEqual(self.pipe.current_question,self.new)
        self.assertTrue(any(e['event']=='capture' for e in self.messages()))
    def test_manual_failure_does_not_reuse_old_question(self):
        api=API()
        with patch('pipeline.capture',return_value=Image.new('RGB',(1080,900))),patch('pipeline.read_question',return_value=(None,{'text':'遮挡残缺'})):
            self.pipe.retry_current(api,{'hwnd':123})
        self.assertIsNone(self.pipe.current_question)
        self.assertEqual(api.questions,[])
        self.assertTrue(any(e['event']=='failure' and e.get('previous') for e in self.messages()))
    def test_manual_prefix_length_is_applied_only_to_one_read(self):
        unconstrained=Question('blank','削减成本',context='____ the costs')
        api=API()
        with patch('pipeline.capture',return_value=Image.new('RGB',(1080,900))),patch('pipeline.read_question',return_value=(unconstrained,{'text':'new'})):
            self.pipe.retry_current(api,{'hwnd':123},hint_override={'prefix':'tr','length':4})
        self.assertEqual(self.pipe.current_question.prefix,'tr')
        self.assertEqual(self.pipe.current_question.length,4)
        other=Question('blank','议员',context='a member of ____')
        self.pipe.observe(other,None,time.monotonic(),repeat=True)
        self.assertEqual(self.pipe.current_question.prefix,'')
        self.assertIsNone(self.pipe.current_question.length)
    def test_manual_epoch_discards_a_monitor_read_that_started_earlier(self):
        blocking=threading.Event()
        entered=threading.Event()
        api=API()
        image=Image.new('RGB',(1080,900))
        def read(engine,im,forced):
            if threading.current_thread() is self.pipe.thread:
                entered.set()
                blocking.wait(3)
                return self.old,{'text':'old'}
            return self.new,{'text':'new'}
        with patch('pipeline.capture',return_value=image),patch('pipeline.read_question',side_effect=read):
            self.pipe.start(api,{'hwnd':123})
            self.assertTrue(entered.wait(2))
            self.pipe.retry_current(api)
            self.assertEqual(self.pipe.current_question,self.new)
            self.pipe.stop.set()
            blocking.set()
            self.pipe.thread.join(3)
        self.pipe.pool.shutdown(wait=True)
        self.assertEqual(api.questions,[self.new])
        self.assertEqual(self.pipe.current_question,self.new)
    def test_missing_new_screen_is_marked_previous_instead_of_current(self):
        with patch('pipeline.capture',return_value=Image.new('RGB',(1080,900))),patch('pipeline.read_question',return_value=(None,{'text':'残缺题'})):
            self.pipe.start(None,{'hwnd':123})
            time.sleep(.35)
            self.pipe.stop.set()
            self.pipe.thread.join(3)
        self.assertTrue(any(e['event']=='held' and e.get('previous') for e in self.messages()))
    def test_confirmed_hint_is_preserved_when_manual_read_occurs_mid_input(self):
        typed=Question('blank',self.old.prompt,context=self.old.context)
        with patch('pipeline.capture',return_value=Image.new('RGB',(1080,900))),patch('pipeline.read_question',return_value=(typed,{})),patch('pipeline.blank_visual',return_value=evidence(0,typing=True)):
            self.pipe.retry_current(None,{'hwnd':123})
        self.assertEqual(self.pipe.current_question.prefix,'pac')
        self.assertEqual(self.pipe.current_question.length,4)
    def test_visual_comparison_uses_local_rows_after_resize(self):
        # Geometric row crops remove window margins and keyboard offsets.
        def screen(scale,dy):
            image=Image.new('RGB',(600*scale,600*scale),'white')
            draw=ImageDraw.Draw(image)
            draw.line((120*scale,(100+dy)*scale,300*scale,(100+dy)*scale),fill='#777777',width=scale)
            draw.rectangle((330*scale,(65+dy)*scale,410*scale,(87+dy)*scale),fill='black')
            word={'text':'up','x':330*scale,'y':(65+dy)*scale,'width':80*scale,'height':22*scale}
            return image,{'lines':[{'words':[word]}]}
        original=blank_visual(*screen(1,0))
        moved=blank_visual(*screen(2,40))
        self.assertIsNotNone(original)
        self.assertLess(visual_distance(original,moved),9)

    def test_later_original_hint_corrects_an_unconstrained_candidate(self):
        incomplete=Question('blank','把东西包装好',context='____ sth. up')
        self.pipe.submit(incomplete,None,time.monotonic(),repeat=True)
        before=self.pipe.generation
        visual=evidence(0,confirmed=True)
        with patch('pipeline.blank_visual',return_value=visual):
            self.pipe.observe(self.old,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={'hint_confirmed':True})
        self.assertEqual(self.pipe.generation,before+1)
        self.assertEqual(self.pipe.current_question.prefix,'pac')

    def test_new_chinese_and_new_pale_hint_override_typing_signal(self):
        other=Question('blank','从事体力工作',context='____ sth. up',prefix='la',length=6)
        visual=evidence(0,typing=True,confirmed=True)
        visual['prompt']=Image.new('L',(96,24),255)
        with patch('pipeline.blank_visual',return_value=visual):
            for _ in range(3):
                self.pipe.observe(other,None,time.monotonic(),image=Image.new('RGB',(10,10)),result={'hint_confirmed':True})
        self.assertEqual(self.pipe.current_question,other)

    def test_unconstrained_guess_bypasses_old_cache_and_is_not_persisted(self):
        question=Question('blank','削减成本',context='____ the costs')
        self.pipe.cache[question.key]={'answer':'reduce'}
        api=API()
        self.pipe.submit(question,api,time.monotonic(),repeat=True)
        self.pipe.pool.shutdown(wait=True)
        self.assertEqual(api.questions,[question])
        self.assertEqual(self.pipe.cache[question.key],{'answer':'reduce'},'Existing user cache remains untouched')
        self.assertFalse((Path(self.temp.name)/'answers.json').exists())
        self.assertTrue(any(e.get('tentative') for e in self.messages() if e['event']=='answer'))

    def test_held_current_question_does_not_promote_previous_answer(self):
        # The UI retains the previous rendered answer while the new API is in
        # flight. Same-question observations must not mark that old text current.
        self.pipe.answered_generation=self.pipe.generation
        self.pipe.submit(self.new,None,time.monotonic(),repeat=True)
        self.pipe.observe(self.new,None,time.monotonic())
        held=[e for e in self.messages() if e['event']=='held'][-1]
        self.assertFalse(held['answer_ready'])
        self.assertTrue(held['previous'])

    def test_held_answer_is_current_only_after_matching_generation_reply(self):
        self.pipe.answered_generation=self.pipe.generation
        self.pipe.observe(self.old,None,time.monotonic())
        held=[e for e in self.messages() if e['event']=='held'][-1]
        self.assertTrue(held['answer_ready'])
        self.assertFalse(held.get('previous',False))

    def test_closed_source_stops_monitor_instead_of_recapturing_other_window(self):
        with patch('pipeline.capture',side_effect=SourceClosedError('窗口已关闭')) as camera:
            self.pipe.start(None,{'hwnd':123,'pid':77})
            self.pipe.thread.join(3)
        self.assertFalse(self.pipe.thread.is_alive())
        self.assertTrue(self.pipe.stop.is_set())
        camera.assert_called_once()
        self.assertTrue(any(e['event']=='error' and '识别已停止' in e['text'] for e in self.messages()))


if __name__=='__main__':
    unittest.main(verbosity=2)
