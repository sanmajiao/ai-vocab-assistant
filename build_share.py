"""Build a relocatable, explicit allowlist package without local user data."""
import argparse
import hashlib
import importlib.metadata as metadata
import json
import shutil
import sys
import zipfile
from pathlib import Path

from core import APP_VERSION

sys.stdout.reconfigure(encoding='utf-8')
ROOT = Path(__file__).resolve().parent
OUT = ROOT/'release'/APP_VERSION
PACKAGE = OUT/'词汇翻译助手'
parser=argparse.ArgumentParser(description="Build a portable Windows package without personal data")
parser.add_argument('--python-root',type=Path,required=True,help='Complete Python 3.13 installation, including Tk')
parser.add_argument('--scrcpy-root',type=Path,required=True,help='Official scrcpy 5.0.1 Windows x64 folder')
args=parser.parse_args()
PYTHON=args.python_root.resolve()
MIRROR=args.scrcpy_root.resolve()
if not (ROOT/'ocr_dependencies/rapidocr_onnxruntime/models').is_dir():
    raise RuntimeError('Install requirements-ocr.txt into ocr_dependencies before building')
if not PYTHON.is_dir() or not MIRROR.is_dir():
    raise RuntimeError('Python and scrcpy folders must exist before building')
APP_FILES = ('app.py','core.py','pipeline.py','reader.py','network.py','ocr.py','ocr_worker.ps1','windows.py','local_check.py','app.ico','requirements.txt','LICENSE','README.md','THIRD_PARTY_NOTICES.md')
if PACKAGE.exists():
    raise RuntimeError('Existing package preserved; use a fresh output directory')
PACKAGE.mkdir(parents=True)
for name in APP_FILES:
    shutil.copy2(ROOT/name,PACKAGE/name)

def clean_copy(source,destination):
    shutil.copytree(source,destination,ignore=shutil.ignore_patterns('__pycache__','*.pyc','test','tests','idlelib','turtledemo','ensurepip','site-packages'))

runtime=PACKAGE/'runtime'
runtime.mkdir()
for name in ('python.exe','pythonw.exe','python3.dll','python313.dll','vcruntime140.dll','vcruntime140_1.dll','LICENSE.txt'):
    shutil.copy2(PYTHON/name,runtime/name)
for directory in ('Lib','DLLs','tcl'):
    clean_copy(PYTHON/directory,runtime/directory)
(runtime/'python313._pth').write_text('.\n..\n../ocr_dependencies\nDLLs\nLib\nLib/site-packages\nimport site\n',encoding='ascii')
site=runtime/'Lib/site-packages'
site.mkdir()
versions={}
for name in ('pillow','httpx','httpcore','anyio','certifi','idna','h11','typing_extensions','sniffio'):
    try:
        dist=metadata.distribution(name)
    except metadata.PackageNotFoundError:
        if name=='sniffio':
            continue
        raise
    versions[name]=dist.version
    # Metadata includes licenses; only top-level package entries are copied.
    entries={p.parts[0] for p in dist.files if p.parts and p.parts[0] not in ('.','..')}
    for entry in sorted(entries):
        source=Path(dist.locate_file(entry))
        destination=site/entry
        if destination.exists():
            continue
        if source.is_dir():
            clean_copy(source,destination)
        elif source.is_file():
            shutil.copy2(source,destination)

# Local OCR dependencies/models are isolated from the user's Python install.
# Installed distribution metadata (including licenses) is kept with the engine.
ocr_vendor=ROOT/'ocr_dependencies'
if ocr_vendor.exists():
    clean_copy(ocr_vendor,PACKAGE/'ocr_dependencies')
    if not list((PACKAGE/'ocr_dependencies').rglob('*.onnx')):
        raise RuntimeError('Independent OCR models are missing; do not ship an incomplete engine')
(PACKAGE/'第三方识别说明.txt').write_text(
    '本地独立 OCR 使用 RapidOCR / ONNX Runtime，截图在本机处理。\n'
    'RapidOCR 及转换模型的来源：https://github.com/RapidAI/RapidOCR\n'
    '上游模型来自 PaddleOCR，权重版权归 Baidu 和相应权利人，按 Apache-2.0 分发。\n'
    '组件许可证保留在 ocr_dependencies 的 dist-info 目录；所有模型文件的 SHA-256 在 manifest.json 中。\n',
    encoding='utf-8-sig')

mirror=PACKAGE/'mirror'
mirror.mkdir()
for name in ('scrcpy.exe','scrcpy-server','adb.exe','AdbWinApi.dll','AdbWinUsbApi.dll','avcodec-63.dll','avformat-63.dll','avutil-61.dll','libusb-1.0.dll','SDL3.dll','swresample-7.dll','LICENSE.txt'):
    shutil.copy2(MIRROR/name,mirror/name)

common='''Option Explicit
Dim shell, fso, base
Set shell = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
'''
assistant=common+'''shell.CurrentDirectory = base
shell.Run """" & base & "\\runtime\\pythonw.exe"" """ & base & "\\app.py""", 1, False
'''
phone=common+'''shell.CurrentDirectory = base & "\\mirror"
shell.Environment("PROCESS")("ADB") = base & "\\mirror\\adb.exe"
shell.Run "cmd /d /c scrcpy.exe --max-size=1920 --max-fps=60 --keyboard=uhid > mirror.log 2>&1", 0, False
'''
local_checker=common+'''shell.CurrentDirectory = base
shell.Run """" & base & "\\runtime\\pythonw.exe"" """ & base & "\\local_check.py""", 1, False
'''
shortcuts=common+'''Dim desktop, shortcut
desktop = shell.SpecialFolders("Desktop")
Set shortcut = shell.CreateShortcut(desktop & "\\Vocab-Assistant.lnk")
shortcut.TargetPath = shell.ExpandEnvironmentStrings("%SystemRoot%\\System32\\wscript.exe")
shortcut.Arguments = """" & base & "\\" & ChrW(&H542F) & ChrW(&H52A8) & ChrW(&H52A9) & ChrW(&H624B) & ".vbs"""
shortcut.WorkingDirectory = base
shortcut.IconLocation = base & "\\app.ico"
shortcut.Save
Set shortcut = shell.CreateShortcut(desktop & "\\Phone-Mirror.lnk")
shortcut.TargetPath = shell.ExpandEnvironmentStrings("%SystemRoot%\\System32\\wscript.exe")
shortcut.Arguments = """" & base & "\\" & ChrW(&H542F) & ChrW(&H52A8) & ChrW(&H6295) & ChrW(&H5C4F) & ".vbs"""
shortcut.WorkingDirectory = base
shortcut.IconLocation = base & "\\mirror\\scrcpy.exe"
shortcut.Save
MsgBox "Desktop shortcuts created.", 64
'''
for name,contents in [('启动助手.vbs',assistant),('启动投屏.vbs',phone),('检查本地识别.vbs',local_checker),('创建桌面快捷方式.vbs',shortcuts)]:
    (PACKAGE/name).write_bytes(contents.replace('\n','\r\n').encode('ascii'))

guide=f'''词汇翻译助手 {APP_VERSION} · Windows 便携分享版

请先确认微软 Visual C++ x64 运行库已安装，ONNX Runtime 需要 MSVCP140 DLL。官方入口：https://learn.microsoft.com/en-us/cpp/windows/latest-supported-vc-redist
本构建产物的第三方对应源码/许可资料还需按 THIRD_PARTY_NOTICES.md 核对，未补齐前不得直接当作已完成公开再分发材料的 Release。

适用：64 位 Windows 10/11，安卓手机。已包含 Python 运行环境、离线中英文 OCR 模型、依赖和 scrcpy 5.0.1；不需要另装 Python 或下载 OCR 模型。

首次使用
1. 将整个压缩包解压到可写文件夹，例如 D:\\app\\词汇翻译助手。不要在压缩包内直接运行。
2. 安卓手机开启 USB 调试，用数据线连接电脑，并在手机上允许本机调试授权。
3. 双击“启动投屏.vbs”，等待手机画面出现。手机厂商若另有“USB 调试（安全设置）”，控制不生效时检查该设置。
4. 双击“启动助手.vbs”。在 DeepSeek API 页填写自己的官方 API 密钥，点击加密保存配置。密钥需要自己的可用余额。
5. 回到识别设置，刷新并选择投屏窗口（也可使用其他投屏软件）。先打开画面预览，确认看到的是当前手机题目；若读取了别的网页，先调整来源或框选区域，勿盲目请求 API。答案只供核对，由你手动操作手机。
6. 未读对时点击手动重试，会重新捕获当前画面、跳过缓存并再次调用接口。前缀、长度不清楚时可手动填写本次提示并重读。填写字母时保持答案；未确认换题时会标为上一题。
7. 可选：双击“创建桌面快捷方式.vbs”，生成 Vocab-Assistant 和 Phone-Mirror 两个桌面入口。移动文件夹后重新创建。

本次修复
局部重复识别浅色字母，几何检测横线长度；不把已键入字符当原始提示。
改进填空换题、手动重新捕获、单次前缀/长度补充、一般窗口来源、截图框选确认和真实画面预览。

常见问题
- 第一次先双击“检查本地识别.vbs”：测试是否能识别生成的中文和英文，不需要 API 密钥。自检通过而实际题目不对时，重点检查捕获预览和框选范围。
- 本包带独立离线 OCR，用于补足系统 OCR 对短词/中文的误读；系统中文 OCR 不可用时也能使用。若提示引擎/模型无法加载，请完整解压新便携包，不要仅复制 .py 文件。缺系统 OCR 时首次启动会尝试备用引擎，可能稍慢。
- 投屏未出现：确认数据线支持数据传输、手机已允许 USB 调试；不同手机可能需要厂商 USB 驱动。
- 识别内容不完整：暂停后框选题目正文，保留所有选项或字母提示；投屏不要最小化。
- 答案需人工核对，网络和 OCR 质量会影响耗时，5/10 秒目标不保证每次达到。

隐私和分享
本包没有分享者的 API 密钥、设置、答案缓存、截图和诊断日志。
启动后会在本文件夹 data 中建立本机配置。密钥加密绑定当前 Windows 用户，不能当作跨电脑通用配置。
只发送识别后的题目文字到 DeepSeek 官方接口，不发送截图。最近一次题目截图/诊断只在本机 data 内保存。
以后转发时使用这个原始压缩包，勿再压缩使用后的整个目录，以免带入自己生成的数据。

文件说明
启动助手.vbs / 启动投屏.vbs / 检查本地识别.vbs：无黑色命令窗口启动。
runtime：独立运行环境；ocr_dependencies：离线识别组件及模型，勿删除；mirror：投屏与 USB 工具；根目录 .py 与 ocr_worker.ps1：可修改源码。
程序无需安装服务、不修改系统 PATH。关闭后可删除解压目录；创建过快捷方式时一并删除快捷方式。
第三方许可保留在 runtime、runtime\\Lib\\site-packages 的各组件元数据和 mirror\\LICENSE.txt。

核验范围
开发机上的提供截图、输入回放及真实 DeepSeek 请求已检查；同学的手机驱动、OCR 语言和网络需在其电脑上确认。
'''
(PACKAGE/'先读我.txt').write_text(guide,encoding='utf-8-sig')
manifest={'version':APP_VERSION,'python':'3.13.12','scrcpy':'5.0.1','dependencies':versions,'files':{}}
for file in sorted(PACKAGE.rglob('*')):
    if file.is_file():
        manifest['files'][file.relative_to(PACKAGE).as_posix()]=hashlib.sha256(file.read_bytes()).hexdigest()
(PACKAGE/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
zip_path=OUT/f'词汇翻译助手-{APP_VERSION}-Windows便携版.zip'
with zipfile.ZipFile(zip_path,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as archive:
    for file in sorted(PACKAGE.rglob('*')):
        if file.is_file():
            archive.write(file,file.relative_to(OUT))
digest=hashlib.sha256(zip_path.read_bytes()).hexdigest()
(OUT/'SHA256.txt').write_text(f'{digest}  {zip_path.name}\n',encoding='utf-8')
print(json.dumps({'archive':str(zip_path),'size_mb':round(zip_path.stat().st_size/1024**2,1),'file_count':len(manifest['files'])+1,'sha256':digest},ensure_ascii=False))
