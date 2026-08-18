package dev.symbiosis.kenji

import android.content.Context
import android.preference.PreferenceManager
import java.io.File

/** All user folders. Changing any of them rewires games, keys, firmware, saves. */
object FolderHub {
    private const val SPACE = "kenji_space"
    private const val FOLDERS = "kenji_folders"
    const val SAVES = "saves_dir"

    fun gamesDirs(context: Context): List<File> {
        val out = LinkedHashSet<File>()
        val stored = context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE)
            .getStringSet("uris", emptySet()) ?: emptySet()
        stored.forEach { s ->
            if (s.startsWith("/")) File(s).takeIf { it.isDirectory }?.let { out.add(it) }
        }
        val off = PreferenceManager.getDefaultSharedPreferences(context)
        off.getString("gameFolderPath", null)?.let { p ->
            File(p).takeIf { it.isDirectory }?.let { out.add(it) }
        }
        return out.toList()
    }

    fun revision(context: Context): Long =
        context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE).getLong("rev", 0L)

    private fun bump(context: Context) {
        val p = context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE)
        p.edit().putLong("rev", p.getLong("rev", 0L) + 1L).commit()
    }

    fun addGamesDir(context: Context, path: String) {
        val f = File(path)
        if (!f.isDirectory) return
        val p = context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE)
        val next = LinkedHashSet(p.getStringSet("uris", emptySet()) ?: emptySet())
        next.add(f.absolutePath)
        p.edit().putStringSet("uris", next).commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit()
            .putString("gameFolderPath", f.absolutePath)
            .putString("gameFolder", f.absolutePath)
            .commit()
        bump(context)
    }

    fun removeGamesDir(context: Context, path: String) {
        val p = context.getSharedPreferences(FOLDERS, Context.MODE_PRIVATE)
        val next = LinkedHashSet(p.getStringSet("uris", emptySet()) ?: emptySet())
        next.remove(path)
        p.edit().putStringSet("uris", next).commit()
        val off = PreferenceManager.getDefaultSharedPreferences(context)
        if (off.getString("gameFolderPath", "") == path) {
            off.edit().remove("gameFolderPath").remove("gameFolder").commit()
        }
        bump(context)
    }

    fun setEden(context: Context, path: String) {
        DataSeed.setEdenDir(context, path)
        DataSeed.ensure(context)
    }

    fun edenPath(context: Context): String =
        DataSeed.edenDir(context) ?: DataSeed.playHome(context).absolutePath

    fun setSaves(context: Context, path: String) {
        File(path).mkdirs()
        context.getSharedPreferences(SPACE, Context.MODE_PRIVATE).edit()
            .putString(SAVES, path).commit()
        bump(context)
    }

    fun savesDir(context: Context): File {
        val p = context.getSharedPreferences(SPACE, Context.MODE_PRIVATE).getString(SAVES, "")
        if (!p.isNullOrBlank()) {
            val f = File(p)
            if (f.isDirectory) return f
        }
        val home = DataSeed.playHome(context)
        val a = File(home, "bis/user/save")
        if (a.isDirectory) return a
        val b = File(home, "nand/user/save")
        if (b.isDirectory) return b
        return a
    }

    fun applyAfterFolderChange(context: Context) {
        try {
            AccessFix.repair(context)
            DataSeed.ensure(context)
        } catch (_: Throwable) {
        }
    }

    fun hasRom(dir: File, depth: Int): Boolean {
        if (depth > 3 || !dir.isDirectory) return false
        val kids = dir.listFiles() ?: return false
        for (f in kids) {
            if (f.isFile && GameShelf.isRom(f.name)) return true
            if (f.isDirectory && depth < 3 && hasRom(f, depth + 1)) return true
        }
        return false
    }
}
