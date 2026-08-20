package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView

/**
 * No extra window, no dim. Only ghost Kenji's own "Loading <game>" widgets
 * while EmulationService is actually running. Never touch settings / prompts.
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
        if (now - lastBury < 120L) return
        lastBury = now
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        try {
            val decor = activity.window?.decorView
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (SpaceHook.isLibraryWindow(root)) continue
                if (isPrompt(root)) continue
                ghostLoadingWidgets(root, 0)
                try {
                    val lp = root.layoutParams
                    if (lp is WindowManager.LayoutParams) {
                        if (lp.dimAmount != 0f) {
                            lp.dimAmount = 0f
                            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                            activity.windowManager.updateViewLayout(root, lp)
                        }
                    }
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

    private fun ghostLoadingWidgets(v: View, depth: Int) {
        if (depth > 14 || SpaceHook.isSpaceView(v)) return
        if (v is TextView) {
            val t = v.text?.toString().orEmpty()
            if (t.contains("Loading", true) || t.contains("Загрузка")) {
                v.alpha = 0f
            }
        }
        val n = v.javaClass.name
        if (n.contains("CircularProgress") || n.contains("LinearProgress") ||
            n.contains("LoadingIndicator")
        ) {
            v.alpha = 0f
        }
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) ghostLoadingWidgets(v.getChildAt(i), depth + 1)
        }
    }
}
