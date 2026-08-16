package org.kenjinx.android

import com.sun.jna.JNIEnv
import com.sun.jna.Library
import com.sun.jna.Native
import java.util.Collections

/**
 * Official Kenji C API, same signatures as KenjinxNativeJna.
 * Loaded from the packaged libkenjinx.so.
 */
interface KenjinxCore : Library {
    fun javaInitialize(appPath: String, env: JNIEnv): Boolean
    fun deviceInitialize(
        memoryManagerMode: Int,
        useNce: Boolean,
        memoryConfiguration: Int,
        systemLanguage: Int,
        regionCode: Int,
        vSyncMode: Int,
        enableDockedMode: Boolean,
        enablePptc: Boolean,
        enableLowPowerPptc: Boolean,
        enableJitCacheEviction: Boolean,
        enableInternetAccess: Boolean,
        enableFsIntegrityChecks: Boolean,
        fsGlobalAccessLogMode: Int,
        timeZone: String,
        ignoreMissingServices: Boolean
    ): Boolean
    fun graphicsInitialize(
        rescale: Float,
        maxAnisotropy: Float,
        fastGpuTime: Boolean,
        fast2DCopy: Boolean,
        enableMacroJit: Boolean,
        enableMacroHLE: Boolean,
        enableShaderCache: Boolean,
        enableTextureRecompression: Boolean,
        backendThreading: Int
    ): Boolean
    fun graphicsInitializeRenderer(extensions: Array<String>, extensionsLength: Int, driver: Long): Boolean
    fun graphicsRendererSetSize(width: Int, height: Int)
    fun graphicsRendererRunLoop()
    fun deviceLoadDescriptor(fileDescriptor: Int, gameType: Int, updateDescriptor: Int): Boolean
    fun inputInitialize(width: Int, height: Int)
    fun deviceCloseEmulation()
    fun deviceSignalEmulationClose()
    fun loggingSetEnabled(logLevel: Int, enabled: Boolean)
    fun deviceInstallFirmware(fileDescriptor: Int, isXci: Boolean)
    fun deviceVerifyFirmware(fileDescriptor: Int, isXci: Boolean): String
    fun deviceGetInstalledFirmwareVersion(): String
    fun deviceReloadFilesystem()
}

object Kenji {
    val core: KenjinxCore by lazy {
        System.loadLibrary("kenjinxjni")
        Native.load(
            "kenjinx",
            KenjinxCore::class.java,
            Collections.singletonMap(Library.OPTION_ALLOW_OBJECTS, true)
        )
    }
}
