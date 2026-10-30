package com.mobilecontroller.client

import com.mobilecontroller.client.protocol.Protocol
import kotlin.math.max

/**
 * The controller state shared between the UI thread (writer) and the sender thread (reader).
 *
 * Digital inputs live in one bit set: bits 0-15 are the XUSB buttons, [LT] and [RT] are the digital
 * triggers, so buttons and triggers share one edge rule. The [LOCAL_MASK] bits are phone-only
 * (e.g. a gyro gate): they are held like buttons but never reach the wire. Analog triggers (racing
 * pedals) are kept separately and the wire carries the larger of the two. Every access holds a private
 * monitor: uncontended thin locking costs a few nanoseconds and, unlike copy-on-write snapshots, never
 * allocates.
 *
 * What the fingers hold ([held]) and what goes on the wire ([out]) are separate. Every change of a
 * wire bit is an edge owed to the PC, and an edge is only applied once the previous state has been in
 * at least one packet (so a tap between two sends still arrives, §3.4) and has lasted [minPressNanos]
 * (so a game that polls once per frame sees the tap, and a lost datagram is repaired by the next
 * one). Edges queue up, so a quick double tap stays two taps; at most three are kept per bit, so
 * mashing a button never lags behind by more than about three minimum presses.
 */
class GamepadState(private val clock: Clock = Clock(System::nanoTime)) {
    /** Time source for the minimum press, replaceable in tests. Same base as [System.nanoTime]. */
    fun interface Clock {
        fun nanoTime(): Long
    }

    private val lock = Any()
    private var held = 0
    private var out = 0

    // Per wire bit: when its output last changed and how many edges it still owes.
    private val outSince = LongArray(WIRE_BIT_COUNT) { LONG_AGO }
    private val pending = IntArray(WIRE_BIT_COUNT)
    private var pendingMask = 0

    // Wire bits whose current output has been in at least one snapshot since it last changed.
    private var sentSinceEdge = WIRE_BITS
    private var ltAnalog = 0
    private var rtAnalog = 0

    // Highest analog value since the last snapshot: the analog counterpart of [latched].
    private var ltPeak = 0
    private var rtPeak = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0

    // Gyro aim / tilt steering, added to the touch sticks so both can be used at once.
    private var motionLx = 0
    private var motionRx = 0
    private var motionRy = 0
    private var seq = 0

    /** True when an INPUT should go out now rather than at the next heartbeat. Read without the lock. */
    @Volatile
    var dirty = false
        private set

    /**
     * [System.nanoTime] at which an owed edge becomes due ([Long.MAX_VALUE] if none waits for time): the
     * sender must send then even if nothing else changed. Read without the lock.
     */
    @Volatile
    var deadlineNanos = Long.MAX_VALUE
        private set

    /** Shortest time a wire bit keeps a state (pressed or released) on the wire; 0 = one packet. */
    @Volatile
    var minPressNanos = 0L

    /** Sets the bits selected by [mask] to [bits]. Returns true if anything changed. */
    fun setDigital(mask: Int, bits: Int): Boolean = synchronized(lock) {
        val next = (held and mask.inv()) or (bits and mask)
        if (next == held) return false
        var edges = (next xor held) and WIRE_BITS
        held = next
        while (edges != 0) {
            val bit = edges and -edges
            edges = edges xor bit
            val b = Integer.numberOfTrailingZeros(bit)
            // Two more edges than the output can show yet cancel out: the bit ends in the same state.
            pending[b] = if (pending[b] >= MAX_PENDING) pending[b] - 1 else pending[b] + 1
            if (pending[b] == 0) pendingMask = pendingMask and bit.inv() else pendingMask = pendingMask or bit
        }
        advance(clock.nanoTime())
        dirty = true
        true
    }

    /** Sets an analog trigger (0..255): [right] selects RT, otherwise LT. Returns true if it changed. */
    fun setTrigger(right: Boolean, value: Int): Boolean = synchronized(lock) {
        if (right) {
            if (value == rtAnalog) return false
            rtAnalog = value
            if (value > rtPeak) rtPeak = value
        } else {
            if (value == ltAnalog) return false
            ltAnalog = value
            if (value > ltPeak) ltPeak = value
        }
        dirty = true
        true
    }

    fun setLeftStick(x: Int, y: Int): Boolean = synchronized(lock) {
        if (x == lx && y == ly) return false
        lx = x
        ly = y
        dirty = true
        true
    }

    fun setRightStick(x: Int, y: Int): Boolean = synchronized(lock) {
        if (x == rx && y == ry) return false
        rx = x
        ry = y
        dirty = true
        true
    }

    /** Motion offsets from the sensor thread: [x] adds to the left stick's X, [rx] / [ry] to the right stick. */
    fun setMotion(x: Int, rx: Int, ry: Int): Boolean = synchronized(lock) {
        if (x == motionLx && rx == motionRx && ry == motionRy) return false
        motionLx = x
        motionRx = rx
        motionRy = ry
        dirty = true
        true
    }

    /** Digital bits the fingers hold right now, local bits included (gyro gates read it). */
    fun heldBits(): Int = synchronized(lock) { held }

    /** The gyro's current right-stick offset, packed like StickMath.pack (the settings' live gauge reads it). */
    fun motionRightStick(): Int = synchronized(lock) { (motionRx shl 16) or (motionRy and 0xFFFF) }

    /** Releases everything, including owed edges, so the next snapshot is fully neutral. */
    fun neutralize() = synchronized(lock) {
        held = 0
        out = 0
        outSince.fill(LONG_AGO)
        pending.fill(0)
        pendingMask = 0
        sentSinceEdge = WIRE_BITS
        deadlineNanos = Long.MAX_VALUE
        ltAnalog = 0
        rtAnalog = 0
        ltPeak = 0
        rtPeak = 0
        lx = 0
        ly = 0
        rx = 0
        ry = 0
        motionLx = 0
        motionRx = 0
        motionRy = 0
        dirty = true
    }

    /** §3.3: the sequence restarts at 0 whenever the client creates a socket. */
    fun resetSequence() = synchronized(lock) { seq = 0 }

    /** Encodes the next INPUT into [packet] (16 bytes), applying due edges and advancing seq. */
    fun snapshot(packet: ByteArray) = synchronized(lock) {
        val now = clock.nanoTime()
        advance(now)
        val sent = out
        Protocol.writeInput(
            packet, seq, sent,
            if ((sent and LT) != 0) TRIGGER_MAX else max(ltAnalog, ltPeak),
            if ((sent and RT) != 0) TRIGGER_MAX else max(rtAnalog, rtPeak),
            axis(lx + motionLx), ly, axis(rx + motionRx), axis(ry + motionRy),
        )
        seq = (seq + 1) and 0xFFFF
        sentSinceEdge = WIRE_BITS
        // An edge that only waited for this packet (a tap released before it, or a pedal peak) is owed
        // now: staying dirty makes the sender emit it after the 2 ms spacing instead of waiting for the
        // heartbeat. Edges that wait for the minimum press are covered by deadlineNanos instead.
        dirty = advance(now) || ltPeak > ltAnalog || rtPeak > rtAnalog
        // Peaks only cover values set after this snapshot; the current value is sent anyway.
        ltPeak = 0
        rtPeak = 0
    }

    /**
     * Applies every owed edge that is allowed now (at most one per bit: the new state must reach the wire
     * before the next edge), and recomputes [deadlineNanos]. Returns true if the output changed.
     */
    private fun advance(now: Long): Boolean {
        var changed = false
        var deadline = Long.MAX_VALUE
        val min = minPressNanos
        var p = pendingMask
        while (p != 0) {
            val bit = p and -p
            p = p xor bit
            // Not on the wire yet: the next snapshot frees it, and dirty already asks for one.
            if ((sentSinceEdge and bit) == 0) continue
            val b = Integer.numberOfTrailingZeros(bit)
            val due = outSince[b] + min
            if (now < due) {
                if (due < deadline) deadline = due
                continue
            }
            out = out xor bit
            outSince[b] = now
            sentSinceEdge = sentSinceEdge and bit.inv()
            if (--pending[b] == 0) pendingMask = pendingMask and bit.inv()
            changed = true
        }
        deadlineNanos = deadline
        return changed
    }

    private fun axis(v: Int): Int = if (v > AXIS_MAX) AXIS_MAX else if (v < -AXIS_MAX) -AXIS_MAX else v

    companion object {
        private const val AXIS_MAX = 32767
        const val LT = 1 shl 16
        const val RT = 1 shl 17
        const val TRIGGER_MAX = 255

        /** Phone-only actions (e.g. a gyro gate): held and read like buttons, never sent. */
        const val LOCAL_BIT_SHIFT = 20
        const val LOCAL_MASK = -1 shl LOCAL_BIT_SHIFT

        private const val WIRE_BIT_COUNT = 18
        private const val WIRE_BITS = (1 shl WIRE_BIT_COUNT) - 1
        // A double tap owes three edges once its first press is out (release, press, release).
        private const val MAX_PENDING = 3

        // Far enough back that the first edge is never held up, without overflowing `+ minPressNanos`.
        private const val LONG_AGO = Long.MIN_VALUE / 4
    }
}
