package com.mobilecontroller.client.input

import com.mobilecontroller.client.GamepadState
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.ControlLayout.Companion.MAX_CONTROLS
import com.mobilecontroller.client.ui.ControlSpec
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Turns pointer events into [GamepadState] writes. A pointer owns the control it lands on until it
 * lifts, so a thumb can drift off a stick, pedal or D-pad without dropping it, and two fingers never
 * fight over one control. Pure Kotlin on primitive arrays: this is the allocation-free half of the
 * touch -> packet path, unit-tested on the JVM.
 *
 * Digital output is recomputed from every control on each change rather than set and cleared per
 * control, because game modes let several controls share a bit (SHOOT and FINESSE both send X):
 * releasing one must not release the other.
 *
 * Every method returns a set of [STATE] / [VISUAL] / [PRESS] / [MENU_TAP] flags describing what changed.
 */
class TouchRouter(private val layout: ControlLayout, private val state: GamepadState) {
    /** Stick deadzone as a fraction of the stick radius. */
    var deadzone = 0.05f

    /** Output shaping per stick axis (0 = left stick, 1 = right stick), see [StickMath.shape]. */
    val antiDeadzone = FloatArray(2)
    val curve = floatArrayOf(1f, 1f)
    val sensitivity = floatArrayOf(1f, 1f)

    /**
     * "Buttons follow your thumb": when a thumb lands on (or near) a slide group that no finger holds,
     * the whole group shifts so the touched button sits exactly under it, and the neighbours keep their
     * distances from where the thumb really is - so a player who is not looking at the phone still
     * rolls onto the right button.
     */
    var floating = false

    private val controlOfPointer = IntArray(MAX_POINTERS) { -1 }
    private val pointerOfControl = IntArray(MAX_CONTROLS) { -1 }

    /** Floating centre and knob position of each stick while it is held, indexed by control id. */
    val stickX = FloatArray(MAX_CONTROLS)
    val stickY = FloatArray(MAX_CONTROLS)
    val knobX = FloatArray(MAX_CONTROLS)
    val knobY = FloatArray(MAX_CONTROLS)

    /** Stick pushed past its edge ring (its edge action is held). */
    val edgeOn = BooleanArray(MAX_CONTROLS)

    /** Stick at full output (the "rim" a haptic tick announces). */
    private val rimOn = BooleanArray(MAX_CONTROLS)

    val dpadBits = IntArray(MAX_CONTROLS)
    val pedalValue = IntArray(MAX_CONTROLS)
    val toggleOn = BooleanArray(MAX_CONTROLS)

    fun isHeld(c: Int): Boolean = pointerOfControl[c] >= 0

    /** After [ControlLayout.load]: forgets every finger and per-control state of the previous mode. */
    fun reset(): Int {
        controlOfPointer.fill(-1)
        pointerOfControl.fill(-1)
        edgeOn.fill(false)
        rimOn.fill(false)
        dpadBits.fill(0)
        pedalValue.fill(0)
        toggleOn.fill(false)
        state.setLeftStick(0, 0)
        state.setRightStick(0, 0)
        commitTriggers()
        return commitDigital()
    }

    fun down(id: Int, px: Float, py: Float): Int {
        if (id < 0 || id >= MAX_POINTERS) return 0
        var flags = if (controlOfPointer[id] >= 0) up(id) else 0
        var c = layout.hitButton(px, py)
        if (floating && (c < 0 || layout.group[c] != 0)) {
            // Near-misses next to a stick belong to the stick, not to the cluster.
            val f = if (c >= 0) c else if (layout.hitStickZone(px, py) < 0) layout.nearestGrouped(px, py, FLOAT_SLOP) else -1
            if (f >= 0 && !groupHeld(layout.group[f])) {
                follow(f, px, py)
                c = f
                flags = flags or VISUAL
            }
        }
        if (c < 0) c = layout.hitStickZone(px, py)
        // A thumb on empty glass gets a distinct buzz: the player knows they missed without looking.
        if (c < 0) return flags or MISS
        if (pointerOfControl[c] >= 0) return flags
        controlOfPointer[id] = c
        pointerOfControl[c] = id
        flags = flags or when (layout.kind[c]) {
            ControlSpec.STICK -> {
                // Floating stick: centre where the thumb lands, clamped so the whole ring stays inside the
                // safe area; the first sample is then (near) zero deflection instead of a jump.
                val r = layout.r[c]
                stickX[c] = clampTo(px, layout.left + r, layout.right - r)
                stickY[c] = clampTo(py, layout.top + r, layout.bottom - r)
                updateStick(c, px, py)
            }
            ControlSpec.DPAD -> updateDpad(c, px, py) or VISUAL
            ControlSpec.PEDAL -> updatePedal(c, py) or PRESS
            ControlSpec.TOGGLE -> {
                toggleOn[c] = !toggleOn[c]
                commitDigital() or (if (toggleOn[c]) TOGGLE_ON else TOGGLE_OFF)
            }
            ControlSpec.MENU -> VISUAL
            else -> commitDigital() or PRESS
        }
        return flags
    }

    fun move(id: Int, px: Float, py: Float): Int {
        if (id < 0 || id >= MAX_POINTERS) return 0
        val c = controlOfPointer[id]
        if (c < 0) return 0
        return when (layout.kind[c]) {
            ControlSpec.STICK -> updateStick(c, px, py)
            ControlSpec.DPAD -> updateDpad(c, px, py)
            ControlSpec.PEDAL -> updatePedal(c, py)
            ControlSpec.BUTTON -> if (layout.group[c] != 0) slide(id, c, px, py) else 0
            else -> 0
        }
    }

    fun up(id: Int): Int {
        if (id < 0 || id >= MAX_POINTERS) return 0
        val c = controlOfPointer[id]
        if (c < 0) return 0
        controlOfPointer[id] = -1
        pointerOfControl[c] = -1
        return when (layout.kind[c]) {
            ControlSpec.STICK -> {
                val changed = if (layout.axis[c] == 0) state.setLeftStick(0, 0) else state.setRightStick(0, 0)
                rimOn[c] = false
                var flags = VISUAL or (if (changed) STATE else 0)
                if (edgeOn[c]) {
                    edgeOn[c] = false
                    flags = flags or commitDigital()
                }
                flags
            }
            ControlSpec.DPAD -> {
                dpadBits[c] = 0
                commitDigital()
            }
            ControlSpec.PEDAL -> {
                pedalValue[c] = 0
                commitTriggers()
            }
            ControlSpec.TOGGLE -> VISUAL
            ControlSpec.MENU -> VISUAL or MENU_TAP
            else -> commitDigital()
        }
    }

    /** ACTION_CANCEL: release every finger (never reports a menu tap). Toggles keep their state. */
    fun cancelAll(): Int {
        var flags = 0
        for (id in 0 until MAX_POINTERS) {
            if (controlOfPointer[id] >= 0) flags = flags or up(id)
        }
        return flags and MENU_TAP.inv()
    }

    /**
     * Pause, dialogs, layout changes: release every finger, switch toggles off (no runaway car) and send
     * floating clusters home.
     */
    fun releaseAll(): Int {
        var flags = cancelAll()
        var anyOn = false
        for (c in 0 until layout.count) {
            if (toggleOn[c]) anyOn = true
            toggleOn[c] = false
        }
        if (anyOn) flags = flags or commitDigital()
        layout.resetShifts()
        return flags or VISUAL
    }

    private fun groupHeld(g: Int): Boolean {
        for (c in 0 until layout.count) if (layout.group[c] == g && pointerOfControl[c] >= 0) return true
        return false
    }

    /** Shifts [c]'s group so [c] sits under ([px], [py]), at most [MAX_FLOAT] units from home. */
    private fun follow(c: Int, px: Float, py: Float) {
        var dx = px - layout.homeX(c)
        var dy = py - layout.homeY(c)
        val limit = MAX_FLOAT * layout.unit
        val d2 = dx * dx + dy * dy
        if (d2 > limit * limit) {
            val k = limit / sqrt(d2)
            dx *= k
            dy *= k
        }
        layout.shiftGroup(layout.group[c], dx, dy)
    }

    private fun updateStick(c: Int, px: Float, py: Float): Int {
        val r = layout.r[c]
        val dx = px - stickX[c]
        val dy = py - stickY[c]
        val d2 = dx * dx + dy * dy
        if (d2 > r * r) {
            val k = r / sqrt(d2)
            knobX[c] = stickX[c] + dx * k
            knobY[c] = stickY[c] + dy * k
        } else {
            knobX[c] = px
            knobY[c] = py
        }
        val a = layout.axis[c]
        val packed = StickMath.stick(dx, dy, r, deadzone, antiDeadzone[a], curve[a], sensitivity[a])
        val ax = StickMath.unpackX(packed)
        val ay = StickMath.unpackY(packed)
        val changed = if (a == 0) state.setLeftStick(ax, ay) else state.setRightStick(ax, ay)
        var flags = VISUAL or (if (changed) STATE else 0)
        // Rim tick where the output saturates (earlier than the ring with a high sensitivity), so the
        // thumb feels "full speed" instead of pushing on uselessly.
        val full = StickMath.fullDeflection(deadzone, sensitivity[a]) * r
        val rimLimit = if (rimOn[c]) full * RIM_RELEASE else full
        val rim = d2 >= rimLimit * rimLimit
        if (rim != rimOn[c]) {
            rimOn[c] = rim
            if (rim) flags = flags or RIM
        }
        if (layout.mask[c] != 0) {
            // Edge action (e.g. sprint): pushing well past the ring, with hysteresis so a thumb resting
            // on the boundary does not flicker it.
            val limit = if (edgeOn[c]) EDGE_OFF * r else EDGE_ON * r
            val on = d2 > limit * limit
            if (on != edgeOn[c]) {
                edgeOn[c] = on
                flags = flags or commitDigital() or (if (on) EDGE else 0)
            }
        }
        return flags
    }

    private fun updateDpad(c: Int, px: Float, py: Float): Int {
        val bits = StickMath.dpad(px - layout.x[c], py - layout.y[c], layout.r[c], DPAD_DEADZONE)
        if (bits == dpadBits[c]) return 0
        val pressed = bits and dpadBits[c].inv()
        dpadBits[c] = bits
        return commitDigital() or (if (pressed != 0) PRESS else 0)
    }

    /** Pedal travel: the bottom edge is a light press, the upper part of the pedal is full power. */
    private fun updatePedal(c: Int, py: Float): Int {
        val h = layout.halfH[c]
        val t = clampTo((layout.y[c] + h - py) / (2 * h), 0f, 1f)
        val v = (minOf(1f, PEDAL_MIN + t * PEDAL_GAIN) * GamepadState.TRIGGER_MAX).roundToInt()
        if (v == pedalValue[c]) return 0
        pedalValue[c] = v
        return commitTriggers()
    }

    /** A thumb rolling from one button onto another of the same group releases one, presses the other. */
    private fun slide(id: Int, c: Int, px: Float, py: Float): Int {
        val n = layout.hitGroup(px, py, layout.group[c])
        if (n < 0 || n == c || pointerOfControl[n] >= 0) return 0
        pointerOfControl[c] = -1
        pointerOfControl[n] = id
        controlOfPointer[id] = n
        return commitDigital() or PRESS
    }

    private fun digitalBits(): Int {
        var bits = 0
        for (c in 0 until layout.count) {
            when (layout.kind[c]) {
                ControlSpec.BUTTON -> if (pointerOfControl[c] >= 0) bits = bits or layout.mask[c]
                ControlSpec.DPAD -> bits = bits or dpadBits[c]
                ControlSpec.STICK -> if (edgeOn[c]) bits = bits or layout.mask[c]
            }
        }
        // Toggles last: they yield while anything held overrides them (REV releases AUTO GAS).
        var toggled = 0
        for (c in 0 until layout.count) {
            if (toggleOn[c] && (bits and layout.suppress[c]) == 0) toggled = toggled or layout.mask[c]
        }
        return bits or toggled
    }

    private fun commitDigital(): Int =
        if (state.setDigital(ALL_DIGITAL, digitalBits())) STATE or VISUAL else VISUAL

    private fun commitTriggers(): Int {
        var lt = 0
        var rt = 0
        for (c in 0 until layout.count) {
            if (layout.kind[c] != ControlSpec.PEDAL) continue
            if (layout.axis[c] == 0) lt = maxOf(lt, pedalValue[c]) else rt = maxOf(rt, pedalValue[c])
        }
        val changed = state.setTrigger(right = false, lt) or state.setTrigger(right = true, rt)
        return VISUAL or (if (changed) STATE else 0)
    }

    private fun clampTo(v: Float, lo: Float, hi: Float): Float =
        if (lo > hi) (lo + hi) / 2 else if (v < lo) lo else if (v > hi) hi else v

    companion object {
        /** MotionEvent pointer ids are always below 32. */
        const val MAX_POINTERS = 32

        /** D-pad centre deadzone, as a fraction of its radius. */
        const val DPAD_DEADZONE = 0.25f

        /** A stick's edge action starts past this many radii from its centre... */
        const val EDGE_ON = 1.3f

        /** ...and stops once the thumb comes back inside this many. */
        const val EDGE_OFF = 1.15f

        /** Pedal output at its bottom edge, and how fast it rises (full power from ~58 % of the travel). */
        const val PEDAL_MIN = 0.3f
        const val PEDAL_GAIN = 1.2f

        /** A floating cluster catches thumbs this many radii from a button, and moves at most this far (units). */
        const val FLOAT_SLOP = 1.6f
        const val MAX_FLOAT = 0.1f

        /** The rim tick re-arms once the thumb is back inside this fraction of full deflection. */
        private const val RIM_RELEASE = 0.9f

        private const val ALL_DIGITAL = Protocol.BUTTON_MASK or GamepadState.LT or GamepadState.RT

        const val STATE = 1
        const val VISUAL = 2

        // Haptic landmarks: what the thumb should feel (GamepadView picks the vibration for each).
        /** A button or D-pad direction went down, or a rolling thumb changed button. */
        const val PRESS = 4
        const val MENU_TAP = 8

        /** A stick's thumb crossed its edge ring (sprint started). */
        const val EDGE = 16

        /** A stick reached full output. */
        const val RIM = 32

        /** A finger landed where there is no control. */
        const val MISS = 64
        const val TOGGLE_ON = 128
        const val TOGGLE_OFF = 256
    }
}
