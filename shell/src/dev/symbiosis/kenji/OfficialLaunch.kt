package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
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
        if (!DataSeed.keysOk(context) || DataSeed.firmwareNca(context) < 5) {
            toast(context, "нет ключей или прошивки — в Настройках укажите Eden/files")
            return
        }
        val file = File(rom.path)
        if (!file.isFile) {
            toast(context, "файл исчез: ${rom.path}")
            return
        }
        remember(context, rom)
        SpaceHook.armTimer()
        val intent = Intent()
        intent.setClassName(context.packageName, "org.kenjinx.android.MainActivity")
        intent.action = "org.kenjinx.android.LAUNCH_GAME"
        // Official handleIntent reads these names. Do not set file:// — FileUriExposedException.
        intent.putExtra("bootPath", rom.path)
        intent.putExtra("titleName", rom.title)
        intent.putExtra("titleId", rom.titleId)
        intent.putExtra("forceNceAndPptc", false)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(intent)
        } catch (t: Throwable) {
            val view = Intent()
            view.setClassName(context.packageName, "org.kenjinx.android.MainActivity")
            view.action = Intent.ACTION_VIEW
            view.putExtra("bootPath", rom.path)
            view.putExtra("titleName", rom.title)
            view.putExtra("titleId", rom.titleId)
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                context.startActivity(view)
            } catch (t2: Throwable) {
                toast(context, "не открылось: ${t2.message}")
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
