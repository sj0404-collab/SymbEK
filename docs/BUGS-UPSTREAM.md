# Что ломает официальный Kenji 2.1.0-pr.2

Прочитано в `libryujinx_bionic`, не угадано.

1. **Сплэш / лаунчер.** `MainActivity.init { System.loadLibrary("kenjinxjni") }`. Нет .so или оно не грузится — класс активити не создаётся, процесс мёртв до UI.
2. **Настройки не сохраняются.** `QuickSettings.save()` и `SettingsViewModel.save()` используют `sharedPref.edit { }` → `apply()`. Краш в следующие 100 мс — диск пустой. У нас `commit()`.
3. **Краш в настройках / при старте.** `MemoryManagerMode.entries[prefs.getInt(...)]` без границ. Сменился enum или битые prefs — `ArrayIndexOutOfBounds`. То же для VSync, DRAM, overlay. У нас `getOrElse(default)`.
4. **Trace-лог.** `enableTraceLogs = sharedPref.getBoolean("enableStubLogs", false)` — читает чужой ключ.
5. **CrashHandler.** Пишет в `MainActivity.AppPath`, на сплэше это `""`. Второй краш поверх первого. Не зовёт default handler — зомби. У нас `filesDir/logs` и проброс дальше.
6. **Вес.** Compose + JNA + 55 МБ ядра в APK. Здесь ядро скачивается, лаунчер — HTML.
