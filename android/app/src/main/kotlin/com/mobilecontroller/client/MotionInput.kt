package com.mobilecontroller.client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import com.mobilecontroller.client.input.MotionMath
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.ui.GameMode

/**
 * Gyro aiming and tilt steering. Sensor events arrive on a dedicated thread at up to 200 Hz (the most
 * an app may request without the high-sampling-rate permission); each one becomes a motion offset in
 * [GamepadState] (added to the touch sticks) and wakes the sender. SensorManager reuses its event
 * objects, and the math is primitive-only, so this path does not allocate either.
 */
class MotionInput(context: Context, private val link: UdpLink) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** [GameMode.MOTION_NONE], [GameMode.MOTION_AIM] or [GameMode.MOTION_STEER]; apply with [restart]. */
    @Volatile var mode = GameMode.MOTION_NONE

    /** Aim only while one of these GamepadState bits is held (0 = always). */
    @Volatile var aimGate = 0
    @Volatile var sensitivity = 1f
    @Volatile var antiDeadzone = 0f
    @Volatile var invertY = false

    /** Display rotation (Surface.ROTATION_*): sensor axes are the device's, the player's are the screen's. */
    @Volatile var rotation = MotionMath.ROTATION_90

    private var thread: HandlerThread? = null
    @Volatile private var active = false

    // Latest gravity ("up") vector; written and read on the sensor thread only.
    private var gx = 0f
    private var gy = 0f
    private var gz = SensorManager.GRAVITY_EARTH

    fun isAvailable(motion: Int): Boolean = when (motion) {
        GameMode.MOTION_AIM -> gyroscope != null
        GameMode.MOTION_STEER -> gravitySensor != null
        else -> false
    }

    /** Applies [mode]: (re)registers the sensors it needs, or stops everything for MOTION_NONE. */
    fun restart() {
        stop()
        val m = mode
        if (m == GameMode.MOTION_NONE || !isAvailable(m)) return
        val sm = sensors ?: return
        val t = HandlerThread("mc-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val handler = Handler(t.looper)
        active = true
        // Gravity is needed in both modes: tilt is measured against it, and gyro yaw is taken around it.
        gravitySensor?.let { sm.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
        if (m == GameMode.MOTION_AIM) gyroscope?.let { sm.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
    }

    fun stop() {
        val t = thread ?: return
        thread = null
        active = false
        sensors?.unregisterListener(this)
        if (link.state.setMotion(0, 0, 0)) link.wake()
        // Events already queued on the sensor thread run before this, so none can leave a stale offset.
        Handler(t.looper).post { if (link.state.setMotion(0, 0, 0)) link.wake() }
        t.quitSafely()
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (!active) return
        val v = e.values
        when (e.sensor.type) {
            Sensor.TYPE_GRAVITY -> {
                gx = v[0]
                gy = v[1]
                gz = v[2]
                if (mode == GameMode.MOTION_STEER) steer()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // No gravity sensor: a light low-pass keeps hand shake out of the tilt.
                gx += (v[0] - gx) * LOW_PASS
                gy += (v[1] - gy) * LOW_PASS
                gz += (v[2] - gz) * LOW_PASS
                if (mode == GameMode.MOTION_STEER) steer()
            }
            Sensor.TYPE_GYROSCOPE -> if (mode == GameMode.MOTION_AIM) aim(v[0], v[1], v[2])
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun aim(wx: Float, wy: Float, wz: Float) {
        val gate = aimGate
        val packed = if (gate != 0 && (link.state.heldBits() and gate) == 0) {
            0
        } else {
            MotionMath.aim(wx, wy, wz, gx, gy, gz, rotation, sensitivity, antiDeadzone, invertY)
        }
        if (link.state.setMotion(0, StickMath.unpackX(packed), StickMath.unpackY(packed))) link.wake()
    }

    private fun steer() {
        val x = MotionMath.steer(gx, gy, gz, rotation, sensitivity, antiDeadzone)
        if (link.state.setMotion(x, 0, 0)) link.wake()
    }

    private companion object {
        const val SAMPLING_PERIOD_US = 5_000
        const val LOW_PASS = 0.2f
    }
}
