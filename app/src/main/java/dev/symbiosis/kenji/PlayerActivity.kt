package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.sun.jna.JNIEnv
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.kenjinx.android.Kenji
import org.kenjinx.android.KenjinxNative
import org.kenjinx.android.NativeHelpers

/**
 * Real Kenji player: official JNA + official kenjinxjni + packaged libkenjinx.so.
 * Isolated in :player so a native abort does not kill the launcher.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback {

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
    private var started = false
    private var playing = false
    private lateinit var status: TextView
    private var touchPad: TouchPad? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra("path").orEmpty()
        val title = intent.getStringExtra("title").orEmpty().ifBlank { "игра" }
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val surface = SurfaceView(this)
        surface.holder.addCallback(this)
        root.addView(
            surface,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        status = TextView(this).apply {
            text = "Kenji Space\n$title"
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(24, 48, 24, 16)
        }

        // Экранное управление поверх картинки.
        //
        // Раньше здесь была только кнопка «Выйти»: игра запускалась и не
        // отвечала ни на что - ни касание, ни геймпад никуда не уходили.
        // Кнопки рисуются сразу, но ввод начнёт приниматься лишь после
        // inputConnectGamepad, который зовётся уже после старта ядра.
        val pad = TouchPad(this, Kenji.core) { showMenu() }
        pad.opacity = SettingsStore(this).int("overlayOpacity", 70)
        touchPad = pad
        root.addView(
            pad,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        pad.visibility = if (SettingsStore(this).bool("showOverlay", true)) View.VISIBLE else View.GONE

        root.addView(status)
        setContentView(root)
        if (path.isBlank()) return fail("нет пути")
        pfd = openRom(path) ?: return fail("не открылся файл игры")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        val fd = pfd?.fd ?: return fail("нет дескриптора")
        val path = intent.getStringExtra("path").orEmpty()
        val w = holder.surfaceFrame.width().coerceAtLeast(128)
        val h = holder.surfaceFrame.height().coerceAtLeast(128)
        started = true
        loop = Thread({
            val err = runCatching { boot(holder, fd, path, w, h) }.exceptionOrNull()
            if (err != null) {
                runOnUiThread { fail("ядро: ${err.message ?: err.javaClass.simpleName}") }
                return@Thread
            }
            runOnUiThread { leave() }
        }, "kenji-loop").also { it.start() }
    }

    private fun boot(holder: SurfaceHolder, fd: Int, path: String, w: Int, h: Int) {
        val core = Kenji.core
        val home = DataRoot.kenjiHome()
        DataRoot.ensureKenjiLayout(home)
        if (!FirmwareBridge.kenjiReady(home)) FirmwareBridge.auto(home, allowCopy = true)
        DataRoot.seedKeysIntoKenji(home)
        File(home, "Logs").mkdirs()
        if (!DataRoot.kenjiKeysReady(home)) {
            val eden = File(home, "keys/prod.keys")
            throw IllegalStateException(
                "Kenji читает только ${File(home, "system/prod.keys").absolutePath}. " +
                    if (eden.isFile) "ключи лежат в keys/, скопировать в system/ не вышло"
                    else "нет prod.keys"
            )
        }
        val data = home.absolutePath
        val s = SettingsStore(this)

        org.kenjinx.android.MainActivity.attachVm()
        ensureJava(core, home, data)
        runCatching { core.deviceReinitEmulation() }
        runCatching { core.deviceReloadFilesystem() }
        installPendingFirmware(core)
        core.loggingSetEnabled(3, true)
        core.loggingSetEnabled(2, true)

        // Official order (MainViewModel.loadGame):
        // graphicsInitialize → graphicsInitializeRenderer → deviceInitialize(main thread) → load.
        // deviceInitialize returns false if Renderer is still null.
        val scale = when (s.int("resolution", 2)) {
            0 -> 0.5f; 1 -> 0.75f; 3 -> 2f; else -> 1f
        }
        if (!core.graphicsInitialize(
                scale, 0f, true, true, false,
                s.bool("enableMacroHLE", true),
                s.bool("enableShaderCache", true),
                s.bool("enableTextureRecompression", false),
                s.int("backendThreading", 1)
            )
        ) throw IllegalStateException("graphicsInitialize отказал")

        val nw = NativeHelpers.instance.getNativeWindow(holder.surface)
        KenjinxNative.nativeSurface = nw
        KenjinxNative.nativeWindow = nw
        core.inputInitialize(w, h)
        val exts = arrayOf("VK_KHR_surface", "VK_KHR_android_surface")
        if (!core.graphicsInitializeRenderer(exts, exts.size, 0L)) {
            throw IllegalStateException("graphicsInitializeRenderer отказал")
        }
        core.graphicsRendererSetSize(w, h)

        if (!deviceInitializeOnUi(core, s)) {
            throw IllegalStateException("deviceInitialize отказал (нужен главный поток и Vulkan renderer)")
        }

        val type = when (path.substringAfterLast('.', "").lowercase().substringBefore('?')) {
            "xci", "xcz" -> 2
            "nro" -> 3
            else -> 1
        }
        if (!core.deviceLoadDescriptor(fd, type, -1)) {
            throw IllegalStateException("не открыл игру (проверьте ключи и прошивку в папке данных)")
        }
        playing = true

        // Геймпад подключается ПОСЛЕ загрузки игры.
        //
        // inputConnectGamepad возвращает номер порта, и без него любой
        // inputSetButtonPressed уходит в никуда: ядру некуда положить
        // нажатие. Официальная оболочка делает это в тот же момент - в
        // GameController при первом событии, когда controllerId ещё -1.
        val port = KenjiInput.connect(core)
        runCatching { core.inputSetClientSize(w, h) }

        runOnUiThread {
            status.text = if (port >= 0) "Kenji · играет" else "Kenji · играет (геймпад не подключился)"
            // Через три секунды подпись убирается: она нужна на старте,
            // а дальше только мешает смотреть на игру.
            status.postDelayed({ status.text = "" }, 3000)
        }
        core.graphicsRendererRunLoop()
    }

    private fun ensureJava(core: org.kenjinx.android.KenjinxCore, home: File, data: String) {
        if (javaReady.get()) return
        val registered = FirmwareBridge.kenjiRegistered(home)
        val stashed = File(registered.parentFile, "registered.stash")
        if (FirmwareBridge.kenjiReady(home) && registered.isDirectory) {
            if (stashed.exists()) stashed.deleteRecursively()
            registered.renameTo(stashed)
        }
        val inited = try {
            core.javaInitialize(data, JNIEnv.CURRENT)
        } finally {
            if (stashed.isDirectory) {
                if (registered.exists()) registered.deleteRecursively()
                stashed.renameTo(registered)
            }
        }
        if (inited) {
            javaReady.set(true)
            // Ядро поднято - только теперь можно спрашивать у него
            // метаданные. В лаунчере этого делать нельзя, там оно не
            // инициализировано и вызов убивает процесс.
            GameInfoReader.coreIsUp()
            return
        }
        if (javaReady.get()) return
        val log = lastKenjiLog(home)
        throw IllegalStateException(
            "javaInitialize отказал. ключи=${DataRoot.kenjiKeysFile(home).length()}Б " +
                "NCA=${FirmwareBridge.kenjiNcaCount(home)} путь=$data" +
                if (log.isNotBlank()) " · лог: $log" else " · Logs/ пуст"
        )
    }

    private fun deviceInitializeOnUi(core: org.kenjinx.android.KenjinxCore, s: SettingsStore): Boolean {
        val done = CountDownLatch(1)
        val ok = AtomicBoolean(false)
        runOnUiThread {
            try {
                ok.set(
                    core.deviceInitialize(
                        s.int("memoryManagerMode", 2),
                        s.bool("useNce", false),
                        s.int("memoryConfiguration", 0),
                        1, 1, 0,
                        s.bool("enableDocked", false),
                        s.bool("enablePptc", true),
                        s.bool("enableLowPowerPptc", false),
                        s.bool("enableJitCacheEviction", false),
                        false,
                        s.bool("enableFsIntegrityChecks", false),
                        0,
                        java.util.TimeZone.getDefault().id,
                        s.bool("ignoreMissingServices", false)
                    )
                )
            } finally {
                done.countDown()
            }
        }
        if (!done.await(20, TimeUnit.SECONDS)) return false
        return ok.get()
    }

    /**
     * Меню по кнопке «≡».
     *
     * Единственный способ выйти раньше был отдельной кнопкой «Выйти»
     * поверх игры - она занимала угол экрана и нажималась случайно.
     * Здесь же живут вещи, которые нужны прямо во время игры.
     */
    private fun showMenu() {
        val pad = touchPad ?: return
        val s = SettingsStore(this)
        val items = arrayOf(
            if (pad.visibility == View.VISIBLE) "Скрыть управление" else "Показать управление",
            "Прозрачность кнопок: ${s.int("overlayOpacity", 70)}%",
            "Выйти из игры"
        )
        android.app.AlertDialog.Builder(this)
            .setTitle("Kenji")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val show = pad.visibility != View.VISIBLE
                        pad.visibility = if (show) View.VISIBLE else View.GONE
                        if (!show) pad.releaseAll()
                        s.setBool("showOverlay", show)
                    }
                    1 -> {
                        // По кругу, чтобы не заводить отдельный экран.
                        val next = when (s.int("overlayOpacity", 70)) {
                            in 0..40 -> 70
                            in 41..79 -> 100
                            else -> 35
                        }
                        s.setInt("overlayOpacity", next)
                        pad.opacity = next
                    }
                    2 -> leave()
                }
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    /** Физический геймпад: кнопки. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (playing) {
            val code = KenjiInput.fromKeyCode(event.keyCode)
            if (code != KenjiInput.NONE) {
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> KenjiInput.press(Kenji.core, code)
                    KeyEvent.ACTION_UP -> KenjiInput.release(Kenji.core, code)
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /** Физический геймпад: стики, курки, крестовина осями. */
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (playing && KenjiInput.motion(Kenji.core, event)) return true
        return super.dispatchGenericMotionEvent(event)
    }

    override fun onPause() {
        super.onPause()
        // Свернули игру - отпустить всё, иначе кнопка останется зажатой
        // и персонаж будет бежать сам.
        touchPad?.releaseAll()
        if (playing) runCatching { Kenji.core.audioSetPaused(true) }
    }

    override fun onResume() {
        super.onResume()
        if (playing) runCatching { Kenji.core.audioSetPaused(false) }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (!playing) return
        // Поворот экрана: без этого картинка остаётся в старом размере
        // и растягивается.
        runCatching { Kenji.core.graphicsRendererSetSize(width, height) }
        runCatching { Kenji.core.inputSetClientSize(width, height) }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        if (playing) {
            runCatching { Kenji.core.deviceSignalEmulationClose() }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { leave() }

    override fun onDestroy() {
        if (playing) {
            runCatching { Kenji.core.deviceSignalEmulationClose() }
        }
        loop?.join(1500)
        runCatching { pfd?.close() }
        super.onDestroy()
    }

    private fun leave() {
        runCatching { Kenji.core.deviceSignalEmulationClose() }
        finish()
    }

    private fun lastKenjiLog(home: File): String {
        val dir = File(home, "Logs")
        val f = dir.listFiles()?.filter { it.isFile }?.maxByOrNull { it.lastModified() }
            ?: return ""
        return runCatching { f.readText().takeLast(500) }.getOrDefault("")
            .replace('\n', ' ').trim()
    }

    private fun fail(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        status.postDelayed({ finish() }, 8000)
    }

    private fun installPendingFirmware(core: org.kenjinx.android.KenjinxCore) {
        val pending = SettingsStore(this).string("pendingFirmware")
        if (pending.isBlank()) return
        val file = File(pending)
        if (!file.isFile || file.length() < 1000) return
        val isXci = file.extension.equals("xci", true)
        val p = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        try {
            core.deviceInstallFirmware(p.fd, isXci)
            SettingsStore(this).setString("pendingFirmware", "")
        } finally {
            runCatching { p.close() }
        }
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) {
            ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        } else {
            contentResolver.openFileDescriptor(Uri.parse(path), "r")
        }
    }.getOrNull()
}
