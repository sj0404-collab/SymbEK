#!/usr/bin/env python3
"""Inject shelf + silent firmware seed. Official GameHost still plays."""
from __future__ import annotations

import pathlib
import re
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

# One icon: our shelf. Official MainActivity stays startable by class name.
def _strip_launcher(block: str) -> str:
    return re.sub(
        r'\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*/>',
        "",
        block,
        count=1,
    )

main_pat = re.compile(
    r'<activity\b[^>]*org\.kenjinx\.android\.MainActivity[^>]*>.*?</activity>',
    re.S,
)
m = main_pat.search(text)
if m:
    text = text[: m.start()] + _strip_launcher(m.group(0)) + text[m.end() :]
else:
    text = re.sub(
        r'\s*<category android:name="android\.intent\.category\.LAUNCHER"\s*/>',
        "",
        text,
        count=1,
    )

if "dev.symbiosis.kenji.SeedProvider" not in text:
    provider = """
        <provider android:authorities="dev.symbiosis.kenji.seed" android:exported="false" android:initOrder="2147483647" android:name="dev.symbiosis.kenji.SeedProvider"/>
"""
    idx = text.find(">", text.find("<application"))
    if idx < 0:
        raise SystemExit("application tag")
    text = text[: idx + 1] + provider + text[idx + 1 :]

if "dev.symbiosis.kenji.LibraryActivity" not in text:
    activities = """
        <activity android:exported="true" android:name="dev.symbiosis.kenji.LibraryActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <activity android:exported="false" android:name="dev.symbiosis.kenji.SettingsActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"/>
        <activity android:exported="false" android:name="dev.symbiosis.kenji.GamePropsActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"/>
"""
    idx = text.rfind("</application>")
    if idx < 0:
        raise SystemExit("application close")
    text = text[:idx] + activities + text[idx:]

manifest.write_text(text, encoding="utf-8")
print("injected shelf + SeedProvider +", "classes4.dex" if DEX and DEX.is_file() else "no dex")
