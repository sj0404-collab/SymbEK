#!/usr/bin/env python3
import pathlib
import sys

p = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else "/tmp/official-apktool/AndroidManifest.xml")
t = p.read_text(encoding="utf-8")
idx = t.find("dev.symbiosis.kenji.HomeActivity")
if idx < 0 or "android.intent.category.LAUNCHER" not in t[idx : idx + 900]:
    raise SystemExit("HomeActivity is not the launcher")
m = t.find("org.kenjinx.android.MainActivity")
if m < 0:
    raise SystemExit("MainActivity missing")
if "LAUNCHER" in t[m : m + 2500]:
    raise SystemExit("MainActivity still has LAUNCHER")
print("launcher ok")
