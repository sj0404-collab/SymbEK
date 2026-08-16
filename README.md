# Kenji Symbiosis

Форк Android-оболочки Kenji-NX. Ядро то же (`libkenjinx.so`), оболочка другая.

## Зачем

Официальный Kenji на телефоне играет, но сам APK тяжёлый и сыпется:

* на заставке — `System.loadLibrary("kenjinxjni")` в `init` активити, до любой кнопки;
* в настройках — `SharedPreferences.edit { }` пишет через `apply()`, процесс падает — настройки не на диске;
* в лаунчере — `Enum.entries[prefs.getInt()]` без проверки, битые prefs = ArrayIndexOutOfBounds;
* `enableTraceLogs` читает ключ `enableStubLogs` (копипаста);
* `CrashHandler` пишет в `MainActivity.AppPath`, который на сплэше ещё пустой.

Здесь это починено. Лаунчер и настройки — PWA (HTML), как в Symbiosis, но сетка и цвета Kenji. Ядро скачивается (~55 МБ), в APK его нет.

## Что умеет

* папка игр через SAF, не теряется;
* плагины (json/css/zip) без пересборки;
* настройки пишутся `commit()`, не `apply()`;
* вылет в `:player` не закрывает лаунчер;
* логотип свой, пакет `dev.symbiosis.kenji` — ставится рядом с официальным Kenji.

## Сборка

GitHub Actions, `build.yml`, release. Ядро: [engine-kenji](https://github.com/sj0404-collab/eden-symbiosis/releases/tag/engine-kenji).
