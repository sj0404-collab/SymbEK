# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** целиком: три DEX, их дом, GameHost, EmulationService, все 18 `.so`. Ничего из оригинала не вырезается и своим лаунчером не подменяется.

Пакет `dev.symbiosis.kenji` — ставится **рядом** с их `org.kenjinx.android`. Подпись постоянная, `versionCode` растёт.

Это тот же путь, что в рабочей **1.0.25**: иконка открывает их интерфейс, игра идёт через их `MainActivity`.

## Сборка

`.github/workflows/build.yml` скачивает официальный APK 49840045 байт, меняет package на `dev.symbiosis.kenji` через apktool (DEX и `.so` не трогает) и подписывает постоянным ключом.
