package dev.symbiosis.kenji

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log

/**
 * Runs before KenjinxApplication.
 * Restores stash, points bis/ at Eden nand via shortcuts, seeds QuickSettings.
 * Never throws into the official process.
 */
class SeedProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        try {
            val ctx = context
            if (ctx != null) {
                WebWipe.run(ctx)
                AccessFix.repair(ctx)
                DataSeed.ensure(ctx)
                SettingsBank.applyDefaultOnce(ctx)
                val app = ctx.applicationContext
                if (app is android.app.Application) SpaceHook.install(app)
            }
        } catch (t: Throwable) {
            Log.e("KenjiSpace", "auto-seed", t)
        }
        return true
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, sel: String?, args: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<out String>?): Int = 0
}
