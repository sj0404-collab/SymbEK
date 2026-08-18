package dev.symbiosis.kenji

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Before KenjinxApplication. Must stay cheap — a disk walk here
 * freezes the first frame (white screen).
 */
class SeedProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            BootLog.add("1. SeedProvider.onCreate")
            val ctx = context
            if (ctx != null) {
                BootLog.captureVersion(ctx)
                WebWipe.run(ctx)
                BootLog.add("2. WebWipe")
                val app = ctx.applicationContext
                if (app is android.app.Application) {
                    SpaceHook.install(app)
                    BootLog.add("3. SpaceHook.install")
                }
                DataSeed.pointHomeEarly(ctx)
                BootLog.add("4. pointHomeEarly готово")
                BootLog.startLogcat()
            } else {
                BootLog.add("SeedProvider: context=null")
            }
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "auto-seed", t)
            BootLog.add("SeedProvider ошибка: ${t.message}")
        }
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
