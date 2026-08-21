package dev.symbiosis.kenji

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Factory Kenji Loading UI → gone. Boot/shaders keep running.
 * Never hide a fullscreen window (that is the Skia player — white screen).
 * Climb at most 3 parents, and only while the tree stays small.
 */
object LoadOverlay {
    private val SPACE = setOf(
        "space-panel", "space-hud", "space-load", "space-load-inject", "space-hold",
    )

    @Volatile private var quiet = 0

    fun show(activity: Activity, title: String) {
        tick(activity)
    }

    fun onGameFps(activity: Activity) {
        tick(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) tick(activity)
    }

    fun buryKenji(activity: Activity) {
        tick(activity)
    }

    fun ghostLoader(activity: Activity) {
        tick(activity)
    }

    fun hideKenjiLoadingOnce(activity: Activity) {
        tick(activity)
    }

    fun hideBlockingLoader(activity: Activity) {
        tick(activity)
    }

    fun reset() {
        quiet = 0
    }

    fun tick(activity: Activity) {
        clearSecureCheap(activity)
        if (quiet >= 5) return
        val hid = try {
            ghost(activity)
        } catch (_: Throwable) {
            false
        }
        if (hid) quiet = 0 else quiet++
    }

    fun clearSecureCheap(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }

    private fun ghost(activity: Activity): Boolean {
        var hid = false
        val decor = activity.window?.decorView
        for (root in windows()) {
            if (isOurs(root)) continue
            if (isSystemPrompt(root)) continue
            clearSecureOn(root, activity)
            if (root !== decor && !isFull(root) && isLoaderTree(root)) {
                if (root.visibility != View.GONE) {
                    root.visibility = View.GONE
                    root.isClickable = false
                    muteWindow(root, activity)
                    hid = true
                }
                continue
            }
            if (strip(root, 0)) hid = true
        }
        return hid
    }

    private fun strip(v: View, depth: Int): Boolean {
        if (depth > 12 || isOurs(v)) return false
        var hid = false
        if (isLoaderLeaf(v)) {
            val box = climbSmall(v)
            if (box != null && box.visibility != View.GONE) {
                box.visibility = View.GONE
                box.isClickable = false
                hid = true
            }
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (strip(v.getChildAt(i), depth + 1)) hid = true
            }
        }
        return hid
    }

    /** At most 3 parents. Stop before a fat Compose tree (the player). */
    private fun climbSmall(v: View): View? {
        var p: View = v
        repeat(3) {
            val par = p.parent as? View ?: return p
            if (isOurs(par)) return p
            if (descendants(par) > 36) return p
            if (isFull(par) && descendants(par) > 20) return p
            p = par
        }
        return p
    }

    private fun isLoaderLeaf(v: View): Boolean {
        val n = v.javaClass.name
        if (n.contains("ProgressBar") || n.contains("CircularProgress") ||
            n.contains("LinearProgress") || n.contains("LoadingIndicator") ||
            n.contains("CircularProgressIndicator")
        ) return true
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.contains("Loading", true) || t.contains("Загрузка") ||
                t.contains("Shader", true) || t.contains("Compiling", true)
            ) return true
        }
        return false
    }

    private fun isLoaderTree(v: View): Boolean {
        if (isLoaderLeaf(v)) return true
        val dm = v.resources.displayMetrics
        val w = if (v.width > 0) v.width / dm.density else 0f
        val h = if (v.height > 0) v.height / dm.density else 0f
        if (w in 80f..520f && h in 50f..400f && hasLoaderMark(v, 0)) return true
        return hasLoaderMark(v, 0) && descendants(v) <= 36
    }

    private fun hasLoaderMark(v: View, depth: Int): Boolean {
        if (depth > 8 || isOurs(v)) return false
        if (isLoaderLeaf(v)) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasLoaderMark(v.getChildAt(i), depth + 1)) return true
            }
        }
        return false
    }

    private fun isFull(v: View): Boolean {
        val dm = v.resources.displayMetrics
        return v.width >= dm.widthPixels * 8 / 10 && v.height >= dm.heightPixels * 7 / 10
    }

    private fun descendants(v: View): Int {
        if (v !is ViewGroup) return 1
        var n = 1
        for (i in 0 until v.childCount) n += descendants(v.getChildAt(i))
        return n
    }

    private fun isOurs(v: View): Boolean {
        var p: View? = v
        repeat(14) {
            val cur = p ?: return false
            if (cur.tag is String && cur.tag in SPACE) return true
            p = cur.parent as? View
        }
        return false
    }

    private fun isSystemPrompt(root: View): Boolean {
        val keys = arrayOf(
            "Разрешить", "Запретить", "Allow", "Deny", "Don't allow",
            "уведомлен", "notification", "Все файлы", "All files",
        )
        for (k in keys) if (hasText(root, k, 0)) return true
        return false
    }

    private fun hasText(v: View, needle: String, depth: Int): Boolean {
        if (depth > 10) return false
        if (v is TextView && v.text?.toString()?.contains(needle, true) == true) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) {
                if (hasText(v.getChildAt(i), needle, depth + 1)) return true
            }
        }
        return false
    }

    private fun clearSecureOn(root: View, activity: Activity) {
        try {
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
            if (lp.flags and WindowManager.LayoutParams.FLAG_SECURE == 0) return
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            activity.windowManager.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    private fun muteWindow(root: View, activity: Activity) {
        try {
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            activity.windowManager.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    private fun windows(): List<View> {
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
}
