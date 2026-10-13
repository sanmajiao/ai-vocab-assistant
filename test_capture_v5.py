"""Native capture and Tk UI checks; only creates our own test windows.

No API, credentials, phone actions, or unrelated desktop screenshot is saved.
"""
import ctypes
import json
import os
import tempfile
import threading
import time
import tkinter as tk
import unittest
from pathlib import Path
from unittest.mock import patch

from PIL import Image
import app as module
from core import Question, validate_answer
from windows import capture,client_bbox,dpi_aware,resolve_bbox,user32,window_pid

ROOT=Path(__file__).resolve().parent
dpi_aware()
user32.GetParent.argtypes=[ctypes.c_void_p]
user32.GetParent.restype=ctypes.c_void_p

def native_id(widget):
    return user32.GetParent(widget.winfo_id()) or widget.winfo_id()


class CaptureChecks(unittest.TestCase):
    def setUp(self):
        self.root=tk.Tk()
        self.root.withdraw()
        self.target=tk.Toplevel(self.root)
        self.target.title('词汇翻译助手 · 捕获检查窗口')
        self.target.geometry('340x400+650+50')
        self.target.configure(bg='#28a8b0')
        self.target.attributes('-topmost',True)
        self.target.update()
        self.target.lift()
        self.target.update()
        time.sleep(.08)
        self.hwnd=native_id(self.target)
        self.source={'hwnd':self.hwnd,'pid':window_pid(self.hwnd)}

    def tearDown(self):
        self.root.destroy()

    def test_visible_pixels_follow_move_and_resize(self):
        image=capture(**self.source)
        self.assertEqual(image.size,(340,400))
        self.assertEqual(image.getpixel((150,150)),(40,168,176))
        selected={**self.source,'crop_rel':(.1,.2,.8,.7)}
        before=resolve_bbox(**selected)
        image=capture(**selected)
        self.assertEqual(image.size,(238,200))
        self.target.geometry('400x500+680+80')
        self.target.update()
        time.sleep(.08)
        after=resolve_bbox(**selected)
        self.assertNotEqual(before,after)
        self.assertEqual(capture(**selected).size,(280,250))

    def test_reused_or_stale_window_is_rejected(self):
        with self.assertRaisesRegex(RuntimeError,'失效'):
            capture(hwnd=self.hwnd,pid=os.getpid()+100000)
        self.target.destroy()
        with self.assertRaisesRegex(RuntimeError,'关闭|失效'):
            capture(**self.source)

    def test_occlusion_rejected_and_fixed_bbox_explicit(self):
        blocker=tk.Toplevel(self.root)
        blocker.geometry('340x400+650+50')
        blocker.configure(bg='#bb3355')
        blocker.attributes('-topmost',True)
        blocker.update()
        blocker.lift()
        blocker.update()
        time.sleep(.08)
        with self.assertRaisesRegex(RuntimeError,'遮挡'):
            capture(**self.source)
        # An explicit screen selection intentionally reads visible pixels.
        box=client_bbox(self.hwnd)
        image=capture(bbox=box)
        self.assertEqual(image.getpixel((150,150)),(187,51,85))


class UIChecks(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory(dir=ROOT,prefix='verify-capture-v5-')
        self.data_patch=patch.object(module,'DATA',Path(self.temp.name))
        self.data_patch.start()
        self.root=tk.Tk()
        self.assistant=module.Assistant(self.root)
        self.root.title('词汇翻译助手 · 界面检查')
        self.root.geometry('590x620+10+30')
        self.root.attributes('-topmost',True)
        self.root.update()

    def tearDown(self):
        self.assistant.close()
        self.data_patch.stop()
        self.temp.cleanup()

    def pump(self,seconds=.1):
        until=time.monotonic()+seconds
        while time.monotonic()<until:
            self.root.update()
            time.sleep(.01)

    def test_manual_reread_background_hints_and_actual_preview(self):
        self.assistant.source={'bbox':[650,50,990,450]}
        self.assistant.manual_prefix.set('tr')
        self.assistant.manual_length.set('4')
        self.assistant.forced.set('填空')
        self.assistant.auto_crop.set(False)
        self.assistant.running=True
        calls=[]
        def retry(**kwargs):
            calls.append(kwargs)
            time.sleep(.35)
        with patch.object(self.assistant.pipeline,'retry_current',side_effect=retry):
            started=time.monotonic()
            self.assistant.retry()
            self.assertLess(time.monotonic()-started,.1)
            self.pump(.05)
            self.assertTrue(self.assistant.manual_thread.is_alive())
            self.assistant.manual_thread.join()
        self.assertEqual(calls[0]['hint_override'],{'prefix':'tr','length':4})
        self.assertEqual(calls[0]['source'],self.assistant.source)
        self.assertEqual(calls[0]['forced'],'blank')
        self.assertFalse(calls[0]['crop'])
        self.assertEqual(self.assistant.manual_prefix.get(),'')
        frame=Image.new('RGB',(360,420),'#28a8b0')
        self.assistant.events.put({'event':'capture','image':frame,'source':self.assistant.source})
        self.pump()
        self.assertEqual(self.assistant.preview_image.size,frame.size)
        self.assertEqual(self.assistant.preview_image.getpixel((10,10)),frame.getpixel((10,10)))
        self.assistant.running=False

    def test_previous_answer_and_tentative_hint_remain_clear(self):
        question=Question('blank','削减成本',context='____ the costs')
        answer=validate_answer(question,'{"answer":"reduce"}')
        self.assistant.events.put({'event':'question','question':question,'generation':1,'deadline':time.monotonic()+10})
        self.assistant.events.put({'event':'answer','answer':answer,'generation':1,'source':'测试接口','elapsed':.1,'ocr_ms':10,'tentative':True})
        self.pump()
        self.assistant.events.put({'event':'held','text':'输入时保留答案','generation':1,'previous':False,'answer_ready':True})
        self.pump()
        self.assertIn('未确认',self.assistant.answer_detail.get())
        self.assistant.events.put({'event':'held','text':'正在确认换题','generation':1,'previous':True})
        self.pump()
        self.assertEqual(self.assistant.answer_text.get(),'reduce')
        self.assertFalse(self.assistant.answer_current)
        self.assistant.copy_answer()
        self.assertIn('上一题',self.assistant.status.get())
        self.assistant.events.put({'event':'held','text':'确认仍是原题','generation':1,'previous':False,'answer_ready':True})
        self.pump()
        self.assertTrue(self.assistant.answer_current)
        self.assertIn('未确认',self.assistant.answer_detail.get())
        viewport=self.assistant.scroll_canvas
        self.assertGreater(viewport.bbox('all')[3],viewport.winfo_height())
        if self.assistant.overlay:
            self.assistant.overlay.withdraw()
        self.root.update_idletasks()
        capture(hwnd=native_id(self.root),pid=os.getpid()).save(ROOT/'verification-v5-ui.png')
        new_question=Question('blank','存放储存',context='____ away',prefix='st',length=5)
        self.assistant.events.put({'event':'question','question':new_question,'generation':2,'deadline':time.monotonic()+10})
        # A computed answer can be ready in the worker before its answer event
        # reaches Tk. The old displayed word must still remain uncopyable.
        self.assistant.events.put({'event':'held','text':'当前题已确认','generation':2,'previous':False,'answer_ready':True})
        self.pump()
        self.assertEqual(self.assistant.answer_text.get(),'reduce')
        self.assertFalse(self.assistant.answer_current)
        self.assertIn('上一题',self.assistant.answer_detail.get())

    def test_static_selection_confirmation_and_preview(self):
        target=tk.Toplevel(self.root)
        target.title('词汇翻译助手 · 框选检查目标')
        target.geometry('340x400+650+50')
        target.configure(bg='#28a8b0')
        target.attributes('-topmost',True)
        target.update()
        target.lift()
        self.pump(.1)
        hwnd=native_id(target)
        self.assistant.source={'hwnd':hwnd,'pid':os.getpid()}
        self.assistant.select_region()
        self.pump()
        selection=next(child for child in self.root.winfo_children()
                       if isinstance(child,tk.Toplevel) and child is not target)
        canvas=next(child for child in selection.winfo_children() if isinstance(child,tk.Canvas))
        canvas.event_generate('<ButtonPress-1>',x=30,y=120)
        canvas.event_generate('<B1-Motion>',x=290,y=370)
        canvas.event_generate('<ButtonRelease-1>',x=290,y=370)
        self.pump()
        # Selection stays visible until an explicit confirmation.
        self.assertTrue(selection.winfo_exists())
        capture(hwnd=native_id(selection),pid=os.getpid()).save(ROOT/'verification-v5-selection.png')
        selection.event_generate('<Return>')
        self.pump()
        self.assertIn('crop_rel',self.assistant.source)
        self.assertEqual(capture(**self.assistant.source).size,(260,250))
        self.assertFalse(self.assistant.auto_crop.get())
        self.assertTrue(self.assistant.preview.winfo_exists())
        self.assertEqual(self.assistant.preview_image.size,(260,250))


if __name__=='__main__':
    unittest.main(verbosity=2)
