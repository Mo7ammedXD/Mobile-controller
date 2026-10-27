package com.mobilecontroller.client.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Control geometry for the current [GameMode]: a persistent model (centre as fractions of the safe
 * area, radius as a fraction of its shorter side, so a layout scales to any phone or tablet) plus the
 * derived pixel geometry used for hit-testing and drawing. Pure Kotlin; all per-control data lives in
 * parallel primitive arrays (sized once for [MAX_CONTROLS]) indexed by control id, so switching modes
 * never reallocates and the touch path walks them without iterators or boxing.
 */
class ControlLayout {
    var mode: GameMode = GameModes.CLASSIC
        private set

    /** Number of controls in [mode]; ids are 0 until count. */
    var count = 0
        private set

    val kind = IntArray(MAX_CONTROLS)
    val mask = IntArray(MAX_CONTROLS)
    val group = IntArray(MAX_CONTROLS)
    val axis = IntArray(MAX_CONTROLS)
    val suppress = IntArray(MAX_CONTROLS)
    private val aspectW = FloatArray(MAX_CONTROLS)
    private val aspectH = FloatArray(MAX_CONTROLS)

    val fx = FloatArray(MAX_CONTROLS)
    val fy = FloatArray(MAX_CONTROLS)
    val size = FloatArray(MAX_CONTROLS)

    val x = FloatArray(MAX_CONTROLS)
    val y = FloatArray(MAX_CONTROLS)
    val r = FloatArray(MAX_CONTROLS)
    val halfW = FloatArray(MAX_CONTROLS)
    val halfH = FloatArray(MAX_CONTROLS)

    var left = 0f
        private set
    var top = 0f
        private set
    var right = 0f
        private set
    var bottom = 0f
        private set

    /** Shorter side of the safe area: the unit every control size is relative to. */
    var unit = 0f
        private set

    /** False until the user saves a layout: defaults are then recomputed for every new area size. */
    var customized = false
        private set

    /** Left-handed: every position is mirrored left <-> right (saved layouts mirror too). */
    var mirrored = false
        private set

    // Temporary offset of each slide group ("buttons follow your thumb"); never saved.
    private val groupDx = FloatArray(MAX_GROUPS)
    private val groupDy = FloatArray(MAX_GROUPS)

    val width: Float get() = right - left
    val height: Float get() = bottom - top

    init {
        load(GameModes.CLASSIC)
    }

    /** Switches to [mode] with its default positions (call [parse] afterwards for a saved layout). */
    fun load(mode: GameMode) {
        require(mode.controls.size <= MAX_CONTROLS) { "${mode.id} has too many controls" }
        this.mode = mode
        count = mode.controls.size
        for (c in 0 until count) {
            val s = mode.controls[c]
            require(s.group in 0 until MAX_GROUPS) { "${mode.id} ${s.name}: group ${s.group}" }
            kind[c] = s.kind
            mask[c] = s.mask
            group[c] = s.group
            axis[c] = s.axis
            suppress[c] = s.suppress
            aspectW[c] = s.aspectW
            aspectH[c] = s.aspectH
        }
        customized = false
        groupDx.fill(0f)
        groupDy.fill(0f)
        if (unit > 0f) place(left, top, right, bottom)
    }

    fun setMirrored(mirror: Boolean) {
        if (mirror == mirrored) return
        mirrored = mirror
        resetShifts()
    }

    /** Where control [c] sits before any group shift. */
    fun homeX(c: Int): Float = left + (if (mirrored) 1f - fx[c] else fx[c]) * width

    fun homeY(c: Int): Float = top + fy[c] * height

    /** Moves every control of slide group [g] by ([dx], [dy]) pixels from its home position. */
    fun shiftGroup(g: Int, dx: Float, dy: Float) {
        groupDx[g] = dx
        groupDy[g] = dy
        for (c in 0 until count) if (group[c] == g) compute(c)
    }

    /** Puts every shifted group back home. */
    fun resetShifts() {
        groupDx.fill(0f)
        groupDy.fill(0f)
        if (unit > 0f) for (c in 0 until count) compute(c)
    }

    fun spec(c: Int): ControlSpec = mode.controls[c]

    fun name(c: Int): String = mode.controls[c].name

    fun indexOf(name: String): Int = mode.indexOf(name)

    fun isStick(c: Int): Boolean = kind[c] == ControlSpec.STICK

    fun isRound(c: Int): Boolean = aspectW[c] == 1f && aspectH[c] == 1f

    fun place(left: Float, top: Float, right: Float, bottom: Float) {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
        unit = min(width, height)
        if (unit <= 0f) return
        if (!customized) computeDefaults()
        groupDx.fill(0f)
        groupDy.fill(0f)
        for (c in 0 until count) compute(c)
    }

    fun resetToDefaults() {
        customized = false
        if (unit > 0f) place(left, top, right, bottom)
    }

    /** Edit mode: moves control [c] so its centre is at ([px], [py]), kept fully inside the area. */
    fun moveTo(c: Int, px: Float, py: Float) {
        val w = width
        val h = height
        val onScreen = (px.coerceIn(left + min(halfW[c], w / 2), right - min(halfW[c], w / 2)) - left) / w
        fx[c] = if (mirrored) 1f - onScreen else onScreen
        fy[c] = (py.coerceIn(top + min(halfH[c], h / 2), bottom - min(halfH[c], h / 2)) - top) / h
        customized = true
        compute(c)
    }

    /** Edit mode: scales control [c] by [factor] within [MIN_SIZE]..[MAX_SIZE], then re-clamps it. */
    fun resize(c: Int, factor: Float) {
        size[c] = (size[c] * factor).coerceIn(MIN_SIZE, MAX_SIZE)
        compute(c)
        moveTo(c, x[c], y[c])
    }

    /** Nearest non-stick control under the point (with a little slop for fat fingers), or -1. */
    fun hitButton(px: Float, py: Float): Int = nearest(px, py, HIT_SLOP, ANY_GROUP, skipSticks = true)

    /** Nearest control of slide group [g] actually under the point: a rolling thumb changes button. */
    fun hitGroup(px: Float, py: Float, g: Int): Int = nearest(px, py, 1f, g, skipSticks = true)

    /** Nearest button of ANY slide group within [slop] radii of the point, or -1 (floating clusters). */
    fun nearestGrouped(px: Float, py: Float, slop: Float): Int = nearest(px, py, slop, ANY_SLIDE_GROUP, skipSticks = true)

    /** Stick whose (larger, floating) zone contains the point, nearest centre first, or -1. */
    fun hitStickZone(px: Float, py: Float): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (c in 0 until count) {
            if (kind[c] != ControlSpec.STICK) continue
            val zone = r[c] * STICK_ZONE
            val dx = px - x[c]
            val dy = py - y[c]
            if (abs(dx) > zone || abs(dy) > zone) continue
            val d = dx * dx + dy * dy
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /** Edit mode: any control, sticks by their base circle. */
    fun hitAny(px: Float, py: Float): Int = nearest(px, py, HIT_SLOP, ANY_GROUP, skipSticks = false)

    /**
     * One machine-readable line describing control [c] in screen pixels (the view's on-screen origin is
     * [ox], [oy]) for automated tests: `rect` is the hit box, a stick's floating touch zone for sticks.
     * Debug builds log it once per layout; it allocates and is never called on the input path.
     */
    fun describe(c: Int, ox: Int, oy: Int): String {
        val stick = isStick(c)
        val ex = if (stick) r[c] * STICK_ZONE else halfW[c]
        val ey = if (stick) r[c] * STICK_ZONE else halfH[c]
        val shape = if (stick) "stick" else if (isRound(c)) "circle" else "rect"
        fun px(v: Float, o: Int) = (v + o).roundToInt()
        return "geom name=${name(c)} shape=$shape cx=${px(x[c], ox)} cy=${px(y[c], oy)} r=${r[c].roundToInt()} " +
            "rect=${px(x[c] - ex, ox)},${px(y[c] - ey, oy)},${px(x[c] + ex, ox)},${px(y[c] + ey, oy)}"
    }

    fun serialize(): String {
        val sb = StringBuilder(FORMAT_VERSION)
        for (c in 0 until count) {
            sb.append(';').append(String.format(Locale.ROOT, "%.4f,%.4f,%.4f", fx[c], fy[c], size[c]))
        }
        return sb.toString()
    }

    /**
     * Loads a layout saved by [serialize] for the current mode. Returns false (and keeps the defaults)
     * if it is unusable. The original single-layout format ("1") is the classic mode's 17 controls in
     * the same order, so a layout saved before game modes existed still loads.
     */
    fun parse(text: String?): Boolean {
        val parts = text?.split(';') ?: return false
        val version = parts[0]
        val known = version == FORMAT_VERSION || (version == LEGACY_FORMAT && mode === GameModes.CLASSIC)
        if (!known || parts.size != count + 1) return false
        val values = FloatArray(count * 3)
        for (c in 0 until count) {
            val v = parts[c + 1].split(',')
            if (v.size != 3) return false
            for (k in 0..2) values[c * 3 + k] = v[k].toFloatOrNull() ?: return false
            if (values[c * 3] !in 0f..1f || values[c * 3 + 1] !in 0f..1f) return false
            if (values[c * 3 + 2] !in MIN_SIZE..MAX_SIZE) return false
        }
        for (c in 0 until count) {
            fx[c] = values[c * 3]
            fy[c] = values[c * 3 + 1]
            size[c] = values[c * 3 + 2]
        }
        customized = true
        if (unit > 0f) place(left, top, right, bottom)
        return true
    }

    private fun compute(c: Int) {
        x[c] = homeX(c) + groupDx[group[c]]
        y[c] = homeY(c) + groupDy[group[c]]
        r[c] = size[c] * unit
        halfW[c] = r[c] * aspectW[c]
        halfH[c] = r[c] * aspectH[c]
    }

    private fun nearest(px: Float, py: Float, slop: Float, g: Int, skipSticks: Boolean): Int {
        var best = -1
        var bestD = slop
        for (c in 0 until count) {
            if (skipSticks && kind[c] == ControlSpec.STICK) continue
            if (g == ANY_SLIDE_GROUP) {
                if (group[c] == 0) continue
            } else if (g != ANY_GROUP && group[c] != g) {
                continue
            }
            val dx = abs(px - x[c])
            val dy = abs(py - y[c])
            // Normalised distance: < 1 means inside the control's own outline.
            val d = if (isRound(c)) sqrt(dx * dx + dy * dy) / r[c] else max(dx / halfW[c], dy / halfH[c])
            if (d < bestD) {
                bestD = d
                best = c
            }
        }
        return best
    }

    /**
     * The mode's defaults for this area. Offsets use a design unit that never exceeds width / 2.1, so
     * squarer screens (tablets, portrait) shrink the controls instead of overlapping them.
     */
    private fun computeDefaults() {
        val w = width
        val h = height
        val u = min(h, w / 2.1f)
        for (c in 0 until count) {
            val s = mode.controls[c]
            fx[c] = (s.ax * w + s.bx * u) / w
            fy[c] = (s.ay * h + s.by * u) / h
            size[c] = (s.r * u / unit).coerceIn(MIN_SIZE, MAX_SIZE)
        }
    }

    companion object {
        /** Upper bound for any mode's control count; arrays are sized once for it. */
        const val MAX_CONTROLS = 24

        /** Half-extent of a floating stick's touch zone, in stick radii. */
        const val STICK_ZONE = 1.9f

        /** Touches up to 20 % outside a button still hit it (nearest control wins). */
        const val HIT_SLOP = 1.2f

        const val MIN_SIZE = 0.03f
        const val MAX_SIZE = 0.35f

        /** Slide-group ids are 0 (none) until this. */
        const val MAX_GROUPS = 8

        private const val ANY_GROUP = -1
        private const val ANY_SLIDE_GROUP = -2
        private const val FORMAT_VERSION = "2"
        private const val LEGACY_FORMAT = "1"
    }
}
