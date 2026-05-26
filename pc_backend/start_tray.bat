@echo off
setlocal
set "BASE_DIR=%~dp0"
set "PYTHONW=pythonw"

net session >nul 2>nul
if not "%errorlevel%"=="0" (
    powershell -NoProfile -ExecutionPolicy Bypass -Command "Start-Process -FilePath '%~f0' -Verb RunAs"
    exit /b
)

set "PS_CMD=Get-CimInstance Win32_Process ^| Where-Object { $_.CommandLine -and $_.CommandLine -like '*pc_backend*tray.pyw*' } ^| ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }"
powershell -NoProfile -ExecutionPolicy Bypass -Command "%PS_CMD%" >nul 2>nul

where pythonw >nul 2>nul
if errorlevel 1 (
    set "PYTHONW=pyw"
)

start "" "%PYTHONW%" "%BASE_DIR%tray.pyw"
endlocal
