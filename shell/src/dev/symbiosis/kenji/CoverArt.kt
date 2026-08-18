package dev.symbiosis.kenji

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Covers without touching libkenjinx in the launcher process. */
object CoverArt {
    fun cacheFile(context: Context, rom: GameRom): File {
        val dir = File(context.cacheDir, "covers")
        dir.mkdirs()
        val id = rom.titleId.ifBlank { rom.path.hashCode().toString(16) }
        return File(dir, "$id.jpg")
    }

    fun load(context: Context, rom: GameRom): Bitmap? {
        val cache = cacheFile(context, rom)
        if (cache.isFile && cache.length() > 32) {
            BitmapFactory.decodeFile(cache.absolutePath)?.let { return it }
        }
        findExisting(context, rom)?.let { found ->
            found.copyTo(cache, overwrite = true)
            return BitmapFactory.decodeFile(found.absolutePath)
        }
        val jpeg = extractNsp(context, rom.path) ?: return null
        if (jpeg.size < 64) return null
        cache.writeBytes(jpeg)
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
    }

    fun hasCover(context: Context, rom: GameRom): Boolean {
        val cache = cacheFile(context, rom)
        if (cache.isFile && cache.length() > 32) return true
        if (findExisting(context, rom) != null) return true
        val bmp = load(context, rom)
        bmp?.recycle()
        return bmp != null
    }

    private fun findExisting(context: Context, rom: GameRom): File? {
        val tid = rom.titleId.lowercase(Locale.US)
        val tidU = rom.titleId.uppercase(Locale.US)
        val romFile = if (rom.path.startsWith("/")) File(rom.path) else null
        if (romFile != null && romFile.isFile) {
            val stem = romFile.name.substringBeforeLast('.')
            var walk = romFile.parentFile
            var up = 0
            while (walk != null && up < 3) {
                listOf(
                    "$stem.jpg", "$stem.png", "$stem.jpeg",
                    "$tidU.jpg", "$tidU.png", "$tid.jpg", "$tid.png",
                    "cover.jpg", "cover.png", "icon.jpg", "icon.png",
                ).forEach { n ->
                    val f = File(walk, n)
                    if (f.isFile && f.length() > 32) return f
                }
                walk.listFiles()?.forEach { k ->
                    val kn = k.name.lowercase(Locale.US)
                    if (k.isFile && k.length() > 32 &&
                        (kn.endsWith(".jpg") || kn.endsWith(".png") || kn.endsWith(".jpeg"))
                    ) {
                        if ((tid.isNotEmpty() && kn.contains(tid)) ||
                            kn.contains(stem.lowercase(Locale.US)) ||
                            kn.contains("cover") || kn.contains("icon")
                        ) return k
                    }
                }
                walk = walk.parentFile
                up++
            }
        }
        val roots = ArrayList<File>()
        roots.add(DataSeed.playHome(context))
        DataSeed.edenDir(context)?.let { roots.add(File(it)) }
        val rels = listOf(
            "cache/game_list/$tid.png", "cache/game_list/$tidU.jpg",
            "cache/icons/$tid.png", "icons/$tidU.jpg",
            "games/$tidU/icon.jpg", "games/$tidU/icon.png",
        )
        for (root in roots) {
            for (r in rels) {
                val f = File(root, r)
                if (f.isFile && f.length() > 32) return f
            }
        }
        return null
    }

    private fun extractNsp(context: Context, path: String): ByteArray? {
        if (!path.startsWith("/")) return null
        val rom = File(path)
        if (!rom.isFile || !rom.name.lowercase(Locale.US).endsWith(".nsp")) return null
        val keysFile = File(DataSeed.playHome(context), "system/prod.keys")
        val map = readKeys(keysFile)
        val headerKey = map["header_key"] ?: return null
        if (headerKey.size != 32) return null
        var raf: RandomAccessFile? = null
        return try {
            raf = RandomAccessFile(rom, "r")
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.US_ASCII) != "PFS0") return null
            val count = Integer.reverseBytes(raf.readInt())
            val strSize = Integer.reverseBytes(raf.readInt())
            raf.readInt()
            if (count <= 0 || count > 4096 || strSize <= 0 || strSize > 1_000_000) return null
            val offs = LongArray(count)
            val sizes = LongArray(count)
            val nameOff = IntArray(count)
            for (i in 0 until count) {
                offs[i] = java.lang.Long.reverseBytes(raf.readLong())
                sizes[i] = java.lang.Long.reverseBytes(raf.readLong())
                nameOff[i] = Integer.reverseBytes(raf.readInt())
                raf.readInt()
            }
            val strings = ByteArray(strSize)
            raf.readFully(strings)
            val dataStart = 16L + 24L * count + strSize
            for (i in 0 until count) {
                val name = cstr(strings, nameOff[i]).lowercase(Locale.US)
                if (!name.endsWith(".nca") || name.endsWith(".cnmt.nca")) continue
                if (sizes[i] < 0xC00 || sizes[i] > 12L * 1024 * 1024) continue
                val nca = ByteArray(sizes[i].toInt())
                raf.seek(dataStart + offs[i])
                raf.readFully(nca)
                controlIcon(nca, map, headerKey)?.let { return it }
            }
            null
        } catch (_: Throwable) {
            null
        } finally {
            try { raf?.close() } catch (_: Exception) {}
        }
    }

    private fun controlIcon(nca: ByteArray, keys: Map<String, ByteArray>, headerKey: ByteArray): ByteArray? {
        return try {
            val header = decryptXts(headerKey, nca.copyOfRange(0, 0xC00))
            if (header.size < 0x340) return null
            if (header[0x200] != 'N'.code.toByte() || header[0x201] != 'C'.code.toByte() ||
                header[0x202] != 'A'.code.toByte()
            ) return null
            if ((header[0x205].toInt() and 0xFF) != 2) return null
            var hasRights = false
            for (i in 0 until 16) if (header[0x230 + i].toInt() != 0) hasRights = true
            if (hasRights) return null
            var keyGen = header[0x220].toInt() and 0xFF
            if (keyGen == 0) keyGen = header[0x206].toInt() and 0xFF
            val kaIndex = header[0x207].toInt() and 0xFF
            val kaName = if (kaIndex == 1) "ocean" else if (kaIndex == 2) "system" else "application"
            val kak = keys[String.format(Locale.US, "key_area_key_%s_%02x", kaName, keyGen)] ?: return null
            if (kak.size != 16) return null
            val decKeys = aesEcb(kak, header.copyOfRange(0x300, 0x340), false)
            if (decKeys.size < 48) return null
            val sectionKey = decKeys.copyOfRange(32, 48)
            val startSector = le32(header, 0x240)
            val endSector = le32(header, 0x244)
            if (endSector <= startSector) return null
            val start = startSector * 0x200
            var size = (endSector - startSector) * 0x200
            if (start < 0 || size <= 0 || start + size > nca.size) return null
            if (size > 8 * 1024 * 1024) size = 8 * 1024 * 1024
            var ctr = ByteArray(16)
            var section = aesCtr(sectionKey, ctr, nca.copyOfRange(start, start + size), 0)
            if (section.size > 0x148) {
                val stored = section.copyOfRange(0x140, 0x148)
                if (stored.any { it.toInt() != 0 }) {
                    ctr = ByteArray(16)
                    System.arraycopy(stored, 0, ctr, 0, 8)
                    section = aesCtr(sectionKey, ctr, nca.copyOfRange(start, start + size), 0)
                }
            }
            findJpeg(section)
        } catch (_: Throwable) {
            null
        }
    }

    private fun findJpeg(data: ByteArray): ByteArray? {
        var start = -1
        for (i in 0 until data.size - 3) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD8 &&
                (data[i + 2].toInt() and 0xFF) == 0xFF
            ) {
                start = i
                break
            }
        }
        if (start < 0) return null
        for (i in start + 3 until data.size - 1) {
            if ((data[i].toInt() and 0xFF) == 0xFF && (data[i + 1].toInt() and 0xFF) == 0xD9) {
                val len = i + 2 - start
                if (len in 64..600_000) return data.copyOfRange(start, start + len)
            }
        }
        return null
    }

    private fun readKeys(prod: File): Map<String, ByteArray> {
        val map = HashMap<String, ByteArray>()
        if (!prod.isFile) return map
        try {
            prod.readLines(Charsets.UTF_8).forEach { raw ->
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || !line.contains("=")) return@forEach
                val eq = line.indexOf('=')
                val k = line.substring(0, eq).trim().lowercase(Locale.US)
                val hex = line.substring(eq + 1).trim().replace(" ", "")
                if (hex.length < 32 || hex.length % 2 != 0) return@forEach
                if (hex.any { Character.digit(it, 16) < 0 }) return@forEach
                val b = ByteArray(hex.length / 2)
                for (i in b.indices) b[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                map[k] = b
            }
        } catch (_: Exception) {
        }
        return map
    }

    private fun decryptXts(key: ByteArray, data: ByteArray): ByteArray {
        val k1 = SecretKeySpec(key, 0, 16, "AES")
        val k2 = SecretKeySpec(key, 16, 16, "AES")
        val dec = Cipher.getInstance("AES/ECB/NoPadding")
        val twk = Cipher.getInstance("AES/ECB/NoPadding")
        dec.init(Cipher.DECRYPT_MODE, k1)
        twk.init(Cipher.ENCRYPT_MODE, k2)
        val out = ByteArray(data.size)
        var sector = 0
        var off = 0
        val SECTOR = 0x200
        while (off < data.size) {
            val tweak = ByteArray(16)
            var v = sector.toLong()
            for (i in 0 until 16) {
                tweak[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            val t = twk.doFinal(tweak)
            var i = 0
            while (i < SECTOR && off + i < data.size) {
                val block = data.copyOfRange(off + i, off + i + 16)
                xor(block, t)
                val plain = dec.doFinal(block)
                xor(plain, t)
                System.arraycopy(plain, 0, out, off + i, 16)
                gf128(t)
                i += 16
            }
            off += SECTOR
            sector++
        }
        return out
    }

    private fun aesEcb(key: ByteArray, data: ByteArray, encrypt: Boolean): ByteArray {
        val c = Cipher.getInstance("AES/ECB/NoPadding")
        c.init(if (encrypt) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"))
        return c.doFinal(data)
    }

    private fun aesCtr(key: ByteArray, ctr0: ByteArray, data: ByteArray, offset: Long): ByteArray {
        val ctr = ctr0.copyOf()
        var blocks = offset / 16
        var i = 15
        while (i >= 8 && blocks > 0) {
            val v = (ctr[i].toInt() and 0xFF) + (blocks and 0xFF)
            ctr[i] = v.toByte()
            blocks = (blocks ushr 8) + (v ushr 8)
            i--
        }
        val c = Cipher.getInstance("AES/CTR/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(ctr))
        return c.doFinal(data)
    }

    private fun xor(a: ByteArray, b: ByteArray) {
        for (i in a.indices) a[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    }

    private fun gf128(t: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val b = t[i].toInt() and 0xFF
            t[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }

    private fun le32(b: ByteArray, off: Int): Int =
        ByteBuffer.wrap(b, off, 4).order(ByteOrder.LITTLE_ENDIAN).int

    private fun cstr(s: ByteArray, off: Int): String {
        if (off < 0 || off >= s.size) return ""
        var e = off
        while (e < s.size && s[e].toInt() != 0) e++
        return String(s, off, e - off, Charsets.UTF_8)
    }
}
