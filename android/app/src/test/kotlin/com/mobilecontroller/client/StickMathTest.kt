package com.mobilecontroller.client

import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class StickMathTest {
    private val r = 200f

    private fun x(packed: Int) = StickMath.unpackX(packed)
    private fun y(packed: Int) = StickMath.unpackY(packed)

    @Test
    fun rightEdgeIsFullPositiveX() {
        val p = StickMath.stick(r, 0f, r, 0f)
        assertEquals(32767, x(p))
        assertEquals(0, y(p))
        // The deadzone rescale still reaches full deflection.
        assertEquals(32767, x(StickMath.stick(r, 0f, r, 0.05f)))
    }

    @Test
    fun fingerMovedUpOnScreenIsPositiveY() {
        val up = StickMath.stick(0f, -r, r, 0.05f)
        assertEquals(0, x(up))
        assertEquals(32767, y(up))
        val down = StickMath.stick(0f, r, r, 0.05f)
        assertEquals(-32767, y(down))
        assertEquals(-32767, x(StickMath.stick(-r, 0f, r, 0.05f)))
    }

    @Test
    fun clampIsRadialNotPerAxis() {
        // 45° up-right, twice the radius: a physical stick tops out on its circular gate.
        val d = 2 * r * cos(Math.PI / 4).toFloat()
        val p = StickMath.stick(d, -d, r, 0f)
        assertEquals(23170.0, x(p).toDouble(), 1.0)
        assertEquals(23170.0, y(p).toDouble(), 1.0)
        // Exactly on the corner of the bounding square, too.
        val corner = StickMath.stick(r, -r, r, 0.05f)
        assertEquals(23170.0, x(corner).toDouble(), 1.0)
        assertEquals(23170.0, y(corner).toDouble(), 1.0)
    }

    @Test
    fun farBeyondTheRadiusKeepsTheDirection() {
        val a = Math.toRadians(30.0)
        val p = StickMath.stick((10 * r * cos(a)).toFloat(), (-10 * r * sin(a)).toFloat(), r, 0.05f)
        assertEquals(32767 * cos(a), x(p).toDouble(), 1.0)
        assertEquals(32767 * sin(a), y(p).toDouble(), 1.0)
    }

    @Test
    fun deadzoneSuppressesJitterAndRescales() {
        assertEquals(0, StickMath.stick(0f, 0f, r, 0.05f))
        assertEquals(0, StickMath.stick(0.049f * r, 0f, r, 0.05f))
        assertEquals(0, StickMath.stick(0.03f * r, -0.03f * r, r, 0.05f))
        // Just outside: small but non-zero.
        val small = x(StickMath.stick(0.06f * r, 0f, r, 0.05f))
        assertTrue("small=$small", small in 1..1000)
        // Half-way through the live range (0.05 + 0.475) maps to half deflection.
        assertEquals(16384.0, x(StickMath.stick(0.525f * r, 0f, r, 0.05f)).toDouble(), 2.0)
        // Without a deadzone the mapping is linear.
        assertEquals(16384.0, x(StickMath.stick(0.5f * r, 0f, r, 0f)).toDouble(), 1.0)
    }

    @Test
    fun axesStayWithinSymmetricRange() {
        var a = 0.0
        while (a < 2 * Math.PI) {
            val p = StickMath.stick((3 * r * cos(a)).toFloat(), (3 * r * sin(a)).toFloat(), r, 0.1f)
            assertTrue(x(p) in -32767..32767)
            assertTrue(y(p) in -32767..32767)
            a += 0.01
        }
    }

    @Test
    fun packRoundTripsNegativeValues() {
        for (vx in intArrayOf(-32767, -1, 0, 1, 32767)) {
            for (vy in intArrayOf(-32767, -1, 0, 1, 32767)) {
                val p = StickMath.pack(vx, vy)
                assertEquals(vx, x(p))
                assertEquals(vy, y(p))
            }
        }
    }

    private fun full(dx: Float, dy: Float, inner: Float, outer: Float, shape: Int) =
        StickMath.stick(dx, dy, r, inner, outer, shape, 0f, 1f, 1f)

    @Test
    fun outerDeadzoneSaturatesBeforeTheRim() {
        assertEquals(32767, x(full(0.8f * r, 0f, 0.05f, 0.2f, StickMath.DZ_RADIAL)))
        // The live band is rescaled: half-way through it is half output.
        assertEquals(16384.0, x(full(0.425f * r, 0f, 0.05f, 0.2f, StickMath.DZ_RADIAL)).toDouble(), 2.0)
        assertEquals(0.8f, StickMath.fullDeflection(0.05f, 0.2f, 1f), 1e-6f)
        // No outer band and the old overload agree.
        assertEquals(StickMath.stick(0.3f * r, -0.2f * r, r, 0.05f), full(0.3f * r, -0.2f * r, 0.05f, 0f, StickMath.DZ_RADIAL))
    }

    @Test
    fun axialDeadzoneSendsPureDirections() {
        // Mostly up, a little right: the radial stick sends both, the axial one only up.
        val radial = full(0.08f * r, -0.9f * r, 0.1f, 0f, StickMath.DZ_RADIAL)
        val axial = full(0.08f * r, -0.9f * r, 0.1f, 0f, StickMath.DZ_AXIAL)
        assertTrue(x(radial) > 0)
        assertEquals(0, x(axial))
        assertTrue(y(axial) > 28000)
        // Each axis inside its own deadzone: nothing at all.
        assertEquals(0, full(0.09f * r, -0.09f * r, 0.1f, 0f, StickMath.DZ_AXIAL))
        // Still clamped to the circle on a diagonal.
        val diagonal = full(r, -r, 0.1f, 0f, StickMath.DZ_AXIAL)
        assertEquals(23170.0, x(diagonal).toDouble(), 2.0)
    }

    @Test
    fun snapPullsNearAxisDirectionsOntoTheAxis() {
        val a = Math.toRadians(8.0)
        val near = full((r * sin(a)).toFloat(), (-r * cos(a)).toFloat(), 0.05f, 0f, StickMath.DZ_SNAP)
        assertEquals(0, x(near))
        assertEquals(32767, y(near))
        // A real diagonal keeps its direction.
        val b = Math.toRadians(30.0)
        val diagonal = full((r * sin(b)).toFloat(), (-r * cos(b)).toFloat(), 0.05f, 0f, StickMath.DZ_SNAP)
        assertEquals(0.577, x(diagonal).toDouble() / y(diagonal), 0.01)
    }

    @Test
    fun configurableDpadMatchesTheEqualSectorsByDefault() {
        var a = 0.0
        while (a < 2 * Math.PI) {
            val dx = (r * cos(a)).toFloat()
            val dy = (-r * sin(a)).toFloat()
            assertEquals(
                "angle ${Math.toDegrees(a)}",
                StickMath.dpad(dx, dy, r, 0.25f),
                StickMath.dpad(dx, dy, r, 0.25f, StickMath.DPAD_EQUAL_DIAGONAL, fourWay = false),
            )
            a += 0.013
        }
    }

    @Test
    fun dpadDiagonalWidthAndFourWay() {
        // 20° above right: right with equal sectors (diagonals span 22.5°..67.5°)...
        val a = Math.toRadians(20.0)
        val dx = (r * cos(a)).toFloat()
        val dy = (-r * sin(a)).toFloat()
        assertEquals(Protocol.DPAD_RIGHT, StickMath.dpad(dx, dy, r, 0.25f, StickMath.DPAD_EQUAL_DIAGONAL, false))
        // ...but wider diagonals (30° either side of 45°, so 15°..75°) catch it.
        val wide = Math.toRadians(30.0).toFloat()
        assertEquals(Protocol.DPAD_UP or Protocol.DPAD_RIGHT, StickMath.dpad(dx, dy, r, 0.25f, wide, false))
        // Four-way never presses two directions, even exactly on a diagonal.
        val d = (r * cos(Math.PI / 4)).toFloat()
        val bits = StickMath.dpad(d * 1.01f, -d, r, 0.25f, wide, true)
        assertTrue(bits == Protocol.DPAD_UP || bits == Protocol.DPAD_RIGHT)
        assertEquals(Protocol.DPAD_LEFT, StickMath.dpad(-r, 0.1f * r, r, 0.25f, wide, true))
    }

    @Test
    fun dpadEightSectorsAndCentre() {
        val up = Protocol.DPAD_UP
        val down = Protocol.DPAD_DOWN
        val left = Protocol.DPAD_LEFT
        val right = Protocol.DPAD_RIGHT
        // Screen angles: 0° = right, counter-clockwise, screen Y negated.
        val expected = intArrayOf(right, up or right, up, up or left, left, down or left, down, down or right)
        for (k in 0 until 8) {
            for (offset in doubleArrayOf(0.0, -20.0, 20.0)) {
                val a = Math.toRadians(k * 45.0 + offset)
                val bits = StickMath.dpad((r * cos(a)).toFloat(), (-r * sin(a)).toFloat(), r, 0.25f)
                assertEquals("sector $k offset $offset", expected[k], bits)
            }
        }
        assertEquals(0, StickMath.dpad(0f, 0f, r, 0.25f))
        assertEquals(0, StickMath.dpad(0.2f * r, -0.1f * r, r, 0.25f))
        // A finger far outside the pad still reports its direction (the pointer owns the D-pad).
        assertEquals(up, StickMath.dpad(0f, -5 * r, r, 0.25f))
    }
}
