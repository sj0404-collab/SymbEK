package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/** Eden-style properties: saves, mods, cheats, DLC, per-game settings. */
class GameProps(private val host: Activity) : FrameLayout(host) {
    private val sheet: LinearLayout
    private val box: LinearLayout
    private var rom: RomList.Rom? = null

    init {
        isClickable = false
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
        sheet = LinearLayout(host)
        sheet.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xF214141A.toInt())
        bg.cornerRadii = floatArrayOf(dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), 0f, 0f, 0f, 0f)
        sheet.background = bg
        sheet.isClickable = true
        sheet.setPadding(dp(14), dp(12), dp(14), dp(16))
        val head = TextView(host)
        head.text = "свойства игры"
        head.setTextColor(0xFF5EF0E6.toInt())
        head.setTypeface(Typeface.DEFAULT_BOLD)
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        sheet.addView(head)
        val scroll = ScrollView(host)
        box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, dp(420)))
        val lp = LayoutParams(-1, -2, Gravity.BOTTOM)
        addView(sheet, lp)
    }

    fun hits(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        sheet.getLocationOnScreen(loc)
        return ev.rawX >= loc[0] && ev.rawX < loc[0] + sheet.width &&
            ev.rawY >= loc[1] && ev.rawY < loc[1] + sheet.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        if (hits(ev)) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            close(); return true
        }
        return false
    }

    fun open(r: RomList.Rom) {
        rom = r
        if (r.titleId.isNotEmpty()) {
            host.getSharedPreferences("kenji_space", 0).edit()
                .putString("focus_title", r.titleId).commit()
        }
        visibility = View.VISIBLE
        fill()
        bringToFront()
    }

    fun close() {
        visibility = View.GONE
    }

    private fun fill() {
        box.removeAllViews()
        val r = rom
        if (r == null) {
            note("игра не выбрана")
            return
        }
        note("${r.title}\n${r.titleId.ifBlank { "нет titleId" }} · ${BootLog.human(r.bytes)}")
        head("сохранения")
        note("Kenji: bis/user/save. Eden: nand/user/save. копируем файлы — индекс Kenji может не подхватить сразу.")
        box.addView(btn("перенести сейвы Eden → Kenji") {
            Toast.makeText(host, GameExtra.transferSaves(host), Toast.LENGTH_LONG).show()
            fill()
        })
        head("моды · читы")
        val extras = TextView(host)
        extras.setTextColor(0xFFB8B8C4.toInt())
        extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        extras.setTypeface(Typeface.MONOSPACE)
        extras.text = GameExtra.report(host)
        box.addView(extras)
        box.addView(btn("папка модов (диск)") {
            val i = android.content.Intent()
            i.setClassName(host.packageName, "dev.symbiosis.kenji.PickActivity")
            i.putExtra("kind", "games")
            host.startActivity(i)
        })
        head("DLC · обновления")
        val dlcs = RomList.list(host).filter { it.update || it.dlc }
        if (dlcs.isEmpty()) note("рядом нет файлов обновлений / DLC")
        else dlcs.forEach { d ->
            val t = TextView(host)
            t.setTextColor(if (d.update) 0xFFFFB020.toInt() else 0xFFF2F2F6.toInt())
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            t.text = d.line()
            t.setPadding(0, dp(4), 0, 0)
            box.addView(t)
        }
        head("настройки этой игры")
        box.addView(tog("NCE", LayerBank.launchNce(host)) { LayerBank.setLaunchNce(host, it) })
        box.addView(tog("PPTC", LayerBank.launchPptc(host)) { LayerBank.setLaunchPptc(host, it) })
        box.addView(tog("Docked", LayerBank.launchDocked(host)) { LayerBank.setLaunchDocked(host, it) })
        box.addView(btn("запомнить для ${r.titleId.ifBlank { "игры" }}") {
            if (r.titleId.isNotEmpty()) LayerBank.saveForGame(host, r.titleId)
            Toast.makeText(host, "запомнил", Toast.LENGTH_SHORT).show()
        })
        box.addView(btn("закрыть") { close() })
    }

    private fun head(t: String) {
        val v = TextView(host)
        v.text = t
        v.setTextColor(0xFF5EF0E6.toInt())
        v.setTypeface(Typeface.DEFAULT_BOLD)
        v.setPadding(0, dp(10), 0, dp(4))
        box.addView(v)
    }

    private fun note(t: String) {
        val v = TextView(host)
        v.text = t
        v.setTextColor(0xFFB8B8C4.toInt())
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.setPadding(0, 0, 0, dp(6))
        box.addView(v)
    }

    private fun btn(label: String, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setOnClickListener { click() }
        return b
    }

    private fun tog(label: String, on: Boolean, set: (Boolean) -> Unit): Button {
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

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
