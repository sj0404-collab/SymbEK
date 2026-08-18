package dev.symbiosis.kenji

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class HomeActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val games = ArrayList<GameRom>()
    private val covers = HashMap<String, Bitmap?>()
    private var index = 0
    private var tab = 0
    private var query = ""
    private lateinit var root: LinearLayout
    private lateinit var body: FrameLayout
    private val tabs = ArrayList<TextView>()
    private var coverView: ImageView? = null
    private var lastRev = -1L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = BG
        window.navigationBarColor = BG
        askAllFiles()
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(BG)
        val pad = dp(14)
        root.setPadding(pad, dp(10), pad, dp(10))
        root.addView(header())
        root.addView(tabRow())
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
            reload(true)
        }, "home-seed").start()
    }

    override fun onResume() {
        super.onResume()
        val rev = FolderHub.revision(this)
        if (rev != lastRev) reload(true)
    }

    private fun askAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager()) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName"),
                    ),
                )
            } catch (_: Exception) {
            }
        }
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

    private fun tabRow(): View {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val names = arrayOf("Лаунчер", "Список", "Настройки")
        names.forEachIndexed { i, n ->
            val t = chip(n, i == 0) { show(i) }
            tabs.add(t)
            row.addView(t, LinearLayout.LayoutParams(0, -2, 1f).also {
                if (i < names.lastIndex) it.marginEnd = dp(6)
            })
        }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(10)
        lp.bottomMargin = dp(8)
        row.layoutParams = lp
        return row
    }

    private fun show(which: Int) {
        tab = which
        tabs.forEachIndexed { i, v -> paintChip(v, i == which) }
        paint()
    }

    private fun reload(coversToo: Boolean) {
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
        if (!coversToo) return
        Thread({
            for (g in list) {
                if (covers.containsKey(g.path)) continue
                val bmp = try {
                    CoverArt.load(this, g)
                } catch (_: Throwable) {
                    null
                }
                covers[g.path] = bmp
                main.post { applyCover(g.path, bmp) }
            }
        }, "covers").start()
    }

    private fun applyCover(path: String, bmp: Bitmap?) {
        val list = filtered()
        val rom = list.getOrNull(if (list.isEmpty()) 0 else index.coerceIn(0, list.lastIndex))
        if (rom != null && rom.path == path && bmp != null) {
            coverView?.scaleType = ImageView.ScaleType.CENTER_CROP
            coverView?.setImageBitmap(bmp)
        }
    }

    private fun filtered(): List<GameRom> {
        val q = query.trim().lowercase()
        return games.filter {
            q.isEmpty() || it.title.lowercase().contains(q) || it.titleId.lowercase().contains(q) ||
                it.fileName.lowercase().contains(q)
        }
    }

    private fun paint() {
        try {
            body.removeAllViews()
            val page = when (tab) {
                1 -> listPage()
                2 -> settingsPage()
                else -> launcherPage()
            }
            body.addView(page, FrameLayout.LayoutParams(-1, -1))
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "paint tab=$tab", t)
            body.removeAllViews()
            val err = TextView(this)
            err.setTextColor(TEXT)
            err.setPadding(dp(16), dp(16), dp(16), dp(16))
            err.text = "экран не собрался: ${t.javaClass.simpleName}\n${t.message}"
            body.addView(err)
        }
    }

    private fun launcherPage(): View {
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER_HORIZONTAL
        val list = filtered()
        val rom = list.getOrNull(if (list.isEmpty()) 0 else index.coerceIn(0, list.size - 1))
        box.addView(coverRow(list, rom))
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBg()
        card.setPadding(dp(16), dp(16), dp(16), dp(16))
        val clp = LinearLayout.LayoutParams(-1, -2)
        clp.topMargin = dp(18)
        if (rom == null) {
            val empty = TextView(this)
            empty.text = "игр нет. Откройте Список → «+ Папка» и укажите каталог с NSP/XCI. Папку можно сменить в Настройках — список пересоберётся."
            empty.setTextColor(MUTED)
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
        sub.text = listOf(
            rom.titleId.ifBlank { "titleId ?" },
            BootLog.human(rom.bytes),
            File(rom.path).parent ?: rom.folder,
        ).joinToString(" · ")
        sub.setTextColor(MUTED)
        sub.setPadding(0, dp(4), 0, dp(12))
        card.addView(sub)
        val ready = rom.exists && DataSeed.keysOk(this) && DataSeed.firmwareNca(this) >= 5
        card.addView(startBtn(rom, ready))
        card.addView(statsGrid(rom), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) })
        val mods = TextView(this)
        mods.setTextColor(MUTED)
        mods.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        mods.setPadding(0, dp(10), 0, 0)
        mods.text = GameMeta.modsLine(this, rom)
        card.addView(mods)
        box.addView(card, clp)
        val foot = LinearLayout(this)
        foot.orientation = LinearLayout.HORIZONTAL
        val ver = try {
            packageManager.getPackageInfo(packageName, 0).versionName
        } catch (_: Exception) {
            "?"
        }
        foot.addView(mini("Ядро: Kenji-NX 2.1.0-pr.2"), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(8) })
        foot.addView(mini("Space $ver"), LinearLayout.LayoutParams(0, -2, 1f))
        box.addView(foot, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        scroll.addView(box)
        return scroll
    }

    private fun startBtn(rom: GameRom, ready: Boolean): TextView {
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
                !DataSeed.keysOk(this) -> "нет ключей — Настройки"
                else -> "нет прошивки — Настройки"
            }
            go.setTextColor(MUTED)
            gd.setColor(0xFF2A2A32.toInt())
            go.setOnClickListener { show(2) }
        }
        go.background = gd
        return go
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
        val img = ImageView(this)
        img.scaleType = ImageView.ScaleType.CENTER_CROP
        val d = GradientDrawable()
        d.setColor(0xFF1A1A22.toInt())
        d.cornerRadius = dp(22).toFloat()
        img.background = d
        img.clipToOutline = true
        coverView = img
        val bmp = rom?.let { covers[it.path] }
        if (bmp != null) img.setImageBitmap(bmp)
        else img.scaleType = ImageView.ScaleType.CENTER
        img.setOnLongClickListener {
            if (rom != null) {
                index = games.indexOfFirst { it.path == rom.path }.coerceAtLeast(0)
                show(1)
            }
            true
        }
        val size = dp(196)
        val flp = LinearLayout.LayoutParams(size, size)
        flp.marginStart = dp(10)
        flp.marginEnd = dp(10)
        row.addView(img, flp)
        row.addView(roundBtn("›") {
            if (list.isNotEmpty()) {
                index = (index + 1) % list.size
                paint()
            }
        })
        return row
    }

    private fun statsGrid(rom: GameRom): View {
        val grid = LinearLayout(this)
        grid.orientation = LinearLayout.VERTICAL
        val r1 = LinearLayout(this)
        r1.orientation = LinearLayout.HORIZONTAL
        r1.addView(stat("запуски", launches(rom)), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r1.addView(stat("запуск", launchLabel(rom)), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r1.addView(stat("прохождение", if (GameMeta.saveBytes(this, rom) > 0) "есть сейв" else "нет"), LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(r1)
        val r2 = LinearLayout(this)
        r2.orientation = LinearLayout.HORIZONTAL
        r2.addView(stat("сейв", humanOrDash(GameMeta.saveBytes(this, rom))), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r2.addView(stat("фото", GameMeta.photos(this, rom).toString()), LinearLayout.LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
        r2.addView(stat("шейдеры", humanOrDash(GameMeta.shaderBytes(this, rom))), LinearLayout.LayoutParams(0, -2, 1f))
        grid.addView(r2, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) })
        return grid
    }

    private fun listPage(): View {
        val wrap = LinearLayout(this)
        wrap.orientation = LinearLayout.VERTICAL
        val search = EditText(this)
        search.hint = "Найти игру…"
        search.setHintTextColor(MUTED)
        search.setTextColor(TEXT)
        search.setSingleLine()
        search.setText(query)
        search.background = cardBg()
        search.setPadding(dp(14), dp(10), dp(14), dp(10))
        search.setOnEditorActionListener { v, _, _ ->
            query = v.text?.toString().orEmpty()
            paint()
            true
        }
        wrap.addView(search)
        wrap.addView(statusCard(), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(10) })
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        val list = filtered()
        if (list.isEmpty()) {
            val empty = TextView(this)
            empty.text = "игр нет. Нажмите «+ Папка» — сработает любая директория с NSP/XCI."
            empty.setTextColor(MUTED)
            empty.setPadding(0, dp(16), 0, 0)
            box.addView(empty)
        }
        list.forEachIndexed { i, g ->
            box.addView(gameRow(g, i), LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        }
        scroll.addView(box)
        wrap.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).also { it.topMargin = dp(8) })
        val bar = LinearLayout(this)
        bar.orientation = LinearLayout.HORIZONTAL
        bar.gravity = Gravity.END or Gravity.CENTER_VERTICAL
        bar.addView(pill("Сейвы", false) { pick("saves") }, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(8) })
        bar.addView(pill("+ Папка", true) { pick("games") })
        wrap.addView(bar, LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(8) })
        return wrap
    }

    private fun settingsPage(): View {
        val scroll = ScrollView(this)
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        try {
            val keys = DataSeed.keysOk(this)
            val nca = DataSeed.firmwareNca(this)
            box.addView(infoCard(buildString {
                append(if (keys) "Ключи ✓" else "Ключи ✗")
                append(" · ")
                append(if (nca >= 5) "Прошивка ✓ $nca NCA" else "Прошивка ✗")
                append(" · игр ${games.size}\n")
                append(DataSeed.playHome(this@HomeActivity).absolutePath)
            }))
            box.addView(section("Папки — можно сменить на любые. Список и прошивка пересоберутся."))
            val gamesPath = FolderHub.gamesDirs(this).joinToString("\n") { it.absolutePath }.ifBlank { "не задано" }
            box.addView(pathRow("Игры (NSP/XCI)", gamesPath) { pick("games") })
            box.addView(pathRow("Eden/files (ключи + прошивка)", FolderHub.edenPath(this)) { pick("eden") })
            box.addView(pathRow("Сейвы", FolderHub.savesDir(this).absolutePath) { pick("saves") })
            FolderHub.gamesDirs(this).forEach { f ->
                box.addView(
                    pill("убрать ${f.name}", false) {
                        FolderHub.removeGamesDir(this, f.absolutePath)
                        reload(true)
                    },
                    LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) },
                )
            }
            box.addView(
                pill("Починить всё", true) {
                    Toast.makeText(this, "чиню…", Toast.LENGTH_SHORT).show()
                    Thread({
                        FolderHub.applyAfterFolderChange(this)
                        main.post {
                            reload(true)
                            Toast.makeText(this, "готово · ${DataSeed.firmwareNca(this)} NCA", Toast.LENGTH_LONG).show()
                        }
                    }, "fix").start()
                },
                LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(12) },
            )
            box.addView(section("Пресеты (пишутся в настройки Kenji)"))
            try {
                SettingsBank.ensureCatalog(this)
                SettingsBank.listNamed(this).forEach { name ->
                    val n = name
                    box.addView(
                        pill(n, false) {
                            Toast.makeText(this, SettingsBank.applyNamed(this, n), Toast.LENGTH_SHORT).show()
                        },
                        LinearLayout.LayoutParams(-1, -2).also { it.topMargin = dp(6) },
                    )
                }
            } catch (t: Throwable) {
                box.addView(section("пресеты не прочитались: ${t.message}"))
            }
        } catch (t: Throwable) {
            android.util.Log.e("KenjiSpace", "settings", t)
            box.addView(section("настройки: ${t.message}"))
        }
        scroll.addView(box)
        return scroll
    }

    private fun statusCard(): View {
        val card = LinearLayout(this)
        card.orientation = LinearLayout.VERTICAL
        card.background = cardBg()
        card.setPadding(dp(12), dp(12), dp(12), dp(12))
        val t = TextView(this)
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val n = games.size
        t.text = "${if (DataSeed.keysOk(this)) "Ключи ✓" else "Ключи ✗"} · " +
            "${if (DataSeed.firmwareNca(this) >= 5) "Прошивка ✓" else "Прошивка ✗"} · " +
            "${if (n > 0) "Игры ✓" else "Игры ✗"} · $n игр · ${BootLog.human(games.sumOf { it.bytes })}"
        card.addView(t)
        val chips = HorizontalScrollView(this)
        val row = LinearLayout(this)
        FolderHub.gamesDirs(this).forEach { f ->
            val c = TextView(this)
            val count = games.count { File(it.path).parentFile?.let { p -> p.absolutePath.startsWith(f.absolutePath) } == true }
            c.text = "${f.name} · $count"
            c.setTextColor(MINT)
            c.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            c.setPadding(dp(10), dp(6), dp(10), dp(6))
            val d = GradientDrawable()
            d.setColor(0xFF16161C.toInt())
            d.setStroke(dp(1), 0x335EF0E6)
            d.cornerRadius = dp(14).toFloat()
            c.background = d
            row.addView(c, LinearLayout.LayoutParams(-2, -2).also { it.marginEnd = dp(6) })
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
        s.text = "${g.titleId.ifBlank { "без id" }} · ${BootLog.human(g.bytes)}\n${g.path}"
        s.setTextColor(MUTED)
        s.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        col.addView(s)
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f).also { it.marginStart = dp(10) })
        row.setOnClickListener {
            index = games.indexOfFirst { it.path == g.path }.takeIf { it >= 0 } ?: i
            show(0)
        }
        return row
    }

    private fun infoCard(text: String): View {
        val t = TextView(this)
        t.text = text
        t.setTextColor(TEXT)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        t.background = cardBg()
        t.setPadding(dp(12), dp(12), dp(12), dp(12))
        return t
    }

    private fun section(text: String): TextView {
        val t = TextView(this)
        t.text = text
        t.setTextColor(MUTED)
        t.setPadding(0, dp(14), 0, dp(6))
        return t
    }

    private fun pathRow(label: String, value: String, click: () -> Unit): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = cardBg()
        box.setPadding(dp(12), dp(10), dp(12), dp(10))
        val a = TextView(this)
        a.text = label
        a.setTextColor(MINT)
        a.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        val b = TextView(this)
        b.text = value
        b.setTextColor(TEXT)
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        box.addView(a)
        box.addView(b)
        box.setOnClickListener { click() }
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(8)
        box.layoutParams = lp
        return box
    }

    private fun pick(kind: String) {
        val i = Intent()
        i.setClassName(packageName, "dev.symbiosis.kenji.PickActivity")
        i.putExtra("kind", kind)
        startActivity(i)
    }

    private fun launches(rom: GameRom): String {
        val n = OfficialLaunch.launches(this, rom.titleId)
        return if (n <= 0) "—" else n.toString()
    }

    private fun launchLabel(rom: GameRom): String {
        val at = OfficialLaunch.lastAt(this, rom.titleId)
        if (at <= 0L) return "—"
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = at
        return String.format("%02d.%02d", cal.get(java.util.Calendar.DAY_OF_MONTH), cal.get(java.util.Calendar.MONTH) + 1)
    }

    private fun humanOrDash(b: Long): String = if (b <= 0) "—" else BootLog.human(b)

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
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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

    private fun pill(label: String, accent: Boolean, click: () -> Unit): TextView {
        val t = TextView(this)
        t.text = label
        t.gravity = Gravity.CENTER
        t.setTypeface(Typeface.DEFAULT_BOLD)
        t.setTextColor(if (accent) Color.WHITE else TEXT)
        t.setPadding(dp(18), dp(12), dp(18), dp(12))
        val d = GradientDrawable()
        d.setColor(if (accent) PINK else 0xFF2A2A32.toInt())
        d.cornerRadius = dp(22).toFloat()
        t.background = d
        t.setOnClickListener { click() }
        return t
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
        t.layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
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
