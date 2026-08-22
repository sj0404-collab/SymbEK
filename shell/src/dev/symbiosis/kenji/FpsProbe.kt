package dev.symbiosis.kenji

/**
 * Real Kenji/renderer FPS via reflection. Choreographer is the display
 * refresh (120 on a 120 Hz panel) — that is not the game.
 */
object FpsProbe {
    @Volatile private var how: String = "нет"

    fun how(): String = how

    fun sample(): Double {
        val names = arrayOf(
            "org.kenjinx.android.KenjinxNative",
            "org.kenjinx.android.native.KenjinxNative",
            "org.kenjinx.android.GameHost",
        )
        val methods = arrayOf(
            "getFps", "GetFps", "deviceGetFps", "DeviceGetFps",
            "getGameFps", "getCurrentFps", "getAverageFps", "getFrameRate",
        )
        for (cn in names) {
            val cls = runCatching { Class.forName(cn) }.getOrNull() ?: continue
            for (mn in methods) {
                val v = invokeNumber(cls, mn)
                if (v != null && v >= 0.0 && v < 400.0) {
                    how = "${cls.simpleName}.$mn"
                    return v
                }
            }
        }
        how = "ядро не отдало FPS"
        return -1.0
    }

    private fun invokeNumber(cls: Class<*>, name: String): Double? {
        val ms = try {
            cls.methods
        } catch (_: Throwable) {
            return null
        }
        for (m in ms) {
            if (m.name != name || m.parameterTypes.isNotEmpty()) continue
            return try {
                m.isAccessible = true
                val raw = m.invoke(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else null)
                when (raw) {
                    is Number -> raw.toDouble()
                    is String -> raw.replace(',', '.').toDoubleOrNull()
                    else -> null
                }
            } catch (_: Throwable) {
                null
            }
        }
        return null
    }
}
