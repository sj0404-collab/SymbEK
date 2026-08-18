package dev.symbiosis.kenji

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/**
 * This Gradle module is not what CI ships.
 * The APK is official Kenji-NX + native Java shelf. No HTML, no React, no WebView.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply {
            text = "Kenji Space: native shelf + official GameHost. No HTML."
            setPadding(48, 48, 48, 48)
        })
    }
}
