#!/usr/bin/env python3
"""Inject silent firmware SeedProvider. Official MainActivity stays the launcher."""
from __future__ import annotations

import pathlib
import shutil
import sys

ROOT = pathlib.Path(sys.argv[1])
DEX = None
for arg in sys.argv[2:]:
    p = pathlib.Path(arg)
    if p.suffix == ".dex" or p.name == "classes.dex":
        DEX = p

www = ROOT / "assets" / "www"
if www.exists():
    shutil.rmtree(www)

if DEX and DEX.is_file():
    shutil.copy2(DEX, ROOT / "classes4.dex")

manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
if 'android:name="org.kenjinx.android.MainActivity"' not in text:
    raise SystemExit("official MainActivity missing")

# Do not steal MAIN/LAUNCHER. Only register the seed provider.
if "dev.symbiosis.kenji.SeedProvider" not in text:
    provider = """
        <provider android:authorities="dev.symbiosis.kenji.seed" android:exported="false" android:initOrder="2147483647" android:name="dev.symbiosis.kenji.SeedProvider"/>
"""
    idx = text.find(">", text.find("<application"))
    if idx < 0:
        raise SystemExit("application tag")
    text = text[: idx + 1] + provider + text[idx + 1 :]
    manifest.write_text(text, encoding="utf-8")

print("injected SeedProvider +", "classes4.dex" if DEX and DEX.is_file() else "no dex")
