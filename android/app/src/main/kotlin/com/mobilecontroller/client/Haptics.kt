package com.mobilecontroller.client

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/**
 * The single owner of the phone's vibrator: touch haptics (button ticks, misses, sprint) and the
 * game's rumble ([Rumble] is its rumble channel). Android keeps one vibration at a time per app, so
 * two independent users would cancel each other: a tick would silently end an endless rumble waveform,
 * or be dropped by it. Here a tick during rumble is either skipped (the default: under rumble it is
 * not felt anyway) or played and followed by the rumble again.
 *
 * Unlike View.performHapticFeedback, these effects do not depend on the system "touch feedback"
 * setting (many players switch that off for the keyboard but still want a controller to click), and
 * their strength is adjustable. Effects are built once per [configure]; vibrator calls are binder
 * transactions, so they run on a small thread of their own instead of the touch thread, posted with
 * preallocated Runnables: playing an effect allocates nothing.
 */
class Haptics(context: Context) {
    val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }?.takeIf { it.hasVibrator() }

    val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    // Games are "interactive media" for the vibrator's per-usage intensity settings, and media
    // vibration is not switched off together with touch feedback.
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

    private val thread = HandlerThread("mc-haptics", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    private val handler = Handler(thread.looper)

    // Built by configure(); read by the haptics thread.
    @Volatile private var effects = arrayOfNulls<VibrationEffect>(EVENT_COUNT)
    @Volatile private var durations = LongArray(EVENT_COUNT)

    /** Events the player wants to feel, as a bit set of `1 shl EVENT_*`. */
    @Volatile var enabledEvents = ALL_EVENTS

    /** Play ticks while the game rumbles (briefly interrupting it) instead of skipping them. */
    @Volatile var playDuringRumble = false

    // The rumble waveform currently running (null = off). Guarded by `this`.
    private var rumbleEffect: VibrationEffect? = null
    private var rumbleSuspended = false

    private val players = Array(EVENT_COUNT) { event -> Runnable { playNow(event) } }
    private val resumeRumble = Runnable { resume() }

    init {
        configure(DEFAULT_INTENSITY)
    }

    /** Rebuilds every effect for [intensity] (0..1). UI thread, at settings time; allocates. */
    fun configure(intensity: Float) {
        val v = vibrator ?: return
        val k = intensity.coerceIn(0f, 1f)
        val built = arrayOfNulls<VibrationEffect>(EVENT_COUNT)
        val ms = LongArray(EVENT_COUNT)
        for (event in 0 until EVENT_COUNT) {
            ms[event] = DURATION_MS[event]
            built[event] = if (k <= 0f) null else build(v, event, k)
        }
        durations = ms
        effects = built
    }

    private fun build(v: Vibrator, event: Int, k: Float): VibrationEffect? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = PRIMITIVE[event]
            if (v.arePrimitivesSupported(primitive).all { it }) {
                val composition = VibrationEffect.startComposition().addPrimitive(primitive, k * PRIMITIVE_SCALE[event])
                // A "reject" is two quick ticks: unmistakable without looking.
                if (event == EVENT_REJECT) composition.addPrimitive(primitive, k * PRIMITIVE_SCALE[event], REJECT_GAP_MS)
                return composition.compose()
            }
        }
        if (hasAmplitudeControl) {
            val amplitude = (AMPLITUDE[event] * k).toInt().coerceIn(1, 255)
            return if (event == EVENT_REJECT) {
                VibrationEffect.createWaveform(
                    longArrayOf(0, ONE_SHOT_MS[event], REJECT_GAP_MS.toLong(), ONE_SHOT_MS[event]),
                    intArrayOf(0, amplitude, 0, amplitude),
                    -1,
                )
            } else {
                VibrationEffect.createOneShot(ONE_SHOT_MS[event], amplitude)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return VibrationEffect.createPredefined(PREDEFINED[event])
        return VibrationEffect.createOneShot(ONE_SHOT_MS[event], VibrationEffect.DEFAULT_AMPLITUDE)
    }

    /** Plays [event] (EVENT_*) unless it is switched off. Any thread; allocation-free. */
    fun play(event: Int) {
        if ((enabledEvents and (1 shl event)) == 0 || effects[event] == null) return
        handler.post(players[event])
    }

    /** Plays [event] even when it is switched off: the settings screen's Test buttons. */
    fun test(event: Int) {
        if (effects[event] != null) handler.post(players[event])
    }

    private fun playNow(event: Int) {
        val v = vibrator ?: return
        val effect = effects[event] ?: return
        synchronized(this) {
            if (rumbleEffect != null) {
                // Identify is the PC asking which phone this is: it must be felt even mid-rumble.
                if (!playDuringRumble && event != EVENT_IDENTIFY) return
                rumbleSuspended = true
                handler.removeCallbacks(resumeRumble)
                handler.postDelayed(resumeRumble, durations[event])
            }
            vibrate(v, effect)
        }
    }

    private fun resume() {
        val v = vibrator ?: return
        synchronized(this) {
            if (!rumbleSuspended) return
            rumbleSuspended = false
            rumbleEffect?.let { vibrate(v, it) }
        }
    }

    /** Starts (or replaces) the endless rumble waveform, or stops it with null. Any thread. */
    fun setRumble(effect: VibrationEffect?) {
        val v = vibrator ?: return
        synchronized(this) {
            if (effect === rumbleEffect) return
            rumbleEffect = effect
            // A tick that interrupted the old waveform must not bring it back after this change.
            rumbleSuspended = false
            handler.removeCallbacks(resumeRumble)
            if (effect == null) v.cancel() else vibrate(v, effect)
        }
    }

    private fun vibrate(v: Vibrator, effect: VibrationEffect) {
        if (attributes != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            v.vibrate(effect, attributes)
        } else {
            @Suppress("DEPRECATION")
            v.vibrate(effect, legacyAttributes)
        }
    }

    /** Stops everything and ends the haptics thread. The instance is unusable afterwards. */
    fun release() {
        setRumble(null)
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
    }

    companion object {
        /** A button or D-pad direction went down. */
        const val EVENT_PRESS = 0

        /** A stick reached full output. */
        const val EVENT_RIM = 1

        /** Sprint started, a toggle switched on. */
        const val EVENT_CONFIRM = 2

        /** A finger landed on empty glass, a toggle switched off. */
        const val EVENT_REJECT = 3

        /** The PC asked this phone to identify itself. */
        const val EVENT_IDENTIFY = 4

        const val EVENT_COUNT = 5
        const val ALL_EVENTS = (1 shl EVENT_COUNT) - 1
        const val DEFAULT_INTENSITY = 0.8f

        private const val REJECT_GAP_MS = 60

        // Per event: composition primitive and its strength, fallback one-shot length and amplitude,
        // predefined effect, and how long the effect keeps the motor busy (rumble resumes after it).
        // The newer constants are compile-time ints, only passed to the vibrator on the API they need.
        @SuppressLint("InlinedApi")
        private val PRIMITIVE = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
        )
        private val PRIMITIVE_SCALE = floatArrayOf(0.7f, 0.5f, 1f, 0.8f, 1f)
        private val ONE_SHOT_MS = longArrayOf(12, 8, 25, 10, 300)
        private val AMPLITUDE = intArrayOf(180, 110, 255, 160, 255)

        @SuppressLint("InlinedApi")
        private val PREDEFINED = intArrayOf(
            VibrationEffect.EFFECT_CLICK,
            VibrationEffect.EFFECT_TICK,
            VibrationEffect.EFFECT_HEAVY_CLICK,
            VibrationEffect.EFFECT_DOUBLE_CLICK,
            VibrationEffect.EFFECT_HEAVY_CLICK,
        )
        private val DURATION_MS = longArrayOf(30, 20, 40, 100, 320)
    }
}
