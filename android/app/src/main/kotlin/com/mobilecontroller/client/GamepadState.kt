package com.mobilecontroller.client

import com.mobilecontroller.client.protocol.Protocol
import kotlin.math.max

/**
 * The controller state shared between the UI thread (writer) and the sender thread (reader).
 *
 * Digital inputs live in one bit set: bits 0-15 are the XUSB buttons, [LT] and [RT] are the digital
 * triggers, so buttons and triggers share one latching rule. Analog triggers (racing pedals) are kept
 * separately and the wire carries the larger of the two. Every access holds a private monitor:
 * uncontended thin locking costs a few nanoseconds and, unlike copy-on-write snapshots, never allocates.
 */
class GamepadState {
    private val lock = Any()
    private var held = 0
    private var latched = 0
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

    /** Sets the bits selected by [mask] to [bits]. Returns true if anything changed. */
    fun setDigital(mask: Int, bits: Int): Boolean = synchronized(lock) {
        val next = (held and mask.inv()) or (bits and mask)
        if (next == held) return false
        // §3.4: a press must reach the wire even if it is released before the next snapshot.
        latched = latched or (next and held.inv())
        held = next
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

    /** Digital bits currently held (gyro "only while aiming" reads it). */
    fun heldBits(): Int = synchronized(lock) { held }

    /** Releases everything, including pending latched presses, so the next snapshot is fully neutral. */
    fun neutralize() = synchronized(lock) {
        held = 0
        latched = 0
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

    /** Encodes the next INPUT into [out] (16 bytes), consuming latched presses and advancing seq. */
    fun snapshot(out: ByteArray) = synchronized(lock) {
        val sent = held or latched
        Protocol.writeInput(
            out, seq, sent,
            if ((sent and LT) != 0) TRIGGER_MAX else max(ltAnalog, ltPeak),
            if ((sent and RT) != 0) TRIGGER_MAX else max(rtAnalog, rtPeak),
            axis(lx + motionLx), ly, axis(rx + motionRx), axis(ry + motionRy),
        )
        seq = (seq + 1) and 0xFFFF
        // A latched press (or pedal peak) that is already released still owes the server a release
        // packet; staying dirty makes the sender emit it after the 2 ms spacing instead of waiting for
        // the heartbeat.
        dirty = (latched and held.inv()) != 0 || ltPeak > ltAnalog || rtPeak > rtAnalog
        latched = 0
        // Peaks only cover values set after this snapshot; the current value is sent anyway.
        ltPeak = 0
        rtPeak = 0
    }

    private fun axis(v: Int): Int = if (v > AXIS_MAX) AXIS_MAX else if (v < -AXIS_MAX) -AXIS_MAX else v

    companion object {
        private const val AXIS_MAX = 32767
        const val LT = 1 shl 16
        const val RT = 1 shl 17
        const val TRIGGER_MAX = 255
    }
}
