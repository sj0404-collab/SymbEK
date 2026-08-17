# Kenji Space

Форк Android-оболочки Kenji-NX с отдельным player-процессом. Нативное ядро не заменяется самодельным эмулятором: в release вшивается официальный набор Kenji-NX 2.1.0-pr.2, а оболочка и lifecycle запуска переписаны.

## Что исправлено в player

- `deviceInitialize` выполняется на главном Android-потоке, как требует официальный core.
- `inputInitialize` выполняется после создания контекста и загрузки игры.
- отдельный поток регулярно вызывает `inputUpdate`, поэтому виртуальные кнопки, touch и физический контроллер доходят до HID игры;
- экранное управление содержит A/B/X/Y, D-pad, L/R, ZL/ZR, L3/R3 и меню;
- реальные акселерометр и гироскоп телефона передаются в core;
- Vulkan renderer берёт `ANativeWindow` с `SurfaceView` (как официальный Kenji). Указатель не отбрасывается из‑за знакового `Long` (PAC/MTE на arm64 даёт «отрицательные» адреса);
- shader/PPTC progress приходит из callback официального core и отображается в HUD;
- HUD показывает настоящий путь данных, размер `system/prod.keys`, количество NCA прошивки, имя/версию Vulkan-драйвера, версию прошивки и FPS/frame time/FIFO;
- выход вызывает полный `deviceCloseEmulation`, поэтому следующая игра не наследует старый renderer;
- native player остаётся в процессе `:player`, чтобы его падение не закрывало лаунчер.

## Ключи и прошивка

Ключи и прошивка намеренно **не входят в APK**. Это должны быть пользовательские дампы:

- `system/prod.keys` — обязательный файл;
- прошивка Kenji: `bis/system/Contents/registered/{id}.nca/00`;
- Eden/Yuzu: `nand/system/Contents/registered/*.nca`.

Kenji Space показывает реальные пути и размеры в player HUD. Если ключей или прошивки нет, вместо чёрного экрана показывается конкретная причина. Мост Eden создаёт ссылки, а если Android запрещает hardlink между приложениями — атомарно копирует NCA в Kenji layout.

## Игры

Через SAF добавляются обычные `nsp`, `xci` и `nro`. `nsz/xcz` не выдаются за запускаемые: полноценного декомпрессора в этом APK нет, поэтому они остаются в отдельной папке конвертера и не вызывают crash при попытке запуска.

## Нативы

Release workflow получает официальный набор из тега [`natives-2.1.0-pr.2`](https://github.com/sj0404-collab/kenji-symbiosis/releases/tag/natives-2.1.0-pr.2) и проверяет SHA256 `libkenjinx.so`:

```text
d781048671e4cef1cde2ec15db8fe29b949df9949d83569ceaf776ad12901590
```

В git намеренно нет `jniLibs`: сборка выполняется в GitHub Actions и перед Android Gradle Build получает проверенные `.so`. Локальный clone без этого asset не является готовым APK.

## Сборка только в GitHub Actions

Workflow `.github/workflows/build.yml`:

1. устанавливает Java 17, NDK 26.1 и CMake 3.22;
2. скачивает и проверяет официальный native asset;
3. собирает реальный `libsymbiosis_kenji.so` с Vulkan-driver probe;
4. собирает APK;
5. проверяет, что APK содержит `libkenjinx.so`, `libkenjinxjni.so`, `libsymbiosis_kenji.so` и `kenji.html`;
6. release подписывается постоянным ключом репозитория.

Каждый push в `main` собирает signed release с одним постоянным ключом и автоматически увеличивает `versionCode`/`versionName`. Для этого в настройках репозитория нужны secrets `KEYSTORE_B64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` и `KEY_PASSWORD`. Debug можно запустить вручную через workflow с `build_type=debug`.
