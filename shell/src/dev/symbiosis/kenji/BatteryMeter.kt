package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Real battery from sticky BATTERY_CHANGED + BatteryManager. No fake percents. */
object BatteryMeter {
    data class Snap(
        val percent: Int,
        val charging: Boolean,
        val ma: Int?,
    )

    fun snap(c: Context): Snap {
        var percent = -1
        var charging = false
        try {
            val i = c.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (i != null) {
                val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
                if (lvl >= 0) percent = (lvl * 100) / sc
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING ||
                    st == BatteryManager.BATTERY_STATUS_FULL
            }
        } catch (_: Throwable) {
        }
        if (percent < 0) {
            try {
                val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
                percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } catch (_: Throwable) {
            }
        }
        val ma = currentMa(c)
        return Snap(percent.coerceIn(0, 100), charging, ma)
    }

    private fun currentMa(c: Context): Int? {
        return try {
            val bm = c.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val ua = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            if (ua == Long.MIN_VALUE || ua == 0L) null
            else (kotlin.math.abs(ua) / 1000L).toInt().coerceAtMost(20000)
        } catch (_: Throwable) {
            null
        }
    }
}
