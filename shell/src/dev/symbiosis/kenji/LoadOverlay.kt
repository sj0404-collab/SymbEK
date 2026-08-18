package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/** Puts the Space loader in its own window above Kenji's Compose dialog. */
object LoadOverlay {
    private var bar: SpaceHook.LoadBar? = null
    private var host: Activity? = null

    fun show(activity: Activity, title: String) {
        host = activity
        val existing = bar
        val view = if (existing != null && existing.context === activity) {
            existing
        } else {
            try {
                if (existing?.parent != null) {
                    activity.windowManager.removeViewImmediate(existing)
                }
            } catch (_: Throwable) {
            }
            SpaceHook.LoadBar(activity).also {
                it.tag = SpaceHook.TAG_LOAD
                bar = it
            }
        }
        fading = false
        view.start(title)
        if (view.parent == null) {
            val lp = WindowManager.LayoutParams()
            lp.width = WindowManager.LayoutParams.MATCH_PARENT
            lp.height = WindowManager.LayoutParams.MATCH_PARENT
            lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
            lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.format = PixelFormat.TRANSLUCENT
            lp.gravity = Gravity.FILL
            lp.token = activity.window?.decorView?.windowToken
            lp.title = "kenji-space-load"
            try {
                activity.windowManager.addView(view, lp)
            } catch (_: Throwable) {
                val content = activity.findViewById<ViewGroup>(android.R.id.content)
                if (view.parent == null && content != null) {
                    content.addView(view, ViewGroup.LayoutParams(-1, -1))
                    view.elevation = 256f
                    view.translationZ = 256f
                }
            }
        } else {
            try {
                view.bringToFront()
                val lp = view.layoutParams
                if (lp is WindowManager.LayoutParams) {
                    activity.windowManager.updateViewLayout(view, lp)
                }
            } catch (_: Throwable) {
            }
        }
        buryKenji(activity)
    }

    @Volatile private var fading = false

    fun onGameFps(activity: Activity) {
        val view = bar ?: return
        if (fading) return
        fading = true
        view.completeThenFade { hide(activity) }
    }

    fun hide(activity: Activity? = host) {
        val view = bar ?: return
        view.stop()
        try {
            if (view.parent != null) {
                val wm = activity?.windowManager
                if (wm != null && view.layoutParams is WindowManager.LayoutParams) {
                    wm.removeViewImmediate(view)
                } else {
                    (view.parent as? ViewGroup)?.removeView(view)
                }
            }
        } catch (_: Throwable) {
        }
        bar = null
        host = null
        fading = false
    }

    /** Official Kenji dialog stays, but fully invisible — no flash, no fight. */
    fun buryKenji(activity: Activity) {
        try {
            val wm = activity.windowManager
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === bar || root === activity.window?.decorView) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.isLibraryWindow(root)) continue
                try {
                    val lp = root.layoutParams
                    if (lp is WindowManager.LayoutParams && lp.alpha != 0f) {
                        lp.alpha = 0f
                        wm.updateViewLayout(root, lp)
                    }
                    if (root.alpha != 0f) root.alpha = 0f
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
