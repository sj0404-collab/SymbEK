package dev.symbiosis.kenji

import android.os.Process
import android.util.Log

/**
 * Background micro-tasks. Pause when a game boots so we don't steal
 * disk/CPU from Kenji (10s → 40s). Lowest thread priority.
 */
object IdleWork {
    @Volatile var pause: Boolean = false

    fun aborted(): Boolean = pause

    fun bg(name: String, block: () -> Unit) {
        val t = Thread({
            try {
                Process.setThreadPriority(Process.THREAD_PRIORITY_LOWEST)
            } catch (_: Throwable) {
            }
            if (pause) return@Thread
            try {
                block()
            } catch (e: Throwable) {
                Log.e("KenjiSpace", name, e)
            }
        }, name)
        t.priority = Thread.MIN_PRIORITY
        t.start()
    }
}
