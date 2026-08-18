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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * Launcher: compact panel + add-folder inside it.
 * Loading: only our live bar; official Kenji dialog is hidden.
 * In-game: hideable FAB, red stats, bottom overlay (pause, not stop).
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val TAG_HUD = "space-hud"
    private const val TAG_LOAD = "space-load"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val RED = 0xFFFF3B30.toInt()
    private const val BG = 0xFF2A2A32.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var seeded = false
    @Volatile private var waitGame = false
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
        if (hasGameSurface(content)) {
            waitGame = false
            return false
        }
        if (!activity.hasWindowFocus()) waitGame = true
        if (loadingTitle(content) != null) waitGame = true
        if (loadingTitle(activity.window?.decorView) != null) waitGame = true
        if (officialLoaderVisible()) waitGame = true
        return waitGame
    }

    private fun officialLoaderVisible(): Boolean {
        for (root in allWindows()) {
            if (ours(root)) continue
            if (findOfficialLoader(root, 0)) return true
        }
        return false
    }

    private fun findOfficialLoader(v: View, depth: Int): Boolean {
        if (depth > 14 || ours(v)) return false
        if (looksOfficialLoader(v)) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (findOfficialLoader(v.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun looksOfficialLoader(v: View): Boolean {
        val n = v.javaClass.name
        if (n.contains("ProgressBar") || n.contains("CircularProgress") ||
            n.contains("LinearProgress") || n.contains("LoadingIndicator")
        ) return true
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.contains("Loading", true) || t.contains("Загрузка")) return true
        }
        return false
    }

    private fun ours(v: View): Boolean {
        var p: View? = v
        repeat(12) {
            val cur = p ?: return false
            val t = cur.tag
            if (t == TAG || t == TAG_HUD || t == TAG_LOAD) return true
            p = cur.parent as? View
        }
        return false
    }

    private fun allWindows(): List<View> {
        return try {
            val cl = Class.forName("android.view.WindowManagerGlobal")
            val inst = cl.getMethod("getInstance").invoke(null)
            val f = cl.getDeclaredField("mViews")
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

    private fun hideOfficialLoader() {
        try {
            for (root in allWindows()) {
                if (ours(root)) continue
                buryOfficialLoader(root, 0)
            }
        } catch (_: Throwable) {
        }
    }

    private fun buryOfficialLoader(v: View, depth: Int) {
        if (depth > 16 || ours(v)) return
        if (looksOfficialLoader(v)) {
            var p: View = v
            repeat(8) {
                val par = p.parent as? View ?: return@repeat
                if (ours(par)) return@repeat
                p = par
            }
            if (!ours(p)) {
                p.alpha = 0f
                p.visibility = View.GONE
            }
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) buryOfficialLoader(v.getChildAt(i), depth + 1)
        }
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
            Thread({
                try {
                    AccessFix.repair(activity)
                    DataSeed.ensure(activity)
                    SettingsBank.applyDefaultOnce(activity)
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
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        main.removeCallbacksAndMessages(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        (content?.findViewWithTag<View>(TAG) as? Panel)?.collapse()
        content?.findViewWithTag<View>(TAG)?.visibility = View.GONE
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
                main.postAtTime(this, activity, android.os.SystemClock.uptimeMillis() + if (waitGame) 180 else 800)
            }
        }
        main.postAtTime(tick, activity, android.os.SystemClock.uptimeMillis() + 600)
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
        if (content.findViewWithTag<View>(TAG_HUD) == null) {
            val hud = PlayHud(activity)
            hud.tag = TAG_HUD
            hud.visibility = View.GONE
            content.addView(hud, FrameLayout.LayoutParams(-1, -1))
            hud.elevation = 26f
        }
        if (content.findViewWithTag<View>(TAG_LOAD) == null) {
            val load = LoadBar(activity)
            load.tag = TAG_LOAD
            load.visibility = View.GONE
            content.addView(load, FrameLayout.LayoutParams(-1, -1))
            load.elevation = 96f
            load.translationZ = 96f
        }
    }

    private fun applyMode(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val game = inGame(activity)
        val busy = launching(activity, content)
        DataSeed.allowEnsure = !game
        val panel = content.findViewWithTag<View>(TAG) as? Panel
        val hud = content.findViewWithTag<View>(TAG_HUD) as? PlayHud
        val load = content.findViewWithTag<View>(TAG_LOAD)
        if (game) {
            panel?.collapse()
            panel?.visibility = View.GONE
            (load as? LoadBar)?.stop()
            load?.visibility = View.GONE
            hud?.visibility = View.VISIBLE
            hud?.start()
            unshiftOfficial(content, panel)
            hideOfficialBottomHud(content)
        } else if (busy) {
            panel?.collapse()
            panel?.visibility = View.GONE
            hud?.visibility = View.GONE
            hud?.stop()
            load?.visibility = View.VISIBLE
            (load as? LoadBar)?.start(loadingTitle(content) ?: "запуск")
            hideOfficialLoader()
            unshiftOfficial(content, panel)
        } else {
            panel?.visibility = View.VISIBLE
            hud?.visibility = View.GONE
            hud?.stop()
            (load as? LoadBar)?.stop()
            load?.visibility = View.GONE
            if (panel != null) panel.post { shiftOfficial(content, panel) }
        }
    }

    private fun hideOfficialBottomHud(root: ViewGroup) {
        try {
            walkHide(root, 0)
        } catch (_: Throwable) {
        }
    }

    private fun walkHide(v: View, depth: Int) {
        if (depth > 14) return
        if (v.tag == TAG || v.tag == TAG_HUD || v.tag == TAG_LOAD) return
        val n = v.javaClass.name
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.startsWith("FPS") || t.contains("Kenji-NX") || t == "≡") {
                v.visibility = View.INVISIBLE
            }
        }
        if (n.contains("Overlay", true) && v !is PlayHud) {
            if (v.width in 1..400 && v.height in 1..120) v.visibility = View.INVISIBLE
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) walkHide(v.getChildAt(i), depth + 1)
        }
    }

    private fun shiftOfficial(content: ViewGroup, panel: View) {
        val h = panel.height
        if (h <= 0 || panel.visibility != View.VISIBLE) return
        val cap = (content.height * 0.34f).toInt().coerceAtLeast(dp(content.context, 44))
        val use = if (h > cap) cap else h
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (v === panel || v.tag == TAG_HUD || v.tag == TAG_LOAD) continue
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
            if (v === panel || v.tag == TAG_HUD || v.tag == TAG_LOAD) continue
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
        listOf(TAG, TAG_HUD, TAG_LOAD).forEach { tag ->
            val v = content.findViewWithTag<View>(tag)
            if (v != null && v.visibility == View.VISIBLE && hit(v, ev)) {
                if (tag == TAG_HUD) {
                    val hud = v as? PlayHud ?: return true
                    return hud.hitsChrome(ev)
                }
                return true
            }
        }
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
                    val content = host.findViewById<ViewGroup>(android.R.id.content)
                    if (content != null && !inGame(host) && !onOurChrome(host, ev) && !launching(host, content)) {
                        sx = ev.rawX
                        sy = ev.rawY
                        val run = Runnable {
                            hold = true
                            showGameCard(host)
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

    private fun showGameCard(host: Activity) {
        try {
            val scroll = ScrollView(host)
            val box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            val pad = dp(host, 14)
            box.setPadding(pad, pad, pad, pad)

            val coverRow = HorizontalScrollView(host)
            val cover = TextView(host)
            cover.text = "  обложка  ·  удержите другую, чтобы сменить  ·  прокрутка →  "
            cover.setTextColor(TEXT)
            cover.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            cover.setPadding(dp(host, 18), dp(host, 28), dp(host, 18), dp(host, 28))
            val cd = GradientDrawable()
            cd.setColor(0xFF1C1C24.toInt())
            cd.cornerRadius = dp(host, 14).toFloat()
            cover.background = cd
            cover.minWidth = dp(host, 280)
            coverRow.addView(cover)
            box.addView(coverRow)

            val id = GameExtra.lastTitleId(host)
            val sub = TextView(host)
            sub.text = if (id.isNotEmpty()) "titleId $id" else "titleId неизвестен — покажу все папки"
            sub.setTextColor(MUTED)
            sub.setPadding(0, dp(host, 8), 0, dp(host, 8))
            box.addView(sub)

            box.addView(toggleRow(host, "оверлей FPS", SettingsBank.overlayOn(host)) { on ->
                SettingsBank.setOverlay(host, on)
            })
            box.addView(toggleRow(host, "журнал запуска", SettingsBank.journalOn(host)) { on ->
                SettingsBank.setJournal(host, on)
            })

            addSection(host, box, "пресеты")
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                box.addView(plainBtn(host, n) {
                    val msg = GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                    Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                })
            }

            addSection(host, box, "моды · сейвы · читы")
            val extras = TextView(host)
            extras.setTextColor(MUTED)
            extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            extras.setTypeface(Typeface.MONOSPACE)
            extras.text = GameExtra.report(host)
            extras.setPadding(0, dp(host, 4), 0, dp(host, 8))
            box.addView(extras)

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

    private fun addSection(host: Activity, box: LinearLayout, title: String) {
        val t = TextView(host)
        t.text = title
        t.setTextColor(MINT)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setPadding(0, dp(host, 12), 0, dp(host, 4))
        box.addView(t)
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

    private fun plainBtn(host: Activity, label: String, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { click() }
        return b
    }

    private class LoadBar(host: Activity) : FrameLayout(host) {
        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        private val track: View
        private val fill: View
        private val label: TextView
        private var running = false
        private var shown = 0f
        private var title = "запуск"
        private val tick = object : Runnable {
            override fun run() {
                if (!running) return
                val (target, step) = BootLog.stage()
                val goal = target.toFloat()
                shown += (goal - shown) * 0.2f
                if (shown < goal) shown += 0.35f
                if (shown > goal + 6f) shown = goal + 6f
                val tw = track.width
                if (tw > 0) {
                    val w = ((tw - dp(4)) * (shown / 100f)).toInt().coerceAtLeast(dp(10))
                    val p = fill.layoutParams
                    if (p.width != w) {
                        p.width = w
                        fill.layoutParams = p
                    }
                }
                label.text = String.format("%s  ·  %d%%  ·  %s", title, shown.toInt(), step)
                hideOfficialLoader()
                main.postDelayed(this, 40)
            }
        }

        init {
            isClickable = false
            isFocusable = false
            setBackgroundColor(0xCC101014.toInt())
            val box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            box.gravity = Gravity.CENTER_HORIZONTAL
            label = TextView(host)
            label.setTextColor(0xFFE8E8EE.toInt())
            label.setTypeface(Typeface.MONOSPACE)
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            label.gravity = Gravity.CENTER
            label.text = "запуск"
            box.addView(label)
            track = FrameLayout(host)
            val tg = GradientDrawable()
            tg.setColor(0xFF3A3A44.toInt())
            tg.cornerRadius = dp(5).toFloat()
            track.background = tg
            val tlp = LinearLayout.LayoutParams(dp(240), dp(10))
            tlp.topMargin = dp(10)
            box.addView(track, tlp)
            fill = View(host)
            val fg = GradientDrawable()
            fg.setColor(MINT)
            fg.cornerRadius = dp(5).toFloat()
            fill.background = fg
            (track as FrameLayout).addView(fill, FrameLayout.LayoutParams(dp(12), -1, Gravity.START or Gravity.CENTER_VERTICAL))
            addView(box, LayoutParams(-2, -2, Gravity.CENTER))
        }

        fun start(now: String) {
            title = now.replace('\n', ' ').take(42)
            if (running) return
            running = true
            shown = shown.coerceAtLeast(6f)
            main.removeCallbacks(tick)
            main.post(tick)
        }

        fun stop() {
            running = false
            main.removeCallbacks(tick)
            shown = 0f
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }

    private class Panel(private val host: Activity) : LinearLayout(host) {
        private val summary: TextView
        private val plus: TextView
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

            val head = LinearLayout(host)
            head.orientation = HORIZONTAL
            head.gravity = Gravity.CENTER_VERTICAL
            summary = TextView(host)
            summary.setTextColor(TEXT)
            summary.setTypeface(Typeface.DEFAULT_BOLD)
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            summary.setPadding(0, dp(4), 0, dp(4))
            summary.setOnClickListener { toggle() }
            head.addView(summary, LayoutParams(0, -2, 1f))

            plus = TextView(host)
            plus.text = "+"
            plus.gravity = Gravity.CENTER
            plus.setTextColor(Color.BLACK)
            plus.setTypeface(Typeface.DEFAULT_BOLD)
            plus.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            val pd = GradientDrawable()
            pd.setColor(MINT)
            pd.cornerRadius = dp(16).toFloat()
            plus.background = pd
            val plp = LayoutParams(dp(32), dp(32))
            plus.layoutParams = plp
            plus.setOnClickListener { pick("games") }
            head.addView(plus)
            addView(head)

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
                append("удержите обложку — карточка (моды, сейвы, читы)")
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

    private class PlayHud(private val host: Activity) : FrameLayout(host) {
        private val stats: TextView
        private val fab: TextView
        private val bar: LinearLayout
        private val sheet: ScrollView
        private val sheetBox: LinearLayout
        private val pauseBtn: TextView
        private var frames = 0
        private var lastNs = 0L
        private var fps = 0.0
        private var running = false
        private var fabHidden = false
        private lateinit var cb: Choreographer.FrameCallback

        init {
            isClickable = false
            isFocusable = false
            cb = Choreographer.FrameCallback { ns ->
                if (!running) return@FrameCallback
                frames++
                if (lastNs == 0L) lastNs = ns
                val dt = ns - lastNs
                if (dt >= 400_000_000L) {
                    fps = frames * 1_000_000_000.0 / dt
                    frames = 0
                    lastNs = ns
                    paintStats()
                }
                Choreographer.getInstance().postFrameCallback(cb)
            }

            stats = TextView(host)
            stats.setTextColor(RED)
            stats.setTypeface(Typeface.MONOSPACE)
            stats.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
            stats.setShadowLayer(2.5f, 0f, 0f, Color.BLACK)
            stats.text = "FPS --"
            val slp = LayoutParams(-2, -2, Gravity.TOP or Gravity.START)
            slp.topMargin = dp(8)
            slp.marginStart = dp(8)
            addView(stats, slp)

            fab = TextView(host)
            fab.text = "◈"
            fab.gravity = Gravity.CENTER
            fab.setTextColor(Color.BLACK)
            fab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val ball = GradientDrawable()
            ball.setColor(MINT)
            ball.cornerRadius = dp(22).toFloat()
            fab.background = ball
            val flp = LayoutParams(dp(44), dp(44), Gravity.BOTTOM or Gravity.START)
            flp.marginStart = dp(14)
            flp.bottomMargin = dp(96)
            addView(fab, flp)
            fab.setOnClickListener { toggleSheet() }
            fab.setOnLongClickListener {
                fabHidden = true
                fab.visibility = GONE
                Toast.makeText(host, "кнопка скрыта · удержите центр снизу, чтобы вернуть", Toast.LENGTH_SHORT).show()
                true
            }

            bar = LinearLayout(host)
            bar.orientation = LinearLayout.HORIZONTAL
            bar.gravity = Gravity.CENTER
            val glass = GradientDrawable()
            glass.setColor(0xE616161C.toInt())
            glass.cornerRadius = dp(22).toFloat()
            glass.setStroke(dp(1), 0x66FFFFFF)
            bar.background = glass
            bar.setPadding(dp(10), dp(6), dp(10), dp(6))
            pauseBtn = chip("❚❚") { togglePause() }
            bar.addView(pauseBtn)
            bar.addView(chip("◈") { toggleSheet() })
            bar.addView(chip("•") {
                val on = !SettingsBank.overlayOn(host)
                SettingsBank.setOverlay(host, on)
                stats.visibility = if (on) VISIBLE else GONE
            })
            val blp = LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL)
            blp.bottomMargin = dp(86)
            addView(bar, blp)
            bar.setOnLongClickListener {
                if (fabHidden) {
                    fabHidden = false
                    fab.visibility = VISIBLE
                }
                true
            }

            sheetBox = LinearLayout(host)
            sheetBox.orientation = LinearLayout.VERTICAL
            sheetBox.setPadding(dp(12), dp(12), dp(12), dp(12))
            sheet = ScrollView(host)
            sheet.setBackgroundColor(0xF214141A.toInt())
            sheet.addView(sheetBox)
            sheet.visibility = GONE
            val shLp = LayoutParams(dp(260), dp(320), Gravity.BOTTOM or Gravity.START)
            shLp.marginStart = dp(12)
            shLp.bottomMargin = dp(150)
            addView(sheet, shLp)
        }

        fun hitsChrome(ev: MotionEvent): Boolean {
            if (fab.visibility == VISIBLE && hitView(fab, ev)) return true
            if (bar.visibility == VISIBLE && hitView(bar, ev)) return true
            if (sheet.visibility == VISIBLE && hitView(sheet, ev)) return true
            return false
        }

        private fun hitView(v: View, ev: MotionEvent): Boolean {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val x = ev.rawX
            val y = ev.rawY
            return x >= loc[0] && x < loc[0] + v.width && y >= loc[1] && y < loc[1] + v.height
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        private fun chip(label: String, click: () -> Unit): TextView {
            val t = TextView(host)
            t.text = label
            t.gravity = Gravity.CENTER
            t.setTextColor(TEXT)
            t.setPadding(dp(12), dp(6), dp(12), dp(6))
            t.setOnClickListener { click() }
            return t
        }

        private fun togglePause() {
            val msg = GamePause.toggle(host)
            pauseBtn.text = if (GamePause.paused) "▶" else "❚❚"
            Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
        }

        private fun toggleSheet() {
            if (sheet.visibility == VISIBLE) {
                sheet.visibility = GONE
            } else {
                fillSheet()
                sheet.visibility = VISIBLE
            }
        }

        private fun fillSheet() {
            sheetBox.removeAllViews()
            val t = TextView(host)
            t.text = if (GamePause.paused) "на паузе · смена пресета безопасна" else "пресеты · сначала пауза"
            t.setTextColor(MUTED)
            sheetBox.addView(t)
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                val b = Button(host)
                b.text = n
                b.isAllCaps = false
                b.setOnClickListener {
                    val msg = GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                    pauseBtn.text = if (GamePause.paused) "▶" else "❚❚"
                    Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                }
                sheetBox.addView(b)
            }
            val extra = TextView(host)
            extra.setTextColor(MUTED)
            extra.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            extra.setTypeface(Typeface.MONOSPACE)
            extra.text = "\n" + GameExtra.report(host)
            sheetBox.addView(extra)
        }

        private fun paintStats() {
            if (!SettingsBank.overlayOn(host)) {
                stats.visibility = GONE
                return
            }
            stats.visibility = VISIBLE
            val cpu = CpuMeter.sample()
            val speed = if (fps <= 0) 0 else (fps / 60.0 * 100.0)
            val scale = SettingsBank.scaleOf(host)
            val dock = if (SettingsBank.dockedOf(host)) "TV" else "HH"
            val mem = Runtime.getRuntime()
            val used = (mem.totalMemory() - mem.freeMemory()) / (1024 * 1024)
            stats.text = String.format(
                "CPU %d%%  FPS %.0f  %d%%  %.2f× %s  %d МБ%s",
                cpu, fps, speed.toInt(), scale, dock, used,
                if (GamePause.paused) "  PAUSE" else "",
            )
        }

        fun start() {
            if (running) return
            running = true
            frames = 0
            lastNs = 0L
            fab.visibility = if (fabHidden) GONE else VISIBLE
            paintStats()
            Choreographer.getInstance().postFrameCallback(cb)
        }

        fun stop() {
            running = false
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }
}
