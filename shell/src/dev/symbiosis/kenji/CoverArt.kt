package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Icon without deviceGetGameInfo and without reading the whole NSP.
 * Order: disk cache → sidecar → Eden/Kenji icon files → Kenji view scrape
 * → native *Icon* (not GameInfo) → small PFS0 JPEG scan.
 */
object CoverArt {
    private val mem = ConcurrentHashMap<String, Bitmap>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val pool = Executors.newFixedThreadPool(2)

    fun cached(rom: RomList.Rom): Bitmap? = mem[key(rom)]

    fun load(context: Context, rom: RomList.Rom, done: (Bitmap?) -> Unit) {
        val k = key(rom)
        mem[k]?.let { done(it); return }
        if (k in failed) {
            done(null)
            return
        }
        val disk = diskFile(context, rom)
        if (disk.isFile && disk.length() > 200) {
            val bmp = decodeFile(disk)
            if (bmp != null) {
                mem[k] = bmp
                done(bmp)
                return
            }
        }
        pool.execute {
            val bmp = try {
                extract(context, rom)
            } catch (t: Throwable) {
                Log.w("KenjiSpace", "cover ${rom.file.name}", t)
                null
            }
            if (bmp != null) {
                mem[k] = bmp
                save(disk, bmp)
            } else {
                failed.add(k)
            }
            val act = context as? Activity
            if (act != null) act.runOnUiThread { done(bmp) } else done(bmp)
        }
    }

    private fun key(rom: RomList.Rom): String =
        rom.titleId.ifBlank { rom.file.absolutePath }

    private fun diskFile(context: Context, rom: RomList.Rom): File {
        val dir = File(DataSeed.playHome(context), "space_icons")
        val name = (rom.titleId.ifBlank { rom.file.nameWithoutExtension }).lowercase(Locale.US)
        return File(dir, "$name.jpg")
    }

    private fun extract(context: Context, rom: RomList.Rom): Bitmap? {
        sidecar(rom)?.let { return it }
        huntNamed(context, rom)?.let { return it }
        (context as? Activity)?.let { scrape(it, rom)?.let { b -> return b } }
        nativeIcon(rom)?.let { return it }
        val f = rom.file
        if (!f.isFile) return null
        val low = f.name.lowercase(Locale.US)
        if (low.endsWith(".nro")) return nroIcon(f)
        return pfs0Icon(f)
    }

    private fun sidecar(rom: RomList.Rom): Bitmap? {
        val parent = rom.file.parentFile ?: return null
        val base = rom.file.nameWithoutExtension
        val names = ArrayList<String>()
        names.add("$base.jpg"); names.add("$base.jpeg"); names.add("$base.png")
        if (rom.titleId.length >= 8) {
            names.add("${rom.titleId}.jpg")
            names.add("${rom.titleId}.png")
            names.add("${rom.titleId.lowercase(Locale.US)}.jpg")
            names.add("${rom.titleId.lowercase(Locale.US)}.png")
        }
        names.add("cover.jpg"); names.add("icon.jpg")
        for (n in names) {
            val f = File(parent, n)
            if (f.isFile && f.length() in 800L..900_000L) decodeFile(f)?.let { return it }
        }
        return null
    }

    private fun huntNamed(context: Context, rom: RomList.Rom): Bitmap? {
        val id = rom.titleId
        if (id.length < 8) return null
        val low = id.lowercase(Locale.US)
        val roots = ArrayList<File>()
        roots.add(DataSeed.playHome(context))
        DataSeed.edenDir(context)?.let { roots.add(File(it)) }
        val sd = Environment.getExternalStorageDirectory()
        listOf(
            "Android/data/dev.eden.eden_emulator/files",
            "Android/data/org.yuzu.yuzu_emu/files",
            "Android/data/org.sudachi.sudachi_emu/files",
            "Android/data/org.kenjinx.android/files",
            "Android/data/dev.symbiosis.kenji/files",
            "Download/ed/Eden/files",
            "Download/ed",
        ).forEach { rel ->
            val f = File(sd, rel)
            if (f.isDirectory) roots.add(f)
        }
        val rels = arrayOf(
            "space_icons/$low.jpg",
            "cache/game_list/$low.png",
            "cache/game_list/$low.jpg",
            "cache/icons/$low.png",
            "cache/icons/$low.jpg",
            "cache/$low.png",
            "cache/$low.jpg",
            "games/$low.jpg",
            "games/$low.png",
            "icons/$low.png",
            "icons/$low.jpg",
            "load/icons/$low.jpg",
            "sdmc/atmosphere/contents/$low/icon.jpg",
        )
        for (root in roots) {
            for (rel in rels) {
                val f = File(root, rel)
                if (f.isFile && f.length() in 800L..900_000L) decodeFile(f)?.let { return it }
            }
        }
        for (root in roots) {
            walkName(root, low, 0)?.let { return it }
        }
        return null
    }

    private fun walkName(dir: File, needle: String, depth: Int): Bitmap? {
        if (depth > 4 || !dir.isDirectory) return null
        val kids = dir.listFiles() ?: return null
        var n = 0
        for (f in kids) {
            if (n++ > 80) break
            val name = f.name.lowercase(Locale.US)
            if (f.isFile && name.contains(needle) &&
                (name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png")) &&
                f.length() in 800L..900_000L
            ) {
                decodeFile(f)?.let { return it }
            }
            if (f.isDirectory && !name.startsWith(".") && name != "registered" && name != "nand") {
                walkName(f, needle, depth + 1)?.let { return it }
            }
        }
        return null
    }

    private fun scrape(act: Activity, rom: RomList.Rom): Bitmap? {
        val want = rom.title.lowercase(Locale.US)
        var fallback: Bitmap? = null
        for (root in SpaceHook.allWindowsPublic()) {
            scrapeWalk(root, 0, want) { bmp ->
                if (bmp.width >= 96 && bmp.height >= 96 && bmp.width <= 1024) {
                    fallback = bmp
                }
            }
        }
        return fallback
    }

    private fun scrapeWalk(v: View, depth: Int, title: String, hit: (Bitmap) -> Unit) {
        if (depth > 16 || SpaceHook.isSpaceView(v)) return
        when (v) {
            is ImageView -> {
                val d = v.drawable
                val bmp = (d as? BitmapDrawable)?.bitmap
                if (bmp != null && !bmp.isRecycled) hit(bmp)
            }
            else -> {
                val d = v.background
                val bmp = (d as? BitmapDrawable)?.bitmap
                if (bmp != null && !bmp.isRecycled && bmp.width >= 96) hit(bmp)
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) scrapeWalk(v.getChildAt(i), depth + 1, title, hit)
        }
    }

    /** Icon-only JNI. Never deviceGetGameInfo — that OOM'd the launcher. */
    private fun nativeIcon(rom: RomList.Rom): Bitmap? {
        val classes = arrayOf(
            "org.kenjinx.android.KenjinxNative",
            "org.kenjinx.android.native.KenjinxNative",
        )
        val path = rom.file.absolutePath
        for (cn in classes) {
            val cls = runCatching { Class.forName(cn) }.getOrNull() ?: continue
            val methods = try {
                cls.methods
            } catch (_: Throwable) {
                continue
            }
            for (m in methods) {
                val n = m.name
                if (!n.contains("Icon", true) && !n.contains("icon", true)) continue
                if (n.contains("GameInfo", true) || n.contains("GetGameInfo", true)) continue
                val raw = invokeIcon(m, path) ?: continue
                val bmp = when (raw) {
                    is ByteArray -> if (raw.size in 2000..400_000) BitmapFactory.decodeByteArray(raw, 0, raw.size) else null
                    is Bitmap -> raw
                    else -> null
                }
                if (bmp != null && bmp.width >= 64) {
                    BootLog.add("cover native ${cls.simpleName}.${m.name}")
                    return bmp
                }
            }
        }
        return null
    }

    private fun invokeIcon(m: java.lang.reflect.Method, path: String): Any? {
        return try {
            m.isAccessible = true
            val static = java.lang.reflect.Modifier.isStatic(m.modifiers)
            val obj: Any? = if (static) null else return null
            when (m.parameterTypes.size) {
                0 -> m.invoke(obj)
                1 -> {
                    val p = m.parameterTypes[0]
                    if (p == String::class.java) m.invoke(obj, path) else null
                }
                2 -> {
                    val a = m.parameterTypes[0]
                    val b = m.parameterTypes[1]
                    if (a == String::class.java && b == String::class.java) {
                        val ext = path.substringAfterLast('.', "nsp")
                        m.invoke(obj, path, ext)
                    } else null
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun pfs0Icon(f: File): Bitmap? {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 64) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic) != "PFS0") return null
            val hdr = ByteArray(12)
            raf.readFully(hdr)
            val le = ByteBuffer.wrap(hdr).order(ByteOrder.LITTLE_ENDIAN)
            val count = le.int
            val strSz = le.int
            if (count !in 1..80 || strSz !in 1..16384) return null
            val table = ByteArray(count * 0x18)
            raf.readFully(table)
            val names = ByteArray(strSz)
            raf.readFully(names)
            val data0 = raf.filePointer
            data class Ent(val off: Long, val size: Long)
            val ents = ArrayList<Ent>()
            for (i in 0 until count) {
                val e = ByteBuffer.wrap(table, i * 0x18, 0x18).order(ByteOrder.LITTLE_ENDIAN)
                val off = e.long
                val size = e.long
                if (size in 20_000L..2_500_000L) ents.add(Ent(data0 + off, size))
            }
            ents.sortBy { it.size }
            for (ent in ents.take(10)) {
                jpegScan(raf, ent.off, ent.size)?.let { return it }
            }
        }
        return null
    }

    private fun nroIcon(f: File): Bitmap? {
        RandomAccessFile(f, "r").use { raf ->
            return jpegScan(raf, 0, minOf(raf.length(), 3_000_000L))
        }
    }

    private fun jpegScan(raf: RandomAccessFile, start: Long, len: Long): Bitmap? {
        val n = len.coerceAtMost(2_500_000L).toInt()
        if (n < 2000) return null
        val buf = ByteArray(n)
        raf.seek(start)
        val got = raf.read(buf)
        if (got < 2000) return null
        var i = 0
        while (i < got - 4) {
            if (buf[i] == 0xFF.toByte() && buf[i + 1] == 0xD8.toByte() && buf[i + 2] == 0xFF.toByte()) {
                var j = i + 3
                while (j < got - 1) {
                    if (buf[j] == 0xFF.toByte() && buf[j + 1] == 0xD9.toByte()) {
                        val size = j + 2 - i
                        if (size in 2000..250_000) {
                            val bmp = BitmapFactory.decodeByteArray(buf, i, size)
                            if (bmp != null && bmp.width >= 64 && bmp.height >= 64) return bmp
                        }
                        break
                    }
                    j++
                }
            }
            i++
        }
        return null
    }

    private fun decodeFile(f: File): Bitmap? {
        return try {
            val opts = BitmapFactory.Options()
            opts.inJustDecodeBounds = true
            BitmapFactory.decodeFile(f.absolutePath, opts)
            if (opts.outWidth < 32 || opts.outHeight < 32) return null
            opts.inJustDecodeBounds = false
            opts.inSampleSize = if (opts.outWidth > 512) 2 else 1
            BitmapFactory.decodeFile(f.absolutePath, opts)
        } catch (_: Throwable) {
            null
        }
    }

    private fun save(f: File, bmp: Bitmap) {
        try {
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Throwable) {
        }
    }
}
