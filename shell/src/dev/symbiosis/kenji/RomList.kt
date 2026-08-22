package dev.symbiosis.kenji

import android.content.Context
import java.io.File
import java.util.Locale

/** Names and sizes only. Does not open NSP / does not call deviceGetGameInfo. */
object RomList {
    private val EXT = arrayOf(".nsp", ".xci", ".nro", ".nsz", ".xcz")
    private val ID = Regex("\\[([0-9A-Fa-f]{16})\\]")

    data class Rom(
        val file: File,
        val title: String,
        val titleId: String,
        val bytes: Long,
        val update: Boolean,
        val dlc: Boolean,
        val compressed: Boolean,
    ) {
        fun line(): String {
            val tag = when {
                update -> "обновление"
                dlc -> "DLC"
                compressed -> "сжат"
                else -> file.extension.lowercase(Locale.US)
            }
            val id = if (titleId.isNotEmpty()) titleId else "нет id в имени"
            return "$title\n  $tag · ${BootLog.human(bytes)} · $id"
        }
    }

    fun list(context: Context): List<Rom> {
        val dirs = LinkedHashSet<File>()
        val cur = GameFolder.currentPath(context)
        if (cur.isNotBlank() && !cur.startsWith("content:")) dirs.add(File(cur))
        FastScan.last?.gameDirs?.forEach { dirs.add(it) }
        val out = ArrayList<Rom>()
        val seen = HashSet<String>()
        for (dir in dirs) {
            if (!dir.isDirectory) continue
            val kids = try {
                dir.listFiles()
            } catch (_: Throwable) {
                null
            } ?: continue
            for (f in kids) {
                if (!f.isFile) continue
                val low = f.name.lowercase(Locale.US)
                if (EXT.none { low.endsWith(it) }) continue
                if (!seen.add(f.absolutePath)) continue
                out.add(parse(f))
                if (out.size >= 48) break
            }
            if (out.size >= 48) break
        }
        return out.sortedWith(compareBy({ it.update }, { it.compressed }, { it.title.lowercase(Locale.US) }))
    }

    private fun parse(f: File): Rom {
        val low = f.name.lowercase(Locale.US)
        val id = ID.find(f.name)?.groupValues?.get(1)?.uppercase(Locale.US) ?: ""
        val update = id.endsWith("800") ||
            low.contains("[upd") ||
            low.contains("update") ||
            low.contains("обнов")
        val compressed = low.endsWith(".nsz") || low.endsWith(".xcz")
        var title = f.name
            .replace(Regex("\\[[0-9A-Fa-f]{16}\\]"), "")
            .replace(Regex("\\[v\\d+\\]", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\([^)]*GB\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\.(nsp|xci|nro|nsz|xcz)$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (title.isEmpty()) title = f.name
        return Rom(f, title, id, f.length(), update, compressed)
    }
}
