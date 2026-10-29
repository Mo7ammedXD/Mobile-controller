package com.mobilecontroller.client

import com.mobilecontroller.client.protocol.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadStateTest {
    private val state = GamepadState()
    private val out = ByteArray(Protocol.INPUT_SIZE)

    private fun snap(): ByteArray {
        state.snapshot(out)
        return out
    }

    private fun seq() = Protocol.u16(out, 2)
    private fun buttons() = Protocol.u16(out, 4)
    private fun lt() = Protocol.u8(out, 6)
    private fun rt() = Protocol.u8(out, 7)

    @Test
    fun sequenceIncrementsAndWraps() {
        snap()
        assertEquals(0, seq())
        snap()
        assertEquals(1, seq())
        repeat(65534) { snap() } // seq 2..65535
        assertEquals(65535, seq())
        snap()
        assertEquals(0, seq())
        snap()
        assertEquals(1, seq())
    }

    @Test
    fun analogTriggersAreSentAndDigitalWins() {
        assertTrue(state.setTrigger(right = true, 128))
        assertFalse(state.setTrigger(right = true, 128))
        state.setTrigger(right = false, 40)
        snap()
        assertEquals(40, lt())
        assertEquals(128, rt())
        // A digital RT (button or toggle) always means full pull, whatever the pedal says.
        state.setDigital(GamepadState.RT, GamepadState.RT)
        snap()
        assertEquals(255, rt())
    }

    @Test
    fun aPedalTapBetweenSnapshotsIsStillSentThenReleased() {
        state.setTrigger(right = true, 200)
        state.setTrigger(right = true, 0)
        snap()
        assertEquals(200, rt())
        // The release is owed immediately rather than at the next heartbeat.
        assertTrue(state.dirty)
        snap()
        assertEquals(0, rt())
        assertFalse(state.dirty)
    }

    @Test
    fun releasingAHeldPedalIsSentAtOnce() {
        state.setTrigger(right = true, 255)
        snap()
        assertEquals(255, rt())
        // Already on the wire: lifting the thumb must send 0 next, not repeat the old value.
        state.setTrigger(right = true, 0)
        snap()
        assertEquals(0, rt())
    }

    @Test
    fun sequenceRestartsWithANewSocket() {
        repeat(10) { snap() }
        state.resetSequence()
        snap()
        assertEquals(0, seq())
    }

    @Test
    fun pressAndReleaseBeforeASnapshotIsStillSentOnceThenReleased() {
        assertTrue(state.setDigital(Protocol.A, Protocol.A))
        assertTrue(state.setDigital(Protocol.A, 0))
        assertTrue(state.dirty)
        snap()
        assertEquals(Protocol.A, buttons())
        // The release is still owed, so the sender must not wait for the next heartbeat.
        assertTrue(state.dirty)
        snap()
        assertEquals(0, buttons())
        assertFalse(state.dirty)
    }

    @Test
    fun digitalTriggersLatchLikeButtons() {
        state.setDigital(GamepadState.LT, GamepadState.LT)
        state.setDigital(GamepadState.LT, 0)
        state.setDigital(GamepadState.RT, GamepadState.RT)
        snap()
        assertEquals(255, lt())
        assertEquals(255, rt())
        assertEquals(0, buttons())
        snap()
        assertEquals(0, lt())
        assertEquals(255, rt())
    }

    @Test
    fun heldButtonsStayPressedAndCleanSnapshotsClearDirty() {
        state.setDigital(Protocol.B or Protocol.X, Protocol.B or Protocol.X)
        snap()
        assertEquals(Protocol.B or Protocol.X, buttons())
        assertFalse(state.dirty)
        snap()
        assertEquals(Protocol.B or Protocol.X, buttons())
        assertFalse(state.setDigital(Protocol.B, Protocol.B))
    }

    @Test
    fun setDigitalOnlyTouchesMaskedBits() {
        state.setDigital(Protocol.DPAD_MASK, Protocol.DPAD_UP or Protocol.DPAD_RIGHT)
        state.setDigital(Protocol.A, Protocol.A)
        state.setDigital(Protocol.DPAD_MASK, Protocol.DPAD_RIGHT)
        snap() // Up was latched while held, so it is in this snapshot, released in the next.
        assertEquals(Protocol.DPAD_UP or Protocol.DPAD_RIGHT or Protocol.A, buttons())
        snap()
        assertEquals(Protocol.DPAD_RIGHT or Protocol.A, buttons())
    }

    @Test
    fun reservedBitIsNeverSent() {
        state.setDigital(-1, -1)
        snap()
        assertEquals(0, buttons() and Protocol.RESERVED)
        assertEquals(0xF7FF, buttons())
        assertEquals(255, lt())
        assertEquals(255, rt())
    }

    // --- Minimum press ---------------------------------------------------------------------------

    private var now = 1_000_000_000L
    private val timed = GamepadState { now }.apply { minPressNanos = ms(20) }

    private fun ms(v: Int) = v * 1_000_000L

    private fun timedButtons(): Int {
        timed.snapshot(out)
        return buttons()
    }

    @Test
    fun aQuickTapIsHeldForTheMinimumPress() {
        timed.setDigital(Protocol.A, Protocol.A)
        now += ms(1)
        timed.setDigital(Protocol.A, 0)
        assertEquals(Protocol.A, timedButtons())
        // The release waits for the minimum press: nothing is owed now, but a deadline is set.
        assertFalse(timed.dirty)
        assertEquals(now - ms(1) + ms(20), timed.deadlineNanos)
        now += ms(5)
        assertEquals(Protocol.A, timedButtons())
        now = timed.deadlineNanos
        assertEquals(0, timedButtons())
        assertEquals(Long.MAX_VALUE, timed.deadlineNanos)
    }

    @Test
    fun aDoubleTapStaysTwoTaps() {
        val start = now
        for (t in intArrayOf(0, 2, 4, 6)) {
            now = start + ms(t)
            timed.setDigital(Protocol.B, if (t % 4 == 0) Protocol.B else 0)
        }
        // Pressed for 20 ms, released for 20 ms, pressed for 20 ms, released.
        val expected = intArrayOf(Protocol.B, 0, Protocol.B, 0)
        for ((i, at) in intArrayOf(6, 20, 40, 60).withIndex()) {
            now = start + ms(at)
            assertEquals("at $at ms", expected[i], timedButtons())
        }
        assertFalse(timed.dirty)
        assertEquals(Long.MAX_VALUE, timed.deadlineNanos)
    }

    @Test
    fun mashingNeverLagsMoreThanThreeEdges() {
        val start = now
        // Ten taps within 10 ms: far faster than a minimum press can show.
        for (i in 0 until 20) {
            now = start + ms(i) / 2
            timed.setDigital(Protocol.X, if (i % 2 == 0) Protocol.X else 0)
        }
        assertEquals(Protocol.X, timedButtons())
        // Snapshots every 5 ms, like heartbeats: the output catches up with the finger (released)
        // within three minimum presses, and nothing is owed afterwards.
        var t = 10
        while (t <= 70) {
            now = start + ms(t)
            timedButtons()
            t += 5
        }
        assertEquals(0, buttons())
        assertEquals(Long.MAX_VALUE, timed.deadlineNanos)
    }

    @Test
    fun aLongHoldIsReleasedAtOnce() {
        timed.setDigital(Protocol.Y, Protocol.Y)
        assertEquals(Protocol.Y, timedButtons())
        now += ms(500)
        timed.setDigital(Protocol.Y, 0)
        assertTrue(timed.dirty)
        assertEquals(0, timedButtons())
    }

    @Test
    fun neutralizeDropsOwedEdges() {
        timed.setDigital(Protocol.A, Protocol.A)
        timed.setDigital(Protocol.A, 0)
        timedButtons()
        timed.neutralize()
        assertEquals(Long.MAX_VALUE, timed.deadlineNanos)
        assertEquals(0, timedButtons())
        // A new press right after the neutral state is not held back by the old one.
        timed.setDigital(Protocol.A, Protocol.A)
        assertEquals(Protocol.A, timedButtons())
    }

    @Test
    fun localBitsAreHeldButNeverSent() {
        val ratchet = 1 shl GamepadState.LOCAL_BIT_SHIFT
        assertTrue(state.setDigital(ratchet or Protocol.A, ratchet or Protocol.A))
        assertEquals(ratchet or Protocol.A, state.heldBits())
        snap()
        assertEquals(Protocol.A, buttons())
        assertEquals(0, Protocol.u8(out, 6))
        assertEquals(0, ratchet and GamepadState.LOCAL_MASK.inv())
    }

    @Test
    fun sticksAreSentAndNeutralizeClearsEverything() {
        assertTrue(state.setLeftStick(1000, -2000))
        assertTrue(state.setRightStick(-32767, 32767))
        assertFalse(state.setRightStick(-32767, 32767))
        state.setDigital(Protocol.Y or GamepadState.LT, Protocol.Y or GamepadState.LT)
        snap()
        assertEquals(1000, Protocol.i16(out, 8))
        assertEquals(-2000, Protocol.i16(out, 10))
        assertEquals(-32767, Protocol.i16(out, 12))
        assertEquals(32767, Protocol.i16(out, 14))
        state.neutralize()
        assertTrue(state.dirty)
        snap()
        for (i in 4 until Protocol.INPUT_SIZE) assertEquals("byte $i", 0, out[i].toInt())
    }
}
