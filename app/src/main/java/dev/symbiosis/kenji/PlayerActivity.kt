package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import com.sun.jna.JNIEnv
import java.io.File
import org.kenjinx.android.Kenji
import org.kenjinx.android.KenjinxNative
import org.kenjinx.android.NativeHelpers

/**
 * Real Kenji player: official JNA + official kenjinxjni + packaged libkenjinx.so.
 * Isolated in :player so a native abort does not kill the launcher.
 */
class PlayerActivity : Activity(), SurfaceHolder.Callback {

    companion object {
        fun intent(context: Context, path: String, title: String): Intent =
            Intent(context, PlayerActivity::class.java)
                .putExtra("path", path)
                .putExtra("title", title)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    private var pfd: ParcelFileDescriptor? = null
    private var loop: Thread? = null
    private var started = false
    private lateinit var status: TextView

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
        root.addView(status)
        root.addView(
            Button(this).apply { text = "Выйти"; setOnClickListener { leave() } },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END
            ).apply { setMargins(24, 24, 24, 48) }
        )
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
        val data = home.absolutePath
        val s = SettingsStore(this)

        if (!core.javaInitialize(data, JNIEnv.CURRENT)) {
            throw IllegalStateException("javaInitialize отказал (ключи/прошивка?)")
        }
        installPendingFirmware(core)
        core.loggingSetEnabled(3, true) // Error
        core.loggingSetEnabled(2, true) // Warning
        if (!core.deviceInitialize(
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
                "UTC",
                s.bool("ignoreMissingServices", false)
            )
        ) throw IllegalStateException("deviceInitialize отказал")

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

        val type = when (path.substringAfterLast('.', "").lowercase().substringBefore('?')) {
            "xci", "xcz" -> 2
            "nro" -> 3
            else -> 1
        }
        if (!core.deviceLoadDescriptor(fd, type, -1)) {
            throw IllegalStateException("не открыл игру (проверьте ключи и прошивку в папке данных)")
        }
        runOnUiThread { status.text = "Kenji · играет" }
        core.graphicsRendererRunLoop()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        runCatching { Kenji.core.deviceSignalEmulationClose() }
        runCatching { Kenji.core.deviceCloseEmulation() }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { leave() }

    override fun onDestroy() {
        runCatching { Kenji.core.deviceSignalEmulationClose() }
        runCatching { Kenji.core.deviceCloseEmulation() }
        loop?.join(1500)
        runCatching { pfd?.close() }
        super.onDestroy()
    }

    private fun leave() {
        runCatching { Kenji.core.deviceSignalEmulationClose() }
        finish()
    }

    private fun fail(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        status.postDelayed({ finish() }, 4000)
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
