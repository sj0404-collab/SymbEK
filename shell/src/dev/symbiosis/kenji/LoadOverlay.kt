package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * Kenji's Loading / shader-check is a FULLSCREEN extra window with
 * FLAG_SECURE. We skipped fullscreen, so it covered everything and
 * blocked screenshots. Neutralize that window (alpha 0, no secure,
 * not touchable). Never alpha-0 the activity decor (that's the game).
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
        if (activity != null) clearSecure(activity)
    }

    fun buryKenji(activity: Activity) {
        ghostLoader(activity)
    }

    fun ghostLoader(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 250L) return
        lastBury = now
        clearSecure(activity)
        try {
            val decor = activity.window?.decorView
            val content = activity.findViewById<ViewGroup>(android.R.id.content)
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (isPrompt(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                neutralizeWindow(activity, root)
            }
            if ((SpaceHook.isPlaying() || SpaceHook.isBooting()) && content != null) {
                muteCoveringSiblings(content)
            }
        } catch (_: Throwable) {
        }
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

    private fun neutralizeWindow(activity: Activity, root: View) {
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

    /** Full-size Kenji Compose overlay sitting on top of SurfaceView. */
    private fun muteCoveringSiblings(content: ViewGroup) {
        val dm = content.resources.displayMetrics
        for (i in 0 until content.childCount) {
            val v = content.getChildAt(i)
            if (SpaceHook.isSpaceView(v)) continue
            if (SpaceHook.hasGameSurface(v)) continue
            if (v.width < dm.widthPixels * 7 / 10) continue
            if (v.height < dm.heightPixels * 6 / 10) continue
            if (v.alpha == 0f) continue
            v.alpha = 0f
            v.isClickable = false
            v.isFocusable = false
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
}
