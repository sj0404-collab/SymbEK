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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Compact corner chip: clock, session, battery, optional FPS, ⚙ overlay.
 * Tap does not open the shelf menu. Drag to move. Flick = ricochet.
 * Tap while bouncing stops and docks. Hide via ⚙ → индикаторы.
 */
class BounceClock(private val host: Activity) : FrameLayout(host) {
    private val chip: LinearLayout
    private val timeLine: TextView
    private val sessionLine: TextView
    private val battLine: TextView
    private val fpsLine: TextView
    private val gear: TextView
    private val overlay: OverlayPanel
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var bouncing = false
    private var t0 = 0L
    private var startPct = -1
    private var x = 0f
    private var y = 0f
    private var vx = 0f
    private var vy = 0f
    private var lastNs = 0L
    private var downX = 0f
    private var downY = 0f
    private var lastMoveX = 0f
    private var lastMoveY = 0f
    private var lastMoveAt = 0L
    private var moved = false
    private var frames = 0
    private var fps = 0.0
    private var fpsNs = 0L
    private lateinit var fly: Choreographer.FrameCallback
    private lateinit var fpsCb: Choreographer.FrameCallback
    private val clockFmt = SimpleDateFormat("dd.MM  HH:mm", Locale.US)

    companion object {
        fun enabled(c: Context): Boolean = LayerBank.anySpaceOnGame(c)

        fun setEnabled(c: Context, on: Boolean) {
            LayerBank.setChip(c, on)
        }
    }

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
        val d = resources.displayMetrics.density

        chip = LinearLayout(host)
        chip.orientation = LinearLayout.HORIZONTAL
        chip.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xCC1C1C24.toInt())
        bg.cornerRadius = 10f * d
        bg.setStroke((1f * d).toInt(), 0x995EF0E6.toInt())
        chip.background = bg
        chip.setPadding((8f * d).toInt(), (5f * d).toInt(), (6f * d).toInt(), (5f * d).toInt())
        chip.isClickable = true

        val col = LinearLayout(host)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.START

        timeLine = line(0xFFF2F2F6.toInt(), 11f, true)
        sessionLine = line(0xFFB8B8C4.toInt(), 10f, false)
        battLine = line(0xFFB8B8C4.toInt(), 10f, false)
        fpsLine = line(0xFFFF3B30.toInt(), 10f, false)
        col.addView(timeLine)
        col.addView(sessionLine)
        col.addView(battLine)
        col.addView(fpsLine)
        chip.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

        gear = TextView(host)
        gear.text = "⚙"
        gear.gravity = Gravity.CENTER
        gear.setTextColor(Color.BLACK)
        gear.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val ball = GradientDrawable()
        ball.setColor(0xFF5EF0E6.toInt())
        ball.cornerRadius = 11f * d
        gear.background = ball
        val glp = LinearLayout.LayoutParams(dp(22), dp(22))
        glp.marginStart = dp(6)
        chip.addView(gear, glp)
        gear.setOnClickListener { overlay.toggle() }

        addView(chip, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))

        overlay = OverlayPanel(host)
        addView(overlay, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        fly = Choreographer.FrameCallback { ns ->
            if (!running || !bouncing) return@FrameCallback
            if (lastNs == 0L) lastNs = ns
            val dt = ((ns - lastNs).coerceAtMost(32_000_000L)) / 1_000_000f
            lastNs = ns
            val cw = chip.width.coerceAtLeast(dp(48))
            val ch = chip.height.coerceAtLeast(dp(28))
            val maxX = (width - cw).coerceAtLeast(0).toFloat()
            val maxY = (height - ch).coerceAtLeast(0).toFloat()
            x += vx * (dt / 16f)
            y += vy * (dt / 16f)
            vx *= 0.985f
            vy *= 0.985f
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
                savePos()
                return@FrameCallback
            }
            Choreographer.getInstance().postFrameCallback(fly)
        }

        fpsCb = Choreographer.FrameCallback { ns ->
            if (!running || !LayerBank.statsOn(host)) return@FrameCallback
            frames++
            if (fpsNs == 0L) fpsNs = ns
            val dt = ns - fpsNs
            if (dt >= 400_000_000L) {
                fps = frames * 1_000_000_000.0 / dt
                frames = 0
                fpsNs = ns
            }
            Choreographer.getInstance().postFrameCallback(fpsCb)
        }

        chip.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (LayerBank.gearOn(host) && gear.visibility == View.VISIBLE) {
                        val gl = IntArray(2)
                        gear.getLocationOnScreen(gl)
                        val gx = ev.rawX
                        val gy = ev.rawY
                        if (gx >= gl[0] && gx < gl[0] + gear.width && gy >= gl[1] && gy < gl[1] + gear.height) {
                            return@setOnTouchListener false
                        }
                    }
                    moved = false
                    bouncing = false
                    downX = ev.rawX
                    downY = ev.rawY
                    lastMoveX = ev.rawX
                    lastMoveY = ev.rawY
                    lastMoveAt = SystemClock.uptimeMillis()
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (dx * dx + dy * dy > 8f * 8f * d * d) moved = true
                    if (!moved) return@setOnTouchListener true
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
                    clamp()
                    chip.translationX = x
                    chip.translationY = y
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!moved) {
                        if (bouncing) {
                            bouncing = false
                            savePos()
                        }
                    } else {
                        val speed = kotlin.math.abs(vx) + kotlin.math.abs(vy)
                        if (speed > 0.8f * d) {
                            bouncing = true
                            lastNs = 0L
                            Choreographer.getInstance().postFrameCallback(fly)
                        } else {
                            savePos()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE || !running) return false
        if (overlay.visibility == View.VISIBLE) return overlay.dispatchTouchEvent(ev)
        val loc = IntArray(2)
        chip.getLocationOnScreen(loc)
        val hx = ev.rawX
        val hy = ev.rawY
        val hit = hx >= loc[0] && hx < loc[0] + chip.width && hy >= loc[1] && hy < loc[1] + chip.height
        if (!hit && ev.actionMasked == MotionEvent.ACTION_DOWN) return false
        return if (hit || ev.actionMasked != MotionEvent.ACTION_DOWN) super.dispatchTouchEvent(ev) else false
    }

    private val idle = object : Runnable {
        override fun run() {
            if (!running) return
            paint()
            main.postDelayed(this, 1000)
        }
    }

    private fun paint() {
        val clock = LayerBank.chipOn(host)
        val session = LayerBank.sessionOn(host)
        val batt = LayerBank.batteryOn(host)
        val stats = LayerBank.statsOn(host)
        val g = LayerBank.gearOn(host)
        timeLine.visibility = if (clock) View.VISIBLE else View.GONE
        sessionLine.visibility = if (session) View.VISIBLE else View.GONE
        battLine.visibility = if (batt) View.VISIBLE else View.GONE
        fpsLine.visibility = if (stats) View.VISIBLE else View.GONE
        gear.visibility = if (g) View.VISIBLE else View.GONE
        if (clock) timeLine.text = clockFmt.format(Date())
        if (session) {
            val sec = if (t0 == 0L) 0L else (SystemClock.elapsedRealtime() - t0) / 1000L
            sessionLine.text = String.format(Locale.US, "сессия %d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60)
        }
        if (batt) {
            val s = BatteryMeter.snap(host)
            val hours = if (t0 == 0L) 0.0 else (SystemClock.elapsedRealtime() - t0) / 3_600_000.0
            val drain = if (startPct >= 0 && hours > 0.08 && !s.charging) {
                val d = (startPct - s.percent) / hours
                if (d >= 0.5) String.format(Locale.US, "  −%.0f%%/ч", d) else ""
            } else ""
            val ch = if (s.charging) " ⚡" else ""
            val ma = s.ma?.let { "  ${it}мА" } ?: ""
            battLine.text = "🔋 ${s.percent}%$ch$drain$ma"
        }
        if (stats) {
            val cpu = CpuMeter.sample()
            fpsLine.text = String.format(Locale.US, "CPU %d%%  FPS %.0f", cpu, fps)
            if (fps >= 1.0) LoadOverlay.onGameFps(host)
        }
        chip.visibility = if (clock || session || batt || stats || g) View.VISIBLE else View.GONE
    }

    fun start() {
        if (!enabled(host)) {
            visibility = View.GONE
            overlay.close()
            return
        }
        visibility = View.VISIBLE
        if (running) {
            paint()
            return
        }
        running = true
        bouncing = false
        t0 = SystemClock.elapsedRealtime()
        startPct = BatteryMeter.snap(host).percent
        post {
            restorePos()
            paint()
        }
        main.removeCallbacks(idle)
        main.post(idle)
        if (LayerBank.statsOn(host)) {
            frames = 0
            fpsNs = 0L
            Choreographer.getInstance().postFrameCallback(fpsCb)
        }
    }

    fun stop() {
        running = false
        bouncing = false
        lastNs = 0L
        main.removeCallbacks(idle)
        overlay.close()
        visibility = View.GONE
    }

    private fun restorePos() {
        val p = host.getSharedPreferences("kenji_space", 0)
        val cw = chip.width.coerceAtLeast(dp(72))
        val ch = chip.height.coerceAtLeast(dp(36))
        val defX = (width - cw - dp(10)).coerceAtLeast(0).toFloat()
        val defY = dp(40).toFloat()
        x = p.getFloat("chip_x", defX)
        y = p.getFloat("chip_y", defY)
        if (x.isNaN() || y.isNaN()) {
            x = defX
            y = defY
        }
        clamp()
        chip.translationX = x
        chip.translationY = y
    }

    private fun savePos() {
        host.getSharedPreferences("kenji_space", 0).edit()
            .putFloat("chip_x", x).putFloat("chip_y", y).commit()
    }

    private fun clamp() {
        val cw = chip.width.coerceAtLeast(1)
        val ch = chip.height.coerceAtLeast(1)
        x = x.coerceIn(0f, (width - cw).coerceAtLeast(0).toFloat())
        y = y.coerceIn(0f, (height - ch).coerceAtLeast(0).toFloat())
    }

    private fun line(color: Int, sp: Float, bold: Boolean): TextView {
        val t = TextView(host)
        t.setTextColor(color)
        t.setTypeface(if (bold) Typeface.MONOSPACE else Typeface.MONOSPACE)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        t.includeFontPadding = false
        return t
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
