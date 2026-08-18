# Kenji Space

Оригинальный **Kenji-NX 2.1.0-pr.2** (их дом, GameHost, DEX, `.so`).

Поверх — только Kotlin:

- одна прошивка **на своём месте** (Eden `nand/` / `bis/`) — ядро читает её через AppPath, без копии и без переезда
- в шапке и на Loading: вес распакованной прошивки (NCA · ГБ) и журнал запуска по шагам + строки ядра/JNI
- автопочинка деревьев (`stash`/`junk`/`00`), путей и прав: all-files + повторный takePersistableUriPermission на папки игр

Пакет `dev.symbiosis.kenji`. Нет HTML, нет React, нет Java-шелла, нет WebView-UI.
