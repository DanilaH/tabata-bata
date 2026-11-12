"""Set the test display before boot, so launcher and insets use one profile."""
import os
from pathlib import Path

def configure(path, size, density):
    width, height = map(int, size.split('x'))
    density = int(density)
    assert 320 <= width <= 4096 and 320 <= height <= 4096 and 120 <= density <= 640
    values = {'hw.lcd.width': str(width), 'hw.lcd.height': str(height),
              'hw.lcd.density': str(density), 'skin.name': size}
    lines = []
    for line in path.read_text().splitlines():
        key = line.partition('=')[0].strip()
        if key == 'skin.path':
            continue
        if key in values:
            line = key + '=' + values.pop(key)
        lines.append(line)
    lines.extend(key + '=' + value for key, value in values.items())
    path.write_text('\n'.join(lines) + '\n')

if __name__ == '__main__':
    root = Path(os.environ.get('ANDROID_AVD_HOME', Path.home() / '.android' / 'avd'))
    path = root / 'tabata-ui.avd' / 'config.ini'
    configure(path, os.environ['UX_SIZE'], os.environ['UX_DENSITY'])
    print('Configured test display before boot:', os.environ['UX_SIZE'], os.environ['UX_DENSITY'])
