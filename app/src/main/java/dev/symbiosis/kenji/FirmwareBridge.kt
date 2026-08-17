package dev.symbiosis.kenji

import android.os.Environment
import android.system.ErrnoException
import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Eden/Yuzu store firmware as loose NCAs under
 * nand/system/Contents/registered (files named .nca).
 * Kenji/Ryujinx LoadEntries only accepts Ryujinx layout:
 * bis/system/Contents/registered/{id}.nca/00
 *
 * Same bytes. No core change — we hardlink (or copy) into Kenji's tree.
 */
object FirmwareBridge {

    private const val EDEN_REGISTERED = "nand/system/Contents/registered"
    private const val KENJI_REGISTERED = "bis/system/Contents/registered"

    fun kenjiRegistered(root: File) = File(root, KENJI_REGISTERED)
    fun edenRegistered(root: File) = File(root, EDEN_REGISTERED)

    data class Source(val label: String, val dir: File, val ncas: Int)

    fun kenjiNcaCount(root: File): Int = countKenjiLayout(kenjiRegistered(root))

    fun kenjiReady(root: File): Boolean = kenjiNcaCount(root) >= 10

    /**
     * A previous javaInitialize stash that never came back leaves firmware
     * in registered.stash and an empty registered/. Put it back before we
     * count NCA or try to init again.
     */
    fun restoreOrphanStash(root: File) {
        val registered = kenjiRegistered(root)
        val stash = File(registered.parentFile, "registered.stash")
        if (!stash.isDirectory) return
        if (kenjiNcaCount(root) >= 10) {
            stash.deleteRecursively()
            return
        }
        if (registered.exists()) registered.deleteRecursively()
        stash.renameTo(registered)
    }

    /**
     * SwitchDevice parses every entry under registered/. Empty directories,
     * leftover .part copies and nested 00/00 from an old bridge bug make
     * javaInitialize return false and poison VirtualFileSystem for the
     * whole :player process.
     */
    fun quarantineJunk(root: File): Int {
        restoreOrphanStash(root)
        val registered = kenjiRegistered(root)
        if (!registered.isDirectory) return 0
        val junk = File(registered.parentFile, "registered.junk")
        junk.mkdirs()
        var moved = 0
        registered.listFiles()?.forEach { entry ->
            val good = when {
                !entry.isDirectory -> false
                File(entry, "00").let { it.isFile && it.length() >= 0xC00 } -> true
                File(entry, "00").isDirectory -> {
                    val inner = File(entry, "00/00")
                    if (inner.isFile && inner.length() >= 0xC00) {
                        val flat = File(entry, "00.flat")
                        if (inner.renameTo(flat)) {
                            File(entry, "00").deleteRecursively()
                            flat.renameTo(File(entry, "00"))
                        }
                        File(entry, "00").let { it.isFile && it.length() >= 0xC00 }
                    } else {
                        false
                    }
                }
                else -> false
            }
            if (!good) {
                val dest = File(junk, entry.name)
                if (dest.exists()) dest.deleteRecursively()
                if (entry.renameTo(dest)) moved++
            }
        }
        return moved
    }

    /**
     * Move registered entries whose 00 is not a decryptable NCA.
     * Returns how many were quarantined. Needs header_key in prod.keys.
     */
    /** -1 if header_key is missing so we cannot judge. */
    fun countValidHeaders(root: File): Int {
        val key = NcaHeader.readHeaderKey(DataRoot.kenjiKeysFile(root)) ?: return -1
        val registered = kenjiRegistered(root)
        if (!registered.isDirectory) return 0
        return registered.listFiles()?.count { entry ->
            entry.isDirectory && NcaHeader.isValid(File(entry, "00"), key)
        } ?: 0
    }

    fun quarantineInvalid(root: File): Int {
        val registered = kenjiRegistered(root)
        if (!registered.isDirectory) return 0
        val key = NcaHeader.readHeaderKey(DataRoot.kenjiKeysFile(root)) ?: return 0
        val entries = registered.listFiles()?.filter { it.isDirectory } ?: return 0
        val good = ArrayList<File>()
        val bad = ArrayList<File>()
        for (entry in entries) {
            if (NcaHeader.isValid(File(entry, "00"), key)) good.add(entry) else bad.add(entry)
        }
        // If nothing decrypts, header_key/XTS is wrong — leave the tree alone
        // and let the stash path handle javaInitialize.
        if (good.isEmpty()) return 0
        val junk = File(registered.parentFile, "registered.junk")
        junk.mkdirs()
        var moved = 0
        for (entry in bad) {
            val dest = File(junk, entry.name)
            if (dest.exists()) dest.deleteRecursively()
            if (entry.renameTo(dest)) moved++
        }
        return moved
    }

    fun findSources(): List<Source> {
        val sd = Environment.getExternalStorageDirectory()
        val candidates = listOf(
            "эта папка (Eden)" to edenRegistered(File(DataRoot.resolve())),
            "официальный Eden" to File(sd, "Android/data/dev.eden.eden_emulator/files/$EDEN_REGISTERED"),
            "Symbiosis / yuzu" to File(sd, "Android/data/org.yuzu.yuzu_emu/files/$EDEN_REGISTERED"),
            "Eden (видимая)" to File(sd, "Eden/$EDEN_REGISTERED"),
            "yuzu (видимая)" to File(sd, "yuzu/$EDEN_REGISTERED"),
            "Switch (общая)" to File(sd, "Switch/$EDEN_REGISTERED"),
            "citron" to File(sd, "Android/data/org.citron.citron_emu/files/$EDEN_REGISTERED"),
        )
        val seen = HashSet<String>()
        val out = ArrayList<Source>()
        for ((label, dir) in candidates) {
            val path = try { dir.canonicalPath } catch (_: Exception) { dir.absolutePath }
            if (!seen.add(path)) continue
            val n = countEdenLayout(dir)
            if (n >= 10) out.add(Source(label, dir, n))
        }
        return out
    }

    fun bestSource(): Source? = findSources().maxByOrNull { it.ncas }

    /**
     * If Kenji bis is empty and Eden firmware exists, lay it out for Kenji.
     * Safe to call on every launch.
     */
    fun auto(root: File = DataRoot.kenjiHome(), allowCopy: Boolean = false): JSONObject {
        if (kenjiReady(root)) {
            return JSONObject()
                .put("ok", true)
                .put("skipped", true)
                .put("message", "прошивка Kenji уже в bis/ (${kenjiNcaCount(root)} NCA)")
                .put("kenji", kenjiNcaCount(root))
        }
        val src = bestSource()
            ?: return JSONObject()
                .put("ok", false)
                .put("message", "не нашёл распакованную прошивку Eden (nand/system/Contents/registered)")
        return apply(src.dir, root, src.label, allowCopy)
    }

    fun apply(
        edenDir: File,
        kenjiRoot: File,
        label: String = edenDir.absolutePath,
        allowCopy: Boolean = true
    ): JSONObject {
        val srcNcas = collectEdenNcas(edenDir)
        if (srcNcas.size < 10) {
            return JSONObject().put("ok", false)
                .put("message", "в $label мало NCA (${srcNcas.size})")
        }
        val destRoot = kenjiRegistered(kenjiRoot)
        destRoot.mkdirs()
        var linked = 0
        var copied = 0
        var skipped = 0
        var failed = 0
        var bytes = 0L
        var mode = "link"
        for (entry in srcNcas) {
            val src = entry.file
            val destDir = File(destRoot, entry.entryName)
            val dest = File(destDir, "00")
            if (dest.isFile && dest.length() == src.length() && dest.length() > 0) {
                skipped++
                continue
            }
            destDir.mkdirs()
            if (dest.exists()) dest.delete()
            val linkedOk = if (mode != "copy") tryLink(src, dest) else false
            if (linkedOk) {
                linked++
                bytes += src.length()
            } else if (!allowCopy) {
                failed++
                dest.delete()
                if (linked == 0 && copied == 0 && skipped == 0) {
                    return JSONObject()
                        .put("ok", false)
                        .put("needCopy", true)
                        .put("source", edenDir.absolutePath)
                        .put("message", "ссылка с $label не вышла (другой раздел). Нажмите «Мост Eden» — скопирую ${srcNcas.size} NCA в bis/.")
                }
            } else {
                mode = "copy"
                if (tryCopy(src, dest)) {
                    copied++
                    bytes += src.length()
                } else {
                    failed++
                    dest.delete()
                }
            }
        }
        DataRoot.seedKeysIntoKenji(kenjiRoot)
        val ready = kenjiReady(kenjiRoot)
        val how = when {
            copied > 0 && linked > 0 -> "ссылки + копия"
            copied > 0 -> "копия"
            else -> "ссылки, места не заняли"
        }
        return JSONObject()
            .put("ok", ready)
            .put("message", if (ready)
                "мост $label → bis/: ${linked + copied} NCA ($how), пропуск $skipped" +
                    (if (failed > 0) ", ошибок $failed" else "")
            else
                "мост не собрал прошивку Kenji (готово ${kenjiNcaCount(kenjiRoot)}, ошибок $failed)")
            .put("source", edenDir.absolutePath)
            .put("dest", destRoot.absolutePath)
            .put("linked", linked)
            .put("copied", copied)
            .put("skipped", skipped)
            .put("failed", failed)
            .put("bytes", bytes)
            .put("kenji", kenjiNcaCount(kenjiRoot))
            .put("eden", srcNcas.size)
    }

    fun statusJson(): String {
        val root = DataRoot.kenjiHome()
        val arr = JSONArray()
        findSources().forEach { s ->
            arr.put(
                JSONObject()
                    .put("label", s.label)
                    .put("path", s.dir.absolutePath)
                    .put("ncas", s.ncas)
            )
        }
        return JSONObject()
            .put("kenjiReady", kenjiReady(root))
            .put("kenjiNcas", kenjiNcaCount(root))
            .put("kenjiPath", kenjiRegistered(root).absolutePath)
            .put("sources", arr)
            .toString()
    }

    private fun countEdenLayout(dir: File): Int {
        if (!dir.isDirectory) return 0
        return collectEdenNcas(dir).size
    }

    private fun countKenjiLayout(dir: File): Int {
        if (!dir.isDirectory) return 0
        return dir.listFiles()?.count { d ->
            d.isDirectory && File(d, "00").let { it.isFile && it.length() > 1000 }
        } ?: 0
    }

    /** Loose .nca files, or already-Ryujinx {id}.nca/00 directories. */
    /**
     * Найденный NCA и ИМЯ, под которым его ждёт Kenji.
     *
     * Раньше возвращался просто File, а имя папки бралось как
     * `src.name`. Для россыпи `abcdef.nca` это верно, но если исходная
     * папка уже в раскладке Ryujinx (`abcdef.nca/00`), то найденным
     * файлом оказывался сам `00`, и мост создавал `bis/.../00/00`.
     * Прошивка после такого не читалась, а счётчик показывал успех:
     * countKenjiLayout считает любые каталоги с непустым `00` внутри.
     *
     * Теперь имя каталога хранится рядом с файлом и берётся у родителя,
     * когда файл называется `00`.
     */
    private data class Nca(val file: File, val entryName: String)

    private fun collectEdenNcas(dir: File): List<Nca> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<Nca>()
        dir.listFiles()?.forEach { f ->
            when {
                f.isFile && f.name.endsWith(".nca", true) && f.length() > 1000 ->
                    out.add(Nca(f, f.name))

                f.isDirectory && f.name.endsWith(".nca", true) -> {
                    // Уже раскладка Ryujinx: {id}.nca/00. Имя записи -
                    // имя КАТАЛОГА, а не файла внутри.
                    val inner = File(f, "00")
                    if (inner.isFile && inner.length() > 1000) {
                        out.add(Nca(inner, f.name))
                    } else {
                        f.listFiles()?.firstOrNull { it.isFile && it.length() > 1000 }
                            ?.let { out.add(Nca(it, f.name)) }
                    }
                }
            }
        }
        return out
    }

    private fun tryLink(src: File, dest: File): Boolean = try {
        Os.link(src.absolutePath, dest.absolutePath)
        dest.isFile && dest.length() == src.length()
    } catch (_: ErrnoException) {
        false
    } catch (_: Throwable) {
        false
    }

    private fun tryCopy(src: File, dest: File): Boolean = try {
        // Never expose a partially copied NCA as bis/.../00. The core scans
        // this tree during javaInitialize, so an interrupted direct copy can
        // poison the whole filesystem for the process.
        val part = File(dest.parentFile, "${dest.name}.part-${System.nanoTime()}")
        FileInputStream(src).use { input ->
            FileOutputStream(part).use { output -> input.copyTo(output) }
        }
        if (!part.isFile || part.length() != src.length()) {
            part.delete()
            false
        } else {
            if (dest.exists()) dest.delete()
            val moved = part.renameTo(dest)
            if (!moved) part.delete()
            moved && dest.isFile && dest.length() == src.length()
        }
    } catch (_: Throwable) {
        false
    }
}
