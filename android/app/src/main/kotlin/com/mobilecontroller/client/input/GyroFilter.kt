package com.mobilecontroller.client.input

import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Gyro aiming with the processing that makes it pleasant, one instance per sensor thread. Each sample
 * goes through:
 *
 * 1. calibration: the sensor's resting offset (bias) is subtracted. It is learned automatically while
 *    the phone is still, or measured on request ([startCalibration]);
 * 2. axis choice: which rotation turns the camera ([AXIS_PLAYER], [AXIS_LOCAL], [AXIS_ROLL]);
 * 3. noise floor and soft tightening: tiny rates are zero, slightly larger ones are scaled down, so a
 *    resting hand holds the crosshair still without a dead step;
 * 4. tiered smoothing: slow movements (fine aim, where jitter shows) are averaged over a short window,
 *    fast ones (flicks) pass untouched, so smoothing never adds lag where speed matters;
 * 5. acceleration, per-axis sensitivity and inversion;
 * 6. the stick shaping of [StickMath.shape] (anti-deadzone).
 *
 * Settings are written by the UI thread and read once per sample; everything else belongs to the sensor
 * thread. Primitive fields and one preallocated ring, so a sample never allocates.
 */
class GyroFilter {
    /** Told on the sensor thread when a requested calibration finished, with the new bias (rad/s). */
    fun interface CalibrationListener {
        fun onCalibrated(x: Float, y: Float, z: Float)
    }

    @Volatile var axisMode = AXIS_PLAYER
    @Volatile var sensitivity = 1f
    @Volatile var sensitivityX = 1f
    @Volatile var sensitivityY = 1f
    @Volatile var invertX = false
    @Volatile var invertY = false
    @Volatile var antiDeadzone = 0f

    /** Rates below this (rad/s) are noise and give no output. */
    @Volatile var noiseFloor = MotionMath.GYRO_NOISE

    /** Rates below this (rad/s) are scaled down proportionally (0 = off). */
    @Volatile var tightening = 0f

    /** Rates below half of this (rad/s) are fully smoothed, above it not at all (0 = off). */
    @Volatile var smoothThreshold = DEFAULT_SMOOTH_THRESHOLD

    /** Smoothing window in samples (1..[MAX_SMOOTH_SAMPLES]); at 200 Hz, 8 samples are 40 ms. */
    @Volatile var smoothSamples = DEFAULT_SMOOTH_SAMPLES

    /** Sensitivity multiplier reached at [accelHigh] (1 = no acceleration), starting from 1 at [accelLow]. */
    @Volatile var accelGain = 1f
    @Volatile var accelLow = DEFAULT_ACCEL_LOW
    @Volatile var accelHigh = DEFAULT_ACCEL_HIGH

    /** Learn the bias whenever the phone is held still. */
    @Volatile var autoCalibrate = true

    // Bias in device axes (rad/s). Written by calibration on the sensor thread, or restored by the UI.
    @Volatile var biasX = 0f
    @Volatile var biasY = 0f
    @Volatile var biasZ = 0f

    @Volatile var calibrationListener: CalibrationListener? = null

    // Sensor thread only.
    private val ringYaw = FloatArray(MAX_SMOOTH_SAMPLES)
    private val ringPitch = FloatArray(MAX_SMOOTH_SAMPLES)
    private var ringSize = 0
    private var ringNext = 0
    private var ringCount = 0
    private var lastTimestamp = 0L
    private var stillNanos = 0L

    // Manual calibration: requested by the UI, run by the sensor thread.
    @Volatile private var calibrationRequest = 0L
    private var calibrationEnd = 0L
    private var calibrationSamples = 0
    private var sumX = 0.0
    private var sumY = 0.0
    private var sumZ = 0.0

    /** True while a requested calibration is still measuring. */
    @Volatile var calibrating = false
        private set

    /**
     * Measures the bias over the next [durationNanos] of samples; the phone should lie still meanwhile.
     * Any thread. [calibrationListener] hears the result.
     */
    fun startCalibration(durationNanos: Long = DEFAULT_CALIBRATION_NANOS) {
        calibrating = true
        calibrationRequest = durationNanos
    }

    /** Forgets the smoothing history, e.g. when the gyro gate opens again after a pause. Sensor thread. */
    fun clearHistory() {
        ringCount = 0
        ringNext = 0
    }

    /**
     * One gyroscope sample: angular velocity ([wx], [wy], [wz], rad/s, device axes), the latest gravity
     * ("up", device axes), the display rotation and the sample's timestamp. Returns the packed right-stick
     * deflection ([StickMath.pack]).
     */
    fun process(
        wx: Float, wy: Float, wz: Float, gx: Float, gy: Float, gz: Float, rotation: Int, timestampNanos: Long,
    ): Int {
        val dt = if (lastTimestamp == 0L) 0L else (timestampNanos - lastTimestamp).coerceIn(0L, MAX_DT_NANOS)
        lastTimestamp = timestampNanos
        calibrate(wx, wy, wz, timestampNanos)

        val x = wx - biasX
        val y = wy - biasY
        val z = wz - biasZ
        if (autoCalibrate && !calibrating) learnBias(wx, wy, wz, x, y, z, dt)

        var pitch = MotionMath.screenX(rotation, x, y)
        var yaw = when (axisMode) {
            AXIS_LOCAL -> MotionMath.screenY(rotation, x, y)
            // Around the screen's normal: turning the phone like a steering wheel.
            AXIS_ROLL -> z
            else -> {
                // Around gravity ("player space"): turning your body aims, however the phone is tilted.
                val g = sqrt(gx * gx + gy * gy + gz * gz)
                if (g > 1f) (x * gx + y * gy + z * gz) / g else MotionMath.screenY(rotation, x, y)
            }
        }

        var rate = sqrt(yaw * yaw + pitch * pitch)
        if (rate < noiseFloor) {
            yaw = 0f
            pitch = 0f
            rate = 0f
        } else {
            val t = tightening
            if (t > 0f && rate < t) {
                val k = rate / t
                yaw *= k
                pitch *= k
                rate *= k
            }
        }

        val s = smoothThreshold
        if (s > 0f) {
            push(yaw, pitch)
            // Weight of the raw sample: 0 below s/2 (fully averaged), 1 from s up (untouched).
            val w = ((rate - s * 0.5f) / (s * 0.5f)).coerceIn(0f, 1f)
            if (w < 1f) {
                yaw = w * yaw + (1f - w) * average(ringYaw)
                pitch = w * pitch + (1f - w) * average(ringPitch)
            }
        }

        var gain = sensitivity
        val accel = accelGain
        if (accel != 1f) {
            val span = accelHigh - accelLow
            val f = if (span > 0f) ((rate - accelLow) / span).coerceIn(0f, 1f) else if (rate >= accelHigh) 1f else 0f
            gain *= 1f + (accel - 1f) * f
        }

        // Counter-clockwise around the chosen axis turns left, so the stick's +X (right) is minus the yaw.
        var sx = -yaw * gain * sensitivityX / MotionMath.FULL_RATE
        var sy = pitch * gain * sensitivityY / MotionMath.FULL_RATE
        if (invertX) sx = -sx
        if (invertY) sy = -sy
        val m = sqrt(sx * sx + sy * sy)
        if (!(m > 0f)) return 0
        val scale = StickMath.shape(min(m, 1f), antiDeadzone, 1f) / m * StickMath.AXIS_MAX
        return StickMath.pack(axis(sx * scale), axis(sy * scale))
    }

    private fun push(yaw: Float, pitch: Float) {
        val n = smoothSamples.coerceIn(1, MAX_SMOOTH_SAMPLES)
        if (n != ringSize) {
            ringSize = n
            clearHistory()
        }
        ringYaw[ringNext] = yaw
        ringPitch[ringNext] = pitch
        ringNext = (ringNext + 1) % n
        if (ringCount < n) ringCount++
    }

    private fun average(ring: FloatArray): Float {
        if (ringCount == 0) return 0f
        var sum = 0f
        for (i in 0 until ringCount) sum += ring[i]
        return sum / ringCount
    }

    /** Manual calibration: averages the raw rates over the requested window. */
    private fun calibrate(wx: Float, wy: Float, wz: Float, now: Long) {
        val request = calibrationRequest
        if (request != 0L) {
            calibrationRequest = 0L
            calibrationEnd = now + request
            calibrationSamples = 0
            sumX = 0.0
            sumY = 0.0
            sumZ = 0.0
        }
        if (calibrationEnd == 0L) return
        sumX += wx
        sumY += wy
        sumZ += wz
        calibrationSamples++
        if (now < calibrationEnd) return
        calibrationEnd = 0L
        val n = calibrationSamples
        biasX = (sumX / n).toFloat()
        biasY = (sumY / n).toFloat()
        biasZ = (sumZ / n).toFloat()
        stillNanos = 0L
        clearHistory()
        calibrating = false
        calibrationListener?.onCalibrated(biasX, biasY, biasZ)
    }

    /**
     * Automatic calibration: after [STILL_NANOS] without real rotation, the bias slowly follows the raw
     * rates (time constant [BIAS_TAU_NANOS]), which tracks the drift of a warming sensor. Slow on
     * purpose, so a deliberate slow pan is barely absorbed before it ends.
     */
    private fun learnBias(wx: Float, wy: Float, wz: Float, x: Float, y: Float, z: Float, dt: Long) {
        if (x * x + y * y + z * z > STILL_RATE * STILL_RATE) {
            stillNanos = 0L
            return
        }
        stillNanos += dt
        if (stillNanos < STILL_NANOS) return
        val k = dt.toFloat() / BIAS_TAU_NANOS
        biasX += (wx - biasX) * k
        biasY += (wy - biasY) * k
        biasZ += (wz - biasZ) * k
    }

    private fun axis(v: Float): Int = v.roundToInt().coerceIn(-StickMath.AXIS_MAX, StickMath.AXIS_MAX)

    companion object {
        /** Yaw around gravity: turning your body or wrists aims, however far the phone is tilted back. */
        const val AXIS_PLAYER = 0

        /** Yaw around the screen's vertical axis, as a phone held upright sees it. */
        const val AXIS_LOCAL = 1

        /** Roll around the screen's normal: turning the phone like a steering wheel aims left / right. */
        const val AXIS_ROLL = 2

        const val MAX_SMOOTH_SAMPLES = 16
        const val DEFAULT_SMOOTH_SAMPLES = 8

        /** About 6 degrees per second: below 3 °/s fully smoothed, above 6 °/s untouched. */
        const val DEFAULT_SMOOTH_THRESHOLD = 0.1f

        /** Acceleration ramps from 1x at about 30 °/s to [accelGain] at about 270 °/s. */
        const val DEFAULT_ACCEL_LOW = 0.5f
        const val DEFAULT_ACCEL_HIGH = 4.7f

        const val DEFAULT_CALIBRATION_NANOS = 2_000_000_000L

        /** Rotation slower than this (about 2.3 °/s) counts as "still" for automatic calibration. */
        private const val STILL_RATE = 0.04f
        private const val STILL_NANOS = 1_000_000_000L
        private const val BIAS_TAU_NANOS = 4_000_000_000f

        /** A gap in the sensor stream (screen off, re-registration) must not count as a long stillness. */
        private const val MAX_DT_NANOS = 50_000_000L
    }
}
