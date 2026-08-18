package dev.symbiosis.kenji

import android.content.Context
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
    private val TID = Pattern.compile("(?i)\\[?(0100[0-9A-F]{12})\\]?")

    fun list(context: Context): List<GameRom> {
        val seen = LinkedHashSet<String>()
        val raw = ArrayList<GameRom>()
        for (dir in FolderHub.gamesDirs(context)) collect(dir, seen, raw, 0)
        val bases = raw.filter { !it.update && it.titleId.length == 16 }.map { it.titleId }.toHashSet()
        return raw.filter { rom ->
            rom.exists && !rom.update && (!rom.update || !bases.contains(baseId(rom.titleId)))
        }.sortedBy { it.title.lowercase(Locale.US) }
    }

    fun addPath(context: Context, path: String) = FolderHub.addGamesDir(context, path)
    fun removePath(context: Context, path: String) = FolderHub.removeGamesDir(context, path)
    fun folders(context: Context): List<File> = FolderHub.gamesDirs(context)

    private fun collect(dir: File, seen: MutableSet<String>, out: MutableList<GameRom>, depth: Int) {
        if (depth > 3 || !dir.isDirectory) return
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (f.isFile && isRom(f.name)) {
                val path = f.absolutePath
                if (!seen.add(path)) continue
                out.add(
                    GameRom(
                        title = pretty(f.name),
                        path = path,
                        titleId = titleIdOf(f.name),
                        bytes = f.length(),
                        fileName = f.name,
                        folder = dir.name,
                    ),
                )
            } else if (f.isDirectory && (depth == 0 || !skipChild(f.name))) {
                collect(f, seen, out, depth + 1)
            }
        }
    }

    private fun skipChild(name: String): Boolean {
        val n = name.lowercase(Locale.US)
        return n in setOf(
            "nand", "bis", "keys", "system", "registered", "android",
            "load", "mods", "shader", "cache", "screenshots", "album",
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
}
