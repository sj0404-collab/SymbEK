package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Kenji's "Loading" + hourglass sits on the shelf (often Compose, no
 * TextView) without opening GameHost. Ghost that card. No dim.
 */
object LoadOverlay {
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        ghostLoader(activity)
    }

    fun onGameFps(activity: Activity) {}

    fun hide(activity: Activity? = null) {}

    fun buryKenji(activity: Activity) {
        ghostLoader(activity)
    }

    fun ghostLoader(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 80L) return
        lastBury = now
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (isPrompt(root)) continue
                ghostLoadingWidgets(root, 0)
                ghostSizedCards(root, 0, root === decor)
                if (root !== decor) hidePopupIfLoader(activity, root)
            }
        } catch (_: Throwable) {
        }
    }

    fun hasShelfLoader(): Boolean {
        for (root in SpaceHook.allWindowsPublic()) {
            if (SpaceHook.isSpaceView(root) || SpaceHook.hasGameSurface(root)) continue
            if (isPrompt(root)) continue
            if (findLoader(root, 0)) return true
        }
        return false
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

    private fun findLoader(v: View, depth: Int): Boolean {
        if (depth > 14 || SpaceHook.isSpaceView(v)) return false
        if (isLoaderWidget(v) || isLoadingCard(v)) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (findLoader(v.getChildAt(i), depth + 1)) return true
        }
        return false
    }

    private fun isLoaderWidget(v: View): Boolean {
        if (v is ProgressBar) return true
        val n = v.javaClass.name
        if (n.contains("CircularProgress") || n.contains("LinearProgress") ||
            n.contains("LoadingIndicator") || n.contains("ProgressBar")
        ) return true
        if (v is TextView) {
            val t = v.text?.toString().orEmpty().trim()
            if (t.equals("Loading", true) || t.equals("Загрузка", true) ||
                t.startsWith("Loading", true) || t.startsWith("Загрузка")
            ) return true
        }
        return false
    }

    private fun isLoadingCard(v: View): Boolean {
        if (v.width <= 0 || v.height <= 0) return false
        val dm = v.resources.displayMetrics
        val w = v.width / dm.density
        val h = v.height / dm.density
        if (w !in 140f..480f || h !in 70f..280f) return false
        val ratio = w / h
        return ratio in 1.25f..4.5f
    }

    private fun ghostLoadingWidgets(v: View, depth: Int) {
        if (depth > 16 || SpaceHook.isSpaceView(v)) return
        if (isLoaderWidget(v)) {
            hideCard(v)
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) ghostLoadingWidgets(v.getChildAt(i), depth + 1)
        }
    }

    private fun ghostSizedCards(v: View, depth: Int, inDecor: Boolean) {
        if (depth > 12 || SpaceHook.isSpaceView(v)) return
        if (inDecor && isLoadingCard(v) && !looksLikeCover(v)) {
            v.alpha = 0f
            v.visibility = View.INVISIBLE
            return
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) ghostSizedCards(v.getChildAt(i), depth + 1, inDecor)
        }
    }

    private fun looksLikeCover(v: View): Boolean {
        if (v.width <= 0 || v.height <= 0) return false
        val r = v.width.toFloat() / v.height
        return r in 0.7f..1.25f
    }

    private fun hidePopupIfLoader(activity: Activity, root: View) {
        val dm = root.resources.displayMetrics
        if (root.width <= 0 || root.height <= 0) return
        val w = root.width / dm.density
        val h = root.height / dm.density
        val full = root.width >= dm.widthPixels * 8 / 10 &&
            root.height >= dm.heightPixels * 7 / 10
        if (full) return
        if (w in 90f..560f && h in 40f..400f) {
            root.alpha = 0f
            root.visibility = View.INVISIBLE
            try {
                val lp = root.layoutParams
                if (lp is WindowManager.LayoutParams) {
                    lp.dimAmount = 0f
                    lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    activity.windowManager.updateViewLayout(root, lp)
                }
            } catch (_: Throwable) {
            }
        }
    }

    private fun hideCard(v: View) {
        v.alpha = 0f
        var p: View = v
        repeat(8) {
            val par = p.parent as? View ?: return
            if (SpaceHook.isSpaceView(par)) return
            if (isLoadingCard(par)) {
                par.alpha = 0f
                par.visibility = View.INVISIBLE
                return
            }
            p = par
        }
    }
}
