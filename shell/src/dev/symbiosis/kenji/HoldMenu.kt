package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Bottom hold-menu on a shelf title. Looks like Kenji's ▶ + ≡ sheet.
 * Settings / presets / layers are separate buttons, each with its own panel.
 */
object HoldMenu {
    const val TAG = "space-hold"
    const val PAGE_BAR = 0
    const val PAGE_SETTINGS = 1
    const val PAGE_PRESETS = 2
    const val PAGE_LAYERS = 3
    const val PAGE_EXTRAS = 4

    fun show(host: Activity, page: Int = PAGE_BAR) {
        try {
            val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return
            var bar = content.findViewWithTag<HoldBar>(TAG)
            if (bar == null) {
                bar = HoldBar(host)
                bar.tag = TAG
                val lp = if (content is FrameLayout)
                    FrameLayout.LayoutParams(-1, -1)
                else ViewGroup.LayoutParams(-1, -1)
                content.addView(bar, lp)
                bar.elevation = 120f
                bar.translationZ = 120f
            }
            bar.visibility = View.VISIBLE
            bar.bringToFront()
            bar.bind()
            bar.open(page)
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "hold-menu", t)
        }
    }

    fun hide(host: Activity) {
        val content = host.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<HoldBar>(TAG)?.let {
            it.visibility = View.GONE
        }
    }

    fun isOpen(host: Activity): Boolean {
        val v = host.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<View>(TAG)
        return v != null && v.visibility == View.VISIBLE
    }

    fun hits(host: Activity, ev: MotionEvent): Boolean {
        val v = host.findViewById<ViewGroup>(android.R.id.content)
            ?.findViewWithTag<HoldBar>(TAG) ?: return false
        return v.visibility == View.VISIBLE && v.hitsSheet(ev)
    }
}

class HoldBar(private val host: Activity) : FrameLayout(host) {
    private val sheet: LinearLayout
    private val handle: View
    private val title: TextView
    private val body: ScrollView
    private val bodyBox: LinearLayout
    private val nav: LinearLayout
    private val tabBtns = ArrayList<LinearLayout>()
    private var page = HoldMenu.PAGE_BAR
    private var rom: RomList.Rom? = null

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)

        sheet = LinearLayout(host)
        sheet.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xF21C1C24.toInt())
        bg.cornerRadii = floatArrayOf(
            dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(), dp(22).toFloat(),
            0f, 0f, 0f, 0f,
        )
        sheet.background = bg
        sheet.setPadding(dp(12), dp(8), dp(12), dp(14))
        sheet.isClickable = true

        handle = View(host)
        val hd = GradientDrawable()
        hd.setColor(0x66FFFFFF)
        hd.cornerRadius = dp(2).toFloat()
        handle.background = hd
        val hlp = LinearLayout.LayoutParams(dp(36), dp(4))
        hlp.gravity = Gravity.CENTER_HORIZONTAL
        hlp.bottomMargin = dp(8)
        sheet.addView(handle, hlp)

        title = TextView(host)
        title.setTextColor(0xFFF2F2F6.toInt())
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, dp(8))
        title.setOnClickListener { cycleRom() }
        sheet.addView(title)

        body = ScrollView(host)
        body.visibility = View.GONE
        bodyBox = LinearLayout(host)
        bodyBox.orientation = LinearLayout.VERTICAL
        body.addView(bodyBox, LinearLayout.LayoutParams(-1, -2))
        val blp = LinearLayout.LayoutParams(-1, 0, 1f)
        blp.bottomMargin = dp(6)
        sheet.addView(body, blp)

        nav = LinearLayout(host)
        nav.orientation = LinearLayout.HORIZONTAL
        nav.gravity = Gravity.CENTER
        sheet.addView(nav, LinearLayout.LayoutParams(-1, -2))

        addTab("▶", "игра", HoldMenu.PAGE_BAR) { play() }
        addTab("⚙", "настройки", HoldMenu.PAGE_SETTINGS) { open(HoldMenu.PAGE_SETTINGS) }
        addTab("◈", "пресеты", HoldMenu.PAGE_PRESETS) { open(HoldMenu.PAGE_PRESETS) }
        addTab("⏳", "слои", HoldMenu.PAGE_LAYERS) { open(HoldMenu.PAGE_LAYERS) }
        addTab("+", "моды", HoldMenu.PAGE_EXTRAS) { open(HoldMenu.PAGE_EXTRAS) }

        val slp = LayoutParams(-1, -2, Gravity.BOTTOM)
        addView(sheet, slp)
    }

    fun hitsSheet(ev: MotionEvent): Boolean {
        val loc = IntArray(2)
        sheet.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x < loc[0] + sheet.width &&
            y >= loc[1] && y < loc[1] + sheet.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        if (hitsSheet(ev)) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            visibility = View.GONE
            return true
        }
        return false
    }

    fun bind() {
        val list = RomList.list(host).filter { !it.update }
        val last = host.getSharedPreferences("kenji_space", 0).getString("hold_rom", "")
        rom = list.firstOrNull { it.file.absolutePath == last } ?: list.firstOrNull()
        paintTitle()
    }

    fun open(which: Int) {
        page = which
        if (which == HoldMenu.PAGE_BAR) {
            body.visibility = View.GONE
            sheet.layoutParams = (sheet.layoutParams as LayoutParams).also {
                it.height = LayoutParams.WRAP_CONTENT
            }
        } else {
            val cap = (resources.displayMetrics.heightPixels * 0.42f).toInt()
            body.visibility = View.VISIBLE
            body.layoutParams = (body.layoutParams as LinearLayout.LayoutParams).also {
                it.height = cap
                it.weight = 0f
            }
            fillPage()
        }
        paintTabs()
        requestLayout()
    }

    private fun play() {
        val r = rom
        if (r == null) {
            Toast.makeText(host, "нет .nsp в папке — короткий тап по обложке Kenji", Toast.LENGTH_LONG).show()
            return
        }
        val msg = GameLaunch.start(host, r, LayerBank.forceNce(host))
        Toast.makeText(host, msg, Toast.LENGTH_LONG).show()
        visibility = View.GONE
    }

    private fun cycleRom() {
        val list = RomList.list(host).filter { !it.update }
        if (list.isEmpty()) return
        val i = list.indexOfFirst { it.file.absolutePath == rom?.file?.absolutePath }
        rom = list[(i + 1).mod(list.size)]
        host.getSharedPreferences("kenji_space", 0).edit()
            .putString("hold_rom", rom?.file?.absolutePath ?: "").commit()
        paintTitle()
        if (page != HoldMenu.PAGE_BAR) fillPage()
    }

    private fun paintTitle() {
        val r = rom
        title.text = when {
            r == null -> "удержание · нет файла в папке"
            else -> r.title
        }
    }

    private fun fillPage() {
        bodyBox.removeAllViews()
        when (page) {
            HoldMenu.PAGE_SETTINGS -> fillSettings()
            HoldMenu.PAGE_PRESETS -> fillPresets()
            HoldMenu.PAGE_LAYERS -> fillLayers()
            HoldMenu.PAGE_EXTRAS -> fillExtras()
        }
    }

    private fun fillSettings() {
        head("настройки игры")
        note("для следующего запуска через ▶. короткий тап по обложке — завод Kenji, без этих extras.")
        bodyBox.addView(toggle("NCE", LayerBank.launchNce(host)) { LayerBank.setLaunchNce(host, it) })
        bodyBox.addView(toggle("PPTC", LayerBank.launchPptc(host)) { LayerBank.setLaunchPptc(host, it) })
        bodyBox.addView(toggle("Docked TV", LayerBank.launchDocked(host)) { LayerBank.setLaunchDocked(host, it) })
        bodyBox.addView(toggle("force NCE+PPTC", LayerBank.forceNce(host)) { LayerBank.setForceNce(host, it) })
        val scales = LinearLayout(host)
        scales.orientation = LinearLayout.HORIZONTAL
        val cur = LayerBank.launchScale(host)
        for (s in floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f)) {
            val lab = when (s) {
                0.5f -> "0.5×"
                0.75f -> "0.75×"
                1f -> "1×"
                1.5f -> "1.5×"
                else -> "2×"
            } + if (kotlin.math.abs(cur - s) < 0.01f) " ✓" else ""
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (s != 0.5f) lp.marginStart = dp(4)
            val v = s
            scales.addView(mini(lab, kotlin.math.abs(cur - s) < 0.01f) {
                LayerBank.setLaunchScale(host, v)
                fillPage()
            }, lp)
        }
        bodyBox.addView(scales)
        rom?.titleId?.takeIf { it.isNotEmpty() }?.let { id ->
            bodyBox.addView(mini("запомнить для ${rom?.title}", false) {
                LayerBank.saveForGame(host, id)
                Toast.makeText(host, "запомнил $id", Toast.LENGTH_SHORT).show()
            })
        }
    }

    private fun fillPresets() {
        head("пресеты графики")
        note("пишет QuickSettings Kenji. в игре сначала пауза, если HUD включён.")
        SettingsBank.ensureCatalog(host)
        val named = LayerBank.launchPreset(host)
        for (name in SettingsBank.listNamed(host)) {
            val n = name
            val on = n == named
            bodyBox.addView(mini(n, on) {
                LayerBank.setLaunchPreset(host, if (on) "" else n)
                val msg = if (SpaceHook.isPlaying()) {
                    GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                } else {
                    SettingsBank.applyNamed(host, n)
                }
                Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                fillPage()
            })
        }
    }

    private fun fillLayers() {
        head("слои в игре")
        note("полка всегда завод Kenji. здесь — что висит поверх игры. заводской Loading не прячем: белый экран.")
        val key = LayerBank.currentPresetKey(host)
        val row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        fun pack(id: String, lab: String, on: Boolean): View {
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (id != "clean") lp.marginStart = dp(4)
            val v = mini(lab, on) {
                LayerBank.applyPreset(host, id)
                SpaceHook.applyLayers(host)
                fillPage()
            }
            row.addView(v, lp)
            return v
        }
        pack("clean", "чистое", key == "clean")
        pack("chip", "часы", key == "chip")
        pack("chip_fps", "часы+FPS", key == "chip_fps")
        pack("full", "полный", key == "full")
        bodyBox.addView(row)
        bodyBox.addView(toggle("⏳ Space чип", LayerBank.chipOn(host)) {
            LayerBank.setChip(host, it); SpaceHook.applyLayers(host)
        })
        bodyBox.addView(toggle("FPS / CPU Space", LayerBank.statsOn(host)) {
            LayerBank.setStats(host, it); SpaceHook.applyLayers(host)
        })
        bodyBox.addView(toggle("пауза + ⚙ Space", LayerBank.hudOn(host)) {
            LayerBank.setHud(host, it); SpaceHook.applyLayers(host)
        })
        val warn = TextView(host)
        warn.setTextColor(0xFFFFB020.toInt())
        warn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        warn.setPadding(0, dp(8), 0, 0)
        warn.text = "заводской Loading / шейдеры — окно плеера Kenji. скрины: FLAG_SECURE снимаем."
        bodyBox.addView(warn)
    }

    private fun fillExtras() {
        head("совмещать · диск")
        note("моды и читы Kenji читает из load/<titleId>/. сейвы — bis/user/save. ничего не копируем.")
        val extras = TextView(host)
        extras.setTextColor(0xFFB8B8C4.toInt())
        extras.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        extras.setTypeface(Typeface.MONOSPACE)
        extras.text = GameExtra.report(host)
        bodyBox.addView(extras)
        val roms = RomList.list(host)
        if (roms.isEmpty()) {
            note("в папке нет .nsp/.xci — «Найти на диске» или + в панели Space.")
        } else {
            head("файлы в папке")
            for (r in roms) {
                val line = TextView(host)
                line.setTextColor(if (r.update) 0xFFFFB020.toInt() else 0xFFF2F2F6.toInt())
                line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                line.text = r.line()
                line.setPadding(0, dp(6), 0, 0)
                line.setOnClickListener {
                    if (!r.update) {
                        rom = r
                        host.getSharedPreferences("kenji_space", 0).edit()
                            .putString("hold_rom", r.file.absolutePath).commit()
                        paintTitle()
                        Toast.makeText(host, "выбрано: ${r.title}", Toast.LENGTH_SHORT).show()
                    }
                }
                bodyBox.addView(line)
            }
        }
    }

    private fun addTab(icon: String, label: String, id: Int, click: () -> Unit) {
        val col = LinearLayout(host)
        col.orientation = LinearLayout.VERTICAL
        col.gravity = Gravity.CENTER
        col.isClickable = true
        col.setPadding(0, dp(4), 0, dp(4))
        val ic = TextView(host)
        ic.text = icon
        ic.gravity = Gravity.CENTER
        ic.setTextColor(0xFFF2F2F6.toInt())
        ic.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        col.addView(ic)
        val lb = TextView(host)
        lb.text = label
        lb.gravity = Gravity.CENTER
        lb.setTextColor(0xFFB8B8C4.toInt())
        lb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        col.addView(lb)
        col.setOnClickListener {
            if (id != HoldMenu.PAGE_BAR && page == id) open(HoldMenu.PAGE_BAR) else click()
        }
        col.tag = id
        tabBtns.add(col)
        nav.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun paintTabs() {
        for (col in tabBtns) {
            val id = col.tag as Int
            val on = page != HoldMenu.PAGE_BAR && page == id
            val ic = col.getChildAt(0) as TextView
            val lb = col.getChildAt(1) as TextView
            ic.setTextColor(if (on) 0xFF5EF0E6.toInt() else 0xFFF2F2F6.toInt())
            lb.setTextColor(if (on) 0xFF5EF0E6.toInt() else 0xFFB8B8C4.toInt())
        }
    }

    private fun head(t: String) {
        val v = TextView(host)
        v.text = t
        v.setTextColor(0xFF5EF0E6.toInt())
        v.setTypeface(Typeface.DEFAULT_BOLD)
        v.setPadding(0, dp(4), 0, dp(4))
        bodyBox.addView(v)
    }

    private fun note(t: String) {
        val v = TextView(host)
        v.text = t
        v.setTextColor(0xFFB8B8C4.toInt())
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        v.setPadding(0, 0, 0, dp(8))
        bodyBox.addView(v)
    }

    private fun toggle(label: String, on: Boolean, set: (Boolean) -> Unit): Button {
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

    private fun mini(label: String, on: Boolean, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (on) Color.BLACK else 0xFFF2F2F6.toInt())
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val d = GradientDrawable()
        d.setColor(if (on) 0xFF5EF0E6.toInt() else 0xFF3A3A44.toInt())
        d.cornerRadius = dp(14).toFloat()
        b.background = d
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
