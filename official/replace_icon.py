#!/usr/bin/env python3
"""Force Kenji Space launcher icon without duplicate png/webp."""
from __future__ import annotations

import pathlib
import sys

from PIL import Image

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
SRC = pathlib.Path(sys.argv[2] if len(sys.argv) > 2 else pathlib.Path(__file__).with_name("icon_master.png"))
if not SRC.is_file():
    print("no icon", SRC)
    sys.exit(0)

master = Image.open(SRC).convert("RGBA")
bbox = master.getbbox()
if bbox:
    master = master.crop(bbox).resize((1024, 1024), Image.Resampling.LANCZOS)

sizes = {"ldpi": 36, "mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}

nodpi = ROOT / "res" / "drawable-nodpi"
nodpi.mkdir(parents=True, exist_ok=True)
master.save(nodpi / "space_mark.png", "PNG")

(ROOT / "res" / "drawable" / "ic_launcher_foreground.xml").write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<bitmap xmlns:android="http://schemas.android.com/apk/res/android"
    android:src="@drawable/space_mark"/>
""",
    encoding="utf-8",
)

(ROOT / "res" / "values" / "space_colors.xml").write_text(
    """<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="space_icon_bg">#FF121218</color>
</resources>
""",
    encoding="utf-8",
)

adaptive = """<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/space_icon_bg"/>
    <foreground android:drawable="@drawable/space_mark"/>
</adaptive-icon>
"""
n = 0
for folder in ROOT.glob("res/mipmap-anydpi*"):
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        p = folder / name
        if p.exists() or "anydpi" in folder.name:
            p.write_text(adaptive, encoding="utf-8")
            n += 1

for folder in ROOT.glob("res/mipmap-*"):
    if "anydpi" in folder.name:
        continue
    dens = folder.name.split("-", 1)[-1].split("-")[0]
    px = sizes.get(dens, 192)
    img = master.resize((px, px), Image.Resampling.LANCZOS)
    for dest in folder.glob("ic_launcher*"):
        if dest.suffix.lower() not in {".png", ".webp"}:
            continue
        if dest.suffix.lower() == ".webp":
            img.save(dest, "WEBP", quality=95)
        else:
            img.save(dest, "PNG")
        n += 1
print(f"icon applied, {n} files")
