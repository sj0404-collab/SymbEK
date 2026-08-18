# Kenji Space

Оригинальный **Kenji-NX 2.1.0-pr.2** (их дом, GameHost, DEX, `.so`).

Поверх — только Kotlin:

- `SeedProvider` до их `Application`: ключи, ярлыки прошивки, пресеты
- прошивка **не копируется**: `nand/*.nca` → `bis/.../{id}.nca/00` (symlink/hardlink)
- настройки пишутся в их QuickSettings (`resScale`, NCE, PPTC, DRAM…)

Пакет `dev.symbiosis.kenji`. Нет HTML, нет React, нет Java-шелла, нет WebView-UI.
