package dev.symbiosis.kenji

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** Native Symbiosis-style home. Official Kenji MainActivity is only the player. */
class HomeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val games = ArrayList<GameRom>()
    private val covers = HashMap<String, Bitmap?>()
    private var index = 0
    private var tab = 0
    private var query = ""
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private lateinit var tabLaunch: TextView
    private lateinit var tabList: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        val pad = dp(14)
        root.setPadding(pad, dp(10), pad, dp(10))
        root.addView(header())
        val tabs = LinearLayout(this)
        tabs.orientation = LinearLayout.HORIZONTAL
        tabLaunch = chip("Лаунчер", true) { show(0) }
        tabList = chip("Список", false) { show(1) }
        tabs.addView(tabLaunch, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(8) })
        tabs.addView(tabList, LinearLayout.LayoutParams(0, -2, 1f))
        val tlp = LinearLayout.LayoutParams(-1, -2)
        tlp.topMargin = dp(10)
        tlp.bottomMargin = dp(8)
        root.addView(tabs, tlp)
        body = FrameLayout(this)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        Thread({
            try {
                AccessFix.repair(this)
                DataSeed.ensure(this)
                SettingsBank.applyDefaultOnce(this)
            } catch (_: Throwable) {
            }
            reload()
        }, "home-seed").start()
    }

    override fun onResume() {
        super.onResume()
        reload()
    }

    private fun header(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val mark = TextView(this)
        mark.text = "◎"
        mark.setTextColor(MINT)
        mark.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        row.addView(mark)
        val title = TextView(this)
        title.text = "  Symbiosis"
        title.setTextColor(TEXT)
        title.setTypeface(Typeface.DEFAULT_BOLD)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
        row.addView(title, LinearLayout.LayoutParams(0, -2, 1f))
        return row
    }

    private fun show(which: Int) {
        tab = which
        paintChip(tabLaunch, which == 0)
        paintChip(tabList, which == 1)
        paint()
    }

    private fun reload() {
        val list = try {
            GameShelf.list(this)
        } catch (_: Throwable) {
            emptyList()
        }
        main.post {
            games.clear()
            games.addAll(list)
            if (index >= games.size) index = 0
            paint()
        }
        Thread({
            for (g in list) {
                if (covers.containsKey(g.path)) continue
                val bmp = try {
                    CoverArt.load(this, g)
                } catch (_: Throwable) {
                    null
                }
                covers[g.path] = bmp
                main.post { paint() }
            }
        }, "covers").start()
    }

    private fun filtered(): List<GameRom> {
        val q = query.trim().lowercase()
        return games.filter { g ->
            q.isEmpty() || g.title.lowercase().contains(q) || g.titleId.lowercase().contains(q)
        }
    }

    private fun paint() {
        body.removeAllViews()
        if (tab == 0) body.addView(launcherPage(), FrameLayout.LayoutParams(-1, -1))
        else body.addView(listPage(), FrameLayout.LayoutParams(-1, -1))
    }

    private fun launcherPage(): View {
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val list = filtered()
        val rom = list.getOrNull(index.coerceIn(0, (list.size - 1).coerceAtLeast(0)))
        box.addView(coverRow(list, rom))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBg()
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val clp = LinearLayout.LayoutParams(-1, -2)
        clp.topMargin = dp(18)
        if (rom == null || !rom.exists) {
            val empty = TextView(this)
            empty.text = "нет файла игры с обложкой"
            empty.setTextColor(MUTED)
            empty.gravity = Gravity.CENTER
            card.addView(empty)
            box.addView(card, clp)
            scroll.addView(box)
            return scroll
        }
        val name = TextView(this)
        name.text = rom.title.uppercase()
        name.setTextColor(TEXT)
        name.setTypeface(Typeface.DEFAULT_BOLD)
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        card.addView(name)
        val sub = TextView(this)
        sub.text = listOf(rom.titleId.ifBlank { "titleId ?" }, BootLog.human(rom.bytes), rom.folder)
            .joinToString(" · ")
        sub.setTextColor(MUTED)
        sub.setPadding(0, dp(4), 0, dp(12))
        card.addView(sub)
        val coverOk = covers[rom.path] != null
        val ready = rom.exists && coverOk && DataSeed.keysOk(this) && DataSeed.firmwareNca(this) >= 5
        val go = TextView(this)
        go.gravity = Gravity.CENTER
        go.setTypeface(Typeface.DEFAULT_BOLD)
        go.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        go.setPadding(dp(18), dp(14), dp(18), dp(14))
        val gd = GradientDrawable()
        gd.cornerRadius = dp(16).toFloat()
        if (ready) {
            go.text = "Запустить"
            go.setTextColor(Color.WHITE)
            gd.setColor(PINK)
            go.setOnClickListener { OfficialLaunch.game(this, rom) }
        } else {
            go.text = when {
                !rom.exists -> "нет файла"
                !coverOk -> "нет обложки"
                !DataSeed.keysOk(this) -> "нет ключей"
                else -> "нет прошивки"
            }
            go.setTextColor(MUTED)
            gd.setColor(0xFF2A2A32.toInt())
        }
        go.background = gd
        card.addView(go, LinearLayout.LayoutParams(-1, -2))
        card.addView(statsGrid(rom), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        val mods = TextView(this)
        mods.setTextColor(MUTED)
        mods.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mods.setPadding(0, dp(10), 0, 0)
        val mid = if (rom.titleId.isNotEmpty()) GameExtra.report(this) else ""
        mods.text = if (mid.contains(rom.titleId) && rom.titleId.isNotEmpty())
            "моды / читы\nмод · ${rom.titleId}"
        else "моды / читы\nнет"
        card.addView(mods)
        box.addView(card, clp)
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        foot.addView(mini("Ядро: Symbiosis"), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(8) })
        foot.addView(mini("CPU: host"), LinearLayout.LayoutParams(0, -2, 1f))
        val flp = LinearLayout.LayoutParams(-1, -2)
        flp.topMargin = dp(10)
        box.addView(foot, flp)
        scroll.addView(box)
        return scroll
    }

    private fun coverRow(list: List<GameRom>, rom: GameRom?): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER
        row.addView(roundBtn("‹") {
            if (list.isNotEmpty()) {
                index = (index - 1 + list.size) % list.size
                paint()
            }
        })
        val frame = FrameLayout(this)
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        val d = GradientDrawable()
        d.setColor(0xFF1A1A22.toInt())
        d.cornerRadius = dp(22).toFloat()
        img.background = d
        img.clipToOutline = true
        val bmp = rom?.let { covers[it.path] }
        if (bmp != null) img.setImageBitmap(bmp)
        val size = dp(196)
        frame.addView(img, FrameLayout.LayoutParams(size, size, Gravity.CENTER))
        val flp = LinearLayout.LayoutParams(size, size)
        flp.marginStart = dp(10)
        flp.marginEnd = dp(10)
        row.addView(frame, flp)
        row.addView(roundBtn("›") {
            if (list.isNotEmpty()) {
                index = (index + 1) % list.size
                paint()
            }
        })
        val wrap = LinearLayout.LayoutParams(-1, -2)
        wrap.topMargin = dp(12)
        row.layoutParams = wrap
        return row
    }

    private fun statsGrid(rom: GameRom): View {
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(stat("время", playLabel(rom)), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r1.addView(stat("запуск", launchLabel(rom)), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r1.addView(stat("прохождение", saveLabel(rom)), LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(r1)
        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(stat("сейв", saveSize(rom)), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r2.addView(stat("фото", photos(rom).toString()), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r2.addView(stat("шейдеры", shaders(rom)), LinearLayout.LayoutParams(0, -2, 1f))
        val r2lp = LinearLayout.LayoutParams(-1, -2)
        r2lp.topMargin = dp(6)
        grid.addView(r2, r2lp)
        return grid
    }

    private fun listPage(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val search = EditText(this)
        search.hint = "Найти игру…"
        search.setHintTextColor(MUTED)
        search.setTextColor(TEXT)
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        search.setSingleLine()
        search.setText(query)
        search.background = cardBg()
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        search.setOnEditorActionListener { v, _, _ ->
            query = v.text?.toString().orEmpty()
            paint()
            true
        }
        wrap.addView(search, LinearLayout.LayoutParams(-1, -2))
        wrap.addView(statusCard(), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val list = filtered()
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = "игр нет. Нажмите «+ Папка» и укажите каталог с NSP/XCI."
            empty.setTextColor(MUTED)
            empty.setPadding(0, dp(16), 0, 0)
            box.addView(empty)
        }
        for ((i, g) in list.withIndex()) {
            box.addView(gameRow(g, i), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        }
        scroll.addView(box)
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(8) })
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.END
        val plus = TextView(this)
        plus.text = "+ Папка"
        plus.gravity = Gravity.CENTER
        plus.setTextColor(Color.WHITE)
        plus.setTypeface(Typeface.DEFAULT_BOLD)
        plus.setPadding(dp(18), dp(12), dp(18), dp(12))
        val pd = GradientDrawable()
        pd.setColor(PINK)
        pd.cornerRadius = dp(22).toFloat()
        plus.background = pd
        plus.setOnClickListener {
            val i = Intent()
            i.setClassName(packageName, "dev.symbiosis.kenji.PickActivity")
            i.putExtra("kind", "games")
            startActivity(i)
        }
        bar.addView(plus)
        wrap.addView(bar, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        return wrap
    }

    private fun statusCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBg()
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        val keys = DataSeed.keysOk(this)
        val nca = DataSeed.firmwareNca(this)
        val n = games.size
        val bytes = games.sumOf { it.bytes }
        val t = TextView(this)
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.text = buildString {
            append(if (keys) "Ключи ✓" else "Ключи ✗")
            append(" · ")
            append(if (nca >= 5) "Прошивка ✓" else "Прошивка ✗")
            append(" · ")
            append(if (n > 0) "Игры ✓" else "Игры ✗")
            append(" · $n игр · ${BootLog.human(bytes)}")
        }
        card.addView(t)
        val chips = HorizontalScrollView(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        GameShelf.folders(this).forEach { f ->
            val count = games.count { File(it.path).parentFile?.absolutePath == f.absolutePath || it.folder == f.name }
            val c = TextView(this)
            c.text = "${f.name} · $count"
            c.setTextColor(MINT)
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            c.setPadding(dp(10), dp(6), dp(10), dp(6))
            val d = GradientDrawable()
            d.setColor(0xFF16161C.toInt())
            d.setStroke(dp(1), 0x335EF0E6)
            d.cornerRadius = dp(14).toFloat()
            c.background = d
            val lp = LinearLayout.LayoutParams(-2, -2)
            lp.marginEnd = dp(6)
            row.addView(c, lp)
        }
        chips.addView(row)
        card.addView(chips, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        return card
    }

    private fun gameRow(g: GameRom, i: Int): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.background = cardBg()
        row.setPadding(dp(10), dp(10), dp(10), dp(10))
        row.gravity = Gravity.CENTER_VERTICAL
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        val d = GradientDrawable()
        d.setColor(0xFF1A1A22.toInt())
        d.cornerRadius = dp(12).toFloat()
        img.background = d
        covers[g.path]?.let { img.setImageBitmap(it) }
        row.addView(img, LinearLayout.LayoutParams(dp(64), dp(64)))
        val col = LinearLayout(this)
        col.orientation = LinearLayout.VERTICAL
        val t = TextView(this)
        t.text = g.title
        t.setTextColor(TEXT)
        t.setTypeface(Typeface.DEFAULT_BOLD)
        col.addView(t)
        val s = TextView(this)
        val cover = if (covers[g.path] != null) "обложка ✓" else "нет обложки"
        s.text = "${g.titleId} · ${BootLog.human(g.bytes)} · $cover"
        s.setTextColor(MUTED)
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        col.addView(s)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(10) })
        row.setOnClickListener {
            index = i
            show(0)
        }
        return row
    }

    private fun playLabel(rom: GameRom): String {
        val n = OfficialLaunch.launches(this, rom.titleId)
        return if (n <= 0) "—" else "$n"
    }

    private fun launchLabel(rom: GameRom): String {
        val at = OfficialLaunch.lastAt(this, rom.titleId)
        if (at <= 0) return "—"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = at
        return String.format("%02d.%02d", cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1)
    }

    private fun saveLabel(rom: GameRom): String =
        if (saveBytes(rom) > 0) "есть сейв" else "нет"

    private fun saveSize(rom: GameRom): String {
        val b = saveBytes(rom)
        return if (b <= 0) "—" else BootLog.human(b)
    }

    private fun saveBytes(rom: GameRom): Long {
        if (rom.titleId.isEmpty()) return 0L
        val roots = listOf(
            File(DataSeed.playHome(this), "bis/user/save"),
            File(DataSeed.playHome(this), "nand/user/save"),
            File(DataSeed.playHome(this), "load/${rom.titleId}"),
        )
        var sum = 0L
        roots.forEach { if (it.exists()) sum += sizeOf(it, 0) }
        return sum
    }

    private fun photos(rom: GameRom): Int {
        val dir = File(DataSeed.playHome(this), "screenshots")
        val kids = dir.listFiles() ?: return 0
        val id = rom.titleId.lowercase()
        return kids.count { it.isFile && (id.isEmpty() || it.name.lowercase().contains(id)) }
    }

    private fun shaders(rom: GameRom): String {
        val dirs = listOf(
            File(DataSeed.playHome(this), "games/${rom.titleId}/cache"),
            File(DataSeed.playHome(this), "bis/system/save"),
        )
        var sum = 0L
        dirs.forEach { if (it.exists()) sum += sizeOf(it, 0) }
        return if (sum <= 0) "—" else BootLog.human(sum)
    }

    private fun sizeOf(dir: File, depth: Int): Long {
        if (depth > 4) return 0L
        var s = 0L
        dir.listFiles()?.forEach { f ->
            s += if (f.isFile) f.length() else sizeOf(f, depth + 1)
        }
        return s
    }

    private fun stat(label: String, value: String): View {
        val v = LinearLayout(this)
        v.orientation = LinearLayout.VERTICAL
        v.background = tileBg()
        v.setPadding(dp(8), dp(8), dp(8), dp(8))
        val a = TextView(this)
        a.text = label
        a.setTextColor(MUTED)
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        val b = TextView(this)
        b.text = value
        b.setTextColor(TEXT)
        b.setTypeface(Typeface.DEFAULT_BOLD)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        v.addView(a)
        v.addView(b)
        return v
    }

    private fun mini(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(MINT)
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        t.background = tileBg()
        return t
    }

    private fun chip(label: String, on: Boolean, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setPadding(dp(8), dp(10), dp(8), dp(10))
        t.setOnClickListener { click() }
        paintChip(t, on)
        return t
    }

    private fun paintChip(t: TextView, on: Boolean) {
        t.setTextColor(if (on) TEXT else MUTED)
        val d = GradientDrawable()
        d.setColor(0xFF16161C.toInt())
        d.setStroke(dp(1), if (on) 0x66FFFFFF else 0x22FFFFFF)
        d.cornerRadius = dp(16).toFloat()
        t.background = d
    }

    private fun roundBtn(label: String, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
        val d = GradientDrawable()
        d.setColor(0xFF1A1A22.toInt())
        d.setStroke(dp(1), 0x22FFFFFF)
        d.cornerRadius = dp(22).toFloat()
        t.background = d
        val lp = LinearLayout.LayoutParams(dp(40), dp(40))
        t.layoutParams = lp
        t.setOnClickListener { click() }
        return t
    }

    private fun cardBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(CARD)
        d.cornerRadius = dp(18).toFloat()
        return d
    }

    private fun tileBg(): GradientDrawable {
        val d = GradientDrawable()
        d.setColor(0xFF16161C.toInt())
        d.setStroke(dp(1), 0x22FFFFFF)
        d.cornerRadius = dp(12).toFloat()
        return d
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)

    companion object {
        private const val BG = 0xFF0E0E14.toInt()
        private const val CARD = 0xFF1C1C24.toInt()
        private const val TEXT = 0xFFF2F2F6.toInt()
        private const val MUTED = 0xFF9A9AA8.toInt()
        private const val MINT = 0xFF5EF0E6.toInt()
        private const val PINK = 0xFFFF4D8D.toInt()
    }
}
