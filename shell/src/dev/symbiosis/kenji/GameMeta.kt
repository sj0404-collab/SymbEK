package dev.symbiosis.kenji

import java.io.File

/** Live stats from whatever folders the user pointed at. */
object GameMeta {
    fun saveBytes(context: android.content.Context, rom: GameRom): Long {
        val id = rom.titleId
        val roots = ArrayList<File>()
        roots.add(FolderHub.savesDir(context))
        roots.add(File(DataSeed.playHome(context), "bis/user/save"))
        roots.add(File(DataSeed.playHome(context), "nand/user/save"))
        DataSeed.edenDir(context)?.let {
            roots.add(File(it, "bis/user/save"))
            roots.add(File(it, "nand/user/save"))
        }
        if (id.isNotEmpty()) {
            roots.add(File(DataSeed.playHome(context), "load/$id"))
            DataSeed.edenDir(context)?.let { roots.add(File(it, "load/$id")) }
        }
        var sum = 0L
        val seen = HashSet<String>()
        for (r in roots) {
            val key = try { r.canonicalPath } catch (_: Exception) { r.absolutePath }
            if (!seen.add(key) || !r.exists()) continue
            sum += if (id.isEmpty()) sizeOf(r, 0) else sizeMatching(r, id, 0)
        }
        return sum
    }

    fun photos(context: android.content.Context, rom: GameRom): Int {
        val dirs = listOf(
            File(DataSeed.playHome(context), "screenshots"),
            File(DataSeed.playHome(context), "album"),
            File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Kenji"),
            File(android.os.Environment.getExternalStorageDirectory(), "Pictures/Kenji Space"),
        )
        val id = rom.titleId.lowercase()
        var n = 0
        for (d in dirs) {
            val kids = d.listFiles() ?: continue
            n += kids.count { f ->
                f.isFile && (id.isEmpty() || f.name.lowercase().contains(id) ||
                    f.name.lowercase().contains(rom.title.lowercase().take(6)))
            }
        }
        return n
    }

    fun shaderBytes(context: android.content.Context, rom: GameRom): Long {
        val id = rom.titleId
        val dirs = ArrayList<File>()
        val home = DataSeed.playHome(context)
        if (id.isNotEmpty()) {
            dirs.add(File(home, "games/$id"))
            dirs.add(File(home, "bis/cache/$id"))
            dirs.add(File(home, "cache/$id"))
        }
        DataSeed.edenDir(context)?.let { e ->
            if (id.isNotEmpty()) dirs.add(File(e, "games/$id"))
        }
        var sum = 0L
        dirs.forEach { if (it.exists()) sum += sizeOf(it, 0) }
        return sum
    }

    fun modsLine(context: android.content.Context, rom: GameRom): String {
        if (rom.titleId.isEmpty()) return "моды / читы\nнет"
        val report = GameExtra.report(context)
        return if (report.contains(rom.titleId)) "моды / читы\nмод · ${rom.titleId}"
        else "моды / читы\nнет"
    }

    private fun sizeMatching(dir: File, id: String, depth: Int): Long {
        if (depth > 5 || !dir.exists()) return 0L
        val low = dir.name.lowercase()
        if (dir.isFile) {
            return if (low.contains(id.lowercase())) dir.length() else 0L
        }
        if (low.contains(id.lowercase()) || depth == 0) {
            var s = 0L
            dir.listFiles()?.forEach { s += if (low.contains(id.lowercase()) && it.isFile) it.length() else sizeMatching(it, id, depth + 1) }
            return s
        }
        var s = 0L
        dir.listFiles()?.forEach { s += sizeMatching(it, id, depth + 1) }
        return s
    }

    private fun sizeOf(dir: File, depth: Int): Long {
        if (depth > 5) return 0L
        if (dir.isFile) return dir.length()
        var s = 0L
        dir.listFiles()?.forEach { s += if (it.isFile) it.length() else sizeOf(it, depth + 1) }
        return s
    }
}
