package dev.symbiosis.kenji

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Inbox for dumps. Kenji opens nsp/xci/nro as-is. Compressed nsz/xcz are
 * kept and marked honestly — we do not pretend to decompress them.
 */
object Inbox {
    fun dir(): File = File(KenjiApp.instance.filesDir, "converter").apply { mkdirs() }

    fun listJson(): String {
        val arr = JSONArray()
        dir().listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.forEach { f ->
            val ext = f.name.substringAfterLast('.', "").lowercase()
            val launch = ext in setOf("nsp", "xci", "nro")
            arr.put(
                JSONObject()
                    .put("ok", launch)
                    .put("canLaunch", launch)
                    .put("path", f.absolutePath)
                    .put("name", f.name)
                    .put("title", f.name.substringBeforeLast('.'))
                    .put("bytes", f.length())
                    .put("size", human(f.length()))
                    .put("reason", if (launch) "" else "Kenji открывает nsp/xci/nro. $ext нужно разжать в Symbiosis.")
            )
        }
        return JSONObject().put("items", arr).put("path", dir().absolutePath).toString()
    }

    fun import(context: Context, uri: Uri): String = runCatching {
        val name = displayName(context, uri).ifBlank { "dump.bin" }
        val dest = File(dir(), name.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_"))
        context.contentResolver.openInputStream(uri)?.use { input ->
            dest.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching JSONObject().put("ok", false).put("message", "не прочитался").toString()
        val ext = dest.name.substringAfterLast('.', "").lowercase()
        val launch = ext in setOf("nsp", "xci", "nro")
        JSONObject().put("ok", true).put("canLaunch", launch).put("path", dest.absolutePath)
            .put("message", if (launch) "можно открыть · ${dest.name}" else "сохранён ${dest.name}, но Kenji его не запустит")
            .toString()
    }.getOrElse { JSONObject().put("ok", false).put("message", it.message ?: "сбой").toString() }

    fun delete(path: String): String {
        val f = File(path)
        val ok = f.exists() && f.canonicalPath.startsWith(dir().canonicalPath) && f.delete()
        return JSONObject().put("ok", ok).toString()
    }

    fun canOpen(path: String): Boolean {
        val ext = path.substringAfterLast('.', "").lowercase()
        return ext in setOf("nsp", "xci", "nro") && File(path).isFile
    }

    fun queueJson(): String = JSONObject().put("busy", false).put("pending", 0).put("note", "").toString()

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull() ?: uri.lastPathSegment.orEmpty()

    private fun human(n: Long): String {
        if (n < 1024L * 1024) return "${n / 1024} КБ"
        if (n < 1024L * 1024 * 1024) return "${n / (1024 * 1024)} МБ"
        return "%.1f ГБ".format(n / (1024.0 * 1024 * 1024))
    }
}
