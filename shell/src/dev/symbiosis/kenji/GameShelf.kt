package dev.symbiosis.kenji

import android.content.Context
import android.net.Uri
import android.preference.PreferenceManager
import java.io.File
import java.util.Locale
import java.util.regex.Pattern

data class GameRom(
    val title: String,
    val path: String,
    val titleId: String,
    val bytes: Long,
    val fileName: String,
    val folder: String,
) {
    val exists: Boolean get() = path.startsWith("/") && File(path).isFile
    val update: Boolean get() = titleId.length == 16 && titleId.uppercase(Locale.US).endsWith("800")
}

object GameShelf {
    private const val PREF = "kenji_folders"
    private val TID = Pattern.compile("(?i)\\[?(0100[0-9A-F]{12})\\]?")

    fun list(context: Context): List<GameRom> {
        val seen = LinkedHashSet<String>()
        val raw = ArrayList<GameRom>()
        for (dir in folders(context)) collect(dir, seen, raw, 0)
        val bases = raw.filter { !it.update && it.titleId.length == 16 }.map { it.titleId }.toHashSet()
        return raw.filter { rom ->
            if (!rom.exists) return@filter false
            if (rom.update && bases.contains(baseId(rom.titleId))) return@filter false
            if (rom.update) return@filter false
            true
        }.sortedBy { it.title.lowercase(Locale.US) }
    }

    fun addPath(context: Context, path: String) {
        val f = File(path)
        if (!f.isDirectory) return
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val next = LinkedHashSet(p.getStringSet("uris", emptySet()) ?: emptySet())
        next.add(f.absolutePath)
        p.edit().putStringSet("uris", next).commit()
        PreferenceManager.getDefaultSharedPreferences(context)
            .edit().putString("gameFolderPath", f.absolutePath).commit()
    }

    fun removePath(context: Context, path: String) {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        val next = LinkedHashSet(p.getStringSet("uris", emptySet()) ?: emptySet())
        next.remove(path)
        p.edit().putStringSet("uris", next).commit()
    }

    fun folders(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        p.getStringSet("uris", emptySet())?.forEach { s ->
            if (s.startsWith("/")) {
                val f = File(s)
                if (f.isDirectory) out.add(f)
            }
        }
        val off = PreferenceManager.getDefaultSharedPreferences(context)
        off.getString("gameFolderPath", null)?.let {
            val f = File(it)
            if (f.isDirectory) out.add(f)
        }
        val sd = android.os.Environment.getExternalStorageDirectory()
        listOf("Download/ed", "Download/Switch", "Download/NSP", "Switch", "Games").forEach { rel ->
            val f = File(sd, rel)
            if (f.isDirectory && looksGameFolder(f)) out.add(f)
        }
        return out.toList()
    }

    private fun looksGameFolder(dir: File): Boolean {
        val kids = dir.listFiles() ?: return false
        return kids.any { it.isFile && isRom(it.name) }
    }

    private fun collect(dir: File, seen: MutableSet<String>, out: MutableList<GameRom>, depth: Int) {
        if (depth > 2 || !dir.isDirectory || skipName(dir.name)) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isFile && isRom(f.name)) {
                val path = f.absolutePath
                if (!seen.add(path)) continue
                val tid = titleIdOf(f.name)
                out.add(
                    GameRom(
                        title = pretty(f.name),
                        path = path,
                        titleId = tid,
                        bytes = f.length(),
                        fileName = f.name,
                        folder = dir.name,
                    ),
                )
            } else if (f.isDirectory && !skipName(f.name)) {
                collect(f, seen, out, depth + 1)
            }
        }
    }

    private fun skipName(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n in setOf(
            "nand", "bis", "keys", "system", "registered", "android",
            "eden", "load", "mods", "shader", "cache", "screenshots",
        ) || n.startsWith(".")
    }

    fun isRom(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nsz") || n.endsWith(".xcz")
    }

    fun pretty(name: String): String {
        var stem = name.substringBeforeLast('.')
        stem = stem.replace(Regex("(?i)\\[0100[0-9A-F]{12}]"), "")
        stem = stem.replace(Regex("(?i)\\[v\\d+]"), "")
        stem = stem.replace(Regex("\\([^)]*\\s*[Gg][Bb]\\)"), "")
        stem = stem.replace('_', ' ').replace(Regex("\\s+"), " ").trim()
        return stem.ifBlank { name }
    }

    fun titleIdOf(name: String): String {
        val m = TID.matcher(name)
        return if (m.find()) m.group(1).uppercase(Locale.US) else ""
    }

    fun baseId(titleId: String): String {
        if (titleId.length != 16 || !titleId.uppercase(Locale.US).endsWith("800")) return titleId
        return try {
            String.format(Locale.US, "%016X", java.lang.Long.parseUnsignedLong(titleId, 16) - 0x800L)
        } catch (_: Exception) {
            titleId
        }
    }

    fun treeToPath(uri: Uri): String? = PickActivity.treeToPath(uri)
}
