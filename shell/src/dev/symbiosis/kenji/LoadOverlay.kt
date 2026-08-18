package dev.symbiosis.kenji

import android.app.Activity
import android.os.SystemClock
import android.view.View
import android.view.WindowManager

/**
 * Do not add a second window. 1.0.75's extra overlay sat on top of Kenji's
 * Loading and they fought. We only ghost their dialog (alpha 0).
 */
object LoadOverlay {
    @Volatile private var lastBury = 0L

    fun show(activity: Activity, title: String) {
        buryKenji(activity)
    }

    fun onGameFps(activity: Activity) {
        hide(activity)
    }

    fun hide(activity: Activity? = null) {}

    fun buryKenji(activity: Activity) {
        val now = SystemClock.uptimeMillis()
        if (now - lastBury < 80L) return
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
                if (root === decor) continue
                if (SpaceHook.isSpaceView(root)) continue
                if (SpaceHook.hasGameSurface(root)) continue
                try {
                    val lp = root.layoutParams
                    if (lp is WindowManager.LayoutParams) {
                        val title = lp.title?.toString().orEmpty()
                        if (title.contains("kenji-space", ignoreCase = true)) continue
                        val dm = root.resources.displayMetrics
                        val full = root.width >= dm.widthPixels * 8 / 10 &&
                            root.height >= dm.heightPixels * 7 / 10
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
                    if (!playing) {
                        root.visibility = View.INVISIBLE
                        root.isClickable = false
                    }
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
        }
    }
}
