package dev.symbiosis.kenji

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Library: thin status + game-folder FAB.
 * In-game: hide library chrome, show FPS + floating presets. Never re-seed bis.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val TAG_PLUS = "space-plus"
    private const val TAG_HUD = "space-hud"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val BG = 0xFF2A2A32.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var seeded = false
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    fun inGame(ctx: Context): Boolean {
        val act = ctx as? Activity
        val boot = act?.intent?.getStringExtra("bootPath")
        if (!boot.isNullOrBlank()) return true
        val action = act?.intent?.action.orEmpty()
        if (action.contains("LAUNCH_GAME") || action.contains("EMULAT")) return true
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            @Suppress("DEPRECATION")
            am.getRunningServices(64).any { s ->
                val n = s.service.className
                n.contains("Emulation", true) || n.contains("GameHost", true) ||
                    n.contains("Vulkan", true)
            }
        } catch (_: Exception) {
            false
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        activity.window?.decorView?.post {
            try {
                attach(activity)
                applyMode(activity)
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "overlay", t)
            }
        }
        if (!seeded && !inGame(activity)) {
            seeded = true
            Thread({
                try {
                    AccessFix.repair(activity)
                    DataSeed.ensure(activity)
                    SettingsBank.enableFps(activity)
                    activity.runOnUiThread {
                        (activity.findViewById<ViewGroup>(android.R.id.content)
                            ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("KenjiSpace", "bg", t)
                }
            }, "kenji-seed").start()
        }
        poll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name == "org.kenjinx.android.MainActivity") {
            main.removeCallbacksAndMessages(activity)
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
                main.postAtTime(this, activity, android.os.SystemClock.uptimeMillis() + 700)
            }
        }
        main.postAtTime(tick, activity, android.os.SystemClock.uptimeMillis() + 700)
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

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
            val plus = plusFab(activity)
            plus.tag = TAG_PLUS
            val lp = FrameLayout.LayoutParams(dp(activity, 56), dp(activity, 56), Gravity.BOTTOM or Gravity.END)
            lp.marginEnd = dp(activity, 88)
            lp.bottomMargin = dp(activity, 20)
            content.addView(plus, lp)
            plus.elevation = 20f
        }
        if (content.findViewWithTag<View>(TAG_HUD) == null) {
            val hud = GameHud(activity)
            hud.tag = TAG_HUD
            hud.visibility = View.GONE
            content.addView(hud, FrameLayout.LayoutParams(-1, -1))
            hud.elevation = 22f
        }
    }

    private fun applyMode(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val game = inGame(activity)
        DataSeed.allowEnsure = !game
        val panel = content.findViewWithTag<View>(TAG)
        val plus = content.findViewWithTag<View>(TAG_PLUS)
        val hud = content.findViewWithTag<View>(TAG_HUD) as? GameHud
        if (game) {
            panel?.visibility = View.GONE
            plus?.visibility = View.GONE
            hud?.visibility = View.VISIBLE
            hud?.startFps()
            unshiftOfficial(content, panel)
        } else {
            panel?.visibility = View.VISIBLE
            plus?.visibility = View.VISIBLE
            hud?.visibility = View.GONE
            hud?.stopFps()
            if (panel != null) panel.post { shiftOfficial(content, panel) }
        }
    }

    private fun plusFab(activity: Activity): TextView {
        val t = TextView(activity)
        t.text = "+"
        t.gravity = Gravity.CENTER
        t.setTextColor(Color.BLACK)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        val d = GradientDrawable()
        d.setColor(MINT)
        d.cornerRadius = dp(activity, 28).toFloat()
        t.background = d
        t.setOnClickListener {
            val i = Intent()
            i.setClassName(activity.packageName, "dev.symbiosis.kenji.PickActivity")
            i.putExtra("kind", "games")
            activity.startActivity(i)
        }
        return t
    }

    private fun shiftOfficial(content: ViewGroup, panel: View) {
        val h = panel.height
        if (h <= 0 || panel.visibility != View.VISIBLE) return
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === panel || v.tag == TAG_PLUS || v.tag == TAG_HUD) continue
            val p = v.layoutParams
            if (p is FrameLayout.LayoutParams) {
                if (p.topMargin != h) {
                    p.topMargin = h
                    v.layoutParams = p
                }
            }
        }
    }

    private fun unshiftOfficial(content: ViewGroup, panel: View?) {
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === panel || v.tag == TAG_PLUS || v.tag == TAG_HUD) continue
            val p = v.layoutParams
            if (p is FrameLayout.LayoutParams && p.topMargin != 0) {
                p.topMargin = 0
                v.layoutParams = p
            }
        }
    }

    private fun dp(c: Context, v: Int): Int =
        Math.round(v * c.resources.displayMetrics.density)

    private class Panel(private val host: Activity) : LinearLayout(host) {
        private val summary: TextView
        private val body: LinearLayout
        private val status: TextView
        private val bridges: LinearLayout
        private val presets: LinearLayout
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
            bridges.addView(rowBtn("Папка Eden/files (оригинал прошивки)") { pick("eden") })
            bridges.addView(rowBtn("Папка игр (+)") { pick("games") })
            bridges.addView(rowBtn("Починить всё", accent = true) { save() })
            body.addView(bridges)

            presets = LinearLayout(host)
            presets.orientation = VERTICAL
            presets.visibility = GONE
            body.addView(presets)
        }

        private fun toggle() {
            open = !open
            body.visibility = if (open) VISIBLE else GONE
            post {
                val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return@post
                shiftOfficial(content, this)
            }
        }

        fun refresh() {
            val nca = DataSeed.firmwareNca(host)
            val fw = if (nca >= 5) "$nca NCA · ${DataSeed.firmwareMode(host)}" else "нет прошивки ($nca NCA)"
            summary.text = "Kenji Space  ·  $fw  ·  ${if (open) "свернуть" else "развернуть"}"
            val keysFile = java.io.File(DataSeed.playHome(host), "system/prod.keys")
            val keys = if (keysFile.isFile && keysFile.length() > 100) "ключи ${keysFile.length() / 1024} КБ" else "нет ключей"
            status.text = "$keys\nпрошивка: ${DataSeed.firmwareSource(host)}\n${AccessFix.statusLine(host)}\n— сканер —\n${FirmwareHunt.lastReport}"
            fillPresets()
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

        private fun pick(kind: String) {
            val i = Intent()
            i.setClassName(host.packageName, "dev.symbiosis.kenji.PickActivity")
            i.putExtra("kind", kind)
            host.startActivity(i)
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

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }

    private class GameHud(private val host: Activity) : FrameLayout(host) {
        private val fps: TextView
        private val sheet: LinearLayout
        private var frames = 0
        private var lastNs = 0L
        private var running = false
        private lateinit var cb: Choreographer.FrameCallback

        init {
            cb = Choreographer.FrameCallback { ns ->
                if (!running) return@FrameCallback
                frames++
                if (lastNs == 0L) lastNs = ns
                val dt = ns - lastNs
                if (dt >= 500_000_000L) {
                    val f = frames * 1_000_000_000.0 / dt
                    fps.text = String.format("FPS %.0f", f)
                    frames = 0
                    lastNs = ns
                }
                Choreographer.getInstance().postFrameCallback(cb)
            }
            fps = TextView(host)
            fps.setTextColor(MINT)
            fps.setTypeface(Typeface.MONOSPACE, Typeface.BOLD)
            fps.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            fps.setShadowLayer(4f, 0f, 0f, Color.BLACK)
            fps.text = "FPS --"
            val fpsLp = LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            fpsLp.topMargin = dp(12)
            fpsLp.marginStart = dp(12)
            addView(fps, fpsLp)

            val fab = TextView(host)
            fab.text = "P"
            fab.gravity = Gravity.CENTER
            fab.setTextColor(Color.BLACK)
            fab.setTypeface(Typeface.DEFAULT_BOLD)
            fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            val ball = GradientDrawable()
            ball.setColor(MINT)
            ball.cornerRadius = dp(24).toFloat()
            fab.background = ball
            val fabLp = LayoutParams(dp(48), dp(48), Gravity.BOTTOM or Gravity.START)
            fabLp.marginStart = dp(16)
            fabLp.bottomMargin = dp(24)
            addView(fab, fabLp)

            sheet = LinearLayout(host)
            sheet.orientation = LinearLayout.VERTICAL
            sheet.setBackgroundColor(0xCC1A1A22.toInt())
            sheet.setPadding(dp(12), dp(12), dp(12), dp(12))
            sheet.visibility = GONE
            val shLp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START)
            shLp.marginStart = dp(16)
            shLp.bottomMargin = dp(80)
            addView(sheet, shLp)

            fab.setOnClickListener {
                if (sheet.visibility == VISIBLE) {
                    sheet.visibility = GONE
                } else {
                    fillSheet()
                    sheet.visibility = VISIBLE
                }
            }
        }

        private fun fillSheet() {
            sheet.removeAllViews()
            val t = TextView(host)
            t.text = "пресеты · игра не останавливается"
            t.setTextColor(MUTED)
            sheet.addView(t)
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                val b = Button(host)
                b.text = n
                b.isAllCaps = false
                b.setOnClickListener {
                    Toast.makeText(host, SettingsBank.applyNamed(host, n), Toast.LENGTH_SHORT).show()
                }
                sheet.addView(b)
            }
        }

        fun startFps() {
            if (running) return
            running = true
            frames = 0
            lastNs = 0L
            Choreographer.getInstance().postFrameCallback(cb)
        }

        fun stopFps() {
            running = false
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }
}
