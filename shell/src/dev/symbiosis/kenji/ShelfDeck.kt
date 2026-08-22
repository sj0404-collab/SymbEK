package dev.symbiosis.kenji

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Our shelf over Kenji's grid. Cover tap selects. Play launches GameHost.
 * Factory FABs / R logo stay under this layer.
 */
class ShelfDeck(private val host: Activity) : FrameLayout(host) {
    private val row: LinearLayout
    private val title: TextView
    private val meta: TextView
    private val play: Button
    private val propsBtn: Button
    private val props: GameProps
    private var selected: RomList.Rom? = null
    private var lastCount = -1

    init {
        tag = TAG
        isClickable = true
        try {
            val bmp = host.assets.open("space/pollination.jpg").use { BitmapFactory.decodeStream(it) }
            if (bmp != null) background = BitmapDrawable(resources, bmp)
            else setBackgroundColor(0xFF121018.toInt())
        } catch (_: Throwable) {
            setBackgroundColor(0xFF121018.toInt())
        }

        val col = LinearLayout(host)
        col.orientation = LinearLayout.VERTICAL
        col.setPadding(dp(12), dp(8), dp(12), dp(10))

        val head = LinearLayout(host)
        head.orientation = LinearLayout.HORIZONTAL
        head.gravity = Gravity.CENTER_VERTICAL
        val icon = ImageView(host)
        icon.scaleType = ImageView.ScaleType.CENTER_CROP
        try {
            val ib = host.assets.open("space/icon.png").use { BitmapFactory.decodeStream(it) }
            if (ib != null) icon.setImageBitmap(ib)
        } catch (_: Throwable) {
        }
        val id = GradientDrawable()
        id.setColor(0xFF14141A.toInt())
        id.cornerRadius = dp(10).toFloat()
        icon.background = id
        icon.clipToOutline = false
        head.addView(icon, LinearLayout.LayoutParams(dp(40), dp(40)))
        val brand = TextView(host)
        brand.text = "  Kenji Space"
        brand.setTextColor(0xFF5EF0E6.toInt())
        brand.setTypeface(Typeface.DEFAULT_BOLD)
        brand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        head.addView(brand)
        col.addView(head)

        val hint = TextView(host)
        hint.text = "листайте вбок · обложка выбирает · играет только кнопка"
        hint.setTextColor(0xFFB8B8C4.toInt())
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        hint.setPadding(0, dp(6), 0, dp(8))
        col.addView(hint)

        val sc = HorizontalScrollView(host)
        sc.isHorizontalScrollBarEnabled = false
        row = LinearLayout(host)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        sc.addView(row)
        col.addView(sc, LinearLayout.LayoutParams(-1, dp(210)))

        val dock = LinearLayout(host)
        dock.orientation = LinearLayout.VERTICAL
        val glass = GradientDrawable()
        glass.setColor(0xE616161C.toInt())
        glass.cornerRadius = dp(16).toFloat()
        glass.setStroke(dp(1), 0x665EF0E6)
        dock.background = glass
        dock.setPadding(dp(14), dp(12), dp(14), dp(12))
        val dlp = LinearLayout.LayoutParams(-1, -2)
        dlp.topMargin = dp(10)
        col.addView(dock, dlp)

        title = TextView(host)
        title.setTextColor(0xFFF2F2F6.toInt())
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.text = "выберите игру"
        dock.addView(title)
        meta = TextView(host)
        meta.setTextColor(0xFFB8B8C4.toInt())
        meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        meta.setPadding(0, dp(2), 0, dp(10))
        dock.addView(meta)

        val actions = LinearLayout(host)
        actions.orientation = LinearLayout.HORIZONTAL
        play = Button(host)
        play.text = "▶  играть"
        play.isAllCaps = false
        play.setTextColor(Color.BLACK)
        val pd = GradientDrawable()
        pd.setColor(0xFF5EF0E6.toInt())
        pd.cornerRadius = dp(18).toFloat()
        play.background = pd
        play.setOnClickListener { launch() }
        actions.addView(play, LinearLayout.LayoutParams(0, -2, 1.2f))
        propsBtn = Button(host)
        propsBtn.text = "свойства"
        propsBtn.isAllCaps = false
        propsBtn.setTextColor(0xFFF2F2F6.toInt())
        val sd = GradientDrawable()
        sd.setColor(0xFF3A3A44.toInt())
        sd.cornerRadius = dp(18).toFloat()
        propsBtn.background = sd
        val plp = LinearLayout.LayoutParams(0, -2, 1f)
        plp.marginStart = dp(8)
        actions.addView(propsBtn, plp)
        propsBtn.setOnClickListener {
            val r = selected
            if (r == null) Toast.makeText(host, "сначала обложка", Toast.LENGTH_SHORT).show()
            else props.open(r)
        }
        dock.addView(actions)

        addView(col, LayoutParams(-1, -1))
        props = GameProps(host)
        addView(props, LayoutParams(-1, -1))
        fill()
    }

    fun fill(force: Boolean = false) {
        val roms = RomList.list(host)
        if (!force && roms.size == lastCount && row.childCount > 0) {
            paintDock()
            return
        }
        lastCount = roms.size
        row.removeAllViews()
        if (roms.isEmpty()) {
            val empty = TextView(host)
            empty.setTextColor(0xFFB8B8C4.toInt())
            empty.text = "нет .nsp/.xci — + папка или «Найти на диске»"
            row.addView(empty)
            return
        }
        if (selected == null) {
            selected = roms.firstOrNull { !it.update && !it.dlc } ?: roms.first()
            paintDock()
        }
        for (r in roms) row.addView(card(r))
    }

    private fun card(r: RomList.Rom): View {
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val on = selected?.file?.absolutePath == r.file.absolutePath
        val bg = GradientDrawable()
        bg.setColor(if (r.update || r.dlc) 0xFF2A2A32.toInt() else 0xCC1C1C24.toInt())
        bg.cornerRadius = dp(14).toFloat()
        bg.setStroke(dp(2), if (on) 0xFF5EF0E6.toInt() else 0x33FFFFFF)
        box.background = bg
        box.setPadding(dp(8), dp(8), dp(8), dp(8))
        val face = TextView(host)
        face.gravity = Gravity.CENTER
        face.setTextColor(0xFF5EF0E6.toInt())
        face.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28f)
        face.text = when {
            r.update -> "+"
            r.dlc -> "DLC"
            else -> "◎"
        }
        face.setBackgroundColor(0xFF14141A.toInt())
        box.addView(face, LinearLayout.LayoutParams(dp(120), dp(120)))
        val nm = TextView(host)
        nm.text = r.title
        nm.setTextColor(0xFFF2F2F6.toInt())
        nm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        nm.maxLines = 2
        nm.setPadding(0, dp(6), 0, 0)
        nm.gravity = Gravity.CENTER
        box.addView(nm, LinearLayout.LayoutParams(dp(120), -2))
        val lp = LinearLayout.LayoutParams(-2, -2)
        lp.marginEnd = dp(10)
        box.layoutParams = lp
        box.setOnClickListener {
            selected = r
            paintDock()
            fill(true)
        }
        return box
    }

    private fun paintDock() {
        val r = selected
        if (r == null) {
            title.text = "выберите игру"
            meta.text = ""
            play.isEnabled = false
            return
        }
        title.text = r.title
        val kind = when {
            r.update -> "обновление · не запускать"
            r.dlc -> "DLC · не базовая"
            else -> r.file.extension
        }
        meta.text = "$kind · ${BootLog.human(r.bytes)}\n${r.titleId.ifBlank { "нет titleId в имени" }}"
        play.isEnabled = !r.update && !r.dlc
    }

    private fun launch() {
        val r = selected
        if (r == null) {
            Toast.makeText(host, "обложка сначала", Toast.LENGTH_SHORT).show()
            return
        }
        Toast.makeText(host, GameLaunch.start(host, r, LayerBank.forceNce(host)), Toast.LENGTH_LONG).show()
    }

    companion object {
        const val TAG = "space-shelf"
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}

object FabHide {
    fun run(root: View, depth: Int = 0) {
        if (depth > 16) return
        if (SpaceHook.isSpaceView(root)) return
        val n = root.javaClass.name
        val fab = n.contains("FloatingActionButton") || n.contains("ExtendedFloatingActionButton")
        if (fab) {
            root.visibility = View.GONE
            return
        }
        val dm = root.resources.displayMetrics
        if (root.width in (40 * dm.density).toInt()..(72 * dm.density).toInt() &&
            root.height in (40 * dm.density).toInt()..(72 * dm.density).toInt()
        ) {
            val loc = IntArray(2)
            root.getLocationOnScreen(loc)
            if (loc[1] > dm.heightPixels * 7 / 10 && loc[0] > dm.widthPixels * 5 / 10) {
                root.visibility = View.GONE
                return
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) run(root.getChildAt(i), depth + 1)
        }
    }
}
