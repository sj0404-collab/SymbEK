package dev.symbiosis.kenji

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.ArrayList
import java.util.Collections
import java.util.Locale

/**
 * Timestamped boot journal: what starts, in which order, firmware weight.
 * Also tails this process logcat so javaInitialize / JNI lines show up
 * the same way they did on the old 1.0.15 overlay.
 */
object BootLog {
    private val t0 = SystemClock.elapsedRealtime()
    private val lines = Collections.synchronizedList(ArrayList<String>())
    private val kernel = Collections.synchronizedList(ArrayList<String>())

    @Volatile var versionLine: String = "Kenji Space"
        private set

    @Volatile private var logcatStarted = false

    fun add(msg: String) {
        val sec = (SystemClock.elapsedRealtime() - t0) / 1000.0
        val line = String.format(Locale.US, "%6.3f  %s", sec, msg)
        synchronized(lines) {
            lines.add(line)
            while (lines.size > 240) lines.removeAt(0)
        }
        Log.i("KenjiSpace", line)
    }

    fun kernel(msg: String) {
        val clean = msg.trim()
        if (clean.isEmpty()) return
        synchronized(kernel) {
            if (kernel.isNotEmpty() && kernel[kernel.size - 1] == clean) return
            kernel.add(clean)
            while (kernel.size > 80) kernel.removeAt(0)
        }
        Log.i("KenjiSpace", "ядро $clean")
    }

    fun dump(): String {
        val a = synchronized(lines) { lines.toList() }
        return a.joinToString("\n")
    }

    fun tail(n: Int): String {
        val a = synchronized(lines) { lines.toList() }
        return a.takeLast(n).joinToString("\n")
    }

    fun kernelDump(): String {
        val a = synchronized(kernel) { kernel.toList() }
        return a.takeLast(18).joinToString("\n")
    }

    fun lastKernel(): String {
        val a = synchronized(kernel) { linesOf(kernel) }
        return a
    }

    /** 0–100 and a short live step name from boot + JNI lines. */
    fun stage(): Pair<Int, String> {
        val blob = (dump() + "\n" + kernelDump() + "\n" + lastKernel()).lowercase(Locale.US)
        var pct = 8
        var name = "старт"
        val steps = arrayOf(
            "seedprovider" to (12 to "процесс"),
            "apppath" to (20 to "данные"),
            "ключи" to (28 to "ключи"),
            "ensure.готово" to (34 to "прошивка"),
            "libkenjinx" to (40 to "библиотеки"),
            "setuplogsdir" to (46 to "логи"),
            "javainitialize" to (55 to "ядро"),
            "deviceinitialize" to (68 to "устройство"),
            "jnisetwindow" to (78 to "окно"),
            "vulkan" to (88 to "vulkan"),
            "mali" to (90 to "gpu"),
            "textureview" to (94 to "экран"),
            "surfaceview" to (94 to "экран"),
        )
        for ((key, pair) in steps) {
            if (blob.contains(key)) {
                pct = pair.first
                name = pair.second
            }
        }
        return pct to name
    }

    private fun linesOf(src: List<String>): String =
        src.lastOrNull().orEmpty()

    fun captureVersion(context: Context) {
        try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            @Suppress("DEPRECATION")
            versionLine = "Kenji Space ${pi.versionName} (${pi.versionCode})"
        } catch (_: Throwable) {
            versionLine = "Kenji Space"
        }
    }

    fun startLogcat() {
        // Never attach a perpetual logcat. It starved the system and
        // other apps were killed. JNI lines still arrive via BootLog.add.
    }

    fun deviceLine(): String {
        val bits = ArrayList<String>()
        try {
            if (Build.HARDWARE.isNotBlank()) bits.add(Build.HARDWARE)
            if (Build.BOARD.isNotBlank() && Build.BOARD != Build.HARDWARE) bits.add(Build.BOARD)
            bits.add("API ${Build.VERSION.SDK_INT}")
        } catch (_: Throwable) {
        }
        return if (bits.isEmpty()) "устройство: ?" else "устройство: ${bits.joinToString(" · ")}"
    }

    fun human(bytes: Long): String {
        if (bytes <= 0L) return "0 Б"
        if (bytes < 1024L) return "$bytes Б"
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f КБ", bytes / 1024.0)
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f МБ", bytes / (1024.0 * 1024.0))
        return String.format(Locale.US, "%.2f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    }

    fun registeredBytes(registered: File?): Long {
        val kids = registered?.listFiles() ?: return 0L
        var sum = 0L
        for (f in kids) {
            val low = f.name.lowercase(Locale.US)
            when {
                f.isFile && low.endsWith(".nca") && f.length() > 1000 -> sum += f.length()
                f.isDirectory && low.endsWith(".nca") -> {
                    val inner = File(f, "00")
                    if (inner.isFile && inner.length() > 1000) sum += inner.length()
                }
            }
        }
        return sum
    }

    private fun interesting(raw: String): Boolean {
        val s = raw
        if (s.contains("KenjiSpace")) return false
        val keys = arrayOf(
            "javaInitialize", "JavaInitialize", "JniInitialize", "deviceInitialize",
            "DeviceInitialize", "SetUpLogsDir", "GameHost", "Ryujinx", "kenjinx",
            "Kenjinx", "VirtualFileSystem", "firmware", "Firmware", "prod.keys",
            "NCA", "JniClose", "JniSignal", "JniSetWindow", "Emulation",
            "Vulkan", "Mali", "Adreno", "Turnip", "libkenjinx",
        )
        for (k in keys) if (s.contains(k)) return true
        return false
    }

    private fun shorten(raw: String): String {
        var s = raw
        val cut = s.indexOf(": ")
        if (cut in 1..40) s = s.substring(cut + 2)
        if (s.length > 220) s = s.substring(0, 220) + "…"
        return s
    }
}
