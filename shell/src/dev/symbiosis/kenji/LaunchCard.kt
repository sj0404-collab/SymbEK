package dev.symbiosis.kenji

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Long-press on the shelf (or «слои» in the panel, or long-press the chip).
 * User picks in-game layers and how the next title boots.
 */
object LaunchCard {
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val WARN = 0xFFFFB020.toInt()

    fun show(host: Activity) {
        try {
            val scroll = ScrollView(host)
            val box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            val pad = dp(host, 14)
            box.setPadding(pad, pad, pad, pad)
            fill(host, box)
            scroll.addView(box)
            AlertDialog.Builder(host)
                .setTitle("запуск и слои")
                .setView(scroll)
                .setPositiveButton("закрыть") { _, _ -> SpaceHook.applyLayers(host) }
                .show()
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "launch-card", t)
        }
    }

    private fun fill(host: Activity, box: LinearLayout) {
        box.removeAllViews()
        val inGame = SpaceHook.isPlaying() || SpaceHook.isBooting()

        addSection(host, box, "слои в игре")
        val hint = TextView(host)
        hint.setTextColor(MUTED)
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        hint.text = if (inGame) {
            "сейчас на игре · переключение сразу"
        } else {
            "на полке завод Kenji (R, Search, обложки). в игре — только то, что включите здесь."
        }
        hint.setPadding(0, 0, 0, dp(host, 6))
        box.addView(hint)

        val presets = LinearLayout(host)
        presets.orientation = LinearLayout.HORIZONTAL
        val key = LayerBank.currentPresetKey(host)
        presets.addView(mini(host, "чистое", key == "clean") {
            LayerBank.applyPreset(host, "clean"); SpaceHook.applyLayers(host); refill(host, box)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        presets.addView(mini(host, "часы", key == "chip") {
            LayerBank.applyPreset(host, "chip"); SpaceHook.applyLayers(host); refill(host, box)
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(host, 4) })
        presets.addView(mini(host, "часы+FPS", key == "chip_fps") {
            LayerBank.applyPreset(host, "chip_fps"); SpaceHook.applyLayers(host); refill(host, box)
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(host, 4) })
        presets.addView(mini(host, "полный", key == "full") {
            LayerBank.applyPreset(host, "full"); SpaceHook.applyLayers(host); refill(host, box)
        }, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(host, 4) })
        box.addView(presets)

        box.addView(toggle(host, "⏳ Space чип", LayerBank.chipOn(host)) { on ->
            LayerBank.setChip(host, on)
            SpaceHook.applyLayers(host)
        })
        box.addView(toggle(host, "FPS / CPU Space", LayerBank.statsOn(host)) { on ->
            LayerBank.setStats(host, on)
            SpaceHook.applyLayers(host)
        })
        box.addView(toggle(host, "пауза + ⚙ Space", LayerBank.hudOn(host)) { on ->
            LayerBank.setHud(host, on)
            SpaceHook.applyLayers(host)
        })

        val factory = TextView(host)
        factory.setTextColor(WARN)
        factory.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        factory.setPadding(0, dp(host, 8), 0, dp(host, 8))
        factory.text = "заводской Loading / шейдеры — окно Kenji, привязано к плееру. прятать нельзя: игра белеет, звук остаётся. скрины: FLAG_SECURE снимаем."
        box.addView(factory)

        addSection(host, box, "как запускать")
        val how = TextView(host)
        how.setTextColor(MUTED)
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        how.text = "короткий тап по обложке — завод Kenji. удержание / кнопки ниже — наш bootPath + пресет. совмещается с модами на диске (load/<id>)."
        how.setPadding(0, 0, 0, dp(host, 6))
        box.addView(how)

        SettingsBank.ensureCatalog(host)
        val named = LayerBank.launchPreset(host)
        box.addView(toggle(host, "NCE", LayerBank.launchNce(host)) { LayerBank.setLaunchNce(host, it) })
        box.addView(toggle(host, "PPTC", LayerBank.launchPptc(host)) { LayerBank.setLaunchPptc(host, it) })
        box.addView(toggle(host, "Docked TV", LayerBank.launchDocked(host)) { LayerBank.setLaunchDocked(host, it) })
        box.addView(toggle(host, "force NCE+PPTC в extras", LayerBank.forceNce(host)) { LayerBank.setForceNce(host, it) })

        val scales = LinearLayout(host)
        scales.orientation = LinearLayout.HORIZONTAL
        val cur = LayerBank.launchScale(host)
        for (s in floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f)) {
            val label = when (s) {
                0.5f -> "0.5×"
                0.75f -> "0.75×"
                1f -> "1×"
                1.5f -> "1.5×"
                else -> "2×"
            } + if (kotlin.math.abs(cur - s) < 0.01f) " ✓" else ""
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (s != 0.5f) lp.marginStart = dp(host, 4)
            val v = s
            scales.addView(mini(host, label, kotlin.math.abs(cur - s) < 0.01f) {
                LayerBank.setLaunchScale(host, v)
                refill(host, box)
            }, lp)
        }
        box.addView(scales)

        addSection(host, box, "пресет графики")
        val presetRow = LinearLayout(host)
        presetRow.orientation = LinearLayout.VERTICAL
        for (name in SettingsBank.listNamed(host)) {
            val n = name
            val on = n == named
            presetRow.addView(mini(host, n, on) {
                LayerBank.setLaunchPreset(host, if (on) "" else n)
                refill(host, box)
            })
        }
        box.addView(presetRow)

        addSection(host, box, "игры в папке")
        val roms = RomList.list(host)
        if (roms.isEmpty()) {
            val empty = TextView(host)
            empty.setTextColor(MUTED)
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            empty.text = "файлов .nsp/.xci в папке не видно. короткий тап по обложке Kenji всё равно запускает. «Найти на диске» если нет доступа."
            box.addView(empty)
        } else {
            for (rom in roms) {
                box.addView(romRow(host, rom, box))
            }
        }

        addSection(host, box, "совмещать · диск")
        val extras = TextView(host)
        extras.setTextColor(MUTED)
        extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        extras.setTypeface(Typeface.MONOSPACE)
        extras.text = GameExtra.report(host)
        extras.setPadding(0, dp(host, 4), 0, dp(host, 8))
        box.addView(extras)
    }

    private fun refill(host: Activity, box: LinearLayout) {
        fill(host, box)
    }

    private fun romRow(host: Activity, rom: RomList.Rom, box: LinearLayout): LinearLayout {
        val row = LinearLayout(host)
        row.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(CARD)
        bg.cornerRadius = dp(host, 10).toFloat()
        row.background = bg
        val p = dp(host, 10)
        row.setPadding(p, p, p, p)
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(host, 6)
        row.layoutParams = lp

        val t = TextView(host)
        t.text = rom.line()
        t.setTextColor(if (rom.update) WARN else TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        row.addView(t)

        if (rom.update) {
            val b = Button(host)
            b.text = "не запускать · это обновление"
            b.isAllCaps = false
            b.isEnabled = false
            row.addView(b)
        } else {
            val b = Button(host)
            b.text = if (rom.compressed) "запустить (сжат, может не открыть)" else "запустить так"
            b.isAllCaps = false
            b.setOnClickListener {
                val msg = GameLaunch.start(host, rom, LayerBank.forceNce(host))
                Toast.makeText(host, msg, Toast.LENGTH_LONG).show()
            }
            row.addView(b)
            if (rom.titleId.isNotEmpty()) {
                val mem = Button(host)
                mem.text = "запомнить слои и пресет для этой игры"
                mem.isAllCaps = false
                mem.setOnClickListener {
                    LayerBank.saveForGame(host, rom.titleId)
                    Toast.makeText(host, "запомнил ${rom.titleId}", Toast.LENGTH_SHORT).show()
                }
                row.addView(mem)
            }
        }
        return row
    }

    private fun addSection(host: Activity, box: LinearLayout, title: String) {
        val t = TextView(host)
        t.text = title
        t.setTextColor(MINT)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setPadding(0, dp(host, 12), 0, dp(host, 4))
        box.addView(t)
    }

    private fun toggle(host: Activity, label: String, on: Boolean, set: (Boolean) -> Unit): Button {
        var state = on
        val b = Button(host)
        fun paint() {
            b.text = if (state) "$label · вкл" else "$label · выкл"
        }
        paint()
        b.isAllCaps = false
        b.setOnClickListener {
            state = !state
            set(state)
            paint()
        }
        return b
    }

    private fun mini(host: Activity, label: String, on: Boolean, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (on) Color.BLACK else TEXT)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        val d = GradientDrawable()
        d.setColor(if (on) MINT else CARD)
        d.cornerRadius = dp(host, 14).toFloat()
        b.background = d
        b.setOnClickListener { click() }
        b.gravity = Gravity.CENTER
        return b
    }

    private fun dp(c: Activity, v: Int): Int =
        Math.round(v * c.resources.displayMetrics.density)
}
