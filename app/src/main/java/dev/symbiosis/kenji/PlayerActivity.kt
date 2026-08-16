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
import java.io.File
import org.yuzu.yuzu_emu.utils.KenjiBridge

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
        root.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
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
        pfd = openRom(path) ?: return fail("не открылся файл")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (started) return
        val fd = pfd?.fd ?: return fail("нет дескриптора")
        val path = intent.getStringExtra("path").orEmpty()
        val w = holder.surfaceFrame.width().coerceAtLeast(128)
        val h = holder.surfaceFrame.height().coerceAtLeast(128)
        started = true
        loop = Thread({
            val nw = KenjiBridge.nativeWindow(holder.surface)
            org.kenjinx.android.KenjinxNative.nativeSurface = nw
            org.kenjinx.android.KenjinxNative.nativeWindow = nw
            val prep = KenjiBridge.preparePlay(this)
            if (!prep.ok) { runOnUiThread { fail(prep.message) }; return@Thread }
            val surf = KenjiBridge.attachSurface(holder.surface, w, h)
            if (!surf.ok) { runOnUiThread { fail(surf.message) }; return@Thread }
            val load = KenjiBridge.loadGame(fd, KenjiBridge.fileTypeOf(path))
            if (!load.ok) { runOnUiThread { fail(load.message) }; return@Thread }
            runOnUiThread { status.text = "Kenji Space · играет" }
            KenjiBridge.runLoop()
            runOnUiThread { leave() }
        }, "kenji-loop").also { it.start() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
    override fun surfaceDestroyed(holder: SurfaceHolder) { KenjiBridge.stopPlay() }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() { leave() }

    override fun onDestroy() {
        KenjiBridge.stopPlay()
        loop?.join(1500)
        KenjiBridge.unload()
        runCatching { pfd?.close() }
        super.onDestroy()
    }

    private fun leave() { KenjiBridge.stopPlay(); finish() }

    private fun fail(msg: String) {
        status.text = msg
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        status.postDelayed({ finish() }, 2400)
    }

    private fun openRom(path: String): ParcelFileDescriptor? = runCatching {
        if (path.startsWith("/")) ParcelFileDescriptor.open(File(path), ParcelFileDescriptor.MODE_READ_ONLY)
        else contentResolver.openFileDescriptor(Uri.parse(path), "r")
    }.getOrNull()
}
