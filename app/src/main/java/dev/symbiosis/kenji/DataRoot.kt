package dev.symbiosis.kenji

import android.content.Context
import android.os.Build
import android.os.Environment
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * One data folder for firmware, keys and Kenji NAND.
 *
 * Official Kenji hides this under Android/data/org.kenjinx.android/files
 * and Eden under its own Android/data. The user should pick once — the
 * same tree both apps can see — instead of installing firmware twice.
 *
 * Kenji layout:  system/prod.keys, bis/, games/, profiles/
 * Eden layout:   keys/prod.keys, nand/, load/
 * We accept either, copy keys into Kenji's system/, and tell the truth
 * when firmware formats do not match.
 */
object DataRoot {

    private const val PREF = "kenji_data_root"

    fun prefs(context: Context) =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)

    var configured: String?
        get() = KenjiApp.instance.let { prefs(it).getString(PREF, null) }?.takeIf { it.isNotBlank() }
        set(value) {
            val e = prefs(KenjiApp.instance).edit()
            if (value.isNullOrBlank()) e.remove(PREF) else e.putString(PREF, value)
            e.commit()
        }

    fun privatePath(): String = KenjiApp.filesRoot().absolutePath

    fun resolve(): String {
        val c = configured
        if (c != null && File(c).isDirectory) return normalise(c)
        return privatePath()
    }

    /** Kenji home: where javaInitialize looks (system/, bis/, games/). */
    fun kenjiHome(): File {
        val root = File(resolve())
        ensureKenjiLayout(root)
        return root
    }

    fun normalise(path: String): String {
        val chosen = File(path)
        if (looksKenji(chosen) || looksEden(chosen)) return path
        val files = File(chosen, "files")
        if (files.isDirectory && (looksKenji(files) || looksEden(files))) return files.absolutePath
        return path
    }

    fun looksKenji(dir: File): Boolean =
        File(dir, "system").isDirectory || File(dir, "bis").isDirectory ||
            File(dir, "system/prod.keys").isFile

    fun looksEden(dir: File): Boolean =
        File(dir, "keys").isDirectory || File(dir, "nand").isDirectory ||
            File(dir, "keys/prod.keys").isFile

    fun ensureKenjiLayout(root: File) {
        listOf("system", "bis", "games", "profiles", "keys", "screenshots", "logs").forEach {
            File(root, it).mkdirs()
        }
        seedKeysIntoKenji(root)
    }

    /** Copy prod.keys / title.keys from Eden-style or Kenji-style locations. */
    fun seedKeysIntoKenji(root: File) {
        val dests = listOf(File(root, "system"), File(root, "keys"))
        dests.forEach { it.mkdirs() }
        val sources = listOf(
            File(root, "system"),
            File(root, "keys"),
            File(root, "kenji/system"),
            File(root, "kenji/keys"),
        )
        listOf("prod.keys", "title.keys").forEach { name ->
            val found = sources.map { File(it, name) }.firstOrNull { it.isFile && it.length() > 100 }
                ?: return@forEach
            dests.forEach { d ->
                val t = File(d, name)
                if (!t.isFile || t.length() != found.length()) {
                    runCatching { found.copyTo(t, overwrite = true) }
                }
            }
        }
    }

    fun keysPresent(): Boolean {
        val root = File(resolve())
        return listOf("system/prod.keys", "keys/prod.keys")
            .any { File(root, it).let { f -> f.isFile && f.length() > 100 } }
    }

    fun firmwarePresent(): Boolean {
        val root = File(resolve())
        val kenjiBis = File(root, "bis")
        if (kenjiBis.isDirectory && (kenjiBis.listFiles()?.isNotEmpty() == true)) return true
        val edenFw = File(root, "nand/system/Contents/registered")
        return edenFw.isDirectory && (edenFw.listFiles()?.size ?: 0) > 10
    }

    fun firmwareNote(): String {
        val root = File(resolve())
        val bis = File(root, "bis")
        if (bis.isDirectory && (bis.listFiles()?.isNotEmpty() == true))
            return "прошивка Kenji в bis/"
        val n = File(root, "nand/system/Contents/registered").listFiles()?.size ?: 0
        if (n > 10) return "это папка Eden: $n файлов прошивки. Kenji её так не читает — поставьте прошивку один раз в эту же папку через «Прошивка» или укажите папку официального Kenji (там уже bis/)."
        return "прошивки нет"
    }

    fun inspect(path: String): JSONObject {
        val dir = File(path)
        val keys = File(dir, "system/prod.keys").isFile || File(dir, "keys/prod.keys").isFile
        val bis = File(dir, "bis").listFiles()?.isNotEmpty() == true
        val edenFw = File(dir, "nand/system/Contents/registered").listFiles()?.size ?: 0
        return JSONObject()
            .put("path", path)
            .put("exists", dir.isDirectory)
            .put("kenji", looksKenji(dir))
            .put("eden", looksEden(dir))
            .put("keys", keys)
            .put("firmwareKenji", bis)
            .put("firmwareEden", edenFw)
    }

    fun suggest(): String {
        val sd = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            "официальный Kenji" to File(sd, "Android/data/org.kenjinx.android/files").absolutePath,
            "Kenji (видимая)" to File(sd, "Kenji").absolutePath,
            "Switch (общая)" to File(sd, "Switch").absolutePath,
            "официальный Eden" to File(sd, "Android/data/dev.eden.eden_emulator/files").absolutePath,
            "Eden (видимая)" to File(sd, "Eden").absolutePath,
            "Symbiosis" to File(sd, "Android/data/org.yuzu.yuzu_emu/files").absolutePath,
            "приватная этого APK" to privatePath(),
        )
        val arr = JSONArray()
        val seen = HashSet<String>()
        for ((label, path) in candidates) {
            if (!seen.add(path)) continue
            val dir = File(path)
            if (!dir.isDirectory && path != privatePath() && !path.endsWith("/Kenji") && !path.endsWith("/Switch"))
                continue
            val inf = inspect(path)
            arr.put(
                JSONObject()
                    .put("label", label)
                    .put("path", path)
                    .put("keys", inf.optBoolean("keys"))
                    .put("kenji", inf.optBoolean("kenji"))
                    .put("eden", inf.optBoolean("eden"))
                    .put("exists", dir.isDirectory)
            )
        }
        return JSONObject().put("roots", arr).put("current", resolve()).toString()
    }

    fun setPath(path: String): String {
        val norm = normalise(path)
        val dir = File(norm)
        if (!dir.exists()) dir.mkdirs()
        if (!dir.isDirectory) {
            return JSONObject().put("ok", false).put("message", "не папка: $norm").toString()
        }
        configured = norm
        ensureKenjiLayout(dir)
        val inf = inspect(norm)
        val bits = mutableListOf<String>()
        if (inf.optBoolean("keys")) bits += "ключи есть"
        else bits += "ключей нет"
        if (inf.optBoolean("firmwareKenji")) bits += "прошивка Kenji на месте"
        else if (inf.optInt("firmwareEden") > 0) bits += firmwareNote()
        else bits += "прошивки нет"
        return JSONObject().put("ok", true).put("path", norm)
            .put("message", "корень данных: $norm · ${bits.joinToString(" · ")}")
            .toString()
    }

    fun statusItems(): JSONArray {
        val root = File(resolve())
        val items = JSONArray()
        items.put(item("Ключи", keysPresent(), if (keysPresent()) "prod.keys" else "нет prod.keys"))
        items.put(item("Прошивка", firmwarePresent(), firmwareNote()))
        items.put(item("Папка данных", root.isDirectory, root.absolutePath))
        return items
    }

    private fun item(label: String, present: Boolean, detail: String) =
        JSONObject().put("label", label).put("present", present).put("detail", detail).put("bytes", 0)

    fun needsAllFiles(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
}
