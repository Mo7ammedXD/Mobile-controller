package com.mobilecontroller.client.input

import com.mobilecontroller.client.protocol.Protocol
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Touch geometry -> controller values. Pure functions on primitives; results that need two numbers
 * are packed into one Int so the touch path never allocates a pair.
 */
object StickMath {
    const val AXIS_MAX = 32767

    /** Deadzone fraction above which the stick could no longer reach full deflection sensibly. */
    const val MAX_DEADZONE = 0.5f

    /** Beyond this an anti-deadzone stops removing the game's deadzone and starts adding a jump. */
    const val MAX_ANTI_DEADZONE = 0.4f

    private const val SECTOR = (PI / 4).toFloat()

    // Index = round(angle / 45°) mod 8, counter-clockwise from +X with +Y up.
    private val SECTOR_BITS = intArrayOf(
        Protocol.DPAD_RIGHT,
        Protocol.DPAD_UP or Protocol.DPAD_RIGHT,
        Protocol.DPAD_UP,
        Protocol.DPAD_UP or Protocol.DPAD_LEFT,
        Protocol.DPAD_LEFT,
        Protocol.DPAD_DOWN or Protocol.DPAD_LEFT,
        Protocol.DPAD_DOWN,
        Protocol.DPAD_DOWN or Protocol.DPAD_RIGHT,
    )

    /**
     * Finger offset ([dx], [dy] in screen pixels from the stick centre) -> packed i16 axes.
     *
     * The deflection is clamped radially to the unit circle, so a diagonal tops out at ~23170 per axis
     * like a physical stick (a per-axis clamp would report the impossible 32767/32767). A radial
     * [deadzone] (fraction of [radius]) suppresses touch jitter, and the remaining travel is rescaled
     * so full deflection is still reachable. Screen Y grows downward, the wire's +Y is up.
     */
    fun stick(dx: Float, dy: Float, radius: Float, deadzone: Float): Int = stick(dx, dy, radius, deadzone, 0f, 1f, 1f)

    /**
     * [stick] with the output shaped for games (see [shape]): [sensitivity] reaches full deflection
     * after 1 / sensitivity of the travel past the deadzone, [curve] and [antiDeadzone] shape the
     * magnitude while the direction stays exactly the finger's.
     */
    fun stick(dx: Float, dy: Float, radius: Float, deadzone: Float, antiDeadzone: Float, curve: Float, sensitivity: Float): Int {
        val nx = dx / radius
        val ny = -dy / radius
        val magnitude = sqrt(nx * nx + ny * ny)
        val dz = deadzone.coerceIn(0f, MAX_DEADZONE)
        if (magnitude <= dz || !(magnitude > 0f)) return 0
        val travel = min(1f, (magnitude - dz) / (1f - dz) * sensitivity)
        val scale = shape(travel, antiDeadzone, curve) / magnitude * AXIS_MAX
        return pack(axis(nx * scale), axis(ny * scale))
    }

    /**
     * Output magnitude (0..1) for a travel of [t] (0..1). [curve] > 1 bends the response so small
     * movements stay small (fine aiming) while full travel is still full output. [antiDeadzone] makes the
     * output start at that fraction instead of 0: XInput games ignore roughly the first quarter of a
     * stick's range, which on a 2 cm touch stick feels like lag; starting just past it removes that.
     */
    fun shape(t: Float, antiDeadzone: Float, curve: Float): Float {
        if (!(t > 0f)) return 0f
        val ad = antiDeadzone.coerceIn(0f, MAX_ANTI_DEADZONE)
        val bent = if (curve == 1f) t else t.pow(curve)
        return ad + (1f - ad) * bent
    }

    /** Travel past the deadzone (in radii) at which [stick] reaches full output. */
    fun fullDeflection(deadzone: Float, sensitivity: Float): Float {
        val dz = deadzone.coerceIn(0f, MAX_DEADZONE)
        return dz + (1f - dz) / sensitivity
    }

    /**
     * Finger offset from the D-pad centre -> D-pad bitmask: 8 sectors of 45° (diagonals press two
     * directions); nothing inside the centre [deadzone] (fraction of [radius]).
     */
    fun dpad(dx: Float, dy: Float, radius: Float, deadzone: Float): Int {
        val dead = radius * deadzone
        if (dx * dx + dy * dy <= dead * dead) return 0
        val sector = ((atan2(-dy, dx) / SECTOR).roundToInt() + 8) and 7
        return SECTOR_BITS[sector]
    }

    fun pack(x: Int, y: Int): Int = (x shl 16) or (y and 0xFFFF)

    fun unpackX(packed: Int): Int = packed shr 16

    fun unpackY(packed: Int): Int = packed.toShort().toInt()

    private fun axis(v: Float): Int = v.roundToInt().coerceIn(-AXIS_MAX, AXIS_MAX)
}
