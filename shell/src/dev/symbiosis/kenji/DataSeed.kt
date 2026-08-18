package dev.symbiosis.kenji

import android.content.Context
import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.Locale

/**
 * Keys + firmware where official GameHost looks.
 * Firmware is never copied: either GameHost reads Eden in-place (AppPath),
 * or each bis/.../{id}.nca/00 is a symlink or hardlink.
 */
object DataSeed {
    @Volatile var allowEnsure: Boolean = true
    @Volatile var pointedEarly: Boolean = false
        private set

    private const val PREF = "kenji_space"
    private const val PREF_SRC = "fw_source"
    private const val PREF_MODE = "fw_mode"
    private const val PREF_EDEN = "eden_dir"
    private const val PREF_KENJI = "kenji_dir"

    fun appPath(context: Context): File =
        context.getExternalFilesDir(null) ?: context.filesDir

    /**
     * What official GameHost actually reads.
     * If AppPath already points at Eden/files (set before the kernel), use that.
     * Never the empty /sdcard/Kenji.
     */
    fun playHome(context: Context): File {
        try {
            val p = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            for (key in arrayOf("AppPath", "appPath", "dataPath")) {
                val v = p.getString(key, null) ?: continue
                if (v.isBlank()) continue
                val f = File(v)
                if (!f.isDirectory) continue
                val kenjiEmpty = f.name.equals("Kenji", true) &&
                    countKenji(File(f, "bis/system/Contents/registered")) < 5 &&
                    !File(f, "nand").isDirectory
                if (kenjiEmpty) continue
                if (File(f, "bis").isDirectory || File(f, "nand").isDirectory ||
                    File(f, "system").isDirectory || File(f, "keys").isDirectory
                ) {
                    return f
                }
            }
        } catch (_: Throwable) {
        }
        return appPath(context)
    }

    fun userKenjiDir(context: Context): File? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_KENJI, "")
        if (p.isNullOrBlank()) return null
        val f = File(p)
        return if (f.isDirectory || f.mkdirs()) f else null
    }

    /** Visible Kenji folder for shortcuts, or playHome if none. */
    fun kenjiHome(context: Context): File = userKenjiDir(context) ?: playHome(context)

    fun edenDir(context: Context): String? {
        val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(PREF_EDEN, "")
        return p?.takeIf { it.isNotBlank() && File(it).isDirectory }
    }

    fun setEdenDir(context: Context, path: String) {
        var p = path
        val files = File(path, "files")
        if (File(path, "nand").isDirectory.not() && files.isDirectory) p = files.absolutePath
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(PREF_EDEN, p).commit()
        pointHomeEarly(context)
        ensure(context)
    }

    fun setKenjiDir(context: Context, path: String) {
        File(path).mkdirs()
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(PREF_KENJI, path).commit()
        ensure(context)
    }

    /**
     * Cheap. Runs in SeedProvider before KenjinxApplication.
     * Official 1.0.16 worked when AppPath = Eden/files (234 NCA in place).
     * Do not call this again while the game is loading.
     */
    fun pointHomeEarly(context: Context) {
        try {
            BootLog.add("pointHomeEarly")
            var eden = edenDir(context)
            if (eden == null) {
                val sd = Environment.getExternalStorageDirectory()
                val guesses = arrayOf(
                    "Download/ed/Eden/files",
                    "Download/ed/Eden",
                    "Eden/files",
                    "Eden",
                )
                for (rel in guesses) {
                    val f = File(sd, rel)
                    val files = File(f, "files")
                    val home = when {
                        File(f, "nand").isDirectory || File(f, "bis").isDirectory || File(f, "keys").isDirectory -> f
                        files.isDirectory && (
                            File(files, "nand").isDirectory ||
                                File(files, "bis").isDirectory ||
                                File(files, "keys").isDirectory
                            ) -> files
                        else -> null
                    } ?: continue
                    context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                        .edit().putString(PREF_EDEN, home.absolutePath).commit()
                    eden = home.absolutePath
                    BootLog.add("нашёл Eden $eden")
                    break
                }
            } else {
                BootLog.add("Eden из настроек $eden")
            }
            ensureLogsDir(appPath(context))
            ensureLogsDir(context.filesDir)
            if (eden == null) {
                BootLog.add("Eden нет — AppPath остаётся ${appPath(context).absolutePath}")
                return
            }
            val home = File(eden)
            if (!home.isDirectory) {
                BootLog.add("Eden путь не папка: $eden")
                return
            }
            val official = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            val e = official.edit()
            listOf("AppPath", "appPath", "dataPath").forEach { key ->
                e.putString(key, home.absolutePath)
            }
            e.commit()
            pointedEarly = true
            File(home, "system").mkdirs()
            ensureLogsDir(home)
            copyKey(File(appPath(context), "system/prod.keys"), File(home, "system/prod.keys"))
            copyKey(File(home, "keys/prod.keys"), File(home, "system/prod.keys"))
            val keys = File(home, "system/prod.keys")
            val kenjiN = countKenji(File(home, "bis/system/Contents/registered"))
            val kenjiB = BootLog.registeredBytes(File(home, "bis/system/Contents/registered"))
            val nandN = countAnyNca(File(home, "nand/system/Contents/registered"))
            val nandB = BootLog.registeredBytes(File(home, "nand/system/Contents/registered"))
            remember(
                context,
                File(home, "bis").absolutePath,
                "на месте (AppPath=Eden)",
                kenjiN,
            )
            BootLog.add("AppPath → ${home.absolutePath}")
            BootLog.add(
                "на месте: ключи ${if (keys.isFile) "${keys.length()} Б" else "нет"} · " +
                    "bis $kenjiN NCA ${BootLog.human(kenjiB)} · nand $nandN NCA ${BootLog.human(nandB)}",
            )
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "pointHomeEarly", t)
            BootLog.add("pointHomeEarly ошибка ${t.message}")
        }
    }

    private fun ensureLogsDir(root: File) {
        try {
            File(root, ".config/Ryujinx/Logs").mkdirs()
        } catch (_: Exception) {
        }
    }

    fun ensure(context: Context) {
        if (!allowEnsure) {
            BootLog.add("ensure пропущен (игра)")
            return
        }
        try {
            ensureInner(context)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "ensure", t)
            BootLog.add("ensure ошибка ${t.message}")
        }
    }

    private fun ensureInner(context: Context) {
        BootLog.add("ensure.start")
        val dest = playHome(context)
        BootLog.add("playHome ${dest.absolutePath}")
        repairAppPath(context)
        AccessFix.repair(context)
        BootLog.add("AccessFix ${AccessFix.statusLine(context)}")
        autoDiscoverEden(context)
        AutoFix.run(context)
        val destKeys = File(dest, "system/prod.keys")
        for (src in sources(context)) {
            if (!isProtected(context, src)) PathFix.repairTree(src)
            restoreOrphanStash(src)
            harvestKeys(src, destKeys)
        }
        harvestLooseKeys(destKeys)
        BootLog.add("ключи после сбора ${if (destKeys.isFile) "${destKeys.length()} Б" else "нет"}")

        FirmwareHunt.best(context)
        val playBis = File(dest, "bis")
        val destReg = File(playBis, "system/Contents/registered")
        val kenjiHit = FirmwareHunt.lastHits.firstOrNull { it.kenjiLayout && it.nca >= 5 }
        val anyHit = FirmwareHunt.lastHits.firstOrNull { it.nca >= 5 }
        var ok = countKenji(destReg) >= 5
        if (ok) {
            BootLog.add("bis уже ${countKenji(destReg)} NCA · ${BootLog.human(BootLog.registeredBytes(destReg))}")
            remember(context, destReg.absolutePath, modeOf(destReg).ifBlank { "на месте" }, countKenji(destReg))
        }
        if (!ok && kenjiHit != null) {
            val srcBis = bisRootOf(kenjiHit.dir)
            BootLog.add("bindDir ${srcBis.absolutePath} → ${playBis.absolutePath}")
            if (isProtected(context, playBis) && !samePath(srcBis, playBis)) {
                BootLog.add("не трогаю Eden bis (это оригинал)")
            } else {
                ok = bindDir(srcBis, playBis)
                if (!ok) ok = bindDir(srcBis, File(context.filesDir, "bis"))
                if (ok) remember(context, srcBis.absolutePath, "ярлык на всю bis/", firmwareNca(context))
                if (!ok) {
                    wipeEmptyBis(context, playBis)
                    destReg.mkdirs()
                    ok = bridgeFirmware(context, File(srcBis, "system/Contents/registered"), destReg)
                }
            }
        }
        if (!ok && anyHit != null && !samePath(anyHit.dir, destReg)) {
            if (!isProtected(context, playBis)) wipeEmptyBis(context, playBis)
            destReg.mkdirs()
            ok = bridgeFirmware(context, anyHit.dir, destReg)
            if (!ok) {
                remember(
                    context,
                    anyHit.dir.absolutePath,
                    "нашёл ${anyHit.nca} NCA, ярлыки не встали",
                    countKenji(destReg),
                )
                BootLog.add("ярлыки не встали, Kenji ${countKenji(destReg)} NCA")
            }
        } else if (!ok && countKenji(destReg) >= 5) {
            remember(context, destReg.absolutePath, modeOf(destReg), countKenji(destReg))
        }
        writePointer(context, dest)
        writeReport(context, dest)
        BootLog.add(
            "ensure.готово NCA=${firmwareNca(context)} · ${BootLog.human(firmwareBytes(context))} · " +
                firmwareMode(context),
        )
    }

    private fun isProtected(context: Context, f: File): Boolean {
        val path = try {
            f.canonicalPath
        } catch (_: Exception) {
            f.absolutePath
        }
        val eden = edenDir(context)
        if (eden != null) {
            val ep = try {
                File(eden).canonicalPath
            } catch (_: Exception) {
                eden
            }
            if (path == ep || path.startsWith("$ep/")) return true
        }
        if (path.contains("/Eden/")) return true
        if (path.contains("/nand/system/Contents/")) return true
        return false
    }

    private fun bisRootOf(dir: File): File {
        var p: File? = dir
        repeat(6) {
            val cur = p ?: return dir
            if (cur.name == "bis" && File(cur, "system/Contents/registered").isDirectory) return cur
            if (File(cur, "system/Contents/registered").let { countKenji(it) >= 5 }) return cur
            p = cur.parentFile
        }
        return dir
    }

    private fun wipeEmptyBis(context: Context, bis: File) {
        if (isProtected(context, bis)) {
            BootLog.add("wipe пропущен — оригинал ${bis.absolutePath}")
            return
        }
        val registered = File(bis, "system/Contents/registered")
        if (isShortcut(bis)) {
            if (countKenji(registered) < 5) bis.delete()
            return
        }
        if (!bis.exists()) return
        if (countKenji(registered) >= 5) return
        registered.listFiles()?.forEach { d ->
            val inner = File(d, "00")
            if (!isReadableNca(inner)) d.deleteRecursively()
        }
        if (countKenji(registered) < 5) bis.deleteRecursively()
    }

    private fun bindDir(src: File, dest: File): Boolean {
        if (!src.isDirectory) return false
        if (samePath(src, dest)) return countKenji(File(dest, "system/Contents/registered")) >= 5
        if (isShortcut(dest)) {
            val target = readLink(dest)
            if (target == src.absolutePath && countKenji(File(dest, "system/Contents/registered")) >= 5) return true
            dest.delete()
        } else {
            // dest may be a real tree; only wipe if empty of firmware
            val n = countKenji(File(dest, "system/Contents/registered"))
            if (n >= 5) return true
            if (dest.exists()) {
                if (isShortcut(dest)) dest.delete() else dest.deleteRecursively()
            }
        }
        if (dest.exists()) return countKenji(File(dest, "system/Contents/registered")) >= 5
        dest.parentFile?.mkdirs()
        return try {
            Os.symlink(src.absolutePath, dest.absolutePath)
            val n = countKenji(File(dest, "system/Contents/registered"))
            BootLog.add("bindDir OK $n ← ${src.absolutePath}")
            n >= 5
        } catch (t: Throwable) {
            val extra = (t as? ErrnoException)?.let { " errno=${it.errno}" } ?: ""
            BootLog.add("bindDir FAIL$extra ${t.message}")
            Log.e("KenjiSpace", "bindDir ${t.message}", t)
            false
        }
    }

    private fun autoDiscoverEden(context: Context) {
        if (edenDir(context) != null) return
        val hit = FirmwareHunt.best(context) ?: return
        var p = hit.dir
        repeat(4) {
            if (File(p, "nand").isDirectory || File(p, "keys").isDirectory || File(p, "load").isDirectory) {
                context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                    .edit().putString(PREF_EDEN, p.absolutePath).commit()
                BootLog.add("auto Eden ${p.absolutePath}")
                return
            }
            p = p.parentFile ?: return
        }
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(PREF_EDEN, hit.dir.absolutePath).commit()
    }

    fun keysOk(context: Context): Boolean {
        val a = File(playHome(context), "system/prod.keys")
        return a.isFile && a.length() > 100
    }

    fun firmwareNca(context: Context): Int =
        countKenji(File(playHome(context), "bis/system/Contents/registered"))

    fun firmwareBytes(context: Context): Long =
        BootLog.registeredBytes(File(playHome(context), "bis/system/Contents/registered"))

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

    /**
     * An empty /sdcard/Kenji was previously symlinked over Android/data/.../bis.
     * Official Kenji then saw zero NCA. Drop that symlink if the target is empty.
     */
    private fun repairAppPath(context: Context) {
        val app = appPath(context)
        val bis = File(app, "bis")
        val registered = File(bis, "system/Contents/registered")
        if (isShortcut(bis) && countKenji(registered) < 5) {
            BootLog.add("снимаю пустой ярлык bis → ${readLink(bis)}")
            bis.delete()
        }
        val system = File(app, "system")
        val keys = File(system, "prod.keys")
        if (isShortcut(system) && !(keys.isFile && keys.length() > 100)) {
            system.delete()
            system.mkdirs()
        }
    }

    private fun readLink(f: File): String = try {
        Os.readlink(f.absolutePath)
    } catch (_: Throwable) {
        f.absolutePath
    }

    /** Pointer only — do not grow a second firmware tree. */
    private fun writePointer(context: Context, play: File) {
        val user = userKenjiDir(context) ?: return
        if (samePath(user, play)) return
        try {
            File(user, "system").mkdirs()
            File(user, "WHERE_FIRMWARE.txt").writeText(
                "Прошивка не копируется и не переезжает.\n" +
                    "Оригинал: ${firmwareSource(context)}\n" +
                    "Kenji читает: ${File(play, "bis/system/Contents/registered").absolutePath}\n" +
                    "Вес: ${BootLog.human(firmwareBytes(context))}\n",
            )
        } catch (_: Exception) {
        }
    }

    private fun sources(context: Context): List<File> {
        val sd = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        out.add(playHome(context))
        out.add(appPath(context))
        edenDir(context)?.let { p ->
            val f = File(p)
            if (f.isDirectory) out.add(f)
        }
        userKenjiDir(context)?.let { if (countKenji(File(it, "bis/system/Contents/registered")) >= 10) out.add(it) }
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
        var fail = 0
        var last = ""
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
            if (dest.exists()) {
                if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
            }
            destDir.mkdirs()
            val how = shortcut(payload, dest)
            if (how == null) {
                fail++
                last = AutoFix.lastErr
            } else if (how == "hardlink") mode = "жёсткие ссылки"
        }
        val n = countKenji(destReg)
        if (fail > 0) BootLog.add("bridge fail=$fail $last")
        if (n < 5) return false
        remember(context, srcReg.absolutePath, mode, n)
        writeReport(context, kenjiHome(context))
        BootLog.add("fw $mode $n · ${BootLog.human(BootLog.registeredBytes(destReg))} ← ${srcReg.absolutePath}")
        return true
    }

    private fun shortcut(src: File, dest: File): String? {
        if (!src.exists()) return null
        if (dest.exists() && !dest.delete()) return null
        try {
            Os.symlink(src.absolutePath, dest.absolutePath)
            if (isReadableNca(dest) || isShortcut(dest)) return "symlink"
        } catch (t: Throwable) {
            AutoFix.lastErr = "symlink ${t.message}"
            Log.w("KenjiSpace", "symlink ${dest.name}", t)
        }
        try {
            Os.link(src.absolutePath, dest.absolutePath)
            if (isReadableNca(dest)) return "hardlink"
        } catch (t: Throwable) {
            AutoFix.lastErr = "link ${t.message}"
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
                "Kenji читает: ${File(playHome(context), "bis/system/Contents/registered").absolutePath}\n" +
                    "Источник: ${firmwareSource(context)}\n" +
                    "Как: ${firmwareMode(context)} (без копии NCA)\n" +
                    "NCA: ${firmwareNca(context)}\n" +
                    "Вес: ${BootLog.human(firmwareBytes(context))}\n",
            )
        } catch (_: Exception) {
        }
    }
}
