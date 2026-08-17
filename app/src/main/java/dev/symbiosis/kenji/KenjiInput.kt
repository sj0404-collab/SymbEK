package dev.symbiosis.kenji

import android.view.KeyEvent
import android.view.MotionEvent
import org.kenjinx.android.KenjinxCore

/**
 * Ввод для ядра Kenji.
 *
 * ЧЕГО ЗДЕСЬ НЕ БЫЛО
 *   PlayerActivity запускала игру и на этом заканчивалась: ни касаний, ни
 *   кнопок, ни геймпада. Игра шла, но управлять ей было нечем. Это не
 *   «недоделанная мелочь» - без ввода эмулятор бесполезен.
 *
 * ОТКУДА ВЗЯТЫ ЧИСЛА
 *   Не угаданы и не списаны с Ryujinx для ПК. Вынуты из официального
 *   kenji-nx-v2.1.0-pr.2-standard.apk дизассемблером:
 *
 *     GamePadButtonInputId.<clinit>  - порядок значений enum;
 *     GameController.handleEvent     - каким числом зовут стик;
 *     KenjinxNativeJna               - точные сигнатуры.
 *
 *   Ordinal enum'а и есть код кнопки: официальный код передаёт
 *   `GamePadButtonInputId.X.ordinal()` прямо в inputSetButtonPressed.
 *   Перепутать порядок = нажимать не те кнопки, поэтому список ниже
 *   переписан ровно в том порядке, в каком объявлен в их байткоде.
 */
object KenjiInput {

    // Порядок обязателен: это и есть коды, которые уходят в ядро.
    const val NONE = 0
    const val A = 1
    const val B = 2
    const val X = 3
    const val Y = 4
    const val LEFT_STICK_BUTTON = 5
    const val RIGHT_STICK_BUTTON = 6
    const val L = 7
    const val R = 8
    const val ZL = 9
    const val ZR = 10
    const val DPAD_UP = 11
    const val DPAD_DOWN = 12
    const val DPAD_LEFT = 13
    const val DPAD_RIGHT = 14
    const val MINUS = 15
    const val PLUS = 16

    /** Идентификаторы стиков в inputSetStickAxis. GameController: левый 1, правый 2. */
    const val STICK_LEFT = 1
    const val STICK_RIGHT = 2

    /** Порт, который вернул inputConnectGamepad. -1 = ещё не подключались. */
    @Volatile
    var pad: Int = -1
        private set

    /** Подключить виртуальный геймпад. Официальный код тоже зовёт с 0. */
    fun connect(core: KenjinxCore): Int {
        if (pad >= 0) return pad
        pad = runCatching { core.inputConnectGamepad(0) }.getOrDefault(-1)
        return pad
    }

    fun reset() { pad = -1 }

    fun press(core: KenjinxCore, button: Int) {
        val id = pad
        if (id < 0 || button == NONE) return
        runCatching { core.inputSetButtonPressed(button, id) }
    }

    fun release(core: KenjinxCore, button: Int) {
        val id = pad
        if (id < 0 || button == NONE) return
        runCatching { core.inputSetButtonReleased(button, id) }
    }

    /**
     * Ось стика.
     *
     * Y инвертируется: в официальном handleEvent перед вызовом стоит
     * neg-float. На экране Y растёт вниз, в ядре - вверх; без минуса
     * персонаж идёт в обратную сторону.
     */
    fun stick(core: KenjinxCore, stick: Int, x: Float, y: Float) {
        val id = pad
        if (id < 0) return
        val cx = x.coerceIn(-1f, 1f)
        val cy = y.coerceIn(-1f, 1f)
        runCatching { core.inputSetStickAxis(stick, cx, -cy, id) }
    }

    fun touch(core: KenjinxCore, x: Int, y: Int) {
        runCatching { core.inputSetTouchPoint(x, y) }
    }

    fun touchRelease(core: KenjinxCore) {
        runCatching { core.inputReleaseTouchPoint() }
    }

    /**
     * Физический геймпад: раскладка Android → коды Kenji.
     *
     * Возвращает NONE для клавиш, которые нас не касаются, чтобы система
     * обработала их сама (громкость, «назад» с геймпада и т.п.).
     */
    fun fromKeyCode(code: Int): Int = when (code) {
        KeyEvent.KEYCODE_BUTTON_A -> B      // на Switch A/B зеркальны Xbox
        KeyEvent.KEYCODE_BUTTON_B -> A
        KeyEvent.KEYCODE_BUTTON_X -> Y
        KeyEvent.KEYCODE_BUTTON_Y -> X
        KeyEvent.KEYCODE_BUTTON_L1 -> L
        KeyEvent.KEYCODE_BUTTON_R1 -> R
        KeyEvent.KEYCODE_BUTTON_L2 -> ZL
        KeyEvent.KEYCODE_BUTTON_R2 -> ZR
        KeyEvent.KEYCODE_BUTTON_THUMBL -> LEFT_STICK_BUTTON
        KeyEvent.KEYCODE_BUTTON_THUMBR -> RIGHT_STICK_BUTTON
        KeyEvent.KEYCODE_BUTTON_START -> PLUS
        KeyEvent.KEYCODE_BUTTON_SELECT -> MINUS
        KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
        else -> NONE
    }

    /** Мёртвая зона: без неё стик «плывёт» на изношенном геймпаде. */
    private const val DEAD_ZONE = 0.15f

    private fun dead(v: Float): Float = if (kotlin.math.abs(v) < DEAD_ZONE) 0f else v

    /** Оси физического геймпада. Возвращает true, если событие наше. */
    fun motion(core: KenjinxCore, event: MotionEvent): Boolean {
        // Скобки обязательны: в Kotlin `==` связывает сильнее инфиксного
        // `and`, поэтому без них выражение читается как
        // `source and (SOURCE_JOYSTICK == 0)` и не компилируется вовсе.
        if ((event.source and android.view.InputDevice.SOURCE_JOYSTICK) == 0) return false
        if (pad < 0) return false

        stick(
            core, STICK_LEFT,
            dead(event.getAxisValue(MotionEvent.AXIS_X)),
            dead(event.getAxisValue(MotionEvent.AXIS_Y))
        )
        stick(
            core, STICK_RIGHT,
            dead(event.getAxisValue(MotionEvent.AXIS_Z)),
            dead(event.getAxisValue(MotionEvent.AXIS_RZ))
        )

        // Крестовина на многих геймпадах приходит осями, а не клавишами.
        val hx = event.getAxisValue(MotionEvent.AXIS_HAT_X)
        val hy = event.getAxisValue(MotionEvent.AXIS_HAT_Y)
        hat(core, DPAD_LEFT, hx < -0.5f)
        hat(core, DPAD_RIGHT, hx > 0.5f)
        hat(core, DPAD_UP, hy < -0.5f)
        hat(core, DPAD_DOWN, hy > 0.5f)

        // Курки как оси: у части геймпадов L2/R2 не дают KEYCODE.
        hat(core, ZL, event.getAxisValue(MotionEvent.AXIS_LTRIGGER) > 0.5f ||
            event.getAxisValue(MotionEvent.AXIS_BRAKE) > 0.5f)
        hat(core, ZR, event.getAxisValue(MotionEvent.AXIS_RTRIGGER) > 0.5f ||
            event.getAxisValue(MotionEvent.AXIS_GAS) > 0.5f)
        return true
    }

    /** Состояние удерживается, чтобы не слать press на каждый кадр. */
    private val held = HashSet<Int>()

    private fun hat(core: KenjinxCore, button: Int, down: Boolean) {
        val was = held.contains(button)
        if (down && !was) {
            held.add(button)
            press(core, button)
        } else if (!down && was) {
            held.remove(button)
            release(core, button)
        }
    }

    fun clearHeld() = held.clear()
}
