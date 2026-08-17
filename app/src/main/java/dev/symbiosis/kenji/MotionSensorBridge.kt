package dev.symbiosis.kenji

import android.app.Activity
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener2
import android.hardware.SensorManager
import android.view.Surface
import org.kenjinx.android.KenjinxCore

/** Sends real phone accelerometer and gyroscope samples to the Kenji gamepad. */
class MotionSensorBridge(
    private val activity: Activity,
    private val core: KenjinxCore
) : SensorEventListener2 {
    private val sensorManager =
        activity.getSystemService(Activity.SENSOR_SERVICE) as SensorManager
    private val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private var controllerId = -1
    private var registered = false

    fun setControllerId(id: Int) {
        controllerId = id
    }

    fun register() {
        if (registered) return
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        registered = true
    }

    fun unregister() {
        if (registered) sensorManager.unregisterListener(this)
        registered = false
        if (controllerId >= 0) {
            runCatching { core.inputSetAccelerometerData(0f, 0f, 0f, controllerId) }
            runCatching { core.inputSetGyroData(0f, 0f, 0f, controllerId) }
        }
    }

    fun close() {
        unregister()
        controllerId = -1
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (!registered || controllerId < 0) return
        val values = event.values
        if (values.size < 3) return

        // Kenji's Android port uses a landscape Switch coordinate system. The
        // phone still supplies the real sensor values; this only rotates axes
        // to match the display orientation.
        val rotation = activity.display?.rotation ?: Surface.ROTATION_90
        val (x, y, z) = when (rotation) {
            Surface.ROTATION_270 -> Triple(-values[1], values[0], values[2])
            Surface.ROTATION_180 -> Triple(-values[0], -values[1], values[2])
            else -> Triple(values[1], -values[0], values[2])
        }

        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER ->
                runCatching { core.inputSetAccelerometerData(x, y, z, controllerId) }
            Sensor.TYPE_GYROSCOPE ->
                runCatching { core.inputSetGyroData(x, y, z, controllerId) }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onFlushCompleted(sensor: Sensor?) = Unit
}
