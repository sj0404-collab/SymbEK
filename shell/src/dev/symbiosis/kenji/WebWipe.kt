package dev.symbiosis.kenji

import android.content.Context
import android.util.Log
import java.io.File

/** Drop leftover WebView / HTML caches from older Space builds. */
object WebWipe {
    fun run(context: Context) {
        try {
            val data = context.applicationInfo.dataDir?.let { File(it) }
                ?: context.filesDir.parentFile
            listOf("app_webview", "app_webview_data", "app_textures", "app_hws_webview").forEach {
                if (data != null) wipe(File(data, it))
            }
            wipe(context.getDir("webview", Context.MODE_PRIVATE))
            wipe(context.getDir("webview_data", Context.MODE_PRIVATE))
            context.cacheDir?.let { cache ->
                listOf("WebView", "webview", "org.chromium.android_webview", "www", "web").forEach {
                    wipe(File(cache, it))
                }
            }
            wipe(File(context.filesDir, "www"))
            wipe(File(context.filesDir, "web"))
            context.externalCacheDir?.let {
                wipe(File(it, "www"))
                wipe(File(it, "WebView"))
            }
        } catch (t: Throwable) {
            Log.w("KenjiSpace", "webwipe", t)
        }
    }

    private fun wipe(f: File?) {
        if (f == null || !f.exists()) return
        f.listFiles()?.forEach { wipe(it) }
        f.delete()
    }
}
