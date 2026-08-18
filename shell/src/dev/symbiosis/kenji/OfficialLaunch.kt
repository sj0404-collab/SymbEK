package dev.symbiosis.kenji

import android.content.Context
import android.content.Intent
import android.widget.Toast
import java.io.File

object OfficialLaunch {
    fun game(context: Context, rom: GameRom) {
        if (!rom.exists) {
            toast(context, "файла игры нет")
            return
        }
        if (rom.update) {
            toast(context, "это обновление, нужен базовый NSP")
            return
        }
        if (!DataSeed.keysOk(context) || DataSeed.firmwareNca(context) < 5) {
            toast(context, "нет ключей или прошивки")
            return
        }
        val cover = CoverArt.load(context, rom)
        if (cover == null) {
            toast(context, "нет обложки — Запустить скрыта, пока ROM не отдаст иконку")
            return
        }
        cover.recycle()
        try {
            val i = Intent()
            i.setClassName(context.packageName, "org.kenjinx.android.MainActivity")
            i.action = "org.kenjinx.android.LAUNCH_GAME"
            i.putExtra("bootPath", rom.path)
            i.putExtra("titleName", rom.title)
            i.putExtra("titleId", rom.titleId)
            i.putExtra("forceNceAndPptc", false)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
            remember(context, rom)
        } catch (t: Throwable) {
            toast(context, "не открылось: ${t.message}")
        }
    }

    private fun remember(context: Context, rom: GameRom) {
        val p = context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        p.edit()
            .putString("last_path", rom.path)
            .putString("last_title", rom.title)
            .putString("last_tid", rom.titleId)
            .putLong("last_at", now)
            .putLong("play_${rom.titleId}", p.getLong("play_${rom.titleId}", 0L) + 1L)
            .commit()
        try {
            android.preference.PreferenceManager.getDefaultSharedPreferences(context)
                .edit().putString("lastTitleId", rom.titleId).commit()
        } catch (_: Throwable) {
        }
    }

    fun lastAt(context: Context, tid: String): Long =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getLong("last_at", 0L).let { at ->
            val last = context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getString("last_tid", "")
            if (last == tid) at else 0L
        }

    fun launches(context: Context, tid: String): Long =
        context.getSharedPreferences("kenji_space", Context.MODE_PRIVATE).getLong("play_$tid", 0L)

    private fun toast(context: Context, msg: String) {
        Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
    }
}
