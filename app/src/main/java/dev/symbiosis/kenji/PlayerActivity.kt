package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.sun.jna.JNIEnv
import java.io.File
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import org.kenjinx.android.Kenji
import org.kenjinx.android.KenjinxCore
import org.kenjinx.android.KenjinxNative
import org.kenjinx.android.MainActivity as OfficialMainActivity
import org.kenjinx.android.NativeHelpers

/**
 * The real emulator process. The launcher never loads the native core; this
 * process owns the complete core lifecycle and can therefore be restarted
 * independently if a native game crash occurs.
 */
class PlayerActivity : Activity() {
    companion object {
        private val javaReady = AtomicBoolean(false)

        fun intent(context: Context, path: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra("path", path)
                .putExtra("title", title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var loop: Thread? = null
    private var inputPump: Thread? = null
    private var statsPump: Thread? = null
    private var motion: MotionSensorBridge? = null
    private var started = false
    @Volatile private var playing = false
    @Volatile private var rendererReady = false
    @Volatile private var surfaceReady = false
    private val shuttingDown = AtomicBoolean(false)
    private var nativeWindowHandle = NativePtr.NONE
    private var pendingNativeWindow = NativePtr.NONE
    private var gameSurface: Surface? = null
    private var textureObject: SurfaceTexture? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var ownsGameSurface = false
    private lateinit var status: TextView
    private var touchPad: TouchPad? = null
    private var keyboardDialog: android.app.AlertDialog? = null

    private var displayTitle = "игра"
    @Volatile private var dataSummary = "данные: проверка"
    @Volatile private var driverInfo = "драйвер: проверка"
    @Volatile private var firmwareInfo = "прошивка: проверка"
    @Volatile private var progressText = "подготовка графики"
    @Volatile private var progressValue = -1f
    @Volatile private var fps = Double.NaN
    @Volatile private var frameTime = Double.NaN
    @Volatile private var fifo = Double.NaN
    @Volatile private var deviceInitError = ""
    @Volatile private var lastTextureWindow = -1L
    @Volatile private var lastSurfaceWindow = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        displayTitle = intent.getStringExtra("title").orEmpty().ifBlank { "игра" }
        val path = intent.getStringExtra("path").orEmpty()

        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // TextureView composites under HUD/touch overlay. SurfaceView punches
        // a separate window and on this Mali phone the game never shows
        // through those overlays. Official Kenji has no overlay, so SurfaceView
        // works there. Pointers must still accept PAC-tagged (negative) Longs.
        val textureView = TextureView(this).apply {
            isOpaque = true
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    bindTexture(texture, width, height)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
                    bindTexture(texture, width, height)
                }
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    onGameSurfaceDestroyed()
                    return true
                }
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
            }
        }
        root.addView(
            textureView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        val pad = TouchPad(this) { showMenu() }.apply {
            opacity = SettingsStore(this@PlayerActivity).int("overlayOpacity", 70)
            controlsVisible = SettingsStore(this@PlayerActivity)
                .bool("showOverlay", true)
        }
        touchPad = pad
        root.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(24, 42, 24, 16)
            setBackgroundColor(Color.argb(150, 0, 0, 0))
            isClickable = false
            isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            text = "Kenji Space\n$displayTitle\nподготовка…"
        }
        // The HUD is informative, not an input surface. It is placed above
        // the game but remains non-clickable so the overlay underneath still
        // receives controls.
        root.addView(status)
        setContentView(root)

        if (path.isBlank()) {
            fail("нет пути к игре")
            return
        }
        pfd = openRom(path)
        if (pfd == null) {
            fail("не открылся файл игры")
            return
        }
        updateHud()
    }

    private fun bindTexture(texture: SurfaceTexture, width: Int, height: Int) {
        val w = width.coerceAtLeast(128)
        val h = height.coerceAtLeast(128)
        texture.setDefaultBufferSize(w, h)
        if (ownsGameSurface) runCatching { gameSurface?.release() }
        val created = Surface(texture)
        ownsGameSurface = true
        textureObject = texture
        gameSurface = created
        surfaceWidth = w
        surfaceHeight = h
        surfaceReady = true
        releasePendingNativeWindow()
        // Producer is attached only after this callback returns to the UI queue.
        status.post {
            if (gameSurface !== created || !surfaceReady || shuttingDown.get()) return@post
            pendingNativeWindow = nativeWindowFromTexture(texture) ?: nativeWindowFromSurface(created)
            if (started && rendererReady) {
                rebindSurface(created, w, h)
            } else if (!started && pfd != null) {
                startBoot(created, w, h)
            }
        }
    }

    private fun onGameSurfaceDestroyed() {
        surfaceReady = false
        releasePendingNativeWindow()
        if (rendererReady && !shuttingDown.get()) {
            runCatching { Kenji.core.graphicsSetPresentEnabled(false) }
            runCatching { Kenji.core.deviceWaitForGpuDone(100) }
            runCatching { Kenji.core.detachWindow() }
            releaseNativeWindow()
        }
        if (ownsGameSurface) runCatching { gameSurface?.release() }
        ownsGameSurface = false
        gameSurface = null
        textureObject = null
        surfaceWidth = 0
        surfaceHeight = 0
    }

    private fun startBoot(surface: Surface, width: Int, height: Int) {
        val fd = pfd?.fd
        if (fd == null || fd < 0) {
            fail("нет дескриптора игры")
            return
        }
        val path = intent.getStringExtra("path").orEmpty()
        started = true
        loop = Thread({
            try {
                boot(surface, fd, path, width.coerceAtLeast(128), height.coerceAtLeast(128))
                if (!shuttingDown.get() && !playing) {
                    runOnUiThread { fail("эмуляция остановилась до первого кадра") }
                }
            } catch (t: Throwable) {
                runCatching { Kenji.core.deviceSignalEmulationClose() }
                runOnUiThread {
                    fail("ядро: ${t.message ?: t.javaClass.simpleName}")
                }
            }
        }, "kenji-render-loop").also { it.start() }
    }

    private fun boot(surface: Surface, fd: Int, path: String, width: Int, height: Int) {
        val home = DataRoot.kenjiHome()
        // Show the real paths/counts before validation too, so a missing key
        // or firmware never looks like an unexplained black screen.
        showDataStatus(home)
        ensureFirmwareAndKeys(home)
        showDataStatus(home)

        val core = Kenji.core
        if (!javaReady.get()) ensureJava(core, home)

        // 1.0.12 reached «загрузка игры» with this trio on the worker:
        // javaInitialize (this thread) → ReinitEmulation → ReloadFilesystem.
        // Skipping Reinit left deviceInitialize parked on the UI thread.
        runCatching { core.deviceReinitEmulation() }
        runCatching { core.deviceReloadFilesystem() }
        installPendingFirmware(core)
        core.loggingSetEnabled(3, true)
        core.loggingSetEnabled(2, true)

        driverInfo = runCatching { "драйвер: ${NativeHelpers.instance.getVulkanDriverInfo()}" }
            .getOrElse { "драйвер: не удалось прочитать (${it.message ?: "ошибка"})" }
        progressText = "инициализация Vulkan"
        updateHud()

        val settings = SettingsStore(this)
        // graphics + renderer stay on this worker: 1.0.16/17 got past them
        // here. 1.0.18 moved the whole block onto UI and Android ANR-killed
        // the player before the HUD passed the first lines.
        val scale = when (settings.int("resolution", 2).coerceIn(0, 3)) {
            0 -> 0.5f
            1 -> 0.75f
            3 -> 2f
            else -> 1f
        }
        val backend = settings.int("backendThreading", 1).coerceIn(0, 2)
        if (!core.graphicsInitialize(
                scale,
                0f,
                true,
                true,
                false,
                settings.bool("enableMacroHLE", true),
                settings.bool("enableShaderCache", true),
                settings.bool("enableTextureRecompression", false),
                backend
            )
        ) {
            throw IllegalStateException("graphicsInitialize отказал")
        }

        step("ожидание Android Surface")
        val window = if (NativePtr.isSet(pendingNativeWindow)) {
            pendingNativeWindow.also { pendingNativeWindow = NativePtr.NONE }
        } else {
            obtainNativeWindow(surface, 3_000L)
        }
        if (!NativePtr.isSet(window)) {
            throw IllegalStateException(
                "Android Surface не создал ANativeWindow: surfaceValid=${surface.isValid}, " +
                    "fromSurface=${NativePtr.hex(lastSurfaceWindow)}"
            )
        }
        nativeWindowHandle = window
        KenjinxNative.nativeSurface = window
        KenjinxNative.nativeWindow = window
        step("окно ${NativePtr.hex(window)}")
        core.deviceSetWindowHandle(window)
        core.deviceSetSurfaceRotation(rotationDegrees())

        val extensions = arrayOf("VK_KHR_surface", "VK_KHR_android_surface")
        step("создание Vulkan renderer")
        if (!core.graphicsInitializeRenderer(extensions, extensions.size, 0L)) {
            throw IllegalStateException("graphicsInitializeRenderer отказал")
        }
        rendererReady = true
        core.graphicsRendererSetSize(width, height)

        // 1.0.12 had input up before deviceInitialize and reached Load.
        // Doing it before the renderer crashed TextureView (1.0.20).
        // After the renderer, before deviceInitialize, is the remaining slot.
        step("inputInitialize")
        core.inputInitialize(width, height)

        // 1.0.12 did not install the UI handler before this call.
        step("инициализация устройства")
        val deviceWatch = startNamedWatch("инициализация устройства")
        val deviceOk = try {
            deviceInitializeOnUi(core, settings)
        } finally {
            deviceWatch.set(false)
        }
        if (!deviceOk) {
            throw IllegalStateException(
                "deviceInitialize отказал: ${deviceInitError.ifBlank { "нет контекста эмуляции" }}"
            )
        }
        firmwareInfo = firmwareVersion(core, home)
        updateHud()

        core.uiHandlerSetup()
        installCallbacks()

        val type = romType(path)
        progressText = "загрузка игры…"
        updateHud()
        val loadWatch = startLoadWatch()
        val loaded = try {
            core.deviceLoadDescriptor(fd, type, -1)
        } finally {
            loadWatch.set(false)
        }
        if (!loaded) {
            throw IllegalStateException("ядро не открыло игру: проверьте ключи и прошивку")
        }

        // Read metadata only in :player, after javaInitialize. This is the
        // same real deviceGetGameInfo call as the official UI and uses the
        // ABI-correct GameInfo structure.
        runCatching {
            GameInfoReader.read(this, path)?.let { info ->
                if (info.title.isNotBlank()) displayTitle = info.title
                if (info.developer.isNotBlank()) displayTitle += " · ${info.developer}"
            }
        }

        val port = KenjiInput.connect(core)
        motion = MotionSensorBridge(this, core).apply {
            setControllerId(port)
            register()
        }
        core.inputSetClientSize(width, height)
        playing = true
        touchPad?.inputEnabled = true
        firmwareInfo = firmwareVersion(core, home)
        progressText = if (port >= 0) "шейдеры инициализируются" else "геймпад не подключился; touch доступен"
        startInputPump(core)
        startStatsPump(core)
        core.graphicsSetPresentEnabled(true)
        updateHud()

        // The official renderer initializes and loads the shader cache from
        // inside this loop. Progress callbacks are displayed by the HUD.
        core.graphicsRendererRunLoop()
        playing = false
        stopPumps()
        if (!shuttingDown.get()) {
            runOnUiThread { fail("render loop завершился без активной эмуляции") }
        }
    }

    private fun ensureFirmwareAndKeys(home: File) {
        DataRoot.ensureKenjiLayout(home)
        if (!FirmwareBridge.kenjiReady(home)) {
            val bridge = FirmwareBridge.auto(home, allowCopy = true)
            if (!FirmwareBridge.kenjiReady(home)) {
                throw IllegalStateException(
                    "прошивка не готова: ${bridge.optString("message", "нет NCA")}. " +
                        "Нужно минимум 10 NCA в ${FirmwareBridge.kenjiRegistered(home).absolutePath}"
                )
            }
        }
        DataRoot.seedKeysIntoKenji(home)
        val keys = DataRoot.kenjiKeysFile(home)
        if (!keys.isFile || keys.length() <= 100L) {
            throw IllegalStateException(
                "нет prod.keys: положите его в ${keys.absolutePath} и запустите снова"
            )
        }
        File(home, "Logs").mkdirs()
        // .NET also tries ~/.config/Ryujinx/Logs; create the writable
        // Android equivalents so the warning is not the only log we have.
        runCatching { File(filesDir, ".config/Ryujinx/Logs").mkdirs() }
        runCatching { File(getExternalFilesDir(null), ".config/Ryujinx/Logs").mkdirs() }
    }

    private fun showDataStatus(home: File) {
        val keys = DataRoot.kenjiKeysFile(home)
        val nca = FirmwareBridge.kenjiNcaCount(home)
        dataSummary = "данные: ${home.absolutePath}\n" +
            "ключи: ${if (keys.isFile) "prod.keys ${humanBytes(keys.length())}" else "НЕТ"}\n" +
            "прошивка: $nca NCA"
        firmwareInfo = "прошивка: $nca NCA · версия после deviceInitialize"
        updateHud()
    }

    private fun ensureJava(core: KenjinxCore, home: File) {
        if (javaReady.get()) return
        if (!OfficialMainActivity.attachVm()) {
            throw IllegalStateException("не удалось подключить JavaVM к официальному JNI")
        }

        val junk = FirmwareBridge.quarantineJunk(home)
        val validHeaders = FirmwareBridge.countValidHeaders(home)
        val bad = if (validHeaders >= 10) FirmwareBridge.quarantineInvalid(home) else 0
        val nca = FirmwareBridge.kenjiNcaCount(home)
        progressText = "javaInitialize · $nca NCA" +
            if (junk + bad > 0) " · карантин ${junk + bad}" else ""
        updateHud()

        // Init WITH firmware only when we decrypted at least 10 headers.
        // Otherwise stash: one bad NCA still poisons VirtualFileSystem.
        val registered = FirmwareBridge.kenjiRegistered(home)
        val stashed = File(registered.parentFile, "registered.stash")
        // 1.0.12 always hid firmware during javaInitialize. Init-with-firmware
        // is still unproven on this 234-NCA tree and can stall deviceInitialize.
        val stash = FirmwareBridge.kenjiReady(home) && registered.isDirectory
        if (stash) {
            if (stashed.exists()) stashed.deleteRecursively()
            if (!registered.renameTo(stashed)) {
                throw IllegalStateException(
                    "не удалось временно убрать прошивку перед javaInitialize"
                )
            }
        }

        val initialized = try {
            core.javaInitialize(home.absolutePath, JNIEnv.CURRENT)
        } finally {
            if (stashed.isDirectory) {
                if (registered.exists()) registered.deleteRecursively()
                if (!stashed.renameTo(registered)) {
                    throw IllegalStateException(
                        "не удалось вернуть прошивку после javaInitialize"
                    )
                }
            }
        }
        if (!initialized) {
            val log = lastKenjiLog(home)
            throw IllegalStateException(
                "javaInitialize отказал: ключи=${DataRoot.kenjiKeysFile(home).length()}Б " +
                    "NCA=${FirmwareBridge.kenjiNcaCount(home)}" +
                    if (log.isNotBlank()) " · $log" else ""
            )
        }
        javaReady.set(true)
        GameInfoReader.coreIsUp()
    }

    private fun javaInitializeOnUi(core: KenjinxCore, data: String): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        val error = AtomicReference<String?>(null)
        runOnUiThread {
            try {
                ok.set(core.javaInitialize(data, JNIEnv.CURRENT))
            } catch (t: Throwable) {
                error.set(t.message ?: t.javaClass.simpleName)
            } finally {
                done.countDown()
            }
        }
        if (!done.await(60, TimeUnit.SECONDS)) {
            throw IllegalStateException("javaInitialize не вернулся за 60с")
        }
        error.get()?.let { throw IllegalStateException("javaInitialize: $it") }
        return ok.get()
    }

    private fun initializeDevice(core: KenjinxCore, settings: SettingsStore): Boolean {
        deviceInitError = ""
        return try {
            core.deviceInitialize(
                settings.int("memoryManagerMode", 2).coerceIn(0, 2),
                settings.bool("useNce", false),
                settings.int("memoryConfiguration", 0).coerceIn(0, 2),
                languageOrdinal(),
                regionOrdinal(),
                settings.int("vSyncMode", 0).coerceIn(0, 2),
                settings.bool("enableDocked", false),
                settings.bool("enablePptc", true),
                settings.bool("enableLowPowerPptc", false),
                settings.bool("enableJitCacheEviction", false),
                false,
                settings.bool("enableFsIntegrityChecks", false),
                0,
                TimeZone.getDefault().id,
                settings.bool("ignoreMissingServices", false)
            )
        } catch (t: Throwable) {
            deviceInitError = t.message ?: t.javaClass.simpleName
            false
        }
    }

    private fun deviceInitializeOnUi(core: KenjinxCore, settings: SettingsStore): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        runOnUiThread {
            try {
                ok.set(initializeDevice(core, settings))
            } catch (t: Throwable) {
                deviceInitError = t.message ?: t.javaClass.simpleName
            } finally {
                done.countDown()
            }
        }
        if (!done.await(180, TimeUnit.SECONDS)) {
            deviceInitError = "не вернулся за 180с"
            return false
        }
        return ok.get()
    }

    private fun paintHud() {
        if (!::status.isInitialized || isFinishing) return
        val fpsText = if (fps.isNaN()) "FPS: —" else "FPS: %.1f".format(Locale.US, fps)
        val frameText = if (frameTime.isNaN()) "" else " · frame %.2f ms".format(Locale.US, frameTime)
        val fifoText = if (fifo.isNaN()) "" else " · FIFO %.0f%%".format(Locale.US, fifo)
        val progress = if (progressValue in 0f..1f) {
            "$progressText · ${(progressValue * 100f).toInt()}%"
        } else {
            progressText
        }
        status.text = buildString {
            append("Kenji Space ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · TextureView\n")
            append(displayTitle)
            append('\n')
            append(dataSummary)
            append('\n')
            append(driverInfo)
            append('\n')
            append(firmwareInfo)
            append('\n')
            append(progress)
            if (playing) {
                append('\n')
                append(fpsText)
                append(frameText)
                append(fifoText)
            }
        }
    }

    private fun installCallbacks() {
        KenjinxNative.progressListener = { text, value ->
            if (text.isNotBlank()) progressText = text
            progressValue = value
            updateHud()
        }
        KenjinxNative.uiMessageListener = { message ->
            if (message.isNotBlank()) {
                progressText = message
                updateHud()
            }
        }
        KenjinxNative.keyboardListener = { title, message, initial, type, min, max ->
            // Types 1/2 are software keyboards. Everything else is a
            // blocking message/confirm: the loader waits on this thread
            // until uiHandlerSetResponse. Swallowing it freezes on
            // «загрузка игры» with no FPS and no crash.
            // Dialogs need a pumping looper. During deviceInitialize / load
            // we are inside a native call on the UI thread — posting a
            // dialog deadlocks. Auto-answer until the game is running.
            if ((type == 1 || type == 2) && playing) {
                runOnUiThread {
                    if (shuttingDown.get() || isFinishing) {
                        runCatching { Kenji.core.uiHandlerSetResponse(false, "") }
                        return@runOnUiThread
                    }
                    if (keyboardDialog?.isShowing == true) return@runOnUiThread

                    val input = EditText(this).apply {
                        setText(initial)
                        setSelection(text.length)
                        inputType = if (type == 2) {
                            InputType.TYPE_CLASS_TEXT
                        } else {
                            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        }
                    }
                    val label = if (min > 0 || max > 0) "$message\n($min…$max символов)" else message
                    val dialog = android.app.AlertDialog.Builder(this)
                        .setTitle(title.ifBlank { "Ввод" })
                        .setMessage(label)
                        .setView(input)
                        .setNegativeButton("Отмена") { _, _ ->
                            runCatching { Kenji.core.uiHandlerSetResponse(false, "") }
                        }
                        .setPositiveButton("ОК") { _, _ ->
                            val value = input.text?.toString().orEmpty()
                            if ((min <= 0 || value.length >= min) && (max <= 0 || value.length <= max)) {
                                runCatching { Kenji.core.uiHandlerSetResponse(true, value) }
                            } else {
                                runCatching { Kenji.core.uiHandlerSetResponse(false, "") }
                            }
                        }
                        .create()
                    dialog.setOnCancelListener {
                        runCatching { Kenji.core.uiHandlerSetResponse(false, "") }
                    }
                    keyboardDialog = dialog
                    dialog.show()
                }
            } else {
                progressText = listOf(title, message).filter { it.isNotBlank() }.joinToString(" · ")
                    .ifBlank { "запрос ядра type=$type" }
                updateHud()
                runCatching { Kenji.core.uiHandlerSetResponse(true, initial) }
            }
        }
    }

    private fun startInputPump(core: KenjinxCore) {
        inputPump?.interrupt()
        inputPump = Thread({
            while (!shuttingDown.get() && playing) {
                runCatching { core.inputUpdate() }
                LockSupport.parkNanos(1_000_000L)
            }
        }, "kenji-input-pump").also { it.start() }
    }

    private fun startStatsPump(core: KenjinxCore) {
        statsPump?.interrupt()
        statsPump = Thread({
            while (!shuttingDown.get() && playing) {
                runCatching { fps = core.deviceGetGameFrameRate() }
                runCatching { frameTime = core.deviceGetGameFrameTime() }
                runCatching { fifo = core.deviceGetGameFifo() }
                updateHud()
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    break
                }
            }
        }, "kenji-stats").also { it.start() }
    }

    private fun stopPumps() {
        inputPump?.interrupt()
        statsPump?.interrupt()
        inputPump = null
        statsPump = null
    }

    private fun nativeWindowFromSurface(surface: Surface): Long {
        val safe = runCatching { NativeHelpers.instance.getNativeWindowSafe(surface) }
            .getOrDefault(NativePtr.NONE)
        if (NativePtr.isSet(safe)) {
            lastSurfaceWindow = safe
            return safe
        }
        val official = runCatching { NativeHelpers.instance.getNativeWindow(surface) }
            .getOrDefault(NativePtr.NONE)
        lastSurfaceWindow = official
        return official
    }

    private fun nativeWindowFromTexture(texture: SurfaceTexture?): Long? {
        if (texture == null) return null
        val handle = runCatching {
            NativeHelpers.instance.getNativeWindowFromTexture(texture)
        }.getOrDefault(NativePtr.NONE)
        lastTextureWindow = handle
        return handle.takeIf { NativePtr.isSet(it) }
    }

    private fun obtainNativeWindow(surface: Surface, timeoutMs: Long): Long {
        val attempts = (timeoutMs / 50L).toInt().coerceAtLeast(1)
        var last = NativePtr.NONE
        repeat(attempts) {
            if (surface.isValid) {
                last = nativeWindowFromTexture(textureObject) ?: nativeWindowFromSurface(surface)
                if (NativePtr.isSet(last)) return last
            }
            try {
                Thread.sleep(50L)
            } catch (_: InterruptedException) {
                return NativePtr.NONE
            }
        }
        return last
    }

    private fun rebindSurface(surface: Surface, width: Int, height: Int) {
        if (!rendererReady || shuttingDown.get()) return
        val newWindow = if (NativePtr.isSet(pendingNativeWindow)) {
            pendingNativeWindow.also { pendingNativeWindow = NativePtr.NONE }
        } else {
            obtainNativeWindow(surface, 300L)
        }
        if (!NativePtr.isSet(newWindow)) {
            progressText = "Android Surface ещё не готов (${NativePtr.hex(lastSurfaceWindow)})"
            updateHud()
            return
        }

        if (NativePtr.isSet(nativeWindowHandle) && nativeWindowHandle != newWindow) {
            runCatching { Kenji.core.graphicsSetPresentEnabled(false) }
            runCatching { Kenji.core.deviceWaitForGpuDone(100) }
            runCatching { Kenji.core.detachWindow() }
            releaseNativeWindow()
        }
        nativeWindowHandle = newWindow
        KenjinxNative.nativeSurface = newWindow
        KenjinxNative.nativeWindow = newWindow
        runCatching { Kenji.core.deviceSetWindowHandle(newWindow) }
        runCatching { Kenji.core.deviceSetSurfaceRotation(rotationDegrees()) }
        runCatching { Kenji.core.deviceRecreateSwapchain() }
        runCatching { Kenji.core.graphicsRendererSetSize(width.coerceAtLeast(128), height.coerceAtLeast(128)) }
        if (playing) runCatching { Kenji.core.inputSetClientSize(width, height) }
        runCatching { Kenji.core.graphicsSetPresentEnabled(true) }
    }

    private fun releasePendingNativeWindow() {
        val pending = pendingNativeWindow
        pendingNativeWindow = NativePtr.NONE
        if (NativePtr.isSet(pending)) runCatching { NativeHelpers.instance.releaseNativeWindowSafe(pending) }
    }

    private fun releaseNativeWindow() {
        releasePendingNativeWindow()
        val window = nativeWindowHandle
        nativeWindowHandle = NativePtr.NONE
        KenjinxNative.nativeSurface = NativePtr.NONE
        KenjinxNative.nativeWindow = NativePtr.NONE
        if (NativePtr.isSet(window)) runCatching { NativeHelpers.instance.releaseNativeWindowSafe(window) }
    }

    private fun rotationDegrees(): Int = when (display?.rotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun firmwareVersion(core: KenjinxCore, home: File): String {
        val version = runCatching { core.deviceGetInstalledFirmwareVersion().trim() }
            .getOrDefault("")
        return if (version.isNotBlank() && version != "0.0") {
            "прошивка: $version · ${FirmwareBridge.kenjiNcaCount(home)} NCA"
        } else {
            "прошивка: ${FirmwareBridge.kenjiNcaCount(home)} NCA · версия ядром не сообщена"
        }
    }

    private fun languageOrdinal(): Int = when (Locale.getDefault().language.lowercase(Locale.ROOT)) {
        "ja" -> 0
        "en" -> 1
        "fr" -> 2
        "de" -> 3
        "it" -> 4
        "es" -> 5
        "zh" -> 6
        "ko" -> 7
        "nl" -> 8
        "pt" -> 9
        "ru" -> 10
        else -> 1
    }

    private fun regionOrdinal(): Int = when (Locale.getDefault().country.uppercase(Locale.ROOT)) {
        "JP" -> 0
        "US", "CA", "MX" -> 1
        "AU", "NZ" -> 3
        "CN" -> 4
        "KR" -> 5
        "TW" -> 6
        else -> 2
    }

    private fun updateHud() {
        if (!::status.isInitialized) return
        runOnUiThread { paintHud() }
    }

    private fun showMenu() {
        val pad = touchPad ?: return
        val settings = SettingsStore(this)
        val items = arrayOf(
            if (pad.controlsVisible) "Скрыть управление" else "Показать управление",
            "Прозрачность кнопок: ${settings.int("overlayOpacity", 70)}%",
            "Выйти из игры"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Kenji")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val visible = !pad.controlsVisible
                        pad.controlsVisible = visible
                        settings.setBool("showOverlay", visible)
                    }
                    1 -> {
                        val next = when (settings.int("overlayOpacity", 70)) {
                            in 0..40 -> 70
                            in 41..79 -> 100
                            else -> 35
                        }
                        settings.setInt("overlayOpacity", next)
                        pad.opacity = next
                    }
                    2 -> leave()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playing) {
            val button = KenjiInput.fromKeyCode(event.keyCode)
            if (button != KenjiInput.NONE) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> KenjiInput.press(Kenji.core, button)
                    KeyEvent.ACTION_UP -> KenjiInput.release(Kenji.core, button)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (playing && KenjiInput.motion(Kenji.core, event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        touchPad?.releaseAll()
        motion?.unregister()
        if (playing) {
            runCatching { Kenji.core.audioSetPaused(true) }
            runCatching { Kenji.core.graphicsSetPresentEnabled(false) }
        }
    }

    override fun onResume() {
        super.onResume()
        if (playing) {
            motion?.register()
            runCatching { Kenji.core.audioSetPaused(false) }
            if (surfaceReady) {
                gameSurface?.let { surface ->
                    rebindSurface(surface, surfaceWidth, surfaceHeight)
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        leave()
    }

    override fun onDestroy() {
        touchPad?.inputEnabled = false
        shutdownCore()
        gameSurface = null
        runCatching { pfd?.close() }
        super.onDestroy()
        // VirtualFileSystem.CreateInstance can run once per process.
        // Leaving :player alive after a failed javaInitialize makes the
        // next launch return false immediately.
        Process.killProcess(Process.myPid())
    }

    private fun leave() {
        if (shuttingDown.compareAndSet(false, true)) {
            runCatching { Kenji.core.deviceSignalEmulationClose() }
        }
        finish()
    }

    private fun shutdownCore() {
        if (!shuttingDown.getAndSet(true)) {
            runCatching { Kenji.core.deviceSignalEmulationClose() }
        }
        playing = false
        touchPad?.inputEnabled = false
        motion?.close()
        motion = null
        stopPumps()
        KenjinxNative.progressListener = null
        KenjinxNative.uiMessageListener = null
        KenjinxNative.keyboardListener = null
        KenjinxNative.frameListener = null
        keyboardDialog?.dismiss()
        keyboardDialog = null

        loop?.let { thread ->
            if (thread !== Thread.currentThread()) {
                runCatching { thread.join(4_000) }
            }
        }
        runCatching { Kenji.core.deviceCloseEmulation() }
        runCatching { Kenji.core.deviceSetWindowHandle(0L) }
        releaseNativeWindow()
        KenjiInput.reset()
        rendererReady = false
    }

    private fun fail(message: String) {
        if (!::status.isInitialized) return
        playing = false
        progressText = message
        updateHud()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        touchPad?.inputEnabled = false
        if (!isFinishing) {
            status.postDelayed({
                finish()
                Process.killProcess(Process.myPid())
            }, 12_000)
        }
    }

    private fun lastKenjiLog(home: File): String {
        val candidates = listOf(
            File(home, "Logs"),
            File(filesDir, ".config/Ryujinx/Logs"),
            File(getExternalFilesDir(null), ".config/Ryujinx/Logs")
        )
        val file = candidates.flatMap { dir ->
            dir.listFiles()?.filter { it.isFile }.orEmpty().toList()
        }.maxByOrNull { it.lastModified() } ?: return ""
        val text = runCatching { file.readText() }.getOrDefault("")
        val useful = text.lineSequence()
            .filter { line ->
                line.contains("|E|") ||
                    line.contains("Exception") ||
                    line.contains("Initializing") ||
                    line.contains("base path", ignoreCase = true) ||
                    line.contains("Firmware", ignoreCase = true) ||
                    line.contains("failed", ignoreCase = true) ||
                    line.contains("|W|")
            }
            .toList()
            .takeLast(6)
            .joinToString(" · ")
            .replace('\n', ' ')
            .trim()
        return useful.ifBlank {
            text.takeLast(240).replace('\n', ' ').trim()
        }
    }

    private fun humanBytes(bytes: Long): String = when {
        bytes < 1024L * 1024L -> "${bytes / 1024L} КБ"
        bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} МБ"
        else -> "%.1f ГБ".format(Locale.US, bytes / (1024.0 * 1024.0 * 1024.0))
    }

    private fun installPendingFirmware(core: KenjinxCore) {
        val pendingPath = SettingsStore(this).string("pendingFirmware")
        if (pendingPath.isBlank()) return
        val file = File(pendingPath)
        if (!file.isFile || file.length() < 1_000L) {
            throw IllegalStateException("отложенная прошивка повреждена: $pendingPath")
        }
        val isXci = file.extension.equals("xci", true)
        val verified = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            runCatching { core.deviceVerifyFirmware(descriptor.fd, isXci).trim() }.getOrDefault("")
        }
        if (verified.isBlank() || verified == "0.0") {
            throw IllegalStateException("прошивка не прошла проверку: ${file.name}")
        }
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            core.deviceInstallFirmware(descriptor.fd, isXci)
        }
        SettingsStore(this).setString("pendingFirmware", "")
    }


    private fun romType(path: String): Int {
        val name = if (path.startsWith("/")) {
            path.substringAfterLast('/')
        } else {
            runCatching { Uri.parse(path).lastPathSegment }.getOrNull() ?: path
        }
        return when (name.substringAfterLast('.', "").lowercase().substringBefore('?')) {
            "xci" -> 2
            "nro" -> 3
            "nsp" -> 1
            else -> throw IllegalArgumentException("сжатый или неподдерживаемый формат: $name")
        }
    }

    private fun step(message: String) {
        progressText = message
        updateHud()
        try {
            Thread.sleep(80)
        } catch (_: InterruptedException) {
            // keep going
        }
    }

    private fun startLoadWatch(): AtomicBoolean = startNamedWatch("загрузка игры")

    private fun startNamedWatch(label: String): AtomicBoolean {
        val running = AtomicBoolean(true)
        Thread({
            var seconds = 0
            while (running.get() && !shuttingDown.get()) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                seconds++
                if (running.get()) {
                    progressText = "$label… ${seconds}с"
                    updateHud()
                }
            }
        }, "kenji-watch").start()
        return running
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        // Official FileStream opens the descriptor ReadWrite. A read-only
        // fd makes the .NET host block inside deviceLoadDescriptor.
        val file = if (path.startsWith("/")) File(path) else resolveContentFile(Uri.parse(path))
        if (file != null && file.isFile) {
            return@runCatching runCatching {
                ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE)
            }.getOrNull() ?: ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        }
        val uri = Uri.parse(path)
        contentResolver.openFileDescriptor(uri, "rw")
            ?: contentResolver.openFileDescriptor(uri, "r")
    }.getOrNull()

    /**
     * SAF gives us content://. Official Kenji opens a real path ReadWrite.
     * Recover /storage/emulated/0/... from the usual ExternalStorage provider.
     */
    private fun resolveContentFile(uri: Uri): File? {
        val decoded = Uri.decode(uri.toString())
        val marker = "primary:"
        val idx = decoded.indexOf(marker)
        if (idx >= 0) {
            val rel = decoded.substring(idx + marker.length)
                .substringBefore('?')
                .substringBefore('#')
            val file = File(Environment.getExternalStorageDirectory(), rel)
            if (file.isFile) return file
        }
        return null
    }
}
