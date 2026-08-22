package dev.symbiosis.kenji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Game icon without loading the whole NSP and without kenjinx JNI.
 * PFS0 header + smallest NCA slices, then JPEG scan. Cache on disk.
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
            try {
                val bmp = extract(context, rom)
                if (bmp != null) {
                    mem[k] = bmp
                    save(disk, bmp)
                    done(bmp)
                } else {
                    failed.add(k)
                    done(null)
                }
            } catch (t: Throwable) {
                Log.w("KenjiSpace", "cover ${rom.file.name}", t)
                failed.add(k)
                done(null)
            }
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
        kenjiCache(context, rom)?.let { return it }
        val f = rom.file
        if (!f.isFile) return null
        val low = f.name.lowercase(Locale.US)
        if (low.endsWith(".nro")) return nroIcon(f)
        return pfs0Icon(f) ?: jpegScan(f, 0L, minOf(f.length(), 12L * 1024L * 1024L))
    }

    private fun sidecar(rom: RomList.Rom): Bitmap? {
        val parent = rom.file.parentFile ?: return null
        val base = rom.file.nameWithoutExtension
        val names = arrayOf(
            "$base.jpg", "$base.jpeg", "$base.png",
            "${rom.titleId}.jpg", "${rom.titleId}.png",
            "cover.jpg", "icon.jpg",
        )
        for (n in names) {
            if (n.startsWith(".") || n.startsWith("null")) continue
            val f = File(parent, n)
            if (f.isFile && f.length() in 800L..800_000L) decodeFile(f)?.let { return it }
        }
        return null
    }

    private fun kenjiCache(context: Context, rom: RomList.Rom): Bitmap? {
        if (rom.titleId.length < 8) return null
        val id = rom.titleId.lowercase(Locale.US)
        val roots = ArrayList<File>()
        roots.add(DataSeed.playHome(context))
        DataSeed.edenDir(context)?.let { roots.add(File(it)) }
        val rels = arrayOf(
            "space_icons/$id.jpg",
            "games/$id.jpg",
            "games/$id.png",
            "cache/$id.jpg",
            "cache/icons/$id.jpg",
            "sdmc/atmosphere/contents/$id/icon.jpg",
        )
        for (root in roots) {
            for (rel in rels) {
                val f = File(root, rel)
                if (f.isFile && f.length() in 800L..800_000L) decodeFile(f)?.let { return it }
            }
        }
        return null
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
            if (count !in 1..64 || strSz !in 1..8192) return null
            val table = ByteArray(count * 0x18)
            raf.readFully(table)
            val names = ByteArray(strSz)
            raf.readFully(names)
            val data0 = raf.filePointer
            data class Ent(val off: Long, val size: Long, val name: String)
            val ents = ArrayList<Ent>(count)
            for (i in 0 until count) {
                val e = ByteBuffer.wrap(table, i * 0x18, 0x18).order(ByteOrder.LITTLE_ENDIAN)
                val off = e.long
                val size = e.long
                val no = e.int
                if (no !in 0 until strSz) continue
                var end = no
                while (end < names.size && names[end] != 0.toByte()) end++
                val name = String(names, no, end - no)
                if (size in 8_000L..4_000_000L) ents.add(Ent(data0 + off, size, name))
            }
            ents.sortBy { it.size }
            for (ent in ents.take(8)) {
                jpegScan(raf, ent.off, ent.size)?.let { return it }
            }
        }
        return null
    }

    private fun nroIcon(f: File): Bitmap? {
        RandomAccessFile(f, "r").use { raf ->
            if (raf.length() < 0x80) return null
            raf.seek(0x10)
            val mag = ByteArray(4)
            raf.readFully(mag)
            if (String(mag) != "NRO0") return jpegScan(raf, 0, minOf(raf.length(), 2_000_000L))
            return jpegScan(raf, 0, minOf(raf.length(), 4_000_000L))
        }
    }

    private fun jpegScan(f: File, start: Long, len: Long): Bitmap? {
        RandomAccessFile(f, "r").use { return jpegScan(it, start, len) }
    }

    private fun jpegScan(raf: RandomAccessFile, start: Long, len: Long): Bitmap? {
        val n = len.coerceAtMost(4_000_000L).toInt()
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
                        if (size in 2000..200_000) {
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

    private fun decodeFile(f: File): Bitmap? = try {
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

    private fun save(f: File, bmp: Bitmap) {
        try {
            f.parentFile?.mkdirs()
            f.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        } catch (_: Throwable) {
        }
    }
}
