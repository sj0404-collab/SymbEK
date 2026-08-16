package dev.symbiosis.kenji

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONObject
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineLoader

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsStore
    private lateinit var folders: FolderStore
    private lateinit var plugins: PluginStore

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val r = folders.add(uri)
        web.evaluateJavascript("try{onFolderAdded($r)}catch(e){}", null)
        reload()
    }

    private val pickPlugin = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var last = "{}"
        uris.forEach { last = plugins.install(it) }
        web.evaluateJavascript("try{onPluginsChanged($last)}catch(e){}", null)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        folders = FolderStore(this)
        plugins = PluginStore(this)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            addJavascriptInterface(Bridge(), "Kenji")
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/kenji.html")
        }
        setContentView(web)
        handleView(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleView(intent)
    }

    private fun handleView(intent: Intent?) {
        val uri = intent?.data ?: return
        startActivity(PlayerActivity.intent(this, uri.toString(), uri.lastPathSegment ?: "игра"))
    }

    private fun reload() {
        web.evaluateJavascript("try{loadGames();loadStatus();paintCore();}catch(e){}", null)
    }

    inner class Bridge {
        @JavascriptInterface fun settings(): String = settings.json()
        @JavascriptInterface fun setBool(key: String, on: Boolean): String = settings.setBool(key, on)
        @JavascriptInterface fun folders(): String = folders.json()
        @JavascriptInterface fun games(): String = folders.gamesJson()
        @JavascriptInterface fun pickFolder() { main.post { pickFolder.launch(null) } }
        @JavascriptInterface fun removeFolder(uri: String): String = folders.remove(uri)
        @JavascriptInterface fun plugins(): String = plugins.listJson()
        @JavascriptInterface fun pluginPayload(): String = plugins.payloadJson()
        @JavascriptInterface fun pickPlugin() { main.post { pickPlugin.launch(arrayOf("*/*")) } }
        @JavascriptInterface fun enablePlugin(id: String, on: Boolean): String = plugins.setEnabled(id, on)
        @JavascriptInterface fun removePlugin(id: String): String = plugins.remove(id)

        @JavascriptInterface
        fun core(): String {
            val st = EngineLoader.state(this@MainActivity, EngineLoader.Engine.KENJI)
            val state = when (st) {
                is EngineLoader.State.Ready -> "ready"
                is EngineLoader.State.Missing -> "missing"
                is EngineLoader.State.Broken -> "broken"
                is EngineLoader.State.Builtin -> "builtin"
            }
            val note = when (st) {
                is EngineLoader.State.Ready -> "скачан · ${st.bytes / 1048576} МБ"
                is EngineLoader.State.Missing -> "не скачан · ${(EngineLoader.KNOWN_SIZE[EngineLoader.Engine.KENJI] ?: 0L) / 1048576} МБ"
                is EngineLoader.State.Broken -> st.reason
                else -> ""
            }
            return JSONObject().put("state", state).put("note", note).toString()
        }

        @JavascriptInterface
        fun downloadCore() {
            Thread({
                val r = EngineDownloader.download(this@MainActivity, EngineLoader.Engine.KENJI) { done, total ->
                    val payload = JSONObject().put("done", done).put("total", total).toString()
                    main.post {
                        web.evaluateJavascript("try{onCoreProgress($payload)}catch(e){}", null)
                    }
                }
                val payload = JSONObject().put("ok", r.ok).put("message", r.message).toString()
                main.post { web.evaluateJavascript("try{onCoreDone($payload)}catch(e){}", null) }
            }, "kenji-dl").start()
        }

        @JavascriptInterface
        fun launch(path: String, title: String) {
            if (path.isBlank()) return
            main.post {
                if (settings.bool("preferExternal", true)) {
                    val handed = OfficialKenji.open(this@MainActivity, path)
                    if (JSONObject(handed).optBoolean("ok")) {
                        Toast.makeText(this@MainActivity, JSONObject(handed).optString("message"), Toast.LENGTH_SHORT).show()
                        return@post
                    }
                }
                val st = EngineLoader.state(this@MainActivity, EngineLoader.Engine.KENJI)
                if (st !is EngineLoader.State.Ready) {
                    Toast.makeText(this@MainActivity, "сначала скачайте ядро или поставьте их Kenji", Toast.LENGTH_LONG).show()
                    return@post
                }
                startActivity(PlayerActivity.intent(this@MainActivity, path, title))
            }
        }
    }
}
