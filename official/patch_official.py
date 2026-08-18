#!/usr/bin/env python3
"""Retarget official Kenji-NX 2.1.0-pr.2 to Kenji Space without touching DEX."""
from __future__ import annotations

import pathlib
import re
import sys

ROOT = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
PKG = "dev.symbiosis.kenji"
VERSION_CODE = sys.argv[2] if len(sys.argv) > 2 else "1036"
VERSION_NAME = sys.argv[3] if len(sys.argv) > 3 else "1.0.36"


manifest = ROOT / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")
text = text.replace(
    'package="org.kenjinx.android"',
    f'package="{PKG}"',
    1,
)
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
replacements = [
    (
        "Install Firmware",
        "Install Firmware (optional — Space already points bis/ at Eden nand via shortcuts)",
    ),
    (
        "Install firmware",
        "Install firmware — or let Space symlink nand/*.nca → bis/{id}.nca/00",
    ),
    (
        "No firmware installed",
        "No readable firmware in bis/. Space looks at Eden nand and official Kenji bis; it does not copy.",
    ),
    (
        "Firmware",
        "Firmware (bis/{id}.nca/00 = ярлык на nand, без копии)",
    ),
    (
        "prod.keys",
        "prod.keys (Kenji: system/prod.keys only, not keys/)",
    ),
]
for old, new in replacements:
    s = s.replace(f">{old}<", f">{new}<", 1)
if "kenji_space_fw_hint" not in s:
    s = s.replace(
        "</resources>",
        '    <string name="kenji_space_fw_hint">Прошивка не копируется. Space сканирует Eden nand/ и чужой Kenji bis/, затем кладёт ярлыки {id}.nca/00 → тот же файл. Путь источника: system/firmware_source.txt. Вечный Loading = ярлыки не читаются (нет доступа к Android/data) — укажите общую папку в «Данные».</string>\n</resources>',
        1,
    )
strings.write_text(s, encoding="utf-8")

print(f"patched {ROOT} → {PKG} {VERSION_NAME} ({VERSION_CODE})")
