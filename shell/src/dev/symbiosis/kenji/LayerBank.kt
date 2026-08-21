package dev.symbiosis.kenji

import android.content.Context
import org.json.JSONObject

/**
 * User picks which Space layers sit on the game.
 * Factory Kenji Loading is not a toggle — hiding that window blanked Skia.
 */
object LayerBank {
    private const val CHIP = "layer_chip"
    private const val STATS = "layer_stats"
    private const val HUD = "layer_hud"
    private const val SESSION = "layer_session"
    private const val BATTERY = "layer_battery"
    private const val GEAR = "layer_gear"
    private const val FORCE = "force_nce_pptc"
    private const val PRESET = "launch_preset"
    private const val NCE = "launch_nce"
    private const val PPTC = "launch_pptc"
    private const val DOCK = "launch_docked"
    private const val SCALE = "launch_scale"

    private fun space(c: Context) = c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)

    fun chipOn(c: Context): Boolean {
        val p = space(c)
        if (p.contains(CHIP)) return SafePrefs.bool(p, CHIP, true)
        return !p.getBoolean("clock_off", false)
    }

    fun setChip(c: Context, on: Boolean) {
        val e = space(c).edit()
        e.putBoolean(CHIP, on)
        e.putBoolean("clock_off", !on)
        e.commit()
    }

    fun statsOn(c: Context): Boolean = SafePrefs.bool(space(c), STATS, false)

    fun setStats(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), STATS, on)
        SettingsBank.setOverlayRaw(c, on)
    }

    fun hudOn(c: Context): Boolean = SafePrefs.bool(space(c), HUD, false)

    fun setHud(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), HUD, on)
    }

    fun anySpaceOnGame(c: Context): Boolean = chipOn(c) || statsOn(c) || hudOn(c)

    fun forceNce(c: Context): Boolean = SafePrefs.bool(space(c), FORCE, false)

    fun setForceNce(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), FORCE, on)
    }

    fun launchPreset(c: Context): String = space(c).getString(PRESET, "").orEmpty()

    fun setLaunchPreset(c: Context, name: String) {
        space(c).edit().putString(PRESET, name).commit()
    }

    fun launchNce(c: Context): Boolean = SafePrefs.bool(space(c), NCE, SettingsBank.nceOf(c))

    fun setLaunchNce(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), NCE, on)
    }

    fun launchPptc(c: Context): Boolean = SafePrefs.bool(space(c), PPTC, SettingsBank.pptcOf(c))

    fun setLaunchPptc(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), PPTC, on)
    }

    fun launchDocked(c: Context): Boolean = SafePrefs.bool(space(c), DOCK, SettingsBank.dockedOf(c))

    fun setLaunchDocked(c: Context, on: Boolean) {
        SafePrefs.putBool(space(c), DOCK, on)
    }

    fun launchScale(c: Context): Float = SafePrefs.dec(space(c), SCALE, SettingsBank.scaleOf(c))

    fun setLaunchScale(c: Context, v: Float) {
        SafePrefs.putFloat(space(c), SCALE, v)
    }

    /** clean / chip / chip_fps / full */
    fun applyPreset(c: Context, key: String) {
        when (key) {
            "clean" -> {
                setChip(c, false); setStats(c, false); setHud(c, false)
            }
            "chip" -> {
                setChip(c, true); setStats(c, false); setHud(c, false)
            }
            "chip_fps" -> {
                setChip(c, true); setStats(c, true); setHud(c, false)
            }
            "full" -> {
                setChip(c, true); setStats(c, true); setHud(c, true)
            }
        }
    }

    fun currentPresetKey(c: Context): String {
        val chip = chipOn(c)
        val stats = statsOn(c)
        val hud = hudOn(c)
        return when {
            !chip && !stats && !hud -> "clean"
            chip && !stats && !hud -> "chip"
            chip && stats && !hud -> "chip_fps"
            chip && stats && hud -> "full"
            else -> ""
        }
    }

    fun applyToOfficial(c: Context) {
        val named = launchPreset(c)
        if (named.isNotEmpty()) SettingsBank.applyNamed(c, named)
        SettingsBank.applyLaunch(c, launchNce(c), launchPptc(c), launchDocked(c), launchScale(c))
    }

    fun saveForGame(c: Context, titleId: String) {
        if (titleId.length < 8) return
        val o = JSONObject()
            .put("preset", launchPreset(c))
            .put("nce", launchNce(c))
            .put("pptc", launchPptc(c))
            .put("docked", launchDocked(c))
            .put("scale", launchScale(c).toDouble())
            .put("force", forceNce(c))
            .put("chip", chipOn(c))
            .put("stats", statsOn(c))
            .put("hud", hudOn(c))
        space(c).edit().putString("game_$titleId", o.toString()).commit()
    }

    fun loadForGame(c: Context, titleId: String): Boolean {
        if (titleId.length < 8) return false
        val raw = space(c).getString("game_$titleId", "") ?: return false
        if (raw.isBlank()) return false
        return try {
            val o = JSONObject(raw)
            setLaunchPreset(c, o.optString("preset", ""))
            setLaunchNce(c, o.optBoolean("nce", launchNce(c)))
            setLaunchPptc(c, o.optBoolean("pptc", launchPptc(c)))
            setLaunchDocked(c, o.optBoolean("docked", launchDocked(c)))
            setLaunchScale(c, o.optDouble("scale", launchScale(c).toDouble()).toFloat())
            setForceNce(c, o.optBoolean("force", forceNce(c)))
            if (o.has("chip")) setChip(c, o.optBoolean("chip", chipOn(c)))
            if (o.has("stats")) setStats(c, o.optBoolean("stats", statsOn(c)))
            if (o.has("hud")) setHud(c, o.optBoolean("hud", hudOn(c)))
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun summary(c: Context): String {
        val layers = buildString {
            if (chipOn(c)) append("⏳ ")
            if (statsOn(c)) append("FPS ")
            if (hudOn(c)) append("⚙ ")
            if (isEmpty()) append("чистое ядро")
        }.trim()
        return layers
    }
}
