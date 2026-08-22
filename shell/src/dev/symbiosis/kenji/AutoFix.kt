package dev.symbiosis.kenji

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import java.io.File

/**
 * The original Space auto-fix, without crashing official Kenji:
 * stash/junk to registered, keys/ to system/prod.keys,
 * Eden nand .nca to playHome bis id.nca/00 as shortcuts (no copy).
 */
object AutoFix {
    @Volatile var lastLog: String = ""
        private set

    @Volatile var lastErr: String = ""

    fun run(context: Context) {
        val lines = ArrayList<String>()
        lastErr = ""
        try {
            BootLog.add("AutoFix.start")
            val play = DataSeed.playHome(context)
            val eden = findEden(context)
            val roots = listOfNotNull(play, eden)
            for (root in roots) {
                val n = restoreStash(root)
                if (n > 0) {
                    lines.add("stash→registered $n в ${root.absolutePath}")
                    BootLog.add("stash→registered $n · ${root.absolutePath}")
                }
            }
            val keys = File(play, "system/prod.keys")
            if (eden != null) {
                copyKey(File(eden, "keys/prod.keys"), keys)
                copyKey(File(eden, "system/prod.keys"), keys)
            }
            val appKeys = File(context.getExternalFilesDir(null), "system/prod.keys")
            copyKey(appKeys, keys)
            copyKey(File(Environment.getExternalStorageDirectory(), "keys/prod.keys"), keys)
            if (keys.isFile && keys.length() > 100) {
                lines.add("ключи ${keys.length()} Б (${BootLog.human(keys.length())})")
                BootLog.add("ключи ${keys.absolutePath} ${keys.length()} Б")
            } else {
                lines.add("ключи не найдены в ${keys.absolutePath}")
                BootLog.add("ключи нет → ${keys.absolutePath}")
            }

            val destReg = File(play, "bis/system/Contents/registered")
            var linked = 0
            if (eden != null) {
                val kenjiReg = File(eden, "bis/system/Contents/registered")
                val nandReg = File(eden, "nand/system/Contents/registered")
                val nand = File(eden, "nand")
                val kN = countKenji(kenjiReg)
                val nN = countLoose(nandReg)
                val nL = countLoose(nand)
                val kB = BootLog.registeredBytes(kenjiReg)
                val nB = BootLog.registeredBytes(nandReg)
                lines.add("Eden ${eden.absolutePath}")
                lines.add("источник kenji-bis: $kN NCA · ${BootLog.human(kB)}")
                lines.add("источник eden-nand: $nN NCA · ${BootLog.human(nB)}")
                BootLog.add("источник kenji $kN NCA ${BootLog.human(kB)} · nand $nN NCA ${BootLog.human(nB)}")
                if (samePath(kenjiReg, destReg) && kN >= 5) {
                    linked = kN
                    lines.add("игра читает Eden bis на месте (без моста)")
                    BootLog.add("без моста: dest=источник $kN NCA")
                } else {
                    destReg.mkdirs()
                    when {
                        kN >= 5 -> linked = linkTree(kenjiReg, destReg)
                        nN >= 5 -> linked = linkLoose(nandReg, destReg)
                        nL >= 5 -> linked = linkLoose(nand, destReg)
                    }
                    if (linked == 0 && lastErr.isNotEmpty()) {
                        lines.add("мост не встал: $lastErr")
                        BootLog.add("мост FAIL $lastErr")
                    } else {
                        BootLog.add("мост ярлыков $linked")
                    }
                }
            }
            val n = countKenji(destReg)
            val bytes = BootLog.registeredBytes(destReg)
            val line = if (n >= 5) {
                "Kenji bis: $n NCA · ${BootLog.human(bytes)} (ярлыки $linked)"
            } else {
                "Kenji bis пуст после моста ($n) · ${destReg.absolutePath}"
            }
            lines.add(line)
            BootLog.add(line)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "autofix", t)
            lines.add("ошибка автопочинки: ${t.message}")
            BootLog.add("AutoFix исключение ${t.message}")
        }
        lastLog = lines.joinToString("\n")
        Log.i("KenjiSpace", "autofix\n$lastLog")
    }

    private fun findEden(context: Context): File? {
        DataSeed.edenDir(context)?.let { p ->
            val f = File(p)
            if (f.isDirectory) return f
        }
        val sd = Environment.getExternalStorageDirectory()
        val guesses = listOf(
            "Download/ed/Eden/files", "Download/ed/Eden", "Eden/files", "Eden",
            "Android/data/dev.eden.eden_emulator/files",
        )
        for (rel in guesses) {
            val f = File(sd, rel)
            if (File(f, "nand").isDirectory || File(f, "bis").isDirectory || File(f, "keys").isDirectory) return f
        }
        return FirmwareHunt.lastHits.firstOrNull()?.dir?.let { d ->
            var p: File? = d
            repeat(5) {
                val cur = p ?: return@repeat
                if (File(cur, "nand").isDirectory || File(cur, "keys").isDirectory) return cur
                p = cur.parentFile
            }
            d
        }
    }

    /** registered.stash / junk → registered. Returns how many entries came back. */
    fun restoreStash(root: File): Int {
        var n = 0
        try {
            val parent = File(root, "bis/system/Contents")
            if (!parent.isDirectory) {
                val alt = File(root, "system/Contents")
                if (alt.isDirectory) return restoreStashAt(alt)
                return 0
            }
            n = restoreStashAt(parent)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "stash", t)
        }
        return n
    }

    private fun restoreStashAt(contents: File): Int {
        val registered = File(contents, "registered")
        val stash = File(contents, "registered.stash")
        val junk = File(contents, "registered.junk")
        var n = 0
        if (countKenji(registered) < 5 && countKenji(stash) >= 5) {
            registered.mkdirs()
            stash.listFiles()?.forEach { kid ->
                val dest = File(registered, kid.name)
                if (!dest.exists() && kid.renameTo(dest)) n++
            }
        }
        if (junk.isDirectory) {
            registered.mkdirs()
            junk.listFiles()?.forEach { kid ->
                val dest = File(registered, kid.name)
                if (!dest.exists() && kid.renameTo(dest)) n++
            }
        }
        return n
    }

    private fun linkTree(srcReg: File, destReg: File): Int {
        destReg.mkdirs()
        var n = 0
        srcReg.listFiles()?.forEach { entry ->
            if (!entry.isDirectory || !entry.name.lowercase().endsWith(".nca")) return@forEach
            val payload = File(entry, "00")
            if (!payload.isFile || payload.length() <= 1000) return@forEach
            if (placeLink(payload, File(destReg, entry.name))) n++
        }
        return n
    }

    private fun linkLoose(srcDir: File, destReg: File): Int {
        destReg.mkdirs()
        var n = 0
        srcDir.listFiles()?.forEach { f ->
            if (!f.isFile || !f.name.lowercase().endsWith(".nca") || f.length() <= 1000) return@forEach
            if (placeLink(f, File(destReg, f.name))) n++
        }
        return n
    }

    /** destDir is {id}.nca folder; 00 inside becomes a shortcut to payload. */
    private fun placeLink(payload: File, destDir: File): Boolean {
        destDir.mkdirs()
        val dest = File(destDir, "00")
        if (dest.isFile && dest.length() == payload.length()) return true
        if (dest.exists()) {
            if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
        }
        return try {
            Os.symlink(payload.absolutePath, dest.absolutePath)
            dest.exists()
        } catch (t: Throwable) {
            lastErr = "symlink ${err(t)} · копию не делаю"
            false
        }
    }

    private fun err(t: Throwable): String {
        val e = t as? ErrnoException
        return if (e != null) "errno=${e.errno} ${e.message}" else (t.message ?: t.javaClass.simpleName)
    }

    private fun copyKey(from: File, to: File) {
        if (!from.isFile || from.length() < 100) return
        if (to.isFile && to.length() == from.length()) return
        to.parentFile?.mkdirs()
        try {
            from.inputStream().use { input -> to.outputStream().use { input.copyTo(it) } }
        } catch (_: Exception) {
        }
    }

    private fun countKenji(registered: File?): Int {
        val dirs = registered?.listFiles() ?: return 0
        return dirs.count { d -> d.isDirectory && File(d, "00").let { it.isFile && it.length() > 1000 } }
    }

    private fun countLoose(dir: File?): Int {
        val kids = dir?.listFiles() ?: return 0
        return kids.count { it.isFile && it.name.lowercase().endsWith(".nca") && it.length() > 1000 }
    }

    private fun samePath(a: File, b: File): Boolean = try {
        a.canonicalPath == b.canonicalPath
    } catch (_: Exception) {
        a.absolutePath == b.absolutePath
    }
}
