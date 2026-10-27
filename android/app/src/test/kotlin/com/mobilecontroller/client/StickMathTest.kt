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
