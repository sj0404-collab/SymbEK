package dev.symbiosis.kenji

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/**
 * Native panel on official MainActivity: Eden/Kenji folders, save,
 * presets tab, status overlay. Their game grid stays below.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG = "space-panel"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val BG = 0xFF2A2A32.toInt()
    private const val CARD = 0xFF3A3A44.toInt()
    private const val TEXT = 0xFFF2F2F6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
    }

    override fun onActivityResumed(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        activity.window?.decorView?.post {
            try {
                attach(activity)
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "overlay", t)
            }
        }
        Thread({
            try {
                AccessFix.repair(activity)
                DataSeed.ensure(activity)
                activity.runOnUiThread {
                    try {
                        (activity.findViewById<ViewGroup>(android.R.id.content)
                            ?.findViewWithTag<View>(TAG) as? Panel)?.refresh()
                    } catch (_: Throwable) {
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "bg", t)
            }
        }, "kenji-seed").start()
    }

    override fun onActivityCreated(a: Activity, b: Bundle?) {}
    override fun onActivityStarted(a: Activity) {}
    override fun onActivityPaused(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG) != null) {
            (content.findViewWithTag<View>(TAG) as? Panel)?.refresh()
            return
        }
        val panel = Panel(activity)
        panel.tag = TAG
        val lp = if (content is FrameLayout)
            FrameLayout.LayoutParams(-1, -2, Gravity.TOP)
        else
            ViewGroup.LayoutParams(-1, -2)
        content.addView(panel, lp)
        panel.elevation = 24f
        panel.refresh()
    }

    private class Panel(private val host: Activity) : LinearLayout(host) {
        private val status: TextView
        private val bridges: LinearLayout
        private val presets: LinearLayout
        private val tabBridges: Button
        private val tabPresets: Button
        private var tab = 0

        init {
            orientation = VERTICAL
            setBackgroundColor(BG)
            val pad = dp(10)
            setPadding(pad, dp(8), pad, dp(8))

            val title = TextView(host)
            title.text = "Kenji Space"
            title.setTextColor(TEXT)
            title.setTypeface(Typeface.DEFAULT_BOLD)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            addView(title)

            status = TextView(host)
            status.setTextColor(MUTED)
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            status.setPadding(0, dp(4), 0, dp(6))
            addView(status)

            val tabs = LinearLayout(host)
            tabs.orientation = HORIZONTAL
            tabBridges = pill("Мосты", true) { show(0) }
            tabPresets = pill("Пресеты", false) { show(1) }
            tabs.addView(tabBridges, LayoutParams(0, -2, 1f).also { it.marginEnd = dp(6) })
            tabs.addView(tabPresets, LayoutParams(0, -2, 1f))
            addView(tabs)

            bridges = LinearLayout(host)
            bridges.orientation = VERTICAL
            bridges.addView(rowBtn("Папка Eden/files (оригинал прошивки)") { pick("eden") })
            bridges.addView(rowBtn("Починить всё", accent = true) { save() })
            addView(bridges)

            presets = LinearLayout(host)
            presets.orientation = VERTICAL
            presets.visibility = GONE
            addView(presets)
        }

        fun refresh() {
            val play = DataSeed.playHome(host)
            val keysFile = java.io.File(play, "system/prod.keys")
            val keys = if (keysFile.isFile && keysFile.length() > 100) "ключи ${keysFile.length() / 1024} КБ" else "нет ключей"
            val nca = DataSeed.firmwareNca(host)
            val fw = if (nca >= 5) "$nca NCA · ${DataSeed.firmwareMode(host)}" else "нет прошивки ($nca NCA в bis)"
            val src = DataSeed.firmwareSource(host).ifEmpty { "источник не выбран" }
            val acc = AccessFix.statusLine(host)
            val hunt = FirmwareHunt.lastReport
            status.text = "$keys · $fw\nпрошивка на месте: $src\nярлыки: ${play.absolutePath}/bis\n$acc\n— сканер —\n$hunt"
            fillPresets()
        }

        private fun show(which: Int) {
            tab = which
            bridges.visibility = if (which == 0) VISIBLE else GONE
            presets.visibility = if (which == 1) VISIBLE else GONE
            paintTab(tabBridges, which == 0)
            paintTab(tabPresets, which == 1)
        }

        private fun save() {
            try {
                AccessFix.repair(host)
                if (!AccessFix.hasAllFiles()) AccessFix.askAllFiles(host)
                DataSeed.ensure(host)
                SettingsBank.saveNamed(host, "последние")
                refresh()
                Toast.makeText(host, status.text, Toast.LENGTH_LONG).show()
            } catch (t: Throwable) {
                Toast.makeText(host, "не сохранилось: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }

        private fun pick(kind: String) {
            val i = Intent()
            i.setClassName(host.packageName, "dev.symbiosis.kenji.PickActivity")
            i.putExtra("kind", kind)
            host.startActivity(i)
        }

        private fun fillPresets() {
            presets.removeAllViews()
            SettingsBank.ensureCatalog(host)
            val items = SettingsBank.listNamed(host)
            if (items.isEmpty()) {
                val t = TextView(host)
                t.text = "пресетов нет"
                t.setTextColor(MUTED)
                presets.addView(t)
            } else {
                for (name in items) {
                    val n = name
                    presets.addView(rowBtn(n) {
                        val msg = SettingsBank.applyNamed(host, n)
                        Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                        refresh()
                    })
                }
            }
            presets.addView(rowBtn("Сохранить текущие как пресет", accent = true) {
                val name = "пресет ${System.currentTimeMillis() % 10000}"
                SettingsBank.saveNamed(host, name)
                fillPresets()
                Toast.makeText(host, "сохранён $name", Toast.LENGTH_SHORT).show()
            })
        }

        private fun rowBtn(label: String, accent: Boolean = false, click: () -> Unit): Button {
            val b = pill(label, accent, click)
            val lp = LayoutParams(-1, -2)
            lp.topMargin = dp(6)
            b.layoutParams = lp
            return b
        }

        private fun pill(label: String, accent: Boolean, click: () -> Unit): Button {
            val b = Button(host)
            b.text = label
            b.isAllCaps = false
            paintTab(b, accent)
            b.setOnClickListener { click() }
            return b
        }

        private fun paintTab(b: Button, on: Boolean) {
            b.setTextColor(if (on) Color.BLACK else TEXT)
            val d = GradientDrawable()
            d.setColor(if (on) MINT else CARD)
            d.cornerRadius = dp(18).toFloat()
            b.background = d
        }

        private fun dp(v: Int): Int =
            Math.round(v * resources.displayMetrics.density)
    }
}
