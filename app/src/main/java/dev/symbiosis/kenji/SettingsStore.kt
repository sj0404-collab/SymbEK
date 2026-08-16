package dev.symbiosis.kenji

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/**
 * Official Kenji loses settings because:
 *  1. androidx.core.content.edit { } uses apply() — crash before flush = gone;
 *  2. Enum.entries[prefs.getInt()] throws if the ordinal is stale.
 *
 * Every write here is commit(). Every enum is getOrElse(default).
 * Keys match official QuickSettings so a future import is possible.
 */
class SettingsStore(context: Context) {

    private val p: SharedPreferences =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)

    data class Toggle(val key: String, val label: String, val hint: String, val default: Boolean)

    val toggles = listOf(
        Toggle("useNce", "NCE", "на Mali держите выкл — иначе рандомный вылет", false),
        Toggle("enablePptc", "PPTC", "кэш трансляции, оставьте вкл", true),
        Toggle("enableLowPowerPptc", "Low-Power PPTC", "", false),
        Toggle("enableJitCacheEviction", "Jit Cache Eviction", "", false),
        Toggle("enableFsIntegrityChecks", "Fs Integrity", "на этом телефоне выкл", false),
        Toggle("ignoreMissingServices", "Ignore Missing Services", "", false),
        Toggle("enableDocked", "Docked", "", false),
        Toggle("enableShaderCache", "Shader cache", "", true),
        Toggle("enableTextureRecompression", "Texture recompression", "", false),
        Toggle("enableMacroHLE", "Macro HLE", "", true),
        Toggle("enablePerformanceMode", "Performance mode", "на Mali лучше выкл", false),
        Toggle("preferExternal", "Открывать их Kenji, если стоит", "", true),
    )

    fun json(): String {
        val arr = JSONArray()
        toggles.forEach { t ->
            arr.put(
                JSONObject()
                    .put("key", t.key)
                    .put("label", t.label)
                    .put("hint", t.hint)
                    .put("on", p.getBoolean(t.key, t.default))
            )
        }
        return JSONObject()
            .put("ok", true)
            .put("toggles", arr)
            .put("memoryConfiguration", p.getInt("memoryConfiguration", 0))
            .put("memoryManagerMode", p.getInt("memoryManagerMode", 2))
            .put("backendThreading", p.getInt("backendThreading", 1))
            .put("gameFolder", p.getString("gameFolder", "") ?: "")
            .put("note", "пишется сразу на диск (commit), не в очередь apply()")
            .toString()
    }

    fun setBool(key: String, on: Boolean): String {
        if (toggles.none { it.key == key } && key != "preferExternal") {
            return JSONObject().put("ok", false).put("message", "нет ключа $key").toString()
        }
        val ok = p.edit().putBoolean(key, on).commit()
        return JSONObject().put("ok", ok)
            .put("message", if (ok) "${labelOf(key)}: ${if (on) "вкл" else "выкл"}" else "не записалось")
            .toString()
    }

    fun bool(key: String, default: Boolean): Boolean = p.getBoolean(key, default)

    fun int(key: String, default: Int): Int = p.getInt(key, default)

    fun string(key: String, default: String = ""): String = p.getString(key, default) ?: default

    fun setString(key: String, value: String): Boolean = p.edit().putString(key, value).commit()

    fun <E : Enum<E>> enum(key: String, values: Array<E>, default: E): E {
        val i = p.getInt(key, default.ordinal)
        return values.getOrElse(i) { default }
    }

    private fun labelOf(key: String) = toggles.firstOrNull { it.key == key }?.label ?: key
}
