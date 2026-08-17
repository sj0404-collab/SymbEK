package dev.symbiosis.kenji

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.kenjinx.android.KenjinxCore
import kotlin.math.abs

/**
 * Android input -> the official Kenji gamepad ABI.
 *
 * The core keeps a virtual gamepad driver. SetButton/SetStick changes that
 * driver's state; inputUpdate() (pumped by PlayerActivity) transfers it into
 * the emulated HID service. Keeping those two responsibilities separate is
 * required by the original Android port.
 */
object KenjiInput {
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

    const val STICK_LEFT = 1
    const val STICK_RIGHT = 2

    @Volatile
    var pad: Int = -1
        private set

    fun connect(core: KenjinxCore): Int {
        if (pad >= 0) return pad
        val connected = runCatching { core.inputConnectGamepad(0) }.getOrDefault(-1)
        pad = connected
        return connected
    }

    fun reset() {
        pad = -1
        held.clear()
    }

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

    fun stick(core: KenjinxCore, stick: Int, x: Float, y: Float) {
        val id = pad
        if (id < 0) return
        runCatching {
            core.inputSetStickAxis(
                stick,
                x.coerceIn(-1f, 1f),
                -y.coerceIn(-1f, 1f),
                id
            )
        }
    }

    fun touch(core: KenjinxCore, x: Int, y: Int) {
        runCatching { core.inputSetTouchPoint(x, y) }
    }

    fun touchRelease(core: KenjinxCore) {
        runCatching { core.inputReleaseTouchPoint() }
    }

    fun fromKeyCode(code: Int): Int = when (code) {
        // Android gamepads use the Xbox physical layout. Kenji expects the
        // Switch semantic layout, so A/B and X/Y are intentionally crossed.
        KeyEvent.KEYCODE_BUTTON_A -> B
        KeyEvent.KEYCODE_BUTTON_B -> A
        KeyEvent.KEYCODE_BUTTON_X -> Y
        KeyEvent.KEYCODE_BUTTON_Y -> X
        KeyEvent.KEYCODE_BUTTON_L1 -> L
        KeyEvent.KEYCODE_BUTTON_R1 -> R
        KeyEvent.KEYCODE_BUTTON_L2 -> ZL
        KeyEvent.KEYCODE_BUTTON_R2 -> ZR
        KeyEvent.KEYCODE_BUTTON_THUMBL,
        KeyEvent.KEYCODE_BUTTON_11 -> LEFT_STICK_BUTTON
        KeyEvent.KEYCODE_BUTTON_THUMBR,
        KeyEvent.KEYCODE_BUTTON_12 -> RIGHT_STICK_BUTTON
        KeyEvent.KEYCODE_BUTTON_START -> PLUS
        KeyEvent.KEYCODE_BUTTON_SELECT -> MINUS
        KeyEvent.KEYCODE_DPAD_UP -> DPAD_UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DPAD_DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> DPAD_LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> DPAD_RIGHT
        else -> NONE
    }

    private const val DEAD_ZONE = 0.15f
    private fun dead(value: Float): Float = if (abs(value) < DEAD_ZONE) 0f else value

    /** Physical controller axes, including RX/RY devices used by Xbox pads. */
    fun motion(core: KenjinxCore, event: MotionEvent): Boolean {
        if ((event.source and InputDevice.SOURCE_CLASS_JOYSTICK) == 0) return false
        if (pad < 0) return false

        val device = event.device
        fun hasAxis(axis: Int): Boolean =
            device?.getMotionRange(axis, InputDevice.SOURCE_CLASS_JOYSTICK) != null ||
                device?.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK) != null

        fun axis(axis: Int): Float = if (hasAxis(axis)) event.getAxisValue(axis) else 0f

        val rightX = if (hasAxis(MotionEvent.AXIS_RX)) MotionEvent.AXIS_RX else MotionEvent.AXIS_Z
        val rightY = if (hasAxis(MotionEvent.AXIS_RY)) MotionEvent.AXIS_RY else MotionEvent.AXIS_RZ

        stick(core, STICK_LEFT, dead(axis(MotionEvent.AXIS_X)), dead(axis(MotionEvent.AXIS_Y)))
        stick(core, STICK_RIGHT, dead(axis(rightX)), dead(axis(rightY)))

        val rightUsesZ = rightX == MotionEvent.AXIS_Z
        val rightUsesRz = rightY == MotionEvent.AXIS_RZ
        val leftTrigger = when {
            hasAxis(MotionEvent.AXIS_LTRIGGER) -> axis(MotionEvent.AXIS_LTRIGGER)
            hasAxis(MotionEvent.AXIS_BRAKE) -> axis(MotionEvent.AXIS_BRAKE)
            !rightUsesZ && hasAxis(MotionEvent.AXIS_Z) -> axis(MotionEvent.AXIS_Z)
            else -> 0f
        }
        val rightTrigger = when {
            hasAxis(MotionEvent.AXIS_RTRIGGER) -> axis(MotionEvent.AXIS_RTRIGGER)
            hasAxis(MotionEvent.AXIS_GAS) -> axis(MotionEvent.AXIS_GAS)
            !rightUsesRz && hasAxis(MotionEvent.AXIS_RZ) -> axis(MotionEvent.AXIS_RZ)
            else -> 0f
        }
        hat(core, ZL, leftTrigger > 0.5f)
        hat(core, ZR, rightTrigger > 0.5f)

        val hatX = axis(MotionEvent.AXIS_HAT_X)
        val hatY = axis(MotionEvent.AXIS_HAT_Y)
        hat(core, DPAD_LEFT, hatX < -0.5f)
        hat(core, DPAD_RIGHT, hatX > 0.5f)
        hat(core, DPAD_UP, hatY < -0.5f)
        hat(core, DPAD_DOWN, hatY > 0.5f)
        return true
    }

    private val held = HashSet<Int>()

    private fun hat(core: KenjinxCore, button: Int, down: Boolean) {
        val wasDown = held.contains(button)
        when {
            down && !wasDown -> {
                held.add(button)
                press(core, button)
            }
            !down && wasDown -> {
                held.remove(button)
                release(core, button)
            }
        }
    }

    fun clearHeld() {
        held.clear()
    }
}
