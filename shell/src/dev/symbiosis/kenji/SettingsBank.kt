package dev.symbiosis.kenji

import android.content.Context
import android.preference.PreferenceManager
import org.json.JSONObject

/**
 * Writes official Kenji QuickSettings (same default SharedPreferences their UI reads).
 */
object SettingsBank {
    private fun space(c: Context) = c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
    private fun official(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    fun applyDefaultOnce(c: Context) {
        if (SafePrefs.bool(space(c), "mali_applied", false)) return
        applyDefault(c)
        SafePrefs.putBool(space(c), "mali_applied", true)
    }

    fun applyDefault(c: Context) {
        write(
            c,
            enablePptc = true,
            useNce = false,
            enableDocked = false,
            enableLowPowerPptc = false,
            enableJitCacheEviction = false,
            enableFsIntegrityChecks = false,
            ignoreMissingServices = false,
            enableShaderCache = true,
            memoryConfiguration = 0,
            memoryManagerMode = 2,
            resScale = 1f,
        )
    }

    fun applyScale(c: Context, scale: Float, docked: Boolean) {
        val p = official(c)
        SafePrefs.putFloat(p, "resScale", scale)
        SafePrefs.putBool(p, "enableDocked", docked)
    }

    fun ensureCatalog(c: Context) {
        val p = named(c)
        if (SafePrefs.bool(p, "catalog_v1", false)) return
        val pack = listOf(
            Triple("скорость 0.5×", 0.5f, false),
            Triple("баланс 0.75×", 0.75f, false),
            Triple("оригинал 1×", 1f, false),
            Triple("чёткость 1.5×", 1.5f, false),
            Triple("качество 2×", 2f, false),
            Triple("максимум 3×", 3f, false),
            Triple("Docked 1×", 1f, true),
            Triple("Docked 1.5×", 1.5f, true),
            Triple("экономия 0.5×", 0.5f, false),
        )
        val arr = org.json.JSONArray(p.getString("list", "[]"))
        for ((name, scale, dock) in pack) {
            val o = snapshot(c)
            o.put("name", name)
            o.put("resScale", scale.toDouble())
            o.put("enableDocked", dock)
            arr.put(o)
        }
        p.edit().putString("list", arr.toString()).putBoolean("catalog_v1", true).commit()
    }

    fun listNamed(c: Context): List<String> {
        ensureCatalog(c)
        val arr = org.json.JSONArray(named(c).getString("list", "[]"))
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val n = arr.optJSONObject(i)?.optString("name").orEmpty()
            if (n.isNotEmpty()) out.add(n)
        }
        return out
    }

    fun saveNamed(c: Context, name: String) {
        val arr = org.json.JSONArray(named(c).getString("list", "[]"))
        val next = org.json.JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) next.put(o)
        }
        next.put(snapshot(c).put("name", name))
        named(c).edit().putString("list", next.toString()).commit()
    }

    fun applyNamed(c: Context, name: String): String {
        val arr = org.json.JSONArray(named(c).getString("list", "[]"))
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            if (o.optString("name") != name) continue
            write(
                c,
                enablePptc = o.optBoolean("enablePptc", true),
                useNce = o.optBoolean("useNce", false),
                enableDocked = o.optBoolean("enableDocked", false),
                enableLowPowerPptc = o.optBoolean("enableLowPowerPptc", false),
                enableJitCacheEviction = o.optBoolean("enableJitCacheEviction", false),
                enableFsIntegrityChecks = o.optBoolean("enableFsIntegrityChecks", false),
                ignoreMissingServices = o.optBoolean("ignoreMissingServices", false),
                enableShaderCache = o.optBoolean("enableShaderCache", true),
                memoryConfiguration = o.optInt("memoryConfiguration", 0),
                memoryManagerMode = o.optInt("memoryManagerMode", 2),
                resScale = o.optDouble("resScale", 1.0).toFloat(),
            )
            return "включён «$name»"
        }
        return "пресета нет"
    }

    private fun named(c: Context) = c.getSharedPreferences("kenji_presets", Context.MODE_PRIVATE)

    fun snapshot(c: Context): JSONObject {
        val p = official(c)
        return JSONObject()
            .put("enablePptc", SafePrefs.bool(p, "enablePptc", true))
            .put("useNce", SafePrefs.bool(p, "useNce", false))
            .put("enableDocked", SafePrefs.bool(p, "enableDocked", false))
            .put("resScale", SafePrefs.dec(p, "resScale", 1f).toDouble())
            .put("memoryConfiguration", SafePrefs.integer(p, "memoryConfiguration", 0))
            .put("memoryManagerMode", SafePrefs.integer(p, "memoryManagerMode", 2))
    }

    private fun write(
        c: Context,
        enablePptc: Boolean,
        useNce: Boolean,
        enableDocked: Boolean,
        enableLowPowerPptc: Boolean,
        enableJitCacheEviction: Boolean,
        enableFsIntegrityChecks: Boolean,
        ignoreMissingServices: Boolean,
        enableShaderCache: Boolean,
        memoryConfiguration: Int,
        memoryManagerMode: Int,
        resScale: Float,
    ) {
        val p = official(c)
        SafePrefs.putBool(p, "enablePptc", enablePptc)
        SafePrefs.putBool(p, "useNce", useNce)
        SafePrefs.putBool(p, "enableDocked", enableDocked)
        SafePrefs.putBool(p, "enableLowPowerPptc", enableLowPowerPptc)
        SafePrefs.putBool(p, "enableJitCacheEviction", enableJitCacheEviction)
        SafePrefs.putBool(p, "enableFsIntegrityChecks", enableFsIntegrityChecks)
        SafePrefs.putBool(p, "ignoreMissingServices", ignoreMissingServices)
        SafePrefs.putBool(p, "enableShaderCache", enableShaderCache)
        SafePrefs.putInt(p, "memoryConfiguration", memoryConfiguration)
        SafePrefs.putInt(p, "memoryManagerMode", memoryManagerMode)
        SafePrefs.putFloat(p, "resScale", resScale)
    }
}
