"""Render store layouts around immutable app captures; never redraw the app UI."""
from pathlib import Path
import base64
import html
import json
import hashlib
import subprocess
from PIL import ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'store-assets/rustore-0.7.5'
SOURCE = ROOT / 'store-assets/rustore-0.7.3/screenshots'
FONT = '/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf'
CARDS = [
    ('01-work.png', ['Интервальный', 'таймер'], ['Крупные цифры, звуки и вибрация.'], '#3049C7', '#FAFAF5', '#FFFFFF', '#425BD2', 88),
    ('02-editor.png', ['Свои интервалы', 'работы и отдыха'], ['Подготовка, раунды, серии и заминка.'], '#F6F7FC', '#151B30', '#3049C7', '#E7EAF7', 78),
    ('03-ready-modes.png', ['Готовые', 'режимы'], ['Выбери подходящий и запускай.'], '#F6F7FC', '#151B30', '#3049C7', '#E7EAF7', 94),
    ('04-rest-dark.png', ['Управление', 'во время тренировки'], ['Пауза, переход между этапами', 'и +10 секунд отдыха.'], '#151B30', '#FAFAF5', '#BDF56D', '#202941', 72),
]


def uri(path):
    return 'data:image/png;base64,' + base64.b64encode(path.read_bytes()).decode()


def text(x, y, content, size, fill, weight=400, extra=''):
    return f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" {extra}>{html.escape(content)}</text>'


def render():
    (OUT / 'screenshots').mkdir(parents=True, exist_ok=True)
    manifest = []
    for i, (name, title, subtitle, bg, fg, accent, decor, font_size) in enumerate(CARDS, 1):
        for line in title:
            assert ImageFont.truetype(FONT, font_size).getlength(line) <= 920, line
        sx, sy, sw, sh = 200, 628, 680, 680 * 16 / 9
        parts = [f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1080" height="1920" viewBox="0 0 1080 1920">',
                 '<defs><clipPath id="screen"><rect x="200" y="628" width="680" height="1208.8889" rx="25"/></clipPath></defs>',
                 f'<rect width="1080" height="1920" fill="{bg}"/>',
                 f'<circle cx="1100" cy="1360" r="600" fill="none" stroke="{decor}" stroke-width="80"/>',
                 '<g font-family="DejaVu Sans,sans-serif">',
                 text(80, 90, 'ТАБАТА БАТА', 27, accent, 700, 'letter-spacing="3"'),
                 text(1000, 90, f'{i:02} / 04', 24, fg, 400, 'text-anchor="end" opacity=".7"'),
                 f'<path d="M80 126H1000" stroke="{fg}" stroke-opacity=".18"/>']
        for j, line in enumerate(title):
            parts.append(text(76, 258 + j * (font_size + 12), line, font_size, fg, 700, 'letter-spacing="-3"'))
        subtitle_y = 258 + (len(title)-1) * (font_size+12) + 78
        for j, line in enumerate(subtitle):
            parts.append(text(80, subtitle_y+j*46, line, 32, fg, 400, 'opacity=".86"'))
        # Native layout around the real PNG. The complete screen remains in its original aspect ratio.
        parts.extend([
            '<rect x="180" y="622" width="720" height="1240" rx="48" fill="#060B20" opacity=".08"/>',
            '<rect x="190" y="618" width="700" height="1229" rx="36" fill="#FFFFFF"/>',
            f'<image x="{sx}" y="{sy}" width="{sw}" height="{sh}" preserveAspectRatio="xMidYMid meet" clip-path="url(#screen)" xlink:href="{uri(SOURCE/name)}"/>',
            '</g></svg>'])
        svg = ''.join(parts)
        target = OUT / 'screenshots' / name
        subprocess.run(["inkscape", "--pipe", "--export-type=png", f"--export-filename={target}"], input=svg.encode(), check=True, capture_output=True)
        manifest.append({'card': name, 'source': str((SOURCE/name).relative_to(ROOT)),
                         'sourceSha256': hashlib.sha256((SOURCE/name).read_bytes()).hexdigest(),
                         'title': ' '.join(title), 'subtitle': ' '.join(subtitle)})
    (OUT/'source.json').write_text(json.dumps({'dimensions':[1080,1920], 'method':'Code-rendered SVG layouts with original app PNG inserts; no generated/redrawn UI', 'captureSourceCommit':'89ac2a31642df511ac728ea1fbcc2e666b46c240', 'cards':manifest},ensure_ascii=False,indent=2)+'\n')

if __name__ == '__main__':
    render()
