package com.mobilecontroller.client

import android.content.Context
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import kotlin.math.roundToInt

const val LOG_TAG = "MobileController"

/** User settings, persisted in SharedPreferences. Read at startup and when the settings dialog closes. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var host: String = prefs.getString(KEY_HOST, "").orEmpty()
    var port: Int = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT)
    var rateHz: Int = prefs.getInt(KEY_RATE, UdpLink.DEFAULT_RATE_HZ)
    var haptics: Boolean = prefs.getBoolean(KEY_HAPTICS, true)
    var rumble: Boolean = prefs.getBoolean(KEY_RUMBLE, true)
    var deadzonePercent: Int = prefs.getInt(KEY_DEADZONE, DEFAULT_DEADZONE_PERCENT)

    /** Selected [GameMode] id. */
    var modeId: String = prefs.getString(KEY_MODE, GameModes.CLASSIC.id) ?: GameModes.CLASSIC.id

    /** Switch mode when the PC reports a known game in the foreground (PROTOCOL §10). */
    var autoMode: Boolean = prefs.getBoolean(KEY_AUTO_MODE, true)

    /** Slide groups re-centre under the thumb ("buttons follow your thumb"). */
    var floatingButtons: Boolean = prefs.getBoolean(KEY_FLOATING, true)

    /** Lower the screen brightness while connected: nobody looks at the phone mid-game. */
    var dimScreen: Boolean = prefs.getBoolean(KEY_DIM, true)

    /** Mirror every layout left <-> right. */
    var leftHanded: Boolean = prefs.getBoolean(KEY_LEFT_HANDED, false)

    /** Gyro aim / tilt steering sensitivity in percent (50..300). */
    var motionSensitivityPercent: Int = prefs.getInt(KEY_MOTION_SENSITIVITY, 100)

    /** Gyro aims only while the mode's aim button (LT) is held. */
    var gyroWhileAiming: Boolean = prefs.getBoolean(KEY_GYRO_WHILE_AIMING, true)
    var gyroInvertY: Boolean = prefs.getBoolean(KEY_GYRO_INVERT_Y, false)

    fun save() {
        prefs.edit()
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putInt(KEY_RATE, rateHz)
            .putBoolean(KEY_HAPTICS, haptics)
            .putBoolean(KEY_RUMBLE, rumble)
            .putInt(KEY_DEADZONE, deadzonePercent)
            .putString(KEY_MODE, modeId)
            .putBoolean(KEY_AUTO_MODE, autoMode)
            .putBoolean(KEY_FLOATING, floatingButtons)
            .putBoolean(KEY_DIM, dimScreen)
            .putBoolean(KEY_LEFT_HANDED, leftHanded)
            .putInt(KEY_MOTION_SENSITIVITY, motionSensitivityPercent)
            .putBoolean(KEY_GYRO_WHILE_AIMING, gyroWhileAiming)
            .putBoolean(KEY_GYRO_INVERT_Y, gyroInvertY)
            .apply()
    }

    /** Per-mode stick feel and motion switch; defaults come from the mode until the user changes them. */
    class ModeFeel(
        var antiDeadzonePercent: Int,
        /** Right-stick response exponent x 10 (10 = linear). */
        var curveTenths: Int,
        var sensitivityPercent: Int,
        var motion: Boolean,
    )

    fun feel(mode: GameMode): ModeFeel = ModeFeel(
        prefs.getInt(KEY_ANTI_DEADZONE + mode.id, (mode.antiDeadzone * 100).roundToInt()),
        prefs.getInt(KEY_CURVE + mode.id, (mode.aimCurve * 10).roundToInt()),
        prefs.getInt(KEY_SENSITIVITY + mode.id, (mode.sensitivity * 100).roundToInt()),
        // Gyro aiming is gated behind the aim button, so it is safe to start on; tilt steering would
        // take over the car of anyone holding the phone at an angle, so it starts off.
        prefs.getBoolean(KEY_MOTION + mode.id, mode.motion == GameMode.MOTION_AIM && mode !== GameModes.CLASSIC),
    )

    fun saveFeel(mode: GameMode, feel: ModeFeel) {
        prefs.edit()
            .putInt(KEY_ANTI_DEADZONE + mode.id, feel.antiDeadzonePercent)
            .putInt(KEY_CURVE + mode.id, feel.curveTenths)
            .putInt(KEY_SENSITIVITY + mode.id, feel.sensitivityPercent)
            .putBoolean(KEY_MOTION + mode.id, feel.motion)
            .apply()
    }

    /**
     * The user's layout for mode [id] ([com.mobilecontroller.client.ui.ControlLayout.serialize]), null
     * for the mode's defaults. Each mode keeps its own; the single layout saved before game modes existed
     * belongs to the classic mode.
     */
    fun layoutFor(id: String): String? =
        prefs.getString(KEY_LAYOUT_PREFIX + id, null)
            ?: if (id == GameModes.CLASSIC.id) prefs.getString(KEY_LEGACY_LAYOUT, null) else null

    fun saveLayout(id: String, serialized: String?) {
        val edit = prefs.edit().putString(KEY_LAYOUT_PREFIX + id, serialized)
        // Reset-to-defaults must not resurrect the pre-modes layout through the fallback above.
        if (id == GameModes.CLASSIC.id) edit.remove(KEY_LEGACY_LAYOUT)
        edit.apply()
    }

    companion object {
        const val DEFAULT_DEADZONE_PERCENT = 5
        const val MAX_DEADZONE_PERCENT = 20
        val RATES = intArrayOf(60, 90, 120)

        const val MAX_ANTI_DEADZONE_PERCENT = 40
        const val MIN_CURVE_TENTHS = 10
        const val MAX_CURVE_TENTHS = 30
        const val MIN_SENSITIVITY_PERCENT = 50
        const val MAX_SENSITIVITY_PERCENT = 200
        const val MIN_MOTION_SENSITIVITY_PERCENT = 50
        const val MAX_MOTION_SENSITIVITY_PERCENT = 300

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_RATE = "rate"
        private const val KEY_HAPTICS = "haptics"
        private const val KEY_RUMBLE = "rumble"
        private const val KEY_DEADZONE = "deadzone"
        private const val KEY_MODE = "mode"
        private const val KEY_AUTO_MODE = "autoMode"
        private const val KEY_FLOATING = "floating"
        private const val KEY_DIM = "dim"
        private const val KEY_LEFT_HANDED = "leftHanded"
        private const val KEY_MOTION_SENSITIVITY = "motionSensitivity"
        private const val KEY_GYRO_WHILE_AIMING = "gyroWhileAiming"
        private const val KEY_GYRO_INVERT_Y = "gyroInvertY"
        private const val KEY_ANTI_DEADZONE = "antiDeadzone."
        private const val KEY_CURVE = "curve."
        private const val KEY_SENSITIVITY = "sensitivity."
        private const val KEY_MOTION = "motion."
        private const val KEY_LAYOUT_PREFIX = "layout."
        private const val KEY_LEGACY_LAYOUT = "layout"
    }
}
