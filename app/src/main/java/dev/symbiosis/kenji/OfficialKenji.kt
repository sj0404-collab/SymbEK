package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import org.json.JSONObject
import java.io.File

object OfficialKenji {
    private val PACKAGES = listOf("org.kenjinx.android", "org.ryujinx.android")

    fun installed(context: Context): String? {
        val pm = context.packageManager
        for (pkg in PACKAGES) {
            val ok = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= 33)
                    pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") pm.getPackageInfo(pkg, 0)
            }.isSuccess
            if (ok) return pkg
        }
        return null
    }

    fun open(context: Context, path: String): String {
        val pkg = installed(context)
            ?: return JSONObject().put("ok", false).put("message", "их Kenji не установлен").toString()
        val uri = runCatching {
            if (path.startsWith("/")) Uri.fromFile(File(path)) else Uri.parse(path)
        }.getOrNull()
        if (uri != null) {
            val view = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/octet-stream")
                setPackage(pkg)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.grantUriPermission(pkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            if (runCatching { context.startActivity(view) }.isSuccess) {
                return JSONObject().put("ok", true).put("message", "открыл их Kenji").toString()
            }
        }
        val launch = context.packageManager.getLaunchIntentForPackage(pkg)
            ?: return JSONObject().put("ok", false).put("message", "не открылся $pkg").toString()
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return if (runCatching { context.startActivity(launch) }.isSuccess)
            JSONObject().put("ok", true).put("message", "открыл их Kenji — выберите игру там").toString()
        else JSONObject().put("ok", false).put("message", "не открылся").toString()
    }
}
