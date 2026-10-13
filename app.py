from __future__ import annotations

import ctypes
import json
import os
import queue
import sys
import threading
import time
import tkinter as tk
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from PIL import ImageGrab, ImageTk

from network import DeepSeek
from core import APP_VERSION
from pipeline import Pipeline
from windows import application_windows, capture, client_bbox, dpi_aware, protect, resolve_bbox, unprotect, user32, window_pid


ROOT = Path(__file__).resolve().parent
DATA = ROOT / 'data'
BG, FG, BLUE, MUTED = '#f4f7fb', '#17263d', '#2465e8', '#66768a'


class Assistant:
    def __init__(self, root):
        self.root = root
        root.title('词汇翻译助手 · '+APP_VERSION)
        available_height = max(520,root.winfo_screenheight()-100)
        root.geometry(f'590x{min(930,available_height)}')
        root.minsize(500,min(740,available_height))
        root.configure(bg=BG)
        if (ROOT / 'app.ico').exists():
            root.iconbitmap(str(ROOT / 'app.ico'))
        DATA.mkdir(parents=True, exist_ok=True)
        self.events = queue.Queue()
        self.pipeline = Pipeline(ROOT, DATA, self.events)
        self.api = None
        self.old_apis = []
        self.key = ''
        self.source = {}
        self.window_map = {}
        self.running = False
        self.last_import_path = None
        self.manual_thread = None
        self.generation = -1
        self.deadline = None
        self.question = None
        self.answer = None
        self.answer_current = False
        self.answer_tentative = False
        self.answer_generation = -1
        self.overlay = None
        self.overlay_answer = None
        self.overlay_detail = None
        self.preview = None
        self.preview_image = None
        self.preview_label = None
        self.preview_photo = None
        self.capture_detail = tk.StringVar(value='尚未选择画面 · 可使用其他投屏软件')
        self.preview_state = tk.StringVar(value='还没有成功截取画面')
        self.manual_prefix = tk.StringVar()
        self.manual_length = tk.StringVar()
        self.forced = tk.StringVar(value='自动')
        self.model = tk.StringVar(value='deepseek-flash')
        self.key_entry = tk.StringVar()
        self.source_name = tk.StringVar()
        self.status = tk.StringVar(value='先保存 API Key，再选择手机投屏窗口')
        self.timer = tk.StringVar(value='先准确识别题目，再等待翻译；请留意手机倒计时')
        self.answer_text = tk.StringVar(value='等待题目')
        self.answer_detail = tk.StringVar(value='答案显示后，由你核对并操作手机')
        self.show_overlay = tk.BooleanVar(value=True)
        self.auto_crop = tk.BooleanVar(value=True)
        self._load_settings()
        self._build()
        self.refresh_windows()
        root.protocol('WM_DELETE_WINDOW', self.close)
        root.bind('<F8>', lambda e: self.toggle())
        root.after(50, self.poll)

    def _load_settings(self):
        try:
            settings = json.loads((DATA / 'settings.json').read_text(encoding='utf-8'))
            self.model.set(settings.get('model', 'deepseek-flash'))
            if settings.get('key_protected'):
                self.key = unprotect(settings['key_protected'])
                self.api = DeepSeek(self.key, self.model.get())
                self.status.set('已读取本机保存的 API 配置')
        except Exception:
            self.status.set('请填写 API Key；已有配置无法读取时可重新保存')

    def _build(self):
        style = ttk.Style()
        style.theme_use('clam')
        style.configure('TFrame', background=BG)
        style.configure('TLabel', background=BG, foreground=FG, font=('Microsoft YaHei UI', 10))
        style.configure('TButton', font=('Microsoft YaHei UI', 10), padding=(9, 6))
        style.configure('TEntry', padding=5)
        style.configure('Accent.TButton', background=BLUE, foreground='white')
        style.map('Accent.TButton', background=[('active', '#1d54c4')])
        # Keep every control reachable on small laptop screens and larger DPI.
        scroll = tk.Canvas(self.root,bg=BG,highlightthickness=0)
        scrollbar = ttk.Scrollbar(self.root,orient='vertical',command=scroll.yview)
        scrollbar.pack(side='right',fill='y')
        scroll.pack(side='left',fill='both',expand=True)
        scroll.configure(yscrollcommand=scrollbar.set)
        outer = ttk.Frame(scroll,padding=14)
        window = scroll.create_window(0,0,window=outer,anchor='nw')
        outer.bind('<Configure>',lambda event: scroll.configure(scrollregion=scroll.bbox('all')))
        scroll.bind('<Configure>',lambda event: scroll.itemconfigure(window,width=event.width))
        def wheel(event):
            if event.widget.winfo_toplevel() == self.root and event.widget != self.question_box:
                scroll.yview_scroll(-int(event.delta/120),'units')
        self.root.bind_all('<MouseWheel>',wheel,add='+')
        self.scroll_canvas = scroll
        ttk.Label(outer, text='词汇翻译助手', font=('Microsoft YaHei UI', 23, 'bold')).pack(anchor='w')
        ttk.Label(outer, text='读取手机投屏 · 翻译候选 · '+APP_VERSION, foreground=MUTED).pack(anchor='w', pady=(3, 16))
        tabs = ttk.Notebook(outer)
        tabs.pack(fill='x')
        setup = ttk.Frame(tabs, padding=14)
        api_tab = ttk.Frame(tabs, padding=14)
        tabs.add(setup, text='识别设置')
        tabs.add(api_tab, text='DeepSeek API')
        ttk.Label(setup, text='读取来源（任意投屏软件或应用窗口）').pack(anchor='w')
        row = ttk.Frame(setup)
        row.pack(fill='x', pady=(5, 7))
        self.source_combo = ttk.Combobox(row, textvariable=self.source_name, state='readonly')
        self.source_combo.pack(side='left', fill='x', expand=True)
        self.source_combo.bind('<<ComboboxSelected>>', lambda e: self.choose_window())
        ttk.Button(row, text='刷新', command=self.refresh_windows).pack(side='left', padx=(6, 0))
        row = ttk.Frame(setup)
        row.pack(fill='x')
        ttk.Button(row, text='框选题目区域', command=self.select_region).pack(side='left')
        ttk.Button(row, text='框选屏幕', command=lambda: self.select_region(desktop=True)).pack(side='left', padx=5)
        ttk.Button(row, text='画面预览', command=self.show_preview).pack(side='left')
        row = ttk.Frame(setup)
        row.pack(fill='x', pady=(5,0))
        self.import_button = ttk.Button(row, text='导入完整截图', command=self.import_image)
        self.import_button.pack(side='left')
        ttk.Button(row,text='本机识别检查',command=self.local_check).pack(side='left',padx=5)
        ttk.Label(setup, textvariable=self.capture_detail, foreground=MUTED, wraplength=490).pack(anchor='w', pady=(5,0))
        row = ttk.Frame(setup)
        row.pack(fill='x', pady=(10, 0))
        ttk.Label(row, text='题型').pack(side='left')
        self.kind_combo = ttk.Combobox(row, values=['自动', '选择', '填空'], textvariable=self.forced, state='readonly', width=8)
        self.kind_combo.pack(side='left', padx=8)
        ttk.Checkbutton(row, text='显示悬浮答案', variable=self.show_overlay, command=self.toggle_overlay).pack(side='left', padx=10)
        self.crop_check = ttk.Checkbutton(setup, text='自动截取题目（兼容输入法弹出）', variable=self.auto_crop)
        self.crop_check.pack(anchor='w', pady=(8, 0))
        row = ttk.Frame(setup)
        row.pack(fill='x', pady=(8,0))
        ttk.Label(row, text='填空提示：前缀').pack(side='left')
        ttk.Entry(row, textvariable=self.manual_prefix, width=9).pack(side='left', padx=5)
        ttk.Label(row, text='总长度').pack(side='left')
        ttk.Entry(row, textvariable=self.manual_length, width=5).pack(side='left', padx=5)
        ttk.Button(row, text='重新读取并应用提示', command=self.retry).pack(side='left')
        ttk.Label(setup, text='留空表示未确认；提示只用于本次重读，换题后不会自动沿用。', foreground=MUTED, wraplength=490).pack(anchor='w', pady=(4,0))
        ttk.Label(api_tab, text='官方接口 · 密钥仅用于本程序请求', foreground=MUTED).pack(anchor='w')
        ttk.Label(api_tab, text='API Key（留空沿用已保存的密钥）').pack(anchor='w', pady=(10, 4))
        ttk.Entry(api_tab, textvariable=self.key_entry, show='●').pack(fill='x')
        row = ttk.Frame(api_tab)
        row.pack(fill='x', pady=10)
        ttk.Label(row, text='模型').pack(side='left')
        self.model_combo = ttk.Combobox(row, textvariable=self.model, values=['deepseek-flash', 'deepseek-v4-pro', 'deepseek-chat'], width=24)
        self.model_combo.pack(side='left', padx=8)
        row = ttk.Frame(api_tab)
        row.pack(fill='x')
        ttk.Button(row, text='加密保存配置', command=self.save_settings).pack(side='left')
        ttk.Button(row, text='测试连接', command=self.test_api).pack(side='left', padx=8)
        ttk.Label(api_tab, text='密钥用 Windows 加密保存；首次联网速度需实际测试。', foreground=MUTED, wraplength=420).pack(anchor='w', pady=(10, 0))
        controls = ttk.Frame(outer)
        controls.pack(fill='x', pady=14)
        self.start_button = ttk.Button(controls, text='开始识别  F8', style='Accent.TButton', command=self.toggle)
        self.start_button.pack(side='left', fill='x', expand=True)
        self.retry_button = ttk.Button(controls, text='手动重试', command=self.retry)
        self.retry_button.pack(side='left', padx=(8, 0))
        ttk.Button(controls, text='复制答案', command=self.copy_answer).pack(side='left', padx=(10, 0))
        card = tk.Frame(outer, background='white', highlightbackground='#dce5f2', highlightthickness=1, padx=16, pady=16)
        card.pack(fill='x')
        tk.Label(card, textvariable=self.answer_text, bg='white', fg=BLUE, font=('Microsoft YaHei UI', 23, 'bold'), wraplength=450, anchor='w', justify='left').pack(fill='x')
        tk.Label(card, textvariable=self.answer_detail, bg='white', fg=MUTED, font=('Microsoft YaHei UI', 10), wraplength=450, justify='left', anchor='w').pack(fill='x', pady=(7, 0))
        ttk.Label(outer, textvariable=self.timer, foreground=MUTED).pack(anchor='w', pady=(10, 12))
        ttk.Label(outer, text='当前识别内容', font=('Microsoft YaHei UI', 10, 'bold')).pack(anchor='w')
        ttk.Label(outer, textvariable=self.status, foreground=MUTED, wraplength=480).pack(side='bottom', anchor='w', fill='x')
        self.question_box = tk.Text(outer, height=6, font=('Microsoft YaHei UI', 11), wrap='word', bg='white', fg=FG, bd=0, padx=10, pady=10)
        self.question_box.pack(fill='both', expand=True, pady=(6, 10))
        self.set_question('这里显示题目和选项，方便核对 OCR 是否读对。')

    def set_question(self, text):
        self.question_box.configure(state='normal')
        self.question_box.delete('1.0', 'end')
        self.question_box.insert('1.0', text)
        self.question_box.configure(state='disabled')

    def refresh_windows(self):
        if self.running:
            return
        previous = self.source_name.get()
        self.window_map = {f'{title} · {hwnd}': hwnd for hwnd, title in application_windows()}
        self.source_combo['values'] = list(self.window_map)
        if previous in self.window_map:
            self.source_name.set(previous)
        elif self.window_map:
            self.source_name.set('请选择实际显示手机画面的窗口')
        else:
            self.source_name.set('未找到应用窗口，可框选屏幕')

    def choose_window(self):
        hwnd = self.window_map.get(self.source_name.get())
        if hwnd:
            self.last_import_path = None
            self.source = {'hwnd': hwnd, 'pid':window_pid(hwnd)}
            self.preview_image = None
            self.preview_state.set('来源已更换，请刷新并核对当前画面')
            if self.preview_label and self.preview and self.preview.winfo_exists():
                self.preview_label.configure(image='',text='来源已更换，请刷新并核对当前画面')
            self.auto_crop.set(True)
            self.capture_detail.set('读取来源：'+self.source_name.get()+'\n可见画面截图 · 跟随窗口移动；请移开遮挡')
            self.status.set('已选择窗口，请打开画面预览核对，再开始识别')

    def show_preview(self):
        if self.preview is None or not self.preview.winfo_exists():
            popup = tk.Toplevel(self.root)
            popup.title('实际读取画面 · 本地预览')
            popup.geometry('580x740+20+40')
            if self.source.get('hwnd'):
                try:
                    x0,y0,x1,y1 = client_bbox(self.source['hwnd'])
                    screen_width = user32.GetSystemMetrics(0)
                    if x0 >= 590:
                        x = x0-590
                    elif x1+590 <= screen_width:
                        x = x1+10
                    else:
                        # A large target leaves no side space. Show a smaller
                        # preview, warning the user if it still overlaps.
                        popup.geometry('380x500+10+10')
                        x = None
                    if x is not None:
                        popup.geometry(f'580x{min(740,self.root.winfo_screenheight()-80)}{x:+}{max(20,y0):+}')
                except RuntimeError:
                    pass
            self.preview = popup
            ttk.Label(popup, textvariable=self.capture_detail, wraplength=550, padding=10).pack(fill='x')
            ttk.Button(popup, text='刷新当前画面', command=self.refresh_preview).pack(pady=4)
            self.preview_label = ttk.Label(popup, anchor='center')
            self.preview_label.pack(fill='both', expand=True, padx=8,pady=8)
            ttk.Label(popup,textvariable=self.preview_state,foreground=MUTED,wraplength=550).pack(padx=10,pady=8)
            popup.bind('<Configure>',lambda event: self.draw_preview(self.preview_image,fresh=False) if event.widget == popup and self.preview_image is not None else None)
        self.preview.deiconify()
        self.preview.lift()
        if self.preview_image is not None:
            self.draw_preview(self.preview_image,fresh=False)
        if not self.running:
            self.refresh_preview()

    def draw_preview(self, image, fresh=True):
        self.preview_image = image.copy()
        if fresh:
            self.preview_state.set(f'最近一次成功截图 {time.strftime("%H:%M:%S")} · {image.width} × {image.height}\n运行时自动更新；预览只在本机显示。')
        if self.preview is None or not self.preview.winfo_exists():
            return
        sized = image.copy()
        sized.thumbnail((max(200,min(540,self.preview.winfo_width()-30)),max(200,min(580,self.preview.winfo_height()-150))))
        self.preview_photo = ImageTk.PhotoImage(sized)
        self.preview_label.configure(image=self.preview_photo, text='')

    def refresh_preview(self):
        try:
            if self.last_import_path:
                from PIL import Image
                image = Image.open(self.last_import_path).convert('RGB')
            else:
                image = capture(**self.source)
            self.draw_preview(image)
            self.status.set(f'画面预览已刷新 · {image.width} × {image.height}；请核对来源')
        except Exception as error:
            self.status.set(str(error) if isinstance(error,RuntimeError) else '无法截取画面，请重新选择来源')
            self.preview_state.set('当前画面未成功截取 · '+self.status.get())
            if self.preview_label:
                self.preview_label.configure(image='',text=self.status.get())

    def local_check(self):
        import subprocess
        path = ROOT/'local_check.py'
        if not path.exists():
            self.status.set('本机检查工具缺失，请使用完整程序包')
            return
        try:
            subprocess.Popen([sys.executable,str(path)],cwd=str(ROOT),creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            self.status.set('已打开本机识别检查；请先检查中英文，再核对画面预览')
        except OSError:
            self.status.set('本机检查无法启动，请检查程序包是否完整')

    def select_region(self, desktop=False):
        if self.running:
            self.status.set('请先暂停识别，再重新框选')
            return
        selected = dict(self.source) if self.source.get('hwnd') and not desktop else {}
        try:
            if selected:
                # Select from the whole chosen client area, independent of an old crop.
                selected.pop('crop_rel',None)
                shot = capture(**selected)
                left,top,right,bottom = client_bbox(selected['hwnd'])
            else:
                left,top = user32.GetSystemMetrics(76),user32.GetSystemMetrics(77)
                right,bottom = left+user32.GetSystemMetrics(78),top+user32.GetSystemMetrics(79)
                shot = capture(bbox=(left,top,right,bottom))
        except RuntimeError as error:
            self.status.set(str(error))
            return
        width,height = shot.size
        selection = tk.Toplevel(self.root)
        selection.overrideredirect(True)
        selection.attributes('-topmost', True)
        selection.geometry(f'{width}x{height}+0+0')
        canvas = tk.Canvas(selection, cursor='cross', highlightthickness=0)
        canvas.pack(fill='both', expand=True)
        picture = ImageTk.PhotoImage(shot)
        selection.picture = picture
        canvas.create_image(0,0,image=picture,anchor='nw')
        bar = tk.Frame(selection,bg='#17263d',padx=8,pady=6)
        info = tk.StringVar(value='拖动框选中文、英文与全部选项；Enter 确认 / Esc 取消')
        tk.Label(bar,textvariable=info,bg='#17263d',fg='white',wraplength=max(220,width-140)).pack()
        start = []
        rectangle = []
        chosen = []
        def down(event):
            start[:] = [event.x_root, event.y_root]
            chosen.clear()
            if rectangle:
                canvas.delete(rectangle[0])
            rectangle[:] = [canvas.create_rectangle(event.x, event.y, event.x, event.y, outline='#69a5ff', width=4)]
        def move(event):
            if start:
                canvas.coords(rectangle[0], start[0]-left, start[1]-top, event.x, event.y)
                info.set(f'范围：{abs(event.x_root-start[0])} × {abs(event.y_root-start[1])} 像素 · 松开后确认')
        def up(event):
            if not start:
                return
            chosen[:] = [max(left,min(start[0], event.x_root)),max(top,min(start[1], event.y_root)),min(right,max(start[0],event.x_root)),min(bottom,max(start[1],event.y_root))]
            info.set(f'已选 {chosen[2]-chosen[0]} × {chosen[3]-chosen[1]} 像素；点击确认或重新拖选')
        def confirm(event=None):
            if not chosen:
                return
            box = chosen
            if box[2]-box[0] < 100 or box[3]-box[1] < 80:
                self.status.set('框选区域过小，请重选')
                return
            if selected:
                self.source = {**selected,'crop_rel':[(box[0]-left)/width,(box[1]-top)/height,(box[2]-left)/width,(box[3]-top)/height]}
                detail = '窗口内框选 · 跟随窗口移动与缩放'
            else:
                self.source = {'bbox':list(box)}
                detail = f'固定屏幕范围 ({box[0]}, {box[1]})—({box[2]}, {box[3]}) · 移动画面后须重新框选'
            self.last_import_path = None
            self.auto_crop.set(False)
            self.capture_detail.set('读取来源：'+detail+f'\n已选 {box[2]-box[0]} × {box[3]-box[1]} 像素')
            self.draw_preview(shot.crop((box[0]-left,box[1]-top,box[2]-left,box[3]-top)))
            selection.destroy()
            self.status.set('范围已确认，请在预览中核对题目完整')
            # Show the frozen selection immediately without recapturing over the preview.
            if self.preview is None or not self.preview.winfo_exists():
                self.show_preview()
            else:
                self.preview.deiconify()
        buttons = tk.Frame(bar,bg='#17263d')
        buttons.pack()
        ttk.Button(buttons,text='确认范围',command=confirm).pack(side='left',padx=4)
        ttk.Button(buttons,text='取消',command=selection.destroy).pack(side='left',padx=4)
        canvas.create_window(width//2,5,window=bar,anchor='n')
        canvas.bind('<ButtonPress-1>', down)
        canvas.bind('<B1-Motion>', move)
        canvas.bind('<ButtonRelease-1>', up)
        selection.bind('<Escape>', lambda e: selection.destroy())
        selection.bind('<Return>',confirm)
        selection.update_idletasks()
        # Native absolute coordinates support monitors left/above the primary.
        user32.SetWindowPos(user32.GetAncestor(selection.winfo_id(),2),None,left,top,width,height,0x14)
        selection.focus_force()

    def save_settings(self):
        if self.running:
            self.status.set('请先暂停，再修改 API 配置')
            return False
        key = self.key_entry.get().strip() or self.key
        if not key:
            self.status.set('请在 DeepSeek API 页填写密钥')
            return False
        try:
            settings = {'model': self.model.get().strip() or 'deepseek-flash', 'key_protected': protect(key)}
            path = DATA / 'settings.json'
            temporary = path.with_suffix('.new')
            temporary.write_text(json.dumps(settings, ensure_ascii=False), encoding='utf-8')
            temporary.replace(path)
            self.key = key
            if self.api:
                self.old_apis.append(self.api)
            self.api = DeepSeek(key, settings['model'])
            self.key_entry.set('')
            self.status.set('API 配置已加密保存，可测试连接或开始识别')
            return True
        except Exception:
            self.status.set('配置保存失败，请检查程序目录是否可写')
            return False

    def test_api(self):
        if not self.save_settings():
            return
        api = self.api
        self.status.set('正在测试官方 API 连接…')
        def run():
            start = time.monotonic()
            try:
                models = api.models()
                self.events.put({'event': 'models', 'models': models, 'elapsed': time.monotonic()-start})
            except Exception as error:
                self.events.put({'event': 'error', 'text': str(error) if isinstance(error, RuntimeError) else 'API 测试失败，请检查网络'})
        threading.Thread(target=run, daemon=True).start()

    def kind(self):
        return {'自动': 'auto', '选择': 'choice', '填空': 'blank'}[self.forced.get()]

    def toggle(self):
        if self.running:
            self.pipeline.halt()
            self.start_button.configure(text='正在暂停…', state='disabled')
            return
        if not self.source:
            self.refresh_windows()
        if not self.source:
            self.status.set('请先从列表选择显示手机画面的窗口，或框选屏幕区域，再核对预览')
            return
        if self.key_entry.get().strip() and not self.save_settings():
            return
        self.pipeline.start(self.api, self.source, self.kind(), self.auto_crop.get())
        self.last_import_path = None
        self.running = True
        self.start_button.configure(text='暂停识别  F8')
        self.import_button.configure(state='disabled')
        self.source_combo.configure(state='disabled')
        self.kind_combo.configure(state='disabled')
        self.crop_check.configure(state='disabled')
        self.toggle_overlay()

    def retry(self):
        if self.manual_thread and self.manual_thread.is_alive():
            self.status.set('正在重新读取，请稍候…')
            return
        if not self.running and self.key_entry.get().strip() and not self.save_settings():
            return
        prefix = self.manual_prefix.get().strip().lower()
        length_text = self.manual_length.get().strip()
        if prefix and (not prefix.isascii() or not prefix.isalpha()):
            self.status.set('前缀请只填写英文字母，例如 tr')
            return
        if length_text and (not length_text.isdigit() or not 1 <= int(length_text) <= 45):
            self.status.set('总长度请填写 1—45，未确认时留空')
            return
        if prefix and length_text and len(prefix) > int(length_text):
            self.status.set('前缀长度超过总长度，请核对')
            return
        hint_override = {'prefix':prefix or None,'length':int(length_text) if length_text else None}
        self.status.set('正在重新截图并识别；人工提示只应用于本次题目')
        self.manual_thread = threading.Thread(target=self.pipeline.retry_current,
            kwargs={'api':self.api, 'source':dict(self.source), 'forced':self.kind(),
                    'crop':self.auto_crop.get(), 'file_path':None if self.running else self.last_import_path,
                    'hint_override':hint_override}, daemon=True)
        self.manual_thread.start()
        self.manual_prefix.set('')
        self.manual_length.set('')

    def import_image(self):
        if self.running or (self.pipeline.thread and self.pipeline.thread.is_alive()):
            self.status.set('请先暂停，再导入截图')
            return
        path = filedialog.askopenfilename(title='选择完整手机截图', initialdir=ROOT, filetypes=[('图片', '*.png *.jpg *.jpeg *.bmp')])
        if path:
            self.import_path(path)

    def import_path(self, path):
        self.last_import_path = path
        self.capture_detail.set('读取来源：导入图片 · '+Path(path).name)
        try:
            from PIL import Image
            self.draw_preview(Image.open(path).convert('RGB'))
        except OSError:
            pass
        self.pipeline.invalidate()
        self.status.set('正在识别截图…')
        threading.Thread(target=self.pipeline.recognize_file, args=(path, self.api, self.kind()), daemon=True).start()

    def toggle_overlay(self):
        if not self.show_overlay.get():
            if self.overlay:
                self.overlay.withdraw()
            return
        if self.overlay is None:
            popup = tk.Toplevel(self.root)
            popup.overrideredirect(True)
            popup.attributes('-topmost', True)
            popup.configure(bg='#17263d', padx=14, pady=12)
            popup.geometry('370x175+80+90')
            drag = tk.Label(popup, text='翻译候选  ·  拖动移动', bg='#17263d', fg='#a9bedc', font=('Microsoft YaHei UI', 9))
            drag.pack(anchor='w')
            origin = []
            def down(event):
                origin[:] = [event.x_root-popup.winfo_x(), event.y_root-popup.winfo_y()]
            def move(event):
                if origin:
                    popup.geometry(f'+{event.x_root-origin[0]}+{event.y_root-origin[1]}')
            drag.bind('<ButtonPress-1>', down)
            drag.bind('<B1-Motion>', move)
            self.overlay_answer = tk.Label(popup, textvariable=self.answer_text, bg='#17263d', fg='white', font=('Microsoft YaHei UI', 23, 'bold'), wraplength=340, anchor='w', justify='left')
            self.overlay_answer.pack(fill='x', pady=(8, 3))
            self.overlay_detail = tk.Label(popup, textvariable=self.answer_detail, bg='#17263d', fg='#bacbe3', font=('Microsoft YaHei UI', 9), wraplength=340, anchor='w', justify='left')
            self.overlay_detail.pack(fill='x')
            self.overlay = popup
            if self.source.get('hwnd'):
                try:
                    x0, y0, x1, y1 = client_bbox(self.source['hwnd'])
                    x = x1+16
                    if x+370 > user32.GetSystemMetrics(0):
                        x = max(0, x0-386)
                    popup.geometry(f'370x175+{x}+{max(30,y0)}')
                except RuntimeError:
                    pass
        if self.running or self.question:
            self.overlay.deiconify()

    def copy_answer(self):
        if self.answer and self.answer_current:
            self.root.clipboard_clear()
            self.root.clipboard_append(self.answer.text)
            self.status.set('答案已复制；请核对后使用')
        elif self.answer:
            self.status.set('当前保留的是上一题答案，请等待当前题确认后再复制')

    def poll(self):
        try:
            while True:
                item = self.events.get_nowait()
                event = item['event']
                generation = item.get('generation')
                if generation is not None and generation < self.generation:
                    continue
                if event in ('frame','capture'):
                    self.draw_preview(item['image'])
                elif event == 'rereading':
                    self.generation = generation
                    self.answer_current = False
                    self.deadline = None
                    if self.answer:
                        self.answer_detail.set('上一题参考答案 · 正在重新读取，请勿填写旧答案\n'+item['text'])
                    else:
                        self.answer_text.set('正在重新读取…')
                        self.answer_detail.set(item['text'])
                    self.status.set(item['text'])
                    self.preview_state.set('正在重新截取画面，等待本次新截图')
                elif event == 'clear':
                    self.answer_current = False
                    self.generation = generation
                    self.question = None
                    self.set_question('正在读取当前画面，请核对预览。')
                    self.deadline = None
                    if self.answer:
                        self.answer_detail.set('上一题参考答案 · 当前画面尚未确认，请勿填写旧答案')
                    else:
                        self.answer_text.set('识别中…')
                        self.answer_detail.set('正在确认当前题目')
                elif event == 'question':
                    self.answer_current = False
                    self.generation = generation
                    self.question = item['question']
                    self.deadline = item['deadline']
                    if self.answer:
                        self.answer_detail.set('上一题参考答案 · 正在取得新题答案，请勿填写旧答案')
                    else:
                        self.answer_text.set('等待翻译…')
                        self.answer_detail.set(' · '.join(self.question.warnings) or '正在取得当前题目的答案')
                    self.set_question(self.question.display())
                    self.toggle_overlay()
                elif event == 'answer':
                    if self.deadline is not None and time.monotonic() > self.deadline:
                        continue
                    self.answer = item['answer']
                    self.answer_generation = generation
                    self.answer_current = True
                    self.answer_tentative = bool(item.get('tentative'))
                    self.deadline = None
                    self.answer_text.set(self.answer.display())
                    warning = ' · '.join(self.answer.warnings)
                    self.answer_detail.set(f'{item["source"]} · 共 {item["elapsed"]:.2f} 秒' + ('\n'+warning if warning else '\n请核对后选择或填写'))
                    self.timer.set(f'本地 OCR {item["ocr_ms"]} 毫秒 · 总耗时 {item["elapsed"]:.2f} 秒')
                    self.status.set('当前答案已显示；下一题出现时自动重新识别')
                    if self.question and self.question.kind == 'blank':
                        self.answer_detail.set(self.answer_detail.get()+'\n已固定显示，填写时不会刷新')
                    if item.get('tentative'):
                        self.answer_detail.set(self.answer_detail.get()+'\n候选答案：前缀或长度未确认，请核对或填写人工提示')
                    if item.get('previous'):
                        self.answer_current = False
                        self.answer_detail.set('上一题参考答案 · 当前画面未确认，请勿填写\n'+self.answer_detail.get())
                elif event == 'held':
                    previous = (item.get('previous') or not item.get('answer_ready',False)
                                or self.answer_generation != generation)
                    if previous:
                        self.answer_current = False
                    elif self.answer:
                        self.answer_current = True
                    if self.answer:
                        self.answer_detail.set(('上一题参考答案 · 请勿填写\n' if previous else '')+item['text'])
                        if self.answer_tentative:
                            self.answer_detail.set(self.answer_detail.get()+'\n候选答案：前缀或长度未确认，请核对人工提示')
                    self.status.set(item['text'])
                elif event == 'paused':
                    self.generation = generation
                    self.deadline = None
                    self.status.set('已暂停识别')
                    if self.answer:
                        self.answer_detail.set('识别已暂停，答案保持显示，可继续填写或复制' if self.answer_current else '识别已暂停，当前为上一题参考答案，请勿填写')
                        if self.answer_tentative:
                            self.answer_detail.set(self.answer_detail.get()+'\n候选答案：前缀或长度未确认，请核对人工提示')
                    else:
                        self.answer_text.set('已暂停识别')
                elif event == 'failure':
                    self.answer_current = False
                    self.deadline = None
                    if self.answer:
                        self.answer_detail.set('上一题参考答案 · 当前未取得可靠答案，请勿填写\n'+item['text'])
                    else:
                        self.answer_text.set('未取得答案')
                        self.answer_detail.set(item['text'])
                    self.status.set(item['text'])
                elif event == 'retry':
                    if not self.answer:
                        self.answer_text.set('正在重试…')
                    self.answer_detail.set(('上一题参考答案 · 请勿填写\n' if self.answer else '')+item['text'])
                    self.status.set(item['text'])
                elif event == 'unread':
                    self.answer_current = False
                    if self.answer:
                        self.answer_detail.set('上一题参考答案 · 当前画面未读完整，请勿填写旧答案')
                    else:
                        self.answer_text.set('题目未读完整')
                        self.answer_detail.set('正在自动重读；请核对画面预览，可填写前缀和总长度后重读')
                    self.set_question(item['text'] or '当前画面未读到文字，请检查投屏窗口或框选区域。')
                    self.timer.set(f'本地识别 {item["ocr_ms"]} 毫秒 · 等待完整题目')
                elif event == 'error':
                    self.status.set(item['text'])
                    if '窗口' in item['text'] or '画面' in item['text']:
                        self.preview_state.set('当前画面读取失败；显示的是上次成功画面\n'+item['text'])
                elif event == 'status':
                    self.status.set(item['text'])
                    if '失败' in item['text'] and ('遮挡' in item['text'] or '窗口' in item['text']):
                        self.preview_state.set('当前截取失败；显示的是上次成功画面\n'+item['text'])
                elif event == 'stopped':
                    self.running = False
                    self.start_button.configure(text='开始识别  F8', state='normal')
                    self.import_button.configure(state='normal')
                    self.source_combo.configure(state='readonly')
                    self.kind_combo.configure(state='readonly')
                    self.crop_check.configure(state='normal')
                elif event == 'models':
                    self.model_combo['values'] = item['models']
                    if self.model.get() not in item['models']:
                        self.status.set('连接成功；当前模型未在列表里，请选择可用模型后保存')
                    else:
                        self.status.set(f'官方 API 连接正常 · {item["elapsed"]:.2f} 秒（仅连接测试）')
        except queue.Empty:
            pass
        if self.deadline is not None:
            remaining = self.deadline-time.monotonic()
            if remaining <= 0:
                self.answer_current = False
                self.deadline = None
                if self.answer:
                    self.answer_detail.set('上一题参考答案 · 新题等待超时，请重新读取')
                else:
                    self.answer_text.set('本题等待超时')
                    self.answer_detail.set('可点击「手动重试」重新截图并获取答案')
            else:
                self.timer.set(f'接口等待剩余 {remaining:.1f} 秒 · 不代表手机答题剩余时间')
        self.root.after(40, self.poll)

    def close(self):
        self.pipeline.close()
        # Pending requests use their own deadline. Avoid blocking the UI on
        # network cleanup when closing; worker threads finish within timeout.
        self.root.destroy()


def main():
    dpi_aware()
    try:
        ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID('AIVocabAssistant.Desktop')
    except OSError:
        pass
    root = tk.Tk()
    Assistant(root)
    root.mainloop()


if __name__ == '__main__':
    main()
