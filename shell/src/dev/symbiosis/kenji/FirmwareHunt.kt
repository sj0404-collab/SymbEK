package dev.symbiosis.kenji

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.util.Log
import java.io.File
import java.util.ArrayDeque
import java.util.Locale

/** Walk the card and SAF trees for a firmware dump. Firmware is never moved. */
object FirmwareHunt {
    data class Hit(val dir: File, val nca: Int, val kenjiLayout: Boolean)

    @Volatile var lastReport: String = "сканер ещё не работал"
        private set

    @Volatile var lastHits: List<Hit> = emptyList()
        private set

    fun best(context: Context): Hit? {
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()

        fun add(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val key = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (!seen.add(key)) return
            val n = countNca(dir)
            if (n >= 5) {
                val kenji = dir.listFiles()?.any {
                    it.isDirectory && it.name.lowercase(Locale.US).endsWith(".nca") &&
                        File(it, "00").isFile
                } == true
                hits.add(Hit(dir, n, kenji))
            }
        }

        val roots = ArrayList<File>()
        val sd = Environment.getExternalStorageDirectory()
        roots.add(sd)
        roots.add(File(sd, "Download"))
        roots.add(File(sd, "Download/ed"))
        context.getExternalFilesDirs(null)?.forEach { ext ->
            if (ext != null) {
                roots.add(ext)
                // …/Android/data/<pkg>/files → volume root
                var p = ext
                repeat(5) { p = p.parentFile ?: return@repeat }
                if (p != null) roots.add(p)
            }
        }
        File("/storage").listFiles()?.forEach { vol ->
            if (vol.isDirectory && vol.name != "self" && vol.name != "emulated") roots.add(vol)
        }
        listOf(
            "Eden", "Eden/files", "Download/ed/Eden", "Download/ed/Eden/files",
            "Switch", "Kenji", "firmware", "Firmware", "FW", "nand",
            "Android/data/dev.eden.eden_emulator/files",
            "Android/data/org.yuzu.yuzu_emu/files",
            "Android/data/org.kenjinx.android/files",
            "Android/data/dev.symbiosis.kenji/files",
            "Android/data/org.citron.citron_emu/files",
            "Android/data/io.github.lime3ds.android/files",
        ).forEach { roots.add(File(sd, it)) }

        DataSeed.edenDir(context)?.let { roots.add(File(it)) }

        context.contentResolver.persistedUriPermissions.forEach { p ->
            PickActivity.treeToPath(p.uri)?.let { roots.add(File(it)) }
            walkSaf(context, p.uri, roots)
        }

        for (root in roots) {
            walkFiles(root, 6, 500, ::add)
        }

        lastHits = hits.sortedByDescending { it.nca }
        lastReport = if (hits.isEmpty()) {
            val all = AccessFix.hasAllFiles()
            "NCA не найдены. all-files=${if (all) "да" else "НЕТ"} · корней ${roots.size}. Укажите папку, где лежат .nca (Eden nand или firmware)."
        } else {
            hits.sortedByDescending { it.nca }.take(6).joinToString("\n") {
                "${it.nca} NCA ${if (it.kenjiLayout) "kenji" else "eden"} · ${it.dir.absolutePath}"
            }
        }
        Log.i("KenjiSpace", "hunt\n$lastReport")
        return lastHits.firstOrNull()
    }

    private fun walkFiles(start: File, maxDepth: Int, maxDirs: Int, visit: (File) -> Unit) {
        if (!start.exists()) return
        val q = ArrayDeque<Pair<File, Int>>()
        q.add(start to 0)
        var n = 0
        val seen = HashSet<String>()
        while (q.isNotEmpty() && n < maxDirs) {
            val (dir, depth) = q.removeFirst()
            val key = dir.absolutePath
            if (!seen.add(key)) continue
            n++
            visit(dir)
            // also visit well-known children even if we don't list everything
            listOf(
                "nand", "nand/system/Contents/registered",
                "bis/system/Contents/registered",
                "bis/system/Contents/registered.stash",
                "system/Contents/registered",
                "registered", "firmware", "Firmware", "files",
            ).forEach { rel ->
                val child = File(dir, rel)
                if (child.isDirectory) visit(child)
            }
            if (depth >= maxDepth) continue
            val kids = dir.listFiles() ?: continue
            for (k in kids) {
                if (!k.isDirectory) continue
                val name = k.name
                if (name.startsWith(".")) continue
                if (name.equals("Android", true) && dir == Environment.getExternalStorageDirectory()) {
                    val data = File(k, "data")
                    if (data.isDirectory) q.add(data to depth + 1)
                    continue
                }
                q.add(k to depth + 1)
            }
        }
    }

    private fun walkSaf(context: Context, tree: Uri, roots: MutableList<File>) {
        try {
            val id = DocumentsContract.getTreeDocumentId(tree)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id)
            val c = context.contentResolver.query(
                children,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            ) ?: return
            c.use {
                while (it.moveToNext()) {
                    val name = it.getString(1) ?: continue
                    val low = name.lowercase(Locale.US)
                    if (low == "nand" || low == "files" || low == "firmware" || low == "registered" || low == "bis") {
                        PickActivity.treeToPath(tree)?.let { base ->
                            roots.add(File(base, name))
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            Log.w("KenjiSpace", "saf", t)
        }
    }

    fun countNca(dir: File?): Int {
        val kids = dir?.listFiles() ?: return 0
        var n = 0
        for (f in kids) {
            val low = f.name.lowercase(Locale.US)
            when {
                f.isFile && low.endsWith(".nca") && f.length() > 1000 -> n++
                f.isDirectory && low.endsWith(".nca") && File(f, "00").let { it.isFile && it.length() > 1000 } -> n++
            }
        }
        return n
    }
}
