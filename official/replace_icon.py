#!/usr/bin/env python3
"""Swap official Kenji launcher icons for Kenji Space."""
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
sizes = {
    "ldpi": 36,
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192,
}
n = 0
for folder in list(ROOT.glob("res/mipmap-*")) + list(ROOT.glob("res/drawable-*")) + list(ROOT.glob("res/mipmap")):
    name = folder.name
    dens = name.split("-", 1)[-1].split("-")[0] if "-" in name else "mdpi"
    px = sizes.get(dens, 192)
    if "anydpi" in name:
        continue
    for pat in ("ic_launcher.png", "ic_launcher_round.png", "ic_launcher_foreground.png",
                "ic_launcher.webp", "ic_launcher_round.webp", "ic_launcher_foreground.webp"):
        dest = folder / pat
        if not dest.exists():
            continue
        img = master.resize((px, px), Image.Resampling.LANCZOS)
        if dest.suffix.lower() == ".webp":
            img.save(dest, "WEBP", quality=95)
        else:
            img.save(dest, "PNG")
        n += 1
print(f"replaced {n} launcher icons from {SRC.name}")
