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
try: ctypes.windll.shcore.SetProcessDpiAwareness(1)
except Exception: ctypes.windll.user32.SetProcessDPIAware()

# --- Windows API Setup --- (保持不变，省略展开以节省空间，直接用你原来的API声明即可)
HANDLE = ctypes.c_size_t
gdi32 = ctypes.windll.gdi32
user32 = ctypes.windll.user32
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

def get_windows_list_minimal():
    windows = []
    foreground_hwnd = win32gui.GetForegroundWindow()
    import win32process
    win32gui.EnumWindows(lambda h, _: windows.append(h) if is_taskbar_window(h) else None, None)
    res = []
    for h in windows:
        res.append({"hwnd": h, "title": win32gui.GetWindowText(h), "icon": get_window_best_icon(h), "preview": "", "is_active": (h == foreground_hwnd), "pid": win32process.GetWindowThreadProcessId(h)[1]})
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
current_obs_hwnd = 0
connected_clients = set()

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

@app.post("/switch/{hwnd}")
async def switch_window(hwnd: int, request: Request):
    if request.headers.get("password") != PASSWORD: return JSONResponse(status_code=401, content={"message": "Invalid password"})
    if win32gui.IsIconic(hwnd): win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
    win32api.keybd_event(win32con.VK_MENU, 0, 0, 0); win32gui.SetForegroundWindow(hwnd); win32api.keybd_event(win32con.VK_MENU, 0, win32con.KEYEVENTF_KEYUP, 0)
    return {"status": "success"}

@app.post("/config/wgc")
async def set_wgc(enabled: bool, max_dim: int = 720, quality: int = 72, fps: int = 30):
    global USE_WGC, WGC_REQUESTED, LIVE_MAX_DIM, LIVE_JPEG_QUALITY, LIVE_TARGET_FPS
    WGC_REQUESTED = enabled
    USE_WGC = enabled and WGC_AVAILABLE
    LIVE_MAX_DIM = float(max(360, min(max_dim, 1440)))
    LIVE_JPEG_QUALITY = max(35, min(quality, 95))
    LIVE_TARGET_FPS = float(max(5, min(fps, 60)))
    return {
        "use_wgc": USE_WGC,
        "wgc_requested": WGC_REQUESTED,
        "max_dim": int(LIVE_MAX_DIM),
        "quality": LIVE_JPEG_QUALITY,
        "fps": int(LIVE_TARGET_FPS)
    }

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
    except: pass
    finally: connected_clients.discard(websocket)


@app.websocket("/live/{hwnd}")
async def live_stream_endpoint(
    websocket: WebSocket,
    hwnd: int,
    use_wgc: bool | None = None,
    max_dim: int | None = None,
    quality: int | None = None,
    fps: int | None = None
):
    await websocket.accept()
    capture = None
    capture_control = None
    try:
        stream_use_wgc = WGC_REQUESTED if use_wgc is None else use_wgc
        stream_max_dim = float(max(360, min(max_dim if max_dim is not None else int(LIVE_MAX_DIM), 1440)))
        stream_quality = max(35, min(quality if quality is not None else LIVE_JPEG_QUALITY, 95))
        stream_fps = float(max(5, min(fps if fps is not None else int(LIVE_TARGET_FPS), 60)))

        if stream_use_wgc:
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
                # 如果画面很大才需要缩放，小窗口直接发，大幅节约 CPU 算力
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
                                print(
                                    f"[live] sent WGC frame hwnd={hwnd} count={sent_frames} bytes={len(jpg_bytes)}",
                                    flush=True
                                )
                        
                        # send_bytes 会随网络背压阻塞；队列只保留最新帧，避免延迟越积越大。
                    except Exception:
                        print(f"[live] send WGC frame failed hwnd={hwnd}", flush=True)
                        break
        else:
            print(f"[live] start GDI hwnd={hwnd}", flush=True)
            while not shutdown_event.is_set():
                p = await asyncio.to_thread(get_window_preview_gdi, hwnd, 65, 800, 600)
                if p: await websocket.send_bytes(base64.b64decode(p))
                await asyncio.sleep(0.04)
                
    except asyncio.CancelledError:
        # 【修复3】：安静地接受 FastAPI 的退出信号，直接结束协程
        pass 
    except Exception as e:
        # 【修复4】：将 except: 改为 except Exception: ，绝不吞噬系统级退出信号
        print(f"[live] stream failed hwnd={hwnd}: {type(e).__name__}: {e}", flush=True)
    finally:
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
            await asyncio.sleep(1.5)
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
            if connected_clients:
                previews = await asyncio.to_thread(fetch_grid_previews)
                if previews:
                    msg = json.dumps({"type": "grid_previews", "data": previews})
                    for ws in list(connected_clients): await ws.send_text(msg)
            await asyncio.sleep(5.0)
        except asyncio.CancelledError: break # 关键修复！
        except Exception: await asyncio.sleep(5.0)

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
