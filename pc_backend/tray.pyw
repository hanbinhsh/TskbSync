import os
import subprocess
import sys
import traceback
import time
import winreg

import win32api
import win32con
import win32event
import win32gui


APP_NAME = "TskbSync Backend"
AUTOSTART_NAME = "TskbSyncBackendTray"
WM_TRAYICON = win32con.WM_USER + 20
TRAY_UID = 1

MENU_OPEN_LOGS = 1001
MENU_RESTART = 1002
MENU_AUTOSTART = 1003
MENU_EXIT = 1004
ERROR_ALREADY_EXISTS = 183

BASE_DIR = os.path.dirname(os.path.abspath(__file__))
MAIN_PY = os.path.join(BASE_DIR, "main.py")
ICON_PATH = os.path.join(BASE_DIR, "logo.ico")
LOG_DIR = os.path.join(BASE_DIR, "logs")
LOG_PATH = os.path.join(LOG_DIR, "server.log")
TRAY_LOG_PATH = os.path.join(LOG_DIR, "tray.log")


def tray_log(message):
    try:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(TRAY_LOG_PATH, "a", encoding="utf-8") as f:
            f.write(f"{time.strftime('%Y-%m-%d %H:%M:%S')} {message}\n")
    except Exception:
        pass


class BackendTray:
    def __init__(self):
        self.hwnd = None
        self.icon = None
        self.process = None
        self.log_file = None
        os.makedirs(LOG_DIR, exist_ok=True)

    def run(self):
        tray_log("tray starting")
        self._create_window()
        self._add_tray_icon()
        self.start_service()
        win32gui.PumpMessages()

    def _create_window(self):
        message_map = {
            win32con.WM_CLOSE: self.on_close,
            win32con.WM_DESTROY: self.on_destroy,
            win32con.WM_COMMAND: self.on_command,
            WM_TRAYICON: self.on_tray_event,
        }
        wc = win32gui.WNDCLASS()
        wc.hInstance = win32api.GetModuleHandle(None)
        wc.lpszClassName = "TskbSyncBackendTrayWindow"
        wc.lpfnWndProc = message_map
        win32gui.RegisterClass(wc)
        self.hwnd = win32gui.CreateWindow(
            wc.lpszClassName,
            APP_NAME,
            win32con.WS_OVERLAPPED,
            0,
            0,
            1,
            1,
            0,
            0,
            wc.hInstance,
            None,
        )

    def _load_icon(self):
        if os.path.exists(ICON_PATH):
            return win32gui.LoadImage(
                0,
                ICON_PATH,
                win32con.IMAGE_ICON,
                0,
                0,
                win32con.LR_LOADFROMFILE | win32con.LR_DEFAULTSIZE,
            )
        return win32gui.LoadIcon(0, win32con.IDI_APPLICATION)

    def _add_tray_icon(self):
        self.icon = self._load_icon()
        flags = win32gui.NIF_ICON | win32gui.NIF_MESSAGE | win32gui.NIF_TIP
        nid = (self.hwnd, TRAY_UID, flags, WM_TRAYICON, self.icon, APP_NAME)
        try:
            win32gui.Shell_NotifyIcon(win32gui.NIM_ADD, nid)
            tray_log("tray icon added")
        except Exception:
            fallback_icon = win32gui.LoadIcon(0, win32con.IDI_APPLICATION)
            fallback_nid = (self.hwnd, TRAY_UID, flags, WM_TRAYICON, fallback_icon, APP_NAME)
            win32gui.Shell_NotifyIcon(win32gui.NIM_ADD, fallback_nid)
            self.icon = fallback_icon
            tray_log("tray icon added with fallback icon")

    def _remove_tray_icon(self):
        try:
            win32gui.Shell_NotifyIcon(win32gui.NIM_DELETE, (self.hwnd, TRAY_UID))
        except Exception:
            pass

    def _python_console_exe(self):
        exe = sys.executable
        if exe.lower().endswith("pythonw.exe"):
            candidate = os.path.join(os.path.dirname(exe), "python.exe")
            if os.path.exists(candidate):
                return candidate
        return exe

    def _python_windowless_exe(self):
        exe = sys.executable
        if exe.lower().endswith("python.exe"):
            candidate = os.path.join(os.path.dirname(exe), "pythonw.exe")
            if os.path.exists(candidate):
                return candidate
        return exe

    def start_service(self):
        if self.process and self.process.poll() is None:
            return
        self.cleanup_port_owner()
        self._close_log_file()
        self.log_file = open(LOG_PATH, "a", encoding="utf-8", buffering=1)
        self.log_file.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} start service ===\n")
        self.process = subprocess.Popen(
            [self._python_console_exe(), "-u", MAIN_PY],
            cwd=BASE_DIR,
            stdout=self.log_file,
            stderr=subprocess.STDOUT,
            stdin=subprocess.DEVNULL,
            creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
        )

    def _port_owners(self, port):
        owners = set()
        try:
            output = subprocess.check_output(
                ["netstat", "-ano", "-p", "tcp"],
                text=True,
                encoding="utf-8",
                errors="ignore",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception as e:
            tray_log(f"port check failed: {e}")
            return owners
        suffix = f":{port}"
        for line in output.splitlines():
            parts = line.split()
            if len(parts) < 5 or parts[0].upper() != "TCP":
                continue
            local_addr, state, pid_text = parts[1], parts[3].upper(), parts[-1]
            if state == "LISTENING" and local_addr.endswith(suffix):
                try:
                    owners.add(int(pid_text))
                except ValueError:
                    pass
        return owners

    def _process_command_line(self, pid):
        try:
            output = subprocess.check_output(
                ["wmic", "process", "where", f"ProcessId={pid}", "get", "CommandLine", "/value"],
                text=True,
                encoding="utf-8",
                errors="ignore",
                creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
            )
        except Exception:
            return ""
        for line in output.splitlines():
            if line.startswith("CommandLine="):
                return line.split("=", 1)[1]
        return ""

    def cleanup_port_owner(self):
        current_pid = self.process.pid if self.process else None
        for pid in self._port_owners(8000):
            if pid == current_pid:
                continue
            command_line = self._process_command_line(pid)
            normalized = command_line.replace("\\", "/").lower()
            if "main.py" in normalized and "pc_backend" in normalized:
                tray_log(f"stopping old backend pid={pid}")
                try:
                    subprocess.run(
                        ["taskkill", "/PID", str(pid), "/T", "/F"],
                        check=False,
                        stdout=subprocess.DEVNULL,
                        stderr=subprocess.DEVNULL,
                        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
                    )
                except Exception as e:
                    tray_log(f"failed to stop old backend pid={pid}: {e}")
            else:
                tray_log(f"port 8000 occupied by unmanaged pid={pid} cmd={command_line!r}")

    def stop_service(self):
        if self.process and self.process.poll() is None:
            try:
                self.process.terminate()
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait(timeout=3)
            except Exception:
                pass
        self.process = None
        self._close_log_file()

    def restart_service(self):
        self.stop_service()
        self.start_service()

    def _close_log_file(self):
        if self.log_file:
            try:
                self.log_file.flush()
                self.log_file.close()
            except Exception:
                pass
        self.log_file = None

    def open_logs(self):
        if not os.path.exists(LOG_PATH):
            open(LOG_PATH, "a", encoding="utf-8").close()
        command = (
            "Write-Host 'TskbSync Backend Logs'; "
            f"Get-Content -LiteralPath '{LOG_PATH}' -Wait -Tail 200"
        )
        subprocess.Popen(
            ["powershell.exe", "-NoExit", "-ExecutionPolicy", "Bypass", "-Command", command],
            cwd=BASE_DIR,
            creationflags=getattr(subprocess, "CREATE_NEW_CONSOLE", 0),
        )

    def _autostart_command(self):
        return f'"{self._python_windowless_exe()}" "{os.path.abspath(__file__)}"'

    def is_autostart_enabled(self):
        try:
            with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run") as key:
                value, _ = winreg.QueryValueEx(key, AUTOSTART_NAME)
            return value == self._autostart_command()
        except FileNotFoundError:
            return False

    def toggle_autostart(self):
        run_key = r"Software\Microsoft\Windows\CurrentVersion\Run"
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, run_key, 0, winreg.KEY_SET_VALUE) as key:
            if self.is_autostart_enabled():
                try:
                    winreg.DeleteValue(key, AUTOSTART_NAME)
                except FileNotFoundError:
                    pass
            else:
                winreg.SetValueEx(key, AUTOSTART_NAME, 0, winreg.REG_SZ, self._autostart_command())

    def show_menu(self):
        pos = win32gui.GetCursorPos()
        tray_log(f"show native menu at {pos}")
        menu = win32gui.CreatePopupMenu()
        autostart_text = "Disable Autostart" if self.is_autostart_enabled() else "Enable Autostart"
        win32gui.AppendMenu(menu, win32con.MF_STRING, MENU_OPEN_LOGS, "Open Console Logs")
        win32gui.AppendMenu(menu, win32con.MF_STRING, MENU_RESTART, "Restart Service")
        win32gui.AppendMenu(menu, win32con.MF_STRING, MENU_AUTOSTART, autostart_text)
        win32gui.AppendMenu(menu, win32con.MF_SEPARATOR, 0, "")
        win32gui.AppendMenu(menu, win32con.MF_STRING, MENU_EXIT, "Exit Service")
        win32gui.SetForegroundWindow(self.hwnd)
        selected = win32gui.TrackPopupMenu(
            menu,
            win32con.TPM_LEFTALIGN | win32con.TPM_BOTTOMALIGN | win32con.TPM_RETURNCMD,
            pos[0],
            pos[1],
            0,
            self.hwnd,
            None,
        )
        win32gui.PostMessage(self.hwnd, win32con.WM_NULL, 0, 0)
        win32gui.DestroyMenu(menu)
        if selected:
            self.handle_command(selected)

    def on_tray_event(self, hwnd, msg, wparam, lparam):
        tray_log(f"tray event lparam={lparam}")
        if lparam in (win32con.WM_RBUTTONUP, win32con.WM_LBUTTONUP):
            self.show_menu()
        return True

    def on_command(self, hwnd, msg, wparam, lparam):
        self.handle_command(win32api.LOWORD(wparam))
        return True

    def handle_command(self, command_id):
        tray_log(f"handle command {command_id}")
        if command_id == MENU_OPEN_LOGS:
            self.open_logs()
        elif command_id == MENU_RESTART:
            self.restart_service()
        elif command_id == MENU_AUTOSTART:
            self.toggle_autostart()
        elif command_id == MENU_EXIT:
            win32gui.DestroyWindow(self.hwnd)

    def on_close(self, hwnd, msg, wparam, lparam):
        win32gui.DestroyWindow(self.hwnd)
        return True

    def on_destroy(self, hwnd, msg, wparam, lparam):
        self.stop_service()
        self._remove_tray_icon()
        win32gui.PostQuitMessage(0)
        return True


def main():
    mutex = win32event.CreateMutex(None, False, "TskbSyncBackendTray")
    if win32api.GetLastError() == ERROR_ALREADY_EXISTS:
        return
    tray = BackendTray()
    tray.run()
    win32api.CloseHandle(mutex)


if __name__ == "__main__":
    try:
        main()
    except Exception:
        os.makedirs(LOG_DIR, exist_ok=True)
        with open(os.path.join(LOG_DIR, "tray_error.log"), "a", encoding="utf-8") as f:
            f.write(f"\n=== {time.strftime('%Y-%m-%d %H:%M:%S')} tray failed ===\n")
            traceback.print_exc(file=f)
