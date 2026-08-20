package dev.symbiosis.kenji

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Do not walk WindowManager every tick during boot — that made launch 40s.
 * Hide Kenji's Loading extra-window once. Never touch content (game/pad).
 */
object LoadOverlay {
    @Volatile private var hiddenOnce = false

    fun show(activity: Activity, title: String) {
        hideKenjiLoadingOnce(activity)
    }

    fun onGameFps(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) clearSecureCheap(activity)
    }

    fun buryKenji(activity: Activity) {
        hideKenjiLoadingOnce(activity)
    }

    fun ghostLoader(activity: Activity) {
        hideKenjiLoadingOnce(activity)
    }

    fun reset() {
        hiddenOnce = false
    }

    fun clearSecureCheap(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }

    fun hideKenjiLoadingOnce(activity: Activity) {
        if (hiddenOnce) return
        hiddenOnce = true
        clearSecureCheap(activity)
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (!hasText(root, "Loading") && !hasText(root, "Загрузка")) continue
                try {
                    root.alpha = 0f
                    val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
                    lp.alpha = 0f
                    lp.dimAmount = 0f
                    lp.flags = (lp.flags
                        or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                        WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                        WindowManager.LayoutParams.FLAG_SECURE.inv()
                    activity.windowManager.updateViewLayout(root, lp)
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun hasText(v: View, needle: String): Boolean {
        if (v is TextView && v.text?.toString()?.contains(needle, ignoreCase = true) == true) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasText(v.getChildAt(i), needle)) return true
        }
        return false
    }
}
