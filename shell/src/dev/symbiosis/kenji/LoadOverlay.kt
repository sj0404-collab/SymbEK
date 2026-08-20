package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * The clock overlay sits ON the game. Never blank content. Never hide
 * fullscreen extra windows (that is often the Skia game / pad).
 * Only: strip FLAG_SECURE, restore content alpha, hide small "Loading" cards.
 */
object LoadOverlay {
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        ghostLoader(activity)
    }

    fun onGameFps(activity: Activity) {
        ghostLoader(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) ghostLoader(activity)
    }

    fun buryKenji(activity: Activity) {
        ghostLoader(activity)
    }

    fun ghostLoader(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 400L) return
        lastBury = now
        clearSecure(activity)
        restoreContentAlpha(activity)
        hideSmallLoadingCards(activity)
    }

    fun clearSecure(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        try {
            for (root in SpaceHook.allWindowsPublic()) {
                val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
                var flags = lp.flags
                var changed = false
                if (flags and WindowManager.LayoutParams.FLAG_SECURE != 0) {
                    flags = flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
                    changed = true
                }
                if (lp.dimAmount != 0f) {
                    lp.dimAmount = 0f
                    flags = flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                    changed = true
                }
                if (changed) {
                    lp.flags = flags
                    activity.windowManager.updateViewLayout(root, lp)
                }
            }
        } catch (_: Throwable) {
        }
    }

    private fun restoreContentAlpha(activity: Activity) {
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                if (SpaceHook.isSpaceView(v)) continue
                if (v.alpha < 1f) v.alpha = 1f
            }
        } catch (_: Throwable) {
        }
    }

    private fun hideSmallLoadingCards(activity: Activity) {
        try {
            val decor = activity.window?.decorView
            val dm = activity.resources.displayMetrics
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (isPrompt(root)) continue
                if (root.width >= dm.widthPixels * 7 / 10) continue
                if (root.height >= dm.heightPixels * 6 / 10) continue
                if (root.width <= 0 || root.height <= 0) continue
                val w = root.width / dm.density
                val h = root.height / dm.density
                if (w !in 90f..520f || h !in 40f..320f) continue
                if (!hasText(root, "Loading") && !hasText(root, "Загрузка")) continue
                try {
                    val lp = root.layoutParams as? WindowManager.LayoutParams ?: continue
                    lp.dimAmount = 0f
                    lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv() and
                        WindowManager.LayoutParams.FLAG_SECURE.inv()
                    activity.windowManager.updateViewLayout(root, lp)
                    root.alpha = 0f
                } catch (_: Throwable) {
                }
            }
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
