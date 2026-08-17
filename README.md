# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** целиком: три DEX (Compose UI, GameHost, EmulationService), все 18 `.so` включая `libkenjinx.so`. Ничего из оригинала не вырезается.

Пакет `dev.symbiosis.kenji` — ставится **рядом** с их `org.kenjinx.android`. Подпись та же, что у предыдущих сборок Kenji Space, `versionCode` растёт.

## Что внутри

- Тот же player, что уже запускает Blade Chimera на itel S666LN в официальном приложении: `MainActivity` + `GameHost` + `MainViewModel.loadGame`.
- Те же нативы: `libkenjinx.so` SHA256 `d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590`.
- Размер APK ~48 МБ, как у оригинала. После установки Android разворачивает OAT — ожидайте сотни мегабайт, как у их Kenji.

## Данные

Ключи и прошивка в APK не входят. В приложении укажите папку игр и поставьте прошивку так же, как в официальном Kenji. Общий дамп: `system/prod.keys` и `bis/system/Contents/registered/{id}.nca/00`.

Поверх оригинала — React-библиотека (`web/`): список игр в WebView, запуск идёт в официальный `MainActivity` / `LAUNCH_GAME`. Ядро и DEX Kenji не вырезаются.

## Сборка

`.github/workflows/build.yml` скачивает официальный APK 49840045 байт, переклеивает package на `dev.symbiosis.kenji` через apktool (DEX и `.so` не трогает) и подписывает постоянным ключом.
