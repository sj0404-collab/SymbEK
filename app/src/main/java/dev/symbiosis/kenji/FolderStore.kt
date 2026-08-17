package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject

class FolderStore(private val context: Context) {

    private val prefs = context.getSharedPreferences("kenji_folders", Context.MODE_PRIVATE)

    fun json(): String {
        val arr = JSONArray()
        uris().forEach { uri ->
            val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
            val name = tree?.name ?: uri.substringAfterLast('/')
            val games = tree?.listFiles()?.count { it.isFile && isRom(it.name ?: "") } ?: 0
            arr.put(JSONObject().put("uri", uri).put("name", name).put("games", games))
        }
        return JSONObject().put("folders", arr).toString()
    }

    fun gamesJson(): String {
        val arr = JSONArray()
        uris().forEach { uri ->
            val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uri)) }.getOrNull()
                ?: return@forEach
            tree.listFiles().forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name ?: return@forEach
                if (!isRom(name)) return@forEach
                arr.put(
                    JSONObject()
                        .put("title", name.substringBeforeLast('.'))
                        .put("path", f.uri.toString())
                        .put("fileSize", human(f.length()))
                        .put("fileBytes", f.length())
                )
            }
        }
        return JSONObject().put("games", arr).put("keys", DataRoot.keysPresent()).toString()
    }

    fun add(uri: Uri): String {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val next = (uris() + uri.toString()).distinct()
        prefs.edit().putStringSet("uris", next.toSet()).commit()
        return JSONObject().put("ok", true).put("message", "папка добавлена").put("left", next.size).toString()
    }

    fun filesJson(uriString: String): String {
        val arr = JSONArray()
        val tree = runCatching { DocumentFile.fromTreeUri(context, Uri.parse(uriString)) }.getOrNull()
        tree?.listFiles()?.sortedBy { it.name ?: "" }?.forEach { f ->
            arr.put(
                JSONObject()
                    .put("name", f.name ?: "")
                    .put("bytes", f.length())
                    .put("size", human(f.length()))
                    .put("launchable", isRom(f.name ?: ""))
            )
        }
        return JSONObject().put("files", arr).toString()
    }

    fun remove(uri: String): String {
        val next = uris().filter { it != uri }
        prefs.edit().putStringSet("uris", next.toSet()).commit()
        return JSONObject().put("ok", true).put("message", "папка убрана, файлы на диске целы").toString()
    }

    private fun uris(): List<String> = prefs.getStringSet("uris", emptySet())?.toList() ?: emptyList()

    private fun isRom(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        // Kenji's loader accepts these three container types directly. NSZ/XCZ
        // are compressed containers and this project does not silently pretend
        // to convert them, so they stay in the explicit converter inbox.
        return ext in setOf("nsp", "xci", "nro")
    }

    private fun human(n: Long): String {
        if (n <= 0) return ""
        if (n < 1024L * 1024) return "${n / 1024} КБ"
        if (n < 1024L * 1024 * 1024) return "${n / (1024 * 1024)} МБ"
        return "%.1f ГБ".format(n / (1024.0 * 1024 * 1024))
    }
}
