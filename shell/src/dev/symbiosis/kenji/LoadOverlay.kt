package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Kenji's player spinner is a modal extra window: full screen, FLAG_SECURE,
 * eats touches, then dismisses when the game is up. Hide that window while
 * booting. Never touch activity content (game + pad). Cheap: mViews only.
 */
object LoadOverlay {
    @Volatile private var last = 0L

    fun show(activity: Activity, title: String) {
        hideBlockingLoader(activity)
    }

    fun onGameFps(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) clearSecureCheap(activity)
    }

    fun buryKenji(activity: Activity) {
        hideBlockingLoader(activity)
    }

    fun ghostLoader(activity: Activity) {
        hideBlockingLoader(activity)
    }

    fun hideKenjiLoadingOnce(activity: Activity) {
        hideBlockingLoader(activity)
    }

    fun reset() {}

    fun clearSecureCheap(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }

    fun hideBlockingLoader(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - last < 900L) return
        last = now
        clearSecureCheap(activity)
        if (SpaceHook.isPlaying()) return
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (isPrompt(root)) continue
                blockExtra(activity, root)
            }
        } catch (_: Throwable) {
        }
    }

    private fun blockExtra(activity: Activity, root: View) {
        try {
            root.alpha = 0f
            val lp = root.layoutParams as? WindowManager.LayoutParams ?: return
            lp.alpha = 0f
            lp.dimAmount = 0f
            lp.flags = (lp.flags
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) and
                WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                WindowManager.LayoutParams.FLAG_SECURE.inv()
            activity.windowManager.updateViewLayout(root, lp)
        } catch (_: Throwable) {
        }
    }

    private fun isPrompt(root: View): Boolean {
        val keys = arrayOf(
            "Разрешить", "Запретить", "Allow", "Deny", "Don't allow",
            "уведомлен", "notification", "Install Firmware", "System Settings",
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
}
