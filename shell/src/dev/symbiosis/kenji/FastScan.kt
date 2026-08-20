package dev.symbiosis.kenji

import android.content.Context
import android.os.Environment
import android.os.SystemClock
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/**
 * Lazy name-only walk. Does not read file bodies. Stops by time and dir cap.
 */
object FastScan {
    data class Report(
        val gameDirs: List<File>,
        val roms: Int,
        val keys: List<File>,
        val firmware: List<File>,
        val saves: List<File>,
        val ms: Long,
        val dirsWalked: Int,
        val allFiles: Boolean,
    ) {
        fun line(): String {
            val perm = if (allFiles) "" else "НЕТ доступа ко всем файлам · "
            val g = if (gameDirs.isEmpty()) "игр нет" else "${gameDirs.size} папок · $roms ROM"
            val k = if (keys.isEmpty()) "ключей нет" else "ключи ${keys.size}"
            val f = if (firmware.isEmpty()) "прошивки нет" else "прошивка ${firmware.size}"
            val s = if (saves.isEmpty()) "сейвов нет" else "сейвы ${saves.size}"
            return "$perm$g · $k · $f · $s · ${ms} мс / $dirsWalked папок"
        }
    }

    @Volatile var last: Report? = null
        private set

    @Volatile var lastLine: String = "сканер ещё не работал"
        private set

    @Volatile var wroteFolder: Boolean = false
        private set

    private val SKIP = setOf(
        "dcim", "pictures", "movies", "music", "alarms", "notifications",
        "ringtones", "audiobooks", "podcasts", "recordings", "whatsapp",
        "telegram", "tencent", "miui", "coloros", "samsung", "recycle",
        ".thumbnails", "thumbnails", "cache", ".cache", "lost+found",
        ".trash", "obb", "media", "fonts", "logs",
    )

    private val ROM_EXT = arrayOf(".nsp", ".xci", ".nro", ".nsz", ".xcz")

    fun run(context: Context): Report {
        val t0 = SystemClock.uptimeMillis()
        val romsByDir = HashMap<String, Int>()
        val keys = ArrayList<File>()
        val firmware = ArrayList<File>()
        val saves = ArrayList<File>()
        val seen = HashSet<String>()
        val q = ArrayDeque<Pair<File, Int>>()
        val sd = Environment.getExternalStorageDirectory()
        listOf(
            File(sd, "Download/ed"),
            File(sd, "Download"),
            File(sd, "Switch"),
            File(sd, "Games"),
            File(sd, "NSP"),
            File(sd, "XCI"),
            File(sd, "roms"),
            File(sd, "Eden"),
            File(sd, "Ryujinx"),
            File(sd, "Yuzu"),
            File(sd, "Sudachi"),
            File(sd, "Kenji"),
            sd,
        ).forEach { if (it.isDirectory) q.add(it to 0) }
        context.getExternalFilesDirs(null)?.forEach { ext ->
            if (ext != null && ext.isDirectory) q.add(ext to 0)
        }
        File("/storage").listFiles()?.forEach { vol ->
            if (vol.isDirectory && vol.name != "self" && vol.canRead()) q.add(vol to 0)
        }

        var walked = 0
        while (q.isNotEmpty() && walked < 400 && SystemClock.uptimeMillis() - t0 < 2500L) {
            val (dir, depth) = q.removeFirst()
            val key = dir.absolutePath
            if (!seen.add(key)) continue
            walked++
            val kids = dir.listFiles() ?: continue
            var romHere = 0
            var looksFw = false
            var looksSave = false
            for (f in kids) {
                val low = f.name.lowercase(Locale.US)
                if (f.isFile) {
                    if (ROM_EXT.any { low.endsWith(it) }) romHere++
                    else if (low == "prod.keys" || low == "title.keys") keys.add(f)
                } else if (f.isDirectory) {
                    if (low.endsWith(".nca") && File(f, "00").isFile) looksFw = true
                    if (low == "registered" || low == "registered.stash") looksFw = true
                    if (low == "save" && (dir.name.equals("user", true) || dir.path.contains("bis") || dir.path.contains("nand"))) {
                        looksSave = true
                    }
                }
            }
            if (romHere > 0) romsByDir[dir.absolutePath] = (romsByDir[dir.absolutePath] ?: 0) + romHere
            if (looksFw) firmware.add(dir)
            if (looksSave) saves.add(dir)

            if (depth >= 5) continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val name = k.name
                if (name.startsWith(".")) continue
                val low = name.lowercase(Locale.US)
                if (low in SKIP) continue
                if (low == "android") {
                    // only known emulator sandboxes, not the whole tree
                    listOf(
                        "data/dev.eden.eden_emulator/files",
                        "data/org.yuzu.yuzu_emu/files",
                        "data/org.kenjinx.android/files",
                        "data/dev.symbiosis.kenji/files",
                        "data/org.citron.citron_emu/files",
                    ).forEach { rel ->
                        val sub = File(k, rel)
                        if (sub.isDirectory) q.add(sub to depth + 1)
                    }
                    continue
                }
                q.add(k to depth + 1)
            }
        }

        val gameDirs = romsByDir.entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenByDescending { it.key.length },
            )
            .map { File(it.key) }
        val roms = romsByDir.values.sum()
        val report = Report(
            gameDirs = gameDirs,
            roms = roms,
            keys = keys.distinctBy { it.absolutePath },
            firmware = firmware.distinctBy { it.absolutePath },
            saves = saves.distinctBy { it.absolutePath },
            ms = SystemClock.uptimeMillis() - t0,
            dirsWalked = walked,
            allFiles = AccessFix.hasAllFiles(),
        )
        last = report
        lastLine = report.line()
        apply(context, report)
        BootLog.add("fastscan: ${report.line()}")
        Log.i("KenjiSpace", lastLine)
        return report
    }

    fun reloadShelf(activity: android.app.Activity, force: Boolean) {
        val folder = GameFolder.currentPath(activity).ifBlank {
            android.preference.PreferenceManager.getDefaultSharedPreferences(activity)
                .getString("gameFolder", "") ?: ""
        }
        if (folder.isBlank()) return
        val space = activity.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
        val last = space.getString("shelf_folder", "")
        if (!force && last == folder) return
        space.edit().putString("shelf_folder", folder).putBoolean("need_shelf_reload", false).commit()
        GameFolder.reloadKenji(activity)
    }

    private fun apply(context: Context, report: Report) {
        wroteFolder = GameFolder.sanitize(context)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getString("gameFolder", "") ?: ""
        val granted = current.startsWith("content:") &&
            context.contentResolver.persistedUriPermissions.any { p ->
                val u = p.uri.toString()
                u == current || current.startsWith(u)
            }
        if (granted) {
            val pathHint = prefs.getString("gameFolderPath", "") ?: ""
            if (pathHint.isBlank() && report.gameDirs.isNotEmpty()) {
                prefs.edit().putString("gameFolderPath", report.gameDirs.first().absolutePath).commit()
            }
        } else if (report.gameDirs.isNotEmpty()) {
            val best = report.gameDirs.first()
            val already = GameFolder.hasRoms(GameFolder.currentPath(context)) &&
                !current.startsWith("content:")
            if (!already) {
                if (GameFolder.write(context, best)) wroteFolder = true
            }
        }
        if (DataSeed.edenDir(context) == null) {

            val fromKeys = report.keys.firstOrNull()?.let { f ->
                val p = f.parentFile ?: return@let null
                when {
                    File(p, "nand").isDirectory -> p
                    File(p.parentFile, "nand").isDirectory -> p.parentFile
                    p.name.equals("system", true) || p.name.equals("keys", true) -> p.parentFile
                    else -> p
                }
            }
            val fromFw = report.firmware.firstOrNull()?.let { climbDataRoot(it) }
            val hit = fromKeys ?: fromFw
            if (hit != null && hit.isDirectory) DataSeed.setEdenDir(context, hit.absolutePath)
        }
    }

    private fun climbDataRoot(dir: File): File? {
        var p: File? = dir
        repeat(5) {
            val cur = p ?: return null
            if (File(cur, "nand").isDirectory || File(cur, "keys").isDirectory ||
                File(cur, "bis").isDirectory || File(cur, "system/prod.keys").isFile
            ) return cur
            p = cur.parentFile
        }
        return dir
    }
}
