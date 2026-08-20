package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.preference.PreferenceManager
import android.util.Log
import java.io.File
import java.util.Locale

/**
 * Official Kenji reads gameFolder as a Documents tree URI
 * (content://com.android.externalstorage.documents/tree/…) plus
 * gameFolderPath for all-files / RawDocumentFile. A plain /storage path
 * makes getGameList empty and decodeGameIcon fall back to NotAvailableIcon.
 */
object GameFolder {
    private val ROM_EXT = arrayOf(".nsp", ".xci", ".nro", ".nsz", ".xcz")

    fun pathToTreeUri(path: String): Uri? {
        if (path.isBlank()) return null
        if (path.startsWith("content:")) return Uri.parse(path)
        val abs = try {
            File(path).canonicalPath
        } catch (_: Exception) {
            File(path).absolutePath
        }
        val id = treeId(abs) ?: return null
        return Uri.parse(
            "content://com.android.externalstorage.documents/tree/" + Uri.encode(id),
        )
    }

    private fun treeId(abs: String): String? {
        val primary = listOf(
            Environment.getExternalStorageDirectory().absolutePath,
            "/storage/emulated/0",
            "/sdcard",
        )
        for (root in primary) {
            if (abs == root || abs.startsWith("$root/")) {
                val rel = abs.removePrefix(root).trimStart('/')
                return if (rel.isEmpty()) "primary:" else "primary:$rel"
            }
        }
        if (abs.startsWith("/storage/")) {
            val rest = abs.removePrefix("/storage/")
            val vol = rest.substringBefore('/')
            if (vol.isBlank() || vol == "emulated" || vol == "self") return null
            val rel = rest.substringAfter('/', missingDelimiterValue = "")
            return if (rel.isEmpty()) "$vol:" else "$vol:$rel"
        }
        return null
    }

    fun hasRoms(path: String): Boolean {
        if (path.isBlank() || path.startsWith("content:")) return false
        val dir = File(path)
        if (!dir.isDirectory) return false
        return dir.listFiles()?.any { f ->
            val n = f.name.lowercase(Locale.US)
            f.isFile && ROM_EXT.any { n.endsWith(it) }
        } == true
    }

    fun write(context: Context, dir: File): Boolean {
        if (!dir.isDirectory) return false
        val path = dir.absolutePath
        val uri = pathToTreeUri(path)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val e = prefs.edit()
        if (uri != null) {
            e.putString("gameFolder", uri.toString())
            e.putString("defaultGameFolderUri", uri.toString())
        } else {
            e.putString("gameFolder", path)
        }
        e.putString("gameFolderPath", path)
        e.putString("defaultFolderPath", path)
        e.commit()
        BootLog.add("gameFolder URI ${uri ?: "нет"} · path $path")
        return true
    }

    fun currentPath(context: Context): String {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val path = prefs.getString("gameFolderPath", "") ?: ""
        if (hasRoms(path)) return path
        val folder = prefs.getString("gameFolder", "") ?: ""
        if (hasRoms(folder)) return folder
        return path.ifBlank { folder }
    }

    fun reloadKenji(activity: Activity) {
        BootLog.add("полка: reloadGameList")
        activity.runOnUiThread {
            try {
                if (invokeReload(activity)) return@runOnUiThread
                if (!SpaceHook.isPlaying()) activity.recreate()
            } catch (t: Throwable) {
                Log.e("KenjiSpace", "reload", t)
                try {
                    if (!SpaceHook.isPlaying()) activity.recreate()
                } catch (_: Throwable) {
                }
            }
        }
    }

    private fun invokeReload(activity: Activity): Boolean {
        val vm = findHomeViewModel(activity) ?: return false
        for (name in arrayOf("reloadGameList", "reloadFromDisk")) {
            try {
                val m = vm.javaClass.methods.firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty()
                } ?: continue
                m.isAccessible = true
                m.invoke(vm)
                BootLog.add("полка: $name()")
                return true
            } catch (t: Throwable) {
                Log.w("KenjiSpace", name, t)
            }
        }
        return false
    }

    private fun findHomeViewModel(activity: Activity): Any? {
        try {
            val store = activity.javaClass.methods
                .firstOrNull { it.name == "getViewModelStore" && it.parameterTypes.isEmpty() }
                ?.invoke(activity) ?: return null
            val mapField = store.javaClass.declaredFields.firstOrNull {
                Map::class.java.isAssignableFrom(it.type)
            } ?: return null
            mapField.isAccessible = true
            val map = mapField.get(store) as? Map<*, *> ?: return null
            for (v in map.values) {
                val n = v?.javaClass?.name.orEmpty()
                if (n.contains("HomeViewModel")) return v
            }
        } catch (t: Throwable) {
            Log.w("KenjiSpace", "vmstore", t)
        }
        var c: Class<*>? = activity.javaClass
        while (c != null) {
            for (f in c.declaredFields) {
                try {
                    f.isAccessible = true
                    val v = f.get(activity) ?: continue
                    if (v.javaClass.name.contains("HomeViewModel")) return v
                } catch (_: Throwable) {
                }
            }
            c = c.superclass
        }
        return null
    }
}
