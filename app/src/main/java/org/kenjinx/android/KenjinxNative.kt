// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

package org.kenjinx.android

import android.util.Log

/**
 * Java callbacks looked up by the official core through
 * org/kenjinx/android/KenjinxNative.
 *
 * The surface and window values are real ANativeWindow pointers obtained from
 * NativeHelpers. Progress and UI callbacks are forwarded to the player HUD;
 * they are not silently discarded.
 */
object KenjinxNative {
    private const val TAG = "KenjinxNative"

    @Volatile var nativeSurface: Long = -1L
    @Volatile var nativeWindow: Long = -1L

    @Volatile
    var progressListener: ((text: String, progress: Float) -> Unit)? = null

    @Volatile
    var uiMessageListener: ((message: String) -> Unit)? = null

    @Volatile
    var keyboardListener: ((title: String, message: String, initialText: String, type: Int, min: Int, max: Int) -> Unit)? = null

    @Volatile
    var frameListener: (() -> Unit)? = null

    @JvmStatic
    fun test() {
        Log.d(TAG, "official core callback connected")
    }

    @JvmStatic
    fun frameEnded() {
        runCatching { frameListener?.invoke() }
    }

    @JvmStatic
    fun updateProgress(infoPtr: Long, progress: Float) {
        val text = if (infoPtr != 0L) {
            runCatching { NativeHelpers.instance.getStringJava(infoPtr) }.getOrDefault("")
        } else {
            ""
        }
        runCatching { progressListener?.invoke(text, progress) }
    }

    @JvmStatic
    fun getSurfacePtr(): Long = nativeSurface

    @JvmStatic
    fun getWindowHandle(): Long = nativeWindow

    @JvmStatic
    fun updateUiHandler(
        newTitlePointer: Long,
        newMessagePointer: Long,
        newWatermarkPointer: Long,
        newType: Int,
        min: Int,
        max: Int,
        nMode: Int,
        newSubtitlePointer: Long,
        newInitialTextPointer: Long
    ) {
        fun read(ptr: Long): String = if (ptr == 0L) "" else {
            runCatching { NativeHelpers.instance.getStringJava(ptr) }.getOrDefault("")
        }

        val title = read(newTitlePointer)
        val message = read(newMessagePointer)
        val watermark = read(newWatermarkPointer)
        val subtitle = read(newSubtitlePointer)
        val initialText = read(newInitialTextPointer)
        val combined = listOf(title, message, watermark, subtitle, initialText)
            .filter { it.isNotBlank() }
            .joinToString(" · ")
        if (combined.isNotBlank()) runCatching { uiMessageListener?.invoke(combined) }
        // Always deliver the handler request. The core blocks LoadApplication
        // until uiHandlerSetResponse. Dropping type=0 / empty strings hung
        // the player on «загрузка игры» with no dialog and no FPS.
        val listener = keyboardListener
        if (listener != null) {
            runCatching { listener.invoke(title, message, initialText, newType, min, max) }
        } else {
            Log.w(TAG, "UI callback with no listener type=$newType")
        }

        Log.d(TAG, "UI callback type=$newType range=$min..$max mode=$nMode")
    }
}
