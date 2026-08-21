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

/**
 * Eden-style in-game overlay. Writes official Kenji QuickSettings.
 * Not a stub: every toggle hits the same prefs Kenji reads.
 */
class OverlayPanel(private val host: Activity) : FrameLayout(host) {
    private val sheet: LinearLayout
    private val box: LinearLayout
    private var page = 0

    init {
        isClickable = false
        isFocusable = false
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE

        sheet = LinearLayout(host)
        sheet.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xF214141A.toInt())
        bg.cornerRadii = floatArrayOf(dp(16).toFloat(), dp(16).toFloat(), 0f, 0f, 0f, 0f, dp(16).toFloat(), dp(16).toFloat())
        sheet.background = bg
        sheet.isClickable = true
        sheet.setPadding(dp(12), dp(12), dp(12), dp(16))

        val head = TextView(host)
        head.text = "Space · оверлей"
        head.setTextColor(0xFF5EF0E6.toInt())
        head.setTypeface(Typeface.DEFAULT_BOLD)
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        sheet.addView(head)

        val tabs = LinearLayout(host)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabs.addView(tab("индик.", 0), LinearLayout.LayoutParams(0, -2, 1f))
        tabs.addView(tab("эмул.", 1), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(4) })
        tabs.addView(tab("графика", 2), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(4) })
        tabs.addView(tab("ввод", 3), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(4) })
        sheet.addView(tabs)

        val scroll = ScrollView(host)
        box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        scroll.addView(box)
        val slp = LinearLayout.LayoutParams(-1, 0, 1f)
        slp.topMargin = dp(8)
        sheet.addView(scroll, slp)

        val lp = LayoutParams(dp(300), (resources.displayMetrics.heightPixels * 0.72f).toInt(), Gravity.END or Gravity.CENTER_VERTICAL)
        lp.marginEnd = dp(8)
        addView(sheet, lp)
    }

    fun hits(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        sheet.getLocationOnScreen(loc)
        val x = ev.rawX
        val y = ev.rawY
        return x >= loc[0] && x < loc[0] + sheet.width && y >= loc[1] && y < loc[1] + sheet.height
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (visibility != View.VISIBLE) return false
        if (hits(ev)) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            close()
            return true
        }
        return false
    }

    fun toggle() {
        if (visibility == View.VISIBLE) close() else open()
    }

    fun open() {
        visibility = View.VISIBLE
        fill()
        bringToFront()
    }

    fun close() {
        visibility = View.GONE
    }

    private fun tab(label: String, id: Int): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        paintMini(b, false)
        b.setOnClickListener {
            page = id
            fill()
        }
        return b
    }

    private fun fill() {
        box.removeAllViews()
        when (page) {
            0 -> fillIndicators()
            1 -> fillEmu()
            2 -> fillGfx()
            else -> fillInput()
        }
    }

    private fun fillIndicators() {
        note("что висит на чипе. скрыть здесь или утащить чип за край. заводской Loading Kenji не трогаем.")
        box.addView(tog("часы (дата · время)", LayerBank.chipOn(host)) {
            LayerBank.setChip(host, it); SpaceHook.applyLayers(host)
        })
        box.addView(tog("время сессии", LayerBank.sessionOn(host)) {
            LayerBank.setSession(host, it); SpaceHook.applyLayers(host)
        })
        box.addView(tog("батарея", LayerBank.batteryOn(host)) {
            LayerBank.setBattery(host, it); SpaceHook.applyLayers(host)
        })
        box.addView(tog("FPS / CPU", LayerBank.statsOn(host)) {
            LayerBank.setStats(host, it); SpaceHook.applyLayers(host)
        })
        box.addView(tog("шестерёнка ⚙", LayerBank.gearOn(host)) {
            LayerBank.setGear(host, it)
            if (!it) close()
            SpaceHook.applyLayers(host)
        })
    }

    private fun fillEmu() {
        note("пишет QuickSettings Kenji. ядро читает их с диска при следующем apply / паузе.")
        box.addView(tog("NCE", SettingsBank.nceOf(host)) {
            SettingsBank.setFlag(host, "useNce", it); LayerBank.setLaunchNce(host, it)
        })
        box.addView(tog("PPTC", SettingsBank.pptcOf(host)) {
            SettingsBank.setFlag(host, "enablePptc", it); LayerBank.setLaunchPptc(host, it)
        })
        box.addView(tog("Low-Power PPTC", SettingsBank.flag(host, "enableLowPowerPptc", false)) {
            SettingsBank.setFlag(host, "enableLowPowerPptc", it)
        })
        box.addView(tog("Shader cache", SettingsBank.flag(host, "enableShaderCache", true)) {
            SettingsBank.setFlag(host, "enableShaderCache", it)
        })
        box.addView(tog("JIT cache eviction", SettingsBank.flag(host, "enableJitCacheEviction", false)) {
            SettingsBank.setFlag(host, "enableJitCacheEviction", it)
        })
        box.addView(tog("Fs integrity", SettingsBank.flag(host, "enableFsIntegrityChecks", false)) {
            SettingsBank.setFlag(host, "enableFsIntegrityChecks", it)
        })
        box.addView(tog("Ignore missing services", SettingsBank.flag(host, "ignoreMissingServices", false)) {
            SettingsBank.setFlag(host, "ignoreMissingServices", it)
        })
        head("DRAM")
        val mem = LinearLayout(host)
        mem.orientation = LinearLayout.HORIZONTAL
        val curM = SettingsBank.memOf(host)
        listOf(0 to "4 ГиБ", 1 to "6 ГиБ", 2 to "8 ГиБ").forEach { (id, lab) ->
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (id != 0) lp.marginStart = dp(4)
            mem.addView(mini(lab, curM == id) {
                SettingsBank.setMem(host, id); fill()
            }, lp)
        }
        box.addView(mem)
        head("Memory manager")
        val mm = LinearLayout(host)
        mm.orientation = LinearLayout.HORIZONTAL
        val curMode = SettingsBank.modeOf(host)
        listOf(0 to "Soft", 1 to "Host", 2 to "Unchecked").forEach { (id, lab) ->
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (id != 0) lp.marginStart = dp(4)
            mm.addView(mini(lab, curMode == id) {
                SettingsBank.setMode(host, id); fill()
            }, lp)
        }
        box.addView(mm)
    }

    private fun fillGfx() {
        note("масштаб и Docked — те же ключи, что Quick Settings Kenji.")
        box.addView(tog("Docked TV", SettingsBank.dockedOf(host)) {
            SettingsBank.setFlag(host, "enableDocked", it)
            LayerBank.setLaunchDocked(host, it)
        })
        val scales = LinearLayout(host)
        scales.orientation = LinearLayout.HORIZONTAL
        val cur = SettingsBank.scaleOf(host)
        for (s in floatArrayOf(0.5f, 0.75f, 1f, 1.5f, 2f)) {
            val lab = when (s) {
                0.5f -> "0.5×"
                0.75f -> "0.75×"
                1f -> "1×"
                1.5f -> "1.5×"
                else -> "2×"
            }
            val lp = LinearLayout.LayoutParams(0, -2, 1f)
            if (s != 0.5f) lp.marginStart = dp(4)
            val v = s
            scales.addView(mini(lab, kotlin.math.abs(cur - v) < 0.01f) {
                LayerBank.setLaunchScale(host, v)
                SettingsBank.applyScale(host, v, SettingsBank.dockedOf(host))
                fill()
            }, lp)
        }
        box.addView(scales)
        head("пресеты")
        SettingsBank.ensureCatalog(host)
        for (name in SettingsBank.listNamed(host)) {
            val n = name
            box.addView(mini(n, n == LayerBank.launchPreset(host)) {
                LayerBank.setLaunchPreset(host, n)
                val msg = GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                fill()
            })
        }
    }

    private fun fillInput() {
        note("ядро Kenji само читает Android InputDevice. ремап кнопок ядра не подменяем — это не заглушка.")
        val pause = Button(host)
        pause.isAllCaps = false
        pause.text = if (GamePause.paused) "▶ продолжить" else "❚❚ пауза"
        pause.setOnClickListener {
            Toast.makeText(host, GamePause.toggle(host), Toast.LENGTH_SHORT).show()
            fill()
        }
        box.addView(pause)
        box.addView(tog("Motion", SettingsBank.flag(host, "enableMotion", false)) {
            SettingsBank.setFlag(host, "enableMotion", it)
            SettingsBank.setFlag(host, "enableMotionControl", it)
        })
        box.addView(tog("Rumble", SettingsBank.flag(host, "enableRumble", true)) {
            SettingsBank.setFlag(host, "enableRumble", it)
        })
        box.addView(tog("Touch screen", SettingsBank.flag(host, "enableTouch", true)) {
            SettingsBank.setFlag(host, "enableTouch", it)
            SettingsBank.setFlag(host, "enableTouchScreen", it)
        })
        val how = TextView(host)
        how.setTextColor(0xFFB8B8C4.toInt())
        how.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        how.setPadding(0, dp(8), 0, 0)
        how.text = "геймпад: системный. раскладку меняйте в Bluetooth / на самом паде. Compose-меню Kenji с полки (⚙ Search) — заводские Input settings."
        box.addView(how)
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
        v.setPadding(0, 0, 0, dp(8))
        box.addView(v)
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

    private fun mini(label: String, on: Boolean, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        paintMini(b, on)
        b.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        return b
    }

    private fun paintMini(b: Button, on: Boolean) {
        b.setTextColor(if (on) Color.BLACK else 0xFFF2F2F6.toInt())
        val d = GradientDrawable()
        d.setColor(if (on) 0xFF5EF0E6.toInt() else 0xFF3A3A44.toInt())
        d.cornerRadius = dp(12).toFloat()
        b.background = d
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
