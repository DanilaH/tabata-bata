"""Capture the ordinary workout outside instrumentation, using real system input."""
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

out = Path('ui-audit-results')
def adb(*args, binary=False):
    p = subprocess.run(['adb', *args], check=True, capture_output=True)
    return p.stdout if binary else p.stdout.decode('utf-8', errors='replace')

adb('shell', 'am', 'force-stop', 'ru.tabatabata')
adb('shell', 'am', 'start', '-W', '-n', 'ru.tabatabata/.MainActivity')
time.sleep(2)
adb('shell', 'uiautomator', 'dump', '/sdcard/ux-native.xml')
root = ET.fromstring(adb('shell', 'cat', '/sdcard/ux-native.xml'))
starts = [n for n in root.iter('node') if n.get('text') == 'Начать' and n.get('bounds')]
if not starts:
    raise RuntimeError('Native Start action absent; inspect native-home.png')
(out / '41-native-home.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
box = list(map(int, re.findall(r'\d+', starts[0].get('bounds'))))
adb('shell', 'input', 'tap', str((box[0] + box[2]) // 2), str((box[1] + box[3]) // 2))
time.sleep(1)
(out / '41-native-workout.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
adb('shell', 'uiautomator', 'dump', '/sdcard/ux-native.xml')
active = adb('shell', 'cat', '/sdcard/ux-native.xml')
(out / '41-native-workout.xml').write_text(active)
assert 'Пауза' in active, 'Native tap did not reach running workout'
adb('shell', 'am', 'force-stop', 'ru.tabatabata')
print('Native workout reached through system input; screenshots saved')
