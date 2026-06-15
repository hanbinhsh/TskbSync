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
import subprocess
import shutil
import os
import uuid
import struct
from pathlib import Path
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
    import numpy as np
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

def resolve_ffmpeg_candidate(value):
    raw = (value or "").strip().strip('"')
    if not raw:
        return ""
    path = Path(raw).expanduser()
    if path.is_dir():
        path = path / "ffmpeg.exe"
    if path.exists() and path.is_file():
        return str(path)
    return ""

def get_ffmpeg_exe():
    backend_dir = Path(__file__).resolve().parent
    for env_key in ("TSKBSYNC_FFMPEG", "TSKBSYNC_FFMPEG_DIR"):
        resolved = resolve_ffmpeg_candidate(os.environ.get(env_key, ""))
        if resolved:
            return resolved
    path_file = backend_dir / "ffmpeg_path.txt"
    if path_file.exists():
        try:
            for line in path_file.read_text(encoding="utf-8").splitlines():
                stripped = line.strip()
                if not stripped or stripped.startswith("#"):
                    continue
                resolved = resolve_ffmpeg_candidate(stripped)
                if resolved:
                    return resolved
        except Exception:
            pass
    local_ffmpeg = backend_dir / "bin" / "ffmpeg.exe"
    if local_ffmpeg.exists():
        return str(local_ffmpeg)
    return shutil.which("ffmpeg")

_h264_encoder_probe_cache = {
    "probed": False,
    "ffmpeg_path": "",
    "encoder": None,
    "encoder_profile": "",
    "encoder_options": [],
    "error": "",
    "results": [],
}
_ffmpeg_gdigrab_probe_cache = {"probed": False, "usable": False, "message": ""}
_native_streamer_probe_cache = {}

def get_native_streamer_exe():
    backend_dir = Path(__file__).resolve().parent
    candidates = [
        backend_dir / "native_streamer" / "build" / "tskbsync_native_streamer.exe",
        backend_dir / "native_streamer" / "build" / "Release" / "tskbsync_native_streamer.exe",
        backend_dir / "native_streamer" / "build" / "Debug" / "tskbsync_native_streamer.exe",
        backend_dir / "bin" / "tskbsync_native_streamer.exe",
    ]
    for path in candidates:
        if path.exists() and path.is_file():
            return str(path)
    return ""

def h264_encoder_option_variants(encoder, fps):
    fps_int = max(5, min(int(round(fps)), 160))
    if encoder == "h264_nvenc":
        return [
            ("nvenc-modern-ull", [
                "-c:v", encoder,
                "-preset", "p1",
                "-tune", "ull",
                "-rc", "constqp",
                "-qp", "24",
                "-bf", "0",
                "-g", str(fps_int),
                "-forced-idr", "1",
                "-pix_fmt", "yuv420p",
            ]),
            ("nvenc-legacy-llhp", [
                "-c:v", encoder,
                "-preset", "llhp",
                "-rc", "constqp",
                "-qp", "24",
                "-bf", "0",
                "-g", str(fps_int),
                "-pix_fmt", "yuv420p",
            ]),
            ("nvenc-legacy-hp", [
                "-c:v", encoder,
                "-preset", "hp",
                "-rc", "constqp",
                "-qp", "24",
                "-bf", "0",
                "-g", str(fps_int),
                "-pix_fmt", "yuv420p",
            ]),
            ("nvenc-minimal", [
                "-c:v", encoder,
                "-bf", "0",
                "-g", str(fps_int),
                "-pix_fmt", "yuv420p",
            ]),
        ]
    if encoder == "h264_qsv":
        return [
            ("qsv-lowlatency", [
                "-c:v", encoder,
                "-preset", "veryfast",
                "-look_ahead", "0",
                "-bf", "0",
                "-g", str(fps_int),
                "-global_quality", "24",
                "-pix_fmt", "nv12",
            ]),
            ("qsv-minimal", [
                "-c:v", encoder,
                "-bf", "0",
                "-g", str(fps_int),
                "-pix_fmt", "nv12",
            ]),
        ]
    if encoder == "h264_amf":
        return [
            ("amf-lowlatency", [
                "-c:v", encoder,
                "-quality", "speed",
                "-usage", "lowlatency",
                "-bf", "0",
                "-g", str(fps_int),
                "-qp_i", "24",
                "-qp_p", "24",
                "-pix_fmt", "yuv420p",
            ]),
            ("amf-minimal", [
                "-c:v", encoder,
                "-bf", "0",
                "-g", str(fps_int),
                "-pix_fmt", "yuv420p",
            ]),
        ]
    return [("minimal", ["-c:v", encoder, "-bf", "0", "-g", str(fps_int), "-pix_fmt", "yuv420p"])]

def estimate_h264_bitrate_kbps(width, height, fps, quality):
    fps_int = max(5, min(int(round(fps)), 160))
    quality = max(35, min(int(quality), 100))
    scale = (width * height * fps_int) / float(1920 * 1080 * 60)
    quality_curve = (quality / 100.0) ** 2
    target_mbps = scale * (4.0 + quality_curve * 28.0)
    target_kbps = int(max(1800, min(target_mbps * 1000, 85000)))
    return target_kbps

def h264_runtime_encoder_options(encoder, fps, width, height, quality):
    fps_int = max(5, min(int(round(fps)), 160))
    bitrate = estimate_h264_bitrate_kbps(width, height, fps_int, quality)
    maxrate = int(bitrate * 1.35)
    bufsize = max(1000, int(bitrate * 0.5))
    cq = max(18, min(32, int(round(34 - (max(35, min(quality, 100)) - 35) * 12 / 65))))
    if encoder == "h264_nvenc":
        profile = _h264_encoder_probe_cache.get("encoder_profile") or ""
        if profile == "nvenc-modern-ull":
            return [
                "-c:v", encoder,
                "-preset", "p1",
                "-tune", "ull",
                "-rc", "vbr",
                "-cq", str(cq),
                "-b:v", f"{bitrate}k",
                "-maxrate", f"{maxrate}k",
                "-bufsize", f"{bufsize}k",
                "-bf", "0",
                "-g", str(fps_int),
                "-forced-idr", "1",
                "-pix_fmt", "yuv420p",
            ], bitrate
        return [
            "-c:v", encoder,
            "-preset", "llhp" if profile == "nvenc-legacy-llhp" else "hp",
            "-rc", "vbr",
            "-cq", str(cq),
            "-b:v", f"{bitrate}k",
            "-maxrate", f"{maxrate}k",
            "-bufsize", f"{bufsize}k",
            "-bf", "0",
            "-g", str(fps_int),
            "-pix_fmt", "yuv420p",
        ], bitrate
    if encoder == "h264_qsv":
        return [
            "-c:v", encoder,
            "-preset", "veryfast",
            "-look_ahead", "0",
            "-b:v", f"{bitrate}k",
            "-maxrate", f"{maxrate}k",
            "-bufsize", f"{bufsize}k",
            "-bf", "0",
            "-g", str(fps_int),
            "-pix_fmt", "nv12",
        ], bitrate
    if encoder == "h264_amf":
        return [
            "-c:v", encoder,
            "-quality", "speed",
            "-usage", "lowlatency",
            "-b:v", f"{bitrate}k",
            "-maxrate", f"{maxrate}k",
            "-bufsize", f"{bufsize}k",
            "-bf", "0",
            "-g", str(fps_int),
            "-pix_fmt", "yuv420p",
        ], bitrate
    return h264_encoder_option_variants(encoder, fps_int)[-1][1], bitrate

def h264_ddagrab_nvenc_options(fps, width, height, quality):
    fps_int = max(5, min(int(round(fps)), 160))
    bitrate = estimate_h264_bitrate_kbps(width, height, fps_int, quality)
    maxrate = int(bitrate * 1.35)
    bufsize = max(1000, int(bitrate * 0.5))
    cq = max(18, min(32, int(round(34 - (max(35, min(quality, 100)) - 35) * 12 / 65))))
    return [
        "-c:v", "h264_nvenc",
        "-preset", "p1",
        "-tune", "ull",
        "-rc", "vbr",
        "-cq", str(cq),
        "-b:v", f"{bitrate}k",
        "-maxrate", f"{maxrate}k",
        "-bufsize", f"{bufsize}k",
        "-bf", "0",
        "-g", str(fps_int),
        "-forced-idr", "1",
        "-aud", "1",
        "-zerolatency", "1",
        "-delay", "0",
        "-rc-lookahead", "0",
        "-surfaces", "4",
    ], bitrate

def probe_h264_encoder(ffmpeg, encoder):
    failures = []
    for profile, options in h264_encoder_option_variants(encoder, 30):
        cmd = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=640x360:rate=30,format=yuv420p",
            "-t",
            "0.2",
            *options,
            "-f",
            "null",
            "-",
        ]
        try:
            result = subprocess.run(
                cmd,
                text=True,
                capture_output=True,
                timeout=8,
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
            if result.returncode == 0:
                return True, "", profile, options
            failures.append(f"{profile}: {(result.stderr or result.stdout or '').strip()}")
        except Exception as e:
            failures.append(f"{profile}: {type(e).__name__}: {e}")
    return False, " || ".join(failures), "", []

def get_h264_probe_error():
    return _h264_encoder_probe_cache.get("error") or "No usable hardware H.264 encoder found"

def get_h264_hardware_encoder(force_probe=False):
    global _h264_encoder_probe_cache
    if _h264_encoder_probe_cache.get("probed") and not force_probe:
        return _h264_encoder_probe_cache.get("encoder")
    ffmpeg = get_ffmpeg_exe()
    if not ffmpeg:
        _h264_encoder_probe_cache = {
            "probed": True,
            "ffmpeg_path": "",
            "encoder": None,
            "encoder_profile": "",
            "encoder_options": [],
            "error": "ffmpeg.exe was not found. Set TSKBSYNC_FFMPEG/TSKBSYNC_FFMPEG_DIR, write a path to pc_backend\\ffmpeg_path.txt, put ffmpeg.exe in pc_backend\\bin, or add ffmpeg to PATH.",
            "results": [],
        }
        return None
    try:
        output = subprocess.check_output([ffmpeg, "-hide_banner", "-encoders"], text=True, errors="ignore")
    except Exception as e:
        _h264_encoder_probe_cache = {
            "probed": True,
            "ffmpeg_path": ffmpeg,
            "encoder": None,
            "encoder_profile": "",
            "encoder_options": [],
            "error": f"Failed to query ffmpeg encoders: {type(e).__name__}: {e}",
            "results": [],
        }
        return None
    errors = []
    results = []
    for encoder in ("h264_nvenc", "h264_qsv", "h264_amf"):
        if encoder in output:
            ok, err, profile, options = probe_h264_encoder(ffmpeg, encoder)
            results.append({
                "encoder": encoder,
                "available": True,
                "usable": ok,
                "profile": profile,
                "message": "" if ok else err,
            })
            if ok:
                _h264_encoder_probe_cache = {
                    "probed": True,
                    "ffmpeg_path": ffmpeg,
                    "encoder": encoder,
                    "encoder_profile": profile,
                    "encoder_options": options,
                    "error": "",
                    "results": results,
                }
                return encoder
            errors.append(f"{encoder}: {err}")
        else:
            results.append({"encoder": encoder, "available": False, "usable": False, "profile": "", "message": "not listed by ffmpeg"})
            errors.append(f"{encoder}: not listed by ffmpeg")
    error_message = "No usable hardware H.264 encoder found. " + " | ".join(errors)
    if errors:
        print("[h264] " + error_message, flush=True)
    _h264_encoder_probe_cache = {
        "probed": True,
        "ffmpeg_path": ffmpeg,
        "encoder": None,
        "encoder_profile": "",
        "encoder_options": [],
        "error": error_message,
        "results": results,
    }
    return None

def get_h264_status(force_probe=False):
    encoder = get_h264_hardware_encoder(force_probe=force_probe)
    direct_ok, direct_message = probe_ffmpeg_gdigrab(force_probe=force_probe)
    native_path = get_native_streamer_exe()
    native_ok = bool(native_path)
    native_message = "native streamer exe found; runtime capture is checked when a stream starts" if native_ok else "native streamer exe was not found"
    return {
        "ffmpeg_path": _h264_encoder_probe_cache.get("ffmpeg_path") or get_ffmpeg_exe() or "",
        "selected_encoder": encoder or "",
        "selected_profile": _h264_encoder_probe_cache.get("encoder_profile") or "",
        "usable": bool(encoder),
        "message": _h264_encoder_probe_cache.get("error") or ("Hardware H.264 encoder ready" if encoder else ""),
        "native_streamer_path": native_path,
        "native_screen_capture": native_ok,
        "native_screen_message": native_message,
        "direct_screen_capture": direct_ok,
        "direct_screen_message": direct_message,
        "results": _h264_encoder_probe_cache.get("results") or [],
        "lookup_order": [
            "TSKBSYNC_FFMPEG",
            "TSKBSYNC_FFMPEG_DIR",
            str(Path(__file__).resolve().parent / "ffmpeg_path.txt"),
            str(Path(__file__).resolve().parent / "bin" / "ffmpeg.exe"),
            "PATH",
        ],
    }

def probe_native_streamer(monitor_index=1, force_probe=False):
    global _native_streamer_probe_cache
    key = int(monitor_index or 1)
    cached = _native_streamer_probe_cache.get(key)
    if cached and not force_probe:
        return bool(cached.get("usable")), cached.get("message", "")

    exe = get_native_streamer_exe()
    if not exe:
        message = "native streamer exe was not found"
        _native_streamer_probe_cache[key] = {"probed": True, "usable": False, "message": message}
        return False, message
    if not force_probe:
        message = "native streamer exe found; runtime capture is checked when a stream starts"
        _native_streamer_probe_cache[key] = {"probed": True, "usable": True, "message": message}
        return True, message

    try:
        screen = get_screen_rect(key)
        out_w, out_h = scaled_even_size(int(screen["width"]), int(screen["height"]), 640)
        cmd = [
            exe,
            "--left", str(int(screen["left"])),
            "--top", str(int(screen["top"])),
            "--width", str(int(screen["width"])),
            "--height", str(int(screen["height"])),
            "--out-width", str(out_w),
            "--out-height", str(out_h),
            "--fps", "30",
            "--quality", "60",
        ]
        proc = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        try:
            stdout, stderr = proc.communicate(timeout=1.2)
        except subprocess.TimeoutExpired:
            proc.terminate()
            try:
                stdout, stderr = proc.communicate(timeout=1.0)
            except subprocess.TimeoutExpired:
                proc.kill()
                stdout, stderr = proc.communicate(timeout=1.0)
        stderr_text = stderr.decode("utf-8", errors="replace").strip()
        usable = bool(stdout)
        message = "native streamer produced H.264 output" if usable else (stderr_text or f"native streamer exited with code {proc.returncode}")
        _native_streamer_probe_cache[key] = {"probed": True, "usable": usable, "message": message}
        return usable, message
    except Exception as e:
        message = f"{type(e).__name__}: {e}"
        _native_streamer_probe_cache[key] = {"probed": True, "usable": False, "message": message}
        return False, message

def probe_ffmpeg_gdigrab(force_probe=False):
    global _ffmpeg_gdigrab_probe_cache
    if _ffmpeg_gdigrab_probe_cache.get("probed") and not force_probe:
        return bool(_ffmpeg_gdigrab_probe_cache.get("usable")), _ffmpeg_gdigrab_probe_cache.get("message", "")
    ffmpeg = get_ffmpeg_exe()
    if not ffmpeg:
        _ffmpeg_gdigrab_probe_cache = {"probed": True, "usable": False, "message": "ffmpeg.exe was not found"}
        return False, _ffmpeg_gdigrab_probe_cache["message"]
    cmd = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "gdigrab",
        "-framerate",
        "10",
        "-video_size",
        "16x16",
        "-i",
        "desktop",
        "-t",
        "0.05",
        "-f",
        "null",
        "-",
    ]
    try:
        result = subprocess.run(
            cmd,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.PIPE,
            timeout=5,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        message = result.stderr.decode("utf-8", errors="replace").strip()
        usable = result.returncode == 0
        _ffmpeg_gdigrab_probe_cache = {"probed": True, "usable": usable, "message": message}
        return usable, message
    except Exception as e:
        message = f"{type(e).__name__}: {e}"
        _ffmpeg_gdigrab_probe_cache = {"probed": True, "usable": False, "message": message}
        return False, message

def read_ffmpeg_stderr(proc):
    if not proc or proc.poll() is None or not proc.stderr:
        return ""
    try:
        data = proc.stderr.read()
        if isinstance(data, bytes):
            return data.decode("utf-8", errors="replace").strip()
        return (data or "").strip()
    except Exception:
        return ""

def resize_for_stream(img_array, max_dim):
    h, w = img_array.shape[:2]
    if w > max_dim or h > max_dim:
        scale = min(max_dim / w, max_dim / h)
        w, h = max(1, int(w * scale)), max(1, int(h * scale))
        img_array = cv2.resize(img_array, (w, h), interpolation=cv2.INTER_AREA)
    h, w = img_array.shape[:2]
    even_w = max(2, w - (w % 2))
    even_h = max(2, h - (h % 2))
    if even_w != w or even_h != h:
        img_array = cv2.resize(img_array, (even_w, even_h), interpolation=cv2.INTER_AREA)
    return img_array

def scaled_even_size(width, height, max_dim):
    width = max(2, int(width))
    height = max(2, int(height))
    max_dim = max(2, float(max_dim))
    scale = min(max_dim / width, max_dim / height, 1.0)
    out_w = max(2, int(round(width * scale)))
    out_h = max(2, int(round(height * scale)))
    out_w -= out_w % 2
    out_h -= out_h % 2
    return max(2, out_w), max(2, out_h)

def fit_frame_to_size(img_array, target_w, target_h):
    h, w = img_array.shape[:2]
    if w == target_w and h == target_h:
        return np.ascontiguousarray(img_array)
    scale = min(target_w / max(w, 1), target_h / max(h, 1))
    new_w = max(1, min(target_w, int(round(w * scale))))
    new_h = max(1, min(target_h, int(round(h * scale))))
    resized = cv2.resize(img_array, (new_w, new_h), interpolation=cv2.INTER_AREA)
    top = (target_h - new_h) // 2
    bottom = target_h - new_h - top
    left = (target_w - new_w) // 2
    right = target_w - new_w - left
    border_value = (0, 0, 0, 255) if len(resized.shape) == 3 and resized.shape[2] == 4 else (0, 0, 0)
    return cv2.copyMakeBorder(resized, top, bottom, left, right, cv2.BORDER_CONSTANT, value=border_value)

def start_h264_encoder(width, height, fps, quality):
    ffmpeg = get_ffmpeg_exe()
    encoder = get_h264_hardware_encoder()
    if not ffmpeg:
        raise RuntimeError("ffmpeg.exe was not found in PATH")
    if not encoder:
        raise RuntimeError(get_h264_probe_error())
    fps_int = max(5, min(int(round(fps)), 160))
    encoder_options, bitrate = h264_runtime_encoder_options(encoder, fps_int, width, height, quality)
    cmd = [
        ffmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "yuv420p",
        "-s",
        f"{width}x{height}",
        "-r",
        str(fps_int),
        "-i",
        "pipe:0",
        "-an",
        "-fflags",
        "nobuffer",
        "-flags",
        "low_delay",
        *encoder_options,
        "-flush_packets",
        "1",
        "-f",
        "h264",
        "pipe:1",
    ]
    profile = _h264_encoder_probe_cache.get("encoder_profile") or "default"
    print(f"[h264] starting ffmpeg encoder={encoder} profile={profile} size={width}x{height} fps={fps_int} target={bitrate}kbps", flush=True)
    return subprocess.Popen(
        cmd,
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        bufsize=0,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    ), encoder

def prepare_h264_input_frame(img_array, stream_max_dim, target_w, target_h):
    fitted = fit_frame_to_size(resize_for_stream(img_array, stream_max_dim), target_w, target_h)
    if len(fitted.shape) == 3 and fitted.shape[2] == 4:
        return cv2.cvtColor(fitted, cv2.COLOR_BGRA2YUV_I420).tobytes()
    return cv2.cvtColor(fitted, cv2.COLOR_BGR2YUV_I420).tobytes()

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
grid_preview_clients = set()
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
VIRTUAL_DISPLAY_MATCH_TOKENS = [
    "virtual display driver",
    "iddsampledriver",
    "parsec",
    "virtualdisplay",
    "virtual display",
    "vdd",
]
EXTENDED_DISPLAY_BINDING = {
    "client_id": "",
    "device": "",
    "monitor_index": 0,
}
PNP_DISPLAY_ENUM_ERROR = ""
LAST_VIRTUAL_PNP_DEVICE = None

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
MAX_TOUCH_POINTERS = 10
TOUCH_POINTER_ID = 0
TOUCH_PRESSURE_NORMAL = 512
INPUT_TOUCH_BUILD = "synthetic-touch-v4-multi"
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

class GUID(ctypes.Structure):
    _fields_ = [
        ("Data1", wintypes.DWORD),
        ("Data2", wintypes.WORD),
        ("Data3", wintypes.WORD),
        ("Data4", ctypes.c_ubyte * 8),
    ]

class DXGI_OUTPUT_DESC(ctypes.Structure):
    _fields_ = [
        ("DeviceName", wintypes.WCHAR * 32),
        ("DesktopCoordinates", wintypes.RECT),
        ("AttachedToDesktop", wintypes.BOOL),
        ("Rotation", ctypes.c_int),
        ("Monitor", wintypes.HMONITOR),
    ]

def _guid_from_string(value: str):
    return GUID.from_buffer_copy(uuid.UUID(value).bytes_le)

def _com_call(ptr, index, restype, *argtypes):
    vtbl = ctypes.cast(ptr, ctypes.POINTER(ctypes.POINTER(ctypes.c_void_p))).contents
    return ctypes.WINFUNCTYPE(restype, ctypes.c_void_p, *argtypes)(vtbl[index])

def _release_com(ptr):
    if ptr:
        try:
            _com_call(ptr, 2, ctypes.c_ulong)(ptr)
        except Exception:
            pass

def enumerate_dxgi_outputs():
    outputs = []
    factory = ctypes.c_void_p()
    try:
        dxgi = ctypes.WinDLL("dxgi")
        create_factory = dxgi.CreateDXGIFactory1
        create_factory.argtypes = [ctypes.POINTER(GUID), ctypes.POINTER(ctypes.c_void_p)]
        create_factory.restype = ctypes.c_long
        iid_factory1 = _guid_from_string("770aae78-f26f-4dba-a829-253c83d1b387")
        hr = create_factory(ctypes.byref(iid_factory1), ctypes.byref(factory))
        if (hr & 0x80000000) or not factory.value:
            raise RuntimeError(f"CreateDXGIFactory1 failed: 0x{hr & 0xffffffff:08x}")

        enum_adapters = _com_call(factory, 12, ctypes.c_long, ctypes.c_uint, ctypes.POINTER(ctypes.c_void_p))
        adapter_idx = 0
        while True:
            adapter = ctypes.c_void_p()
            hr = enum_adapters(factory, adapter_idx, ctypes.byref(adapter))
            if (hr & 0xffffffff) == 0x887A0002:
                break
            if (hr & 0x80000000) or not adapter.value:
                adapter_idx += 1
                continue
            try:
                enum_outputs = _com_call(adapter, 7, ctypes.c_long, ctypes.c_uint, ctypes.POINTER(ctypes.c_void_p))
                output_idx = 0
                while True:
                    output = ctypes.c_void_p()
                    hr = enum_outputs(adapter, output_idx, ctypes.byref(output))
                    if (hr & 0xffffffff) == 0x887A0002:
                        break
                    if (hr & 0x80000000) or not output.value:
                        output_idx += 1
                        continue
                    try:
                        desc = DXGI_OUTPUT_DESC()
                        get_desc = _com_call(output, 7, ctypes.c_long, ctypes.POINTER(DXGI_OUTPUT_DESC))
                        hr_desc = get_desc(output, ctypes.byref(desc))
                        if not (hr_desc & 0x80000000):
                            rect = desc.DesktopCoordinates
                            outputs.append({
                                "adapter_idx": adapter_idx,
                                "output_idx": output_idx,
                                "device": str(desc.DeviceName),
                                "hmonitor": int(desc.Monitor or 0),
                                "left": int(rect.left),
                                "top": int(rect.top),
                                "right": int(rect.right),
                                "bottom": int(rect.bottom),
                                "width": int(rect.right - rect.left),
                                "height": int(rect.bottom - rect.top),
                            })
                    finally:
                        _release_com(output)
                    output_idx += 1
            finally:
                _release_com(adapter)
            adapter_idx += 1
    except Exception as e:
        print(f"[screens] dxgi enumerate failed: {type(e).__name__}: {e}", flush=True)
    finally:
        _release_com(factory)
    return outputs

def get_display_devices():
    devices = []
    try:
        index = 0
        while True:
            try:
                device = win32api.EnumDisplayDevices(None, index)
            except Exception:
                break
            monitor_strings = []
            monitor_ids = []
            monitor_index = 0
            while True:
                try:
                    monitor = win32api.EnumDisplayDevices(device.DeviceName, monitor_index)
                except Exception:
                    break
                monitor_strings.append(str(getattr(monitor, "DeviceString", "") or ""))
                monitor_ids.append(str(getattr(monitor, "DeviceID", "") or ""))
                monitor_index += 1
            devices.append({
                "device": str(getattr(device, "DeviceName", "") or ""),
                "name": str(getattr(device, "DeviceString", "") or ""),
                "id": str(getattr(device, "DeviceID", "") or ""),
                "state": int(getattr(device, "StateFlags", 0) or 0),
                "monitors": monitor_strings,
                "monitor_ids": monitor_ids,
            })
            index += 1
    except Exception as e:
        print(f"[extended] display device enumerate failed: {type(e).__name__}: {e}", flush=True)
    return devices

def is_virtual_display_device(device: dict):
    haystack = " ".join([
        str(device.get("device", "")),
        str(device.get("name", "")),
        str(device.get("id", "")),
        " ".join(device.get("monitors", []) or []),
        " ".join(device.get("monitor_ids", []) or []),
    ]).lower()
    return any(token in haystack for token in VIRTUAL_DISPLAY_MATCH_TOKENS)

def get_display_device_map():
    return {str(device.get("device", "")).lower(): device for device in get_display_devices()}

def is_process_admin():
    try:
        return bool(ctypes.windll.shell32.IsUserAnAdmin())
    except Exception:
        return False

def run_powershell_json(script: str, timeout: float = 8.0):
    powershell = shutil.which("powershell.exe") or shutil.which("powershell") or shutil.which("pwsh.exe") or shutil.which("pwsh")
    if not powershell:
        raise RuntimeError("PowerShell was not found")
    completed = subprocess.run(
        [powershell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-Command", script],
        capture_output=True,
        text=True,
        timeout=timeout,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )
    if completed.returncode != 0:
        message = (completed.stderr or completed.stdout or "").strip()
        raise RuntimeError(message or f"PowerShell exited with {completed.returncode}")
    output = (completed.stdout or "").strip()
    if not output:
        return None
    return json.loads(output)

def normalize_pnp_device(raw):
    if not isinstance(raw, dict):
        return {}
    return {
        "friendly_name": str(raw.get("FriendlyName") or raw.get("Name") or ""),
        "instance_id": str(raw.get("InstanceId") or raw.get("InstanceID") or ""),
        "status": str(raw.get("Status") or ""),
        "class": str(raw.get("Class") or ""),
        "present": bool(raw.get("Present", False)),
    }

def parse_pnp_devices(data):
    if data is None:
        return []
    if isinstance(data, dict):
        data = [data]
    if not isinstance(data, list):
        return []
    return [normalize_pnp_device(item) for item in data if isinstance(item, dict)]

def get_pnp_display_devices():
    global PNP_DISPLAY_ENUM_ERROR
    PNP_DISPLAY_ENUM_ERROR = ""
    display_script = (
        "Get-PnpDevice -Class Display | "
        "Select-Object FriendlyName,InstanceId,Status,Class,Present | "
        "ConvertTo-Json -Compress"
    )
    try:
        data = run_powershell_json(display_script)
    except Exception as e:
        PNP_DISPLAY_ENUM_ERROR = str(e)
        print(f"[extended] pnp display enumerate failed: {type(e).__name__}: {e}", flush=True)
        return []
    devices = parse_pnp_devices(data)
    if any(is_virtual_pnp_device(device) for device in devices):
        return devices

    fallback_script = (
        "$rx='Virtual Display Driver|IddSampleDriver|VirtualDisplay|Virtual Display|VDD'; "
        "Get-PnpDevice | Where-Object { "
        "($_.FriendlyName -match $rx) -or ($_.InstanceId -match $rx) "
        "} | Select-Object FriendlyName,InstanceId,Status,Class,Present | ConvertTo-Json -Compress"
    )
    try:
        return devices + parse_pnp_devices(run_powershell_json(fallback_script, timeout=12.0))
    except Exception as e:
        PNP_DISPLAY_ENUM_ERROR = str(e)
        print(f"[extended] pnp fallback enumerate failed: {type(e).__name__}: {e}", flush=True)
        return devices

def is_virtual_pnp_device(device: dict):
    haystack = " ".join([
        str(device.get("friendly_name", "")),
        str(device.get("instance_id", "")),
        str(device.get("status", "")),
    ]).lower()
    return any(token in haystack for token in VIRTUAL_DISPLAY_MATCH_TOKENS)

def get_virtual_pnp_device():
    global LAST_VIRTUAL_PNP_DEVICE
    devices = [device for device in get_pnp_display_devices() if is_virtual_pnp_device(device)]
    deduped = {}
    for device in devices:
        key = str(device.get("instance_id", "")).lower()
        if key:
            deduped[key] = device
    devices = list(deduped.values()) if deduped else devices
    devices.sort(key=lambda d: (
        0 if str(d.get("status", "")).lower() == "ok" else 1,
        str(d.get("friendly_name", "")).lower(),
    ))
    if devices:
        LAST_VIRTUAL_PNP_DEVICE = devices[0]
        return devices[0]
    return LAST_VIRTUAL_PNP_DEVICE

def pnp_device_enabled(device: dict | None):
    if not device:
        return False
    return str(device.get("status", "")).strip().lower() == "ok"

def set_pnp_device_enabled(instance_id: str, enabled: bool):
    if not instance_id:
        raise RuntimeError("missing PnP device instance id")
    escaped = instance_id.replace("'", "''")
    command = "Enable-PnpDevice" if enabled else "Disable-PnpDevice"
    script = (
        f"{command} -InstanceId '{escaped}' -Confirm:$false -ErrorAction Stop; "
        "Start-Sleep -Milliseconds 300; "
        "Get-PnpDevice -InstanceId '{0}' | "
        "Select-Object FriendlyName,InstanceId,Status,Class,Present | "
        "ConvertTo-Json -Compress"
    ).format(escaped)
    data = run_powershell_json(script, timeout=20.0)
    return normalize_pnp_device(data) if isinstance(data, dict) else {}

def get_supported_display_modes(device_name: str, limit: int = 80):
    modes = []
    seen = set()
    index = 0
    while index < 512:
        try:
            mode = win32api.EnumDisplaySettings(device_name, index)
        except Exception:
            break
        width = int(getattr(mode, "PelsWidth", 0) or 0)
        height = int(getattr(mode, "PelsHeight", 0) or 0)
        fps = int(getattr(mode, "DisplayFrequency", 0) or 0)
        if width > 0 and height > 0:
            key = (width, height, fps)
            if key not in seen:
                seen.add(key)
                modes.append({"width": width, "height": height, "fps": fps})
        index += 1
    modes.sort(key=lambda m: (m["width"] * m["height"], m["fps"], m["width"], m["height"]), reverse=True)
    return modes[:limit]

def get_current_display_mode(device_name: str):
    try:
        mode = win32api.EnumDisplaySettings(device_name, win32con.ENUM_CURRENT_SETTINGS)
        return {
            "width": int(getattr(mode, "PelsWidth", 0) or 0),
            "height": int(getattr(mode, "PelsHeight", 0) or 0),
            "fps": int(getattr(mode, "DisplayFrequency", 0) or 0),
        }
    except Exception:
        return None

def get_screens_list():
    screens = []
    try:
        monitors = win32api.EnumDisplayMonitors()
        dxgi_outputs = enumerate_dxgi_outputs()
        dxgi_by_hmonitor = {int(item["hmonitor"]): item for item in dxgi_outputs if int(item.get("hmonitor") or 0)}
        dxgi_by_device = {str(item["device"]).lower(): item for item in dxgi_outputs if item.get("device")}
        display_devices = get_display_device_map()
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
            device = info.get("Device", f"Monitor {idx}")
            dxgi_output = dxgi_by_hmonitor.get(int(handle)) or dxgi_by_device.get(str(device).lower())
            display_device = display_devices.get(str(device).lower(), {})
            is_virtual = is_virtual_display_device(display_device) if display_device else False
            screens.append({
                "monitor_index": idx,
                "ddagrab_adapter_idx": int(dxgi_output["adapter_idx"]) if dxgi_output else 0,
                "ddagrab_output_idx": int(dxgi_output["output_idx"]) if dxgi_output else idx - 1,
                "ddagrab_left": int(dxgi_output["left"]) if dxgi_output else int(left),
                "ddagrab_top": int(dxgi_output["top"]) if dxgi_output else int(top),
                "ddagrab_right": int(dxgi_output["right"]) if dxgi_output else int(right),
                "ddagrab_bottom": int(dxgi_output["bottom"]) if dxgi_output else int(bottom),
                "ddagrab_width": int(dxgi_output["width"]) if dxgi_output else int(right - left),
                "ddagrab_height": int(dxgi_output["height"]) if dxgi_output else int(bottom - top),
                "name": device,
                "device": device,
                "left": int(left),
                "top": int(top),
                "right": int(right),
                "bottom": int(bottom),
                "width": int(right - left),
                "height": int(bottom - top),
                "is_primary": is_primary,
                "is_virtual": is_virtual,
                "virtual_driver": display_device.get("name", "") if is_virtual else "",
                "extended_owner": EXTENDED_DISPLAY_BINDING.get("client_id", "") if (
                    is_virtual and EXTENDED_DISPLAY_BINDING.get("device", "").lower() == str(device).lower()
                ) else "",
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

def get_screen_for_rect(left: int, top: int, right: int, bottom: int):
    best = None
    best_area = -1
    for screen in get_screens_list():
        ix_left = max(int(left), int(screen["left"]))
        iy_top = max(int(top), int(screen["top"]))
        ix_right = min(int(right), int(screen["right"]))
        iy_bottom = min(int(bottom), int(screen["bottom"]))
        area = max(0, ix_right - ix_left) * max(0, iy_bottom - iy_top)
        if area > best_area:
            best = screen
            best_area = area
    if best is None:
        raise RuntimeError("no screen found")
    return best

def get_window_capture_region(hwnd: int):
    if not win32gui.IsWindow(hwnd):
        raise RuntimeError(f"window not found: {hwnd}")
    if win32gui.IsIconic(hwnd):
        win32gui.ShowWindow(hwnd, win32con.SW_RESTORE)
        time.sleep(0.08)
    left, top, right, bottom = get_window_bounds(hwnd)
    if right <= left or bottom <= top:
        left, top, right, bottom = map(int, win32gui.GetWindowRect(hwnd))
    if right <= left or bottom <= top:
        raise RuntimeError(f"invalid window bounds: {left},{top},{right},{bottom}")
    screen = get_screen_for_rect(left, top, right, bottom)
    capture_left = max(int(left), int(screen["left"]))
    capture_top = max(int(top), int(screen["top"]))
    capture_right = min(int(right), int(screen["right"]))
    capture_bottom = min(int(bottom), int(screen["bottom"]))
    width = max(0, capture_right - capture_left)
    height = max(0, capture_bottom - capture_top)
    if width < 2 or height < 2:
        raise RuntimeError(f"window is outside visible desktop: {left},{top},{right},{bottom}")
    return screen, capture_left, capture_top, width, height

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


def _start_menu_dirs():
    dirs = []
    appdata = os.environ.get("APPDATA")
    programdata = os.environ.get("PROGRAMDATA")
    if appdata:
        dirs.append(Path(appdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs")
    if programdata:
        dirs.append(Path(programdata) / "Microsoft" / "Windows" / "Start Menu" / "Programs")
    return dirs

def get_start_menu_apps():
    apps = []
    seen = set()
    for root in _start_menu_dirs():
        if not root.exists():
            continue
        try:
            for item in root.rglob("*.lnk"):
                try:
                    path = str(item)
                    key = path.lower()
                    if key in seen:
                        continue
                    seen.add(key)
                    apps.append({
                        "label": item.stem,
                        "path": path,
                        "target": "",
                    })
                except Exception:
                    pass
        except Exception:
            pass
    apps.sort(key=lambda app: app.get("label", "").lower())
    return apps

def launch_start_menu_app(target: str):
    value = str(target or "").strip().strip('"')
    if not value:
        return False, "target is empty"
    path = Path(value).expanduser()
    if not path.exists():
        return False, "target not found"
    try:
        os.startfile(str(path))
        return True, "launched"
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"

def run_custom_command(command: str):
    value = str(command or "").strip()
    if not value:
        return False, "command is empty"
    if len(value) > 2048:
        return False, "command is too long"
    try:
        subprocess.Popen(
            value,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            shell=True,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        return True, "started"
    except Exception as e:
        return False, f"{type(e).__name__}: {e}"

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

def is_relative_mouse(data: dict) -> bool:
    return data.get("action") == "move_rel" or bool(data.get("relative"))

def get_mouse_button_flags(data: dict):
    button = data.get("button", "left")
    if button == "right":
        return MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP
    elif button == "middle":
        return MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP
    return MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP

def apply_mouse_buttons(data: dict):
    action = data.get("action", "move")
    down_flag, up_flag = get_mouse_button_flags(data)
    if action == "down":
        user32.mouse_event(down_flag, 0, 0, 0, 0)
    elif action == "up":
        user32.mouse_event(up_flag, 0, 0, 0, 0)
    elif action == "click":
        user32.mouse_event(down_flag, 0, 0, 0, 0)
        user32.mouse_event(up_flag, 0, 0, 0, 0)
    elif action == "wheel":
        user32.mouse_event(MOUSEEVENTF_WHEEL, 0, 0, int(data.get("delta", 0)), 0)

def handle_mouse_relative(data: dict):
    # Touchpad-style: move the cursor by a delta from its current position,
    # and perform clicks/wheel wherever the cursor already is (no repositioning).
    action = data.get("action", "move")
    if action == "move_rel":
        dx = int(round(data.get("dx", 0.0)))
        dy = int(round(data.get("dy", 0.0)))
        if dx != 0 or dy != 0:
            user32.mouse_event(MOUSEEVENTF_MOVE, dx, dy, 0, 0)
        return
    apply_mouse_buttons(data)

def handle_mouse_input(hwnd: int, data: dict):
    if is_relative_mouse(data):
        handle_mouse_relative(data)
        return
    action = data.get("action", "move")
    sx, sy = get_window_screen_point(hwnd, data.get("x", 0.0), data.get("y", 0.0))
    if action in ("down", "click"):
        focus_window(hwnd, aggressive=True)
    handle_mouse_at_point(sx, sy, data)

def handle_mouse_at_point(sx: int, sy: int, data: dict):
    user32.SetCursorPos(sx, sy)
    apply_mouse_buttons(data)

def handle_mouse_screen_input(monitor_index: int, data: dict):
    if is_relative_mouse(data):
        handle_mouse_relative(data)
        return
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
    return build_touch_info_at_point_with_id(action, sx, sy, TOUCH_POINTER_ID, True)

def build_touch_info_at_point_with_id(action: str, sx: int, sy: int, pointer_id: int, primary: bool = False):
    tx, ty = to_virtual_screen_point(sx, sy)

    if action == "down":
        flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_DOWN
    elif action == "up":
        flags = POINTER_FLAG_UP
    else:
        flags = POINTER_FLAG_INRANGE | POINTER_FLAG_INCONTACT | POINTER_FLAG_UPDATE
    if primary:
        flags |= POINTER_FLAG_PRIMARY

    contact_size = 4
    touch = POINTER_TOUCH_INFO()
    touch.pointerInfo.pointerType = PT_TOUCH
    touch.pointerInfo.pointerId = max(0, min(int(pointer_id), MAX_TOUCH_POINTERS - 1))
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
        synthetic_touch_device = user32.CreateSyntheticPointerDevice(PT_TOUCH, MAX_TOUCH_POINTERS, TOUCH_FEEDBACK_DEFAULT)
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

def inject_synthetic_touches(touches: list[POINTER_TOUCH_INFO]):
    global synthetic_touch_device
    if not touches:
        return
    if not hasattr(user32, "CreateSyntheticPointerDevice") or not hasattr(user32, "InjectSyntheticPointerInput"):
        raise RuntimeError("Windows synthetic pointer API is unavailable")
    if not synthetic_touch_device:
        synthetic_touch_device = user32.CreateSyntheticPointerDevice(PT_TOUCH, MAX_TOUCH_POINTERS, TOUCH_FEEDBACK_DEFAULT)
        if not synthetic_touch_device:
            raise RuntimeError(f"CreateSyntheticPointerDevice failed: {ctypes.get_last_error()}")
    arr = (POINTER_TYPE_INFO * len(touches))()
    for idx, touch in enumerate(touches):
        arr[idx].type = PT_TOUCH
        arr[idx].u.touchInfo = touch
    if not user32.InjectSyntheticPointerInput(synthetic_touch_device, arr, len(touches)):
        raise RuntimeError(
            f"InjectSyntheticPointerInput multi failed: {ctypes.get_last_error()} "
            f"count={len(touches)} build={INPUT_TOUCH_BUILD}"
        )

def inject_legacy_touch(action: str, sx: int, sy: int, flags: int, touch: POINTER_TOUCH_INFO):
    global touch_initialized
    if not hasattr(user32, "InitializeTouchInjection") or not hasattr(user32, "InjectTouchInput"):
        raise RuntimeError("Windows touch injection API is unavailable")
    if not touch_initialized:
        if not user32.InitializeTouchInjection(MAX_TOUCH_POINTERS, TOUCH_FEEDBACK_DEFAULT):
            raise RuntimeError(f"InitializeTouchInjection failed: {ctypes.get_last_error()}")
        touch_initialized = True

    if not user32.InjectTouchInput(1, ctypes.byref(touch)):
        raise RuntimeError(
            f"InjectTouchInput failed: {ctypes.get_last_error()} "
            f"action={action} flags=0x{flags:x} point=({sx},{sy}) "
            f"mask=0x{touch.touchMask:x} size={ctypes.sizeof(POINTER_TOUCH_INFO)} "
            f"build={INPUT_TOUCH_BUILD}"
        )

def inject_legacy_touches(touches: list[POINTER_TOUCH_INFO]):
    global touch_initialized
    if not touches:
        return
    if not hasattr(user32, "InitializeTouchInjection") or not hasattr(user32, "InjectTouchInput"):
        raise RuntimeError("Windows touch injection API is unavailable")
    if not touch_initialized:
        if not user32.InitializeTouchInjection(MAX_TOUCH_POINTERS, TOUCH_FEEDBACK_DEFAULT):
            raise RuntimeError(f"InitializeTouchInjection failed: {ctypes.get_last_error()}")
        touch_initialized = True
    arr = (POINTER_TOUCH_INFO * len(touches))(*touches)
    if not user32.InjectTouchInput(len(touches), arr):
        raise RuntimeError(
            f"InjectTouchInput multi failed: {ctypes.get_last_error()} "
            f"count={len(touches)} build={INPUT_TOUCH_BUILD}"
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

def _point_action(point: dict):
    action = str(point.get("action", "move")).lower()
    if action in ("down", "up", "move"):
        return action
    return "move"

def build_multi_touch_infos_for_window(hwnd: int, data: dict):
    points = data.get("points") or []
    touches = []
    for idx, point in enumerate(points[:MAX_TOUCH_POINTERS]):
        action = _point_action(point)
        sx, sy = get_window_screen_point(hwnd, point.get("x", 0.0), point.get("y", 0.0))
        pointer_id = int(point.get("id", idx))
        primary = bool(point.get("primary", idx == 0))
        _, _, _, _, _, _, touch = build_touch_info_at_point_with_id(action, sx, sy, pointer_id, primary)
        touches.append(touch)
    return touches

def build_multi_touch_infos_for_screen(monitor_index: int, data: dict):
    points = data.get("points") or []
    touches = []
    for idx, point in enumerate(points[:MAX_TOUCH_POINTERS]):
        action = _point_action(point)
        sx, sy = get_screen_point(monitor_index, point.get("x", 0.0), point.get("y", 0.0))
        pointer_id = int(point.get("id", idx))
        primary = bool(point.get("primary", idx == 0))
        _, _, _, _, _, _, touch = build_touch_info_at_point_with_id(action, sx, sy, pointer_id, primary)
        touches.append(touch)
    return touches

def handle_multi_touch_input(hwnd: int, data: dict):
    points = data.get("points") or []
    if any(_point_action(point) == "down" for point in points):
        focus_window(hwnd, aggressive=True)
    touches = build_multi_touch_infos_for_window(hwnd, data)
    if hasattr(user32, "CreateSyntheticPointerDevice") and hasattr(user32, "InjectSyntheticPointerInput"):
        inject_synthetic_touches(touches)
    else:
        inject_legacy_touches(touches)

def handle_touch_screen_input(monitor_index: int, data: dict):
    action, sx, sy, tx, ty, flags, touch = build_screen_touch_info(monitor_index, data)
    if hasattr(user32, "CreateSyntheticPointerDevice") and hasattr(user32, "InjectSyntheticPointerInput"):
        inject_synthetic_touch(action, sx, sy, tx, ty, flags, touch)
    else:
        inject_legacy_touch(action, sx, sy, flags, touch)

def handle_multi_touch_screen_input(monitor_index: int, data: dict):
    touches = build_multi_touch_infos_for_screen(monitor_index, data)
    if hasattr(user32, "CreateSyntheticPointerDevice") and hasattr(user32, "InjectSyntheticPointerInput"):
        inject_synthetic_touches(touches)
    else:
        inject_legacy_touches(touches)

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

@app.get("/h264/status")
async def h264_status_endpoint(refresh: bool = False):
    return get_h264_status(force_probe=refresh)

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

@app.get("/start-menu/apps")
async def start_menu_apps_endpoint(request: Request):
    if request.headers.get("password") != PASSWORD:
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    return get_start_menu_apps()

@app.get("/screens")
async def screens_endpoint():
    return get_screens_list()

@app.get("/windows")
async def windows_endpoint(request: Request):
    if request.headers.get("password") != PASSWORD:
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    return get_windows_list_minimal()

def _authorized_request(request: Request):
    return request.headers.get("password") == PASSWORD

def get_extended_display_status_payload():
    screens = get_screens_list()
    virtual_screens = [screen for screen in screens if bool(screen.get("is_virtual"))]
    virtual_devices = [device for device in get_display_devices() if is_virtual_display_device(device)]
    pnp_device = get_virtual_pnp_device()
    admin = is_process_admin()
    pnp_error = PNP_DISPLAY_ENUM_ERROR
    pnp_access_denied = "denied" in pnp_error.lower() or "拒绝访问" in pnp_error
    bound_device = str(EXTENDED_DISPLAY_BINDING.get("device", "") or "")
    bound_screen = next((screen for screen in virtual_screens if str(screen.get("device", "")).lower() == bound_device.lower()), None)
    if not bound_screen and EXTENDED_DISPLAY_BINDING.get("monitor_index"):
        bound_screen = next((screen for screen in virtual_screens if int(screen.get("monitor_index", 0)) == int(EXTENDED_DISPLAY_BINDING["monitor_index"])), None)
    current_mode = get_current_display_mode(bound_screen["device"]) if bound_screen else None
    supported_modes = get_supported_display_modes(bound_screen["device"], limit=32) if bound_screen else []
    available = bool(virtual_screens)
    driver_enabled = pnp_device_enabled(pnp_device) or available
    driver_status = str(pnp_device.get("status", "")) if pnp_device else ""
    if not driver_enabled and not available and pnp_device:
        driver_status = "Disabled"
    if available:
        message = "Virtual display ready"
    elif pnp_access_denied:
        message = "Run the PC backend as administrator to query or control the virtual display driver"
    elif pnp_device and not driver_enabled:
        message = "Virtual display driver is disabled"
    elif virtual_devices or pnp_device:
        message = "Virtual display driver found, but no active virtual monitor is attached"
    else:
        message = "No compatible virtual display driver was found"
    return {
        "available": available,
        "message": message,
        "driver_control_available": bool(pnp_device) or bool(virtual_devices) or pnp_access_denied,
        "driver_enabled": bool(driver_enabled),
        "requires_admin": (bool(pnp_device) and not admin) or pnp_access_denied,
        "is_admin": admin,
        "driver_instance_id": str(pnp_device.get("instance_id", "")) if pnp_device else "",
        "driver_status": driver_status,
        "driver_name": str(pnp_device.get("friendly_name", "")) if pnp_device else "",
        "driver_error": pnp_error,
        "bound_client_id": EXTENDED_DISPLAY_BINDING.get("client_id", "") if bound_screen else "",
        "monitor_index": int(bound_screen.get("monitor_index", 0)) if bound_screen else 0,
        "screen": bound_screen,
        "current_mode": current_mode,
        "supported_modes": supported_modes,
        "virtual_devices": virtual_devices,
        "pnp_device": pnp_device,
    }

@app.get("/extended_display/status")
async def extended_display_status(request: Request):
    if not _authorized_request(request):
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    return await asyncio.to_thread(get_extended_display_status_payload)

@app.post("/extended_display/connect")
async def extended_display_connect(request: Request):
    if not _authorized_request(request):
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    try:
        data = await request.json()
    except Exception:
        data = {}
    client_id = str(data.get("client_id", "")).strip()
    if not client_id:
        raise HTTPException(status_code=400, detail="client_id is required")

    screens = get_screens_list()
    virtual_screens = [screen for screen in screens if bool(screen.get("is_virtual"))]
    if not virtual_screens:
        virtual_devices = [device for device in get_display_devices() if is_virtual_display_device(device)]
        return JSONResponse(
            status_code=404,
            content={
                "status": "missing",
                "message": "No active compatible virtual display was found" if not virtual_devices else "Virtual display driver found, but no active virtual monitor is attached",
                "virtual_devices": virtual_devices,
            },
        )

    bound_client = str(EXTENDED_DISPLAY_BINDING.get("client_id", "") or "")
    bound_device = str(EXTENDED_DISPLAY_BINDING.get("device", "") or "")
    if bound_client and bound_client != client_id:
        bound_screen = next((screen for screen in virtual_screens if str(screen.get("device", "")).lower() == bound_device.lower()), None)
        if bound_screen:
            return JSONResponse(
                status_code=409,
                content={
                    "status": "busy",
                    "message": "Extended display is already bound to another client",
                    "monitor_index": int(bound_screen.get("monitor_index", 0)),
                    "screen": bound_screen,
                },
            )

    selected = next((screen for screen in virtual_screens if str(screen.get("device", "")).lower() == bound_device.lower()), None)
    if not selected:
        selected = virtual_screens[0]

    screens = get_screens_list()
    selected = next((screen for screen in screens if str(screen.get("device", "")).lower() == str(selected["device"]).lower()), selected)
    EXTENDED_DISPLAY_BINDING["client_id"] = client_id
    EXTENDED_DISPLAY_BINDING["device"] = str(selected.get("device", ""))
    EXTENDED_DISPLAY_BINDING["monitor_index"] = int(selected.get("monitor_index", 0))
    selected["extended_owner"] = client_id
    return {
        "status": "connected",
        "message": "Virtual display bound",
        "monitor_index": int(selected.get("monitor_index", 0)),
        "screen": selected,
        "current_mode": get_current_display_mode(selected["device"]),
    }

@app.post("/extended_display/disconnect")
async def extended_display_disconnect(request: Request):
    if not _authorized_request(request):
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    try:
        data = await request.json()
    except Exception:
        data = {}
    client_id = str(data.get("client_id", "")).strip()
    if not client_id or EXTENDED_DISPLAY_BINDING.get("client_id") == client_id:
        EXTENDED_DISPLAY_BINDING["client_id"] = ""
        EXTENDED_DISPLAY_BINDING["device"] = ""
        EXTENDED_DISPLAY_BINDING["monitor_index"] = 0
    return {"status": "disconnected", "message": "binding cleared; virtual display was left enabled"}

@app.post("/extended_display/driver_state")
async def extended_display_driver_state(request: Request):
    if not _authorized_request(request):
        return JSONResponse(status_code=401, content={"message": "Invalid password"})
    try:
        data = await request.json()
    except Exception:
        data = {}
    return await asyncio.to_thread(set_extended_display_driver_state_payload, data)

def set_extended_display_driver_state_payload(data: dict):
    enabled = bool(data.get("enabled", False))
    client_id = str(data.get("client_id", "")).strip()
    pnp_device = get_virtual_pnp_device()
    pnp_error = PNP_DISPLAY_ENUM_ERROR
    pnp_access_denied = "denied" in pnp_error.lower() or "拒绝访问" in pnp_error
    if pnp_access_denied and not is_process_admin():
        return JSONResponse(
            status_code=403,
            content={
                "status": "requires_admin",
                "message": "Run the PC backend as administrator to enable or disable the virtual display driver",
                "driver_error": pnp_error,
            },
        )
    if not pnp_device:
        return JSONResponse(
            status_code=404,
            content={"status": "missing_driver", "message": "No compatible virtual display driver was found"},
        )
    if not is_process_admin():
        return JSONResponse(
            status_code=403,
            content={
                "status": "requires_admin",
                "message": "Run the PC backend as administrator to enable or disable the virtual display driver",
                "driver_name": pnp_device.get("friendly_name", ""),
                "driver_status": pnp_device.get("status", ""),
            },
        )

    if pnp_device_enabled(pnp_device) == enabled:
        status = get_extended_display_status_payload()
        status["status"] = "enabled" if enabled else "disabled"
        status["message"] = "Virtual display driver already enabled" if enabled else "Virtual display driver already disabled"
        return status

    try:
        updated_device = set_pnp_device_enabled(str(pnp_device.get("instance_id", "")), enabled)
    except Exception as e:
        print(f"[extended] set driver enabled={enabled} command failed: {type(e).__name__}: {e}", flush=True)
        deadline = time.perf_counter() + 3.0
        status = get_extended_display_status_payload()
        while time.perf_counter() < deadline:
            driver_matches = bool(status.get("driver_enabled")) == enabled
            screen_matches = bool(status.get("available")) == enabled
            if driver_matches or screen_matches:
                status["status"] = "enabled" if enabled else "disabled"
                status["message"] = "Virtual display driver enabled" if enabled else "Virtual display driver disabled"
                status["driver_warning"] = f"{type(e).__name__}: {e}"
                return status
            time.sleep(0.2)
            status = get_extended_display_status_payload()
        return JSONResponse(
            status_code=500,
            content={"status": "failed", "message": f"{type(e).__name__}: {e}"},
        )

    if not enabled:
        bound_client = str(EXTENDED_DISPLAY_BINDING.get("client_id", "") or "")
        if not client_id or bound_client == client_id:
            EXTENDED_DISPLAY_BINDING["client_id"] = ""
            EXTENDED_DISPLAY_BINDING["device"] = ""
            EXTENDED_DISPLAY_BINDING["monitor_index"] = 0

    deadline = time.perf_counter() + 2.0
    status = get_extended_display_status_payload()
    while time.perf_counter() < deadline:
        if bool(status.get("driver_enabled")) == enabled:
            if not enabled or status.get("available"):
                break
        time.sleep(0.2)
        status = get_extended_display_status_payload()
    status["status"] = "enabled" if enabled else "disabled"
    status["message"] = "Virtual display driver enabled" if enabled else "Virtual display driver disabled"
    status["pnp_device"] = updated_device or status.get("pnp_device")
    return status

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
                elif event_type == "touch_multi":
                    handle_multi_touch_input(hwnd, data)
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
                elif event_type == "launch_start_menu_app":
                    target = data.get("target", "")
                    print(f"[input] launch_start_menu_app target={str(target)[:120]!r}", flush=True)
                    ok, message = launch_start_menu_app(target)
                    if not ok:
                        await websocket.send_text(json.dumps({"type": "error", "message": message}))
                elif event_type == "run_command":
                    command = data.get("command", "")
                    print(f"[input] run_command chars={len(str(command))}", flush=True)
                    ok, message = run_custom_command(command)
                    if not ok:
                        await websocket.send_text(json.dumps({"type": "error", "message": message}))
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
        try:
            screen = get_screen_rect(monitor_index)
        except Exception as e:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
            return
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
                elif event_type == "touch_multi":
                    handle_multi_touch_screen_input(monitor_index, data)
                elif event_type == "text":
                    send_unicode_text(data.get("text", ""))
                elif event_type == "key":
                    send_key_name(data.get("key", ""))
                elif event_type == "shortcut":
                    send_shortcut(data.get("keys", []))
                elif event_type == "launch_start_menu_app":
                    ok, message = launch_start_menu_app(data.get("target", ""))
                    if not ok:
                        await websocket.send_text(json.dumps({"type": "error", "message": message}))
                elif event_type == "run_command":
                    ok, message = run_custom_command(data.get("command", ""))
                    if not ok:
                        await websocket.send_text(json.dumps({"type": "error", "message": message}))
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
            elif msg == "grid_preview:1":
                grid_preview_clients.add(websocket)
            elif msg == "grid_preview:0":
                grid_preview_clients.discard(websocket)
            elif msg == "ping": await websocket.send_text("pong")
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[ws] failed: {type(e).__name__}: {e}", flush=True)
    finally:
        connected_clients.discard(websocket)
        grid_preview_clients.discard(websocket)


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

async def stream_h264_capture(websocket: WebSocket, capture, label: str, stream_max_dim: float, stream_fps: float, stream_quality: int):
    capture_control = None
    encoder_process = None
    reader_task = None
    loop = asyncio.get_running_loop()
    frame_event = asyncio.Event()
    wgc_buffer = collections.deque(maxlen=1)
    last_accepted_frame = 0.0
    captured_frames = 0
    dropped_by_fps = 0
    dropped_busy = 0
    output_chunks = 0
    output_bytes = 0
    total_prep_ms = 0.0
    max_prep_ms = 0.0
    total_write_ms = 0.0
    max_write_ms = 0.0
    slow_writes = 0

    @capture.event
    def on_frame_arrived(frame: Frame, _):
        nonlocal last_accepted_frame, captured_frames, dropped_by_fps, dropped_busy
        try:
            now = time.perf_counter()
            if now - last_accepted_frame < (1.0 / max(stream_fps, 1.0)):
                dropped_by_fps += 1
                return
            if wgc_buffer:
                dropped_busy += 1
                return
            last_accepted_frame = now
            captured_frames += 1
            wgc_buffer.append(frame.frame_buffer)
            loop.call_soon_threadsafe(frame_event.set)
        except Exception as e:
            print(f"[h264] frame callback failed {label}: {type(e).__name__}: {e}", flush=True)

    @capture.event
    def on_closed(*_):
        try:
            loop.call_soon_threadsafe(frame_event.set)
        except Exception:
            pass

    async def send_encoder_output(proc):
        nonlocal output_chunks, output_bytes
        fd = proc.stdout.fileno()
        while not shutdown_event.is_set():
            chunk = await asyncio.to_thread(os.read, fd, 16384)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)

    try:
        if not get_ffmpeg_exe():
            await websocket.send_text(json.dumps({"type": "error", "message": "ffmpeg.exe was not found in PATH"}))
            return
        if not get_h264_hardware_encoder():
            await websocket.send_text(json.dumps({"type": "error", "message": get_h264_probe_error()}))
            return

        capture_control = capture.start_free_threaded()
        while not wgc_buffer and not shutdown_event.is_set():
            await asyncio.wait_for(frame_event.wait(), timeout=3.0)
            frame_event.clear()
            if capture_control and capture_control.is_finished():
                break
        if not wgc_buffer:
            await websocket.send_text(json.dumps({"type": "error", "message": "No WGC frame received"}))
            return

        first_frame = resize_for_stream(wgc_buffer.pop(), stream_max_dim)
        height, width = first_frame.shape[:2]
        encoder_process, encoder_name = start_h264_encoder(width, height, stream_fps, stream_quality)

        async def write_frame(img_array):
            nonlocal total_prep_ms, max_prep_ms, total_write_ms, max_write_ms, slow_writes
            if encoder_process.poll() is not None:
                stderr = read_ffmpeg_stderr(encoder_process)
                raise RuntimeError(f"ffmpeg encoder exited before frame write: {stderr}".strip())
            prep_start = time.perf_counter()
            raw = prepare_h264_input_frame(img_array, stream_max_dim, width, height)
            prep_ms = (time.perf_counter() - prep_start) * 1000.0
            total_prep_ms += prep_ms
            max_prep_ms = max(max_prep_ms, prep_ms)
            write_start = time.perf_counter()
            try:
                await asyncio.to_thread(encoder_process.stdin.write, raw)
            except BrokenPipeError:
                stderr = read_ffmpeg_stderr(encoder_process)
                raise RuntimeError(f"ffmpeg encoder pipe closed: {stderr}".strip())
            elapsed_ms = (time.perf_counter() - write_start) * 1000.0
            total_write_ms += elapsed_ms
            max_write_ms = max(max_write_ms, elapsed_ms)
            if elapsed_ms > 24.0:
                slow_writes += 1
            if encoder_process.poll() is not None:
                stderr = read_ffmpeg_stderr(encoder_process)
                raise RuntimeError(f"ffmpeg encoder exited after frame write: {stderr}".strip())

        await write_frame(first_frame)
        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": encoder_name,
            "width": width,
            "height": height,
            "fps": int(stream_fps),
        }))
        reader_task = asyncio.create_task(send_encoder_output(encoder_process))

        sent_frames = 1
        last_log_at = time.perf_counter()
        last_log_frames = sent_frames
        last_log_bytes = output_bytes
        while not shutdown_event.is_set():
            if reader_task and reader_task.done():
                stderr = read_ffmpeg_stderr(encoder_process)
                if encoder_process and encoder_process.poll() is not None:
                    raise RuntimeError(f"ffmpeg encoder output ended: {stderr}".strip())
                break
            try:
                await asyncio.wait_for(frame_event.wait(), timeout=0.5)
            except asyncio.TimeoutError:
                continue
            frame_event.clear()
            if capture_control and capture_control.is_finished() and not wgc_buffer:
                break
            if wgc_buffer:
                await write_frame(wgc_buffer.pop())
                sent_frames += 1
                now = time.perf_counter()
                if sent_frames == 1 or now - last_log_at >= 3.0:
                    interval = max(0.001, now - last_log_at)
                    fps_out = (sent_frames - last_log_frames) / interval
                    mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                    avg_prep = total_prep_ms / max(sent_frames, 1)
                    avg_write = total_write_ms / max(sent_frames, 1)
                    last_log_at = now
                    last_log_frames = sent_frames
                    last_log_bytes = output_bytes
                    print(
                        f"[h264] stats {label} fed={sent_frames} out_fps={fps_out:.1f} "
                        f"net={mbps:.1f}Mbps chunks={output_chunks} captured={captured_frames} "
                        f"drop_fps={dropped_by_fps} drop_busy={dropped_busy} "
                        f"prep_avg={avg_prep:.1f}ms prep_max={max_prep_ms:.1f}ms "
                        f"write_avg={avg_write:.1f}ms write_max={max_write_ms:.1f}ms slow_writes={slow_writes}",
                        flush=True
                    )
    except Exception as e:
        print(f"[h264] stream failed {label}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if reader_task:
            reader_task.cancel()
        if encoder_process:
            try:
                if encoder_process.stdin:
                    encoder_process.stdin.close()
            except Exception:
                pass
            try:
                encoder_process.terminate()
            except Exception:
                pass
        if capture_control:
            try:
                capture_control.stop()
            except Exception:
                pass

async def stream_h264_native_screen(websocket: WebSocket, monitor_index: int, stream_max_dim: float, stream_fps: float, stream_quality: int):
    native_process = None
    stderr_thread = None
    try:
        exe = get_native_streamer_exe()
        if not exe:
            await websocket.send_text(json.dumps({"type": "error", "message": "native streamer exe was not found"}))
            return

        screen = get_screen_rect(monitor_index)
        src_w = int(screen["width"])
        src_h = int(screen["height"])
        out_w, out_h = scaled_even_size(src_w, src_h, stream_max_dim)
        fps_int = max(5, min(int(round(stream_fps)), 160))
        quality_int = max(35, min(int(round(stream_quality)), 100))
        cmd = [
            exe,
            "--left", str(int(screen["left"])),
            "--top", str(int(screen["top"])),
            "--width", str(src_w),
            "--height", str(src_h),
            "--out-width", str(out_w),
            "--out-height", str(out_h),
            "--fps", str(fps_int),
            "--quality", str(quality_int),
        ]
        print(
            f"[h264-native] start screen={monitor_index} src={src_w}x{src_h} out={out_w}x{out_h} "
            f"fps={fps_int} quality={quality_int}",
            flush=True,
        )
        native_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )

        def drain_stderr():
            try:
                for raw in iter(native_process.stderr.readline, b""):
                    line = raw.decode("utf-8", errors="replace").strip()
                    if line:
                        print(line, flush=True)
            except Exception:
                pass

        stderr_thread = threading.Thread(target=drain_stderr, daemon=True)
        stderr_thread.start()

        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": "native-mf",
            "width": out_w,
            "height": out_h,
            "fps": fps_int,
            "source": "native-wgc-mf",
        }))

        fd = native_process.stdout.fileno()
        output_chunks = 0
        output_bytes = 0
        last_log_at = time.perf_counter()
        last_log_bytes = 0
        while not shutdown_event.is_set():
            if native_process.poll() is not None:
                raise RuntimeError(f"native streamer exited with code {native_process.returncode}")
            chunk = await asyncio.to_thread(os.read, fd, 16384)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)
            now = time.perf_counter()
            if now - last_log_at >= 3.0:
                interval = max(0.001, now - last_log_at)
                mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                last_log_at = now
                last_log_bytes = output_bytes
                print(f"[h264-native] stats screen={monitor_index} net={mbps:.1f}Mbps chunks={output_chunks} bytes={output_bytes}", flush=True)
    except Exception as e:
        print(f"[h264-native] stream failed screen={monitor_index}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if native_process:
            try:
                native_process.terminate()
                try:
                    await asyncio.to_thread(native_process.wait, 1.5)
                except Exception:
                    native_process.kill()
            except Exception:
                pass

async def stream_h264_native_window(websocket: WebSocket, hwnd: int, stream_max_dim: float, stream_fps: float, stream_quality: int):
    native_process = None
    try:
        exe = get_native_streamer_exe()
        if not exe:
            await websocket.send_text(json.dumps({"type": "error", "message": "native streamer exe was not found"}))
            return
        if not win32gui.IsWindow(hwnd):
            await websocket.send_text(json.dumps({"type": "error", "message": f"window not found: {hwnd}"}))
            return

        left, top, right, bottom = get_window_bounds(hwnd)
        src_w = max(2, int(right - left))
        src_h = max(2, int(bottom - top))
        out_w, out_h = scaled_even_size(src_w, src_h, stream_max_dim)
        fps_int = max(5, min(int(round(stream_fps)), 160))
        quality_int = max(35, min(int(round(stream_quality)), 100))
        cmd = [
            exe,
            "--hwnd", str(int(hwnd)),
            "--width", str(src_w),
            "--height", str(src_h),
            "--out-width", str(out_w),
            "--out-height", str(out_h),
            "--fps", str(fps_int),
            "--quality", str(quality_int),
        ]
        title = win32gui.GetWindowText(hwnd)
        print(
            f"[h264-native] start hwnd={hwnd} title={title!r} src={src_w}x{src_h} out={out_w}x{out_h} "
            f"fps={fps_int} quality={quality_int}",
            flush=True,
        )
        native_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )

        def drain_stderr():
            try:
                for raw in iter(native_process.stderr.readline, b""):
                    line = raw.decode("utf-8", errors="replace").strip()
                    if line:
                        print(line, flush=True)
            except Exception:
                pass

        threading.Thread(target=drain_stderr, daemon=True).start()

        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": "native-mf",
            "width": out_w,
            "height": out_h,
            "fps": fps_int,
            "source": "native-wgc-window-mf",
        }))

        fd = native_process.stdout.fileno()
        output_chunks = 0
        output_bytes = 0
        last_log_at = time.perf_counter()
        last_log_bytes = 0
        while not shutdown_event.is_set():
            if native_process.poll() is not None:
                raise RuntimeError(f"native streamer exited with code {native_process.returncode}")
            chunk = await asyncio.to_thread(os.read, fd, 16384)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)
            now = time.perf_counter()
            if now - last_log_at >= 3.0:
                interval = max(0.001, now - last_log_at)
                mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                last_log_at = now
                last_log_bytes = output_bytes
                print(f"[h264-native] stats hwnd={hwnd} net={mbps:.1f}Mbps chunks={output_chunks} bytes={output_bytes}", flush=True)
    except Exception as e:
        print(f"[h264-native] stream failed hwnd={hwnd}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if native_process:
            try:
                native_process.terminate()
                try:
                    await asyncio.to_thread(native_process.wait, 1.5)
                except Exception:
                    native_process.kill()
            except Exception:
                pass

async def stream_h264_direct_screen(websocket: WebSocket, monitor_index: int, stream_max_dim: float, stream_fps: float, stream_quality: int):
    ffmpeg_process = None
    try:
        ffmpeg = get_ffmpeg_exe()
        if not ffmpeg:
            await websocket.send_text(json.dumps({"type": "error", "message": "ffmpeg.exe was not found"}))
            return
        encoder = get_h264_hardware_encoder()
        if not encoder:
            await websocket.send_text(json.dumps({"type": "error", "message": get_h264_probe_error()}))
            return

        screen = get_screen_rect(monitor_index)
        src_w = int(screen["width"])
        src_h = int(screen["height"])
        out_w, out_h = scaled_even_size(src_w, src_h, stream_max_dim)
        fps_int = max(5, min(int(round(stream_fps)), 160))
        encoder_options, bitrate = h264_runtime_encoder_options(encoder, fps_int, out_w, out_h, stream_quality)
        cmd = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-fflags",
            "nobuffer",
            "-f",
            "gdigrab",
            "-draw_mouse",
            "1",
            "-framerate",
            str(fps_int),
            "-offset_x",
            str(int(screen["left"])),
            "-offset_y",
            str(int(screen["top"])),
            "-video_size",
            f"{src_w}x{src_h}",
            "-i",
            "desktop",
            "-an",
            "-vf",
            f"scale={out_w}:{out_h}:flags=fast_bilinear,format=yuv420p",
            "-flags",
            "low_delay",
            *encoder_options,
            "-flush_packets",
            "1",
            "-f",
            "h264",
            "pipe:1",
        ]
        profile = _h264_encoder_probe_cache.get("encoder_profile") or "default"
        print(
            f"[h264-direct] start screen={monitor_index} src={src_w}x{src_h} out={out_w}x{out_h} "
            f"fps={fps_int} quality={stream_quality} encoder={encoder} profile={profile} target={bitrate}kbps",
            flush=True,
        )
        ffmpeg_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": encoder,
            "width": out_w,
            "height": out_h,
            "fps": fps_int,
            "source": "ffmpeg-gdigrab",
        }))

        fd = ffmpeg_process.stdout.fileno()
        output_chunks = 0
        output_bytes = 0
        last_log_at = time.perf_counter()
        last_log_bytes = 0
        while not shutdown_event.is_set():
            if ffmpeg_process.poll() is not None:
                stderr = read_ffmpeg_stderr(ffmpeg_process)
                raise RuntimeError(f"ffmpeg direct capture exited: {stderr}".strip())
            chunk = await asyncio.to_thread(os.read, fd, 16384)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)
            now = time.perf_counter()
            if now - last_log_at >= 3.0:
                interval = max(0.001, now - last_log_at)
                mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                last_log_at = now
                last_log_bytes = output_bytes
                print(f"[h264-direct] stats screen={monitor_index} net={mbps:.1f}Mbps chunks={output_chunks} bytes={output_bytes}", flush=True)
    except Exception as e:
        print(f"[h264-direct] stream failed screen={monitor_index}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if ffmpeg_process:
            try:
                ffmpeg_process.terminate()
                try:
                    await asyncio.to_thread(ffmpeg_process.wait, 1.5)
                except Exception:
                    ffmpeg_process.kill()
            except Exception:
                pass

async def stream_h264_gdigrab_region(
    websocket: WebSocket,
    label: str,
    left: int,
    top: int,
    width: int,
    height: int,
    stream_max_dim: float,
    stream_fps: float,
    stream_quality: int,
):
    ffmpeg_process = None
    try:
        ffmpeg = get_ffmpeg_exe()
        if not ffmpeg:
            await websocket.send_text(json.dumps({"type": "error", "message": "ffmpeg.exe was not found"}))
            return
        encoder = get_h264_hardware_encoder()
        if not encoder:
            await websocket.send_text(json.dumps({"type": "error", "message": get_h264_probe_error()}))
            return

        width = max(2, int(width) - int(width) % 2)
        height = max(2, int(height) - int(height) % 2)
        out_w, out_h = scaled_even_size(width, height, stream_max_dim)
        fps_int = max(5, min(int(round(stream_fps)), 160))
        encoder_options, bitrate = h264_runtime_encoder_options(encoder, fps_int, out_w, out_h, stream_quality)
        cmd = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-fflags",
            "nobuffer",
            "-f",
            "gdigrab",
            "-draw_mouse",
            "1",
            "-framerate",
            str(fps_int),
            "-offset_x",
            str(int(left)),
            "-offset_y",
            str(int(top)),
            "-video_size",
            f"{width}x{height}",
            "-i",
            "desktop",
            "-an",
            "-vf",
            f"scale={out_w}:{out_h}:flags=fast_bilinear,format=yuv420p",
            "-flags",
            "low_delay",
            *encoder_options,
            "-flush_packets",
            "1",
            "-f",
            "h264",
            "pipe:1",
        ]
        profile = _h264_encoder_probe_cache.get("encoder_profile") or "default"
        print(
            f"[h264-gdigrab] start {label} offset={left},{top} src={width}x{height} "
            f"out={out_w}x{out_h} fps={fps_int} quality={stream_quality} encoder={encoder} profile={profile} target={bitrate}kbps",
            flush=True,
        )
        ffmpeg_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        await asyncio.sleep(0.05)
        if ffmpeg_process.poll() is not None:
            stderr = read_ffmpeg_stderr(ffmpeg_process)
            raise RuntimeError(f"ffmpeg gdigrab exited before config: {stderr}".strip())
        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": encoder,
            "width": out_w,
            "height": out_h,
            "fps": fps_int,
            "source": "ffmpeg-gdigrab-region",
        }))

        fd = ffmpeg_process.stdout.fileno()
        output_chunks = 0
        output_bytes = 0
        last_log_at = time.perf_counter()
        last_log_bytes = 0
        while not shutdown_event.is_set():
            if ffmpeg_process.poll() is not None:
                stderr = read_ffmpeg_stderr(ffmpeg_process)
                raise RuntimeError(f"ffmpeg gdigrab exited: {stderr}".strip())
            chunk = await asyncio.to_thread(os.read, fd, 32768)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)
            now = time.perf_counter()
            if now - last_log_at >= 3.0:
                interval = max(0.001, now - last_log_at)
                mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                last_log_at = now
                last_log_bytes = output_bytes
                print(f"[h264-gdigrab] stats {label} net={mbps:.1f}Mbps chunks={output_chunks} bytes={output_bytes}", flush=True)
    except Exception as e:
        print(f"[h264-gdigrab] stream failed {label}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if ffmpeg_process:
            try:
                ffmpeg_process.terminate()
                try:
                    await asyncio.to_thread(ffmpeg_process.wait, 1.5)
                except Exception:
                    ffmpeg_process.kill()
            except Exception:
                pass

async def stream_h264_ddagrab(
    websocket: WebSocket,
    label: str,
    adapter_idx: int,
    output_idx: int,
    offset_x: int,
    offset_y: int,
    width: int,
    height: int,
    out_width: int,
    out_height: int,
    stream_fps: float,
    stream_quality: int,
):
    ffmpeg_process = None
    try:
        ffmpeg = get_ffmpeg_exe()
        if not ffmpeg:
            await websocket.send_text(json.dumps({"type": "error", "message": "ffmpeg.exe was not found"}))
            return
        selected_encoder = get_h264_hardware_encoder()
        if selected_encoder != "h264_nvenc":
            await websocket.send_text(json.dumps({"type": "error", "message": "ddagrab path currently requires h264_nvenc"}))
            return

        width = max(2, int(width) - int(width) % 2)
        height = max(2, int(height) - int(height) % 2)
        out_width = max(2, int(out_width) - int(out_width) % 2)
        out_height = max(2, int(out_height) - int(out_height) % 2)
        fps_int = max(5, min(int(round(stream_fps)), 160))
        encoder_options, bitrate = h264_ddagrab_nvenc_options(fps_int, out_width, out_height, stream_quality)
        ddagrab = (
            f"ddagrab=output_idx={max(0, int(output_idx))}:"
            f"framerate={fps_int}:"
            f"offset_x={int(offset_x)}:"
            f"offset_y={int(offset_y)}:"
            f"video_size={width}x{height}:"
            "draw_mouse=1"
        )
        cmd = [
            ffmpeg,
            "-hide_banner",
            "-loglevel",
            "error",
            "-f",
            "lavfi",
            "-i",
            ddagrab,
            "-an",
            "-vf",
            f"hwdownload,format=bgra,scale={out_width}:{out_height}:flags=fast_bilinear,format=yuv420p",
            "-flags",
            "low_delay",
            *encoder_options,
            "-flush_packets",
            "1",
            "-f",
            "h264",
            "pipe:1",
        ]
        print(
            f"[h264-dda] start {label} adapter_idx={adapter_idx} output_idx={output_idx} offset={offset_x},{offset_y} "
            f"src={width}x{height} out={out_width}x{out_height} fps={fps_int} quality={stream_quality} target={bitrate}kbps",
            flush=True,
        )
        ffmpeg_process = subprocess.Popen(
            cmd,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            stdin=subprocess.DEVNULL,
            bufsize=0,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )
        await asyncio.sleep(0.05)
        if ffmpeg_process.poll() is not None:
            stderr = read_ffmpeg_stderr(ffmpeg_process)
            raise RuntimeError(f"ffmpeg ddagrab exited before config: {stderr}".strip())
        await websocket.send_text(json.dumps({
            "type": "video_config",
            "codec": "h264",
            "encoder": "ffmpeg-ddagrab-nvenc",
            "width": out_width,
            "height": out_height,
            "fps": fps_int,
            "source": "ffmpeg-ddagrab",
        }))

        fd = ffmpeg_process.stdout.fileno()
        output_chunks = 0
        output_bytes = 0
        last_log_at = time.perf_counter()
        last_log_bytes = 0
        while not shutdown_event.is_set():
            if ffmpeg_process.poll() is not None:
                stderr = read_ffmpeg_stderr(ffmpeg_process)
                raise RuntimeError(f"ffmpeg ddagrab exited: {stderr}".strip())
            chunk = await asyncio.to_thread(os.read, fd, 32768)
            if not chunk:
                break
            await websocket.send_bytes(chunk)
            output_chunks += 1
            output_bytes += len(chunk)
            now = time.perf_counter()
            if now - last_log_at >= 3.0:
                interval = max(0.001, now - last_log_at)
                mbps = ((output_bytes - last_log_bytes) * 8.0 / 1_000_000.0) / interval
                last_log_at = now
                last_log_bytes = output_bytes
                print(f"[h264-dda] stats {label} net={mbps:.1f}Mbps chunks={output_chunks} bytes={output_bytes}", flush=True)
    except Exception as e:
        print(f"[h264-dda] stream failed {label}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": f"{type(e).__name__}: {e}"}))
        except Exception:
            pass
    finally:
        if ffmpeg_process:
            try:
                ffmpeg_process.terminate()
                try:
                    await asyncio.to_thread(ffmpeg_process.wait, 1.5)
                except Exception:
                    ffmpeg_process.kill()
            except Exception:
                pass

@app.websocket("/live_h264/{hwnd}")
async def live_h264_stream_endpoint(
    websocket: WebSocket,
    hwnd: int,
    max_dim: int | None = None,
    quality: int | None = None,
    fps: int | None = None
):
    global active_live_streams
    await websocket.accept()
    active_live_streams += 1
    try:
        stream_max_dim = float(max(360, min(max_dim if max_dim is not None else int(LIVE_MAX_DIM), 3840)))
        stream_quality = max(35, min(quality if quality is not None else LIVE_JPEG_QUALITY, 100))
        stream_fps = float(max(5, min(fps if fps is not None else int(LIVE_TARGET_FPS), 160)))
        title = win32gui.GetWindowText(hwnd) if win32gui.IsWindow(hwnd) else ""
        screen, capture_left, capture_top, capture_w, capture_h = get_window_capture_region(hwnd)
        encoder = get_h264_hardware_encoder()
        if encoder == "h264_nvenc" and int(screen.get("ddagrab_adapter_idx", 0)) == 0:
            ddagrab_left = int(screen.get("ddagrab_left", screen["left"]))
            ddagrab_top = int(screen.get("ddagrab_top", screen["top"]))
            offset_x = max(0, int(capture_left) - ddagrab_left)
            offset_y = max(0, int(capture_top) - ddagrab_top)
            output_idx = int(screen.get("ddagrab_output_idx", int(screen["monitor_index"]) - 1))
            out_w, out_h = scaled_even_size(capture_w, capture_h, stream_max_dim)
            await stream_h264_ddagrab(
                websocket,
                f"hwnd={hwnd} title={title!r} screen={screen.get('monitor_index')}",
                int(screen.get("ddagrab_adapter_idx", 0)),
                output_idx,
                offset_x,
                offset_y,
                capture_w,
                capture_h,
                out_w,
                out_h,
                stream_fps,
                stream_quality,
            )
            return
        print(
            f"[h264] using gdigrab window region hwnd={hwnd} screen={screen.get('monitor_index')} "
            f"encoder={encoder or 'none'} adapter_idx={screen.get('ddagrab_adapter_idx', 0)}",
            flush=True,
        )
        await stream_h264_gdigrab_region(
            websocket,
            f"hwnd={hwnd} title={title!r} screen={screen.get('monitor_index')}",
            capture_left,
            capture_top,
            capture_w,
            capture_h,
            stream_max_dim,
            stream_fps,
            stream_quality,
        )
    finally:
        active_live_streams = max(0, active_live_streams - 1)
        await safe_close(websocket)

@app.websocket("/live_h264/screen/{monitor_index}")
async def live_h264_screen_stream_endpoint(
    websocket: WebSocket,
    monitor_index: int,
    max_dim: int | None = None,
    quality: int | None = None,
    fps: int | None = None
):
    global active_live_streams
    await websocket.accept()
    active_live_streams += 1
    try:
        try:
            _ = get_screen_rect(monitor_index)
        except Exception as e:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
            return
        stream_max_dim = float(max(360, min(max_dim if max_dim is not None else int(LIVE_MAX_DIM), 3840)))
        stream_quality = max(35, min(quality if quality is not None else LIVE_JPEG_QUALITY, 100))
        stream_fps = float(max(5, min(fps if fps is not None else int(LIVE_TARGET_FPS), 160)))
        encoder = get_h264_hardware_encoder()
        if encoder == "h264_nvenc":
            screen = get_screen_rect(monitor_index)
            capture_w = int(screen.get("ddagrab_width") or screen["width"])
            capture_h = int(screen.get("ddagrab_height") or screen["height"])
            out_w, out_h = scaled_even_size(capture_w, capture_h, stream_max_dim)
            adapter_idx = int(screen.get("ddagrab_adapter_idx", 0))
            output_idx = int(screen.get("ddagrab_output_idx", int(monitor_index) - 1))
            if adapter_idx != 0:
                print(
                    f"[h264] ddagrab skipped screen={monitor_index}: output is on adapter_idx={adapter_idx}; using gdigrab hardware path",
                    flush=True,
                )
                await stream_h264_direct_screen(websocket, monitor_index, stream_max_dim, stream_fps, stream_quality)
                return
            await stream_h264_ddagrab(
                websocket,
                f"screen={monitor_index} device={screen.get('device', '')}",
                adapter_idx,
                output_idx,
                0,
                0,
                capture_w,
                capture_h,
                out_w,
                out_h,
                stream_fps,
                stream_quality,
            )
            return
        if not WGC_AVAILABLE:
            message = f"ddagrab requires h264_nvenc; selected encoder={encoder or 'none'}; windows_capture is unavailable"
            print(f"[h264] hardware screen stream unavailable screen={monitor_index}: {message}", flush=True)
            await websocket.send_text(json.dumps({"type": "error", "message": message}))
            return
        print(f"[h264] ddagrab unavailable screen={monitor_index}: selected encoder={encoder or 'none'}; using WGC jpeg-compatible screen path", flush=True)
        await stream_h264_capture(websocket, WindowsCapture(monitor_index=monitor_index, draw_border=False), f"screen={monitor_index}", stream_max_dim, stream_fps, stream_quality)
    except WebSocketDisconnect:
        pass
    except Exception as e:
        print(f"[h264] screen stream failed monitor={monitor_index}: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
        except Exception:
            pass
    finally:
        active_live_streams = max(0, active_live_streams - 1)
        await safe_close(websocket)

async def drain_process_stderr(proc, label: str):
    try:
        while True:
            line = await asyncio.to_thread(proc.stderr.readline)
            if not line:
                break
            text = line.decode("utf-8", errors="replace").strip()
            if text:
                print(f"[{label}] {text}", flush=True)
    except Exception:
        pass

def build_wasapi_audio_cmd(ffmpeg_path: str, sample_rate: int, channels: int, frame_ms: int, use_loopback: bool):
    cmd = [
        ffmpeg_path,
        "-hide_banner",
        "-loglevel", "warning",
        "-fflags", "nobuffer",
        "-flags", "low_delay",
        "-probesize", "32",
        "-analyzeduration", "0",
        "-f", "wasapi",
    ]
    if use_loopback:
        cmd += ["-loopback", "1"]
    cmd += [
        "-i", "default",
        "-vn",
        "-ac", str(channels),
        "-ar", str(sample_rate),
        "-acodec", "pcm_s16le",
        "-f", "s16le",
        "-blocksize", str(max(256, int(sample_rate * channels * 2 * frame_ms / 1000))),
        "pipe:1",
    ]
    return cmd

async def start_wasapi_audio_process(sample_rate: int, channels: int, frame_ms: int):
    ffmpeg_path = get_ffmpeg_exe()
    if not ffmpeg_path:
        return None, "FFmpeg not found. Configure PATH, TSKBSYNC_FFMPEG, TSKBSYNC_FFMPEG_DIR, or pc_backend\\ffmpeg_path.txt."

    attempts = [
        ("wasapi-loopback", build_wasapi_audio_cmd(ffmpeg_path, sample_rate, channels, frame_ms, True)),
        ("wasapi-default", build_wasapi_audio_cmd(ffmpeg_path, sample_rate, channels, frame_ms, False)),
    ]
    last_error = ""
    for label, cmd in attempts:
        try:
            proc = subprocess.Popen(
                cmd,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                stdin=subprocess.DEVNULL,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0,
                bufsize=0,
            )
            await asyncio.sleep(0.25)
            if proc.poll() is None:
                print(f"[audio] started {label}: sample_rate={sample_rate} channels={channels} frame_ms={frame_ms}", flush=True)
                return proc, ""
            stderr = ""
            try:
                stderr = (proc.stderr.read() or b"").decode("utf-8", errors="replace")
            except Exception:
                pass
            last_error = stderr.strip() or f"{label} exited with code {proc.returncode}"
            print(f"[audio] {label} failed: {last_error}", flush=True)
        except Exception as e:
            last_error = f"{label}: {type(e).__name__}: {e}"
            print(f"[audio] {last_error}", flush=True)
    return None, f"WASAPI loopback unavailable: {last_error}"

class PyAudioLoopbackSource:
    def __init__(self, pa, stream, sample_rate: int, channels: int, frame_samples: int, device_name: str):
        self.pa = pa
        self.stream = stream
        self.sample_rate = sample_rate
        self.channels = channels
        self.frame_samples = frame_samples
        self.frame_bytes = frame_samples * channels * 2
        self.device_name = device_name

    def read(self):
        return self.stream.read(self.frame_samples, exception_on_overflow=False)

    def close(self):
        try:
            self.stream.stop_stream()
        except Exception:
            pass
        try:
            self.stream.close()
        except Exception:
            pass
        try:
            self.pa.terminate()
        except Exception:
            pass

def score_pyaudio_capture_device(device, host_api_name: str):
    name = str(device.get("name", "")).lower()
    if int(device.get("maxInputChannels", 0) or 0) <= 0:
        return -1
    bad_tokens = [
        "microphone", "mic", "麦克风", "input", "capture",
        "noise-cancelling input", "streaming microphone",
    ]
    good_tokens = [
        "loopback", "stereo mix", "what u hear", "speaker", "speakers",
        "扬声器", "电脑扬声器", "output", "line out", "system virtual line",
    ]
    score = 0
    if "wasapi" in host_api_name.lower():
        score += 20
    if "wdm-ks" in host_api_name.lower():
        score += 16
    if "directsound" in host_api_name.lower():
        score += 6
    for token in good_tokens:
        if token in name:
            score += 30
    for token in bad_tokens:
        if token in name:
            score -= 40
    return score

def start_pyaudio_loopback_source(sample_rate: int, channels: int, frame_ms: int):
    try:
        import pyaudio
    except Exception as e:
        return None, f"PyAudio is not available: {type(e).__name__}: {e}"

    pa = None
    try:
        pa = pyaudio.PyAudio()
        candidates = []
        for index in range(pa.get_device_count()):
            device = pa.get_device_info_by_index(index)
            host_api = pa.get_host_api_info_by_index(int(device.get("hostApi", 0)))
            score = score_pyaudio_capture_device(device, host_api.get("name", ""))
            if score >= 20:
                candidates.append((score, device, host_api))
        candidates.sort(key=lambda item: item[0], reverse=True)
        errors = []
        for score, device, host_api in candidates:
            device_index = int(device["index"])
            max_channels = int(device.get("maxInputChannels", 0) or 0)
            use_channels = max(1, min(channels, max_channels, 2))
            rate_candidates = []
            requested = int(sample_rate)
            default_rate = int(float(device.get("defaultSampleRate", requested) or requested))
            for rate in (requested, default_rate, 48000, 44100):
                if rate not in rate_candidates and 16000 <= rate <= 48000:
                    rate_candidates.append(rate)
            for rate in rate_candidates:
                try:
                    frame_samples = max(128, int(rate * frame_ms / 1000))
                    stream = pa.open(
                        format=pyaudio.paInt16,
                        channels=use_channels,
                        rate=rate,
                        input=True,
                        input_device_index=device_index,
                        frames_per_buffer=frame_samples,
                    )
                    print(
                        f"[audio] started pyaudio device={device_index} name={device.get('name', '')!r} "
                        f"host={host_api.get('name', '')!r} rate={rate} channels={use_channels}",
                        flush=True,
                    )
                    return PyAudioLoopbackSource(pa, stream, rate, use_channels, frame_samples, str(device.get("name", ""))), ""
                except Exception as e:
                    errors.append(f"{device_index}:{device.get('name', '')}@{rate}Hz {type(e).__name__}: {e}")
        pa.terminate()
        return None, "No usable PyAudio loopback-style input device found. Tried: " + "; ".join(errors[:6])
    except Exception as e:
        if pa:
            try:
                pa.terminate()
            except Exception:
                pass
        return None, f"PyAudio loopback failed: {type(e).__name__}: {e}"

class WasapiLoopbackSource:
    def __init__(self, audio_client, capture_client, sample_rate: int, in_channels: int, out_channels: int, bits_per_sample: int, block_align: int, is_float: bool, frame_samples: int):
        self.audio_client = audio_client
        self.capture_client = capture_client
        self.sample_rate = sample_rate
        self.in_channels = in_channels
        self.channels = out_channels
        self.bits_per_sample = bits_per_sample
        self.block_align = block_align
        self.is_float = is_float
        self.frame_samples = frame_samples
        self.frame_bytes = frame_samples * out_channels * 2

    def start(self):
        self.audio_client.Start()

    def read(self):
        import numpy as _np
        out = bytearray()
        deadline = time.perf_counter() + max(0.02, self.frame_samples / max(self.sample_rate, 1) * 2.0)
        while len(out) < self.frame_bytes:
            packet_frames = self.capture_client.GetNextPacketSize()
            if int(packet_frames) == 0:
                if time.perf_counter() >= deadline:
                    out.extend(b"\x00" * (self.frame_bytes - len(out)))
                    break
                time.sleep(0.001)
                continue
            data_ptr, frames, flags, _device_pos, _qpc_pos = self.capture_client.GetBuffer()
            frames = int(frames)
            flags = int(flags)
            try:
                raw_size = frames * int(self.block_align)
                if flags & 0x2:  # AUDCLNT_BUFFERFLAGS_SILENT
                    raw = b"\x00" * raw_size
                else:
                    raw = ctypes.string_at(data_ptr, raw_size)
                out.extend(self._convert_to_s16(raw, frames, _np))
            finally:
                self.capture_client.ReleaseBuffer(frames)
        return bytes(out[:self.frame_bytes])

    def _convert_to_s16(self, raw: bytes, frames: int, np_mod):
        if frames <= 0:
            return b""
        if self.bits_per_sample == 32 and self.is_float:
            arr = np_mod.frombuffer(raw, dtype="<f4").reshape(-1, self.in_channels)
            arr = np_mod.clip(arr[:, :self.channels], -1.0, 1.0)
            if self.channels == 2 and arr.shape[1] == 1:
                arr = np_mod.repeat(arr, 2, axis=1)
            return (arr * 32767.0).astype("<i2").tobytes()
        if self.bits_per_sample == 16:
            arr = np_mod.frombuffer(raw, dtype="<i2").reshape(-1, self.in_channels)
            arr = arr[:, :self.channels]
            if self.channels == 2 and arr.shape[1] == 1:
                arr = np_mod.repeat(arr, 2, axis=1)
            return arr.astype("<i2", copy=False).tobytes()
        if self.bits_per_sample == 24:
            data = np_mod.frombuffer(raw, dtype=np_mod.uint8).reshape(-1, self.block_align)
            samples = data[:, :self.in_channels * 3].reshape(-1, self.in_channels, 3)
            vals = (
                samples[:, :, 0].astype(np_mod.int32) |
                (samples[:, :, 1].astype(np_mod.int32) << 8) |
                (samples[:, :, 2].astype(np_mod.int32) << 16)
            )
            vals = np_mod.where(vals & 0x800000, vals | ~0xFFFFFF, vals)
            vals = (vals[:, :self.channels] >> 8).astype("<i2")
            if self.channels == 2 and vals.shape[1] == 1:
                vals = np_mod.repeat(vals, 2, axis=1)
            return vals.tobytes()
        return b"\x00" * (frames * self.channels * 2)

    def close(self):
        try:
            self.audio_client.Stop()
        except Exception:
            pass

def start_coreaudio_wasapi_loopback_source(sample_rate: int, channels: int, frame_ms: int):
    try:
        import comtypes
        from comtypes import GUID, COMMETHOD, HRESULT, POINTER
        from comtypes.automation import IUnknown
    except Exception as e:
        return None, f"comtypes is not available: {type(e).__name__}: {e}"

    WAVE_FORMAT_PCM = 0x0001
    WAVE_FORMAT_IEEE_FLOAT = 0x0003
    WAVE_FORMAT_EXTENSIBLE = 0xFFFE
    AUDCLNT_SHAREMODE_SHARED = 0
    AUDCLNT_STREAMFLAGS_LOOPBACK = 0x00020000
    CLSCTX_ALL = 23
    eRender = 0
    eConsole = 0
    IID_IAudioCaptureClient = GUID("{C8ADBD64-E71E-48A0-A4DE-185C395CD317}")
    KSDATAFORMAT_SUBTYPE_IEEE_FLOAT = GUID("{00000003-0000-0010-8000-00AA00389B71}")

    class WAVEFORMATEX(ctypes.Structure):
        _fields_ = [
            ("wFormatTag", wintypes.WORD),
            ("nChannels", wintypes.WORD),
            ("nSamplesPerSec", wintypes.DWORD),
            ("nAvgBytesPerSec", wintypes.DWORD),
            ("nBlockAlign", wintypes.WORD),
            ("wBitsPerSample", wintypes.WORD),
            ("cbSize", wintypes.WORD),
        ]

    class WAVEFORMATEXTENSIBLE(ctypes.Structure):
        _fields_ = [
            ("Format", WAVEFORMATEX),
            ("wValidBitsPerSample", wintypes.WORD),
            ("dwChannelMask", wintypes.DWORD),
            ("SubFormat", GUID),
        ]

    class IMMDevice(IUnknown):
        _iid_ = GUID("{D666063F-1587-4E43-81F1-B948E807363F}")
        _methods_ = [
            COMMETHOD([], HRESULT, "Activate",
                (["in"], POINTER(GUID), "iid"),
                (["in"], wintypes.DWORD, "dwClsCtx"),
                (["in"], ctypes.c_void_p, "pActivationParams"),
                (["out"], ctypes.POINTER(ctypes.c_void_p), "ppInterface")),
        ]

    class IMMDeviceEnumerator(IUnknown):
        _iid_ = GUID("{A95664D2-9614-4F35-A746-DE8DB63617E6}")
        _methods_ = [
            COMMETHOD([], HRESULT, "EnumAudioEndpoints"),
            COMMETHOD([], HRESULT, "GetDefaultAudioEndpoint",
                (["in"], ctypes.c_int, "dataFlow"),
                (["in"], ctypes.c_int, "role"),
                (["out"], ctypes.POINTER(POINTER(IMMDevice)), "ppEndpoint")),
        ]

    class IAudioClient(IUnknown):
        _iid_ = GUID("{1CB9AD4C-DBFA-4c32-B178-C2F568A703B2}")
        _methods_ = [
            COMMETHOD([], HRESULT, "Initialize",
                (["in"], ctypes.c_int, "ShareMode"),
                (["in"], wintypes.DWORD, "StreamFlags"),
                (["in"], ctypes.c_longlong, "hnsBufferDuration"),
                (["in"], ctypes.c_longlong, "hnsPeriodicity"),
                (["in"], ctypes.POINTER(WAVEFORMATEX), "pFormat"),
                (["in"], ctypes.POINTER(GUID), "AudioSessionGuid")),
            COMMETHOD([], HRESULT, "GetBufferSize", (["out"], ctypes.POINTER(ctypes.c_uint32), "pNumBufferFrames")),
            COMMETHOD([], HRESULT, "GetStreamLatency"),
            COMMETHOD([], HRESULT, "GetCurrentPadding"),
            COMMETHOD([], HRESULT, "IsFormatSupported"),
            COMMETHOD([], HRESULT, "GetMixFormat", (["out"], ctypes.POINTER(ctypes.POINTER(WAVEFORMATEX)), "ppDeviceFormat")),
            COMMETHOD([], HRESULT, "GetDevicePeriod"),
            COMMETHOD([], HRESULT, "Start"),
            COMMETHOD([], HRESULT, "Stop"),
            COMMETHOD([], HRESULT, "Reset"),
            COMMETHOD([], HRESULT, "SetEventHandle"),
            COMMETHOD([], HRESULT, "GetService",
                (["in"], POINTER(GUID), "riid"),
                (["out"], ctypes.POINTER(ctypes.c_void_p), "ppv")),
        ]

    class IAudioCaptureClient(IUnknown):
        _iid_ = IID_IAudioCaptureClient
        _methods_ = [
            COMMETHOD([], HRESULT, "GetBuffer",
                (["out"], ctypes.POINTER(ctypes.POINTER(ctypes.c_ubyte)), "ppData"),
                (["out"], ctypes.POINTER(ctypes.c_uint32), "pNumFramesToRead"),
                (["out"], ctypes.POINTER(wintypes.DWORD), "pdwFlags"),
                (["out"], ctypes.POINTER(ctypes.c_uint64), "pu64DevicePosition"),
                (["out"], ctypes.POINTER(ctypes.c_uint64), "pu64QPCPosition")),
            COMMETHOD([], HRESULT, "ReleaseBuffer", (["in"], ctypes.c_uint32, "NumFramesRead")),
            COMMETHOD([], HRESULT, "GetNextPacketSize", (["out"], ctypes.POINTER(ctypes.c_uint32), "pNumFramesInNextPacket")),
        ]

    try:
        comtypes.CoInitialize()
        enumerator = comtypes.CoCreateInstance(
            GUID("{BCDE0395-E52F-467C-8E3D-C4579291692E}"),
            interface=IMMDeviceEnumerator,
            clsctx=CLSCTX_ALL,
        )
        device = enumerator.GetDefaultAudioEndpoint(eRender, eConsole)
        audio_client_ptr = device.Activate(ctypes.byref(IAudioClient._iid_), CLSCTX_ALL, None)
        audio_client = ctypes.cast(audio_client_ptr, POINTER(IAudioClient))
        mix_ptr = audio_client.GetMixFormat()
        fmt = mix_ptr.contents
        ext = ctypes.cast(mix_ptr, ctypes.POINTER(WAVEFORMATEXTENSIBLE)).contents if fmt.wFormatTag == WAVE_FORMAT_EXTENSIBLE else None
        raw_bits = int(ext.wValidBitsPerSample if ext else fmt.wBitsPerSample)
        bits = raw_bits if raw_bits in (8, 16, 24, 32) else int(fmt.wBitsPerSample)
        subtype = str(ext.SubFormat).lower() if ext is not None else ""
        is_float = fmt.wFormatTag == WAVE_FORMAT_IEEE_FLOAT or "00000003" in subtype or bits == 32
        actual_rate = int(fmt.nSamplesPerSec)
        input_channels = int(fmt.nChannels)
        output_channels = max(1, min(int(channels), input_channels, 2))
        frame_samples = max(128, int(actual_rate * frame_ms / 1000))
        buffer_duration_ms = max(40, int(frame_ms) * 4)
        audio_client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK,
            buffer_duration_ms * 10_000,
            0,
            mix_ptr,
            None,
        )
        capture_ptr = audio_client.GetService(ctypes.byref(IID_IAudioCaptureClient))
        capture_client = ctypes.cast(capture_ptr, POINTER(IAudioCaptureClient))
        source = WasapiLoopbackSource(
            audio_client,
            capture_client,
            actual_rate,
            input_channels,
            output_channels,
            bits,
            int(fmt.nBlockAlign),
            is_float,
            frame_samples,
        )
        source.start()
        print(
            f"[audio] started coreaudio-wasapi-loopback rate={actual_rate} in_ch={input_channels} "
            f"out_ch={output_channels} bits={bits} float={is_float} buffer_ms={buffer_duration_ms}",
            flush=True,
        )
        return source, ""
    except Exception as e:
        return None, f"Core Audio WASAPI loopback failed: {type(e).__name__}: {e}"

@app.websocket("/audio/loopback")
async def audio_loopback_endpoint(
    websocket: WebSocket,
    sample_rate: int = 48000,
    channels: int = 2,
    frame_ms: int = 20,
):
    await websocket.accept()
    proc = None
    audio_source = None
    stderr_task = None
    try:
        if await websocket.receive_text() != PASSWORD:
            await websocket.send_text("error: auth failed")
            await safe_close(websocket)
            return

        sample_rate = max(16000, min(int(sample_rate), 48000))
        channels = max(1, min(int(channels), 2))
        frame_ms = max(10, min(int(frame_ms), 40))
        frame_bytes = int(sample_rate * channels * 2 * frame_ms / 1000)
        if frame_bytes <= 0:
            await websocket.send_text(json.dumps({"type": "error", "message": "invalid audio frame size"}))
            return

        audio_source, coreaudio_error = await asyncio.to_thread(start_coreaudio_wasapi_loopback_source, sample_rate, channels, frame_ms)
        source_name = "coreaudio-wasapi-loopback"
        if audio_source:
            sample_rate = audio_source.sample_rate
            channels = audio_source.channels
            frame_bytes = audio_source.frame_bytes
        else:
            print(f"[audio] coreaudio fallback unavailable: {coreaudio_error}", flush=True)
            audio_source, pyaudio_error = await asyncio.to_thread(start_pyaudio_loopback_source, sample_rate, channels, frame_ms)
            source_name = "pyaudio"
        if audio_source:
            sample_rate = audio_source.sample_rate
            channels = audio_source.channels
            frame_bytes = audio_source.frame_bytes
        else:
            print(f"[audio] pyaudio fallback unavailable: {pyaudio_error}", flush=True)
            proc, error = await start_wasapi_audio_process(sample_rate, channels, frame_ms)
            if not proc:
                await websocket.send_text(json.dumps({"type": "error", "message": f"{coreaudio_error}; {pyaudio_error}; {error}"}))
                return
            source_name = "ffmpeg"
            stderr_task = asyncio.create_task(drain_process_stderr(proc, "audio-ffmpeg"))

        await websocket.send_text(json.dumps({
            "type": "audio_config",
            "sample_rate": sample_rate,
            "channels": channels,
            "format": "pcm_s16le",
            "frame_ms": frame_ms,
            "source": source_name,
        }))

        seq = 0
        start_us = int(time.perf_counter() * 1_000_000)
        while not shutdown_event.is_set():
            if audio_source:
                chunk = await asyncio.to_thread(audio_source.read)
            else:
                chunk = await asyncio.to_thread(proc.stdout.read, frame_bytes)
            if not chunk:
                if proc and proc.poll() is not None:
                    await websocket.send_text(json.dumps({"type": "error", "message": f"audio capture ended with code {proc.returncode}"}))
                    break
                await asyncio.sleep(0.005)
                continue
            if len(chunk) < frame_bytes:
                chunk += b"\x00" * (frame_bytes - len(chunk))
            pts_us = start_us + int(seq * frame_ms * 1000)
            header = struct.pack("<QII", pts_us, seq & 0xFFFFFFFF, len(chunk))
            await websocket.send_bytes(header + chunk)
            seq += 1
    except WebSocketDisconnect:
        pass
    except asyncio.CancelledError:
        pass
    except Exception as e:
        print(f"[audio] loopback failed: {type(e).__name__}: {e}", flush=True)
        try:
            await websocket.send_text(json.dumps({"type": "error", "message": str(e)}))
        except Exception:
            pass
    finally:
        if stderr_task:
            stderr_task.cancel()
        if audio_source:
            await asyncio.to_thread(audio_source.close)
        if proc:
            try:
                proc.terminate()
                try:
                    await asyncio.to_thread(proc.wait, 1.5)
                except Exception:
                    proc.kill()
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
        started_at = time.perf_counter()
        try:
            clients = list(grid_preview_clients)
            if clients:
                previews = await asyncio.to_thread(fetch_grid_previews)
                if previews:
                    msg = json.dumps({"type": "grid_previews", "data": previews})
                    stale_clients = []
                    for ws in clients:
                        try:
                            await ws.send_text(msg)
                        except Exception:
                            stale_clients.append(ws)
                    for ws in stale_clients:
                        grid_preview_clients.discard(ws)
                        connected_clients.discard(ws)
            elapsed = time.perf_counter() - started_at
            await asyncio.sleep(max(0.05, GRID_PREVIEW_INTERVAL - elapsed))
        except asyncio.CancelledError: break # 关键修复！
        except Exception:
            elapsed = time.perf_counter() - started_at
            await asyncio.sleep(max(0.05, GRID_PREVIEW_INTERVAL - elapsed))

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
