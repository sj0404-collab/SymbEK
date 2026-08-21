package dev.symbiosis.kenji

import android.app.Activity

/** Back-compat. Hold-menu is the bottom sheet with separate pages. */
object LaunchCard {
    fun show(host: Activity) = HoldMenu.show(host)
}
