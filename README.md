# Kenji Space

Оригинальный **Kenji-NX 2.1.0-pr.2** (их дом, GameHost, DEX, `.so`).

Поверх — только Kotlin:

- панель на их MainActivity: **Eden/files**, **Kenji** (куда идут ярлыки), **Сохранить**, вкладка **Пресеты**
- после сохранения оверлей: ключи, число NCA, источник; под ним их сетка игр
- прошивка **не копируется**: `nand/*.nca` → `Kenji/bis/.../{id}.nca/00`

Пакет `dev.symbiosis.kenji`. Нет HTML, нет React, нет Java-шелла, нет WebView-UI.
