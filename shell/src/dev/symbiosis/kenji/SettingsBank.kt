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
