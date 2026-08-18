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
        if (logcatStarted) return
        logcatStarted = true
        val t = Thread({
            try {
                val pb = ProcessBuilder("logcat", "-v", "brief", "-T", "1")
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val r = BufferedReader(InputStreamReader(proc.inputStream))
                while (true) {
                    val raw = r.readLine() ?: break
                    if (interesting(raw)) kernel(shorten(raw))
                }
            } catch (t: Throwable) {
                add("logcat: ${t.message}")
            }
        }, "kenji-logcat")
        t.isDaemon = true
        t.start()
        add("logcat: слушаю ядро / JNI")
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
