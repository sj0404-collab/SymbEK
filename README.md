# Kenji Space

Форк Android-оболочки Kenji-NX. Нативы — те же, что в официальном `kenji-nx-v2.1.0-pr.2-standard.apk`. Оболочка другая (PWA как у Symbiosis).

## Ядро

Не «похожее», а файлы из релиза 2.1.0-pr.2:

* `libkenjinx.so` 45.8 МБ (SHA `d7810486…`)
* официальный `libkenjinxjni.so` (adrenotools)
* OpenAL, FFmpeg (`avcodec`/`avutil`/`swscale`/`swresample`), OpenSSL, SDL2, Skia, хуки драйвера

Старый дамп `engine-kenji` (55 МБ, другой BuildID) больше не используется.

## Зачем другая оболочка

Официальный Kenji на телефоне играет, но сам APK сыпется:

* на заставке — `System.loadLibrary("kenjinxjni")` в `init` активити, до любой кнопки;
* в настройках — `SharedPreferences.edit { }` пишет через `apply()`, процесс падает — настройки не на диске;
* в лаунчере — `Enum.entries[prefs.getInt()]` без проверки, битые prefs = ArrayIndexOutOfBounds;
* `enableTraceLogs` читает ключ `enableStubLogs`;
* `CrashHandler` пишет в `MainActivity.AppPath`, который на сплэше ещё пустой.

Здесь это починено. Лаунчер — HTML. Ядро вшито.

Почему после установки официальный ~250 МБ, а мы легче: у них 54 МБ Compose DEX, который ART разворачивает в OAT. Это UI, не эмулятор. Нативы теперь те же.

## Что умеет

Тот же лаунчер, что у Symbiosis (карусель / список / настройки / плагины / пресеты).

* мост прошивки Eden → Kenji: те же NCA из `nand/.../registered` раскладываются в `bis/.../registered/{id}.nca/00` (ссылка или копия), ядро не меняется;
* общая папка данных для прошивки и ключей — та же, что у официального Kenji, Eden или `/sdcard/Kenji`;
* папка игр через SAF, та же NSP-папка, что в Symbiosis;
* плагины (json/css/zip);
* пресеты настроек Kenji (`commit()` на диск);
* вылет в `:player` не закрывает лаунчер;
* пакет `dev.symbiosis.kenji` — можно держать рядом с официальным или удалить его.

## Сборка

GitHub Actions, `build.yml`, push в `main` собирает release. Нативы: [natives-2.1.0-pr.2](https://github.com/sj0404-collab/kenji-symbiosis/releases/tag/natives-2.1.0-pr.2).
