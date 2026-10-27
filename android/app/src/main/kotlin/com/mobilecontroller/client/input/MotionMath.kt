package com.mobilecontroller.client.input

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Phone motion -> stick values: gyro aiming and tilt steering. Pure functions on primitives (sensor
 * vectors in Android's device axes), so they are JVM-testable and allocation-free.
 *
 * Gravity vectors follow the accelerometer convention: at rest they point UP (+9.81 along z when the
 * phone lies face-up). Rotations are rad/s, right-handed around the device axes.
 */
object MotionMath {
    // Display rotations, numerically equal to android.view.Surface.ROTATION_*.
    const val ROTATION_0 = 0
    const val ROTATION_90 = 1
    const val ROTATION_180 = 2
    const val ROTATION_270 = 3

    /** Rotation rate (rad/s) that gives full stick deflection at sensitivity 1: 180 degrees per second. */
    const val FULL_RATE = 3.1415927f

    /** Gyro rates below this (about 1.1 degree/s) are sensor noise and a resting hand, not aiming. */
    const val GYRO_NOISE = 0.02f

    /** Tilt for full lock at sensitivity 1 (about 35 degrees), and the centre deadzone (about 2 degrees). */
    const val STEER_RANGE = 0.61f
    const val STEER_DEADZONE = 0.035f

    /** The screen's X axis (right, as the user sees it) of a device-axis vector. */
    fun screenX(rotation: Int, x: Float, y: Float): Float = when (rotation) {
        ROTATION_90 -> -y
        ROTATION_180 -> -x
        ROTATION_270 -> y
        else -> x
    }

    /** The screen's Y axis (up, as the user sees it) of a device-axis vector. */
    fun screenY(rotation: Int, x: Float, y: Float): Float = when (rotation) {
        ROTATION_90 -> x
        ROTATION_180 -> -y
        ROTATION_270 -> -x
        else -> y
    }

    /**
     * Gyro aiming: angular velocity ([wx], [wy], [wz]) -> packed right-stick deflection.
     *
     * Yaw is the rotation around gravity ("player space"), so turning your body or wrists left/right aims
     * left/right however far the phone is tilted back; pitch is the rotation around the screen's
     * horizontal axis (top edge rising = aim up). Rate maps to deflection, because a stick sets the
     * camera's turning SPEED; [antiDeadzone] lifts small motions past the game's stick deadzone.
     */
    fun aim(
        wx: Float, wy: Float, wz: Float, gx: Float, gy: Float, gz: Float,
        rotation: Int, sensitivity: Float, antiDeadzone: Float, invertY: Boolean,
    ): Int {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        // Without a usable gravity reading, fall back to the rotation around the screen's vertical axis.
        val yaw = if (g > 1f) (wx * gx + wy * gy + wz * gz) / g else screenY(rotation, wx, wy)
        val pitch = screenX(rotation, wx, wy)
        val rate = sqrt(yaw * yaw + pitch * pitch)
        if (rate < GYRO_NOISE) return 0
        // Counter-clockwise around "up" turns left, so the stick's +X (right) is minus the yaw rate.
        val x = -yaw * sensitivity / FULL_RATE
        val y = (if (invertY) -pitch else pitch) * sensitivity / FULL_RATE
        val m = sqrt(x * x + y * y)
        val scale = StickMath.shape(min(m, 1f), antiDeadzone, 1f) / m * StickMath.AXIS_MAX
        return StickMath.pack(axis(x * scale), axis(y * scale))
    }

    /**
     * Tilt steering: gravity -> left-stick X. Turning the phone clockwise, like a steering wheel, steers
     * right. Uses the roll against gravity, so it does not matter how far the phone is tilted back.
     */
    fun steer(gx: Float, gy: Float, gz: Float, rotation: Int, sensitivity: Float, antiDeadzone: Float): Int {
        val g = sqrt(gx * gx + gy * gy + gz * gz)
        if (g < 1f) return 0
        // Clockwise turns the "up" vector towards the screen's left edge, i.e. negative screen X.
        val roll = asin((-screenX(rotation, gx, gy) / g).coerceIn(-1f, 1f))
        val magnitude = abs(roll)
        if (magnitude <= STEER_DEADZONE) return 0
        val range = STEER_RANGE / sensitivity
        val t = min(1f, (magnitude - STEER_DEADZONE) / (range - STEER_DEADZONE))
        val v = StickMath.shape(t, antiDeadzone, 1f) * StickMath.AXIS_MAX
        return axis(if (roll < 0f) -v else v)
    }

    private fun axis(v: Float): Int = v.roundToInt().coerceIn(-StickMath.AXIS_MAX, StickMath.AXIS_MAX)
}
