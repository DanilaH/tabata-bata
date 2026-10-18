"""Install real 0.5.1 code, seed via its Store, update without clearing app data."""
import os
from pathlib import Path
import re
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = 'ru.tabatabata'
OUT = Path('app/build/reports/upgrade-check')
OUT.mkdir(parents=True, exist_ok=True)
OLD = 'baseline/app/build/outputs/apk/debug/app-debug.apk'
OLD_TEST = 'baseline/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
NEW = 'app/build/outputs/apk/debug/app-debug.apk'
NEW_TEST = 'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'


def adb(*args, binary=False):
    return subprocess.check_output(['adb', *args], text=not binary, timeout=90)


def instrument(phase):
    output = adb('shell', 'am', 'instrument', '-w', '-e', 'class',
                 'ru.tabatabata.UpgradeStateTest', '-e', 'phase', phase,
                 PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner')
    (OUT / (phase + '.txt')).write_text(output)
    assert 'OK (1 test)' in output, output


def seed():
    subprocess.run(['adb', 'uninstall', PACKAGE], capture_output=True)
    subprocess.run(['adb', 'uninstall', PACKAGE + '.test'], capture_output=True)
    adb('install', OLD)
    adb('install', OLD_TEST)
    instrument('seed')
    adb('shell', 'am', 'force-stop', PACKAGE)


def private_file(path):
    return adb('exec-out', 'run-as', PACKAGE, 'cat', path, binary=True)


def service_running():
    dump = adb('shell', 'dumpsys', 'activity', 'services', PACKAGE)
    return 'ru.tabatabata/.TimerService' in dump and 'isForeground=true' in dump


def visible(*texts):
    adb('shell', 'uiautomator', 'dump', '/sdcard/upgrade.xml')
    xml = adb('exec-out', 'cat', '/sdcard/upgrade.xml')
    (OUT / 'last-ui.xml').write_text(xml)
    return next((n for n in ET.fromstring(xml).iter('node') if n.get('text') in texts), None)


def wait_node(*texts):
    deadline = time.monotonic() + 30
    while time.monotonic() < deadline:
        node = visible(*texts)
        if node is not None:
            return node
        time.sleep(.5)
    raise AssertionError('Missing UI: ' + str(texts))


def tap(node):
    a, b, c, d = map(int, re.findall(r'\d+', node.get('bounds')))
    adb('shell', 'input', 'tap', str((a+c)//2), str((b+d)//2))


seed()
paths = ['files/datastore/tabata.preferences_pb', 'shared_prefs/session.xml']
before = {p: private_file(p) for p in paths}
adb('install', '-r', NEW)
for p in paths:
    assert private_file(p) == before[p], 'Update changed stored bytes: ' + p
adb('install', '-r', NEW_TEST)
instrument('verify')

# Repeat from 0.5.1 directly to the minified release, with the same CI signing key.
seed()
adb('install', '-r', os.environ['UPGRADE_RELEASE_APK'])
adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
adb('shell', 'wm', 'dismiss-keyguard')
adb('shell', 'am', 'start', '-W', '-n', PACKAGE + '/.MainActivity')
wait_node('Workout interrupted', 'Тренировка была прервана')
assert not service_running(), 'Update must not automatically restart workout'
(OUT / 'release-recovery.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
tap(wait_node('Discard', 'Не продолжать'))
wait_node('Upgrade 37-13')
wait_node('Второй режим')
(OUT / 'release-presets.png').write_bytes(adb('exec-out', 'screencap', '-p', binary=True))
adb('shell', 'am', 'force-stop', PACKAGE)
(OUT / 'result.txt').write_text('PASS: 0.5.1 -> current debug preserves bytes, preset IDs/order/parameters, options, last plan/preset and checkpoint; 0.5.1 -> minified release shows manual recovery and both saved presets. Temporary CI key; no real SDK IDs.\n')
print((OUT / 'result.txt').read_text())
