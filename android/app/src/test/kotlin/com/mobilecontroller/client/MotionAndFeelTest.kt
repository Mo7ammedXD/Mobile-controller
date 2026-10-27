package com.mobilecontroller.client

import com.mobilecontroller.client.input.MotionMath
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** Stick shaping, gyro aiming, tilt steering, motion offsets and the left-handed mirror. */
class MotionAndFeelTest {
    private val g = 9.81f

    // --- Stick shaping ------------------------------------------------------------------------

    @Test
    fun neutralShapingIsTheOriginalStick() {
        for (dx in floatArrayOf(-150f, -30f, 0f, 12f, 77f, 100f, 260f)) {
            assertEquals(StickMath.stick(dx, 40f, 100f, 0.05f), StickMath.stick(dx, 40f, 100f, 0.05f, 0f, 1f, 1f))
        }
    }

    @Test
    fun shapeLiftsSmallInputsAndBendsTheCurve() {
        assertEquals(0f, StickMath.shape(0f, 0.2f, 1.6f), 0f)
        assertEquals(1f, StickMath.shape(1f, 0.2f, 1.6f), 1e-6f)
        // Anti-deadzone: the first bit of travel already lands past a 20 % game deadzone.
        assertTrue(StickMath.shape(0.01f, 0.2f, 1f) > 0.2f)
        // Curve: half travel gives less than half output (finer aim), but it stays monotonic.
        assertTrue(StickMath.shape(0.5f, 0f, 1.6f) < 0.5f)
        var last = 0f
        for (i in 1..100) {
            val v = StickMath.shape(i / 100f, 0.15f, 2.2f)
            assertTrue(v >= last)
            last = v
        }
        // Clamped: a silly anti-deadzone never exceeds the maximum.
        assertEquals(StickMath.MAX_ANTI_DEADZONE, StickMath.shape(1e-9f, 0.9f, 1f), 1e-3f)
    }

    @Test
    fun shapedStickKeepsTheFingersDirection() {
        val p = StickMath.stick(30f, -40f, 100f, 0.05f, 0.25f, 1.6f, 1.3f)
        val x = StickMath.unpackX(p)
        val y = StickMath.unpackY(p)
        // Screen (30, -40) points right and up at 3:4.
        assertEquals(0.75f, x.toFloat() / y, 0.01f)
    }

    // --- Gyro aiming ---------------------------------------------------------------------------

    /** Gravity ("up") of a phone held in landscape (rotation 90) and tilted back by [tilt] radians. */
    private fun upLandscape(tilt: Float) = floatArrayOf(g * cos(tilt), 0f, g * sin(tilt))

    @Test
    fun turningLeftAimsLeftAtAnyTilt() {
        for (tilt in floatArrayOf(0f, 0.4f, 0.8f, 1.2f)) {
            val up = upLandscape(tilt)
            // Turning left = counter-clockwise around "up": angular velocity along +up.
            val w = 1.0f
            val p = MotionMath.aim(up[0] / g * w, up[1] / g * w, up[2] / g * w, up[0], up[1], up[2],
                MotionMath.ROTATION_90, 1f, 0f, invertY = false)
            assertTrue("tilt $tilt x=${StickMath.unpackX(p)}", StickMath.unpackX(p) < -9000)
            assertEquals(0, StickMath.unpackY(p))
        }
    }

    @Test
    fun raisingTheTopEdgeAimsUpAndInvertFlipsIt() {
        val up = upLandscape(0.6f)
        // Rotation 90: the screen's X axis is the device's -Y; positive rotation around it raises the top.
        val p = MotionMath.aim(0f, -1f, 0f, up[0], up[1], up[2], MotionMath.ROTATION_90, 1f, 0f, invertY = false)
        assertTrue(StickMath.unpackY(p) > 9000)
        assertEquals(0, StickMath.unpackX(p))
        val inverted = MotionMath.aim(0f, -1f, 0f, up[0], up[1], up[2], MotionMath.ROTATION_90, 1f, 0f, invertY = true)
        assertEquals(-StickMath.unpackY(p), StickMath.unpackY(inverted))
    }

    @Test
    fun aRestingHandDoesNotAimAndSmallTurnsClearTheGamesDeadzone() {
        val up = upLandscape(0.5f)
        assertEquals(0, MotionMath.aim(0.01f, 0.005f, 0f, up[0], up[1], up[2], MotionMath.ROTATION_90, 1f, 0.2f, false))
        // A slow 5 degree/s turn, with a 20 % anti-deadzone, already gives a usable deflection.
        val slow = 0.087f
        val p = MotionMath.aim(up[0] / g * slow, 0f, up[2] / g * slow, up[0], up[1], up[2], MotionMath.ROTATION_90, 1f, 0.2f, false)
        assertTrue(abs(StickMath.unpackX(p)) > 0.2f * 32767)
        // Fast turns saturate.
        val fast = MotionMath.aim(up[0] / g * 9f, 0f, up[2] / g * 9f, up[0], up[1], up[2], MotionMath.ROTATION_90, 1f, 0f, false)
        assertEquals(-32767, StickMath.unpackX(fast))
    }

    @Test
    fun screenAxesFollowTheDisplayRotation() {
        // Device +Y ("up" in portrait) is screen left in rotation 90 and screen right in rotation 270.
        assertEquals(-1f, MotionMath.screenX(MotionMath.ROTATION_90, 0f, 1f), 0f)
        assertEquals(1f, MotionMath.screenX(MotionMath.ROTATION_270, 0f, 1f), 0f)
        assertEquals(1f, MotionMath.screenY(MotionMath.ROTATION_90, 1f, 0f), 0f)
        assertEquals(-1f, MotionMath.screenY(MotionMath.ROTATION_270, 1f, 0f), 0f)
        assertEquals(1f, MotionMath.screenX(MotionMath.ROTATION_0, 1f, 0f), 0f)
    }

    // --- Tilt steering -------------------------------------------------------------------------

    /** Gravity of a landscape phone (rotation 90) turned clockwise by [roll] like a steering wheel. */
    private fun wheel(roll: Float, rotation: Int = MotionMath.ROTATION_90): FloatArray {
        // Screen coordinates: up rotates towards the screen's left edge when turning clockwise.
        val sx = -g * sin(roll)
        val sy = g * cos(roll)
        return if (rotation == MotionMath.ROTATION_90) floatArrayOf(sy, -sx, 0f) else floatArrayOf(-sy, sx, 0f)
    }

    @Test
    fun turningClockwiseSteersRightInBothLandscapes() {
        for (rotation in intArrayOf(MotionMath.ROTATION_90, MotionMath.ROTATION_270)) {
            val v = wheel(0.3f, rotation)
            val x = MotionMath.steer(v[0], v[1], v[2], rotation, 1f, 0f)
            assertTrue("rotation $rotation x=$x", x > 10000)
            val l = wheel(-0.3f, rotation)
            assertEquals(-x, MotionMath.steer(l[0], l[1], l[2], rotation, 1f, 0f))
        }
    }

    @Test
    fun steeringHasACentreDeadzoneAndFullLock() {
        val level = wheel(0.02f)
        assertEquals(0, MotionMath.steer(level[0], level[1], level[2], MotionMath.ROTATION_90, 1f, 0.2f))
        val full = wheel(0.7f)
        assertEquals(32767, MotionMath.steer(full[0], full[1], full[2], MotionMath.ROTATION_90, 1f, 0f))
        // Double sensitivity reaches full lock at about half the angle.
        val half = wheel(0.33f)
        assertEquals(32767, MotionMath.steer(half[0], half[1], half[2], MotionMath.ROTATION_90, 2f, 0f))
        // Tilting the phone back (screen up) does not change the steering for the same roll.
        val roll = 0.25f
        val upright = wheel(roll)
        val flatter = floatArrayOf(upright[0] * 0.6f, upright[1], g * 0.8f)
        val a = MotionMath.steer(upright[0], upright[1], upright[2], MotionMath.ROTATION_90, 1f, 0f)
        val b = MotionMath.steer(flatter[0], flatter[1], flatter[2], MotionMath.ROTATION_90, 1f, 0f)
        assertTrue("$a vs $b", a > 0 && b > 0)
    }

    // --- Motion offsets in the packet ----------------------------------------------------------

    @Test
    fun motionAddsToTheSticksAndIsClamped() {
        val state = GamepadState()
        val out = ByteArray(Protocol.INPUT_SIZE)
        state.setLeftStick(20000, 0)
        state.setRightStick(-5000, 1000)
        assertTrue(state.setMotion(20000, 3000, -2000))
        state.snapshot(out)
        assertEquals(32767, Protocol.i16(out, 8))
        assertEquals(-2000, Protocol.i16(out, 12))
        assertEquals(-1000, Protocol.i16(out, 14))
        state.neutralize()
        state.snapshot(out)
        assertEquals(0, Protocol.i16(out, 8))
        assertEquals(0, Protocol.i16(out, 12))
    }

    // --- Left-handed mirror --------------------------------------------------------------------

    @Test
    fun mirrorSwapsSidesAndEditsStayMirrored() {
        val l = ControlLayout().apply { load(GameModes.SHOOTER); place(0f, 0f, 2400f, 1080f) }
        val move = l.indexOf("MOVE")
        val fire = l.indexOf("FIRE")
        val x = l.x[move]
        l.setMirrored(true)
        assertEquals(2400f - x, l.x[move], 0.5f)
        assertTrue(l.x[fire] < 1200f)
        // Dragging in mirrored mode stores an unmirrored position: switching back puts it on the other side.
        l.moveTo(move, 2000f, 500f)
        assertEquals(2000f, l.x[move], 0.5f)
        l.setMirrored(false)
        assertEquals(400f, l.x[move], 0.5f)
    }
}
