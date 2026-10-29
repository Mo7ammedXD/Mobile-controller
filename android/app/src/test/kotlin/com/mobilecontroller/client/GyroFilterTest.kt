package com.mobilecontroller.client

import com.mobilecontroller.client.input.GyroFilter
import com.mobilecontroller.client.input.MotionMath
import com.mobilecontroller.client.input.StickMath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** Gyro aiming filter: calibration, noise, smoothing, acceleration. */
class GyroFilterTest {
    private val g = 9.81f
    private val rotation = MotionMath.ROTATION_90
    private var t = 1_000_000_000L

    // Landscape phone held upright: "up" is the device's +X, so yaw around gravity is the X rate.
    private fun GyroFilter.sample(yawRate: Float, pitchRate: Float = 0f, extraZ: Float = 0f): Int {
        t += 5_000_000L
        // Rotation 90: the screen's X axis is the device's -Y, so pitch is minus the Y rate.
        return process(yawRate, -pitchRate, extraZ, g, 0f, 0f, rotation, t)
    }

    private fun x(packed: Int) = StickMath.unpackX(packed)
    private fun y(packed: Int) = StickMath.unpackY(packed)

    private fun filter(block: GyroFilter.() -> Unit = {}) = GyroFilter().apply {
        smoothThreshold = 0f
        autoCalibrate = false
        block()
    }

    @Test
    fun matchesTheStatelessAimWithoutFiltering() {
        val f = filter()
        for (rate in floatArrayOf(-2f, -0.3f, 0.05f, 0.4f, 1.5f)) {
            val expected = MotionMath.aim(rate, 0f, 0f, g, 0f, 0f, rotation, 1f, 0f, false)
            assertEquals("rate $rate", expected, f.sample(rate))
        }
    }

    @Test
    fun noiseFloorAndSoftTightening() {
        val f = filter { tightening = 0.2f }
        assertEquals(0, f.sample(0.01f))
        // Just above the floor: scaled down by rate / tightening, not cut.
        val small = x(f.sample(0.1f))
        val linear = x(MotionMath.aim(0.1f, 0f, 0f, g, 0f, 0f, rotation, 1f, 0f, false))
        assertEquals(linear / 2.0, small.toDouble(), 2.0)
        // Continuous at the threshold: no jump between just below and just above it.
        val below = abs(x(f.sample(0.199f)))
        val above = abs(x(f.sample(0.201f)))
        assertTrue("$below vs $above", abs(above - below) < 100)
    }

    @Test
    fun smoothingCalmsSlowJitterButLeavesFlicksAlone() {
        val raw = filter()
        val smooth = filter { smoothThreshold = 0.3f; smoothSamples = 8 }
        val random = Random(42)
        var rawVariance = 0.0
        var smoothVariance = 0.0
        for (i in 0 until 400) {
            // A slow pan (0.06 rad/s) with hand tremor on top.
            val rate = 0.06f + 0.04f * (random.nextFloat() - 0.5f)
            val a = x(raw.sample(rate)).toDouble()
            val b = x(smooth.sample(rate)).toDouble()
            if (i >= 16) {
                val mean = -0.06 / MotionMath.FULL_RATE * StickMath.AXIS_MAX
                rawVariance += (a - mean) * (a - mean)
                smoothVariance += (b - mean) * (b - mean)
            }
        }
        assertTrue("raw $rawVariance smooth $smoothVariance", smoothVariance < rawVariance / 3)
        // A fast flick is passed through on its first sample.
        assertEquals(x(raw.sample(2f)), x(smooth.sample(2f)))
    }

    @Test
    fun manualCalibrationRemovesTheOffset() {
        val f = filter()
        val offset = 0.05f
        assertTrue(x(f.sample(offset)) != 0)
        f.startCalibration(200_000_000L)
        var calibrated = floatArrayOf()
        f.calibrationListener = GyroFilter.CalibrationListener { bx, by, bz -> calibrated = floatArrayOf(bx, by, bz) }
        repeat(60) { f.sample(offset, extraZ = 0.01f) }
        assertEquals(offset, calibrated[0], 1e-5f)
        assertEquals(0.01f, calibrated[2], 1e-5f)
        assertEquals(0, f.sample(offset, extraZ = 0.01f))
        // Real motion is still there.
        assertTrue(x(f.sample(offset + 0.5f)) < -4000)
    }

    @Test
    fun automaticCalibrationLearnsTheDriftOfAStillPhone() {
        val f = filter { autoCalibrate = true }
        val drift = 0.025f
        // 6 s still (1 s to notice, then a 4 s time constant): most of the drift is learned.
        repeat(1200) { f.sample(drift) }
        assertTrue("bias ${f.biasX}", f.biasX > drift * 0.6f)
        // Moving resets the stillness: a deliberate turn is not absorbed.
        val before = f.biasX
        repeat(200) { f.sample(1f) }
        assertEquals(before, f.biasX, 0f)
    }

    @Test
    fun accelerationOnlyBoostsFastTurns() {
        val plain = filter()
        val accel = filter { accelGain = 2f }
        assertEquals(x(plain.sample(0.3f)), x(accel.sample(0.3f)))
        val fast = 1.2f
        assertTrue(abs(x(accel.sample(fast))) > abs(x(plain.sample(fast))) * 1.15)
        // Monotonic: faster never aims slower.
        var last = 0
        var rate = 0.05f
        while (rate < 3f) {
            val v = abs(x(accel.sample(rate)))
            assertTrue("rate $rate", v >= last)
            last = v
            rate += 0.05f
        }
    }

    @Test
    fun axesInvertAndSensitivityApplyPerAxis() {
        val f = filter { sensitivityY = 2f; invertX = true }
        val p = f.sample(0.4f, 0.4f)
        val base = MotionMath.aim(0.4f, -0.4f, 0f, g, 0f, 0f, rotation, 1f, 0f, false)
        assertEquals(-x(base).toDouble(), x(p).toDouble(), 2.0)
        assertEquals(2.0 * y(base), y(p).toDouble(), 3.0)
    }

    @Test
    fun rollAxisAimsLikeASteeringWheel() {
        val f = filter { axisMode = GyroFilter.AXIS_ROLL }
        // Counter-clockwise around the screen's normal (+Z) turns left.
        assertTrue(x(f.sample(0f, extraZ = 0.8f)) < -5000)
        assertTrue(x(f.sample(0f, extraZ = -0.8f)) > 5000)
        // Yaw around gravity does nothing in this mode.
        assertEquals(0, x(f.sample(0.8f)))
    }
}
