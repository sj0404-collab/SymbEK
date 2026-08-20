package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One bouncing clock. No dim, no bar, no percent. Does not eat touches.
 * Shown only while a game is actually booting.
 */
class BounceClock(host: Activity) : FrameLayout(host) {
    private val chip: LinearLayout
    private val wall: TextView
    private val elapsed: TextView
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private var running = false
    private var t0 = 0L
    private var x = 40f
    private var y = 80f
    private var vx = 0f
    private var vy = 0f
    private var lastNs = 0L
    private lateinit var cb: Choreographer.FrameCallback

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
        val d = resources.displayMetrics.density
        vx = 2.4f * d
        vy = 1.8f * d

        chip = LinearLayout(host)
        chip.orientation = LinearLayout.VERTICAL
        chip.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(0xE61C1C24.toInt())
        bg.cornerRadius = 18f * d
        bg.setStroke((1.5f * d).toInt(), 0xFF5EF0E6.toInt())
        chip.background = bg
        val padH = (16f * d).toInt()
        val padV = (8f * d).toInt()
        chip.setPadding(padH, padV, padH, padV)

        wall = TextView(host)
        wall.setTextColor(0xFF5EF0E6.toInt())
        wall.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
        wall.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        wall.gravity = Gravity.CENTER
        wall.text = "00:00:00"
        chip.addView(wall)

        elapsed = TextView(host)
        elapsed.setTextColor(0xFFB8B8C4.toInt())
        elapsed.setTypeface(Typeface.MONOSPACE)
        elapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        elapsed.gravity = Gravity.CENTER
        elapsed.text = "0:00"
        chip.addView(elapsed)

        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        cb = Choreographer.FrameCallback { ns ->
            if (!running) return@FrameCallback
            if (lastNs == 0L) lastNs = ns
            val dt = ((ns - lastNs).coerceAtMost(40_000_000L)) / 1_000_000f
            lastNs = ns
            val cw = chip.width.coerceAtLeast(dp(88))
            val ch = chip.height.coerceAtLeast(dp(44))
            val maxX = (width - cw).coerceAtLeast(0).toFloat()
            val maxY = (height - ch).coerceAtLeast(0).toFloat()
            x += vx * (dt / 16f)
            y += vy * (dt / 16f)
            if (x <= 0f) {
                x = 0f
                vx = kotlin.math.abs(vx)
            } else if (x >= maxX) {
                x = maxX
                vx = -kotlin.math.abs(vx)
            }
            if (y <= 0f) {
                y = 0f
                vy = kotlin.math.abs(vy)
            } else if (y >= maxY) {
                y = maxY
                vy = -kotlin.math.abs(vy)
            }
            chip.translationX = x
            chip.translationY = y
            wall.text = fmt.format(Date())
            val sec = if (t0 == 0L) 0L else (SystemClock.elapsedRealtime() - t0) / 1000L
            elapsed.text = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
            Choreographer.getInstance().postFrameCallback(cb)
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean = false
    override fun onTouchEvent(event: MotionEvent): Boolean = false
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

    fun start() {
        visibility = View.VISIBLE
        if (running) return
        running = true
        t0 = SystemClock.elapsedRealtime()
        lastNs = 0L
        if (width > 0) {
            x = (width / 3f)
            y = (height / 4f)
        }
        Choreographer.getInstance().postFrameCallback(cb)
    }

    fun stop() {
        running = false
        lastNs = 0L
        t0 = 0L
        visibility = View.GONE
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
