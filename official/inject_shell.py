#!/usr/bin/env python3
"""Register the native launcher on the official APK tree. No HTML."""
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

# Drop any leftover WebView assets from older builds.
www = ROOT / "assets" / "www"
if www.exists():
    shutil.rmtree(www)

if DEX and DEX.is_file():
    shutil.copy2(DEX, ROOT / "classes4.dex")

manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
needle = 'android:name="org.kenjinx.android.MainActivity"'
if needle not in text:
    raise SystemExit("official MainActivity missing")

# Official MainActivity stays and still plays the game, but is not the icon.
text = text.replace(
    """            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
""",
    "",
    1,
)

activity = """
        <provider android:authorities="dev.symbiosis.kenji.seed" android:exported="false" android:initOrder="2147483647" android:name="dev.symbiosis.kenji.SeedProvider"/>
        <activity android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode" android:exported="true" android:hardwareAccelerated="true" android:name="dev.symbiosis.kenji.LibraryActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
        <activity android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode" android:exported="false" android:hardwareAccelerated="true" android:name="dev.symbiosis.kenji.GamePropsActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"/>
        <activity android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode" android:exported="false" android:hardwareAccelerated="true" android:name="dev.symbiosis.kenji.SettingsActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar"/>
"""
idx = text.find(">", text.find("<application"))
if idx < 0:
    raise SystemExit("application tag")
text = text[: idx + 1] + activity + text[idx + 1 :]
manifest.write_text(text, encoding="utf-8")
print("injected native launcher +", "classes4.dex" if DEX and DEX.is_file() else "no dex")
