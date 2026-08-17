# Kenji Space

Официальный Kenji-NX **2.1.0-pr.2** целиком: их дом, GameHost, три DEX, все 18 `.so`. Иконка открывает их интерфейс — как в рабочей 1.0.25.

Пакет `dev.symbiosis.kenji`. Рядом с `org.kenjinx.android`.

Перед стартом их процесса тихо чинится прошивка (ошибка в их код не уходит):

- `registered.stash` / `junk` → `registered`
- `keys/prod.keys` → `system/prod.keys`
- Eden `nand/*.nca` → `bis/system/Contents/registered/{id}.nca/00`

В их настройках подписи про firmware/keys уточнены: Kenji читает `bis/`, не Eden `nand/`.
