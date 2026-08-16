package dev.symbiosis.kenji

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

class PluginStore(private val context: Context) {

    private fun root(): File = File(context.filesDir, "plugins").apply { mkdirs(); File(this, "packs").mkdirs() }
    private fun packs(): File = File(root(), "packs")

    fun listJson(): String {
        val arr = JSONArray()
        packs().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val man = readMan(dir)
            arr.put(
                JSONObject()
                    .put("id", dir.name)
                    .put("name", man.optString("name", dir.name))
                    .put("enabled", man.optBoolean("enabled", true))
                    .put("files", dir.listFiles()?.size ?: 0)
            )
        }
        return JSONObject().put("items", arr).put("logs", JSONArray()).toString()
    }

    fun payloadJson(): String {
        val css = StringBuilder()
        val theme = JSONObject()
        val hide = JSONArray()
        packs().listFiles()?.filter { it.isDirectory }?.forEach { dir ->
            val man = readMan(dir)
            if (!man.optBoolean("enabled", true)) return@forEach
            man.optJSONObject("theme")?.let { t -> t.keys().forEach { theme.put(it, t.optString(it)) } }
            man.optJSONArray("hide")?.let { a -> for (i in 0 until a.length()) hide.put(a.optString(i)) }
            dir.listFiles()?.filter { it.extension.equals("css", true) }?.forEach {
                runCatching { css.append(it.readText()).append('\n') }
            }
        }
        return JSONObject().put("css", css.toString()).put("theme", theme).put("hide", hide)
            .put("html", JSONArray()).put("strings", JSONObject()).toString()
    }

    fun setEnabled(id: String, on: Boolean): String {
        val dir = File(packs(), id)
        if (!dir.isDirectory) return fail("нет плагина")
        val man = readMan(dir)
        man.put("enabled", on)
        File(dir, "plugin.json").writeText(man.toString(2))
        return JSONObject().put("ok", true).put("message", if (on) "включён" else "скрыт").toString()
    }

    fun remove(id: String): String {
        val dir = File(packs(), id)
        if (!dir.isDirectory) return fail("нет плагина")
        dir.deleteRecursively()
        return JSONObject().put("ok", true).put("message", "снесён").toString()
    }

    fun install(uri: Uri): String = runCatching {
        val name = displayName(uri).ifBlank { "plugin.bin" }
        val dest = File(packs(), stem(name)).apply { mkdirs() }
        context.contentResolver.openInputStream(uri)?.use { input ->
            if (name.endsWith(".zip", true) || name.endsWith(".pkg", true)) {
                ZipInputStream(input).use { zis ->
                    while (true) {
                        val e = zis.nextEntry ?: break
                        if (e.isDirectory) continue
                        val rel = e.name.replace('\\', '/').trimStart('/')
                        if (".." in rel) continue
                        val out = File(dest, rel)
                        if (!out.canonicalPath.startsWith(dest.canonicalPath)) continue
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                    }
                }
            } else {
                File(dest, name.substringAfterLast('/')).outputStream().use { input.copyTo(it) }
                if (name.endsWith(".json", true) || name.endsWith(".css", true)) {
                    if (!File(dest, "plugin.json").isFile) {
                        File(dest, "plugin.json").writeText(
                            JSONObject().put("name", name).put("enabled", true).toString(2)
                        )
                    }
                }
            }
        }
        if (!File(dest, "plugin.json").isFile) {
            File(dest, "plugin.json").writeText(
                JSONObject().put("name", name).put("enabled", true).toString(2)
            )
        }
        JSONObject().put("ok", true).put("message", "Поздравляю: $name встроен").put("id", dest.name).toString()
    }.getOrElse { fail(it.message ?: "не установился") }

    private fun readMan(dir: File): JSONObject {
        val f = File(dir, "plugin.json")
        if (!f.isFile) return JSONObject().put("name", dir.name).put("enabled", true)
        return runCatching { JSONObject(f.readText()) }.getOrDefault(
            JSONObject().put("name", dir.name).put("enabled", true)
        )
    }

    private fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun stem(name: String): String =
        name.substringAfterLast('/').substringBeforeLast('.').lowercase()
            .replace(Regex("[^a-z0-9_-]"), "_").take(40).ifBlank { "pack" }

    private fun fail(m: String) = JSONObject().put("ok", false).put("message", m).toString()
}
