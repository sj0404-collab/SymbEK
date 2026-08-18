package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import java.io.File

object OfficialLaunch {
    fun game(context: Context, rom: GameRom) {
        if (!rom.exists) {
            toast(context, "файла игры нет — укажите папку в Настройках")
            return
        }
        if (rom.update) {
            toast(context, "это обновление, нужен базовый NSP")
            return
        }
        FolderHub.applyAfterFolderChange(context)
        if (!DataSeed.keysOk(context) || DataSeed.firmwareNca(context) < 5) {
            toast(context, "нет ключей или прошивки — в Настройках укажите Eden/files")
            return
        }
        val file = File(rom.path)
        val parent = file.parentFile
        if (parent != null) FolderHub.addGamesDir(context, parent.absolutePath)
        remember(context, rom)
        val uri = Uri.fromFile(file)
        val extras = arrayOf("bootPath", "path", "gamePath", "filePath", "romPath")
        fun fill(i: Intent) {
            extras.forEach { i.putExtra(it, rom.path) }
            i.putExtra("titleName", rom.title)
            i.putExtra("titleId", rom.titleId)
            i.putExtra("forceNceAndPptc", false)
            i.setDataAndType(uri, "application/octet-stream")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val primary = Intent().also {
            it.setClassName(context.packageName, "org.kenjinx.android.MainActivity")
            it.action = "org.kenjinx.android.LAUNCH_GAME"
            fill(it)
        }
        val view = Intent().also {
            it.setClassName(context.packageName, "org.kenjinx.android.MainActivity")
            it.action = Intent.ACTION_VIEW
            fill(it)
        }
        try {
            context.startActivity(primary)
        } catch (_: Throwable) {
            try {
                context.startActivity(view)
            } catch (t: Throwable) {
                toast(context, "не открылось: ${t.message}")
            }
        }
    }

    private fun remember(context: Context, rom: GameRom) {
        val p = context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        p.edit()
            .putString("last_path", rom.path)
            .putString("last_title", rom.title)
            .putString("last_tid", rom.titleId)
            .putLong("last_at_${rom.titleId}", now)
            .putLong("play_${rom.titleId}", p.getLong("play_${rom.titleId}", 0L) + 1L)
            .commit()
        try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("lastTitleId", rom.titleId).commit()
        } catch (_: Throwable) {
        }
    }

    fun lastAt(context: Context, tid: String): Long =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getLong("last_at_$tid", 0L)

    fun launches(context: Context, tid: String): Long =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getLong("play_$tid", 0L)

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
