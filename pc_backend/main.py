import win32gui
import win32con
import win32api
import base64
import io
import json
import asyncio
import socket
import threading
import time
import ctypes
import hashlib
import collections
import concurrent.futures
from ctypes import wintypes
from contextlib import asynccontextmanager
from fastapi import FastAPI, WebSocket, WebSocketDisconnect, Header, HTTPException, Depends, Request
from fastapi.responses import JSONResponse
from starlette.websockets import WebSocketState
from pydantic import BaseModel
from PIL import Image
import uvicorn

# --- DPI Awareness ---
try:
    ctypes.windll.user32.SetProcessDpiAwarenessContext(ctypes.c_void_p(-4))  # PER_MONITOR_AWARE_V2
except Exception:
    try: ctypes.windll.shcore.SetProcessDpiAwareness(2)
    except Exception: ctypes.windll.user32.SetProcessDPIAware()

# --- Windows API Setup --- (保持不变，省略展开以节省空间，直接用你原来的API声明即可)
HANDLE = ctypes.c_size_t
gdi32 = ctypes.windll.gdi32
user32 = ctypes.WinDLL("user32", use_last_error=True)
shell32 = ctypes.windll.shell32
comctl32 = ctypes.windll.comctl32
dwmapi = ctypes.windll.dwmapi

user32.GetIconInfo.argtypes = [HANDLE, ctypes.c_void_p]
gdi32.GetObjectW.argtypes = [HANDLE, ctypes.c_int, ctypes.c_void_p]
gdi32.GetDIBits.argtypes = [HANDLE, HANDLE, wintypes.UINT, wintypes.UINT, ctypes.c_void_p, ctypes.c_void_p, wintypes.UINT]
user32.GetDC.argtypes = [HANDLE]; user32.GetDC.restype = HANDLE
user32.ReleaseDC.argtypes = [HANDLE, HANDLE]
user32.DestroyIcon.argtypes = [HANDLE]
gdi32.DeleteObject.argtypes = [HANDLE]
gdi32.CreateCompatibleDC.argtypes = [HANDLE]; gdi32.CreateCompatibleDC.restype = HANDLE
gdi32.CreateCompatibleBitmap.argtypes = [HANDLE, ctypes.c_int, ctypes.c_int]; gdi32.CreateCompatibleBitmap.restype = HANDLE
gdi32.SelectObject.argtypes = [HANDLE, HANDLE]
gdi32.DeleteDC.argtypes = [HANDLE]
user32.PrintWindow.argtypes = [HANDLE, HANDLE, wintypes.UINT]
gdi32.StretchBlt.argtypes = [HANDLE, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, HANDLE, ctypes.c_int, ctypes.c_int, ctypes.c_int, ctypes.c_int, wintypes.DWORD]
user32.SetCursorPos.argtypes = [ctypes.c_int, ctypes.c_int]
user32.mouse_event.argtypes = [wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, wintypes.DWORD, ctypes.c_size_t]

# --- WGC Setup ---
WGC_AVAILABLE = False
try:
    from windows_capture import WindowsCapture, Frame
    import cv2
    WGC_AVAILABLE = True
except ImportError:
    pass

# Constants
SHGFI_SYSICONINDEX = 0x000004000
SHIL_JUMBO = 0x00004
ILD_TRANSPARENT = 0x00000001
BI_RGB = 0

class ICONINFO(ctypes.Structure): _fields_ = [("fIcon", wintypes.BOOL), ("xHotspot", wintypes.DWORD), ("yHotspot", wintypes.DWORD), ("hbmMask", HANDLE), ("hbmColor", HANDLE)]
class BITMAP(ctypes.Structure): _fields_ = [("bmType", ctypes.c_long), ("bmWidth", ctypes.c_long), ("bmHeight", ctypes.c_long), ("bmWidthBytes", ctypes.c_long), ("bmPlanes", wintypes.WORD), ("bmBitsPixel", wintypes.WORD), ("bmBits", wintypes.LPVOID)]
class BITMAPINFOHEADER(ctypes.Structure): _fields_ = [("biSize", wintypes.DWORD), ("biWidth", ctypes.c_long), ("biHeight", ctypes.c_long), ("biPlanes", wintypes.WORD), ("biBitCount", wintypes.WORD), ("biCompression", wintypes.DWORD), ("biSizeImage", wintypes.DWORD), ("biXPelsPerMeter", ctypes.c_long), ("biYPelsPerMeter", ctypes.c_long), ("biClrUsed", wintypes.DWORD), ("biClrImportant", wintypes.DWORD)]

# --- 图标和截图函数原样保留 ---
def hicon_to_image(hicon_handle):
    if not hicon_handle: return None
    try:
        info = ICONINFO()
        if not user32.GetIconInfo(hicon_handle, ctypes.byref(info)): return None
        target_bmp = info.hbmColor if info.hbmColor else info.hbmMask
        if not target_bmp: return None
        bmp = BITMAP()
        if not gdi32.GetObjectW(target_bmp, ctypes.sizeof(bmp), ctypes.byref(bmp)): return None
        w, h = bmp.bmWidth, bmp.bmHeight
        bi = BITMAPINFOHEADER(); bi.biSize, bi.biWidth, bi.biHeight, bi.biPlanes, bi.biBitCount, bi.biCompression = ctypes.sizeof(BITMAPINFOHEADER), w, -h, 1, 32, BI_RGB
        hdc = user32.GetDC(0); buf = ctypes.create_string_buffer(w * h * 4); gdi32.GetDIBits(hdc, target_bmp, 0, h, buf, ctypes.byref(bi), 0); user32.ReleaseDC(0, hdc)
        if info.hbmColor: gdi32.DeleteObject(info.hbmColor)
        if info.hbmMask: gdi32.DeleteObject(info.hbmMask)
        return Image.frombuffer("RGBA", (w, h), buf, "raw", "BGRA", 0, 1)
    except: return None

static_icon_cache = {}

def get_window_best_icon(hwnd):
    try:
        res = win32gui.SendMessageTimeout(hwnd, win32con.WM_GETICON, win32con.ICON_BIG, 0, win32con.SMTO_ABORTIFHUNG, 50)
        hicon = res[1] if res else 0
        if hicon:
            img = hicon_to_image(hicon)
            if img:
                buf = io.BytesIO(); img.save(buf, format="PNG"); return base64.b64encode(buf.getvalue()).decode('utf-8')
    except: pass
    try:
        import win32process
        _, pid = win32process.GetWindowThreadProcessId(hwnd)
        hproc = win32api.OpenProcess(win32con.PROCESS_QUERY_INFORMATION | win32con.PROCESS_VM_READ, False, pid)
        path = win32process.GetModuleFileNameEx(hproc, 0); win32api.CloseHandle(hproc)
        if path in static_icon_cache: return static_icon_cache[path]
        class GUID(ctypes.Structure): _fields_ = [("Data1", wintypes.DWORD), ("Data2", wintypes.WORD), ("Data3", wintypes.WORD), ("Data4", wintypes.BYTE * 8)]
        iid_iimagelist = GUID(0x46EB5926, 0x582E, 0x4017, (wintypes.BYTE * 8)(0x9F, 0xDF, 0xE8, 0x99, 0x8D, 0xAA, 0x09, 0x50))
        class SHFILEINFO_INT(ctypes.Structure): _fields_ = [("hIcon", HANDLE), ("iIcon", ctypes.c_int), ("dwAttributes", wintypes.DWORD), ("szDisplayName", wintypes.WCHAR * 260), ("szTypeName", wintypes.WCHAR * 80)]
        sfi = SHFILEINFO_INT(); shell32.SHGetFileInfoW(path, 0, ctypes.byref(sfi), ctypes.sizeof(sfi), SHGFI_SYSICONINDEX)
        p_image_list = ctypes.c_void_p(); shell32.SHGetImageList(SHIL_JUMBO, ctypes.byref(iid_iimagelist), ctypes.byref(p_image_list))
        hicon_shell = comctl32.ImageList_GetIcon(p_image_list, sfi.iIcon, ILD_TRANSPARENT)
        if hicon_shell:
            img = hicon_to_image(hicon_shell); user32.DestroyIcon(hicon_shell)
            if img:
                buf = io.BytesIO(); img.save(buf, format="PNG"); b64 = base64.b64encode(buf.getvalue()).decode('utf-8')
                static_icon_cache[path] = b64; return b64
    except: pass
    return ""

def get_window_preview_gdi(hwnd, q=65, w_limit=800, h_limit=600):
    if not win32gui.IsWindow(hwnd) or win32gui.IsIconic(hwnd): return ""
    try:
        DWMWA_EXTENDED_FRAME_BOUNDS = 9
        rect = wintypes.RECT(); dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, ctypes.byref(rect), ctypes.sizeof(rect))
        w, h = rect.right - rect.left, rect.bottom - rect.top
        if w <= 10 or h <= 10: return ""
        scale = min(w_limit/w, h_limit/h, 1.0); sw, sh = int(w*scale), int(h*scale)
        hdc_screen = user32.GetDC(0); hdc_mem = gdi32.CreateCompatibleDC(hdc_screen); hbmp = gdi32.CreateCompatibleBitmap(hdc_screen, sw, sh); gdi32.SelectObject(hdc_mem, hbmp)
        hdc_full = gdi32.CreateCompatibleDC(hdc_screen); hbmp_full = gdi32.CreateCompatibleBitmap(hdc_screen, w, h); gdi32.SelectObject(hdc_full, hbmp_full)
        user32.PrintWindow(hwnd, hdc_full, 2); gdi32.SetStretchBltMode(hdc_mem, 3); gdi32.StretchBlt(hdc_mem, 0, 0, sw, sh, hdc_full, 0, 0, w, h, 0x00CC0020)
        bi = BITMAPINFOHEADER(); bi.biSize, bi.biWidth, bi.biHeight, bi.biPlanes, bi.biBitCount, bi.biCompression = ctypes.sizeof(BITMAPINFOHEADER), sw, -sh, 1, 32, BI_RGB
        buf = ctypes.create_string_buffer(sw * sh * 4); gdi32.GetDIBits(hdc_mem, hbmp, 0, sh, buf, ctypes.byref(bi), 0)
        img = Image.frombuffer("RGB", (sw, sh), buf, "raw", "BGRX", 0, 1)
        gdi32.DeleteObject(hbmp); gdi32.DeleteObject(hbmp_full); gdi32.DeleteDC(hdc_mem); gdi32.DeleteDC(hdc_full); user32.ReleaseDC(0, hdc_screen)
        buf = io.BytesIO(); img.save(buf, format="JPEG", quality=q); return base64.b64encode(buf.getvalue()).decode('utf-8')
    except: return ""

def is_taskbar_window(hwnd):
    if not win32gui.IsWindowVisible(hwnd): return False
    title = win32gui.GetWindowText(hwnd)
    if not title or title == "Program Manager": return False
    owner = win32gui.GetWindow(hwnd, win32con.GW_OWNER); ex_style = win32gui.GetWindowLong(hwnd, win32con.GWL_EXSTYLE)
    return not (ex_style & win32con.WS_EX_TOOLWINDOW) and (owner == 0 or (ex_style & win32con.WS_EX_APPWINDOW))

def get_window_process_info(hwnd):
    import os
    import win32process
    pid = win32process.GetWindowThreadProcessId(hwnd)[1]
    path = ""
    try:
        hproc = win32api.OpenProcess(win32con.PROCESS_QUERY_INFORMATION | win32con.PROCESS_VM_READ, False, pid)
        path = win32process.GetModuleFileNameEx(hproc, 0)
        win32api.CloseHandle(hproc)
    except Exception:
        path = ""
    return pid, path, os.path.basename(path) if path else ""

def should_filter_window(title, process_name, class_name):
    if not WINDOW_FILTER_CONFIG["enabled"]:
        return False
    title_l = (title or "").casefold()
    process_l = (process_name or "").casefold()
    class_l = (class_name or "").casefold()
    title_rules = list(WINDOW_FILTER_CONFIG["title_contains"])
    process_rules = list(WINDOW_FILTER_CONFIG["process_names"])
    class_rules = list(WINDOW_FILTER_CONFIG["class_names"])
    if WINDOW_FILTER_CONFIG["hide_system_windows"]:
        title_rules += DEFAULT_SYSTEM_TITLE_FILTERS
        process_rules += DEFAULT_SYSTEM_PROCESS_FILTERS
    if any(rule.casefold() in title_l for rule in title_rules if rule):
        return True
    if any(rule.casefold() == process_l for rule in process_rules if rule):
        return True
    if any(rule.casefold() == class_l for rule in class_rules if rule):
        return True
    return False

def is_window_maximized(hwnd: int):
    try:
        return win32gui.GetWindowPlacement(hwnd)[1] == win32con.SW_SHOWMAXIMIZED
    except Exception:
        return False

def get_windows_list_minimal():
    windows = []
    foreground_hwnd = win32gui.GetForegroundWindow()
    win32gui.EnumWindows(lambda h, _: windows.append(h) if is_taskbar_window(h) else None, None)
    res = []
    for h in windows:
        title = win32gui.GetWindowText(h)
        class_name = win32gui.GetClassName(h)
        pid, process_path, process_name = get_window_process_info(h)
        if should_filter_window(title, process_name, class_name):
            continue
        res.append({
            "hwnd": h,
            "title": title,
            "icon": get_window_best_icon(h),
            "preview": "",
            "is_active": (h == foreground_hwnd),
            "is_maximized": is_window_maximized(h),
            "pid": pid,
            "process_name": process_name,
            "class_name": class_name
        })
    res.sort(key=lambda x: (x["pid"], x["hwnd"])); return res


# --- 核心变量与服务器生命周期配置 ---
PASSWORD = "123"
PORT = 8000
DISCOVERY_PORT = 8001
USE_WGC = False
WGC_REQUESTED = False
LIVE_MAX_DIM = 720.0
LIVE_JPEG_QUALITY = 72
LIVE_TARGET_FPS = 30.0
GRID_PREVIEW_INTERVAL = 5.0
current_obs_hwnd = 0
connected_clients = set()
active_live_streams = 0
DEFAULT_SYSTEM_TITLE_FILTERS = [
    "Windows 输入体验",
    "Windows Input Experience",
    "Microsoft Text Input Application",
]
DEFAULT_SYSTEM_PROCESS_FILTERS = ["TextInputHost.exe"]
WINDOW_FILTER_CONFIG = {
    "enabled": True,
    "hide_system_windows": True,
    "title_contains": [],
    "process_names": [],
    "class_names": [],
}

SM_XVIRTUALSCREEN = 76
SM_YVIRTUALSCREEN = 77

KEY_CODES = {
    "BACKSPACE": win32con.VK_BACK, "TAB": win32con.VK_TAB, "ENTER": win32con.VK_RETURN,
    "SHIFT": win32con.VK_SHIFT, "CTRL": win32con.VK_CONTROL, "CONTROL": win32con.VK_CONTROL,
    "ALT": win32con.VK_MENU, "ESC": win32con.VK_ESCAPE, "ESCAPE": win32con.VK_ESCAPE,
    "SPACE": win32con.VK_SPACE, "LEFT": win32con.VK_LEFT, "UP": win32con.VK_UP,
    "RIGHT": win32con.VK_RIGHT, "DOWN": win32con.VK_DOWN, "DELETE": win32con.VK_DELETE,
    "WIN": win32con.VK_LWIN, "META": win32con.VK_LWIN, "COPY": ord("C"), "PASTE": ord("V"),
}
for c in "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789":
    KEY_CODES[c] = ord(c)
for i in range(1, 13):
    KEY_CODES[f"F{i}"] = win32con.VK_F1 + i - 1

MOUSEEVENTF_MOVE = 0x0001
MOUSEEVENTF_LEFTDOWN = 0x0002
MOUSEEVENTF_LEFTUP = 0x0004
MOUSEEVENTF_RIGHTDOWN = 0x0008
MOUSEEVENTF_RIGHTUP = 0x0010
MOUSEEVENTF_MIDDLEDOWN = 0x0020
MOUSEEVENTF_MIDDLEUP = 0x0040
MOUSEEVENTF_WHEEL = 0x0800
INPUT_KEYBOARD = 1
KEYEVENTF_KEYUP = 0x0002
KEYEVENTF_UNICODE = 0x0004
PT_TOUCH = 2
POINTER_FLAG_INRANGE = 0x00000002
POINTER_FLAG_INCONTACT = 0x00000004
POINTER_FLAG_PRIMARY = 0x00002000
POINTER_FLAG_DOWN = 0x00010000
POINTER_FLAG_UPDATE = 0x00020000
POINTER_FLAG_UP = 0x00040000
TOUCH_MASK_CONTACTAREA = 0x00000001
TOUCH_MASK_ORIENTATION = 0x00000002
TOUCH_MASK_PRESSURE = 0x00000004
TOUCH_FLAG_NONE = 0x00000000
TOUCH_FEEDBACK_DEFAULT = 0x1
TOUCH_POINTER_ID = 0
TOUCH_PRESSURE_NORMAL = 512
INPUT_TOUCH_BUILD = "synthetic-touch-v3"
touch_initialized = False
synthetic_touch_device = None

class KEYBDINPUT(ctypes.Structure):
    _fields_ = [
        ("wVk", wintypes.WORD),
        ("wScan", wintypes.WORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_size_t),
    ]

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [
        ("dx", ctypes.c_long),
        ("dy", ctypes.c_long),
        ("mouseData", wintypes.DWORD),
        ("dwFlags", wintypes.DWORD),
        ("time", wintypes.DWORD),
        ("dwExtraInfo", ctypes.c_size_t),
    ]

class HARDWAREINPUT(ctypes.Structure):
    _fields_ = [
        ("uMsg", wintypes.DWORD),
        ("wParamL", wintypes.WORD),
        ("wParamH", wintypes.WORD),
    ]

class INPUT_UNION(ctypes.Union):
    _fields_ = [("mi", MOUSEINPUT), ("ki", KEYBDINPUT), ("hi", HARDWAREINPUT)]

class INPUT(ctypes.Structure):
    _fields_ = [("type", wintypes.DWORD), ("union", INPUT_UNION)]

user32.SendInput.argtypes = [wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int]
user32.SendInput.restype = wintypes.UINT

class POINTER_INFO(ctypes.Structure):
    _fields_ = [
        ("pointerType", wintypes.DWORD),
        ("pointerId", wintypes.UINT),
        ("frameId", wintypes.UINT),
        ("pointerFlags", wintypes.DWORD),
        ("sourceDevice", HANDLE),
        ("hwndTarget", HANDLE),
        ("ptPixelLocation", wintypes.POINT),
        ("ptHimetricLocation", wintypes.POINT),
        ("ptPixelLocationRaw", wintypes.POINT),
        ("ptHimetricLocationRaw", wintypes.POINT),
        ("dwTime", wintypes.DWORD),
        ("historyCount", wintypes.UINT),
        ("InputData", ctypes.c_int),
        ("dwKeyStates", wintypes.DWORD),
        ("PerformanceCount", ctypes.c_uint64),
        ("ButtonChangeType", ctypes.c_int),
    ]

class POINTER_TOUCH_INFO(ctypes.Structure):
    _fields_ = [
        ("pointerInfo", POINTER_INFO),
        ("touchFlags", wintypes.DWORD),
        ("touchMask", wintypes.DWORD),
        ("rcContact", wintypes.RECT),
        ("rcContactRaw", wintypes.RECT),
        ("orientation", wintypes.UINT),
        ("pressure", wintypes.UINT),
    ]

class POINTER_TYPE_UNION(ctypes.Union):
    _fields_ = [
        ("pointerInfo", POINTER_INFO),
        ("touchInfo", POINTER_TOUCH_INFO),
    ]

class POINTER_TYPE_INFO(ctypes.Structure):
    _fields_ = [
        ("type", wintypes.DWORD),
        ("u", POINTER_TYPE_UNION),
    ]

if hasattr(user32, "InitializeTouchInjection"):
    user32.InitializeTouchInjection.argtypes = [wintypes.UINT, wintypes.DWORD]
    user32.InitializeTouchInjection.restype = wintypes.BOOL
if hasattr(user32, "InjectTouchInput"):
    user32.InjectTouchInput.argtypes = [wintypes.UINT, ctypes.POINTER(POINTER_TOUCH_INFO)]
    user32.InjectTouchInput.restype = wintypes.BOOL
if hasattr(user32, "CreateSyntheticPointerDevice"):
    user32.CreateSyntheticPointerDevice.argtypes = [wintypes.DWORD, wintypes.ULONG, wintypes.DWORD]
    user32.CreateSyntheticPointerDevice.restype = HANDLE
if hasattr(user32, "InjectSyntheticPointerInput"):
    user32.InjectSyntheticPointerInput.argtypes = [HANDLE, ctypes.POINTER(POINTER_TYPE_INFO), wintypes.UINT]
    user32.InjectSyntheticPointerInput.restype = wintypes.BOOL
if hasattr(user32, "DestroySyntheticPointerDevice"):
    user32.DestroySyntheticPointerDevice.argtypes = [HANDLE]
    user32.DestroySyntheticPointerDevice.restype = None

# 线程池与退出信号 (处理 Graceful Shutdown)
shutdown_event = threading.Event()
live_stream_pool = concurrent.futures.ThreadPoolExecutor(max_workers=4)
background_tasks = []

@asynccontextmanager
async def lifespan(app: FastAPI):
    shutdown_event.clear()
    background_tasks.append(asyncio.create_task(list_broadcaster()))
    background_tasks.append(asyncio.create_task(grid_preview_broadcaster()))
    threading.Thread(target=udp_discovery, daemon=True).start()
    
    yield  # 服务器运行中...
    
    # 捕获 Ctrl+C，开始优雅清理，不报红字错误！
    shutdown_event.set()
    for task in background_tasks:
        task.cancel()
    live_stream_pool.shutdown(wait=False)

app = FastAPI(lifespan=lifespan)


async def safe_close(websocket: WebSocket):
    if websocket.client_state != WebSocketState.DISCONNECTED:
        try: await websocket.close()
        except: pass

def get_window_screen_point(hwnd: int, x: float, y: float):
    DWMWA_EXTENDED_FRAME_BOUNDS = 9
    rect = wintypes.RECT()
    try:
        dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, ctypes.byref(rect), ctypes.sizeof(rect))
    except Exception:
        l, t, r, b = win32gui.GetWindowRect(hwnd)
        rect.left, rect.top, rect.right, rect.bottom = l, t, r, b
    width = max(1, rect.right - rect.left)
    height = max(1, rect.bottom - rect.top)
    nx = max(0.0, min(float(x), 1.0))
    ny = max(0.0, min(float(y), 1.0))
    return int(rect.left + nx * width), int(rect.top + ny * height)

def get_window_bounds(hwnd: int):
    DWMWA_EXTENDED_FRAME_BOUNDS = 9
    rect = wintypes.RECT()
    try:
        dwmapi.DwmGetWindowAttribute(hwnd, DWMWA_EXTENDED_FRAME_BOUNDS, ctypes.byref(rect), ctypes.sizeof(rect))
        return int(rect.left), int(rect.top), int(rect.right), int(rect.bottom)
    except Exception:
        return tuple(map(int, win32gui.GetWindowRect(hwnd)))

def get_screens_list():
    screens = []
    try:
        monitors = win32api.EnumDisplayMonitors()
        primary_rect = (
            user32.GetSystemMetrics(0),
            user32.GetSystemMetrics(1),
        )
        for idx, monitor in enumerate(monitors, start=1):
            handle, _, rect = monitor
            info = win32api.GetMonitorInfo(handle)
            left, top, right, bottom = info.get("Monitor", rect)
            work = info.get("Work", (left, top, right, bottom))
            is_primary = bool(info.get("Flags", 0) & 1)
            screens.append({
                "monitor_index": idx,
                "name": info.get("Device", f"Monitor {idx}"),
                "device": info.get("Device", f"Monitor {idx}"),
                "left": int(left),
                "top": int(top),
                "right": int(right),
                "bottom": int(bottom),
                "width": int(right - left),
                "height": int(bottom - top),
                "is_primary": is_primary,
                "work_left": int(work[0]),
                "work_top": int(work[1]),
                "work_right": int(work[2]),
                "work_bottom": int(work[3]),
            })
    except Exception as e:
        print(f"[screens] enumerate failed: {type(e).__name__}: {e}", flush=True)
    return screens

def get_screen_rect(monitor_index: int):
    for screen in get_screens_list():
        if int(screen["monitor_index"]) == int(monitor_index):
            return screen
    raise RuntimeError(f"screen not found: {monitor_index}")

def move_window_to_screen(hwnd: int, monitor_index: int):
    screen = get_screen_rect(monitor_index)
    if win32gui.IsIconic(hwnd) or is_window_maximized(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        time.sleep(0.05)

    left, top, right, bottom = get_window_bounds(hwnd)
    width = max(120, right - left)
    height = max(80, bottom - top)
    work_left = int(screen.get("work_left", screen["left"]))
    work_top = int(screen.get("work_top", screen["top"]))
    work_right = int(screen.get("work_right", screen["right"]))
    work_bottom = int(screen.get("work_bottom", screen["bottom"]))
    work_width = max(1, work_right - work_left)
    work_height = max(1, work_bottom - work_top)
    target_width = min(width, work_width)
    target_height = min(height, work_height)
    target_left = work_left + max(0, (work_width - target_width) // 2)
    target_top = work_top + max(0, (work_height - target_height) // 2)
    win32gui.SetWindowPos(
        hwnd,
        None,
        target_left,
        target_top,
        target_width,
        target_height,
        win32con.SWP_NOZORDER | win32con.SWP_NOACTIVATE,
    )
    return {
        "left": target_left,
        "top": target_top,
        "width": target_width,
        "height": target_height,
        "monitor_index": monitor_index,
    }

def get_screen_point(monitor_index: int, x: float, y: float):
    screen = get_screen_rect(monitor_index)
    nx = max(0.0, min(float(x), 1.0))
    ny = max(0.0, min(float(y), 1.0))
    sx = int(screen["left"] + nx * max(1, screen["width"]))
    sy = int(screen["top"] + ny * max(1, screen["height"]))
    return sx, sy

def to_virtual_screen_point(sx: int, sy: int):
    return sx - user32.GetSystemMetrics(SM_XVIRTUALSCREEN), sy - user32.GetSystemMetrics(SM_YVIRTUALSCREEN)

def focus_window(hwnd: int, aggressive: bool = True):
    try:
        if win32gui.IsIconic(hwnd):
            win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        win32gui.ShowWindow(hwnd, win32con.SW_SHOW)
        import win32process
        current_thread = win32api.GetCurrentThreadId()
        target_thread = win32process.GetWindowThreadProcessId(hwnd)[0]
        foreground = win32gui.GetForegroundWindow()
        foreground_thread = win32process.GetWindowThreadProcessId(foreground)[0] if foreground else 0
        attached = []
        for thread_id in {target_thread, foreground_thread}:
            if thread_id and thread_id != current_thread:
                try:
                    user32.AttachThreadInput(current_thread, thread_id, True)
                    attached.append(thread_id)
                except Exception:
                    pass
        win32gui.BringWindowToTop(hwnd)
        win32gui.SetForegroundWindow(hwnd)
        try:
            win32gui.SetFocus(hwnd)
        except Exception:
            pass
        for thread_id in attached:
            try:
                user32.AttachThreadInput(current_thread, thread_id, False)
            except Exception:
                pass
        if aggressive and win32gui.GetForegroundWindow() != hwnd:
            # Fallback: Alt-based activation only when normal foreground activation fails.
            for vk in (win32con.VK_MENU, win32con.VK_CONTROL, win32con.VK_SHIFT, win32con.VK_LWIN):
                win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP, 0)
            win32api.keybd_event(win32con.VK_MENU, 0, 0, 0)
            win32gui.SetForegroundWindow(hwnd)
            win32api.keybd_event(win32con.VK_MENU, 0, win32con.KEYEVENTF_KEYUP, 0)
            time.sleep(0.03)
    except Exception:
        pass

def send_vk(vk: int, keyup: bool = False):
    win32api.keybd_event(vk, 0, win32con.KEYEVENTF_KEYUP if keyup else 0, 0)
    time.sleep(0.015)

def send_key_name(name: str):
    vk = KEY_CODES.get(str(name).upper())
    if vk is None:
        return False
    send_vk(vk, False)
    send_vk(vk, True)
    return True

def send_shortcut(keys):
    vks = []
    for key in keys or []:
        vk = KEY_CODES.get(str(key).upper())
        if vk is not None:
            vks.append(vk)
    for vk in vks:
        send_vk(vk, False)
    time.sleep(0.06)
    for vk in reversed(vks):
        send_vk(vk, True)
    return bool(vks)

def send_unicode_text(text: str):
    for ch in text or "":
        code = ord(ch)
        if ch == "\n":
            send_key_name("ENTER")
            continue
        inp_down = INPUT()
        inp_down.type = INPUT_KEYBOARD
        inp_down.union.ki = KEYBDINPUT(0, code, KEYEVENTF_UNICODE, 0, 0)
        inp_up = INPUT()
        inp_up.type = INPUT_KEYBOARD
        inp_up.union.ki = KEYBDINPUT(0, code, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP, 0, 0)
        arr = (INPUT * 2)(inp_down, inp_up)
        sent = user32.SendInput(2, arr, ctypes.sizeof(INPUT))
        if sent != 2:
            raise RuntimeError(f"SendInput unicode failed: sent={sent} error={ctypes.get_last_error()} size={ctypes.sizeof(INPUT)}")

def handle_mouse_input(hwnd: int, data: dict):
    action = data.get("action", "move")
    sx, sy = get_window_screen_point(hwnd, data.get("x", 0.0), data.get("y", 0.0))
    if action in ("down", "click"):
        focus_window(hwnd, aggressive=True)
    handle_mouse_at_point(sx, sy, data)

def handle_mouse_at_point(sx: int, sy: int, data: dict):
    action = data.get("action", "move")
    user32.SetCursorPos(sx, sy)
    button = data.get("button", "left")
    if button == "right":
        down_flag, up_flag = MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP
    elif button == "middle":
        down_flag, up_flag = MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP
    else:
        down_flag, up_flag = MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP
    if action == "down":
        user32.mouse_event(down_flag, 0, 0, 0, 0)
    elif action == "up":
        user32.mouse_event(up_flag, 0, 0, 0, 0)
    elif action == "click":
        user32.mouse_event(down_flag, 0, 0, 0, 0)
        user32.mouse_event(up_flag, 0, 0, 0, 0)
    elif action == "wheel":
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(data.get("delta", 0)), 0)

def handle_mouse_screen_input(monitor_index: int, data: dict):
    sx, sy = get_screen_point(monitor_index, data.get("x", 0.5), data.get("y", 0.5))
    handle_mouse_at_point(sx, sy, data)

def build_touch_info(hwnd: int, data: dict):
    action = data.get("action", "move")
    sx, sy = get_window_screen_point(hwnd, data.get("x", 0.0), data.get("y", 0.0))
    return build_touch_info_at_point(action, sx, sy)

def build_screen_touch_info(monitor_index: int, data: dict):
    action = data.get("action", "move")
    sx, sy = get_screen_point(monitor_index, data.get("x", 0.0), data.get("y", 0.0))
    return build_touch_info_at_point(action, sx, sy)

def build_touch_info_at_point(action: str, sx: int, sy: int):
    tx, ty = to_virtual_screen_point(sx, sy)

    if action == "down":
        flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN
    elif action == "up":
        flags = POINTER_FLAG_UP
    else:
        flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_UPDATE

    contact_size = 4
    touch = POINTER_TOUCH_INFO()
    touch.pointerInfo.pointerType = PT_TOUCH
    touch.pointerInfo.pointerId = TOUCH_POINTER_ID
    touch.pointerInfo.pointerFlags = flags
    touch.pointerInfo.historyCount = 1
    touch.pointerInfo.ptPixelLocation = wintypes.POINT(tx, ty)
    touch.pointerInfo.ptPixelLocationRaw = wintypes.POINT(tx, ty)
    touch.touchFlags = TOUCH_FLAG_NONE
    if action == "up":
        touch.touchMask = 0
    else:
        touch.touchMask = TOUCH_MASK_CONTACTAREA
        touch.rcContact = wintypes.RECT(tx - contact_size, ty - contact_size, tx + contact_size, ty + contact_size)
    return action, sx, sy, tx, ty, flags, touch

def inject_synthetic_touch(action: str, sx: int, sy: int, tx: int, ty: int, flags: int, touch: POINTER_TOUCH_INFO):
    global synthetic_touch_device
    if not hasattr(user32, "CreateSyntheticPointerDevice") or not hasattr(user32, "InjectSyntheticPointerInput"):
        raise RuntimeError("Windows synthetic pointer API is unavailable")
    if not synthetic_touch_device:
        synthetic_touch_device = user32.CreateSyntheticPointerDevice(PT_TOUCH, 1, TOUCH_FEEDBACK_DEFAULT)
        if not synthetic_touch_device:
            raise RuntimeError(f"CreateSyntheticPointerDevice failed: {ctypes.get_last_error()}")

    pointer_type_info = POINTER_TYPE_INFO()
    pointer_type_info.type = PT_TOUCH
    pointer_type_info.u.touchInfo = touch
    if not user32.InjectSyntheticPointerInput(synthetic_touch_device, ctypes.byref(pointer_type_info), 1):
        raise RuntimeError(
            f"InjectSyntheticPointerInput failed: {ctypes.get_last_error()} "
            f"action={action} flags=0x{flags:x} screen=({sx},{sy}) virtual=({tx},{ty}) "
            f"mask=0x{touch.touchMask:x} touchSize={ctypes.sizeof(POINTER_TOUCH_INFO)} "
            f"typeSize={ctypes.sizeof(POINTER_TYPE_INFO)} build={INPUT_TOUCH_BUILD}"
        )

def inject_legacy_touch(action: str, sx: int, sy: int, flags: int, touch: POINTER_TOUCH_INFO):
    global touch_initialized
    if not hasattr(user32, "InitializeTouchInjection") or not hasattr(user32, "InjectTouchInput"):
        raise RuntimeError("Windows touch injection API is unavailable")
    if not touch_initialized:
        if not user32.InitializeTouchInjection(1, TOUCH_FEEDBACK_DEFAULT):
            raise RuntimeError(f"InitializeTouchInjection failed: {ctypes.get_last_error()}")
        touch_initialized = True

    if not user32.InjectTouchInput(1, ctypes.byref(touch)):
        raise RuntimeError(
            f"InjectTouchInput failed: {ctypes.get_last_error()} "
            f"action={action} flags=0x{flags:x} point=({sx},{sy}) "
            f"mask=0x{touch.touchMask:x} size={ctypes.sizeof(POINTER_TOUCH_INFO)} "
            f"build={INPUT_TOUCH_BUILD}"
        )

def handle_touch_input(hwnd: int, data: dict):
    action = data.get("action", "move")
    if action == "down":
        focus_window(hwnd, aggressive=True)

    action, sx, sy, tx, ty, flags, touch = build_touch_info(hwnd, data)
    if hasattr(user32, "CreateSyntheticPointerDevice") and hasattr(user32, "InjectSyntheticPointerInput"):
        inject_synthetic_touch(action, sx, sy, tx, ty, flags, touch)
    else:
        inject_legacy_touch(action, sx, sy, flags, touch)

def handle_touch_screen_input(monitor_index: int, data: dict):
    action, sx, sy, tx, ty, flags, touch = build_screen_touch_info(monitor_index, data)
    if hasattr(user32, "CreateSyntheticPointerDevice") and hasattr(user32, "InjectSyntheticPointerInput"):
        inject_synthetic_touch(action, sx, sy, tx, ty, flags, touch)
    else:
        inject_legacy_touch(action, sx, sy, flags, touch)

@app.post("/switch/{hwnd}")
async def switch_window(hwnd: int, request: Request):
    if request.headers.get("password") != PASSWORD: return JSONResponse(status_code=401, content={"message": "Invalid password"})
    if win32gui.IsIconic(hwnd): win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    win32api.keybd_event(win32con.VK_MENU, 0, 0, 0); win32gui.SetForegroundWindow(hwnd); win32api.keybd_event(win32con.VK_MENU, 0, win32con.KEYEVENTF_KEYUP, 0)
    return {"status": "success"}

@app.post("/window/{hwnd}/control")
async def control_window(hwnd: int, request: Request):
    if request.headers.get("password") != PASSWORD:
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    if not win32gui.IsWindow(hwnd):
        raise HTTPException(status_code=404, detail="window not found")

    try:
        data = await request.json()
    except Exception:
        data = {}
    action = str(data.get("action", "")).strip().lower()

    try:
        result = None
        if action == "close":
            win32gui.PostMessage(hwnd, win32con.WM_CLOSE, 0, 0)
        elif action == "minimize":
            win32gui.ShowWindow(hwnd, win32con.SW_MINIMIZE)
        elif action == "maximize":
            if win32gui.IsIconic(hwnd):
                win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
            win32gui.ShowWindow(hwnd, win32con.SW_MAXIMIZE)
        elif action == "restore":
            win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        elif action == "move_to_screen":
            monitor_index = int(data.get("monitor_index", 0))
            if monitor_index <= 0:
                raise HTTPException(status_code=400, detail="monitor_index is required")
            result = move_window_to_screen(hwnd, monitor_index)
        else:
            raise HTTPException(status_code=400, detail="unsupported action")
        return {"status": "success", "action": action, "hwnd": hwnd, "result": result}
    except HTTPException:
        raise
    except Exception as e:
        raise HTTPException(status_code=400, detail=f"{type(e).__name__}: {e}")

@app.post("/config/wgc")
async def set_wgc(enabled: bool = True, max_dim: int = 720, quality: int = 72, fps: int = 30):
    global USE_WGC, WGC_REQUESTED, LIVE_MAX_DIM, LIVE_JPEG_QUALITY, LIVE_TARGET_FPS
    WGC_REQUESTED = True
    USE_WGC = WGC_AVAILABLE
    LIVE_MAX_DIM = float(max(360, min(max_dim, 3840)))
    LIVE_JPEG_QUALITY = max(35, min(quality, 100))
    LIVE_TARGET_FPS = float(max(5, min(fps, 160)))
    return {
        "use_wgc": USE_WGC,
        "wgc_requested": WGC_REQUESTED,
        "max_dim": int(LIVE_MAX_DIM),
        "quality": LIVE_JPEG_QUALITY,
        "fps": int(LIVE_TARGET_FPS)
    }

@app.post("/config/grid_preview")
async def set_grid_preview(interval_ms: int = 2000):
    global GRID_PREVIEW_INTERVAL
    GRID_PREVIEW_INTERVAL = max(0.5, min(float(interval_ms) / 1000.0, 10.0))
    return {"interval_ms": int(GRID_PREVIEW_INTERVAL * 1000)}

@app.post("/config/window_filter")
async def set_window_filter(request: Request):
    global WINDOW_FILTER_CONFIG
    try:
        data = await request.json()
    except Exception:
        data = {}

    def clean_list(value):
        if not isinstance(value, list):
            return []
        return [str(v).strip() for v in value if str(v).strip()]

    WINDOW_FILTER_CONFIG = {
        "enabled": bool(data.get("enabled", True)),
        "hide_system_windows": bool(data.get("hide_system_windows", True)),
        "title_contains": clean_list(data.get("title_contains", [])),
        "process_names": clean_list(data.get("process_names", [])),
        "class_names": clean_list(data.get("class_names", [])),
    }
    if connected_clients:
        msg = json.dumps({"type": "list", "data": get_windows_list_minimal()})
        for ws in list(connected_clients):
            try:
                await ws.send_text(msg)
            except Exception:
                pass
    return WINDOW_FILTER_CONFIG

@app.get("/screens")
async def screens_endpoint():
    return get_screens_list()

@app.websocket("/input/{hwnd}")
async def input_endpoint(websocket: WebSocket, hwnd: int):
    await websocket.accept()
    try:
        if await websocket.receive_text() != PASSWORD:
            await websocket.send_text(json.dumps({"type": "error", "message": "auth failed"}))
            await safe_close(websocket)
            return
        print(f"[input] connected hwnd={hwnd} title={win32gui.GetWindowText(hwnd)!r} build={INPUT_TOUCH_BUILD}", flush=True)
        await websocket.send_text(json.dumps({"type": "status", "message": f"input connected {INPUT_TOUCH_BUILD}"}))

        while not shutdown_event.is_set():
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
                event_type = data.get("type")
                if event_type == "mouse":
                    handle_mouse_input(hwnd, data)
                elif event_type == "touch":
                    handle_touch_input(hwnd, data)
                elif event_type == "text":
                    text = data.get("text", "")
                    print(f"[input] text hwnd={hwnd} chars={len(text)} text={text!r}", flush=True)
                    focus_window(hwnd, aggressive=True)
                    send_unicode_text(text)
                elif event_type == "key":
                    print(f"[input] key hwnd={hwnd} key={data.get('key', '')!r}", flush=True)
                    focus_window(hwnd, aggressive=True)
                    send_key_name(data.get("key", ""))
                elif event_type == "shortcut":
                    print(f"[input] shortcut hwnd={hwnd} keys={data.get('keys', [])!r}", flush=True)
                    focus_window(hwnd, aggressive=True)
                    send_shortcut(data.get("keys", []))
                else:
                    await websocket.send_text(json.dumps({"type": "error", "message": f"unknown input type: {event_type}"}))
            except Exception as e:
                await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[input] failed hwnd={hwnd}: {type(e).__name__}: {e}", flush=True)
    finally:
        await safe_close(websocket)

@app.websocket("/input/screen/{monitor_index}")
async def input_screen_endpoint(websocket: WebSocket, monitor_index: int):
    await websocket.accept()
    try:
        if await websocket.receive_text() != PASSWORD:
            await websocket.send_text(json.dumps({"type": "error", "message": "auth failed"}))
            await safe_close(websocket)
            return
        screen = get_screen_rect(monitor_index)
        print(f"[input] connected screen={monitor_index} rect={screen}", flush=True)
        await websocket.send_text(json.dumps({"type": "status", "message": f"screen input connected {monitor_index}"}))

        while not shutdown_event.is_set():
            raw = await websocket.receive_text()
            try:
                data = json.loads(raw)
                event_type = data.get("type")
                if event_type == "mouse":
                    handle_mouse_screen_input(monitor_index, data)
                elif event_type == "touch":
                    handle_touch_screen_input(monitor_index, data)
                elif event_type == "text":
                    send_unicode_text(data.get("text", ""))
                elif event_type == "key":
                    send_key_name(data.get("key", ""))
                elif event_type == "shortcut":
                    send_shortcut(data.get("keys", []))
                else:
                    await websocket.send_text(json.dumps({"type": "error", "message": f"unknown input type: {event_type}"}))
            except Exception as e:
                await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[input] screen failed monitor={monitor_index}: {type(e).__name__}: {e}", flush=True)
    finally:
        await safe_close(websocket)

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    try:
        if await websocket.receive_text() != PASSWORD: await websocket.send_text("error: auth failed"); await safe_close(websocket); return
        connected_clients.add(websocket)
        await websocket.send_json({"type": "list", "data": get_windows_list_minimal()})
        while True:
            msg = await websocket.receive_text()
            if msg.startswith("observe:"):
                global current_obs_hwnd; current_obs_hwnd = int(msg.split(":")[1])
            elif msg == "ping": await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[ws] failed: {type(e).__name__}: {e}", flush=True)
    finally: connected_clients.discard(websocket)


@app.websocket("/live/{hwnd}")
async def live_stream_endpoint(
    websocket: WebSocket,
    hwnd: int,
    max_dim: int | None = None,
    quality: int | None = None,
    fps: int | None = None
):
    global active_live_streams
    await websocket.accept()
    capture = None
    capture_control = None
    active_live_streams += 1
    try:
        stream_use_wgc = True
        stream_max_dim = float(max(360, min(max_dim if max_dim is not None else int(LIVE_MAX_DIM), 3840)))
        stream_quality = max(35, min(quality if quality is not None else LIVE_JPEG_QUALITY, 100))
        stream_fps = float(max(5, min(fps if fps is not None else int(LIVE_TARGET_FPS), 160)))

        if not WGC_AVAILABLE:
            print(f"[live] hwnd={hwnd} requested WGC but windows_capture is unavailable", flush=True)
            await safe_close(websocket)
            return

        title = win32gui.GetWindowText(hwnd)
        print(
            f"[live] start WGC hwnd={hwnd} title={title!r} max_dim={stream_max_dim:.0f} "
            f"quality={stream_quality} fps={stream_fps:.0f}",
            flush=True
        )
        capture = WindowsCapture(window_name=title, draw_border=False)
        wgc_buffer = collections.deque(maxlen=1)
        last_accepted_frame = 0.0
        received_frames = 0
        sent_frames = 0
        last_log_at = time.perf_counter()
        
        loop = asyncio.get_running_loop()
        frame_event = asyncio.Event()

        @capture.event
        def on_frame_arrived(frame: Frame, _):
            nonlocal last_accepted_frame, received_frames
            try:
                now = time.perf_counter()
                min_frame_interval = 1.0 / max(stream_fps, 1.0)
                if now - last_accepted_frame < min_frame_interval:
                    return
                last_accepted_frame = now
                received_frames += 1
                if received_frames == 1:
                    print(f"[live] first WGC frame arrived hwnd={hwnd}", flush=True)
                wgc_buffer.append(frame.convert_to_bgr().frame_buffer.copy())
                loop.call_soon_threadsafe(frame_event.set)
            except Exception as e:
                print(f"[live] WGC frame callback failed hwnd={hwnd}: {type(e).__name__}: {e}", flush=True)

        @capture.event
        def on_closed(*_):
            print(f"[live] WGC closed hwnd={hwnd}", flush=True)
            try:
                loop.call_soon_threadsafe(frame_event.set)
            except Exception:
                pass

        capture_control = capture.start_free_threaded()

        def process_wgc_frame(img_array):
            h, w = img_array.shape[:2]
            if w > stream_max_dim or h > stream_max_dim:
                scale = min(stream_max_dim/w, stream_max_dim/h)
                new_w, new_h = max(1, int(w * scale)), max(1, int(h * scale))
                img_s = cv2.resize(img_array, (new_w, new_h), interpolation=cv2.INTER_AREA)
            else:
                img_s = img_array
            ok, buf = cv2.imencode('.jpg', img_s, [int(cv2.IMWRITE_JPEG_QUALITY), stream_quality])
            return buf.tobytes() if ok else b""
            
        while not shutdown_event.is_set():
            try:
                await asyncio.wait_for(frame_event.wait(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            frame_event.clear()

            if capture_control and capture_control.is_finished() and not wgc_buffer:
                break
            if wgc_buffer:
                img = wgc_buffer.pop()
                try:
                    jpg_bytes = await loop.run_in_executor(live_stream_pool, process_wgc_frame, img)
                    if jpg_bytes:
                        await websocket.send_bytes(jpg_bytes)
                        sent_frames += 1
                        now = time.perf_counter()
                        if sent_frames == 1 or now - last_log_at >= 3.0:
                            last_log_at = now
                            print(f"[live] sent WGC frame hwnd={hwnd} count={sent_frames} bytes={len(jpg_bytes)}", flush=True)
                except Exception:
                    print(f"[live] send WGC frame failed hwnd={hwnd}", flush=True)
                    break
                
    except asyncio.CancelledError:
        # 【修复3】：安静地接受 FastAPI 的退出信号，直接结束协程
        pass 
    except Exception as e:
        # 【修复4】：将 except: 改为 except Exception: ，绝不吞噬系统级退出信号
        print(f"[live] stream failed hwnd={hwnd}: {type(e).__name__}: {e}", flush=True)
    finally:
        active_live_streams = max(0, active_live_streams - 1)
        if capture_control:
            try: 
                capture_control.stop()
            except Exception: 
                pass
        await safe_close(websocket)

@app.websocket("/live/screen/{monitor_index}")
async def live_screen_stream_endpoint(
    websocket: WebSocket,
    monitor_index: int,
    max_dim: int | None = None,
    quality: int | None = None,
    fps: int | None = None
):
    global active_live_streams
    await websocket.accept()
    capture = None
    capture_control = None
    active_live_streams += 1
    try:
        if not WGC_AVAILABLE:
            await safe_close(websocket)
            return
        _ = get_screen_rect(monitor_index)
        stream_max_dim = float(max(360, min(max_dim if max_dim is not None else int(LIVE_MAX_DIM), 3840)))
        stream_quality = max(35, min(quality if quality is not None else LIVE_JPEG_QUALITY, 100))
        stream_fps = float(max(5, min(fps if fps is not None else int(LIVE_TARGET_FPS), 160)))
        print(f"[live] start WGC screen={monitor_index} max_dim={stream_max_dim:.0f} quality={stream_quality} fps={stream_fps:.0f}", flush=True)
        capture = WindowsCapture(monitor_index=monitor_index, draw_border=False)
        wgc_buffer = collections.deque(maxlen=1)
        last_accepted_frame = 0.0
        loop = asyncio.get_running_loop()
        frame_event = asyncio.Event()

        @capture.event
        def on_frame_arrived(frame: Frame, _):
            nonlocal last_accepted_frame
            now = time.perf_counter()
            if now - last_accepted_frame < (1.0 / max(stream_fps, 1.0)):
                return
            last_accepted_frame = now
            wgc_buffer.append(frame.convert_to_bgr().frame_buffer.copy())
            loop.call_soon_threadsafe(frame_event.set)

        @capture.event
        def on_closed(*_):
            loop.call_soon_threadsafe(frame_event.set)

        capture_control = capture.start_free_threaded()

        def process_wgc_frame(img_array):
            h, w = img_array.shape[:2]
            if w > stream_max_dim or h > stream_max_dim:
                scale = min(stream_max_dim / w, stream_max_dim / h)
                img_array = cv2.resize(img_array, (max(1, int(w * scale)), max(1, int(h * scale))), interpolation=cv2.INTER_AREA)
            ok, buf = cv2.imencode('.jpg', img_array, [int(cv2.IMWRITE_JPEG_QUALITY), stream_quality])
            return buf.tobytes() if ok else b""

        while not shutdown_event.is_set():
            try:
                await asyncio.wait_for(frame_event.wait(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            frame_event.clear()
            if capture_control and capture_control.is_finished() and not wgc_buffer:
                break
            if wgc_buffer:
                jpg_bytes = await loop.run_in_executor(live_stream_pool, process_wgc_frame, wgc_buffer.pop())
                if jpg_bytes:
                    await websocket.send_bytes(jpg_bytes)
    except Exception as e:
        print(f"[live] screen stream failed monitor={monitor_index}: {type(e).__name__}: {e}", flush=True)
    finally:
        active_live_streams = max(0, active_live_streams - 1)
        if capture_control:
            try:
                capture_control.stop()
            except Exception:
                pass
        await safe_close(websocket)

# ---------------- 捕获 CancelledError 异常，防止红字报错 ----------------
async def list_broadcaster():
    last_hash = ""
    while True:
        try:
            if connected_clients:
                data = await asyncio.to_thread(get_windows_list_minimal)
                data_json = json.dumps({"type": "list", "data": data})
                curr_hash = hashlib.md5(data_json.encode()).hexdigest()
                if curr_hash != last_hash:
                    last_hash = curr_hash
                    for ws in list(connected_clients): await ws.send_text(data_json)
            await asyncio.sleep(5.0 if active_live_streams else 1.5)
        except asyncio.CancelledError: break  # 关键修复！退出时安静结束任务
        except Exception: await asyncio.sleep(1.5)

async def grid_preview_broadcaster():
    def fetch_grid_previews():
        windows = get_windows_list_minimal(); previews = {}
        for w in windows:
            p = get_window_preview_gdi(w["hwnd"], q=25, w_limit=256, h_limit=192)
            if p: previews[str(w["hwnd"])] = p
        return previews

    while True:
        try:
            if connected_clients and active_live_streams == 0:
                previews = await asyncio.to_thread(fetch_grid_previews)
                if previews:
                    msg = json.dumps({"type": "grid_previews", "data": previews})
                    for ws in list(connected_clients): await ws.send_text(msg)
            await asyncio.sleep(GRID_PREVIEW_INTERVAL)
        except asyncio.CancelledError: break # 关键修复！
        except Exception: await asyncio.sleep(GRID_PREVIEW_INTERVAL)

def udp_discovery():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    sock.setsockopt(socket.SOL_SOCKET, socket.SO_BROADCAST, 1)
    msg = json.dumps({"type": "tskb_sync", "port": PORT}).encode()
    while not shutdown_event.is_set(): # 关键修复！Ctrl+C时线程自动结束
        try: sock.sendto(msg, ('255.255.255.255', DISCOVERY_PORT))
        except: pass
        shutdown_event.wait(2.0)

if __name__ == "__main__":
    uvicorn.run("main:app", host="0.0.0.0", port=PORT, log_level="warning")
