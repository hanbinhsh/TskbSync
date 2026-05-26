# TskbSync PC Backend

This directory contains the Windows backend used by the Android app. It provides window listing, remote input, screen/window streaming, hardware H.264 streaming, and optional virtual-display support.

## Requirements

- Windows 10/11
- Python 3.10+
- Python dependencies installed for this project
- FFmpeg for hardware H.264 streaming
- Optional: Virtual Display Driver for the phone/tablet extended-screen feature

## Start

For normal streaming:

```powershell
python -u main.py
```

For tray mode, double-click:

```text
start_tray.bat
```

`start_tray.bat` requests administrator permission through UAC. Administrator mode is required if you want the Android app to enable or disable the virtual display driver.

## FFmpeg

Hardware streaming requires FFmpeg with an H.264 encoder such as `h264_nvenc`, `h264_qsv`, or `h264_amf`.

Recommended setup:

1. Download a Windows FFmpeg build.
2. Put `ffmpeg.exe` and `ffprobe.exe` in `pc_backend/bin/`, or make sure `ffmpeg.exe` is available in `PATH`.
3. Restart the backend.
4. In the Android app settings, enable `Use Hardware Encoding for Screen Streaming` as needed.

The backend will probe FFmpeg encoders and report status in the app settings.

## Virtual Display Driver

The extended-screen feature depends on an already installed Windows virtual display driver. This project does not install or ship the driver.

Recommended driver:

```text
https://github.com/VirtualDrivers/Virtual-Display-Driver
```

Install and enable the driver from that repository first. After Windows sees the virtual display, TskbSync can bind to it and stream it to the Android device.

Notes:

- The `+` button in the Android screen panel only binds/switches to an existing virtual display.
- Resolution and refresh rate should be configured in Windows/VDD tools, not inside TskbSync.
- Enabling/disabling the VDD from Android requires the PC backend to run as administrator.
- If the backend is not administrator, the app will show a permission warning instead of changing the driver state.

## Admin Autostart

The tray menu item `Enable Admin Autostart` creates a Windows Scheduled Task named `TskbSyncBackendTray`:

- Trigger: current user logon
- Run level: highest privileges
- Action: start `tray.pyw`

This is used instead of the normal registry `Run` key because Windows does not allow silent elevation from regular startup entries.

If creating the task fails, start the tray with `start_tray.bat`, accept the UAC prompt, and enable autostart from the tray menu again.

## Logs

Backend logs are written to:

```text
pc_backend/logs/server.log
pc_backend/logs/tray.log
```

These files are ignored by git.
