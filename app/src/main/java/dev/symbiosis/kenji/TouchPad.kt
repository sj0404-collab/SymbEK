package dev.symbiosis.kenji

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot
import org.kenjinx.android.KenjinxCore

/**
 * Экранное управление.
 *
 * Зачем своё, а не библиотека: официальный Kenji тянет radialgamepad и
 * Compose - это десятки мегабайт DEX ради того, что здесь рисуется
 * четырьмя вызовами Canvas. Оболочка задумана лёгкой, а без ввода она
 * всё равно бесполезна, так что дешёвый вариант лучше отсутствующего.
 *
 * Многопальцевый ввод обязателен: держать стик и одновременно жать A -
 * это норма, а не редкость. Каждый палец (pointerId) отслеживается
 * отдельно, поэтому вторым касанием первое не теряется.
 */
@SuppressLint("ViewConstructor")
class TouchPad(
    context: Context,
    private val core: KenjinxCore,
    private val onMenu: () -> Unit
) : View(context) {

    private data class Btn(val id: Int, val label: String, var cx: Float = 0f, var cy: Float = 0f, var r: Float = 0f)

    private val buttons = listOf(
        Btn(KenjiInput.A, "A"),
        Btn(KenjiInput.B, "B"),
        Btn(KenjiInput.X, "X"),
        Btn(KenjiInput.Y, "Y"),
        Btn(KenjiInput.L, "L"),
        Btn(KenjiInput.R, "R"),
        Btn(KenjiInput.ZL, "ZL"),
        Btn(KenjiInput.ZR, "ZR"),
        Btn(KenjiInput.MINUS, "-"),
        Btn(KenjiInput.PLUS, "+"),
        Btn(KenjiInput.DPAD_UP, "▲"),
        Btn(KenjiInput.DPAD_DOWN, "▼"),
        Btn(KenjiInput.DPAD_LEFT, "◀"),
        Btn(KenjiInput.DPAD_RIGHT, "▶"),
    )

    private var stickCx = 0f
    private var stickCy = 0f
    private var stickR = 0f
    private var knobX = 0f
    private var knobY = 0f

    private var menuCx = 0f
    private var menuCy = 0f
    private var menuR = 0f

    /** Какой палец что держит: pointerId → код кнопки, либо STICK/MENU. */
    private val owner = HashMap<Int, Int>()
    private var stickPointer = -1

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(60, 255, 255, 255)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255)
        textAlign = Paint.Align.CENTER
    }

    var opacity: Int = 100
        set(value) {
            field = value.coerceIn(20, 100)
            val a = (field * 255 / 100)
            fill.alpha = (a * 0.25f).toInt()
            edge.alpha = (a * 0.5f).toInt()
            text.alpha = (a * 0.8f).toInt()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val unit = minOf(w, h) / 10f
        val r = unit * 0.75f
        text.textSize = r * 0.8f

        // Правый кластр A/B/X/Y ромбом, как на самой консоли.
        val ax = w - unit * 1.6f
        val ay = h - unit * 2.2f
        place(KenjiInput.A, ax, ay, r)
        place(KenjiInput.B, ax - unit * 1.3f, ay + unit * 1.1f, r)
        place(KenjiInput.X, ax - unit * 1.3f, ay - unit * 1.1f, r)
        place(KenjiInput.Y, ax - unit * 2.6f, ay, r)

        // Крестовина слева.
        val dx = unit * 3.6f
        val dy = h - unit * 2.2f
        place(KenjiInput.DPAD_UP, dx, dy - unit * 1.1f, r * 0.85f)
        place(KenjiInput.DPAD_DOWN, dx, dy + unit * 1.1f, r * 0.85f)
        place(KenjiInput.DPAD_LEFT, dx - unit * 1.1f, dy, r * 0.85f)
        place(KenjiInput.DPAD_RIGHT, dx + unit * 1.1f, dy, r * 0.85f)

        // Плечи по верхним углам.
        place(KenjiInput.L, unit * 1.4f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.ZL, unit * 3.0f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.R, w - unit * 1.4f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.ZR, w - unit * 3.0f, unit * 1.2f, r * 0.9f)

        // Minus / Plus по центру сверху.
        place(KenjiInput.MINUS, w / 2f - unit, unit * 1.0f, r * 0.7f)
        place(KenjiInput.PLUS, w / 2f + unit, unit * 1.0f, r * 0.7f)

        // Левый стик.
        stickR = unit * 1.7f
        stickCx = unit * 2.2f
        stickCy = h - unit * 5.0f
        knobX = stickCx
        knobY = stickCy

        // Кнопка меню - маленькая, по центру снизу, чтобы не мешала.
        menuR = unit * 0.55f
        menuCx = w / 2f
        menuCy = h - unit * 0.9f
    }

    private fun place(id: Int, x: Float, y: Float, r: Float) {
        buttons.firstOrNull { it.id == id }?.apply { cx = x; cy = y; this.r = r }
    }

    override fun onDraw(canvas: Canvas) {
        if (!controlsVisible) {
            // Кнопка меню остаётся всегда, иначе управление не вернуть.
            canvas.drawCircle(menuCx, menuCy, menuR, edge)
            canvas.drawText("≡", menuCx, menuCy + text.textSize * 0.35f, text)
            return
        }
        // Стик.
        canvas.drawCircle(stickCx, stickCy, stickR, edge)
        canvas.drawCircle(knobX, knobY, stickR * 0.42f, fill)

        buttons.forEach { b ->
            canvas.drawCircle(b.cx, b.cy, b.r, fill)
            canvas.drawCircle(b.cx, b.cy, b.r, edge)
            canvas.drawText(b.label, b.cx, b.cy + text.textSize * 0.35f, text)
        }

        canvas.drawCircle(menuCx, menuCy, menuR, edge)
        canvas.drawText("≡", menuCx, menuCy + text.textSize * 0.35f, text)
    }

    private companion object {
        const val OWNER_STICK = -2
        const val OWNER_MENU = -3

        /**
         * Касание, которое ушло В ИГРУ, а не в кнопку.
         *
         * Без него тачскрина у игры не было вовсе: onTouchEvent всегда
         * возвращал true, то есть панель съедала КАЖДОЕ касание экрана,
         * а KenjiInput.touch() не звал никто - функция была написана и
         * осталась мёртвой. Меню, инвентарь и всё, что на Switch тыкают
         * пальцем, не работало.
         */
        const val OWNER_GAME = -4
    }

    /**
     * Показывать ли кнопки.
     *
     * Не View.GONE: скрытая вьюха не получает касаний, и тогда игра
     * тоже осталась бы без тачскрина. Панель остаётся на месте, просто
     * перестаёт рисовать и ловить кнопки - все касания уходят в игру.
     */
    var controlsVisible: Boolean = true
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = event.actionIndex
                grab(event.getPointerId(i), event.getX(i), event.getY(i))
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    val id = event.getPointerId(i)
                    when (owner[id]) {
                        OWNER_STICK -> moveStick(event.getX(i), event.getY(i))
                        OWNER_GAME -> KenjiInput.touch(
                            core, event.getX(i).toInt(), event.getY(i).toInt()
                        )
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val i = event.actionIndex
                letGo(event.getPointerId(i))
            }
        }
        return true
    }

    private fun grab(pointer: Int, x: Float, y: Float) {
        if (hypot(x - menuCx, y - menuCy) <= menuR * 1.6f) {
            owner[pointer] = OWNER_MENU
            onMenu()
            return
        }
        if (controlsVisible && hypot(x - stickCx, y - stickCy) <= stickR * 1.25f) {
            owner[pointer] = OWNER_STICK
            stickPointer = pointer
            moveStick(x, y)
            return
        }
        // Радиус попадания чуть больше нарисованного: пальцем в точный
        // круг не попадают, и «кнопка не нажалась» - самая частая жалоба
        // на экранное управление.
        val hit = if (controlsVisible) {
            buttons.firstOrNull { hypot(x - it.cx, y - it.cy) <= it.r * 1.35f }
        } else null

        if (hit == null) {
            // Мимо кнопок - значит это тычок по самой игре.
            owner[pointer] = OWNER_GAME
            KenjiInput.touch(core, x.toInt(), y.toInt())
            return
        }
        owner[pointer] = hit.id
        KenjiInput.press(core, hit.id)
    }

    private fun letGo(pointer: Int) {
        val what = owner.remove(pointer) ?: return
        when (what) {
            OWNER_MENU -> Unit
            OWNER_GAME -> KenjiInput.touchRelease(core)
            OWNER_STICK -> {
                stickPointer = -1
                knobX = stickCx
                knobY = stickCy
                KenjiInput.stick(core, KenjiInput.STICK_LEFT, 0f, 0f)
                invalidate()
            }
            else -> KenjiInput.release(core, what)
        }
    }

    private fun moveStick(x: Float, y: Float) {
        var dx = (x - stickCx) / stickR
        var dy = (y - stickCy) / stickR
        val len = hypot(dx, dy)
        if (len > 1f) { dx /= len; dy /= len }
        if (abs(dx) < 0.12f) dx = 0f
        if (abs(dy) < 0.12f) dy = 0f
        knobX = stickCx + dx * stickR
        knobY = stickCy + dy * stickR
        KenjiInput.stick(core, KenjiInput.STICK_LEFT, dx, dy)
        invalidate()
    }

    /** Отпустить всё: вызывается при сворачивании, иначе кнопка «залипнет». */
    fun releaseAll() {
        var hadGameTouch = false
        owner.values.forEach { what ->
            if (what >= 0) KenjiInput.release(core, what)
            if (what == OWNER_GAME) hadGameTouch = true
        }
        if (hadGameTouch) KenjiInput.touchRelease(core)
        owner.clear()
        stickPointer = -1
        knobX = stickCx
        knobY = stickCy
        KenjiInput.stick(core, KenjiInput.STICK_LEFT, 0f, 0f)
        KenjiInput.clearHeld()
        invalidate()
    }
}
