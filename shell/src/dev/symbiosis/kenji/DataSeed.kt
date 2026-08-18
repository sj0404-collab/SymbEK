package dev.symbiosis.kenji

import android.content.Context
import android.os.Environment
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Keys + firmware where official GameHost looks.
 * Firmware is never copied: each bis/.../{id}.nca/00 is a symlink or hardlink.
 */
object DataSeed {
    private const val PREF = "kenji_space"
    private const val PREF_SRC = "fw_source"
    private const val PREF_MODE = "fw_mode"

    fun appPath(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    fun ensure(context: Context) {
        try {
            ensureInner(context)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "ensure", t)
        }
    }

    private fun ensureInner(context: Context) {
        val dest = appPath(context)
        restoreOrphanStash(dest)
        val destKeys = File(dest, "system/prod.keys")
        for (src in sources(context)) {
            restoreOrphanStash(src)
            harvestKeys(src, destKeys)
        }
        harvestLooseKeys(destKeys)

        val destReg = File(dest, "bis/system/Contents/registered")
        if (countKenji(destReg) >= 10) {
            remember(context, destReg.absolutePath, modeOf(destReg), countKenji(destReg))
            writeReport(context, dest)
            return
        }
        for (src in sources(context)) {
            val kenji = File(src, "bis/system/Contents/registered")
            val stash = File(src, "bis/system/Contents/registered.stash")
            val eden = File(src, "nand/system/Contents/registered")
            val nand = File(src, "nand")
            when {
                countKenji(kenji) >= 10 && !samePath(kenji, destReg) &&
                    bridgeFirmware(context, kenji, destReg) -> return
                countKenji(stash) >= 10 && bridgeFirmware(context, stash, destReg) -> return
                countAnyNca(eden) >= 10 && bridgeFirmware(context, eden, destReg) -> return
                countAnyNca(nand) >= 10 && countAnyNca(eden) < 10 &&
                    bridgeFirmware(context, nand, destReg) -> return
            }
        }
        writeReport(context, dest)
    }

    fun keysOk(context: Context): Boolean {
        val keys = File(appPath(context), "system/prod.keys")
        return keys.isFile && keys.length() > 100
    }

    fun firmwareNca(context: Context): Int =
        countKenji(File(appPath(context), "bis/system/Contents/registered"))

    fun firmwareSource(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_SRC, "") ?: ""

    fun firmwareMode(context: Context): String =
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_MODE, "") ?: ""

    private fun harvestKeys(dir: File, destKeys: File) {
        if (!dir.isDirectory) return
        listOf(
            "prod.keys", "keys/prod.keys", "system/prod.keys",
            "kenji/system/prod.keys", "files/keys/prod.keys", "files/system/prod.keys",
        ).forEach { copyKey(File(dir, it), destKeys) }
    }

    private fun harvestLooseKeys(destKeys: File) {
        val sd = Environment.getExternalStorageDirectory()
        listOf(
            "prod.keys", "keys/prod.keys", "Download/prod.keys", "Download/keys/prod.keys",
            "Switch/prod.keys", "Switch/keys/prod.keys", "Kenji/prod.keys",
            "Kenji/system/prod.keys", "Eden/prod.keys", "Eden/keys/prod.keys",
            "Eden/files/keys/prod.keys",
        ).forEach { copyKey(File(sd, it), destKeys) }
        File(sd, "Download").listFiles()?.forEach { f ->
            if (f.isFile && f.name.equals("prod.keys", true)) copyKey(f, destKeys)
            if (f.isDirectory) harvestKeys(f, destKeys)
        }
    }

    private fun sources(context: Context): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        val rel = arrayOf(
            "Download/ed/Eden/files", "Download/ed/Eden", "Eden/files", "Eden",
            "Switch", "Kenji",
            "Android/data/org.kenjinx.android/files",
            "Android/data/dev.symbiosis.kenji/files",
            "Android/data/dev.eden.eden_emulator/files",
            "Android/data/org.yuzu.yuzu_emu/files",
            "Android/data/org.citron.citron_emu/files",
        )
        for (r in rel) {
            val f = File(sd, r)
            if (f.isDirectory) out.add(f)
        }
        scanKids(File(sd, "Download"), out)
        scanKids(sd, out)
        return out
    }

    private fun scanKids(dir: File, out: MutableList<File>) {
        val kids = dir.listFiles() ?: return
        for (f in kids) {
            if (!f.isDirectory) continue
            if (looksData(f) && f !in out) out.add(f)
            val files = File(f, "files")
            if (looksData(files) && files !in out) out.add(files)
        }
    }

    private fun looksData(f: File): Boolean =
        f.isDirectory && (
            File(f, "system/prod.keys").isFile ||
                File(f, "keys/prod.keys").isFile ||
                File(f, "bis").isDirectory ||
                File(f, "nand").isDirectory ||
                File(f, "load").isDirectory
            )

    fun restoreOrphanStash(root: File) {
        try {
            if (!root.isDirectory) return
            val registered = File(root, "bis/system/Contents/registered")
            val stash = File(root, "bis/system/Contents/registered.stash")
            val junk = File(root, "bis/system/Contents/registered.junk")
            if (countKenji(registered) < 10 && countKenji(stash) >= 10) {
                if (registered.exists()) mergeInto(stash, registered) else stash.renameTo(registered)
            }
            if (junk.isDirectory) mergeInto(junk, registered)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "stash", t)
        }
    }

    private fun mergeInto(from: File, to: File) {
        if (!from.isDirectory) return
        to.mkdirs()
        from.listFiles()?.forEach { kid ->
            val dest = File(to, kid.name)
            if (!dest.exists()) kid.renameTo(dest)
        }
    }

    private fun copyKey(from: File, to: File) {
        if (!from.isFile || from.length() < 100) return
        if (to.isFile && to.length() == from.length()) return
        to.parentFile?.mkdirs()
        try {
            FileInputStream(from).use { input ->
                FileOutputStream(to).use { output ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n <= 0) break
                        output.write(buf, 0, n)
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun bridgeFirmware(context: Context, srcReg: File, destReg: File): Boolean {
        destReg.mkdirs()
        val entries = srcReg.listFiles() ?: return false
        var mode = "ярлыки"
        for (entry in entries) {
            val payload: File
            val name: String
            when {
                entry.isFile && entry.name.lowercase(Locale.US).endsWith(".nca") && entry.length() > 1000 -> {
                    payload = entry
                    name = entry.name
                }
                entry.isDirectory && entry.name.lowercase(Locale.US).endsWith(".nca") -> {
                    val inner = File(entry, "00")
                    if (!inner.isFile || inner.length() <= 1000) continue
                    payload = inner
                    name = entry.name
                }
                else -> continue
            }
            val destDir = File(destReg, name)
            val dest = File(destDir, "00")
            if (isReadableNca(dest) && isShortcut(dest)) continue
            if (dest.isFile && !isShortcut(dest) && dest.length() == payload.length()) {
                if (!dest.delete()) continue
            }
            destDir.mkdirs()
            val how = shortcut(payload, dest) ?: continue
            if (how == "hardlink") mode = "жёсткие ссылки"
        }
        val n = countKenji(destReg)
        if (n < 10) return false
        remember(context, srcReg.absolutePath, mode, n)
        writeReport(context, appPath(context))
        Log.i("KenjiSpace", "fw $mode $n ← ${srcReg.absolutePath}")
        return true
    }

    private fun shortcut(src: File, dest: File): String? {
        if (!src.exists()) return null
        if (dest.exists() && !dest.delete()) return null
        try {
            Os.symlink(src.absolutePath, dest.absolutePath)
            if (isReadableNca(dest) || isShortcut(dest)) return "symlink"
        } catch (t: Throwable) {
            Log.w("KenjiSpace", "symlink ${dest.name}", t)
        }
        try {
            Os.link(src.absolutePath, dest.absolutePath)
            if (isReadableNca(dest)) return "hardlink"
        } catch (t: Throwable) {
            Log.w("KenjiSpace", "link ${dest.name}", t)
        }
        return null
    }

    private fun isShortcut(f: File): Boolean = try {
        val st = Os.lstat(f.absolutePath)
        OsConstants.S_ISLNK(st.st_mode) || st.st_nlink > 1
    } catch (_: Throwable) {
        false
    }

    private fun isReadableNca(f: File): Boolean = try {
        f.isFile && f.length() > 1000
    } catch (_: Throwable) {
        false
    }

    private fun countKenji(registered: File?): Int {
        val dirs = registered?.listFiles() ?: return 0
        return dirs.count { d -> d.isDirectory && isReadableNca(File(d, "00")) }
    }

    private fun countAnyNca(dir: File?): Int {
        val kids = dir?.listFiles() ?: return 0
        var n = 0
        for (f in kids) {
            val low = f.name.lowercase(Locale.US)
            if (f.isFile && low.endsWith(".nca") && f.length() > 1000) n++
            else if (f.isDirectory && low.endsWith(".nca") && isReadableNca(File(f, "00"))) n++
        }
        return n
    }

    private fun samePath(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) {
        a.absolutePath == b.absolutePath
    }

    private fun modeOf(destReg: File): String {
        val dirs = destReg.listFiles() ?: return ""
        var links = 0
        var files = 0
        for (d in dirs) {
            val inner = File(d, "00")
            if (!inner.exists()) continue
            files++
            if (isShortcut(inner)) links++
        }
        return when {
            files == 0 -> ""
            links == files -> "ярлыки"
            links > 0 -> "ярлыки + файлы"
            else -> "файлы"
        }
    }

    private fun remember(context: Context, source: String, mode: String, nca: Int) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(PREF_SRC, source)
            .putString(PREF_MODE, mode)
            .putInt("fw_nca", nca)
            .commit()
    }

    private fun writeReport(context: Context, dest: File) {
        try {
            val report = File(dest, "system/firmware_source.txt")
            report.parentFile?.mkdirs()
            report.writeText(
                "Kenji читает: ${File(dest, "bis/system/Contents/registered").absolutePath}\n" +
                    "Источник: ${firmwareSource(context)}\n" +
                    "Как: ${firmwareMode(context)} (без копии NCA)\n" +
                    "NCA: ${firmwareNca(context)}\n",
            )
        } catch (_: Exception) {
        }
    }
}
