package com.mobilecontroller.client.input

import com.mobilecontroller.client.protocol.Protocol
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
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

    /** The outer band is "full deflection already"; more than this would leave too little travel. */
    const val MAX_OUTER_DEADZONE = 0.3f

    // Deadzone shapes, see the full stick().
    const val DZ_RADIAL = 0
    const val DZ_AXIAL = 1
    const val DZ_SNAP = 2

    /** Snap range of [DZ_SNAP]: directions within about 12° of an axis become that axis. */
    const val SNAP_ANGLE = 0.21f
    private val SNAP_SIN = sin(SNAP_ANGLE)

    /** The equal-sector D-pad: every direction and diagonal gets 45°. */
    const val DPAD_EQUAL_DIAGONAL = (PI / 8).toFloat()

    private const val SECTOR = (PI / 4).toFloat()
    private const val TWO_PI = (2 * PI).toFloat()
    private const val QUARTER = (PI / 2).toFloat()
    private const val EIGHTH = (PI / 4).toFloat()

    // Quadrant q covers [q * 90°, (q + 1) * 90°), counter-clockwise from +X with +Y up.
    private val CARDINAL_BITS = intArrayOf(Protocol.DPAD_RIGHT, Protocol.DPAD_UP, Protocol.DPAD_LEFT, Protocol.DPAD_DOWN)
    private val DIAGONAL_BITS = intArrayOf(
        Protocol.DPAD_UP or Protocol.DPAD_RIGHT,
        Protocol.DPAD_UP or Protocol.DPAD_LEFT,
        Protocol.DPAD_DOWN or Protocol.DPAD_LEFT,
        Protocol.DPAD_DOWN or Protocol.DPAD_RIGHT,
    )

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
    fun stick(dx: Float, dy: Float, radius: Float, deadzone: Float, antiDeadzone: Float, curve: Float, sensitivity: Float): Int =
        stick(dx, dy, radius, deadzone, 0f, DZ_RADIAL, antiDeadzone, curve, sensitivity)

    /**
     * The full stick: [inner] deadzone and [outer] saturation band (fractions of [radius]), the
     * deadzone's [shape] ([DZ_RADIAL], [DZ_AXIAL] or [DZ_SNAP]), then the game shaping of [stick].
     *
     * - Radial: one circle around the centre; the direction is always exactly the finger's.
     * - Axial: each axis has its own deadzone, so a thumb pushed mostly up sends pure up (menus, 2D
     *   games), at the cost of directions near the axes.
     * - Snap: radial, then directions within [SNAP_ANGLE] of an axis snap onto it; diagonals stay free.
     */
    fun stick(
        dx: Float, dy: Float, radius: Float, inner: Float, outer: Float, deadzoneShape: Int,
        antiDeadzone: Float, curve: Float, sensitivity: Float,
    ): Int {
        var nx = dx / radius
        var ny = -dy / radius
        val dz = inner.coerceIn(0f, MAX_DEADZONE)
        val live = 1f - dz - outer.coerceIn(0f, MAX_OUTER_DEADZONE)
        if (deadzoneShape == DZ_AXIAL) {
            nx = axialTravel(nx, dz, live)
            ny = axialTravel(ny, dz, live)
            val m = sqrt(nx * nx + ny * ny)
            if (!(m > 0f)) return 0
            val travel = min(1f, m * sensitivity)
            val scale = shape(travel, antiDeadzone, curve) / m * AXIS_MAX
            return pack(axis(nx * scale), axis(ny * scale))
        }
        val magnitude = sqrt(nx * nx + ny * ny)
        if (magnitude <= dz || !(magnitude > 0f)) return 0
        if (deadzoneShape == DZ_SNAP) {
            // Snap onto an axis when the other component is a small share of the deflection.
            if (abs(nx) < magnitude * SNAP_SIN) nx = 0f else if (abs(ny) < magnitude * SNAP_SIN) ny = 0f
            if (nx == 0f) ny = if (ny < 0f) -magnitude else magnitude
            if (ny == 0f) nx = if (nx < 0f) -magnitude else magnitude
        }
        val travel = min(1f, (magnitude - dz) / live * sensitivity)
        val scale = shape(travel, antiDeadzone, curve) / magnitude * AXIS_MAX
        return pack(axis(nx * scale), axis(ny * scale))
    }

    /** One axis of an axial deadzone: signed travel past [dz], rescaled over the [live] band, clamped. */
    private fun axialTravel(v: Float, dz: Float, live: Float): Float {
        val a = abs(v)
        if (a <= dz) return 0f
        val t = min(1f, (a - dz) / live)
        return if (v < 0f) -t else t
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
    fun fullDeflection(deadzone: Float, sensitivity: Float): Float = fullDeflection(deadzone, 0f, sensitivity)

    /** [fullDeflection] with an [outer] saturation band. */
    fun fullDeflection(inner: Float, outer: Float, sensitivity: Float): Float {
        val dz = inner.coerceIn(0f, MAX_DEADZONE)
        return dz + (1f - dz - outer.coerceIn(0f, MAX_OUTER_DEADZONE)) / sensitivity
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

    /**
     * [dpad] with adjustable diagonals: a diagonal covers [diagonalHalfAngle] (radians) either side of
     * 45°, so a wider value makes diagonals easier to hit and a narrower one makes the cardinal
     * directions safer. [fourWay] never presses two directions (fighting games, menus, grid movement).
     */
    fun dpad(dx: Float, dy: Float, radius: Float, deadzone: Float, diagonalHalfAngle: Float, fourWay: Boolean): Int {
        val dead = radius * deadzone
        if (dx * dx + dy * dy <= dead * dead) return 0
        var a = atan2(-dy, dx)
        if (a < 0f) a += TWO_PI
        val quadrant = (a / QUARTER).toInt()
        val within = a - quadrant * QUARTER
        val q = quadrant and 3
        if (!fourWay && abs(within - EIGHTH) < diagonalHalfAngle) return DIAGONAL_BITS[q]
        return CARDINAL_BITS[if (within < EIGHTH) q else (q + 1) and 3]
    }

    fun pack(x: Int, y: Int): Int = (x shl 16) or (y and 0xFFFF)

    fun unpackX(packed: Int): Int = packed shr 16

    fun unpackY(packed: Int): Int = packed.toShort().toInt()

    private fun axis(v: Float): Int = v.roundToInt().coerceIn(-AXIS_MAX, AXIS_MAX)
}
