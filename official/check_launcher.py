#!/usr/bin/env python3
import pathlib
import sys

p = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/official-apktool/AndroidManifest.xml")
t = p.read_text(encoding="utf-8")
if "dev.symbiosis.kenji.HomeActivity" in t:
    raise SystemExit("HomeActivity must not be in 1.0.75-based build")
m = t.find('android:name="org.kenjinx.android.MainActivity"')
if m < 0:
    raise SystemExit("MainActivity missing")
if "android.intent.category.LAUNCHER" not in t[m : m + 1800]:
    raise SystemExit("MainActivity is not the launcher")
print("launcher ok — 1.0.75 Kenji home")
