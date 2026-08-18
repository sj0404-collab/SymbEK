package dev.symbiosis.kenji

import android.app.Activity
import android.app.AlertDialog
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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Library: compact status. Long-press a cover → game settings (overlay / journal).
 * In-game: small red FPS overlay. Journal hidden unless turned on.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val TAG_PLUS = "space-plus"
    private const val TAG_HUD = "space-hud"
    private const val TAG_BOOT = "space-boot"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val RED = 0xFFFF3B30.toInt()
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
        BootLog.add("SpaceHook: колбэки активности")
    }

    fun inGame(ctx: Context): Boolean {
        val act = ctx as? Activity ?: return false
        return hasGameSurface(act.findViewById(android.R.id.content))
    }

    private fun hasGameSurface(v: View?): Boolean {
        if (v == null) return false
        val n = v.javaClass.name
        val surface = n.contains("SurfaceView") || n.contains("GLSurface") ||
            n.contains("Vulkan", true) || n.contains("TextureView")
        if (surface && v.width > 400 && v.height > 400) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasGameSurface(v.getChildAt(i))) return true
            }
        }
        return false
    }

    private fun loadingTitle(v: View?): String? {
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.contains("Loading", ignoreCase = true) || t.contains("Загрузка", ignoreCase = true)) {
                return t.replace('\n', ' ').trim()
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                val t = loadingTitle(v.getChildAt(i))
                if (t != null) return t
            }
        }
        return null
    }

    private fun launching(activity: Activity, content: ViewGroup): Boolean {
        if (!activity.hasWindowFocus()) return true
        if (loadingTitle(content) != null) return true
        if (loadingTitle(activity.window?.decorView) != null) return true
        return false
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        if (a.javaClass.name == "org.kenjinx.android.MainActivity") {
            BootLog.add("MainActivity.onCreate")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        hookWindow(activity)
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
            BootLog.add("фон: AccessFix + ensure")
            Thread({
                try {
                    AccessFix.repair(activity)
                    DataSeed.ensure(activity)
                    SettingsBank.applyDefaultOnce(activity)
                    BootLog.add("фон ensure ок · NCA=${DataSeed.firmwareNca(activity)}")
                    activity.runOnUiThread {
                        (activity.findViewById<ViewGroup>(android.R.id.content)
                            ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("KenjiSpace", "bg", t)
                    BootLog.add("фон ошибка ${t.message}")
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
        content?.findViewWithTag<View>(TAG)?.visibility = View.GONE
        content?.findViewWithTag<View>(TAG_PLUS)?.visibility = View.GONE
        if (content != null) unshiftOfficial(content, content.findViewWithTag(TAG))
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
                main.postAtTime(this, activity, android.os.SystemClock.uptimeMillis() + 900)
            }
        }
        main.postAtTime(tick, activity, android.os.SystemClock.uptimeMillis() + 700)
    }

    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun hookWindow(activity: Activity) {
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
            val plus = plusFab(activity)
            plus.tag = TAG_PLUS
            val lp = FrameLayout.LayoutParams(dp(activity, 52), dp(activity, 52), Gravity.BOTTOM or Gravity.START)
            lp.marginStart = dp(activity, 16)
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
        if (content.findViewWithTag<View>(TAG_BOOT) == null) {
            val boot = BootPane(activity)
            boot.tag = TAG_BOOT
            boot.visibility = View.GONE
            val lp = FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.START)
            content.addView(boot, lp)
            boot.elevation = 24f
        }
    }

    private fun applyMode(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val game = inGame(activity)
        val busy = launching(activity, content)
        DataSeed.allowEnsure = !game
        val panel = content.findViewWithTag<View>(TAG) as? Panel
        val plus = content.findViewWithTag<View>(TAG_PLUS)
        val hud = content.findViewWithTag<View>(TAG_HUD) as? GameHud
        val boot = content.findViewWithTag<View>(TAG_BOOT) as? BootPane
        if (game) {
            panel?.collapse()
            panel?.visibility = View.GONE
            plus?.visibility = View.GONE
            boot?.visibility = View.GONE
            if (SettingsBank.overlayOn(activity)) {
                hud?.visibility = View.VISIBLE
                hud?.startFps()
            } else {
                hud?.visibility = View.GONE
                hud?.stopFps()
            }
            unshiftOfficial(content, panel)
        } else if (busy) {
            panel?.collapse()
            panel?.visibility = View.GONE
            plus?.visibility = View.GONE
            hud?.visibility = View.GONE
            hud?.stopFps()
            if (SettingsBank.journalOn(activity)) {
                boot?.visibility = View.VISIBLE
                boot?.refresh(loadingTitle(content) ?: "запуск…")
            } else {
                boot?.visibility = View.GONE
            }
            unshiftOfficial(content, panel)
        } else {
            panel?.visibility = View.VISIBLE
            plus?.visibility = View.VISIBLE
            hud?.visibility = View.GONE
            hud?.stopFps()
            boot?.visibility = View.GONE
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
        val cap = (content.height * 0.38f).toInt().coerceAtLeast(dp(content.context, 48))
        val use = if (h > cap) cap else h
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === panel || v.tag == TAG_PLUS || v.tag == TAG_HUD || v.tag == TAG_BOOT) continue
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
            if (v === panel || v.tag == TAG_PLUS || v.tag == TAG_HUD || v.tag == TAG_BOOT) continue
            val p = v.layoutParams
            if (p is FrameLayout.LayoutParams && p.topMargin != 0) {
                p.topMargin = 0
                v.layoutParams = p
            }
        }
    }

    private fun dp(c: Context, v: Int): Int =
        Math.round(v * c.resources.displayMetrics.density)

    private fun hit(v: View, ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
    }

    private fun onOurChrome(activity: Activity, ev: MotionEvent): Boolean {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        listOf(TAG, TAG_PLUS, TAG_HUD, TAG_BOOT).forEach { tag ->
            val v = content.findViewWithTag<View>(tag)
            if (v != null && v.visibility == View.VISIBLE && hit(v, ev)) return true
        }
        val h = content.height
        if (h > 0 && ev.y > h - dp(activity, 88)) return true
        return false
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

        private fun cancel() {
            posted?.let { main.removeCallbacks(it) }
            posted = null
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    hold = false
                    if (!inGame(host) && !onOurChrome(host, ev) && !launching(host, host.findViewById(android.R.id.content) ?: return base.dispatchTouchEvent(ev))) {
                        sx = ev.rawX
                        sy = ev.rawY
                        val run = Runnable {
                            hold = true
                            showGameSettings(host)
                        }
                        posted = run
                        main.postDelayed(run, 480)
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - sx
                    val dy = ev.rawY - sy
                    if (dx * dx + dy * dy > slop * slop) cancel()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancel()
                    if (hold) {
                        hold = false
                        return true
                    }
                }
            }
            return base.dispatchTouchEvent(ev)
        }
    }

    private fun showGameSettings(host: Activity) {
        try {
            val box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            val pad = dp(host, 14)
            box.setPadding(pad, pad, pad, pad)
            val hint = TextView(host)
            hint.text = "удержание обложки · настройки оверлея"
            hint.setTextColor(MUTED)
            hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            box.addView(hint)
            box.addView(toggleRow(host, "оверлей FPS (красный)", SettingsBank.overlayOn(host)) { on ->
                SettingsBank.setOverlay(host, on)
            })
            box.addView(toggleRow(host, "журнал запуска", SettingsBank.journalOn(host)) { on ->
                SettingsBank.setJournal(host, on)
                (host.findViewById<ViewGroup>(android.R.id.content)
                    ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
            })
            val t = TextView(host)
            t.text = "пресеты"
            t.setTextColor(MUTED)
            t.setPadding(0, dp(host, 10), 0, dp(host, 4))
            box.addView(t)
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                val b = Button(host)
                b.text = n
                b.isAllCaps = false
                b.setOnClickListener {
                    Toast.makeText(host, SettingsBank.applyNamed(host, n), Toast.LENGTH_SHORT).show()
                }
                box.addView(b)
            }
            AlertDialog.Builder(host)
                .setTitle("настройки игры")
                .setView(box)
                .setPositiveButton("закрыть", null)
                .show()
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "gameset", t)
        }
    }

    private fun toggleRow(host: Activity, label: String, on: Boolean, set: (Boolean) -> Unit): Button {
        var state = on
        val b = Button(host)
        fun paint() {
            b.text = if (state) "$label · вкл" else "$label · выкл"
        }
        paint()
        b.isAllCaps = false
        b.setOnClickListener {
            state = !state
            set(state)
            paint()
        }
        return b
    }

    private class BootPane(private val host: Activity) : TextView(host) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        private var last = 0L

        init {
            isClickable = false
            isFocusable = false
            setTextColor(RED)
            setTypeface(Typeface.MONOSPACE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
            setPadding(dp(8), dp(6), dp(8), dp(6))
            setBackgroundColor(0x00000000)
        }

        fun refresh(loading: String) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - last < 1200L && text.isNotEmpty()) return
            last = now
            val k = BootLog.lastKernel()
            text = buildString {
                append(loading)
                if (k.isNotEmpty()) append('\n').append(k)
            }
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
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
            bridges.addView(rowBtn("Папка Eden/files (оригинал прошивки)") { pick("eden") })
            bridges.addView(rowBtn("Папка игр (+)") { pick("games") })
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
            status.text = buildString {
                append(keys).append(" · ").append(nca).append(" NCA · ").append(BootLog.human(bytes)).append('\n')
                append(home.absolutePath).append('\n')
                append("оверлей: ").append(if (SettingsBank.overlayOn(host)) "вкл" else "выкл")
                append(" · журнал: ").append(if (SettingsBank.journalOn(host)) "вкл" else "скрыт")
                append("\nудержите обложку игры — настройки")
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
                append("прошивка: ").append(DataSeed.firmwareNca(host)).append(" NCA · ")
                    .append(BootLog.human(DataSeed.firmwareBytes(host))).append('\n')
                append("данные: ").append(DataSeed.playHome(host).absolutePath).append('\n')
                append("— автопочинка —\n").append(AutoFix.lastLog).append('\n')
                append("— сканер —\n").append(FirmwareHunt.lastReport).append('\n')
                append("— порядок —\n").append(BootLog.dump()).append('\n')
                val k = BootLog.kernelDump()
                if (k.isNotEmpty()) append("— ядро —\n").append(k)
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
                    BootLog.add("Починить всё")
                    AccessFix.repair(host)
                    DataSeed.ensure(host)
                    SettingsBank.saveNamed(host, "последние")
                    host.runOnUiThread {
                        refresh()
                        Toast.makeText(
                            host,
                            "готово · ${DataSeed.firmwareNca(host)} NCA · ${BootLog.human(DataSeed.firmwareBytes(host))}",
                            Toast.LENGTH_LONG,
                        ).show()
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
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false
        private val line: TextView
        private var frames = 0
        private var lastNs = 0L
        private var fps = 0.0
        private var running = false
        private lateinit var cb: Choreographer.FrameCallback

        init {
            cb = Choreographer.FrameCallback { ns ->
                if (!running) return@FrameCallback
                frames++
                if (lastNs == 0L) lastNs = ns
                val dt = ns - lastNs
                if (dt >= 400_000_000L) {
                    fps = frames * 1_000_000_000.0 / dt
                    frames = 0
                    lastNs = ns
                    paint()
                }
                Choreographer.getInstance().postFrameCallback(cb)
            }
            isClickable = false
            isFocusable = false
            line = TextView(host)
            line.setTextColor(RED)
            line.setTypeface(Typeface.MONOSPACE)
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            line.setShadowLayer(2.5f, 0f, 0f, Color.BLACK)
            line.text = "FPS --"
            val lp = LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            lp.topMargin = dp(8)
            lp.marginStart = dp(8)
            addView(line, lp)
        }

        private fun paint() {
            val scale = SettingsBank.scaleOf(host)
            val dock = if (SettingsBank.dockedOf(host)) "docked" else "handheld"
            val pptc = if (SettingsBank.pptcOf(host)) "PPTC" else "pptc-"
            val nce = if (SettingsBank.nceOf(host)) "NCE" else "jit"
            val nca = DataSeed.firmwareNca(host)
            line.text = String.format(
                "FPS %.0f  %.2f× %s  %s %s  %d NCA",
                fps, scale, dock, pptc, nce, nca,
            )
        }

        fun startFps() {
            if (running) return
            running = true
            frames = 0
            lastNs = 0L
            paint()
            Choreographer.getInstance().postFrameCallback(cb)
        }

        fun stopFps() {
            running = false
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }
}
