# Что ломало официальный Kenji 2.1.0-pr.2 и что делает Kenji Space

Сравнение сделано с официальной Android-веткой `libryujinx_bionic` и native release 2.1.0-pr.2.

1. **Сплэш / лаунчер.** Официальный `MainActivity.init` сразу вызывает `System.loadLibrary("kenjinxjni")`. В Kenji Space native core загружается только в отдельном `:player` процессе.
2. **Настройки не сохранялись.** В официальном UI `sharedPref.edit { }` использует `apply()`. Kenji Space пишет через `commit()` и безопасно читает повреждённые типы/ordinal.
3. **Краш из-за битых enum prefs.** Официальный UI индексирует enum без границ. Kenji Space ограничивает DRAM, memory mode, resolution и другие числовые значения.
4. **CrashHandler.** Официальный обработчик зависел от ещё пустого `MainActivity.AppPath`. Kenji Space пишет Java-исключения в `filesDir/logs` и не маскирует исходный handler.
5. **Native player lifecycle.** Player теперь запускает core в правильном порядке: JavaVM → device → renderer → game → input → `inputUpdate()` pump, показывает реальные данные и закрывает `deviceCloseEmulation()` перед следующим запуском.
6. **APK.** В release workflow вшивается проверенный официальный набор `.so`, включая `libkenjinx.so`, `libkenjinxjni.so` и `libsymbiosis_kenji.so` с реальным Vulkan driver probe. Ключи и прошивка в APK не входят и показываются в player как обязательные пользовательские данные.
