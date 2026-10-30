package com.mobilecontroller.client

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import com.mobilecontroller.client.input.GyroFilter
import com.mobilecontroller.client.input.MotionMath
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.ui.GameMode

/**
 * Gyro aiming and tilt steering. Sensor events arrive on a dedicated thread at up to 200 Hz (the most
 * an app may request without the high-sampling-rate permission); each one becomes a motion offset in
 * [GamepadState] (added to the touch sticks) and wakes the sender. SensorManager reuses its event
 * objects, and the math is primitive-only ([GyroFilter] keeps preallocated state), so this path does
 * not allocate either.
 */
class MotionInput(context: Context, private val link: UdpLink) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val gyroscope: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val gravitySensor: Sensor? =
        sensors?.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    /** [GameMode.MOTION_NONE], [GameMode.MOTION_AIM] or [GameMode.MOTION_STEER]; apply with [restart]. */
    @Volatile var mode = GameMode.MOTION_NONE

    /** Aim only while one of these GamepadState bits is held (0 = always)... */
    @Volatile var aimGate = 0

    /** ...or, inverted, only while none of them is held (a "ratchet": hold to lift the aim off). */
    @Volatile var gateInverted = false

    /** Tilt steering sensitivity and output floor; gyro aiming is configured on [gyro]. */
    @Volatile var sensitivity = 1f
    @Volatile var antiDeadzone = 0f

    /** Gyro aiming filter and its settings (smoothing, calibration, axes). */
    val gyro = GyroFilter()

    /** The gyro's latest filtered output (StickMath-packed), gate or not: the settings screen's live gauge. */
    @Volatile var preview = 0
        private set

    /** Display rotation (Surface.ROTATION_*): sensor axes are the device's, the player's are the screen's. */
    @Volatile var rotation = MotionMath.ROTATION_90

    private var thread: HandlerThread? = null
    @Volatile private var active = false

    // Sensor thread: whether the gate was open on the previous gyro sample.
    private var gateWasOpen = false

    // Latest gravity ("up") vector; written and read on the sensor thread only.
    private var gx = 0f
    private var gy = 0f
    private var gz = SensorManager.GRAVITY_EARTH

    fun isAvailable(motion: Int): Boolean = when (motion) {
        GameMode.MOTION_AIM -> gyroscope != null
        GameMode.MOTION_STEER -> gravitySensor != null
        else -> false
    }

    /**
     * Measures the gyroscope's resting offset over 2 s (the phone should lie still); works in any mode.
     * [onDone] runs on the UI thread with the new bias, which the caller persists.
     */
    fun calibrate(onDone: (Float, Float, Float) -> Unit) {
        if (gyroscope == null) return
        val main = Handler(Looper.getMainLooper())
        gyro.calibrationListener = GyroFilter.CalibrationListener { x, y, z ->
            gyro.calibrationListener = null
            main.post {
                onDone(x, y, z)
                // Calibrating may have started the gyroscope for a mode that does not aim with it.
                if (mode != GameMode.MOTION_AIM && thread != null) restart()
            }
        }
        gyro.startCalibration()
        if (mode != GameMode.MOTION_AIM || thread == null) restart()
    }

    /** Applies [mode]: (re)registers the sensors it needs, or stops everything for MOTION_NONE. */
    fun restart() {
        stop()
        val m = mode
        val calibrating = gyro.calibrating && gyroscope != null
        if (!calibrating && (m == GameMode.MOTION_NONE || !isAvailable(m))) return
        val sm = sensors ?: return
        val t = HandlerThread("mc-motion", Process.THREAD_PRIORITY_URGENT_DISPLAY).apply { start() }
        thread = t
        val handler = Handler(t.looper)
        active = true
        // Gravity is needed in both modes: tilt is measured against it, and gyro yaw is taken around it.
        gravitySensor?.let { sm.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
        if (m == GameMode.MOTION_AIM || calibrating) {
            gyroscope?.let { sm.registerListener(this, it, SAMPLING_PERIOD_US, handler) }
        }
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
            Sensor.TYPE_GYROSCOPE -> aim(v[0], v[1], v[2], e.timestamp)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit

    private fun aim(wx: Float, wy: Float, wz: Float, timestamp: Long) {
        val gate = aimGate
        val open = mode == GameMode.MOTION_AIM &&
            (gate == 0 || ((link.state.heldBits() and gate) != 0) != gateInverted)
        // Smoothing must not replay the motion made while the gate was closed.
        if (open && !gateWasOpen) gyro.clearHistory()
        gateWasOpen = open
        // Filtered even while closed, so calibration keeps learning (not aiming is the best time to).
        val packed = gyro.process(wx, wy, wz, gx, gy, gz, rotation, timestamp)
        preview = packed
        if (mode != GameMode.MOTION_AIM) return
        val out = if (open) packed else 0
        if (link.state.setMotion(0, StickMath.unpackX(out), StickMath.unpackY(out))) link.wake()
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
