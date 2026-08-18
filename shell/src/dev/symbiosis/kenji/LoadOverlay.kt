package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager

/** Puts the Space loader in its own window above Kenji's Compose dialog. */
object LoadOverlay {
    private var bar: SpaceHook.LoadBar? = null
    private var host: Activity? = null

    @Volatile private var fading = false
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        if (SpaceHook.isPlaying()) {
            hide(activity)
            return
        }
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
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_SECURE.inv()
            lp.format = PixelFormat.TRANSLUCENT
            lp.gravity = Gravity.FILL
            lp.token = activity.window?.decorView?.windowToken
            lp.title = "kenji-space-load"
            lp.alpha = 1f
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
            } catch (_: Throwable) {
            }
        }
        buryKenji(activity)
    }

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

    /**
     * Official Kenji Compose "Loading …" is its own window — often MATCH_PARENT
     * with a tiny card drawn in Compose (no TextView, no tiny Android child).
     * Only that extra window is made fully transparent. Never the activity,
     * never a window that already has the game surface.
     */
    fun buryKenji(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 700L) return
        lastBury = now
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
        try {
            val wm = activity.windowManager
            val decor = activity.window?.decorView
            val playing = SpaceHook.isPlaying()
            for (root in SpaceHook.allWindowsPublic()) {
                if (root === bar || root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                if (SpaceHook.isLibraryWindow(root) && root === decor) continue
                try {
                    val lp = root.layoutParams
                    if (lp is WindowManager.LayoutParams) {
                        val title = lp.title?.toString().orEmpty()
                        if (title.contains("kenji-space", ignoreCase = true)) continue
                        val dm = root.resources.displayMetrics
                        val full = root.width >= dm.widthPixels * 8 / 10 &&
                            root.height >= dm.heightPixels * 7 / 10
                        // In-game: only ghost leftover fullscreen Loading.
                        // Small windows may be on-screen controls — leave them.
                        if (playing && !full) continue
                        lp.alpha = 0f
                        lp.dimAmount = 0f
                        lp.flags = (lp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) and
                            WindowManager.LayoutParams.FLAG_SECURE.inv() and
                            WindowManager.LayoutParams.FLAG_DIM_BEHIND.inv()
                        wm.updateViewLayout(root, lp)
                    } else if (playing) {
                        continue
                    }
                    if (root.alpha != 0f) root.alpha = 0f
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
