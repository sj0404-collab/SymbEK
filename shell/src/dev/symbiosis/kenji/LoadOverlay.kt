package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * 1.0.95 set alpha=0 on Kenji's fullscreen window / Compose siblings.
 * The game is Skia/Compose, not SurfaceView — audio ran, picture was white.
 * Now: only strip FLAG_SECURE. Do not hide any fullscreen or content view.
 */
object LoadOverlay {
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        ghostLoader(activity)
    }

    fun onGameFps(activity: Activity) {
        clearSecure(activity)
        restoreGameViews(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) {
            clearSecure(activity)
            restoreGameViews(activity)
        }
    }

    fun buryKenji(activity: Activity) {
        ghostLoader(activity)
    }

    fun ghostLoader(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 400L) return
        lastBury = now
        clearSecure(activity)
        restoreGameViews(activity)
        if (SpaceHook.isPlaying()) return
        hideSmallLoadingPopups(activity)
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
                if (lp.alpha == 0f) {
                    lp.alpha = 1f
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

    /** Undo 1.0.95 alpha=0 on the game Compose view. */
    fun restoreGameViews(activity: Activity) {
        try {
            val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
            for (i in 0 until content.childCount) {
                val v = content.getChildAt(i)
                if (SpaceHook.isSpaceView(v)) continue
                if (v.alpha < 1f) v.alpha = 1f
            }
            val decor = activity.window?.decorView
            if (decor != null && decor.alpha < 1f) decor.alpha = 1f
            for (root in SpaceHook.allWindowsPublic()) {
                if (SpaceHook.isSpaceView(root)) continue
                if (root.alpha == 0f) root.alpha = 1f
            }
        } catch (_: Throwable) {
        }
    }

    private fun hideSmallLoadingPopups(activity: Activity) {
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
                if (w !in 90f..520f || h !in 40f..360f) continue
                if (!looksLoading(root)) continue
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

    private fun looksLoading(root: View): Boolean {
        return hasText(root, "Loading") || hasText(root, "Загрузка")
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
