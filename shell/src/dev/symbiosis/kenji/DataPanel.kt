package dev.symbiosis.kenji

import android.app.Activity
import android.content.Intent
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

/**
 * Former top Space bar. Data transfer: any folder name, any path.
 * First launch and non-default locations live here.
 */
class DataPanel(private val host: Activity) : FrameLayout(host) {
    private val sheet: LinearLayout
    private val status: TextView

    init {
        tag = TAG
        isClickable = false
        setBackgroundColor(Color.TRANSPARENT)
        visibility = View.GONE
        sheet = LinearLayout(host)
        sheet.orientation = LinearLayout.VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xF214141A.toInt())
        bg.cornerRadii = floatArrayOf(
            dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(), dp(18).toFloat(),
            0f, 0f, 0f, 0f,
        )
        sheet.background = bg
        sheet.isClickable = true
        sheet.setPadding(dp(14), dp(12), dp(14), dp(16))

        val head = TextView(host)
        head.text = "перенос данных"
        head.setTextColor(0xFF5EF0E6.toInt())
        head.setTypeface(Typeface.DEFAULT_BOLD)
        head.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        sheet.addView(head)

        val note = TextView(host)
        note.setTextColor(0xFFB8B8C4.toInt())
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        note.setPadding(0, dp(4), 0, dp(8))
        note.text = "папка может называться как угодно и лежать где угодно. дефолты Download/ed не обязательны."
        sheet.addView(note)

        status = TextView(host)
        status.setTextColor(0xFFF2F2F6.toInt())
        status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        status.setTypeface(Typeface.MONOSPACE)
        status.setPadding(0, 0, 0, dp(8))
        sheet.addView(status)

        val scroll = ScrollView(host)
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.addView(row("доступ ко всем файлам") { askAccess() })
        box.addView(row("указать папку игр (любое имя)") { pick("games") })
        box.addView(row("указать Eden / прошивку / ключи") { pick("eden") })
        box.addView(row("найти на диске .nsp и prod.keys") { scan() })
        box.addView(row("слои в игре") { HoldMenu.show(host, HoldMenu.PAGE_LAYERS) })
        box.addView(row("починить прошивку и ключи", accent = true) { fix() })
        box.addView(row("закрыть") { close() })
        scroll.addView(box)
        sheet.addView(scroll, LinearLayout.LayoutParams(-1, dp(360)))
        addView(sheet, LayoutParams(-1, -2, Gravity.BOTTOM))
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
            close()
            return true
        }
        return false
    }

    fun open() {
        visibility = View.VISIBLE
        refresh()
        bringToFront()
    }

    fun close() {
        visibility = View.GONE
    }

    fun startScan() {
        open()
        scan()
    }

    fun refresh() {
        val nca = DataSeed.firmwareNca(host)
        val bytes = DataSeed.firmwareBytes(host)
        val home = DataSeed.playHome(host)
        val keys = File(home, "system/prod.keys")
        val games = GameFolder.currentPath(host)
        val eden = DataSeed.edenDir(host)
        status.text = buildString {
            append(if (keys.isFile && keys.length() > 100) "ключи ${BootLog.human(keys.length())}" else "ключей нет")
            append(" · ")
            append(if (nca >= 5) "$nca NCA · ${BootLog.human(bytes)}" else "прошивки нет ($nca NCA)")
            append('\n')
            append("игры: ").append(games.ifBlank { "не заданы" }).append('\n')
            append("Eden: ").append(eden ?: "не задана").append('\n')
            append("Kenji: ").append(home.absolutePath).append('\n')
            append(if (AccessFix.hasAllFiles()) "доступ ко всем файлам: да" else "доступ ко всем файлам: нет")
            append('\n')
            append(FastScan.lastLine)
        }
    }

    private fun askAccess() {
        if (AccessFix.hasAllFiles()) {
            Toast.makeText(host, "доступ уже есть", Toast.LENGTH_SHORT).show()
            return
        }
        AccessFix.askAllFiles(host)
        Toast.makeText(host, "включите «доступ ко всем файлам» и вернитесь", Toast.LENGTH_LONG).show()
    }

    private fun pick(kind: String) {
        val i = Intent()
        i.setClassName(host.packageName, "dev.symbiosis.kenji.PickActivity")
        i.putExtra("kind", kind)
        host.startActivity(i)
    }

    private fun scan() {
        if (!AccessFix.hasAllFiles()) {
            AccessFix.askAllFiles(host)
            Toast.makeText(host, "сначала доступ ко всем файлам, потом ещё раз", Toast.LENGTH_LONG).show()
            return
        }
        status.text = "ищу…"
        Thread({
            val r = try {
                FastScan.run(host)
            } catch (t: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "сканер: ${t.message}", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }
            host.runOnUiThread {
                refresh()
                Toast.makeText(host, r.line(), Toast.LENGTH_LONG).show()
                FastScan.reloadShelf(host, force = true)
                (host.findViewById<android.view.ViewGroup>(android.R.id.content)
                    ?.findViewWithTag<ShelfDeck>(ShelfDeck.TAG))?.fill(true)
            }
        }, "data-scan").start()
    }

    private fun fix() {
        status.text = "чиню…"
        Thread({
            try {
                AccessFix.repair(host)
                DataSeed.ensure(host)
                host.runOnUiThread {
                    refresh()
                    Toast.makeText(host, "готово · ${DataSeed.firmwareNca(host)} NCA", Toast.LENGTH_LONG).show()
                    (host.findViewById<android.view.ViewGroup>(android.R.id.content)
                        ?.findViewWithTag<ShelfDeck>(ShelfDeck.TAG))?.fill(true)
                }
            } catch (t: Throwable) {
                host.runOnUiThread {
                    Toast.makeText(host, "не вышло: ${t.message}", Toast.LENGTH_LONG).show()
                }
            }
        }, "data-fix").start()
    }

    private fun row(label: String, accent: Boolean = false, click: () -> Unit): Button {
        val b = Button(host)
        b.text = label
        b.isAllCaps = false
        b.setTextColor(if (accent) Color.BLACK else 0xFFF2F2F6.toInt())
        val d = GradientDrawable()
        d.setColor(if (accent) 0xFF5EF0E6.toInt() else 0xFF3A3A44.toInt())
        d.cornerRadius = dp(16).toFloat()
        b.background = d
        val lp = LinearLayout.LayoutParams(-1, -2)
        lp.topMargin = dp(6)
        b.layoutParams = lp
        b.setOnClickListener { click() }
        return b
    }

    companion object {
        const val TAG = "space-data"
    }

    private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
}
