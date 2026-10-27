package com.mobilecontroller.client

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.max

/**
 * Plays the game's rumble on the phone's vibrator. Every [VibrationEffect] is built once up front
 * (one endless waveform per amplitude level), so applying a FEEDBACK packet never allocates in our
 * code and the vibrator is only touched when the level actually changes.
 */
class Rumble(context: Context) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }

    private val levels = if (vibrator?.hasAmplitudeControl() == true) LEVELS else 1

    // Index 0 is "off". A one-segment waveform repeating from index 0 vibrates until cancel(), which
    // avoids re-issuing (and restarting) a one-shot on every 100 ms FEEDBACK heartbeat.
    private val effects: Array<VibrationEffect?> = Array(levels + 1) { level ->
        if (level == 0) {
            null
        } else {
            val amplitude = if (levels == 1) VibrationEffect.DEFAULT_AMPLITUDE else level * 255 / levels
            VibrationEffect.createWaveform(longArrayOf(SEGMENT_MS), intArrayOf(amplitude), 0)
        }
    }

    // Games are "interactive media" for the vibrator's per-usage intensity settings.
    private val attributes: VibrationAttributes? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA)
        } else {
            null
        }
    private val legacyAttributes: AudioAttributes? =
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).build()
        } else {
            null
        }

    @Volatile
    var enabled = true
        set(value) {
            field = value
            if (!value) stop()
        }

    @Volatile
    private var level = 0

    /** True while the motor runs; lets the link's watchdog skip the monitor when there is nothing to stop. */
    val active: Boolean get() = level != 0

    /** Applies the motor speeds of a FEEDBACK packet (0..255 each). */
    @Synchronized
    fun set(largeMotor: Int, smallMotor: Int) {
        val v = vibrator ?: return
        val strength = if (enabled) max(largeMotor, smallMotor) else 0
        // A phone has one motor: drive it with the stronger of the two. Without amplitude control, only
        // rumble that is at least ~1/8 strength switches the motor on, so faint effects don't buzz at full.
        val target = if (levels == 1) {
            if (strength >= 32) 1 else 0
        } else {
            (strength * levels + 254) / 255
        }
        if (target == level) return
        level = target
        val effect = effects[target]
        if (effect == null) {
            v.cancel()
        } else if (attributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, attributes)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, legacyAttributes)
        }
    }

    fun stop() = set(0, 0)

    private companion object {
        const val LEVELS = 16
        const val SEGMENT_MS = 1000L
    }
}
