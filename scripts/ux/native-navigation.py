"""Check history and workout through system input, without Compose instrumentation."""
from pathlib import Path
import json
import re
import subprocess
import struct
import os
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
    (out / (name + '-systemui.txt')).write_text(adb('shell', 'dumpsys', 'activity', 'service', 'com.android.systemui/.SystemUIService'))
    (out / (name + '-launcher.txt')).write_text(adb('shell', 'dumpsys', 'activity', 'service', 'com.android.launcher3/com.android.quickstep.TouchInteractionService'))
    (out / (name + '-ime.txt')).write_text(adb('shell', 'dumpsys', 'input_method'))

def navigation_contrast():
    # Physical pixels, independent of the app's requested appearance flags.
    # In the controlled portrait 3-button phone profile Home is centered in its inset.
    raw = adb('exec-out', 'screencap', binary=True)
    width, height, pixel_format = struct.unpack_from('<III', raw)
    assert pixel_format == 1, 'Expected Android RGBA_8888 screenshot'
    offset = len(raw) - width * height * 4
    assert offset in (12, 16), 'Unexpected raw screenshot header'
    density = int(os.environ.get('UX_DENSITY', '420'))
    if width * 160 / density >= 600:
        return {'scope': 'tablet taskbar reviewed separately'}
    window = (out / '41-native-workout-window.txt').read_text()
    match = re.search(r'type=navigationBars frame=\[(\d+),(\d+)\]\[(\d+),(\d+)\]', window)
    assert match, 'Navigation inset missing'
    left, top, right, bottom = map(int, match.groups())
    assert bottom == height and bottom > top and right == width, 'Expected bottom 3-button bar'
    def sample(x, y):
        values = [raw[offset + ((y + dy) * width + x + dx) * 4:
                      offset + ((y + dy) * width + x + dx) * 4 + 3]
                  for dy in range(-2, 3) for dx in range(-2, 3)]
        return [sorted(v[channel] for v in values)[12] for channel in range(3)]
    def luminance(rgb):
        channels = [v / 255 for v in rgb]
        channels = [v / 12.92 if v <= .04045 else ((v + .055) / 1.055) ** 2.4 for v in channels]
        return sum(v * w for v, w in zip(channels, (.2126, .7152, .0722)))
    foreground = sample((left + right) // 2, (top + bottom) // 2)
    background = sample(left + 10, (top + bottom) // 2)
    lights = sorted((luminance(foreground), luminance(background)))
    contrast = (lights[1] + .05) / (lights[0] + .05)
    result = {'scope': 'portrait three-button phone', 'foreground_rgb': foreground,
              'background_rgb': background, 'ratio': round(contrast, 3), 'minimum': 3.0}
    (out / '41-native-navigation-contrast.json').write_text(json.dumps(result, indent=2))
    assert contrast >= 3, f'Navigation icon contrast below 3:1: {result}'
    return result

adb('shell', 'cmd', 'overlay', 'enable-exclusive', '--category',
    'com.android.internal.systemui.navbar.threebutton')
adb('shell', 'settings', 'put', 'secure', 'show_ime_with_hard_keyboard', '1')
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
deadline = time.monotonic() + 10
while time.monotonic() < deadline:
    if re.search(r'mInputShown=true|mIsInputViewShown=true', adb('shell', 'dumpsys', 'input_method')):
        break
    time.sleep(.3)
else:
    screenshot('41-native-ime-not-shown')
    raise RuntimeError('Native keyboard did not open')
screenshot('41-native-editor-ime')
adb('shell', 'input', 'keyevent', '4')
time.sleep(1)
tap(wait_for(lambda n: n.get('content-desc') in {'← ' + s for s in labels('saved')}))
next_screen = wait_for(lambda n: text_is(n, 'discard') or text_is(n, 'start'))
if text_is(next_screen, 'discard'):
    tap(next_screen)
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
contrast = navigation_contrast()
(out / '41-native-result.json').write_text(json.dumps({
    'history_loaded': True, 'workout_paused': True,
    'history_observation_seconds_including_ui_dump': round(history_seconds, 3),
    'input': 'adb system touch', 'instrumentation': False, 'navigation_contrast': contrast,
}, indent=2))
adb('shell', 'am', 'force-stop', 'ru.tabatabata')
print('Native history and workout reached through system input; screenshots saved')
