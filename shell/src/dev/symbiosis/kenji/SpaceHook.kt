package dev.symbiosis.kenji

import android.app.Activity
import android.app.Application
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * In the official player only: a floating presets button.
 * The shelf is HomeActivity. No loaders, no extra windows, no FLAG_SECURE.
 */
object SpaceHook : Application.ActivityLifecycleCallbacks {
    private const val TAG_PRESET = "space-preset"
    private const val MINT = 0xFF5EF0E6.toInt()
    private const val MUTED = 0xFFB8B8C4.toInt()

    @Volatile private var installed = false
    @Volatile private var playing = false
    private val main = Handler(Looper.getMainLooper())

    fun install(app: Application) {
        if (installed) return
        installed = true
        app.registerActivityLifecycleCallbacks(this)
        BootLog.add("SpaceHook: колбэки активности")
    }

    fun isPlaying(): Boolean = playing
    fun waitingForGame(): Boolean = false

    override fun onActivityCreated(a: Activity, b: Bundle?) {
        if (a.javaClass.name == "org.kenjinx.android.MainActivity") {
            playing = false
            BootLog.add("MainActivity.onCreate")
        }
    }

    override fun onActivityResumed(activity: Activity) {
        unlock(activity)
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        activity.window?.decorView?.post {
            try {
                attach(activity)
                apply(activity)
            } catch (t: Throwable) {
                android.util.Log.e("KenjiSpace", "hook", t)
            }
        }
        poll(activity)
    }

    override fun onActivityPaused(activity: Activity) {
        if (activity.javaClass.name != "org.kenjinx.android.MainActivity") return
        main.removeCallbacksAndMessages(activity)
    }

    override fun onActivityStarted(a: Activity) {}
    override fun onActivityStopped(a: Activity) {}
    override fun onActivitySaveInstanceState(a: Activity, o: Bundle) {}
    override fun onActivityDestroyed(a: Activity) {}

    private fun unlock(activity: Activity) {
        try {
            activity.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } catch (_: Throwable) {
        }
    }

    private fun poll(activity: Activity) {
        main.removeCallbacksAndMessages(activity)
        val tick = object : Runnable {
            override fun run() {
                if (activity.isFinishing) return
                try {
                    apply(activity)
                } catch (_: Throwable) {
                }
                main.postAtTime(this, activity, SystemClock.uptimeMillis() + 2500)
            }
        }
        main.postAtTime(tick, activity, SystemClock.uptimeMillis() + 800)
    }

    private fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_PRESET) == null) {
            val fab = PresetFab(activity)
            fab.tag = TAG_PRESET
            content.addView(fab, FrameLayout.LayoutParams(-1, -1))
            fab.elevation = 20f
            fab.visibility = View.GONE
        }
    }

    private fun apply(activity: Activity) {
        unlock(activity)
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        val surface = hasGameSurface(content) || hasGameSurface(activity.window?.decorView)
        if (surface) playing = true
        DataSeed.allowEnsure = !playing
        val preset = content.findViewWithTag<View>(TAG_PRESET) as? PresetFab
        if (playing) {
            preset?.visibility = View.VISIBLE
        } else {
            preset?.hideSheet()
            preset?.visibility = View.GONE
        }
    }

    private fun hasGameSurface(v: View?): Boolean {
        if (v == null) return false
        val n = v.javaClass.name
        val surface = n.contains("SurfaceView") || n.contains("GLSurface") ||
            n.contains("Vulkan", true) || n.contains("TextureView") ||
            n.contains("NativeSurface")
        if (surface && v.width > 200 && v.height > 200) return true
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) if (hasGameSurface(v.getChildAt(i))) return true
        }
        return false
    }

    private class PresetFab(private val host: Activity) : FrameLayout(host) {
        private val sheet: ScrollView
        private val box: LinearLayout

        init {
            isClickable = false
            isFocusable = false
            val btn = TextView(host)
            btn.text = "⚙"
            btn.gravity = Gravity.CENTER
            btn.setTextColor(Color.BLACK)
            btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            val ball = GradientDrawable()
            ball.setColor(MINT)
            ball.cornerRadius = dp(22).toFloat()
            btn.background = ball
            addView(btn, LayoutParams(dp(44), dp(44), Gravity.CENTER_VERTICAL or Gravity.START))
            btn.setOnClickListener { toggle() }
            box = LinearLayout(host)
            box.orientation = LinearLayout.VERTICAL
            box.setPadding(dp(10), dp(10), dp(10), dp(10))
            sheet = ScrollView(host)
            sheet.setBackgroundColor(0xF214141A.toInt())
            sheet.addView(box)
            sheet.visibility = GONE
            val slp = LayoutParams(dp(220), dp(280), Gravity.CENTER_VERTICAL or Gravity.START)
            slp.marginStart = dp(52)
            addView(sheet, slp)
        }

        override fun onTouchEvent(event: MotionEvent): Boolean = false
        override fun onInterceptTouchEvent(ev: MotionEvent): Boolean = false

        fun hideSheet() {
            sheet.visibility = GONE
        }

        private fun toggle() {
            if (sheet.visibility == VISIBLE) {
                sheet.visibility = GONE
                return
            }
            box.removeAllViews()
            val t = TextView(host)
            t.text = "пресеты"
            t.setTextColor(MUTED)
            box.addView(t)
            SettingsBank.ensureCatalog(host)
            for (name in SettingsBank.listNamed(host)) {
                val n = name
                val b = Button(host)
                b.text = n
                b.isAllCaps = false
                b.setOnClickListener {
                    val msg = GamePause.applyThen(host) { SettingsBank.applyNamed(host, n) }
                    Toast.makeText(host, msg, Toast.LENGTH_SHORT).show()
                }
                box.addView(b)
            }
            sheet.visibility = VISIBLE
        }

        private fun dp(v: Int): Int = Math.round(v * resources.displayMetrics.density)
    }
}
