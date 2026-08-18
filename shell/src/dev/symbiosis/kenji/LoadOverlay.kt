package dev.symbiosis.kenji

import android.app.Activity

/** Extra windows over the game are forbidden. Stubs keep old call sites compiling. */
object LoadOverlay {
    fun show(activity: Activity, title: String) {
        hide(activity)
    }

    fun onGameFps(activity: Activity) {
        hide(activity)
    }

    fun hide(activity: Activity? = null) {}

    fun buryKenji(activity: Activity) {}
}
