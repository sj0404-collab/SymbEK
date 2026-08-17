# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** целиком: три DEX (Compose UI, GameHost, EmulationService), все 18 `.so` включая `libkenjinx.so`. Ничего из оригинала не вырезается.

Пакет `dev.symbiosis.kenji` — ставится **рядом** с их `org.kenjinx.android`. Подпись та же, что у предыдущих сборок Kenji Space, `versionCode` растёт.

## Что внутри

- Нативный лаунчер (без HTML/WebView): сетка игр, обложки, свойства.
- Игра открывается официальным `MainActivity` через extra `bootPath` — тот же путь, которым их ярлыки запускают ROM.
- Те же нативы: `libkenjinx.so` SHA256 `d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590`.
- Размер APK ~48 МБ, как у оригинала. После установки Android разворачивает OAT — ожидайте сотни мегабайт, как у их Kenji.

## Данные

Ключи и прошивка в APK не входят. Укажите папку игр («Папка») и при необходимости корень Eden («Данные»). Автомост кладёт `system/prod.keys` и `bis/system/Contents/registered/{id}.nca/00` туда, куда смотрит официальный GameHost.

## Сборка

`.github/workflows/build.yml` скачивает официальный APK 49840045 байт, переклеивает package на `dev.symbiosis.kenji` через apktool (DEX и `.so` не трогает), вшивает нативную оболочку как `classes4.dex` и подписывает постоянным ключом.
