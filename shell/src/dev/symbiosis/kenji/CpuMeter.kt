package dev.symbiosis.kenji

import java.io.File

object CpuMeter {
    private var lastIdle = 0L
    private var lastTotal = 0L

    fun sample(): Int {
        return try {
            val line = File("/proc/stat").bufferedReader().use { it.readLine() } ?: return 0
            val p = line.trim().split(Regex("\\s+"))
            if (p.size < 5) return 0
            val nums = p.drop(1).take(8).mapNotNull { it.toLongOrNull() }
            if (nums.size < 4) return 0
            val idle = nums[3] + if (nums.size > 4) nums[4] else 0L
            val total = nums.sum()
            val dIdle = idle - lastIdle
            val dTotal = total - lastTotal
            lastIdle = idle
            lastTotal = total
            if (dTotal <= 0L) 0 else (((dTotal - dIdle) * 100L) / dTotal).toInt().coerceIn(0, 100)
        } catch (_: Throwable) {
            0
        }
    }
}
