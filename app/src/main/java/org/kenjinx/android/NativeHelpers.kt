package org.kenjinx.android

import android.view.Surface

/** JNI helpers from the official Android native library plus the real Vulkan probe. */
class NativeHelpers {
    companion object {
        val instance = NativeHelpers()

        init {
            // The official library owns the ANativeWindow, driver-loader and
            // JNI callbacks used by libkenjinx.so. The small companion library
            // only adds a Vulkan-properties query; it is not a second emulator.
            System.loadLibrary("kenjinxjni")
            System.loadLibrary("symbiosis_kenji")
        }
    }

    external fun releaseNativeWindow(window: Long)
    external fun getCreateSurfacePtr(): Long
    external fun getNativeWindow(surface: Surface): Long
    external fun loadDriver(nativeLibPath: String, privateAppsPath: String, driverName: String): Long
    external fun setTurboMode(enable: Boolean)
    external fun getMaxSwapInterval(nativeWindow: Long): Int
    external fun getMinSwapInterval(nativeWindow: Long): Int
    external fun setSwapInterval(nativeWindow: Long, swapInterval: Int): Int
    external fun getStringJava(ptr: Long): String
    external fun setIsInitialOrientationFlipped(isFlipped: Boolean)

    /** Returns the actual Vulkan physical-device/driver properties, never a guessed label. */
    external fun getVulkanDriverInfo(): String
}
