#!/usr/bin/env python3
"""Copy React assets and register the WebView launcher on the official APK tree."""
from __future__ import annotations

import pathlib
import shutil
import sys

ROOT = pathlib.Path(sys.argv[1])
WWW = pathlib.Path(sys.argv[2])
DEX = pathlib.Path(sys.argv[3]) if len(sys.argv) > 3 else None

dest = ROOT / "assets" / "www"
if dest.exists():
    shutil.rmtree(dest)
shutil.copytree(WWW, dest)

if DEX and DEX.is_file():
    shutil.copy2(DEX, ROOT / "classes4.dex")

manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
needle = 'android:name="org.kenjinx.android.MainActivity"'
if needle not in text:
    raise SystemExit("official MainActivity missing")

# Official MainActivity stays, but is no longer the launcher.
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
        <activity android:configChanges="orientation|screenSize|keyboardHidden|screenLayout|uiMode" android:exported="true" android:hardwareAccelerated="true" android:name="dev.symbiosis.kenji.LibraryActivity" android:theme="@android:style/Theme.DeviceDefault.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
        </activity>
"""
text = text.replace("<application", "<application", 1)
# insert activity just inside <application ...>
idx = text.find(">", text.find("<application"))
if idx < 0:
    raise SystemExit("application tag")
text = text[: idx + 1] + activity + text[idx + 1 :]
manifest.write_text(text, encoding="utf-8")
print("injected React launcher +", "classes4.dex" if DEX and DEX.is_file() else "no dex")
