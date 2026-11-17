"""Compose accurate RuStore cards around unchanged Android 0.11.4 captures.

Requires Python Pillow (font measurement/verification) and Inkscape (SVG export).
The original PNG pixels are embedded, never repainted or stretched. Explicit
viewBox crops omit only Android chrome and, on the modes card, the ad region.
"""
from pathlib import Path
import base64
import hashlib
import html
import json
import shutil
import subprocess
from PIL import Image, ImageFont

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "store-assets/rustore-0.11.4"
FONT = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
COMMIT = "6ba22f03e50ae6063f713841878d254c35b06d35"
RUN = 43266280411
ARTIFACT = 13967411191
CARDS = [
    dict(name="01-timer", source="29-feel-running.png", title=["Интервальный", "таймер"],
         bg="#073DFC", fg="#FFFFFF", accent="#9BFF5C", crop=[0,72,1080,1860]),
    dict(name="02-intervals", source="05-editor-top.png", title=["Свои", "интервалы"],
         bg="#F3F5FB", fg="#151B30", accent="#3049C7", crop=[0,72,1080,1860]),
    dict(name="03-modes", source="40-home-everyday.png", title=["Любимые", "режимы"],
         bg="#E9EDFF", fg="#151B30", accent="#3049C7", crop=[0,72,1080,1550], width=920),
    dict(name="04-plan", source="09-editor-preview.png", title=["План занятия", "до старта"],
         bg="#111A32", fg="#FFFFFF", accent="#9BFF5C", crop=[0,72,1080,1860]),
    dict(name="05-history", source="12-history-week.png", title=["История", "тренировок"],
         bg="#F3F5FB", fg="#151B30", accent="#3049C7", crop=[0,72,1080,1860]),
]

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def text(x,y,value,size,fill,weight=400,extra=""):
    return f'<text x="{x}" y="{y}" font-size="{size}" font-weight="{weight}" fill="{fill}" {extra}>{html.escape(value)}</text>'

def render():
    (OUT/"phone").mkdir(parents=True,exist_ok=True)
    (OUT/"source/svg").mkdir(parents=True,exist_ok=True)
    icon=ROOT/"app/src/main/res/drawable-nodpi/ic_timer.png"
    assert Image.open(icon).size==(512,512)
    assert Image.open(icon).mode=="RGB", "Icon must be opaque"
    shutil.copyfile(icon,OUT/"icon-512.png")
    manifest={"version":"0.11.4", "production_source_commit":COMMIT,
              "capture_workflow_run":RUN, "capture_artifact_id":ARTIFACT,
              "capture_profile":"ru-normal / API35 / font 100%",
              "dimensions":[1080,1920],"icon_sha256":sha(icon),
              "method":"SVG composition around original PNGs; uniform scaling and explicit crops only. No redrawn UI.",
              "cards":[]}
    for i,c in enumerate(CARDS,1):
        source=OUT/"source/captures"/c["source"]
        assert Image.open(source).size==(1080,1920)
        title_size=120
        while max(ImageFont.truetype(FONT,title_size).getlength(line) for line in c["title"])>936:
            title_size-=2
        font=ImageFont.truetype(FONT,title_size)
        for line in c["title"]:
            assert font.getlength(line)<=936, line
        left,top,right,bottom=c["crop"]
        width=c.get("width",800)
        height=width*(bottom-top)/(right-left)
        if height>1320:
            width*=1320/height; height=1320
        x=(1080-width)/2
        y=1840-height
        data=base64.b64encode(source.read_bytes()).decode()
        bg,fg,accent=c["bg"],c["fg"],c["accent"]
        svg=[f'<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="1080" height="1920" viewBox="0 0 1080 1920">',
             f'<rect width="1080" height="1920" fill="{bg}"/>',
             f'<circle cx="1100" cy="1510" r="610" fill="none" stroke="{accent}" stroke-opacity=".065" stroke-width="110"/>',
             '<g font-family="DejaVu Sans,sans-serif">']
        for j,line in enumerate(c["title"]):
            svg.append(text(68,220+j*140,line,title_size,fg,700,'letter-spacing="-2"'))
        # App viewport is separate from the design; clipping never covers app controls.
        svg += [f'<rect x="{x-14}" y="{y-8}" width="{width+28}" height="{height+28}" rx="35" fill="#030819" opacity=".12"/>',
                f'<rect x="{x-6}" y="{y-6}" width="{width+12}" height="{height+12}" rx="29" fill="#FFFFFF" fill-opacity=".8"/>',
                f'<defs><clipPath id="viewport"><rect x="{x}" y="{y}" width="{width}" height="{height}" rx="24"/></clipPath></defs>',
                f'<g clip-path="url(#viewport)"><svg x="{x}" y="{y}" width="{width}" height="{height}" viewBox="{left} {top} {right-left} {bottom-top}">',
                f'<image width="1080" height="1920" xlink:href="data:image/png;base64,{data}"/></svg></g>']
        svg.append('</g></svg>')
        svgpath=OUT/"source/svg"/(c["name"]+".svg")
        svgpath.write_text("".join(svg))
        target=OUT/"phone"/(c["name"]+".png")
        subprocess.run(["inkscape",str(svgpath),"--export-type=png",f"--export-filename={target}"],check=True,capture_output=True)
        image=Image.open(target)
        assert image.size==(1080,1920)
        assert target.stat().st_size<3_000_000
        manifest["cards"].append({**c,"title_font_px":title_size,"source_sha256":sha(source),"output_sha256":sha(target),"output_bytes":target.stat().st_size,
                                  "viewport":[x,y,width,height]})
    (OUT/"source.json").write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+"\n")
    print(json.dumps({"cards":len(CARDS),"output":str(OUT),"max_bytes":max(c["output_bytes"] for c in manifest["cards"])},ensure_ascii=False))

if __name__=="__main__":
    render()
