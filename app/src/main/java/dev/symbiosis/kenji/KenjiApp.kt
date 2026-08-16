package dev.symbiosis.kenji

import android.app.Application
import java.io.File

class KenjiApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashGuard.install(this)
    }

    companion object {
        lateinit var instance: KenjiApp
            private set

        fun filesRoot(): File = instance.getExternalFilesDir(null) ?: instance.filesDir
    }
}
