package dev.symbiosis.kenji

import java.io.File
import java.io.FileInputStream
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * Minimal NCA header check. SwitchDevice parses every registered/00 during
 * javaInitialize; one undecryptable file returns false and poisons VFS.
 *
 * Magic sits at 0x200 after AES-XTS of the first 0xC00 bytes with
 * header_key from prod.keys. Sector size 0x200.
 */
object NcaHeader {
    private const val HEADER = 0xC00
    private const val SECTOR = 0x200

    fun readHeaderKey(prodKeys: File): ByteArray? {
        if (!prodKeys.isFile) return null
        val line = runCatching {
            prodKeys.readLines().firstOrNull { it.trim().startsWith("header_key") }
        }.getOrNull() ?: return null
        val hex = line.substringAfter('=').trim().replace(" ", "")
        if (hex.length != 64 || hex.any { it !in "0123456789abcdefABCDEF" }) return null
        return hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    fun isValid(file: File, headerKey: ByteArray?): Boolean {
        if (!file.isFile || file.length() < HEADER) return false
        val raw = ByteArray(HEADER)
        val n = runCatching {
            FileInputStream(file).use { it.read(raw) }
        }.getOrDefault(-1)
        if (n < HEADER) return false
        if (hasMagic(raw)) return true
        if (headerKey == null || headerKey.size != 32) return false
        val plain = runCatching { decryptXts(headerKey, raw) }.getOrNull() ?: return false
        return hasMagic(plain)
    }

    private fun hasMagic(header: ByteArray): Boolean {
        if (header.size < 0x204) return false
        val a = header[0x200].toInt().toChar()
        val b = header[0x201].toInt().toChar()
        val c = header[0x202].toInt().toChar()
        val d = header[0x203]
        return a == 'N' && b == 'C' && c == 'A' && d in 0x30..0x33
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
        while (off < data.size) {
            val tweak = ByteArray(16)
            var v = sector.toLong()
            for (i in 0 until 16) {
                tweak[i] = (v and 0xFF).toByte()
                v = v ushr 8
            }
            var t = twk.doFinal(tweak)
            var i = 0
            while (i < SECTOR && off + i < data.size) {
                val block = ByteArray(16)
                System.arraycopy(data, off + i, block, 0, 16)
                xorInPlace(block, t)
                val plain = dec.doFinal(block)
                xorInPlace(plain, t)
                System.arraycopy(plain, 0, out, off + i, 16)
                gf128Mul(t)
                i += 16
            }
            off += SECTOR
            sector++
        }
        return out
    }

    private fun xorInPlace(a: ByteArray, b: ByteArray) {
        for (i in a.indices) a[i] = (a[i].toInt() xor b[i].toInt()).toByte()
    }

    private fun gf128Mul(t: ByteArray) {
        var carry = 0
        for (i in 0 until 16) {
            val b = t[i].toInt() and 0xFF
            t[i] = ((b shl 1) or carry).toByte()
            carry = b ushr 7
        }
        if (carry != 0) t[0] = (t[0].toInt() xor 0x87).toByte()
    }
}
