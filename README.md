# Kenji Symbiosis

Форк Android-оболочки Kenji-NX. Ядро то же (`libkenjinx.so`), оболочка другая.

## Зачем

Официальный Kenji на телефоне играет, но сам APK тяжёлый и сыпется:

* на заставке — `System.loadLibrary("kenjinxjni")` в `init` активити, до любой кнопки;
* в настройках — `SharedPreferences.edit { }` пишет через `apply()`, процесс падает — настройки не на диске;
* в лаунчере — `Enum.entries[prefs.getInt()]` без проверки, битые prefs = ArrayIndexOutOfBounds;
* `enableTraceLogs` читает ключ `enableStubLogs` (копипаста);
* `CrashHandler` пишет в `MainActivity.AppPath`, который на сплэше ещё пустой.

Здесь это починено. Лаунчер и настройки — PWA (HTML), как в Symbiosis. Ядро вшито в APK как libkenjinx.so.

## Что умеет

Тот же лаунчер, что у Symbiosis (карусель / список / настройки / плагины / пресеты).

* общая папка данных для прошивки и ключей — та же, что у официального Kenji или `/sdcard/Kenji`;
* папка игр через SAF, та же NSP-папка, что в Symbiosis;
* плагины (json/css/zip);
* пресеты настроек Kenji (`commit()` на диск);
* вылет в `:player` не закрывает лаунчер;
* пакет `dev.symbiosis.kenji` — можно держать рядом с официальным или удалить его.

## Сборка

GitHub Actions, `build.yml`, release. Ядро: [engine-kenji](https://github.com/sj0404-collab/eden-symbiosis/releases/tag/engine-kenji).
