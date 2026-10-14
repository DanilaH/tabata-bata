"""Out-of-process emulator checks; does not claim hardware audio reliability."""
import hashlib
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "ru.tabatabata"
APK = "app/build/outputs/apk/debug/app-debug.apk"
OUT = Path("app/build/reports/system-check")
OUT.mkdir(parents=True, exist_ok=True)


def adb(*args, binary=False):
    return subprocess.check_output(["adb", *args], text=not binary, timeout=30)


def wait_for(check, seconds=15):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        if check():
            return
        time.sleep(.3)
    raise AssertionError("Timed out waiting for Android state")


def hierarchy():
    adb("shell", "uiautomator", "dump", "/sdcard/tabata-check.xml")
    return adb("exec-out", "cat", "/sdcard/tabata-check.xml")


def visible(*labels):
    xml = hierarchy()
    (OUT / "last-ui.xml").write_text(xml)
    return next((n for n in ET.fromstring(xml).iter("node") if n.get("text") in labels), None)


def service_running():
    dump = adb("shell", "dumpsys", "activity", "services", PACKAGE)
    return "ru.tabatabata/.TimerService" in dump and "isForeground=true" in dump


adb("install", "-r", APK)
adb("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
adb("shell", "wm", "dismiss-keyguard")
adb("shell", "am", "force-stop", PACKAGE)
adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
button = None

def find_start():
    global button
    button = visible("Начать тренировку", "Start workout")
    return button is not None

wait_for(find_start)
x1, y1, x2, y2 = map(int, re.findall(r"\d+", button.get("bounds")))
adb("shell", "input", "tap", str((x1+x2)//2), str((y1+y2)//2))
wait_for(service_running)
time.sleep(2)
adb("shell", "am", "force-stop", PACKAGE)
assert not service_running(), "Force-stop must remove the foreground service"
checkpoint = adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/session.xml")
assert 'name="active"' in checkpoint, "Running session must leave a recovery checkpoint"
prefs_before = adb("exec-out", "run-as", PACKAGE, "cat", "files/datastore/tabata.preferences_pb", binary=True)
adb("install", "-r", APK)
prefs_after = adb("exec-out", "run-as", PACKAGE, "cat", "files/datastore/tabata.preferences_pb", binary=True)
assert hashlib.sha256(prefs_before).digest() == hashlib.sha256(prefs_after).digest(), "Reinstall must preserve DataStore bytes"
assert checkpoint == adb("exec-out", "run-as", PACKAGE, "cat", "shared_prefs/session.xml"), "Reinstall must preserve checkpoint"
adb("shell", "am", "start", "-W", "-n", PACKAGE + "/.MainActivity")
wait_for(lambda: visible("Тренировка была прервана", "Workout interrupted") is not None)
time.sleep(2)
assert not service_running(), "Opening recovery dialog must not restart the service"
(OUT / "recovery.png").write_bytes(adb("exec-out", "screencap", "-p", binary=True))
(OUT / "result.txt").write_text("PASS: force-stop removes service; checkpoint survives; same-version APK reinstall preserves DataStore and checkpoint; relaunch offers manual recovery without starting service.\n")
print((OUT / "result.txt").read_text())
adb("shell", "am", "force-stop", PACKAGE)
