package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.util.Log

/**
 * Pause the running title instead of stopping it, so presets can apply
 * without tearing down GameHost.
 */
object GamePause {
    @Volatile var paused: Boolean = false
        private set

    @Volatile var lastHow: String = ""
        private set

    fun toggle(context: Context): String =
        if (paused) resume(context) else pause(context)

    fun pause(context: Context): String {
        if (paused) return "уже пауза"
        val ok = invokePause(context, wantPause = true)
        paused = true
        BootLog.add("пауза ${if (ok) lastHow else "логика Space"}")
        return if (ok) "пауза · $lastHow" else "пауза (ядро не отдало метод — стоп не жму)"
    }

    fun resume(context: Context): String {
        if (!paused) return "уже идёт"
        val ok = invokePause(context, wantPause = false)
        paused = false
        BootLog.add("продолжить ${if (ok) lastHow else "логика Space"}")
        return if (ok) "продолжить · $lastHow" else "продолжить"
    }

    fun applyThen(context: Context, block: () -> String): String {
        val was = paused
        if (!was) pause(context)
        val msg = try {
            block()
        } catch (t: Throwable) {
            "ошибка: ${t.message}"
        }
        return if (was) "на паузе · $msg" else "пауза → $msg · нажмите ▶"
    }

    private fun invokePause(context: Context, wantPause: Boolean): Boolean {
        val names = arrayOf(
            "org.kenjinx.android.KenjinxNative",
            "org.kenjinx.android.native.KenjinxNative",
            "org.kenjinx.android.GameHost",
            "org.kenjinx.android.EmulationService",
            "org.kenjinx.android.MainActivity",
            "org.ryujinx.android.KenjinxNative",
        )
        for (n in names) {
            val cls = runCatching { Class.forName(n) }.getOrNull() ?: continue
            if (tryClass(cls, null, wantPause)) return true
        }
        val act = context as? Activity
        if (act != null && tryObject(act, wantPause)) return true
        if (act != null) {
            try {
                for (f in act.javaClass.declaredFields) {
                    f.isAccessible = true
                    val v = runCatching { f.get(act) }.getOrNull() ?: continue
                    if (tryObject(v, wantPause)) return true
                }
            } catch (_: Throwable) {
            }
        }
        lastHow = "метод паузы не найден"
        return false
    }

    private fun tryObject(obj: Any, wantPause: Boolean): Boolean =
        tryClass(obj.javaClass, obj, wantPause)

    private fun tryClass(cls: Class<*>, obj: Any?, wantPause: Boolean): Boolean {
        val methods = try {
            cls.methods
        } catch (_: Throwable) {
            return false
        }
        val want = if (wantPause) {
            arrayOf(
                "pauseEmulation", "PauseEmulation", "pause", "Pause",
                "setPaused", "SetPaused", "togglePauseEmulation",
                "TogglePauseEmulation", "jniPause", "JniPause",
            )
        } else {
            arrayOf(
                "resumeEmulation", "ResumeEmulation", "resume", "Resume",
                "setPaused", "SetPaused", "togglePauseEmulation",
                "TogglePauseEmulation", "jniResume", "JniResume",
            )
        }
        for (name in want) {
            for (m in methods) {
                if (m.name != name) continue
                try {
                    m.isAccessible = true
                    when (m.parameterTypes.size) {
                        0 -> {
                            if (name.contains("setPaused", true)) continue
                            m.invoke(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else obj)
                            lastHow = "${cls.simpleName}.${m.name}()"
                            return true
                        }
                        1 -> {
                            val p = m.parameterTypes[0]
                            val arg: Any? = when {
                                p == java.lang.Boolean.TYPE || p == java.lang.Boolean::class.java -> wantPause
                                p == java.lang.Integer.TYPE -> if (wantPause) 1 else 0
                                else -> continue
                            }
                            m.invoke(if (java.lang.reflect.Modifier.isStatic(m.modifiers)) null else obj, arg)
                            lastHow = "${cls.simpleName}.${m.name}($wantPause)"
                            return true
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("KenjiSpace", "pause $name", t)
                }
            }
        }
        return false
    }
}
