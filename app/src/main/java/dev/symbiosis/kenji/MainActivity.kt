package dev.symbiosis.kenji

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.yuzu.yuzu_emu.utils.EngineDownloader
import org.yuzu.yuzu_emu.utils.EngineLoader
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private val main = Handler(Looper.getMainLooper())
    private lateinit var settings: SettingsStore
    private lateinit var folders: FolderStore
    private lateinit var plugins: PluginStore

    private val pickFolder = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        val r = folders.add(uri)
        web.evaluateJavascript("try{if(typeof onFolderAdded==='function')onFolderAdded($r)}catch(e){}", null)
        reload()
    }

    private val pickData = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        val path = treePath(uri)
        val r = if (path != null) DataRoot.setPath(path)
        else JSONObject().put("ok", false).put("message", "не удалось прочитать путь к папке").toString()
        web.evaluateJavascript("try{if(typeof onSavesPicked==='function')onSavesPicked($r)}catch(e){}", null)
        reload()
        Toast.makeText(this, JSONObject(r).optString("message"), Toast.LENGTH_LONG).show()
    }

    private val pickPlugin = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        var last = "{}"
        uris.forEach { last = plugins.install(it) }
        web.evaluateJavascript("try{if(typeof onPluginsChanged==='function')onPluginsChanged($last)}catch(e){}", null)
    }

    private val pickKeys = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val r = installKeyFile(uri)
        Toast.makeText(this, JSONObject(r).optString("message"), Toast.LENGTH_LONG).show()
        reload()
    }

    private val pickFirmware = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@registerForActivityResult
        val r = stageFirmware(uri)
        Toast.makeText(this, JSONObject(r).optString("message"), Toast.LENGTH_LONG).show()
        reload()
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsStore(this)
        folders = FolderStore(this)
        plugins = PluginStore(this)
        DataRoot.ensureKenjiLayout(DataRoot.kenjiHome())
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            val bridge = Bridge()
            addJavascriptInterface(bridge, "Symbiosis")
            addJavascriptInterface(bridge, "Kenji")
            webViewClient = WebViewClient()
            loadUrl("file:///android_asset/kenji.html")
        }
        setContentView(web)
        handleView(intent)
        maybeAskAllFiles()
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
        web.evaluateJavascript(
            "try{if(typeof loadGames==='function')loadGames();if(typeof loadStatus==='function')loadStatus();}catch(e){}",
            null
        )
    }

    private fun maybeAskAllFiles() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            // Needed to share /sdcard/Kenji or another app's files folder.
            runCatching {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }
    }

    private fun treePath(uri: Uri): String? {
        val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val parts = id.split(':')
        if (parts.isEmpty()) return null
        val volume = parts[0]
        val relative = parts.getOrElse(1) { "" }
        val candidates = buildList {
            if (volume == "primary") add("${Environment.getExternalStorageDirectory()}/$relative")
            add("/storage/$volume/$relative")
        }
        return candidates.firstOrNull { File(it).isDirectory }
    }

    private fun installKeyFile(uri: Uri): String = runCatching {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "prod.keys"
        if (!name.endsWith(".keys", true)) {
            return@runCatching JSONObject().put("ok", false).put("message", "нужен prod.keys или title.keys").toString()
        }
        val root = DataRoot.kenjiHome()
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return@runCatching JSONObject().put("ok", false).put("message", "не прочитался файл").toString()
        if (bytes.size < 100) {
            return@runCatching JSONObject().put("ok", false).put("message", "файл слишком маленький").toString()
        }
        listOf(File(root, "system"), File(root, "keys")).forEach { dir ->
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
        JSONObject().put("ok", true).put("message", "ключи $name записаны в ${root.absolutePath}").toString()
    }.getOrElse { JSONObject().put("ok", false).put("message", it.message ?: "ключи не встали").toString() }

    private fun stageFirmware(uri: Uri): String = runCatching {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull() ?: "firmware.bin"
        val dest = File(DataRoot.kenjiHome(), "pending-firmware")
        dest.mkdirs()
        val out = File(dest, name)
        contentResolver.openInputStream(uri)?.use { input ->
            out.outputStream().use { input.copyTo(it) }
        } ?: return@runCatching JSONObject().put("ok", false).put("message", "не прочиталась прошивка").toString()
        settings.setString("pendingFirmware", out.absolutePath)
        JSONObject().put("ok", true)
            .put("message", "прошивка сохранена ($name). Поставится в эту папку при первом запуске ядра.")
            .toString()
    }.getOrElse { JSONObject().put("ok", false).put("message", it.message ?: "прошивка не сохранилась").toString() }

    inner class Bridge {
        @JavascriptInterface fun bridgeVersion(): Int = 16
        @JavascriptInterface fun settings(): String = settings.json()
        @JavascriptInterface fun setBool(key: String, on: Boolean): String = settings.setBool(key, on)
        @JavascriptInterface fun setResolution(index: Int): String = settings.setResolution(index)
        @JavascriptInterface fun cycleDram(): String = settings.cycleDram()
        @JavascriptInterface fun cycleMemMode(): String = settings.cycleMemMode()
        @JavascriptInterface fun pickKeys() { main.post { pickKeys.launch(arrayOf("*/*")) } }
        @JavascriptInterface fun pickFirmware() { main.post { pickFirmware.launch(arrayOf("*/*")) } }
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
        fun status(): String = JSONObject()
            .put("items", DataRoot.statusItems())
            .put("dataRoot", DataRoot.resolve())
            .toString()

        @JavascriptInterface fun dataRoot(): String =
            JSONObject().put("path", DataRoot.resolve())
                .put("hasLoad", File(DataRoot.resolve(), "load").isDirectory)
                .put("hasSaves", File(DataRoot.resolve(), "bis").isDirectory)
                .toString()

        @JavascriptInterface fun suggestRoots(): String = DataRoot.suggest()
        @JavascriptInterface fun pickDataRoot() { main.post { pickData.launch(null) } }
        @JavascriptInterface fun pickSavesFolder() { main.post { pickData.launch(null) } }
        @JavascriptInterface fun clearSavesFolder() {
            DataRoot.configured = null
        }

        @JavascriptInterface
        fun saveSource(): String {
            val root = DataRoot.resolve()
            val bis = File(root, "bis")
            return JSONObject()
                .put("path", root)
                .put("name", File(root).name)
                .put("titles", bis.walkTopDown().count { it.isDirectory && it.name.length == 32 })
                .put("size", "")
                .toString()
        }

        @JavascriptInterface fun saves(): String {
            val root = File(DataRoot.resolve(), "bis")
            val items = JSONArray()
            if (root.isDirectory) {
                root.walkTopDown().maxDepth(4).forEach { f ->
                    if (f.isDirectory && f != root && (f.listFiles()?.any { it.isFile } == true)) {
                        items.put(JSONObject().put("name", f.name).put("detail", f.absolutePath))
                    }
                }
            }
            return JSONObject().put("path", root.absolutePath).put("items", items).toString()
        }

        @JavascriptInterface fun mods(): String =
            JSONObject().put("path", "").put("items", JSONArray()).toString()

        @JavascriptInterface fun keysOk(): Boolean = DataRoot.keysPresent()
        @JavascriptInterface fun files(uriString: String): String = folders.filesJson(uriString)
        @JavascriptInterface fun icon(path: String): String = ""
        @JavascriptInterface fun cover(path: String): String = ""
        @JavascriptInterface fun shot(path: String): String = encodeJpeg(path)
        @JavascriptInterface fun shots(path: String, title: String): String = shotsJson()
        @JavascriptInterface fun memory(): String = memoryJson()
        @JavascriptInterface fun prepareShaders(): String =
            JSONObject().put("ok", true).put("note", "шейдер-кэш Kenji живёт в games/<title>/cache/shader").toString()
        @JavascriptInterface fun applyAaaMode(): String {
            settings.setBool("useNce", false)
            settings.setBool("enablePerformanceMode", false)
            settings.setBool("enableFsIntegrityChecks", false)
            return JSONObject().put("ok", true).put("applied", 3)
                .put("message", "AAA минимум для Kenji: NCE выкл, performance выкл, integrity выкл")
                .put("where", "настройки Kenji").toString()
        }
        @JavascriptInterface fun crashReport(): String {
            val f = File(filesDir, "logs/crash.log")
            val text = if (f.isFile) f.readText().takeLast(4000) else "крашей оболочки нет"
            return JSONObject().put("ok", true).put("title", "Отчёт Kenji Space")
                .put("detail", DataRoot.resolve()).put("excerpt", text)
                .put("path", f.absolutePath).toString()
        }

        @JavascriptInterface fun presets(): String = Presets.listJson()
        @JavascriptInterface fun savePreset(name: String): String = Presets.snapshot(name, settings)
        @JavascriptInterface fun applyPreset(name: String): String = Presets.apply(name, settings)
        @JavascriptInterface fun removePreset(name: String): String = Presets.remove(name)

        @JavascriptInterface fun converterItems(): String = Inbox.listJson()
        @JavascriptInterface fun pickConvert() { main.post { pickConvertFiles.launch(arrayOf("*/*")) } }
        @JavascriptInterface fun deleteConverted(path: String): String = Inbox.delete(path)
        @JavascriptInterface fun canOpen(path: String): Boolean = Inbox.canOpen(path)
        @JavascriptInterface fun convertQueue(): String = Inbox.queueJson()
        @JavascriptInterface fun readText(path: String): String = JSONObject().put("ok", false).put("reason", "нет").toString()
        @JavascriptInterface fun adoptSave(path: String, title: String): String = JSONObject().put("ok", true).toString()
        @JavascriptInterface fun rescan() { main.post { reload() } }
        @JavascriptInterface fun reloadInterface() { main.post { web.loadUrl("file:///android_asset/kenji.html") } }
        @JavascriptInterface fun openTools() {
            main.post { web.evaluateJavascript("try{openUtilitiesSheet()}catch(e){}", null) }
        }
        @JavascriptInterface fun openUtilities() {
            main.post { web.evaluateJavascript("try{openUtilitiesSheet()}catch(e){}", null) }
        }
        @JavascriptInterface fun openSettings() {
            main.post { web.evaluateJavascript("try{openSettingsSheet()}catch(e){}", null) }
        }
        @JavascriptInterface fun openGameMenu(path: String) {
            main.post {
                val g = JSONObject().put("path", path).toString()
                web.evaluateJavascript("try{openGameSheet($g)}catch(e){}", null)
            }
        }
        @JavascriptInterface fun openEngines() { main.post { downloadCore() } }
        @JavascriptInterface fun spaces(): String {
            val ext = OfficialKenji.installed(this@MainActivity)
            return JSONObject()
                .put("current", "symbiosis")
                .put("preferExternal", settings.bool("preferExternal", false))
                .put("official", JSONObject().put("installed", ext != null).put("package", ext ?: "").put("label", if (ext != null) "Kenji-NX" else ""))
                .put("kenjiCore", coreState())
                .put("items", JSONArray()
                    .put(JSONObject().put("id", "symbiosis").put("label", "Kenji Space").put("selected", true).put("ready", true))
                    .put(JSONObject().put("id", "kenji").put("label", "Их Kenji").put("selected", false).put("ready", ext != null)))
                .toString()
        }
        @JavascriptInterface fun selectSpace(id: String): String {
            if (id == "kenji") {
                val r = OfficialKenji.open(this@MainActivity, "")
                return r
            }
            return JSONObject().put("ok", true).put("id", "symbiosis").put("message", "Kenji Space").toString()
        }
        @JavascriptInterface fun installKenjiShell(): String =
            JSONObject().put("ok", true).put("message", "оболочка уже эта").toString()
        @JavascriptInterface fun setPreferExternal(on: Boolean): String = settings.setBool("preferExternal", on)
        @JavascriptInterface fun openOfficialKenji(path: String): String = OfficialKenji.open(this@MainActivity, path)
        @JavascriptInterface fun engines(): String {
            val st = EngineLoader.state(this@MainActivity, EngineLoader.Engine.KENJI)
            val ready = st is EngineLoader.State.Ready
            return JSONObject()
                .put("current", "kenji")
                .put("currentLabel", "Kenji-NX")
                .put("launch", "kenji")
                .put("launchLabel", "Kenji-NX")
                .put("items", JSONArray().put(
                    JSONObject().put("id", "kenji").put("label", "Kenji-NX")
                        .put("state", if (ready) "ready" else "missing")
                        .put("usable", ready)
                        .put("selected", true)
                        .put("launches", ready)
                        .put("note", if (ready) "вшито в APK · ${st.let { if (it is EngineLoader.State.Ready) it.bytes / 1048576 else 54 }} МБ" else "ядра нет в APK")
                ))
                .toString()
        }
        @JavascriptInterface fun selectEngine(id: String): String =
            JSONObject().put("ok", true).put("id", "kenji").put("message", "ядро Kenji").toString()
        @JavascriptInterface fun cycleCpu(): String {
            val on = !settings.bool("useNce", false)
            return settings.setBool("useNce", on)
        }
        @JavascriptInterface fun downloadEngine(id: String) { downloadCore() }
        @JavascriptInterface fun probeEngine(id: String) {
            val payload = JSONObject().put("ok", DataRoot.keysPresent())
                .put("message", if (DataRoot.keysPresent()) "ключи на месте, ядро можно запускать" else "нет ключей — положите prod.keys")
                .toString()
            main.post { web.evaluateJavascript("try{if(typeof onEngineProbe==='function')onEngineProbe($payload)}catch(e){}", null) }
        }
        @JavascriptInterface fun removeEngine(id: String): String {
            EngineLoader.remove(this@MainActivity, EngineLoader.Engine.KENJI)
            return JSONObject().put("ok", true).put("message", "ядро удалено").toString()
        }

        @JavascriptInterface
        fun downloadCore() {
            Thread({
                val r = EngineDownloader.download(this@MainActivity, EngineLoader.Engine.KENJI) { done, total ->
                    val payload = JSONObject().put("done", done).put("total", total).toString()
                    main.post { web.evaluateJavascript("try{if(typeof onEngineProgress==='function')onEngineProgress($payload)}catch(e){}", null) }
                }
                val payload = JSONObject().put("ok", r.ok).put("message", r.message).toString()
                main.post { web.evaluateJavascript("try{if(typeof onEngineDone==='function')onEngineDone($payload)}catch(e){}", null) }
            }, "kenji-dl").start()
        }

        @JavascriptInterface
        fun launch(path: String, title: String) {
            if (path.isBlank()) return
            main.post {
                if (settings.bool("preferExternal", false)) {
                    val handed = OfficialKenji.open(this@MainActivity, path)
                    if (JSONObject(handed).optBoolean("ok")) return@post
                }
                val st = EngineLoader.state(this@MainActivity, EngineLoader.Engine.KENJI)
                if (st !is EngineLoader.State.Ready) {
                    Toast.makeText(this@MainActivity, "скачайте ядро: Ядра → Скачать", Toast.LENGTH_LONG).show()
                    downloadCore()
                    return@post
                }
                startActivity(PlayerActivity.intent(this@MainActivity, path, title))
            }
        }

        private fun coreState(): String {
            val st = EngineLoader.state(this@MainActivity, EngineLoader.Engine.KENJI)
            return when (st) {
                is EngineLoader.State.Ready -> "ready"
                is EngineLoader.State.Broken -> "broken"
                else -> "missing"
            }
        }
    }

    private val pickConvertFiles = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        uris.forEach { Inbox.import(this, it) }
        web.evaluateJavascript(
            "try{if(typeof onConverted==='function')onConverted({ok:true,message:'файлы в конвертере'})}catch(e){}",
            null
        )
    }

    private fun memoryJson(): String {
        val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
        val info = android.app.ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val left = info.availMem / (1024 * 1024)
        val total = info.totalMem / (1024 * 1024)
        return JSONObject()
            .put("leftMb", left)
            .put("usedMb", (total - left).coerceAtLeast(0))
            .put("budgetMb", total)
            .put("warn", info.lowMemory || left < 800)
            .put("note", if (info.lowMemory) "мало RAM" else "")
            .toString()
    }

    private fun encodeJpeg(path: String): String {
        if (path.isBlank()) return ""
        val f = File(path)
        if (!f.isFile) return ""
        return runCatching {
            val bytes = f.readBytes()
            if (bytes.size < 32) ""
            else android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        }.getOrDefault("")
    }

    private fun shotsJson(): String {
        val dir = File(DataRoot.resolve(), "screenshots")
        val arr = JSONArray()
        dir.listFiles()
            ?.filter { it.isFile && it.extension.lowercase() in setOf("jpg", "jpeg", "png") }
            ?.sortedByDescending { it.lastModified() }
            ?.take(24)
            ?.forEach { arr.put(JSONObject().put("path", it.absolutePath).put("name", it.name)) }
        return JSONObject().put("items", arr).toString()
    }
}
