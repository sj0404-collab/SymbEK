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
 * Official Kenji lists games from a *granted* Documents tree URI
 * (the one ACTION_OPEN_DOCUMENT_TREE returns) or, with all-files,
 * from a filesystem path via fromFullPath / toRawFile.
 *
 * 1.0.91 wrote a synthesized tree URI without persistable permission.
 * fromTreeUri then returns an empty tree → blank shelf + NotAvailableIcon.
 */
object GameFolder {
    private val ROM_EXT = arrayOf(".nsp", ".xci", ".nro", ".nsz", ".xcz")

    fun hasRoms(path: String): Boolean {
        if (path.isBlank() || path.startsWith("content:")) return false
        val dir = File(path)
        if (!dir.isDirectory) return false
        return dir.listFiles()?.any { f ->
            val n = f.name.lowercase(Locale.US)
            f.isFile && ROM_EXT.any { n.endsWith(it) }
        } == true
    }

    fun matchingPersisted(context: Context, path: String): Uri? {
        if (path.isBlank()) return null
        context.contentResolver.persistedUriPermissions.forEach { p ->
            val got = PickActivity.treeToPath(p.uri) ?: return@forEach
            if (got == path || path.startsWith("$got/") || got.startsWith("$path/")) return p.uri
        }
        return null
    }

    fun write(context: Context, dir: File, grantedUri: Uri? = null): Boolean {
        if (!dir.isDirectory) return false
        val path = dir.absolutePath
        val persisted = grantedUri ?: matchingPersisted(context, path)
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val e = prefs.edit()
        if (persisted != null) {
            e.putString("gameFolder", persisted.toString())
            e.putString("defaultGameFolderUri", persisted.toString())
            BootLog.add("gameFolder URI (granted) $persisted · $path")
        } else {
            // Never invent an ungranted tree URI. Kenji can use the path
            // when MANAGE_EXTERNAL_STORAGE is on (fromFullPath / RawDocumentFile).
            e.putString("gameFolder", path)
            e.remove("defaultGameFolderUri")
            BootLog.add("gameFolder path $path (all-files, без SAF)")
        }
        e.putString("gameFolderPath", path)
        e.putString("defaultFolderPath", path)
        e.commit()
        return true
    }

    /** Drop 1.0.91 fake tree URIs that have no persistable grant. */
    fun sanitize(context: Context): Boolean {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val folder = prefs.getString("gameFolder", "") ?: ""
        val path = prefs.getString("gameFolderPath", "") ?: ""
        if (folder.startsWith("content:")) {
            val granted = context.contentResolver.persistedUriPermissions.any { p ->
                val u = p.uri.toString()
                u == folder || folder.startsWith(u)
            }
            if (granted) return false
            val match = matchingPersisted(context, path)
            if (match != null) {
                prefs.edit()
                    .putString("gameFolder", match.toString())
                    .putString("defaultGameFolderUri", match.toString())
                    .commit()
                BootLog.add("gameFolder: подставил выданный URI $match")
                return true
            }
            if (path.isNotBlank()) {
                prefs.edit()
                    .putString("gameFolder", path)
                    .remove("defaultGameFolderUri")
                    .commit()
                BootLog.add("gameFolder: снял фейковый URI → $path")
                return true
            }
            return false
        }
        if (folder.isBlank() && hasRoms(path)) {
            return write(context, File(path))
        }
        return false
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
        BootLog.add("полка: reload")
        activity.runOnUiThread {
            // Do not call reloadGameList — Kenji shows a modal Loading card
            // on the shelf without opening GameHost.
            try {
                if (!SpaceHook.isPlaying() && !SpaceHook.isBooting()) activity.recreate()
            } catch (t: Throwable) {
                Log.e("KenjiSpace", "recreate", t)
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
        return null
    }
}
