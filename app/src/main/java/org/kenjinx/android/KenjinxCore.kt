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
    fun graphicsRendererSetVsync(vsyncMode: Int)
    fun deviceLoadDescriptor(fileDescriptor: Int, gameType: Int, updateDescriptor: Int): Boolean
    fun inputInitialize(width: Int, height: Int)

    // --- Ввод -------------------------------------------------------------
    // Сигнатуры сверены с KenjinxNativeJna из официального 2.1.0-pr.2:
    //   inputSetButtonPressed (I I)V, inputSetStickAxis (I F F I)V,
    //   inputSetTouchPoint (I I)V,    inputConnectGamepad (I)I.
    // Без этих объявлений JNA просто не находила методов, и играть было
    // нечем: игра запускалась и не реагировала ни на что.
    fun inputConnectGamepad(index: Int): Int
    fun inputSetButtonPressed(button: Int, controllerId: Int)
    fun inputSetButtonReleased(button: Int, controllerId: Int)
    fun inputSetStickAxis(stick: Int, x: Float, y: Float, controllerId: Int)
    fun inputSetTouchPoint(x: Int, y: Int)
    fun inputReleaseTouchPoint()
    fun inputSetClientSize(width: Int, height: Int)
    fun inputUpdate()

    // Звук: официальная оболочка глушит его на паузе. Без этого игра
    // продолжает играть музыку, когда её свернули.
    fun audioSetPaused(paused: Boolean)
    fun audioSetMuted(muted: Boolean)

    // Смена размера поверхности при повороте экрана.
    fun deviceResize(width: Int, height: Int)
    fun deviceSetSurfaceRotation(rotation: Int)
    fun deviceRecreateSwapchain()
    fun deviceSetWindowHandle(handle: Long)

    // Счётчики для наложения FPS.
    fun deviceGetGameFrameRate(): Double
    fun deviceGetGameFrameTime(): Double
    fun deviceGetGameFifo(): Double
    fun deviceCloseEmulation()
    fun deviceSignalEmulationClose()
    fun loggingSetEnabled(logLevel: Int, enabled: Boolean)
    fun deviceInstallFirmware(fileDescriptor: Int, isXci: Boolean)
    fun deviceVerifyFirmware(fileDescriptor: Int, isXci: Boolean): String
    fun deviceGetInstalledFirmwareVersion(): String
    fun deviceReloadFilesystem()
    fun deviceReinitEmulation()

    // Название, издатель, версия и обложка одним вызовом. Сигнатура
    // сверена с KenjinxNativeJna: (I Ljava/lang/String; GameInfo)V.
    fun deviceGetGameInfo(
        fileDescriptor: Int,
        extension: String,
        info: dev.symbiosis.kenji.GameInfoReader.GameInfo
    )
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
