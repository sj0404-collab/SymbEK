# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** целиком (GameHost, три DEX, все 18 `.so`). Иконка открывает **нативную полку** (`LibraryActivity`): обложки, запуск нажатием, пресеты графики. Игра идёт в их `MainActivity`.

Нет HTML, нет React, нет WebView. UI — только Android View (`LibraryActivity`, `SettingsActivity`, `GamePropsActivity`).

Пакет `dev.symbiosis.kenji`. Рядом с `org.kenjinx.android` и Eden.

## Прошивка без копии

Перед стартом `SeedProvider` сканирует диск и **не копирует** NCA:

- `registered.stash` / `junk` → `registered`
- `keys/prod.keys` → `system/prod.keys` (ключи маленькие)
- Eden `nand/*.nca` → ярлык `bis/system/Contents/registered/{id}.nca/00`
- чужой Kenji `bis/{id}.nca/00` → тот же ярлык

Два APK читают одну прошивку. Откуда взяли — строка статуса, Настройки и `system/firmware_source.txt`.

Если ярлык в чужой `Android/data` не читается (SELinux), укажите общую папку в «Данные» (`/sdcard/Eden`, `/sdcard/Switch`).
