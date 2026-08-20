package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Layers:
 *   content = game + gamepad (never alpha-0)
 *   extra window = Kenji Loading / shaders (hide, don't restore while in game)
 *
 * 1.0.95 muted content → white. 1.0.96 restored extra windows → Loading
 * back on top of the game. Hide only extra Loading windows.
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
        if (now - lastBury < 300L) return
        lastBury = now
        clearSecure(activity)
        restoreContentOnly(activity)
        hideKenjiLoadingWindows(activity)
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
                // Do not force lp.alpha = 1 — that brings Loading back.
                if (changed) {
                    lp.flags = flags
                    activity.windowManager.updateViewLayout(root, lp)
                }
            }
        } catch (_: Throwable) {
        }
    }

    /** Only the activity content (game / gamepad). Not extra windows. */
    private fun restoreContentOnly(activity: Activity) {
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

    /**
     * Extra WM windows only. Fullscreen Loading / shader check lives here.
     * Decor = the game — never touch it.
     */
    private fun hideKenjiLoadingWindows(activity: Activity) {
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (isPrompt(root)) continue
                if (isOurUi(root)) continue
                neutralizeExtra(activity, root)
            }
        } catch (_: Throwable) {
        }
    }

    private fun neutralizeExtra(activity: Activity, root: View) {
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

    private fun isOurUi(root: View): Boolean {
        return hasText(root, "карточка игры") || hasText(root, "журнал запуска") ||
            hasText(root, "Kenji Space")
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
}
