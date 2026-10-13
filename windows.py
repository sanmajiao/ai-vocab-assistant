from __future__ import annotations

import base64
import ctypes
import os
from ctypes import wintypes as W

from PIL import ImageGrab


class SourceClosedError(RuntimeError):
    """The chosen capture window no longer exists; never select another silently."""


user32 = ctypes.WinDLL('user32', use_last_error=True)
kernel32 = ctypes.WinDLL('kernel32', use_last_error=True)
user32.GetWindowTextW.argtypes = [W.HWND, W.LPWSTR, ctypes.c_int]
user32.GetClassNameW.argtypes = [W.HWND, W.LPWSTR, ctypes.c_int]
user32.GetClientRect.argtypes = [W.HWND, ctypes.POINTER(W.RECT)]
user32.ClientToScreen.argtypes = [W.HWND, ctypes.POINTER(W.POINT)]
user32.IsIconic.argtypes = [W.HWND]
user32.IsWindowVisible.argtypes = [W.HWND]
user32.IsWindow.argtypes = [W.HWND]
user32.GetAncestor.argtypes = [W.HWND, W.UINT]
user32.GetAncestor.restype = W.HWND
user32.WindowFromPoint.argtypes = [W.POINT]
user32.WindowFromPoint.restype = W.HWND
user32.SetWindowPos.argtypes = [W.HWND,W.HWND,ctypes.c_int,ctypes.c_int,ctypes.c_int,ctypes.c_int,W.UINT]
user32.GetWindowThreadProcessId.argtypes = [W.HWND, ctypes.POINTER(W.DWORD)]
kernel32.OpenProcess.argtypes = [W.DWORD, W.BOOL, W.DWORD]
kernel32.OpenProcess.restype = W.HANDLE
kernel32.QueryFullProcessImageNameW.argtypes = [W.HANDLE, W.DWORD, W.LPWSTR, ctypes.POINTER(W.DWORD)]
kernel32.CloseHandle.argtypes = [W.HANDLE]


def dpi_aware():
    try:
        user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))
    except (AttributeError, OSError):
        user32.SetProcessDPIAware()


def window_pid(hwnd):
    pid = W.DWORD()
    user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
    return pid.value


def application_windows():
    found = []
    callback_type = ctypes.WINFUNCTYPE(W.BOOL, W.HWND, W.LPARAM)
    def check(hwnd, unused):
        if not user32.IsWindowVisible(hwnd):
            return True
        cls = ctypes.create_unicode_buffer(256)
        user32.GetClassNameW(hwnd, cls, len(cls))
        if cls.value in ('Progman', 'WorkerW', 'Shell_TrayWnd', 'Shell_SecondaryTrayWnd'):
            return True
        pid = W.DWORD()
        user32.GetWindowThreadProcessId(hwnd, ctypes.byref(pid))
        if pid.value == os.getpid():
            return True
        title = ctypes.create_unicode_buffer(512)
        user32.GetWindowTextW(hwnd, title, len(title))
        if not title.value or title.value.startswith('词汇翻译助手'):
            return True
        try:
            client_bbox(hwnd)
        except RuntimeError:
            return True
        found.append((int(hwnd), title.value))
        return True
    callback = callback_type(check)
    user32.EnumWindows.argtypes = [callback_type, W.LPARAM]
    user32.EnumWindows(callback, 0)
    return found


def scrcpy_windows():
    """Compatibility helper; UI now supports all visible application windows."""
    return application_windows()


def client_bbox(hwnd):
    if not user32.IsWindow(hwnd) or not user32.IsWindowVisible(hwnd):
        raise SourceClosedError('所选窗口已关闭或隐藏，请重新选择')
    if user32.IsIconic(hwnd):
        raise RuntimeError('投屏窗口已最小化，请恢复窗口')
    rect, point = W.RECT(), W.POINT()
    if not user32.GetClientRect(hwnd, ctypes.byref(rect)) or not user32.ClientToScreen(hwnd, ctypes.byref(point)):
        raise RuntimeError('投屏窗口已关闭，请重新选择')
    if rect.right < 50 or rect.bottom < 50:
        raise RuntimeError('投屏窗口尺寸太小')
    return point.x, point.y, point.x + rect.right, point.y + rect.bottom


def resolve_bbox(hwnd=None, bbox=None, pid=None, crop_rel=None):
    if hwnd:
        if pid is not None and window_pid(hwnd) != pid:
            raise SourceClosedError('所选窗口已失效，请重新选择；不会读取其他窗口')
        box = client_bbox(hwnd)
        if crop_rel:
            x0, y0, x1, y1 = box
            width, height = x1-x0, y1-y0
            box = tuple(int(round(base + fraction*size)) for base, fraction, size in
                        zip((x0,y0,x0,y0), crop_rel, (width,height,width,height)))
        return box
    if bbox:
        return tuple(bbox)
    raise RuntimeError('请先选择应用窗口或框选屏幕区域')


def capture(hwnd=None, bbox=None, pid=None, crop_rel=None):
    box = resolve_bbox(hwnd, bbox, pid, crop_rel)
    if hwnd:
        # Capture the pixels the user sees, never PrintWindow's possibly stale
        # backing surface. Refuse obscured windows rather than read an app behind.
        x0,y0,x1,y1 = box
        screen = (user32.GetSystemMetrics(76),user32.GetSystemMetrics(77),
                  user32.GetSystemMetrics(76)+user32.GetSystemMetrics(78),
                  user32.GetSystemMetrics(77)+user32.GetSystemMetrics(79))
        if x0 < screen[0] or y0 < screen[1] or x1 > screen[2] or y1 > screen[3]:
            raise RuntimeError('读取区域超出屏幕，请把所选窗口移回屏幕内')
        for xf in (.03,.25,.5,.75,.97):
            for yf in (.03,.25,.5,.75,.97):
                point = W.POINT(int(x0+(x1-x0)*xf),int(y0+(y1-y0)*yf))
                visible = user32.WindowFromPoint(point)
                if (user32.GetAncestor(visible,2) or visible) != hwnd:
                    raise RuntimeError('所选窗口被遮挡，请移开遮挡窗口；未读取背后的画面')
    return ImageGrab.grab(bbox=box, all_screens=True).convert('RGB')


class Blob(ctypes.Structure):
    _fields_ = [('length', W.DWORD), ('data', ctypes.POINTER(ctypes.c_ubyte))]


def _dpapi(data: bytes, encrypt: bool):
    array = (ctypes.c_ubyte * len(data)).from_buffer_copy(data)
    original, output = Blob(len(data), array), Blob()
    dll = ctypes.WinDLL('crypt32', use_last_error=True)
    function = dll.CryptProtectData if encrypt else dll.CryptUnprotectData
    function.argtypes = [ctypes.POINTER(Blob), ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, ctypes.c_void_p, W.DWORD, ctypes.POINTER(Blob)]
    if not function(ctypes.byref(original), None, None, None, None, 1, ctypes.byref(output)):
        raise OSError(ctypes.get_last_error(), '无法使用 Windows 加密配置')
    try:
        return ctypes.string_at(output.data, output.length)
    finally:
        kernel32.LocalFree.argtypes = [ctypes.c_void_p]
        kernel32.LocalFree(output.data)


def protect(text: str) -> str:
    return base64.b64encode(_dpapi(text.encode('utf-8'), True)).decode('ascii')


def unprotect(text: str) -> str:
    return _dpapi(base64.b64decode(text), False).decode('utf-8')
