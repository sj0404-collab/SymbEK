package dev.symbiosis.kenji

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
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
import android.view.Window
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Launcher: status strip, + at the bottom, «Начать игру» when ready.
 * Load: a small draggable stopwatch only after that button; gone on audio.
 * In-game: only a floating presets button. Official FPS HUD is left alone.
 * No extra windows, no FLAG_SECURE, no logcat, no walking other apps' views.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val TAG_PLUS = "space-plus"
    private const val TAG_START = "space-start"
    private const val TAG_TIMER = "space-timer"
    private const val TAG_PRESET = "space-preset"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val BG = 0xFF2A2A32.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var seeded = false
    @Volatile private var playing = false
    @Volatile private var waitTimer = false
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        BootLog.add("SpaceHook: колбэки активности")
    }

    fun isPlaying(): Boolean = playing
    fun waitingForGame(): Boolean = waitTimer && !playing

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        if (a.javaClass.name == "org.kenjinx.android.MainActivity") {
            playing = false
            waitTimer = false
            BootLog.add("MainActivity.onCreate")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        unlockSystem(activity)
        hookLongPress(activity)
        activity.window?.decorView?.post {
            try {
                attach(activity)
                applyMode(activity)
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "overlay", t)
            }
        }
        if (!seeded && !hasGameSurface(activity.findViewById(android.R.id.content))) {
            seeded = true
            Thread({
                try {
                    AccessFix.repair(activity)
                    DataSeed.ensure(activity)
                    SettingsBank.applyDefaultOnce(activity)
                    activity.runOnUiThread {
                        (activity.findViewById<ViewGroup>(android.R.id.content)
                            ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                        applyMode(activity)
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("KenjiSpace", "bg", t)
                }
            }, "kenji-seed").start()
        }
        poll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        main.removeCallbacksAndMessages(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        (content?.findViewWithTag<View>(TAG) as? Panel)?.collapse()
    }

    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun unlockSystem(activity: Activity) {
        try {
            val w = activity.window ?: return
            w.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        } catch (_: Throwable) {
        }
    }

    private fun poll(activity: Activity) {
        main.removeCallbacksAndMessages(activity)
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing) return
                try {
                    applyMode(activity)
                } catch (_: Throwable) {
                }
                val gap = when {
                    playing -> 3000
                    waitTimer -> 400
                    else -> 1600
                }
                main.postAtTime(this, activity, SystemClock.uptimeMillis() + gap)
            }
        }
        main.postAtTime(tick, activity, SystemClock.uptimeMillis() + 700)
    }

    private fun hookLongPress(activity: Activity) {
        val w = activity.window ?: return
        if (w.callback is CoverHold) return
        w.callback = CoverHold(activity, w.callback)
    }

    private fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) == null) {
            val panel = Panel(activity)
            panel.tag = TAG
            val lp = if (content is FrameLayout)
                FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
            else ViewGroup.LayoutParams(-1, -2)
            content.addView(panel, lp)
            panel.elevation = 8f
            panel.refresh()
            panel.post { shiftOfficial(content, panel) }
        }
        if (content.findViewWithTag<View>(TAG_PLUS) == null) {
            val plus = PlusFab(activity)
            plus.tag = TAG_PLUS
            val lp = FrameLayout.LayoutParams(dp(activity, 48), dp(activity, 48), Gravity.BOTTOM or Gravity.END)
            lp.marginEnd = dp(activity, 16)
            lp.bottomMargin = dp(activity, 28)
            content.addView(plus, lp)
            plus.elevation = 18f
        }
        if (content.findViewWithTag<View>(TAG_START) == null) {
            val start = StartBtn(activity)
            start.tag = TAG_START
            val lp = FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            lp.bottomMargin = dp(activity, 28)
            content.addView(start, lp)
            start.elevation = 18f
            start.visibility = View.GONE
        }
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

    private fun applyMode(activity: Activity) {
        unlockSystem(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val surface = hasGameSurface(content) || hasGameSurface(activity.window?.decorView)
        if (surface) playing = true
        if (playing && !surface && looksLikeLibrary(content)) playing = false
        DataSeed.allowEnsure = !playing

        val panel = content.findViewWithTag<View>(TAG) as? Panel
        val plus = content.findViewWithTag<View>(TAG_PLUS)
        val start = content.findViewWithTag<View>(TAG_START) as? StartBtn
        val timer = content.findViewWithTag<View>(TAG_TIMER) as? TimerBall
        val preset = content.findViewWithTag<View>(TAG_PRESET) as? PresetFab

        if (!playing && officialLoadingVisible(activity)) {
            waitTimer = true
            hideOfficialLoading(activity)
        }

        if (playing) {
            panel?.collapse()
            panel?.visibility = View.GONE
            plus?.visibility = View.GONE
            start?.visibility = View.GONE
            preset?.visibility = View.VISIBLE
            if (waitTimer) {
                hideOfficialLoading(activity)
                timer?.showRunning()
                if (timer?.heardAudio() == true) {
                    waitTimer = false
                    timer.dismiss()
                }
            } else {
                timer?.dismiss()
            }
            unshiftOfficial(content, panel)
        } else if (waitTimer) {
            hideOfficialLoading(activity)
            panel?.visibility = View.GONE
            plus?.visibility = View.GONE
            start?.visibility = View.GONE
            preset?.visibility = View.GONE
            timer?.showRunning()
            if (timer?.heardAudio() == true) {
                waitTimer = false
                timer.dismiss()
            }
        } else {
            panel?.visibility = View.VISIBLE
            plus?.visibility = View.VISIBLE
            preset?.hideSheet()
            preset?.visibility = View.GONE
            val ready = GameExtra.readyToStart(activity)
            start?.visibility = if (ready) View.VISIBLE else View.GONE
            timer?.dismiss()
            if (panel != null) panel.post { shiftOfficial(content, panel) }
        }
    }

    /** Official Kenji Loading lives in a second window. Never draw our own card over it. */
    private fun officialLoadingVisible(activity: Activity): Boolean {
        val decor = activity.window?.decorView
        for (v in windowViews()) {
            if (v === decor || isOursTree(v)) continue
            if (hasLoadingMark(v)) return true
        }
        return hasLoadingMark(decor)
    }

    private fun hideOfficialLoading(activity: Activity) {
        try {
            val decor = activity.window?.decorView
            for (v in windowViews()) {
                if (v === decor || isOursTree(v)) continue
                if (hasLoadingMark(v)) {
                    v.visibility = View.GONE
                    v.alpha = 0f
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hasLoadingMark(v: View?): Boolean {
        if (v == null) return false
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.contains("Loading", true) || t.contains("Загрузка")) return true
        }
        val n = v.javaClass.name
        if (n.contains("LinearProgress") || n.contains("CircularProgress") ||
            n.contains("LoadingIndicator")
        ) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasLoadingMark(v.getChildAt(i))) return true
        }
        return false
    }

    private fun isOursTree(v: View): Boolean {
        if (isOurs(v)) return true
        var p: android.view.ViewParent? = v.parent
        var i = 0
        while (p is View && i < 8) {
            if (isOurs(p)) return true
            p = p.parent
            i++
        }
        return false
    }

    private fun windowViews(): List<View> {
        return try {
            val cls = Class.forName("android.view.WindowManagerGlobal")
            val inst = cls.getMethod("getInstance").invoke(null)
            val f = cls.getDeclaredField("mViews")
            f.isAccessible = true
            when (val raw = f.get(inst)) {
                is List<*> -> raw.filterIsInstance<View>()
                is Array<*> -> raw.filterIsInstance<View>()
                else -> emptyList()
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun hasGameSurface(v: View?): Boolean {
        if (v == null) return false
        val n = v.javaClass.name
        val surface = n.contains("SurfaceView") || n.contains("GLSurface") ||
            n.contains("Vulkan", true) || n.contains("TextureView") ||
            n.contains("SurfaceControl") || n.contains("NativeSurface")
        if (surface && v.width > 200 && v.height > 200) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasGameSurface(v.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun looksLikeLibrary(root: View?): Boolean {
        if (root == null) return false
        return findText(root, "Search")
    }

    private fun findText(v: View, needle: String): Boolean {
        if (v is TextView && v.text?.toString()?.contains(needle, ignoreCase = true) == true) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (findText(v.getChildAt(i), needle)) return true
            }
        }
        return false
    }

    private fun shiftOfficial(content: ViewGroup, panel: View) {
        val h = panel.height
        if (h <= 0 || panel.visibility != View.VISIBLE) return
        val cap = (content.height * 0.34f).toInt().coerceAtLeast(dp(content.context, 44))
        val use = if (h > cap) cap else h
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (isOurs(v)) continue
            val p = v.layoutParams
            if (p is FrameLayout.LayoutParams) {
                if (p.topMargin != use) {
                    p.topMargin = use
                    v.layoutParams = p
                }
            }
        }
    }

    private fun unshiftOfficial(content: ViewGroup, panel: View?) {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === panel || isOurs(v)) continue
            val p = v.layoutParams
            if (p is FrameLayout.LayoutParams && p.topMargin != 0) {
                p.topMargin = 0
                v.layoutParams = p
            }
        }
    }

    private fun isOurs(v: View): Boolean {
        val t = v.tag
        return t == TAG || t == TAG_PLUS || t == TAG_START || t == TAG_TIMER || t == TAG_PRESET
    }

    private fun dp(c: Context, v: Int): Int =
        Math.round(v * c.resources.displayMetrics.density)

    private fun pick(host: Activity, kind: String) {
        val i = Intent()
        i.setClassName(host.packageName, "dev.symbiosis.kenji.PickActivity")
        i.putExtra("kind", kind)
        host.startActivity(i)
    }

    private class CoverHold(
        private val host: Activity,
        private val base: Window.Callback,
    ) : Window.Callback by base {
        private var sx = 0f
        private var sy = 0f
        private var hold = false
        private var posted: Runnable? = null
        private val slop = 28f * host.resources.displayMetrics.density

        private fun onOurChrome(ev: MotionEvent): Boolean {
            val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return false
            listOf(TAG, TAG_PLUS, TAG_START, TAG_TIMER, TAG_PRESET).forEach { tag ->
                val v = content.findViewWithTag<View>(tag) ?: return@forEach
                if (v.visibility != View.VISIBLE) return@forEach
                val loc = IntArray(2)
                v.getLocationOnScreen(loc)
                val x = ev.rawX
                val y = ev.rawY
                if (x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height) return true
            }
            return false
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (playing) return base.dispatchTouchEvent(ev)
            if (onOurChrome(ev)) return base.dispatchTouchEvent(ev)
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    posted?.let { main.removeCallbacks(it) }
                    hold = false
                    sx = ev.rawX
                    sy = ev.rawY
                    val run = Runnable {
                        hold = true
                        showGameCard(host)
                    }
                    posted = run
                    main.postDelayed(run, 520)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx
                    val dy = ev.rawY - sy
                    if (dx * dx + dy * dy > slop * slop) {
                        posted?.let { main.removeCallbacks(it) }
                        posted = null
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    posted?.let { main.removeCallbacks(it) }
                    posted = null
                    if (hold) {
                        hold = false
                        return true
                    }
                }
            }
            return base.dispatchTouchEvent(ev)
        }
    }

    private fun showGameCard(host: Activity) {
        try {
            val box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            val pad = dp(host, 14)
            box.setPadding(pad, pad, pad, pad)
            val id = GameExtra.lastTitleId(host)
            val sub = TextView(host)
            sub.text = if (id.isNotEmpty()) "titleId $id" else "titleId неизвестен"
            sub.setTextColor(MUTED)
            box.addView(sub)
            val extras = TextView(host)
            extras.setTextColor(MUTED)
            extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            extras.setTypeface(Typeface.MONOSPACE)
            extras.text = GameExtra.report(host)
            box.addView(extras)
            val scroll = ScrollView(host)
            scroll.addView(box)
            AlertDialog.Builder(host)
                .setTitle("карточка игры")
                .setView(scroll)
                .setPositiveButton("закрыть", null)
                .show()
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "card", t)
        }
    }

    private class PlusFab(private val host: Activity) : TextView(host) {
        init {
            text = "+"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
            val d = GradientDrawable()
            d.setColor(MINT)
            d.cornerRadius = dp(24).toFloat()
            background = d
            setOnClickListener { pick(host, "games") }
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }

    private class StartBtn(private val host: Activity) : TextView(host) {
        init {
            text = "Начать игру"
            gravity = Gravity.CENTER
            setTextColor(Color.BLACK)
            setTypeface(Typeface.DEFAULT_BOLD)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(dp(22), dp(12), dp(22), dp(12))
            val d = GradientDrawable()
            d.setColor(MINT)
            d.cornerRadius = dp(22).toFloat()
            background = d
            setOnClickListener {
                waitTimer = true
                BootLog.add("Начать игру → секундомер")
                val content = host.findViewById<ViewGroup>(android.R.id.content)
                (content?.findViewWithTag<View>(TAG_TIMER) as? TimerBall)?.showRunning()
            }
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }

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
                    val dt = (now - lastT).coerceAtLeast(1L).toFloat()
                    vx = (nx - lastX) * (16f / dt)
                    vy = (ny - lastY) * (16f / dt)
                    x += nx - lastX
                    y += ny - lastY
                    lastX = nx
                    lastY = ny
                    lastT = now
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (kotlin.math.abs(vx) + kotlin.math.abs(vy) < 4f) {
                        vx = 7f
                        vy = -5f
                    }
                    main.removeCallbacks(physics)
                    main.post(physics)
                    return true
                }
            }
            return super.onTouchEvent(event)
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

    private class Panel(private val host: Activity) : LinearLayout(host) {
        private val summary: TextView
        private val body: LinearLayout
        private val status: TextView
        private val bridges: LinearLayout
        private val presets: LinearLayout
        private val journalBtn: Button
        private val tabBridges: Button
        private val tabPresets: Button
        private var tab = 0
        private var open = false

        init {
            orientation = VERTICAL
            setBackgroundColor(BG)
            val pad = dp(10)
            setPadding(pad, dp(8), pad, dp(8))

            summary = TextView(host)
            summary.setTextColor(TEXT)
            summary.setTypeface(Typeface.DEFAULT_BOLD)
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            summary.setPadding(0, dp(4), 0, dp(4))
            summary.setOnClickListener { toggle() }
            addView(summary)

            body = LinearLayout(host)
            body.orientation = VERTICAL
            body.visibility = GONE
            addView(body)

            status = TextView(host)
            status.setTextColor(MUTED)
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            status.setPadding(0, dp(4), 0, dp(6))
            body.addView(status)

            val tabs = LinearLayout(host)
            tabs.orientation = HORIZONTAL
            tabBridges = pill("Мосты", true) { show(0) }
            tabPresets = pill("Пресеты", false) { show(1) }
            tabs.addView(tabBridges, LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
            tabs.addView(tabPresets, LayoutParams(0, -2, 1f))
            body.addView(tabs)

            bridges = LinearLayout(host)
            bridges.orientation = VERTICAL
            bridges.addView(rowBtn("Папка Eden/files (оригинал прошивки)") { pick(host, "eden") })
            bridges.addView(rowBtn("Папка игр") { pick(host, "games") })
            journalBtn = rowBtn("Журнал запуска") { showJournal() }
            journalBtn.visibility = GONE
            bridges.addView(journalBtn)
            bridges.addView(rowBtn("Починить всё", accent = true) { save() })
            body.addView(bridges)

            presets = LinearLayout(host)
            presets.orientation = VERTICAL
            presets.visibility = GONE
            body.addView(presets)
        }

        fun collapse() {
            if (!open) return
            open = false
            body.visibility = GONE
            summary.text = summary.text.toString().replace("свернуть", "развернуть")
        }

        private fun toggle() {
            open = !open
            body.visibility = if (open) VISIBLE else GONE
            if (open) refresh()
            post {
                val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return@post
                shiftOfficial(content, this)
            }
        }

        fun refresh() {
            val nca = DataSeed.firmwareNca(host)
            val bytes = DataSeed.firmwareBytes(host)
            val fw = if (nca >= 5) {
                "$nca NCA · ${BootLog.human(bytes)}"
            } else {
                "нет прошивки ($nca NCA)"
            }
            summary.text = "Kenji Space  ·  $fw  ·  ${if (open) "свернуть" else "развернуть"}"
            if (!open) return
            journalBtn.visibility = if (SettingsBank.journalOn(host)) VISIBLE else GONE
            val home = DataSeed.playHome(host)
            val keysFile = File(home, "system/prod.keys")
            val keys = if (keysFile.isFile && keysFile.length() > 100) {
                "ключи ${BootLog.human(keysFile.length())}"
            } else {
                "нет ключей"
            }
            val covers = if (GameExtra.coversOk(host)) "обложки есть" else "нет обложек"
            status.text = buildString {
                append(keys).append(" · ").append(nca).append(" NCA · ").append(BootLog.human(bytes)).append('\n')
                append(covers).append('\n')
                append(home.absolutePath)
            }
            fillPresets()
        }

        private fun showJournal() {
            val box = ScrollView(host)
            val t = TextView(host)
            t.setTextIsSelectable(true)
            t.setTextColor(TEXT)
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            t.setTypeface(Typeface.MONOSPACE)
            t.setPadding(dp(12), dp(12), dp(12), dp(12))
            t.text = buildString {
                append(BootLog.versionLine).append('\n')
                append(BootLog.dump()).append('\n')
                append(BootLog.kernelDump())
            }
            box.addView(t)
            AlertDialog.Builder(host)
                .setTitle("журнал запуска")
                .setView(box)
                .setPositiveButton("скрыть") { _, _ ->
                    SettingsBank.setJournal(host, false)
                    refresh()
                }
                .setNegativeButton("закрыть", null)
                .show()
        }

        private fun show(which: Int) {
            tab = which
            bridges.visibility = if (which == 0) VISIBLE else GONE
            presets.visibility = if (which == 1) VISIBLE else GONE
            paintTab(tabBridges, which == 0)
            paintTab(tabPresets, which == 1)
        }

        private fun save() {
            status.text = "чиню…"
            Thread({
                try {
                    AccessFix.repair(host)
                    DataSeed.ensure(host)
                    SettingsBank.saveNamed(host, "последние")
                    host.runOnUiThread {
                        refresh()
                        Toast.makeText(host, "готово · ${DataSeed.firmwareNca(host)} NCA", Toast.LENGTH_LONG).show()
                    }
                } catch (t: Throwable) {
                    host.runOnUiThread {
                        Toast.makeText(host, "не сохранилось: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }, "kenji-fix").start()
        }

        private fun fillPresets() {
            presets.removeAllViews()
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                presets.addView(rowBtn(n) {
                    Toast.makeText(host, SettingsBank.applyNamed(host, n), Toast.LENGTH_SHORT).show()
                })
            }
        }

        private fun rowBtn(label: String, accent: Boolean = false, click: () -> Unit): Button {
            val b = pill(label, accent, click)
            val lp = LayoutParams(-1, -2)
            lp.topMargin = dp(6)
            b.layoutParams = lp
            return b
        }

        private fun pill(label: String, accent: Boolean, click: () -> Unit): Button {
            val b = Button(host)
            b.text = label
            b.isAllCaps = false
            paintTab(b, accent)
            b.setOnClickListener { click() }
            return b
        }

        private fun paintTab(b: Button, on: Boolean) {
            b.setTextColor(if (on) Color.BLACK else TEXT)
            val d = GradientDrawable()
            d.setColor(if (on) MINT else CARD)
            d.cornerRadius = dp(18).toFloat()
            b.background = d
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }
}
