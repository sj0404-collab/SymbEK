# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** только как **GameHost** (DEX + `.so`). Иконка открывает нативную полку: обложки, запуск нажатием, пресеты. Их Compose-дом больше не открывается — это и был вечный Loading без прошивки.

Нет HTML, нет React, нет WebView, нет старого Gradle-модуля `app/`.

Пакет `dev.symbiosis.kenji`.

## Прошивка без копии

`SeedProvider` и кнопка «Данные» сканируют диск и **не копируют** NCA:

- `prod.keys` / `keys/prod.keys` → `system/prod.keys`
- Eden `nand/*.nca` → ярлык `bis/.../registered/{id}.nca/00`
- чужой Kenji `bis/` → тот же ярлык

Если статус «нет ключей / нет прошивки» — нажмите розовый баннер или «Данные» и укажите папку Eden/Kenji, где лежат `prod.keys` и `nand/` или `bis/`.
