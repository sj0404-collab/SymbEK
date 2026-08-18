package dev.symbiosis.kenji

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/** Fix firmware trees in place. Never move the original dump. */
object PathFix {
    fun repairTree(root: File?) {
        if (root == null || !root.isDirectory) return
        try {
            restoreStash(root)
            flattenNested(File(root, "bis/system/Contents/registered"))
            dropBroken(File(root, "bis/system/Contents/registered"))
            File(root, "bis/system/Contents/registered").mkdirs()
            File(root, "system").mkdirs()
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "pathfix", t)
        }
    }

    private fun restoreStash(root: File) {
        val registered = File(root, "bis/system/Contents/registered")
        val stash = File(root, "bis/system/Contents/registered.stash")
        val junk = File(root, "bis/system/Contents/registered.junk")
        if (countKenji(registered) < 10 && countKenji(stash) >= 10) {
            if (registered.exists()) merge(stash, registered) else stash.renameTo(registered)
        }
        if (junk.isDirectory) merge(junk, registered)
    }

    private fun flattenNested(registered: File) {
        val dirs = registered.listFiles() ?: return
        for (d in dirs) {
            val inner = File(d, "00")
            if (inner.isDirectory) {
                val deep = File(inner, "00")
                if (deep.isFile && deep.length() > 1000) {
                    val flat = File(d, "00.flat")
                    if (deep.renameTo(flat)) {
                        inner.deleteRecursively()
                        flat.renameTo(File(d, "00"))
                    }
                }
            }
            d.listFiles()?.forEach { kid ->
                if (kid.name.endsWith(".part") || kid.name.contains(".part-")) kid.delete()
            }
        }
    }

    private fun dropBroken(registered: File) {
        val dirs = registered.listFiles() ?: return
        for (d in dirs) {
            val inner = File(d, "00")
            if (isLink(inner) && !(inner.isFile && inner.length() > 1000)) {
                inner.delete()
                if (d.list().isNullOrEmpty()) d.delete()
            }
        }
    }

    private fun merge(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        from.listFiles()?.forEach { kid ->
            val dest = File(to, kid.name)
            if (!dest.exists()) kid.renameTo(dest)
        }
    }

    private fun countKenji(registered: File): Int {
        val dirs = registered.listFiles() ?: return 0
        return dirs.count { d ->
            d.isDirectory && File(d, "00").let { it.isFile && it.length() > 1000 }
        }
    }

    private fun isLink(f: File): Boolean = try {
        OsConstants.S_ISLNK(Os.lstat(f.absolutePath).st_mode)
    } catch (_: Throwable) {
        false
    }
}
