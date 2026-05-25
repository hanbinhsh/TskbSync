# TskbSync native streamer

Experimental low-latency Windows screen streamer.

The helper captures a monitor in native C++, encodes H.264 with Media Foundation,
and writes the encoded Annex-B byte stream to stdout. The Python backend starts
this process and forwards stdout to the Android client.

Build from a Visual Studio x64 developer shell:

```bat
cmake -S pc_backend\native_streamer -B pc_backend\native_streamer\build -G "Visual Studio 18 2026" -A x64
cmake --build pc_backend\native_streamer\build --config Release
```

Or with the VS developer environment initialized from PowerShell/cmd:

```bat
cmd /c ""E:\tool\VS2026\VC\Auxiliary\Build\vcvars64.bat" && cmake -S pc_backend\native_streamer -B pc_backend\native_streamer\build -G "NMake Makefiles""
cmd /c ""E:\tool\VS2026\VC\Auxiliary\Build\vcvars64.bat" && cmake --build pc_backend\native_streamer\build --config Release"
```

The backend looks for:

```text
pc_backend/native_streamer/build/tskbsync_native_streamer.exe
pc_backend/native_streamer/build/Release/tskbsync_native_streamer.exe
pc_backend/native_streamer/build/Debug/tskbsync_native_streamer.exe
pc_backend/bin/tskbsync_native_streamer.exe
```
