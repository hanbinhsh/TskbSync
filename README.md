# TskbSync

TskbSync is an Android + Windows companion app for viewing and controlling a Windows PC from an Android phone or tablet. The Android app shows window and screen previews, sends touch/mouse/keyboard input, and can stream a physical or virtual display from the PC backend.

## Features

- Window list, window switching, and basic window controls
- JPEG preview mode for compatibility
- Hardware H.264 screen/window streaming when FFmpeg and a supported encoder are available
- Touch, mouse, keyboard text, shortcuts, wheel input, and multi-touch input
- Physical monitor streaming
- Optional virtual display / extended-screen workflow through a Windows VDD
- Tray backend with admin autostart support

## Project Layout

```text
app/                         Android Jetpack Compose app
pc_backend/                  Windows Python backend and tray launcher
pc_backend/native_streamer/   Experimental native H.264 streamer helper
pc_backend/bin/              Optional local binaries such as ffmpeg.exe
```

## Android App

Build the debug APK with:

```powershell
.\gradlew.bat assembleDebug
```

Install the generated APK from:

```text
app/build/outputs/apk/debug/
```

Current Android configuration targets SDK 36 and uses Jetpack Compose, Ktor WebSocket/HTTP client, DataStore, Navigation Compose, and Coil.

## PC Backend

Start the backend directly:

```powershell
cd pc_backend
python -u main.py
```

Or start tray mode:

```text
pc_backend/start_tray.bat
```

`start_tray.bat` requests UAC elevation. Administrator mode is required for changing the virtual display driver state from the Android app.

Default backend ports:

- HTTP/WebSocket API: `8000`
- UDP discovery: `8001`

The current default password in the backend is `123`; the Android app must use the same password.

## FFmpeg And Hardware Encoding

Hardware H.264 streaming requires FFmpeg with a usable encoder such as:

- `h264_nvenc`
- `h264_qsv`
- `h264_amf`

FFmpeg lookup order:

1. `TSKBSYNC_FFMPEG`, pointing to `ffmpeg.exe` or an FFmpeg directory
2. `TSKBSYNC_FFMPEG_DIR`, pointing to an FFmpeg directory
3. `pc_backend/ffmpeg_path.txt`
4. `pc_backend/bin/ffmpeg.exe`
5. `ffmpeg` from `PATH`

You can copy `pc_backend/ffmpeg_path.example.txt` to `pc_backend/ffmpeg_path.txt` and put either an FFmpeg directory or full `ffmpeg.exe` path on the first non-comment line.

## Virtual Display Driver

The extended-screen feature requires a Windows virtual display driver. This repository does not ship or install one.

Recommended VDD repository:

```text
https://github.com/VirtualDrivers/Virtual-Display-Driver
```

Install and enable the VDD first. Once Windows exposes the virtual monitor, TskbSync can bind to it and stream it to the Android device.

Notes:

- The Android `+` button binds/switches to an existing virtual display.
- Resolution and refresh rate should be configured in Windows or the VDD tool, not inside TskbSync.
- Enabling/disabling the VDD from Android requires the PC backend to run as administrator.
- The tray menu can create an admin autostart Scheduled Task named `TskbSyncBackendTray`.

## Native Streamer

The optional native streamer lives in `pc_backend/native_streamer/`. See its README for Visual Studio/CMake build instructions. The backend looks for the generated `tskbsync_native_streamer.exe` in the native build directory or `pc_backend/bin/`.

## Logs

Backend logs are written under:

```text
pc_backend/logs/
```

Useful files:

- `server.log`
- `tray.log`
- `tray_error.log`

Logs and local binaries are ignored by git.

## Git Hygiene

The repository ignores Android/Gradle build output, Python caches, backend logs, local FFmpeg/native binaries, virtual environments, and native build artifacts.

To check whether any tracked files are also ignored by `.gitignore`, run:

```powershell
git ls-files -ci --exclude-standard
```

At the time this README was added, that command returned no files.
