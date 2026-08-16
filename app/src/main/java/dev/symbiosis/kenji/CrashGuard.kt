package dev.symbiosis.kenji

import android.content.Context
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Official Kenji's CrashHandler writes to MainActivity.AppPath, which is
 * still empty on the splash. A crash there dies twice: once from the fault,
 * once from a NullPointerException in the handler.
 */
object CrashGuard {
    private const val TAG = "KenjiSpace"

    fun install(context: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        val dir = File(context.filesDir, "logs").apply { mkdirs() }
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                val text = buildString {
                    append("=== $stamp thread=${thread.name} ===\n")
                    append(sw.toString())
                    append('\n')
                }
                File(dir, "crash.log").appendText(text)
                Log.e(TAG, text)
            }
            prev?.uncaughtException(thread, error)
        }
    }
}
