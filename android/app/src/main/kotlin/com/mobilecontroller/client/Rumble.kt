package com.mobilecontroller.client

import android.os.VibrationEffect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Plays the game's rumble on the phone's vibrator, through [haptics] (which owns the vibrator). Every
 * [VibrationEffect] is built once up front (one endless waveform per amplitude level), so applying a
 * FEEDBACK packet never allocates in our code and the vibrator is only touched when the level actually
 * changes.
 */
class Rumble(val haptics: Haptics) {
    private val levels = if (haptics.hasAmplitudeControl) LEVELS else 1

    // Index 0 is "off". A one-segment waveform repeating from index 0 vibrates until cancelled, which
    // avoids re-issuing (and restarting) a one-shot on every 100 ms FEEDBACK heartbeat.
    private val effects: Array<VibrationEffect?> = Array(levels + 1) { level ->
        if (level == 0) {
            null
        } else {
            val amplitude = if (levels == 1) VibrationEffect.DEFAULT_AMPLITUDE else level * 255 / levels
            VibrationEffect.createWaveform(longArrayOf(SEGMENT_MS), intArrayOf(amplitude), 0)
        }
    }

    @Volatile
    var enabled = true
        set(value) {
            field = value
            if (!value) stop()
        }

    /** Strength multiplier (0..2): weak phone motors often want more than the game asks for. */
    @Volatile var gain = 1f

    /** How the PC pad's two motors become the phone's one: [MIX_MAX], [MIX_SUM], [MIX_LARGE], [MIX_SMALL]. */
    @Volatile var mix = MIX_MAX

    @Volatile
    private var level = 0

    /** True while the motor runs; lets the link's watchdog skip the monitor when there is nothing to stop. */
    val active: Boolean get() = level != 0

    /** Applies the motor speeds of a FEEDBACK packet (0..255 each). */
    @Synchronized
    fun set(largeMotor: Int, smallMotor: Int) {
        if (haptics.vibrator == null) return
        val raw = when (mix) {
            MIX_SUM -> min(255, largeMotor + smallMotor)
            MIX_LARGE -> largeMotor
            MIX_SMALL -> smallMotor
            else -> max(largeMotor, smallMotor)
        }
        val strength = if (enabled && raw > 0) min(255, (raw * gain).roundToInt()) else 0
        // Without amplitude control, only rumble that is at least ~1/8 strength switches the motor on,
        // so faint effects don't buzz at full.
        val target = if (levels == 1) {
            if (strength >= ON_OFF_THRESHOLD) 1 else 0
        } else {
            (strength * levels + 254) / 255
        }
        if (target == level) return
        level = target
        haptics.setRumble(effects[target])
    }

    fun stop() = set(0, 0)

    companion object {
        /** The stronger motor drives the phone (default). */
        const val MIX_MAX = 0

        /** Both motors add up: strong effects feel stronger. */
        const val MIX_SUM = 1

        /** Only the low-frequency (heavy) motor: crashes and explosions, no engine hum. */
        const val MIX_LARGE = 2

        /** Only the high-frequency (light) motor. */
        const val MIX_SMALL = 3

        private const val LEVELS = 16
        private const val SEGMENT_MS = 1000L
        private const val ON_OFF_THRESHOLD = 32
    }
}
