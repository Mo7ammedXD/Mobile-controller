package com.mobilecontroller.client

import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Touch routing in the classic layout (the default mode); mode-specific controls are in GameModeTest. */
class TouchRouterTest {
    private val layout = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
    private val state = GamepadState()
    private val router = TouchRouter(layout, state).apply { deadzone = 0.05f }
    private val out = ByteArray(Protocol.INPUT_SIZE)

    private fun snap(): ByteArray = out.also { state.snapshot(it) }
    private fun buttons() = Protocol.u16(snap(), 4)
    private fun id(name: String) = layout.indexOf(name).also { check(it >= 0) { name } }
    private fun cx(name: String) = layout.x[id(name)]
    private fun cy(name: String) = layout.y[id(name)]

    @Test
    fun tapOnAButtonPressesAndReleasesIt() {
        val flags = router.down(0, cx("A"), cy("A"))
        assertTrue((flags and TouchRouter.STATE) != 0)
        assertTrue((flags and TouchRouter.PRESS) != 0)
        assertEquals(Protocol.A, buttons())
        router.up(0)
        assertEquals(0, buttons())
    }

    @Test
    fun everyDigitalControlDrivesItsBit() {
        val bits = mapOf(
            "A" to Protocol.A, "B" to Protocol.B, "X" to Protocol.X, "Y" to Protocol.Y,
            "LB" to Protocol.LEFT_SHOULDER, "RB" to Protocol.RIGHT_SHOULDER,
            "BACK" to Protocol.BACK, "START" to Protocol.START, "GUIDE" to Protocol.GUIDE,
            "L3" to Protocol.LEFT_THUMB, "R3" to Protocol.RIGHT_THUMB,
        )
        for ((name, bit) in bits) {
            router.down(1, cx(name), cy(name))
            assertEquals(name, bit, buttons())
            router.up(1)
            assertEquals(0, buttons())
        }
        router.down(2, cx("LT"), cy("LT"))
        router.down(3, cx("RT"), cy("RT"))
        snap()
        assertEquals(255, Protocol.u8(out, 6))
        assertEquals(255, Protocol.u8(out, 7))
    }

    @Test
    fun floatingStickCentresWhereTheThumbLands() {
        val ls = id("LS")
        val r = layout.r[ls]
        val x0 = layout.x[ls] + r * 0.6f
        val y0 = layout.y[ls] - r * 0.4f
        router.down(0, x0, y0)
        snap()
        assertEquals(0, Protocol.i16(out, 8))
        assertEquals(0, Protocol.i16(out, 10))
        router.move(0, x0 + r, y0)
        snap()
        assertEquals(32767, Protocol.i16(out, 8))
        assertEquals(0, Protocol.i16(out, 10))
        // Leaving the zone entirely keeps ownership; moving up is +Y on the wire.
        router.move(0, x0, y0 - 10 * r)
        snap()
        assertEquals(0, Protocol.i16(out, 8))
        assertEquals(32767, Protocol.i16(out, 10))
        router.up(0)
        snap()
        assertEquals(0, Protocol.i16(out, 10))
    }

    @Test
    fun rightStickDrivesTheRightAxes() {
        val rs = id("RS")
        router.down(4, layout.x[rs], layout.y[rs])
        router.move(4, layout.x[rs] - layout.r[rs], layout.y[rs])
        snap()
        assertEquals(-32767, Protocol.i16(out, 12))
        assertEquals(0, Protocol.i16(out, 8))
    }

    @Test
    fun stickCentreIsClampedSoTheRingStaysInside() {
        val ls = id("LS")
        val zone = layout.r[ls] * ControlLayout.STICK_ZONE
        // Lower-left corner of the zone, which pokes past the screen edge.
        val px = maxOf(1f, layout.x[ls] - zone + 1)
        val py = minOf(1079f, layout.y[ls] + zone - 1)
        router.down(0, px, py)
        assertTrue(router.isHeld(ls))
        assertTrue(router.stickX[ls] >= layout.r[ls])
        assertTrue(router.stickY[ls] <= 1080f - layout.r[ls])
    }

    @Test
    fun fingerSlidesBetweenFaceButtons() {
        router.down(0, cx("A"), cy("A"))
        assertEquals(Protocol.A, buttons())
        val flags = router.move(0, cx("B"), cy("B"))
        assertTrue((flags and TouchRouter.PRESS) != 0)
        // A was released and B pressed in the same update.
        assertEquals(Protocol.B, buttons())
        assertFalse(router.isHeld(id("A")))
        assertTrue(router.isHeld(id("B")))
        router.up(0)
        assertEquals(0, buttons())
    }

    @Test
    fun shouldersDoNotSlide() {
        router.down(0, cx("LB"), cy("LB"))
        router.move(0, cx("A"), cy("A"))
        assertEquals(Protocol.LEFT_SHOULDER, buttons())
    }

    @Test
    fun dpadSlidesBetweenDirections() {
        val d = id("DPAD")
        val r = layout.r[d]
        router.down(0, layout.x[d], layout.y[d] - r * 0.8f)
        assertEquals(Protocol.DPAD_UP, buttons())
        router.move(0, layout.x[d] + r * 0.8f, layout.y[d] - r * 0.8f)
        assertEquals(Protocol.DPAD_UP or Protocol.DPAD_RIGHT, buttons())
        router.move(0, layout.x[d] + r * 0.8f, layout.y[d])
        assertEquals(Protocol.DPAD_RIGHT, buttons())
        router.up(0)
        assertEquals(0, buttons())
    }

    @Test
    fun simultaneousFingersAndCancel() {
        val ls = id("LS")
        router.down(0, layout.x[ls], layout.y[ls])
        router.move(0, layout.x[ls], layout.y[ls] + layout.r[ls])
        router.down(1, cx("A"), cy("A"))
        router.down(2, cx("RB"), cy("RB"))
        snap()
        assertEquals(Protocol.A or Protocol.RIGHT_SHOULDER, Protocol.u16(out, 4))
        assertEquals(-32767, Protocol.i16(out, 10))
        router.cancelAll()
        snap()
        for (i in 4 until Protocol.INPUT_SIZE) assertEquals(0, out[i].toInt())
    }

    @Test
    fun stickReportsTheRimOnceWhenFullOutputIsReached() {
        val ls = id("LS")
        val r = layout.r[ls]
        router.down(0, layout.x[ls], layout.y[ls])
        assertEquals(0, router.move(0, layout.x[ls] + 0.5f * r, layout.y[ls]) and TouchRouter.RIM)
        assertTrue((router.move(0, layout.x[ls] + r, layout.y[ls]) and TouchRouter.RIM) != 0)
        // Staying at (or past) the rim does not tick again; coming back in and out re-arms it.
        assertEquals(0, router.move(0, layout.x[ls] + 1.2f * r, layout.y[ls]) and TouchRouter.RIM)
        router.move(0, layout.x[ls] + 0.5f * r, layout.y[ls])
        assertTrue((router.move(0, layout.x[ls] + r, layout.y[ls]) and TouchRouter.RIM) != 0)
    }

    @Test
    fun sensitivityReachesFullOutputEarlierAndAntiDeadzoneSkipsTheGamesDeadzone() {
        val ls = id("LS")
        val r = layout.r[ls]
        router.sensitivity[0] = 2f
        router.antiDeadzone[0] = 0.25f
        router.down(0, layout.x[ls], layout.y[ls])
        // Half the travel is already full output at 200 % sensitivity.
        router.move(0, layout.x[ls] + 0.6f * r, layout.y[ls])
        snap()
        assertEquals(32767, Protocol.i16(out, 8))
        // A small push starts just past the game's ~25 % deadzone instead of at 0.
        router.move(0, layout.x[ls] + 0.07f * r, layout.y[ls])
        snap()
        val lx = Protocol.i16(out, 8)
        assertTrue("lx=$lx", lx in 8192..9500)
    }

    @Test
    fun fixedStickDeflectsFromItsDrawnCentre() {
        val ls = id("LS")
        val r = layout.r[ls]
        router.anchor[0] = TouchRouter.ANCHOR_FIXED
        // Landing half a radius right of the drawn centre is already half deflection.
        router.down(0, layout.x[ls] + 0.5f * r, layout.y[ls])
        snap()
        assertTrue(Protocol.i16(out, 8) in 15000..17500)
        assertEquals(layout.x[ls], router.stickX[ls], 0f)
    }

    @Test
    fun followStickDragsItsCentreAlong() {
        val ls = id("LS")
        val r = layout.r[ls]
        router.anchor[0] = TouchRouter.ANCHOR_FOLLOW
        val x0 = layout.x[ls]
        val y0 = layout.y[ls]
        router.down(0, x0, y0)
        router.move(0, x0 + 3 * r, y0)
        // The centre followed to one radius behind the thumb...
        assertEquals(x0 + 2 * r, router.stickX[ls], 0.5f)
        // ...so reversing direction is a short move: one radius back is neutral again.
        router.move(0, x0 + 2 * r, y0)
        snap()
        assertEquals(0, Protocol.i16(out, 8))
        router.move(0, x0 + r, y0)
        snap()
        assertEquals(-32767, Protocol.i16(out, 8))
    }

    @Test
    fun invertFlipsOnlyThatStick() {
        val rs = id("RS")
        val r = layout.r[rs]
        router.invertY[1] = true
        router.down(0, layout.x[rs], layout.y[rs])
        router.move(0, layout.x[rs] + r, layout.y[rs] - r)
        snap()
        assertTrue(Protocol.i16(out, 12) > 20000)
        assertTrue(Protocol.i16(out, 14) < -20000)
    }

    @Test
    fun edgeRingRadiusIsAdjustable() {
        val l = ControlLayout().apply { load(GameModes.FOOTBALL); place(0f, 0f, 2400f, 1080f) }
        val football = TouchRouter(l, GamepadState())
        val move = l.indexOf("MOVE")
        val r = l.r[move]
        football.down(0, l.x[move], l.y[move])
        football.move(0, l.x[move] + 1.25f * r, l.y[move])
        assertFalse(football.edgeOn[move])
        football.edgeOnRadius = 1.2f
        football.edgeOffRadius = 1.1f
        football.move(0, l.x[move] + 1.25f * r, l.y[move])
        assertTrue(football.edgeOn[move])
    }

    @Test
    fun aSecondFingerCannotStealAHeldControl() {
        router.down(0, cx("A"), cy("A"))
        router.down(1, cx("A"), cy("A"))
        router.up(1)
        assertEquals(Protocol.A, buttons())
    }

    @Test
    fun gearTapIsReportedOnLiftOnly() {
        val down = router.down(0, cx("MENU"), cy("MENU"))
        assertEquals(0, down and TouchRouter.MENU_TAP)
        assertEquals(0, down and TouchRouter.STATE)
        assertNotEquals(0, router.up(0) and TouchRouter.MENU_TAP)
        router.down(0, cx("MENU"), cy("MENU"))
        assertEquals(0, router.cancelAll() and TouchRouter.MENU_TAP)
    }

    @Test
    fun emptySpaceAndBadPointerIdsAreIgnored() {
        // Empty glass changes nothing but reports MISS, so the player feels the miss.
        assertEquals(TouchRouter.MISS, router.down(0, 1200f, 400f))
        assertEquals(0, router.down(-1, cx("A"), cy("A")))
        assertEquals(0, router.down(TouchRouter.MAX_POINTERS, cx("A"), cy("A")))
        assertEquals(0, router.move(5, 0f, 0f))
        assertEquals(0, router.up(5))
        assertEquals(0, buttons())
    }
}
