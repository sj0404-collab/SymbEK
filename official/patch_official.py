#!/usr/bin/env python3
"""Retarget official Kenji-NX 2.1.0-pr.2 to Kenji Space without touching DEX."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
PKG = "dev.symbiosis.kenji"
VERSION_CODE = sys.argv[2] if len(sys.argv) > 2 else "1024"
VERSION_NAME = sys.argv[3] if len(sys.argv) > 3 else "1.0.24"


def replace(path: pathlib.Path, old: str, new: str) -> None:
    text = path.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"{path}: missing {old!r}")
    path.write_text(text.replace(old, new), encoding="utf-8")


manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
text = text.replace(
    'package="org.kenjinx.android"',
    f'package="{PKG}"',
    1,
)
# Unique authorities so this installs beside official org.kenjinx.android.
text = text.replace(
    'android:authorities="org.kenjinx.android.fileprovider"',
    f'android:authorities="{PKG}.fileprovider"',
)
text = text.replace(
    'android:authorities="org.kenjinx.android.providers"',
    f'android:authorities="{PKG}.providers"',
)
text = text.replace(
    'android:authorities="org.kenjinx.android.androidx-startup"',
    f'android:authorities="{PKG}.androidx-startup"',
)
text = text.replace(
    "org.kenjinx.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
    f"{PKG}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
)
# Keep querying the real official app.
if f'<package android:name="{PKG}"/>' in text:
    text = text.replace(
        f'<package android:name="{PKG}"/>',
        '<package android:name="org.kenjinx.android"/>',
        1,
    )
manifest.write_text(text, encoding="utf-8")

yml = ROOT / "apktool.yml"
y = yml.read_text(encoding="utf-8")
y = re.sub(r"renameManifestPackage:.*", f"renameManifestPackage: {PKG}", y)
y = re.sub(r"versionCode:.*", f"versionCode: {VERSION_CODE}", y)
y = re.sub(r"versionName:.*", f"versionName: {VERSION_NAME}", y)
yml.write_text(y, encoding="utf-8")

strings = ROOT / "res/values/strings.xml"
s = strings.read_text(encoding="utf-8")
s = s.replace(">Kenji-NX<", ">Kenji Space<", 1)
s = s.replace(">Kenji-NX Optimized<", ">Kenji Space<", 1)
# Clarify firmware in official settings copy (string bodies only, no DEX).
replacements = [
    (
        "Install Firmware",
        "Install Firmware (Kenji: bis/.../registered/{id}.nca/00 — not Eden nand/*.nca)",
    ),
    (
        "Install firmware",
        "Install firmware — Kenji reads bis/system/Contents/registered/{id}.nca/00",
    ),
    (
        "No firmware installed",
        "No firmware in bis/. Eden nand/*.nca is not enough. Use Kenji Space «Мост прошивки» or restore registered.stash → registered.",
    ),
    (
        "Firmware",
        "Firmware (bis/{id}.nca/00)",
    ),
    (
        "prod.keys",
        "prod.keys (Kenji: system/prod.keys only, not keys/)",
    ),
]
for old, new in replacements:
    # only replace standalone UI phrases, once each
    s = s.replace(f">{old}<", f">{new}<", 1)
# Always append a visible hint used if a matching name exists later
if "kenji_space_fw_hint" not in s:
    s = s.replace(
        "</resources>",
        '    <string name="kenji_space_fw_hint">Kenji не видит Eden nand/*.nca. Нужны system/prod.keys и bis/system/Contents/registered/{id}.nca/00. Если есть registered.stash — переименуйте в registered. Вечный Loading = нет прошивки в bis/.</string>\n</resources>',
        1,
    )
strings.write_text(s, encoding="utf-8")

print(f"patched {ROOT} → {PKG} {VERSION_NAME} ({VERSION_CODE})")
