package com.mobilecontroller.client

import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.ControlSpec
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/** The game-mode control types: combos, slide groups, toggles, edge-ring sprint and analog pedals. */
class GameModeTest {
    private val layout = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
    private val state = GamepadState()
    private val router = TouchRouter(layout, state)
    private val out = ByteArray(Protocol.INPUT_SIZE)

    private fun use(mode: GameMode) {
        router.releaseAll()
        layout.load(mode)
        router.reset()
    }

    private fun snap(): ByteArray = out.also { state.snapshot(it) }
    private fun buttons() = Protocol.u16(snap(), 4)
    private fun lt() = Protocol.u8(snap(), 6)
    private fun rt() = Protocol.u8(snap(), 7)
    private fun id(name: String) = layout.indexOf(name).also { check(it >= 0) { name } }
    private fun down(pointer: Int, name: String) = router.down(pointer, layout.x[id(name)], layout.y[id(name)])
    private fun move(pointer: Int, name: String) = router.move(pointer, layout.x[id(name)], layout.y[id(name)])

    @Test
    fun everyModeIsWellFormed() {
        for (mode in GameModes.ALL) {
            val names = mode.controls.map { it.name }
            assertEquals(mode.id, names.size, names.toSet().size)
            assertTrue(mode.id, mode.controls.size <= ControlLayout.MAX_CONTROLS)
            assertEquals(mode.id, 1, mode.controls.count { it.kind == ControlSpec.MENU })
            for (s in mode.controls) {
                if (s.kind == ControlSpec.BUTTON) assertTrue("${mode.id} ${s.name} sends nothing", s.mask != 0)
                assertEquals("${mode.id} ${s.name} uses the reserved bit", 0, s.mask and Protocol.RESERVED)
            }
            assertEquals(mode, GameModes.byId(mode.id))
        }
        assertEquals(GameModes.CLASSIC, GameModes.byId("no-such-mode"))
        assertEquals(GameModes.CLASSIC, GameModes.byId(null))
    }

    @Test
    fun comboButtonSendsBothBitsAndSharedBitsSurviveItsRelease() {
        use(GameModes.FOOTBALL)
        down(0, "SHOOT")
        assertEquals(Protocol.X, buttons())
        down(1, "FINESSE")
        assertEquals(Protocol.X or Protocol.RIGHT_SHOULDER, buttons())
        // SHOOT still holds X when the combo that also sends X is released.
        router.up(1)
        assertEquals(Protocol.X, buttons())
        router.up(0)
        assertEquals(0, buttons())
    }

    @Test
    fun rollingFromBoostOverJumpPlusBoostNeverDropsBoost() {
        use(GameModes.ROCKET)
        down(0, "BOOST")
        assertEquals(Protocol.B, buttons())
        assertTrue((move(0, "JB") and TouchRouter.PRESS) != 0)
        assertEquals(Protocol.A or Protocol.B, buttons())
        move(0, "JUMP")
        assertEquals(Protocol.A, buttons())
        router.up(0)
        assertEquals(0, buttons())
    }

    @Test
    fun autoGasHoldsTheThrottleAndReverseOverridesIt() {
        use(GameModes.ROCKET)
        assertTrue((down(0, "GAS") and TouchRouter.TOGGLE_ON) != 0)
        router.up(0)
        assertTrue(router.toggleOn[id("GAS")])
        assertEquals(255, rt())
        assertEquals(0, lt())
        // While REV is held the throttle yields (a real pad would have both triggers fighting).
        down(1, "REV")
        snap()
        assertEquals(0, Protocol.u8(out, 7))
        assertEquals(255, Protocol.u8(out, 6))
        router.up(1)
        assertEquals(255, rt())
        // Steering and other fingers do not disturb it.
        down(2, "BOOST")
        assertEquals(255, rt())
        router.up(2)
        // Tapping again stops the car; so does any pause / dialog (releaseAll).
        assertTrue((down(0, "GAS") and TouchRouter.TOGGLE_OFF) != 0)
        router.up(0)
        assertEquals(0, rt())
        down(0, "GAS")
        router.up(0)
        assertEquals(255, rt())
        router.releaseAll()
        assertFalse(router.toggleOn[id("GAS")])
        assertEquals(0, rt())
    }

    @Test
    fun pushingPastTheRingSprintsWithHysteresis() {
        use(GameModes.FOOTBALL)
        val c = id("MOVE")
        val r = layout.r[c]
        val x0 = layout.x[c]
        val y0 = layout.y[c]
        router.down(0, x0, y0)
        router.move(0, x0 + r, y0)
        snap()
        assertEquals(32767, Protocol.i16(out, 8))
        assertEquals(0, Protocol.u8(out, 7))
        val flags = router.move(0, x0 + 1.4f * r, y0)
        assertTrue((flags and TouchRouter.EDGE) != 0)
        assertEquals(255, rt())
        // Inside the hysteresis band it keeps sprinting; well inside, it stops.
        router.move(0, x0 + 1.2f * r, y0)
        assertEquals(255, rt())
        router.move(0, x0 + 1.05f * r, y0)
        assertEquals(0, rt())
        // Lifting the thumb while sprinting stops the sprint too.
        router.move(0, x0, y0 - 2 * r)
        assertEquals(255, rt())
        router.up(0)
        assertEquals(0, rt())
        assertEquals(0, buttons())
    }

    @Test
    fun shooterSprintIsL3AndFireWorksFromEitherThumb() {
        use(GameModes.SHOOTER)
        val c = id("MOVE")
        router.down(0, layout.x[c], layout.y[c])
        router.move(0, layout.x[c], layout.y[c] - 1.5f * layout.r[c])
        assertEquals(Protocol.LEFT_THUMB, buttons())
        router.up(0)
        down(1, "FIRE_L")
        assertEquals(255, rt())
        down(2, "FIRE")
        router.up(1)
        // The other FIRE still holds the trigger.
        assertEquals(255, rt())
        router.up(2)
        assertEquals(0, rt())
    }

    @Test
    fun pedalsAreAnalogByHeight() {
        use(GameModes.RACING)
        val gas = id("GAS")
        val top = layout.y[gas] - layout.halfH[gas]
        val bottom = layout.y[gas] + layout.halfH[gas]
        router.down(0, layout.x[gas], bottom)
        assertEquals((TouchRouter.PEDAL_MIN * 255).roundToInt(), rt())
        router.move(0, layout.x[gas], (top + bottom) / 2)
        assertEquals(((TouchRouter.PEDAL_MIN + 0.5f * TouchRouter.PEDAL_GAIN) * 255).roundToInt(), rt())
        router.move(0, layout.x[gas], top + 2f)
        assertEquals(255, rt())
        // The finger may slide off the pedal without losing it.
        router.move(0, layout.x[gas] - 500f, top - 300f)
        assertEquals(255, rt())
        down(1, "BRAKE")
        assertTrue(lt() > 0)
        router.up(0)
        router.up(1)
        snap()
        assertEquals(0, Protocol.u8(out, 6))
        assertEquals(0, Protocol.u8(out, 7))
    }

    @Test
    fun arcadeGridSlidesForRollingInputs() {
        use(GameModes.ARCADE)
        down(0, "X")
        assertEquals(Protocol.X, buttons())
        move(0, "Y")
        assertEquals(Protocol.Y, buttons())
        move(0, "RB")
        assertEquals(Protocol.RIGHT_SHOULDER, buttons())
        // Two fingers on the grid: a throw (LP + LK).
        router.up(0)
        down(0, "X")
        down(1, "A")
        assertEquals(Protocol.X or Protocol.A, buttons())
    }

    @Test
    fun floatingClusterCentresTheTouchedButtonUnderTheThumb() {
        use(GameModes.ROCKET)
        router.floating = true
        val jump = id("JUMP")
        val boost = id("BOOST")
        val gap = layout.x[boost] - layout.x[jump]
        // The thumb lands a little left of and below JUMP's centre (still JUMP)...
        val px = layout.x[jump] - 0.4f * layout.r[jump]
        val py = layout.y[jump] + 0.3f * layout.r[jump]
        assertTrue((router.down(0, px, py) and TouchRouter.PRESS) != 0)
        assertEquals(Protocol.A, buttons())
        // ...so the cluster follows: JUMP is now exactly under it and BOOST kept its distance.
        assertEquals(px, layout.x[jump], 0.5f)
        assertEquals(py, layout.y[jump], 0.5f)
        assertEquals(gap, layout.x[boost] - layout.x[jump], 0.5f)
        // While a finger holds the cluster it stays put for other fingers.
        val x = layout.x[boost]
        router.down(1, layout.x[boost] + 0.3f * layout.r[boost], layout.y[boost])
        assertEquals(x, layout.x[boost], 0.01f)
        // Pause / dialogs send it home.
        router.releaseAll()
        assertEquals(layout.homeX(jump), layout.x[jump], 0.01f)
    }

    @Test
    fun floatingClusterCatchesNearMissesButNeverStealsFromAStick() {
        use(GameModes.ROCKET)
        router.floating = true
        val boost = id("BOOST")
        // 1.4 radii right of BOOST's centre: outside the normal hit slop, inside the floating catch.
        val px = layout.x[boost] + 1.4f * layout.r[boost]
        assertTrue(px < layout.right)
        router.down(0, px, layout.y[boost])
        assertEquals(Protocol.B, buttons())
        router.releaseAll()
        // A touch inside the steering zone always takes the stick.
        router.floating = true
        val steer = id("STEER")
        router.down(1, layout.x[steer], layout.y[steer])
        assertTrue(router.isHeld(steer))
    }

    @Test
    fun switchingModesReleasesEverything() {
        use(GameModes.CLASSIC)
        down(0, "A")
        router.down(1, layout.x[id("LS")], layout.y[id("LS")])
        router.move(1, layout.x[id("LS")] + 300f, layout.y[id("LS")])
        // The press reached the wire (otherwise its latch would rightly still owe one packet).
        assertEquals(Protocol.A, buttons())
        use(GameModes.RACING)
        snap()
        for (i in 4 until Protocol.INPUT_SIZE) assertEquals(0, out[i].toInt())
        assertFalse(router.isHeld(0))
    }
}
