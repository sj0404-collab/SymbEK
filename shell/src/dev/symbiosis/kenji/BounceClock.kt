package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Idle hourglass (cheap pulse). Flick to ricochet as anti-stress.
 * Tap hides. Long-press disables until turned on in the panel.
 * No 60fps loop unless the user threw it.
 */
class BounceClock(private val host: Activity) : FrameLayout(host) {
    private val chip: LinearLayout
    private val sand: TextView
    private val elapsed: TextView
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var bouncing = false
    private var t0 = 0L
    private var x = 48f
    private var y = 120f
    private var vx = 0f
    private var vy = 0f
    private var lastNs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastMoveAt = 0L
    private var moved = false
    private var pulseOn = false
    private lateinit var fly: Choreographer.FrameCallback

    companion object {
        private const val PREF = "clock_off"

        fun enabled(c: Context): Boolean =
            !c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getBoolean(PREF, false)

        fun setEnabled(c: Context, on: Boolean) {
            c.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
                .edit().putBoolean(PREF, !on).commit()
        }
    }

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
        val d = resources.displayMetrics.density

        chip = LinearLayout(host)
        chip.orientation = LinearLayout.VERTICAL
        chip.gravity = Gravity.CENTER
        val bg = GradientDrawable()
        bg.setColor(0xE61C1C24.toInt())
        bg.cornerRadius = 22f * d
        bg.setStroke((1.2f * d).toInt(), 0xFF5EF0E6.toInt())
        chip.background = bg
        chip.setPadding((14f * d).toInt(), (8f * d).toInt(), (14f * d).toInt(), (8f * d).toInt())
        chip.isClickable = true

        sand = TextView(host)
        sand.text = "⏳"
        sand.gravity = Gravity.CENTER
        sand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        chip.addView(sand)

        elapsed = TextView(host)
        elapsed.setTextColor(0xFFB8B8C4.toInt())
        elapsed.setTypeface(Typeface.MONOSPACE)
        elapsed.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        elapsed.gravity = Gravity.CENTER
        elapsed.text = "0:00"
        chip.addView(elapsed)

        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        fly = Choreographer.FrameCallback { ns ->
            if (!running || !bouncing) return@FrameCallback
            if (lastNs == 0L) lastNs = ns
            val dt = ((ns - lastNs).coerceAtMost(32_000_000L)) / 1_000_000f
            lastNs = ns
            val cw = chip.width.coerceAtLeast(dp(56))
            val ch = chip.height.coerceAtLeast(dp(56))
            val maxX = (width - cw).coerceAtLeast(0).toFloat()
            val maxY = (height - ch).coerceAtLeast(0).toFloat()
            x += vx * (dt / 16f)
            y += vy * (dt / 16f)
            val damp = 0.985f
            vx *= damp
            vy *= damp
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
            if (kotlin.math.abs(vx) + kotlin.math.abs(vy) < 0.12f * d) {
                bouncing = false
                lastNs = 0L
                return@FrameCallback
            }
            Choreographer.getInstance().postFrameCallback(fly)
        }

        val longPress = Runnable {
            if (!moved && running) {
                setEnabled(host, false)
                stop()
                Toast.makeText(host, "часы выкл · включите в панели Space", Toast.LENGTH_LONG).show()
            }
        }
        chip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    moved = false
                    downX = ev.rawX
                    downY = ev.rawY
                    lastMoveX = ev.rawX
                    lastMoveY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    bouncing = false
                    main.removeCallbacks(longPress)
                    main.postDelayed(longPress, 550)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dx * dx + dy * dy > 12f * 12f * d * d) {
                        moved = true
                        main.removeCallbacks(longPress)
                    }
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastMoveAt).coerceAtLeast(1L)
                    vx = (ev.rawX - lastMoveX) / dt * 16f
                    vy = (ev.rawY - lastMoveY) / dt * 16f
                    lastMoveX = ev.rawX
                    lastMoveY = ev.rawY
                    lastMoveAt = now
                    x += ev.rawX - downX
                    y += ev.rawY - downY
                    downX = ev.rawX
                    downY = ev.rawY
                    val cw = chip.width.coerceAtLeast(1)
                    val ch = chip.height.coerceAtLeast(1)
                    x = x.coerceIn(0f, (width - cw).coerceAtLeast(0).toFloat())
                    y = y.coerceIn(0f, (height - ch).coerceAtLeast(0).toFloat())
                    chip.translationX = x
                    chip.translationY = y
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    if (!moved) {
                        stop()
                        Toast.makeText(host, "часы скрыты · долгое нажатие — выкл совсем", Toast.LENGTH_SHORT).show()
                    } else {
                        val speed = kotlin.math.abs(vx) + kotlin.math.abs(vy)
                        if (speed > 0.8f * d) {
                            bouncing = true
                            lastNs = 0L
                            Choreographer.getInstance().postFrameCallback(fly)
                        }
                    }
                    true
                }
                else -> false
            }
        }
        chip.setOnLongClickListener {
            setEnabled(host, false)
            stop()
            Toast.makeText(host, "часы выкл · включите в панели Space", Toast.LENGTH_LONG).show()
            true
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE || !running) return false
        val loc = IntArray(2)
        chip.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        val hit = x >= loc[0] && x < loc[0] + chip.width && y >= loc[1] && y < loc[1] + chip.height
        if (!hit && ev.actionMasked == MotionEvent.ACTION_DOWN) return false
        return if (hit || ev.actionMasked != MotionEvent.ACTION_DOWN) super.dispatchTouchEvent(ev) else false
    }

    private val idle = object : Runnable {
        override fun run() {
            if (!running || bouncing) return
            pulseOn = !pulseOn
            sand.text = if (pulseOn) "⌛" else "⏳"
            sand.alpha = if (pulseOn) 0.72f else 1f
            val sec = if (t0 == 0L) 0L else (SystemClock.elapsedRealtime() - t0) / 1000L
            elapsed.text = String.format(Locale.US, "%d:%02d", sec / 60, sec % 60)
            main.postDelayed(this, 480)
        }
    }

    fun start() {
        if (!enabled(host)) {
            visibility = View.GONE
            return
        }
        visibility = View.VISIBLE
        if (running) return
        running = true
        bouncing = false
        t0 = SystemClock.elapsedRealtime()
        if (width > 0) {
            x = 24f
            y = (height * 0.35f)
        }
        chip.translationX = x
        chip.translationY = y
        main.removeCallbacks(idle)
        main.post(idle)
    }

    fun stop() {
        running = false
        bouncing = false
        lastNs = 0L
        t0 = 0L
        main.removeCallbacks(idle)
        visibility = View.GONE
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
