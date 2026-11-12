"""Cold boot an AVD after wm overrides so launcher sees its final display profile."""
import subprocess
import time

def adb(*args):
    return subprocess.run(['adb', *args], check=True, capture_output=True,
                          timeout=30).stdout.decode(errors='replace').strip()

adb('reboot')
adb('wait-for-device')
deadline = time.monotonic() + 180
while time.monotonic() < deadline:
    try:
        if adb('shell', 'getprop', 'sys.boot_completed') == '1':
            break
    except (subprocess.CalledProcessError, subprocess.TimeoutExpired):
        pass
    time.sleep(2)
else:
    raise RuntimeError('AVD did not finish cold boot after display overrides')
adb('shell', 'input', 'keyevent', '82')
