You do not have to copy ffmpeg.exe here if you already installed FFmpeg elsewhere.

Lookup order:
1. TSKBSYNC_FFMPEG environment variable, pointing to ffmpeg.exe or an FFmpeg directory
2. TSKBSYNC_FFMPEG_DIR environment variable, pointing to an FFmpeg directory
3. pc_backend/ffmpeg_path.txt, containing either ffmpeg.exe path or an FFmpeg directory
4. pc_backend/bin/ffmpeg.exe
5. ffmpeg from PATH
