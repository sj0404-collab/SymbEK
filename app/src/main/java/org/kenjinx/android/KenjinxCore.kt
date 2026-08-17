package org.kenjinx.android

import com.sun.jna.JNIEnv
import com.sun.jna.Library
import com.sun.jna.Native
import java.util.Collections

/**
 * The public C ABI exported by the official Kenji-NX 2.1.0-pr.2 core.
 *
 * This interface deliberately contains the lifecycle, surface and input calls
 * used by PlayerActivity. Omitting one of these calls does not make it
 * optional: the core keeps state between games and the Android input driver is
 * only advanced by inputUpdate().
 */
interface KenjinxCore : Library {
    fun javaInitialize(appPath: String, env: JNIEnv): Boolean

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

    fun graphicsInitializeRenderer(
        extensions: Array<String>,
        extensionsLength: Int,
        driver: Long
    ): Boolean

    fun graphicsRendererSetSize(width: Int, height: Int)
    fun graphicsRendererSetVsync(vSyncMode: Int)
    fun graphicsRendererRunLoop()
    fun graphicsSetBackendThreading(mode: Int)
    fun graphicsSetPresentEnabled(enabled: Boolean)

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

    fun deviceReinitEmulation()
    fun deviceReloadFilesystem()
    fun deviceCloseEmulation()
    fun deviceSignalEmulationClose()
    fun deviceWaitForGpuDone(timeoutMs: Int)
    fun deviceRecreateSwapchain()
    fun deviceSetWindowHandle(handle: Long)
    fun deviceSetSurfaceRotation(degrees: Int)
    fun deviceResize(width: Int, height: Int)
    fun detachWindow()

    fun deviceLoadDescriptor(fileDescriptor: Int, gameType: Int, updateDescriptor: Int): Boolean
    fun deviceGetGameInfo(fileDescriptor: Int, extension: String, info: dev.symbiosis.kenji.GameInfoReader.GameInfo)
    fun deviceGetGameFrameRate(): Double
    fun deviceGetGameFrameTime(): Double
    fun deviceGetGameFifo(): Double
    fun deviceInstallFirmware(fileDescriptor: Int, isXci: Boolean)
    fun deviceVerifyFirmware(fileDescriptor: Int, isXci: Boolean): String
    fun deviceGetInstalledFirmwareVersion(): String
    fun uiHandlerSetup()
    fun uiHandlerSetResponse(isOkPressed: Boolean, input: String)

    fun inputInitialize(width: Int, height: Int)
    fun inputSetClientSize(width: Int, height: Int)
    fun inputSetTouchPoint(x: Int, y: Int)
    fun inputReleaseTouchPoint()
    fun inputUpdate()
    fun inputConnectGamepad(index: Int): Int
    fun inputSetButtonPressed(button: Int, controllerId: Int)
    fun inputSetButtonReleased(button: Int, controllerId: Int)
    fun inputSetStickAxis(stick: Int, x: Float, y: Float, controllerId: Int)
    fun inputSetAccelerometerData(x: Float, y: Float, z: Float, controllerId: Int)
    fun inputSetGyroData(x: Float, y: Float, z: Float, controllerId: Int)

    fun audioSetPaused(paused: Boolean)
    fun audioSetMuted(muted: Boolean)
    fun loggingSetEnabled(logLevel: Int, enabled: Boolean)
}

object Kenji {
    /**
     * JNA loads the official libkenjinx.so. The JNI companion library is
     * loaded first because libkenjinx.so imports its Android callbacks from
     * libkenjinxjni.so.
     */
    val core: KenjinxCore by lazy {
        System.loadLibrary("kenjinxjni")
        Native.load(
            "kenjinx",
            KenjinxCore::class.java,
            Collections.singletonMap(Library.OPTION_ALLOW_OBJECTS, true)
        )
    }
}
