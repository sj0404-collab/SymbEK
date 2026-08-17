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
import org.kenjinx.android.Kenji

/**
 * A lightweight, real virtual Switch controller and touch screen overlay.
 *
 * The overlay is drawn before the core exists. Native input is forwarded
 * only after PlayerActivity sets [inputEnabled] — calling
 * inputSetTouchPoint before javaInitialize was ending up in the Kenji log
 * and racing CloseEmulation.
 */
@SuppressLint("ViewConstructor")
class TouchPad(
    context: Context,
    private val onMenu: () -> Unit
) : View(context) {
    @Volatile var inputEnabled: Boolean = false

    private data class Btn(
        val id: Int,
        val label: String,
        var cx: Float = 0f,
        var cy: Float = 0f,
        var r: Float = 0f
    )

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
        Btn(KenjiInput.LEFT_STICK_BUTTON, "L3"),
        Btn(KenjiInput.RIGHT_STICK_BUTTON, "R3")
    )

    private var stickCx = 0f
    private var stickCy = 0f
    private var stickR = 0f
    private var knobX = 0f
    private var knobY = 0f
    private var menuCx = 0f
    private var menuCy = 0f
    private var menuR = 0f

    private val owner = HashMap<Int, Int>()
    private val gamePointers = LinkedHashSet<Int>()
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
            val alpha = field * 255 / 100
            fill.alpha = (alpha * 0.25f).toInt()
            edge.alpha = (alpha * 0.5f).toInt()
            text.alpha = (alpha * 0.8f).toInt()
            invalidate()
        }

    var controlsVisible: Boolean = true
        set(value) {
            field = value
            if (!value) releaseAll()
            invalidate()
        }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val unit = minOf(w, h) / 10f
        val r = unit * 0.75f
        text.textSize = r * 0.8f

        val ax = w - unit * 1.6f
        val ay = h - unit * 2.2f
        place(KenjiInput.A, ax, ay, r)
        place(KenjiInput.B, ax - unit * 1.3f, ay + unit * 1.1f, r)
        place(KenjiInput.X, ax - unit * 1.3f, ay - unit * 1.1f, r)
        place(KenjiInput.Y, ax - unit * 2.6f, ay, r)

        val dx = unit * 3.6f
        val dy = h - unit * 2.2f
        place(KenjiInput.DPAD_UP, dx, dy - unit * 1.1f, r * 0.85f)
        place(KenjiInput.DPAD_DOWN, dx, dy + unit * 1.1f, r * 0.85f)
        place(KenjiInput.DPAD_LEFT, dx - unit * 1.1f, dy, r * 0.85f)
        place(KenjiInput.DPAD_RIGHT, dx + unit * 1.1f, dy, r * 0.85f)

        place(KenjiInput.L, unit * 1.4f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.ZL, unit * 3.0f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.R, w - unit * 1.4f, unit * 1.2f, r * 0.9f)
        place(KenjiInput.ZR, w - unit * 3.0f, unit * 1.2f, r * 0.9f)

        place(KenjiInput.MINUS, w / 2f - unit, unit * 1.0f, r * 0.7f)
        place(KenjiInput.PLUS, w / 2f + unit, unit * 1.0f, r * 0.7f)

        stickR = unit * 1.7f
        stickCx = unit * 2.2f
        stickCy = h - unit * 5.0f
        knobX = stickCx
        knobY = stickCy
        place(KenjiInput.LEFT_STICK_BUTTON, stickCx + unit * 2.0f, stickCy + unit * 1.5f, r * 0.62f)
        place(KenjiInput.RIGHT_STICK_BUTTON, w - unit * 4.2f, h - unit * 4.0f, r * 0.62f)

        menuR = unit * 0.55f
        menuCx = w / 2f
        menuCy = h - unit * 0.9f
    }

    private fun place(id: Int, x: Float, y: Float, r: Float) {
        buttons.firstOrNull { it.id == id }?.apply {
            cx = x
            cy = y
            this.r = r
        }
    }

    override fun onDraw(canvas: Canvas) {
        if (!controlsVisible) {
            canvas.drawCircle(menuCx, menuCy, menuR, edge)
            canvas.drawText("≡", menuCx, menuCy + text.textSize * 0.35f, text)
            return
        }

        canvas.drawCircle(stickCx, stickCy, stickR, edge)
        canvas.drawCircle(knobX, knobY, stickR * 0.42f, fill)
        buttons.forEach { button ->
            canvas.drawCircle(button.cx, button.cy, button.r, fill)
            canvas.drawCircle(button.cx, button.cy, button.r, edge)
            canvas.drawText(button.label, button.cx, button.cy + text.textSize * 0.35f, text)
        }
        canvas.drawCircle(menuCx, menuCy, menuR, edge)
        canvas.drawText("≡", menuCx, menuCy + text.textSize * 0.35f, text)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                grab(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                for (index in 0 until event.pointerCount) {
                    val pointer = event.getPointerId(index)
                    when (owner[pointer]) {
                        OWNER_STICK -> moveStick(event.getX(index), event.getY(index))
                        OWNER_GAME -> sendTouch(event.getX(index).toInt(), event.getY(index).toInt())
                    }
                }
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP -> {
                letGo(event.getPointerId(event.actionIndex))
            }
            MotionEvent.ACTION_CANCEL -> releaseAll()
        }
        return true
    }

    private fun grab(pointer: Int, x: Float, y: Float) {
        if (hypot(x - menuCx, y - menuCy) <= menuR * 1.6f) {
            owner[pointer] = OWNER_MENU
            onMenu()
            return
        }
        if (controlsVisible && hypot(x - stickCx, y - stickCy) <= stickR * 1.25f && stickPointer < 0) {
            owner[pointer] = OWNER_STICK
            stickPointer = pointer
            moveStick(x, y)
            return
        }

        val hit = if (controlsVisible) {
            buttons.firstOrNull { button ->
                hypot(x - button.cx, y - button.cy) <= button.r * 1.35f
            }
        } else {
            null
        }

        if (hit == null) {
            owner[pointer] = OWNER_GAME
            gamePointers.add(pointer)
            sendTouch(x.toInt(), y.toInt())
            return
        }
        owner[pointer] = hit.id
        if (inputEnabled) KenjiInput.press(Kenji.core, hit.id)
    }

    private fun letGo(pointer: Int) {
        val what = owner.remove(pointer) ?: return
        when (what) {
            OWNER_MENU -> Unit
            OWNER_GAME -> {
                gamePointers.remove(pointer)
                if (gamePointers.isEmpty()) releaseTouch()
            }
            OWNER_STICK -> {
                if (stickPointer == pointer) stickPointer = -1
                knobX = stickCx
                knobY = stickCy
                sendStick(0f, 0f)
                invalidate()
            }
            else -> if (inputEnabled) KenjiInput.release(Kenji.core, what)
        }
    }

    private fun moveStick(x: Float, y: Float) {
        var dx = (x - stickCx) / stickR
        var dy = (y - stickCy) / stickR
        val length = hypot(dx, dy)
        if (length > 1f) {
            dx /= length
            dy /= length
        }
        if (abs(dx) < 0.12f) dx = 0f
        if (abs(dy) < 0.12f) dy = 0f
        knobX = stickCx + dx * stickR
        knobY = stickCy + dy * stickR
        sendStick(dx, dy)
        invalidate()
    }

    fun releaseAll() {
        if (inputEnabled) {
            val core = Kenji.core
            owner.values.forEach { what ->
                if (what >= 0) KenjiInput.release(core, what)
            }
            if (gamePointers.isNotEmpty()) KenjiInput.touchRelease(core)
            KenjiInput.stick(core, KenjiInput.STICK_LEFT, 0f, 0f)
        }
        owner.clear()
        gamePointers.clear()
        stickPointer = -1
        knobX = stickCx
        knobY = stickCy
        KenjiInput.clearHeld()
        invalidate()
    }

    private fun sendTouch(x: Int, y: Int) {
        if (inputEnabled) KenjiInput.touch(Kenji.core, x, y)
    }

    private fun releaseTouch() {
        if (inputEnabled) KenjiInput.touchRelease(Kenji.core)
    }

    private fun sendStick(x: Float, y: Float) {
        if (inputEnabled) KenjiInput.stick(Kenji.core, KenjiInput.STICK_LEFT, x, y)
    }

    private companion object {
        const val OWNER_STICK = -2
        const val OWNER_MENU = -3
        const val OWNER_GAME = -4
    }
}
