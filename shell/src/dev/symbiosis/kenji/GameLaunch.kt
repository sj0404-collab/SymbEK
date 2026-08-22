package dev.symbiosis.kenji

import android.app.Activity
import android.content.Intent
import android.util.Log

/**
 * Official MainActivity extras: bootPath / titleName / titleId / forceNceAndPptc.
 * No file:// data — FileUriExposedException on API 24+.
 */
object GameLaunch {
    fun start(host: Activity, rom: RomList.Rom, forceNce: Boolean): String {
        if (rom.update) {
            return "это обновление ${rom.titleId} — Kenji рисует Unknown, не игра"
        }
        if (rom.dlc) {
            return "это DLC ${rom.titleId} — не базовая игра"
        }
        if (!rom.file.isFile) return "файла нет: ${rom.file.absolutePath}"
        if (rom.compressed) {
            BootLog.add("launch compressed ${rom.file.name} — Kenji может не открыть NSZ/XCZ")
        }
        LayerBank.applyToOfficial(host)
        if (rom.titleId.isNotEmpty()) LayerBank.saveForGame(host, rom.titleId)
        val intent = Intent()
        intent.setClassName(host.packageName, "org.kenjinx.android.MainActivity")
        intent.action = "org.kenjinx.android.LAUNCH_GAME"
        intent.putExtra("bootPath", rom.file.absolutePath)
        intent.putExtra("titleName", rom.title)
        if (rom.titleId.isNotEmpty()) intent.putExtra("titleId", rom.titleId)
        intent.putExtra("forceNceAndPptc", forceNce)
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val how = deliver(host, intent)
        BootLog.add("launch ${rom.file.name} $how forceNce=$forceNce")
        return if (how != "fail") "запуск ${rom.title} · $how" else "не смог отдать bootPath ядру"
    }

    private fun deliver(host: Activity, intent: Intent): String {
        for (name in arrayOf("handleIntent", "onNewIntent")) {
            var cls: Class<*>? = host.javaClass
            while (cls != null && cls != Any::class.java) {
                val methods = try {
                    cls.declaredMethods
                } catch (_: Throwable) {
                    emptyArray()
                }
                for (m in methods) {
                    if (m.name != name) continue
                    if (m.parameterTypes.size != 1) continue
                    if (!Intent::class.java.isAssignableFrom(m.parameterTypes[0])) continue
                    try {
                        m.isAccessible = true
                        m.invoke(host, intent)
                        return name
                    } catch (t: Throwable) {
                        Log.w("KenjiSpace", name, t)
                    }
                }
                cls = cls.superclass
            }
        }
        return try {
            host.startActivity(intent)
            "startActivity"
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "launch", t)
            "fail"
        }
    }
}
