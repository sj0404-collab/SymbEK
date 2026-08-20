package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Hide Kenji's Loading card only. Never INVISIBLE on the game tree,
 * never FLAG_NOT_TOUCHABLE, never FLAG_SECURE. Restore on release()
 * so screenshots and in-game UI keep working.
 */
object LoadOverlay {
    private data class Ghost(val v: View, val alpha: Float, val vis: Int)

    private val ghosted = ArrayList<Ghost>()
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        ghostLoader(activity)
    }

    fun onGameFps(activity: Activity) {
        release()
    }

    fun hide(activity: Activity? = null) {
        release()
    }

    fun buryKenji(activity: Activity) {
        ghostLoader(activity)
    }

    fun release() {
        val copy = ArrayList(ghosted)
        ghosted.clear()
        for (g in copy) {
            try {
                g.v.alpha = g.alpha
                g.v.visibility = g.vis
            } catch (_: Throwable) {
            }
        }
    }

    fun ghostLoader(activity: Activity) {
        if (SpaceHook.isPlaying()) {
            release()
            clearSecure(activity)
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 400L) return
        lastBury = now
        clearSecure(activity)
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (isPrompt(root)) continue
                hidePopupIfLoader(activity, root)
            }
        } catch (_: Throwable) {
        }
    }

    private fun clearSecure(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        try {
            for (root in SpaceHook.allWindowsPublic()) {
                val lp = root.layoutParams
                if (lp is WindowManager.LayoutParams &&
                    lp.flags and WindowManager.LayoutParams.FLAG_SECURE != 0
                ) {
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    activity.windowManager.updateViewLayout(root, lp)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun isPrompt(root: View): Boolean {
        val keys = arrayOf(
            "Разрешить", "Запретить", "Allow", "Deny", "Don't allow",
            "уведомлен", "notification", "Install Firmware", "System Settings",
            "Quick Settings", "Ignore Missing Services",
        )
        for (k in keys) if (hasText(root, k)) return true
        return false
    }

    private fun hasText(v: View, needle: String): Boolean {
        if (v is TextView && v.text?.toString()?.contains(needle, ignoreCase = true) == true) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), needle)) return true
        }
        return false
    }

    private fun hidePopupIfLoader(activity: Activity, root: View) {
        val dm = root.resources.displayMetrics
        if (root.width <= 0 || root.height <= 0) return
        val w = root.width / dm.density
        val h = root.height / dm.density
        val full = root.width >= dm.widthPixels * 8 / 10 &&
            root.height >= dm.heightPixels * 7 / 10
        if (full) return
        if (w !in 90f..560f || h !in 40f..400f) return
        remember(root)
        root.alpha = 0f
        try {
            val lp = root.layoutParams
            if (lp is WindowManager.LayoutParams) {
                lp.dimAmount = 0f
                lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                    WindowManager.LayoutParams.FLAG_SECURE.inv()
                activity.windowManager.updateViewLayout(root, lp)
            }
        } catch (_: Throwable) {
        }
    }

    private fun remember(v: View) {
        if (ghosted.any { it.v === v }) return
        ghosted.add(Ghost(v, v.alpha, v.visibility))
    }

    @Suppress("unused")
    private fun isLoaderWidget(v: View): Boolean {
        if (v is ProgressBar) return true
        val n = v.javaClass.name
        if (n.contains("CircularProgress") || n.contains("LinearProgress") ||
            n.contains("LoadingIndicator")
        ) return true
        if (v is TextView) {
            val t = v.text?.toString().orEmpty().trim()
            if (t.equals("Loading", true) || t.equals("Загрузка", true)) return true
        }
        return false
    }
}
