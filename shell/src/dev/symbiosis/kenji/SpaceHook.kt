package dev.symbiosis.kenji

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * In the official player: pocket load timer (Eden-style) + floating presets.
 * No extra windows, no FLAG_SECURE, no walking other apps.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG_PRESET = "space-preset"
    private const val TAG_TIMER = "space-timer"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var playing = false
    @Volatile private var waitTimer = false
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        BootLog.add("SpaceHook: колбэки активности")
    }

    fun armTimer() {
        waitTimer = true
        playing = false
    }

    fun isPlaying(): Boolean = playing
    fun waitingForGame(): Boolean = waitTimer && !playing

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        if (a.javaClass.name == "org.kenjinx.android.MainActivity") {
            playing = false
            BootLog.add("MainActivity.onCreate")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        unlock(activity)
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        activity.window?.decorView?.post {
            try {
                attach(activity)
                apply(activity)
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "hook", t)
            }
        }
        poll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        main.removeCallbacksAndMessages(activity)
    }

    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun unlock(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }

    private fun poll(activity: Activity) {
        main.removeCallbacksAndMessages(activity)
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing) return
                try {
                    apply(activity)
                } catch (_: Throwable) {
                }
                main.postAtTime(this, activity, SystemClock.uptimeMillis() + 1200)
            }
        }
        main.postAtTime(tick, activity, SystemClock.uptimeMillis() + 400)
    }

    private fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_TIMER) == null) {
            val timer = TimerBall(activity)
            timer.tag = TAG_TIMER
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            lp.bottomMargin = dp(activity, 88)
            content.addView(timer, lp)
            timer.elevation = 20f
            timer.visibility = View.GONE
        }
        if (content.findViewWithTag<View>(TAG_PRESET) == null) {
            val fab = PresetFab(activity)
            fab.tag = TAG_PRESET
            content.addView(fab, FrameLayout.LayoutParams(-1, -1))
            fab.elevation = 20f
            fab.visibility = View.GONE
        }
    }

    private fun apply(activity: Activity) {
        unlock(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val surface = hasGameSurface(content) || hasGameSurface(activity.window?.decorView)
        if (surface) playing = true
        DataSeed.allowEnsure = !playing
        val preset = content.findViewWithTag<View>(TAG_PRESET) as? PresetFab
        val timer = content.findViewWithTag<View>(TAG_TIMER) as? TimerBall
        if (playing) {
            preset?.visibility = View.VISIBLE
            if (waitTimer) {
                timer?.showRunning()
                if (timer?.heardAudio() == true) {
                    waitTimer = false
                    timer.dismiss()
                }
            } else {
                timer?.dismiss()
            }
        } else {
            preset?.hideSheet()
            preset?.visibility = View.GONE
            if (waitTimer) {
                timer?.showRunning()
                if (timer?.heardAudio() == true) {
                    waitTimer = false
                    timer.dismiss()
                }
            } else {
                timer?.dismiss()
            }
        }
    }

    private fun hasGameSurface(v: View?): Boolean {
        if (v == null) return false
        val n = v.javaClass.name
        val surface = n.contains("SurfaceView") || n.contains("GLSurface") ||
            n.contains("Vulkan", true) || n.contains("TextureView") ||
            n.contains("NativeSurface")
        if (surface && v.width > 200 && v.height > 200) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasGameSurface(v.getChildAt(i))) return true
        }
        return false
    }

    private fun dp(c: Context, v: Int): Int = Math.round(v * c.resources.displayMetrics.density)

    private class TimerBall(private val host: Activity) : FrameLayout(host) {
        private val label: TextView
        private var running = false
        private var t0 = 0L
        private var vx = 0f
        private var vy = 0f
        private var lastX = 0f
        private var lastY = 0f
        private var lastT = 0L
        private var dragging = false
        private val am = host.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val sec = (SystemClock.elapsedRealtime() - t0) / 1000L
                label.text = String.format("%d:%02d", sec / 60, sec % 60)
                if (heardAudio() || sec > 180) {
                    waitTimer = false
                    dismiss()
                    return
                }
                main.postDelayed(this, 200)
            }
        }
        private val physics = object : Runnable {
            override fun run() {
                if (!running || dragging || parent == null) return
                val p = parent as? ViewGroup ?: return
                var x = x + vx
                var y = y + vy
                val maxX = (p.width - width).toFloat().coerceAtLeast(0f)
                val maxY = (p.height - height).toFloat().coerceAtLeast(0f)
                if (x <= 0f) {
                    x = 0f
                    vx = -vx * 0.72f
                } else if (x >= maxX) {
                    x = maxX
                    vx = -vx * 0.72f
                }
                if (y <= 0f) {
                    y = 0f
                    vy = -vy * 0.72f
                } else if (y >= maxY) {
                    y = maxY
                    vy = -vy * 0.72f
                }
                vx *= 0.985f
                vy *= 0.985f
                this@TimerBall.x = x
                this@TimerBall.y = y
                if (kotlin.math.abs(vx) + kotlin.math.abs(vy) > 0.4f) {
                    main.postDelayed(this, 16)
                }
            }
        }

        init {
            val d = GradientDrawable()
            d.setColor(0xE616161C.toInt())
            d.setStroke(dp(1), MINT)
            d.cornerRadius = dp(20).toFloat()
            background = d
            setPadding(dp(14), dp(8), dp(14), dp(8))
            label = TextView(host)
            label.setTextColor(MINT)
            label.setTypeface(Typeface.MONOSPACE)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            label.text = "0:00"
            addView(label, LayoutParams(-2, -2, Gravity.CENTER))
        }

        fun showRunning() {
            visibility = VISIBLE
            if (!running) {
                running = true
                t0 = SystemClock.elapsedRealtime()
                vx = 0f
                vy = 0f
                main.removeCallbacks(tick)
                main.post(tick)
            }
        }

        fun heardAudio(): Boolean {
            if (SystemClock.elapsedRealtime() - t0 < 1600L) return false
            return try {
                if (am.isMusicActive) return true
                val cfgs = am.activePlaybackConfigurations ?: return false
                for (c in cfgs) {
                    val u = c.audioAttributes.usage
                    if (u == android.media.AudioAttributes.USAGE_GAME ||
                        u == android.media.AudioAttributes.USAGE_MEDIA ||
                        u == android.media.AudioAttributes.USAGE_UNKNOWN
                    ) return true
                }
                false
            } catch (_: Throwable) {
                false
            }
        }

        fun dismiss() {
            running = false
            main.removeCallbacks(tick)
            main.removeCallbacks(physics)
            visibility = GONE
        }

        override fun onTouchEvent(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lastX = event.rawX
                    lastY = event.rawY
                    lastT = SystemClock.uptimeMillis()
                    vx = 0f
                    vy = 0f
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val nx = event.rawX
                    val ny = event.rawY
                    val now = SystemClock.uptimeMillis()
                    val dt = (now - lastT).coerceAtLeast(1)
                    vx = (nx - lastX) * 16f / dt
                    vy = (ny - lastY) * 16f / dt
                    x += nx - lastX
                    y += ny - lastY
                    lastX = nx
                    lastY = ny
                    lastT = now
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    main.removeCallbacks(physics)
                    main.post(physics)
                    return true
                }
            }
            return false
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }

    private class PresetFab(private val host: Activity) : FrameLayout(host) {
        private val sheet: ScrollView
        private val box: LinearLayout

        init {
            isClickable = false
            isFocusable = false
            val btn = TextView(host)
            btn.text = "⚙"
            btn.gravity = Gravity.CENTER
            btn.setTextColor(Color.BLACK)
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val ball = GradientDrawable()
            ball.setColor(MINT)
            ball.cornerRadius = dp(22).toFloat()
            btn.background = ball
            addView(btn, LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
            btn.setOnClickListener { toggle() }
            box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(10), dp(10), dp(10), dp(10))
            sheet = ScrollView(host)
            sheet.setBackgroundColor(0xF214141A.toInt())
            sheet.addView(box)
            sheet.visibility = GONE
            val slp = LayoutParams(dp(220), dp(280), Gravity.CENTER_VERTICAL or Gravity.START)
            slp.marginStart = dp(52)
            addView(sheet, slp)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        fun hideSheet() {
            sheet.visibility = GONE
        }

        private fun toggle() {
            if (sheet.visibility == VISIBLE) {
                sheet.visibility = GONE
                return
            }
            box.removeAllViews()
            val t = TextView(host)
            t.text = "пресеты"
            t.setTextColor(MUTED)
            box.addView(t)
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                val b = Button(host)
                b.text = n
                b.isAllCaps = false
                b.setOnClickListener {
                    val msg = GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                    Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                }
                box.addView(b)
            }
            sheet.visibility = VISIBLE
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }
}
