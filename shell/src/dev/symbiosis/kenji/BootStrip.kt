package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** Minimal boot hint at the bottom. Not Kenji's giant static Loading. */
class BootStrip(private val host: Activity) : FrameLayout(host) {
    private val bar: View
    private val label: TextView
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var t0 = 0L
    private var pulse = 0

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val glass = GradientDrawable()
        glass.setColor(0xCC14141A.toInt())
        glass.cornerRadius = dp(12).toFloat()
        box.background = glass
        box.setPadding(dp(14), dp(8), dp(14), dp(8))

        bar = View(host)
        val fill = GradientDrawable()
        fill.setColor(0xFF5EF0E6.toInt())
        fill.cornerRadius = dp(2).toFloat()
        bar.background = fill
        box.addView(bar, LinearLayout.LayoutParams(dp(48), dp(3)))

        label = TextView(host)
        label.setTextColor(0xFFB8B8C4.toInt())
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        label.gravity = Gravity.CENTER
        label.setPadding(0, dp(4), 0, 0)
        box.addView(label)

        val lp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
        lp.bottomMargin = dp(18)
        addView(box, lp)
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean = false

    fun start() {
        visibility = View.VISIBLE
        if (running) return
        running = true
        t0 = SystemClock.elapsedRealtime()
        main.removeCallbacks(tick)
        main.post(tick)
    }

    fun stop() {
        running = false
        main.removeCallbacks(tick)
        visibility = View.GONE
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            pulse = 1 - pulse
            bar.alpha = if (pulse == 0) 0.35f else 1f
            val sec = (SystemClock.elapsedRealtime() - t0) / 1000L
            label.text = "шейдеры · $sec с"
            main.postDelayed(this, 420)
        }
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
