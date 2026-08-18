#!/usr/bin/env python3
"""Inject Kotlin seed only. Official MainActivity stays the launcher."""
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

for folder in (ROOT / "assets" / "www", ROOT / "assets" / "web", ROOT / "assets" / "react"):
    if folder.exists():
        shutil.rmtree(folder)
for p in ROOT.rglob("*"):
    if p.is_file() and p.suffix.lower() in {".html", ".htm", ".jsx", ".tsx", ".vue"}:
        p.unlink()

if DEX and DEX.is_file():
    shutil.copy2(DEX, ROOT / "classes4.dex")

manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
if 'android:name="org.kenjinx.android.MainActivity"' not in text:
    raise SystemExit("official MainActivity missing")

for perm in (
    "android.permission.MANAGE_EXTERNAL_STORAGE",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
):
    needle = f'android:name="{perm}"'
    if needle not in text:
        text = text.replace(
            "<application",
            f'    <uses-permission android:name="{perm}" android:maxSdkVersion="32"/>\n    <application'
            if "MANAGE" not in perm
            else f'    <uses-permission android:name="{perm}"/>\n    <application',
            1,
        )

if "dev.symbiosis.kenji.SeedProvider" not in text:
    provider = """
        <provider android:authorities="dev.symbiosis.kenji.seed" android:exported="false" android:initOrder="2147483647" android:name="dev.symbiosis.kenji.SeedProvider"/>
"""
    idx = text.find(">", text.find("<application"))
    if idx < 0:
        raise SystemExit("application tag")
    text = text[: idx + 1] + provider + text[idx + 1 :]

if "dev.symbiosis.kenji.PickActivity" not in text:
    act = """
        <activity android:exported="false" android:name="dev.symbiosis.kenji.PickActivity" android:theme="@android:style/Theme.Translucent.NoTitleBar"/>
"""
    idx = text.rfind("</application>")
    if idx < 0:
        raise SystemExit("application close")
    text = text[:idx] + act + text[idx:]

manifest.write_text(text, encoding="utf-8")

print("injected Kotlin SeedProvider +", "classes4.dex" if DEX and DEX.is_file() else "no dex")
