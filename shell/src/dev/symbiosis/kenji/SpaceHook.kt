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
import android.view.WindowManager
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
 * Loading: one bouncing clock, no dim, no percent bar.
 * Clock only while EmulationService runs — never in settings.
 * In-game: hideable FAB, red stats, bottom overlay (pause, not stop).
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val TAG_HUD = "space-hud"
    internal const val TAG_LOAD = "space-load"
    private const val TAG_INJECT = "space-load-inject"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val RED = 0xFFFF3B30.toInt()
    private const val BG = 0xFF2A2A32.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var seeded = false
    @Volatile private var waitGame = false
    @Volatile private var playing = false
    @Volatile private var tappedAt = 0L
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        BootLog.add("SpaceHook: колбэки активности")
    }

    fun isPlaying(): Boolean = playing
    fun isBooting(): Boolean = waitGame && !playing

    fun applyLayers(activity: Activity) {
        try {
            applyMode(activity)
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "layers", t)
        }
    }

    fun inGame(ctx: Context): Boolean {
        val act = ctx as? Activity ?: return false
        return hasGameSurface(act.findViewById(android.R.id.content)) ||
            hasGameSurface(act.window?.decorView)
    }

    internal fun hasGameSurface(v: View?): Boolean {
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
        if (playing) {
            waitGame = false
            return false
        }
        if (hasGameSurface(content) || hasGameSurface(activity.window?.decorView)) {
            playing = true
            waitGame = false
            return false
        }
        if (waitGame) return true
        if (looksLikeSettings(content) || looksLikeSettings(activity.window?.decorView)) {
            waitGame = false
            return false
        }
        // Never arm from a tap. Compose settings have no TextView, so grid
        // hit-tests used to fire in Kenji / Android settings. Clock only
        // while our own EmulationService is up (or JNI already booting).
        if (!emulationRunning(activity) && !kernelBooting()) {
            waitGame = false
            return false
        }
        waitGame = true
        return true
    }

    private fun emulationRunning(ctx: Context): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            @Suppress("DEPRECATION")
            val list = am.getRunningServices(64) ?: return false
            list.any { info ->
                val n = info.service?.className.orEmpty()
                n.contains("EmulationService")
            }
        } catch (_: Throwable) {
            false
        }
    }

    private fun kernelBooting(): Boolean {
        val blob = (BootLog.dump() + "\n" + BootLog.kernelDump()).lowercase(java.util.Locale.US)
        return blob.contains("javainitialize") || blob.contains("deviceinitialize")
    }

    private fun looksGameLoad(title: String): Boolean {
        val t = title.replace('\n', ' ').trim()
        if (!t.contains("Loading", ignoreCase = true) && !t.contains("Загрузка", ignoreCase = true)) return false
        val rest = t.replace("Loading", "", ignoreCase = true)
            .replace("Загрузка", "", ignoreCase = true)
            .trim()
        return rest.length >= 2
    }

    private fun scrapeLoadingTitle(): String? {
        for (root in allWindows()) {
            val t = loadingTitle(root)
            if (t != null && looksGameLoad(t)) return t
        }
        return null
    }

    private fun officialGameDialog(): Boolean {
        if (playing) return false
        for (root in allWindows()) {
            if (ours(root) || isOurDialog(root) || isSystemPrompt(root)) continue
            if (hasGameSurface(root)) continue
            if (findOfficialLoader(root, 0)) return true
            if (isKenjiLoadWindow(root)) return true
        }
        return false
    }

    private fun isSystemPrompt(root: View): Boolean {
        val keys = arrayOf(
            "Разрешить", "Запретить", "Allow", "Deny", "Don't allow",
            "уведомлен", "notification", "хранить", "storage",
            "Все файлы", "All files", "доступ",
        )
        for (k in keys) if (findText(root, k)) return true
        return false
    }

    private fun isKenjiLoadWindow(root: View): Boolean {
        if (looksLikeLibrary(root)) return false
        if (isFullScreen(root)) {
            return hasTinyCard(root, 0)
        }
        val dm = root.resources.displayMetrics
        val w = root.width / dm.density
        val h = root.height / dm.density
        return w in 140f..480f && h in 70f..320f
    }

    private fun hasTinyCard(v: View, depth: Int): Boolean {
        if (depth > 10 || ours(v)) return false
        val dm = v.resources.displayMetrics
        if (v.width > 0 && v.height > 0) {
            val w = v.width / dm.density
            val h = v.height / dm.density
            if (w in 160f..420f && h in 80f..260f) return true
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasTinyCard(v.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun isFullScreen(v: View): Boolean {
        val dm = v.resources.displayMetrics
        return v.width >= dm.widthPixels * 8 / 10 && v.height >= dm.heightPixels * 7 / 10
    }

    private fun isOurDialog(root: View): Boolean =
        findText(root, "карточка игры") || findText(root, "журнал запуска") ||
            findText(root, "настройки игры") || findText(root, "запуск и слои")

    private fun looksLikeSettings(root: View?): Boolean {
        if (root == null) return false
        val keys = arrayOf(
            "Install Firmware", "Ignore Missing Services", "Memory Manager",
            "Memory Configuration", "Shader Cache", "Low Power PPTC",
            "Jit Cache", "Fs Integrity", "System Settings", "Quick Settings",
            "Install Keys", "prod.keys (Kenji",
        )
        for (k in keys) if (findText(root, k)) return true
        return false
    }

    private fun looksLikeLibrary(root: View?): Boolean {
        if (root == null) return false
        return findVisibleText(root, "Search")
    }

    private fun findVisibleText(v: View, needle: String): Boolean {
        if (v.visibility != View.VISIBLE) return false
        if (v is TextView && v.text?.toString()?.contains(needle, ignoreCase = true) == true) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (findVisibleText(v.getChildAt(i), needle)) return true
            }
        }
        return false
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
            if (t == TAG || t == TAG_HUD || t == TAG_LOAD || t == TAG_INJECT || t == HoldMenu.TAG) return true
            p = cur.parent as? View
        }
        return false
    }

    internal fun allWindowsPublic(): List<View> = allWindows()
    internal fun isSpaceView(v: View): Boolean = ours(v)
    internal fun isLibraryWindow(v: View): Boolean =
        looksLikeLibrary(v) || isOurDialog(v) || looksLikeSettings(v)

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
        hideOfficialLoader(null)
    }

    private fun hideOfficialLoader(host: Activity?) {
        if (host != null) LoadOverlay.buryKenji(host)
    }

    private fun plantLoader(host: Activity, vg: ViewGroup) {
        // Never plant a loader into other windows.
    }

    private fun stripInjected() {
        try {
            for (root in allWindows()) {
                val vg = root as? ViewGroup ?: continue
                val bar = vg.findViewWithTag<View>(TAG_INJECT) ?: continue
                vg.removeView(bar)
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
            waitGame = false
            playing = false
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
        val space = activity.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
        if (space.getBoolean("need_shelf_reload", false) && !inGame(activity)) {
            FastScan.reloadShelf(activity, force = true)
        }
        val allFiles = AccessFix.hasAllFiles()
        val scanned = space.getBoolean("scanned_all_files", false)
        if (allFiles && !scanned && !inGame(activity) && seeded) {
            space.edit().putBoolean("scanned_all_files", true).commit()
            Thread({
                try {
                    FastScan.run(activity)
                    activity.runOnUiThread {
                        (activity.findViewById<ViewGroup>(android.R.id.content)
                            ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                        FastScan.reloadShelf(activity, force = true)
                    }
                } catch (t: Throwable) {
                    android.util.Log.e("KenjiSpace", "scan2", t)
                }
            }, "fast-scan-grant").start()
        }
        if (!seeded && !inGame(activity)) {
            seeded = true
            IdleWork.bg("ks-access") {
                AccessFix.repair(activity)
                GameFolder.sanitize(activity)
            }
            IdleWork.bg("ks-scan") {
                if (IdleWork.aborted()) return@bg
                if (AccessFix.hasAllFiles()) {
                    activity.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
                        .edit().putBoolean("scanned_all_files", true).commit()
                    FastScan.run(activity)
                }
            }
            IdleWork.bg("ks-fw") {
                if (IdleWork.aborted()) return@bg
                DataSeed.ensure(activity)
            }
            IdleWork.bg("ks-prefs") {
                if (IdleWork.aborted()) return@bg
                SettingsBank.applyDefaultOnce(activity)
                SettingsBank.enableFps(activity)
                activity.runOnUiThread {
                    (activity.findViewById<ViewGroup>(android.R.id.content)
                        ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                }
            }
        }
        poll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        main.removeCallbacksAndMessages(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
        (content?.findViewWithTag<View>(TAG) as? Panel)?.collapse()
        content?.findViewWithTag<View>(TAG)?.visibility = View.GONE
        (content?.findViewWithTag<View>(TAG_LOAD) as? BounceClock)?.stop()
        content?.findViewWithTag<View>(TAG_LOAD)?.visibility = View.GONE
        HoldMenu.hide(activity)
        waitGame = false
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
                val gap = if (playing) 2500 else if (waitGame) 1000 else 2000
                main.postAtTime(this, activity, android.os.SystemClock.uptimeMillis() + gap)
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
        if (content.findViewWithTag<View>(TAG_LOAD) == null) {
            val load = BounceClock(activity)
            load.tag = TAG_LOAD
            load.visibility = View.GONE
            content.addView(load, FrameLayout.LayoutParams(-1, -1))
            load.elevation = 40f
        }
    }

    /** Kenji's game view sits on top of our children — pin chrome to that host. */
    private fun gameHost(activity: Activity): ViewGroup {
        val content = activity.findViewById<ViewGroup>(android.R.id.content)
            ?: (activity.window?.decorView as? ViewGroup)
            ?: throw IllegalStateException("no host")
        val decor = activity.window?.decorView
        val dm = activity.resources.displayMetrics
        for (root in allWindows()) {
            if (root === decor || root === content) continue
            if (isSpaceView(root)) continue
            val vg = root as? ViewGroup ?: continue
            if (vg.width < dm.widthPixels * 7 / 10) continue
            if (vg.height < dm.heightPixels * 6 / 10) continue
            if (looksLikeLibrary(vg)) continue
            return vg
        }
        return content
    }

    private fun pinChrome(activity: Activity) {
        val host = try {
            gameHost(activity)
        } catch (_: Throwable) {
            return
        }
        fun ensure(tag: String, make: () -> View): View {
            var v = host.findViewWithTag<View>(tag)
            if (v == null) {
                for (root in allWindows()) {
                    val vg = root as? ViewGroup ?: continue
                    val found = vg.findViewWithTag<View>(tag) ?: continue
                    if (found.parent !== host) {
                        try {
                            (found.parent as? ViewGroup)?.removeView(found)
                        } catch (_: Throwable) {
                        }
                    }
                    v = found
                    break
                }
            }
            if (v == null) {
                v = make()
                v.tag = tag
            }
            if (v.parent !== host) {
                try {
                    (v.parent as? ViewGroup)?.removeView(v)
                } catch (_: Throwable) {
                }
                try {
                    host.addView(v, FrameLayout.LayoutParams(-1, -1))
                } catch (_: Throwable) {
                    try {
                        host.addView(v, ViewGroup.LayoutParams(-1, -1))
                    } catch (_: Throwable) {
                    }
                }
            }
            v.elevation = 96f
            v.translationZ = 96f
            v.visibility = View.VISIBLE
            v.bringToFront()
            return v
        }
        fun drop(tag: String) {
            for (root in allWindows()) {
                val vg = root as? ViewGroup ?: continue
                val v = vg.findViewWithTag<View>(tag) ?: continue
                when (v) {
                    is BounceClock -> v.stop()
                    is PlayHud -> v.stop()
                }
                v.visibility = View.GONE
            }
        }
        drop(TAG_HUD)
        if (LayerBank.anySpaceOnGame(activity)) {
            val load = ensure(TAG_LOAD) { BounceClock(activity) } as? BounceClock
            load?.start()
            load?.bringToFront()
        } else {
            drop(TAG_LOAD)
        }
    }

    private fun hideChrome(activity: Activity) {
        for (root in allWindows()) {
            val vg = root as? ViewGroup ?: continue
            vg.findViewWithTag<View>(TAG_HUD)?.let {
                (it as? PlayHud)?.stop()
                it.visibility = View.GONE
            }
            vg.findViewWithTag<View>(TAG_LOAD)?.let {
                (it as? BounceClock)?.stop()
                it.visibility = View.GONE
            }
        }
    }

    private fun applyMode(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val surface = hasGameSurface(content) || hasGameSurface(activity.window?.decorView)
        if (surface) playing = true
        val emu = emulationRunning(activity)
        // Factory shelf has Compose "Search" — not a TextView. HUD on the
        // library was PlayHud, not Kenji. Chip = BounceClock. Factory = logo/covers.
        val shelf = !surface && !emu && !waitGame
        if (shelf) playing = false
        launching(activity, content)
        DataSeed.allowEnsure = shelf
        IdleWork.pause = !shelf
        try {
            if (shelf) {
                activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                activity.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        } catch (_: Throwable) {
        }
        val panel = content.findViewWithTag<View>(TAG) as? Panel
        if (shelf) {
            waitGame = false
            LoadOverlay.reset()
            hideChrome(activity)
            panel?.visibility = View.VISIBLE
            if (panel != null) panel.post { shiftOfficial(content, panel) }
        } else {
            panel?.collapse()
            panel?.visibility = View.GONE
            unshiftOfficial(content, panel)
            HoldMenu.hide(activity)
            pinChrome(activity)
            LoadOverlay.clearSecureCheap(activity)
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
            if (v === panel || v.tag == TAG_HUD || v.tag == TAG_LOAD || v.tag == HoldMenu.TAG) continue
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
            if (v === panel || v.tag == TAG_HUD || v.tag == TAG_LOAD || v.tag == HoldMenu.TAG) continue
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

    private fun onShelf(activity: Activity): Boolean {
        if (playing || waitGame || inGame(activity)) return false
        if (emulationRunning(activity)) return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val decor = activity.window?.decorView
        if (looksLikeSettings(content) || looksLikeSettings(decor)) return false
        return true
    }

    private fun onGameGrid(activity: Activity, ev: MotionEvent): Boolean {
        if (onOurChrome(activity, ev)) return false
        if (!onShelf(activity)) return false
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        val h = content.height
        if (h <= 0) return false
        val panel = content.findViewWithTag<View>(TAG)
        // Skip Kenji home / gear / search row and the folder/refresh FABs.
        val top = (panel?.height ?: 0) + dp(activity, 108)
        val bot = h - dp(activity, 92)
        return ev.y > top && ev.y < bot
    }

    private fun onOurChrome(activity: Activity, ev: MotionEvent): Boolean {
        if (HoldMenu.hits(activity, ev)) return true
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return false
        listOf(TAG, TAG_HUD, TAG_LOAD, TAG_INJECT).forEach { tag ->
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
        private var grid = false
        private var forwarded = false
        private var captured: MotionEvent? = null
        private var posted: Runnable? = null
        private val slop = 28f * host.resources.displayMetrics.density

        private fun cancel() {
            posted?.let { main.removeCallbacks(it) }
            posted = null
        }

        private fun dropCaptured() {
            captured?.recycle()
            captured = null
        }

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (HoldMenu.isOpen(host)) {
                if (HoldMenu.hits(host, ev) || onOurChrome(host, ev)) {
                    return base.dispatchTouchEvent(ev)
                }
                if (ev.actionMasked == MotionEvent.ACTION_DOWN) HoldMenu.hide(host)
                return true
            }
            if (onOurChrome(host, ev)) {
                return base.dispatchTouchEvent(ev)
            }
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    cancel()
                    dropCaptured()
                    hold = false
                    forwarded = false
                    grid = onGameGrid(host, ev) && !HoldMenu.isOpen(host)
                    if (grid) {
                        sx = ev.rawX
                        sy = ev.rawY
                        captured = MotionEvent.obtain(ev)
                        val run = Runnable {
                            hold = true
                            HoldMenu.show(host)
                        }
                        posted = run
                        main.postDelayed(run, 380)
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (grid && !forwarded && !hold) {
                        val dx = ev.rawX - sx
                        val dy = ev.rawY - sy
                        if (dx * dx + dy * dy > slop * slop) {
                            cancel()
                            forwarded = true
                            captured?.let { base.dispatchTouchEvent(it) }
                            dropCaptured()
                            return base.dispatchTouchEvent(ev)
                        }
                        return true
                    }
                    if (hold) return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    cancel()
                    if (hold) {
                        hold = false
                        dropCaptured()
                        return true
                    }
                    if (grid && !forwarded) {
                        forwarded = true
                        captured?.let { base.dispatchTouchEvent(it) }
                        dropCaptured()
                        return base.dispatchTouchEvent(ev)
                    }
                    dropCaptured()
                }
            }
            return base.dispatchTouchEvent(ev)
        }
    }

    private fun showGameCard(host: Activity) {
        LaunchCard.show(host)
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

            val layers = TextView(host)
            layers.text = "слои"
            layers.gravity = Gravity.CENTER
            layers.setTextColor(Color.BLACK)
            layers.setTypeface(Typeface.DEFAULT_BOLD)
            layers.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val ld = GradientDrawable()
            ld.setColor(MINT)
            ld.cornerRadius = dp(16).toFloat()
            layers.background = ld
            layers.setPadding(dp(10), dp(6), dp(10), dp(6))
            val llp = LayoutParams(-2, dp(32))
            llp.marginEnd = dp(8)
            layers.layoutParams = llp
            layers.setOnClickListener { HoldMenu.show(host, HoldMenu.PAGE_LAYERS) }
            head.addView(layers, head.childCount - 1)
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
            bridges.addView(rowBtn("Найти на диске") { scanDisk() })
            bridges.addView(rowBtn("Слои в игре · запуск") { LaunchCard.show(host) })
            var clockBtn: Button? = null
            clockBtn = rowBtn(if (LayerBank.chipOn(host)) "Часы ожидания · вкл" else "Часы ожидания · выкл") {
                val on = !LayerBank.chipOn(host)
                LayerBank.setChip(host, on)
                clockBtn?.text = if (on) "Часы ожидания · вкл" else "Часы ожидания · выкл"
                SpaceHook.applyLayers(host)
                Toast.makeText(
                    host,
                    if (on) "часы вкл · тяните чип, ⚙ — оверлей" else "часы выкл",
                    Toast.LENGTH_SHORT,
                ).show()
            }
            bridges.addView(clockBtn)
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
                if (!AccessFix.hasAllFiles()) {
                    append("нет доступа ко всем файлам — нажмите «Найти на диске»\n")
                }
                append(FastScan.lastLine).append('\n')
                append("слои: ").append(LayerBank.summary(host)).append('\n')
                append("удержите обложку — нижнее меню: настройки / пресеты / слои")
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

        private fun scanDisk() {
            if (!AccessFix.hasAllFiles()) {
                status.text = "нужен доступ ко всем файлам — включите и нажмите ещё раз"
                AccessFix.askAllFiles(host)
                Toast.makeText(
                    host,
                    "включите доступ ко всем файлам и нажмите «Найти на диске» ещё раз",
                    Toast.LENGTH_LONG,
                ).show()
                return
            }
            status.text = "ищу игры на диске…"
            Thread({
                val r = try {
                    FastScan.run(host)
                } catch (t: Throwable) {
                    host.runOnUiThread {
                        Toast.makeText(host, "сканер: ${t.message}", Toast.LENGTH_LONG).show()
                    }
                    return@Thread
                }
                host.runOnUiThread {
                    refresh()
                    Toast.makeText(host, r.line(), Toast.LENGTH_LONG).show()
                    FastScan.reloadShelf(host, force = true)
                }
            }, "fast-scan").start()
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
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
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
            fab.text = "⚙"
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

        override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
            if (visibility != View.VISIBLE) return false
            return if (hitsChrome(ev)) super.dispatchTouchEvent(ev) else false
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
            val layers = Button(host)
            layers.text = "слои в игре"
            layers.isAllCaps = false
            layers.setOnClickListener { HoldMenu.show(host, HoldMenu.PAGE_LAYERS) }
            sheetBox.addView(layers)
        }

        private fun paintStats() {
            if (!SettingsBank.overlayOn(host)) {
                stats.visibility = GONE
                return
            }
            stats.visibility = VISIBLE
            if (fps >= 1.0) LoadOverlay.onGameFps(host)
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

        fun applyMode(showStats: Boolean, showBar: Boolean) {
            stats.visibility = if (showStats) VISIBLE else GONE
            fab.visibility = if (showBar && !fabHidden) VISIBLE else GONE
            bar.visibility = if (showBar) VISIBLE else GONE
            if (!showBar) sheet.visibility = GONE
        }

        fun start() {
            if (running) return
            running = true
            frames = 0
            lastNs = 0L
            applyMode(LayerBank.statsOn(host), LayerBank.hudOn(host))
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
