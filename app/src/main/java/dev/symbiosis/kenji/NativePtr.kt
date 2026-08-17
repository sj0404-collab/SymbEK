package dev.symbiosis.kenji

/**
 * ANativeWindow* is stored in Java as a signed Long. On arm64 the top bits
 * can be set (PAC / MTE / TBI), so a valid pointer looks negative.
 * Never use `handle > 0`. Only 0 and -1 are empty.
 */
internal object NativePtr {
    const val NONE: Long = -1L

    fun isSet(handle: Long): Boolean = handle != 0L && handle != NONE

    fun hex(handle: Long): String =
        if (!isSet(handle)) "none" else "0x" + java.lang.Long.toUnsignedString(handle, 16)
}
