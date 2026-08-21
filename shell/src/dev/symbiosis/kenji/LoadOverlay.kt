package dev.symbiosis.kenji

import android.app.Activity
import android.view.WindowManager

/** Only clear FLAG_SECURE. Do not hide Kenji windows — that faked a fix and blanked the game. */
object LoadOverlay {
    fun show(activity: Activity, title: String) {
        clearSecureCheap(activity)
    }

    fun onGameFps(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun hide(activity: Activity? = null) {
        if (activity != null) clearSecureCheap(activity)
    }

    fun buryKenji(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun ghostLoader(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun hideKenjiLoadingOnce(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun hideBlockingLoader(activity: Activity) {
        clearSecureCheap(activity)
    }

    fun reset() {}

    fun clearSecureCheap(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }
}
