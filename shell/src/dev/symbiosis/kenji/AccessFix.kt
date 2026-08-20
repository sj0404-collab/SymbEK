package dev.symbiosis.kenji

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.preference.PreferenceManager
import android.provider.Settings
import android.util.Log
import java.io.File

/** Re-take game-folder read/write and all-files access. */
object AccessFix {
    fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true

    fun repair(context: Context) {
        try {
            retakeUris(context)
            seedGameFolder(context)
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "access", t)
        }
    }

    fun askAllFiles(activity: Activity) {
        if (hasAllFiles()) return
        try {
            val i = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
            i.data = Uri.parse("package:${activity.packageName}")
            activity.startActivity(i)
        } catch (_: Exception) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            } catch (_: Exception) {
            }
        }
    }

    fun retakeUris(context: Context): Int {
        var n = 0
        val cr = context.contentResolver
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        cr.persistedUriPermissions.forEach { p ->
            try {
                cr.takePersistableUriPermission(p.uri, flags)
                n++
            } catch (_: Exception) {
            }
        }
        val official = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("gameFolder", "")
        if (!official.isNullOrBlank() && official.startsWith("content:")) {
            try {
                cr.takePersistableUriPermission(Uri.parse(official), flags)
                n++
            } catch (_: Exception) {
            }
        }
        return n
    }

    fun seedGameFolder(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val current = prefs.getString("gameFolder", "")
        if (!current.isNullOrBlank()) return
        val sd = Environment.getExternalStorageDirectory()
        val persisted = context.contentResolver.persistedUriPermissions.firstOrNull()
        if (persisted != null) {
            prefs.edit().putString("gameFolder", persisted.uri.toString()).commit()
            return
        }
        val candidates = listOf(
            "Download/ed", "Download/ed/Eden", "Switch", "Games", "NSP",
            "Download/Switch", "Download/Games", "Download",
        )
        for (rel in candidates) {
            val dir = File(sd, rel)
            if (!dir.isDirectory) continue
            val hasRom = dir.listFiles()?.any { f ->
                val n = f.name.lowercase()
                f.isFile && (n.endsWith(".nsp") || n.endsWith(".xci") || n.endsWith(".nro"))
            } == true
            if (hasRom) {
                GameFolder.write(context, dir)
                return
            }
        }
    }

    fun statusLine(context: Context): String {
        val all = if (hasAllFiles()) "доступ ко всем файлам: да" else "доступ ко всем файлам: нет — откроется запрос"
        val uris = context.contentResolver.persistedUriPermissions.size
        val folder = PreferenceManager.getDefaultSharedPreferences(context)
            .getString("gameFolder", "") ?: ""
        val games = if (folder.isBlank()) "папка игр не задана" else "игры: $folder"
        return "$all · URI $uris · $games"
    }
}
