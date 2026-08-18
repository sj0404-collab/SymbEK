package dev.symbiosis.kenji

import android.content.Context
import android.preference.PreferenceManager
import java.io.File
import java.util.Locale

/** Mods / saves / cheats stay on disk. We only list them in-place. */
object GameExtra {
    fun readyToStart(context: Context): Boolean =
        DataSeed.keysOk(context) && DataSeed.firmwareNca(context) >= 5 && coversOk(context)

    fun coversOk(context: Context): Boolean {
        for (dir in gameDirs(context)) {
            if (countGames(dir, 0) > 0) return true
        }
        return false
    }

    private fun gameDirs(context: Context): List<File> {
        val out = ArrayList<File>()
        val p = PreferenceManager.getDefaultSharedPreferences(context)
        p.getString("gameFolderPath", null)?.let { if (it.isNotBlank()) out.add(File(it)) }
        try {
            val uri = p.getString("gameFolder", null)
            if (!uri.isNullOrBlank() && uri.startsWith("/")) out.add(File(uri))
        } catch (_: Throwable) {
        }
        val sd = android.os.Environment.getExternalStorageDirectory()
        out.add(File(sd, "Download/ed"))
        return out
    }

    private fun countGames(dir: File, depth: Int): Int {
        if (depth > 2 || !dir.isDirectory) return 0
        var n = 0
        val kids = dir.listFiles() ?: return 0
        for (f in kids) {
            val low = f.name.lowercase(Locale.US)
            if (f.isFile && (low.endsWith(".nsp") || low.endsWith(".xci") ||
                    low.endsWith(".nsz") || low.endsWith(".xcz"))
            ) {
                n++
                if (n > 0) return n
            } else if (f.isDirectory && depth < 2 && !f.name.startsWith(".")) {
                n += countGames(f, depth + 1)
                if (n > 0) return n
            }
        }
        return n
    }

    data class Bucket(val title: String, val path: String, val count: Int, val bytes: Long)

    fun lastTitleId(context: Context): String {
        val p = PreferenceManager.getDefaultSharedPreferences(context)
        for (k in arrayOf("lastTitleId", "titleId", "currentTitleId", "gameTitleId")) {
            val v = p.getString(k, "") ?: ""
            if (v.length >= 8) return v.uppercase(Locale.US)
        }
        return ""
    }

    fun scan(context: Context): List<Bucket> {
        val out = ArrayList<Bucket>()
        val roots = ArrayList<File>()
        roots.add(DataSeed.playHome(context))
        DataSeed.edenDir(context)?.let { roots.add(File(it)) }
        val id = lastTitleId(context)
        val rels = ArrayList<Pair<String, String>>()
        if (id.isNotEmpty()) {
            rels.add("моды load/$id" to "load/$id")
            rels.add("моды contents/$id" to "mods/contents/$id")
            rels.add("читы $id" to "load/$id/cheats")
            rels.add("читы atmosphere/$id" to "sdmc/atmosphere/contents/$id/cheats")
        }
        rels.add("моды load/" to "load")
        rels.add("моды mods/" to "mods")
        rels.add("читы cheats/" to "cheats")
        rels.add("сейвы bis/user/save" to "bis/user/save")
        rels.add("сейвы nand/user/save" to "nand/user/save")
        rels.add("сейвы sdmc/Nintendo/save" to "sdmc/Nintendo/save")
        val seen = HashSet<String>()
        for (root in roots) {
            for ((title, rel) in rels) {
                val dir = File(root, rel)
                val key = try {
                    dir.canonicalPath
                } catch (_: Exception) {
                    dir.absolutePath
                }
                if (!seen.add(key)) continue
                if (!dir.isDirectory) continue
                val (n, b) = tally(dir)
                if (n <= 0 && !dir.name.equals("save", true) && !rel.contains("cheat") && !rel.contains("load")) continue
                out.add(Bucket(title, dir.absolutePath, n, b))
            }
        }
        return out
    }

    fun report(context: Context): String {
        val rows = scan(context)
        if (rows.isEmpty()) {
            return "моды / сейвы / читы не найдены рядом с Eden и Kenji.\nположите их в load/<titleId>/, mods/contents/<titleId>/ или bis/user/save — без копии прошивки."
        }
        return rows.joinToString("\n") {
            "${it.title}: ${it.count} шт · ${BootLog.human(it.bytes)}\n  ${it.path}"
        }
    }

    private fun tally(dir: File): Pair<Int, Long> {
        var n = 0
        var b = 0L
        val kids = dir.listFiles() ?: return 0 to 0L
        for (f in kids) {
            if (f.name.startsWith(".")) continue
            n++
            b += if (f.isFile) f.length() else sizeOf(f, 0)
        }
        return n to b
    }

    private fun sizeOf(dir: File, depth: Int): Long {
        if (depth > 4) return 0L
        var s = 0L
        dir.listFiles()?.forEach { f ->
            s += if (f.isFile) f.length() else sizeOf(f, depth + 1)
        }
        return s
    }
}
