"""Check history and workout through system input, without Compose instrumentation."""
from pathlib import Path
import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET

out = Path('ui-audit-results')
out.mkdir(exist_ok=True)
resources = [ET.parse(Path('app/src/main/res') / p / 'strings.xml').getroot()
             for p in ('values', 'values-en')]
def labels(key):
    return {''.join(n.itertext()) for r in resources for n in r if n.get('name') == key}
def adb(*args, binary=False):
    p = subprocess.run(['adb', *args], check=True, capture_output=True)
    return p.stdout if binary else p.stdout.decode('utf-8', errors='replace')
def dump():
    adb('shell', 'uiautomator', 'dump', '/sdcard/ux-native.xml')
    xml = adb('shell', 'cat', '/sdcard/ux-native.xml')
    (out / '41-native-latest.xml').write_text(xml)
    return list(ET.fromstring(xml).iter('node'))
def text_is(node, key):
    return node.get('text') in labels(key)
def tap(node):
    bounds = list(map(int, re.findall(r'\d+', node.get('bounds', ''))))
    assert len(bounds) == 4 and bounds[2] > bounds[0] and bounds[3] > bounds[1]
    adb('shell', 'input', 'tap', str((bounds[0] + bounds[2]) // 2), str((bounds[1] + bounds[3]) // 2))
def wait_for(predicate, seconds=20):
    deadline = time.monotonic() + seconds
    while time.monotonic() < deadline:
        nodes = dump()
        node = next((n for n in nodes if predicate(n)), None)
        if node is not None:
            return node
        time.sleep(.25)
    screenshot('41-native-timeout')
    raise RuntimeError('Native UI did not reach expected state; inspect latest XML and screenshot')
def screenshot(name):
    # A matching accessibility tree can precede the SurfaceFlinger frame.
    time.sleep(1)
    (out / (name + '.png')).write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
    (out / (name + '-window.txt')).write_text(adb('shell', 'dumpsys', 'window'))
    (out / (name + '-statusbar.txt')).write_text(adb('shell', 'dumpsys', 'statusbar'))
    (out / (name + '-ime.txt')).write_text(adb('shell', 'dumpsys', 'input_method'))

adb('shell', 'am', 'force-stop', 'ru.tabatabata')
adb('shell', 'am', 'start', '-W', '-n', 'ru.tabatabata/.MainActivity')
wait_for(lambda n: text_is(n, 'start'))
screenshot('41-native-home')
tap(wait_for(lambda n: n.get('content-desc') in labels('history_open')))
history_started = time.monotonic()
wait_for(lambda n: text_is(n, 'history_this_week'))
history_seconds = time.monotonic() - history_started
screenshot('41-native-history')
tap(wait_for(lambda n: n.get('content-desc') in {'← ' + s for s in labels('saved')}))
# Reproduce native IME ownership changes before the workout, without test hooks.
tap(wait_for(lambda n: any(v in n.get('text', '') for v in labels('new_timer'))))
field = wait_for(lambda n: n.get('class') == 'android.widget.EditText')
tap(field)
adb('shell', 'input', 'text', 'NativeProbe')
time.sleep(1)
screenshot('41-native-editor-ime')
adb('shell', 'input', 'keyevent', '4')
time.sleep(1)
tap(wait_for(lambda n: n.get('content-desc') in {'← ' + s for s in labels('saved')}))
tap(wait_for(lambda n: text_is(n, 'discard')))
wait_for(lambda n: text_is(n, 'start'))
screenshot('41-native-after-ime-home')
tap(wait_for(lambda n: text_is(n, 'start')))
prompt_dismissed = False
deadline = time.monotonic() + 25
while time.monotonic() < deadline:
    nodes = dump()
    pause = next((n for n in nodes if text_is(n, 'pause')), None)
    if pause is not None:
        tap(pause)
        break
    later = next((n for n in nodes if text_is(n, 'later')), None)
    if later is not None and not prompt_dismissed:
        tap(later)
        prompt_dismissed = True
else:
    screenshot('41-native-start-timeout')
    raise RuntimeError('Native Start did not reach running workout')
wait_for(lambda n: text_is(n, 'resume'))
screenshot('41-native-workout')
(out / '41-native-result.json').write_text(json.dumps({
    'history_loaded': True, 'workout_paused': True,
    'history_observation_seconds_including_ui_dump': round(history_seconds, 3),
    'input': 'adb system touch', 'instrumentation': False,
}, indent=2))
adb('shell', 'am', 'force-stop', 'ru.tabatabata')
print('Native history and workout reached through system input; screenshots saved')
