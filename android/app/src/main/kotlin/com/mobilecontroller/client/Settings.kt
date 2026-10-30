package com.mobilecontroller.client

import android.content.Context
import com.mobilecontroller.client.input.GyroFilter
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import kotlin.math.roundToInt

const val LOG_TAG = "MobileController"

/** User settings, persisted in SharedPreferences. Read at startup and when the settings screen saves. */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var host: String = prefs.getString(KEY_HOST, "").orEmpty()
    var port: Int = prefs.getInt(KEY_PORT, Protocol.DEFAULT_PORT)

    /** The name the target PC announces (discovery, QR link): finds it again when its address changes. */
    var serverName: String = prefs.getString(KEY_SERVER_NAME, "").orEmpty()

    /** PCs this phone connected to, most recent first. */
    val knownPcs: KnownPcs = KnownPcs.parse(prefs.getString(KEY_KNOWN_PCS, null))
    var rateHz: Int = prefs.getInt(KEY_RATE, UdpLink.DEFAULT_RATE_HZ)
    var haptics: Boolean = prefs.getBoolean(KEY_HAPTICS, true)
    var rumble: Boolean = prefs.getBoolean(KEY_RUMBLE, true)

    /** Left stick deadzone (the key predates per-stick settings, when it applied to both). */
    var deadzonePercent: Int = prefs.getInt(KEY_DEADZONE, DEFAULT_DEADZONE_PERCENT)
    var deadzoneRightPercent: Int = prefs.getInt(KEY_DEADZONE_RIGHT, deadzonePercent)

    /** Shortest press (and release) the PC sees, in ms: quick taps survive packet loss and frame polling. */
    var minPressMs: Int = prefs.getInt(KEY_MIN_PRESS, DEFAULT_MIN_PRESS_MS)

    // Per stick, index 0 = left, 1 = right (see TouchRouter and StickMath for the meaning).
    val outerDeadzonePercent = IntArray(2) { prefs.getInt(KEY_OUTER_DEADZONE + SIDE[it], 0) }
    val deadzoneShape = IntArray(2) { prefs.getInt(KEY_DEADZONE_SHAPE + SIDE[it], StickMath.DZ_RADIAL) }
    val stickAnchor = IntArray(2) { prefs.getInt(KEY_ANCHOR + SIDE[it], TouchRouter.ANCHOR_FLOATING) }
    val invertX = BooleanArray(2) { prefs.getBoolean(KEY_INVERT_X + SIDE[it], false) }
    val invertY = BooleanArray(2) { prefs.getBoolean(KEY_INVERT_Y + SIDE[it], false) }

    var dpadDeadzonePercent: Int = prefs.getInt(KEY_DPAD_DEADZONE, (TouchRouter.DPAD_DEADZONE * 100).roundToInt())

    /** Half-width of a D-pad diagonal in degrees (22.5 = all eight directions equal). */
    var dpadDiagonalDegrees: Float = prefs.getFloat(KEY_DPAD_DIAGONAL, DEFAULT_DPAD_DIAGONAL)
    var dpadFourWay: Boolean = prefs.getBoolean(KEY_DPAD_FOUR_WAY, false)

    /** Edge ring (sprint) in percent of the stick radius: starts past [edgeOnPercent], stops inside [edgeOffPercent]. */
    var edgeOnPercent: Int = prefs.getInt(KEY_EDGE_ON, (TouchRouter.EDGE_ON * 100).roundToInt())
    var edgeOffPercent: Int = prefs.getInt(KEY_EDGE_OFF, (TouchRouter.EDGE_OFF * 100).roundToInt())

    var pedalMinPercent: Int = prefs.getInt(KEY_PEDAL_MIN, (TouchRouter.PEDAL_MIN * 100).roundToInt())
    var pedalGainPercent: Int = prefs.getInt(KEY_PEDAL_GAIN, (TouchRouter.PEDAL_GAIN * 100).roundToInt())
    var pedalCurveTenths: Int = prefs.getInt(KEY_PEDAL_CURVE, 10)

    /** Haptic strength in percent, which events play, and whether they interrupt the game's rumble. */
    var hapticIntensityPercent: Int = prefs.getInt(KEY_HAPTIC_INTENSITY, (Haptics.DEFAULT_INTENSITY * 100).roundToInt())
    var hapticEvents: Int = prefs.getInt(KEY_HAPTIC_EVENTS, Haptics.ALL_EVENTS)
    var hapticsDuringRumble: Boolean = prefs.getBoolean(KEY_HAPTICS_DURING_RUMBLE, false)

    var rumbleGainPercent: Int = prefs.getInt(KEY_RUMBLE_GAIN, 100)
    var rumbleMix: Int = prefs.getInt(KEY_RUMBLE_MIX, Rumble.MIX_MAX)

    /** The controller this phone asks the PC to be ([Protocol.OUTPUT_NONE] = the PC decides). */
    var outputPreference: Int = prefs.getInt(KEY_OUTPUT, Protocol.OUTPUT_NONE)

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
    var gyroInvertX: Boolean = prefs.getBoolean(KEY_GYRO_INVERT_X, false)

    /** Which rotation aims sideways: [GyroFilter.AXIS_PLAYER], [GyroFilter.AXIS_LOCAL] or [GyroFilter.AXIS_ROLL]. */
    var gyroAxis: Int = prefs.getInt(KEY_GYRO_AXIS, GyroFilter.AXIS_PLAYER)

    /** Vertical and horizontal gyro sensitivity relative to [motionSensitivityPercent]. */
    var gyroSensitivityXPercent: Int = prefs.getInt(KEY_GYRO_SENSITIVITY_X, 100)
    var gyroSensitivityYPercent: Int = prefs.getInt(KEY_GYRO_SENSITIVITY_Y, 100)

    /** Gyro smoothing threshold in degrees per second (0 = off), see [GyroFilter.smoothThreshold]. */
    var gyroSmoothingDegrees: Int = prefs.getInt(KEY_GYRO_SMOOTHING, Math.toDegrees(GyroFilter.DEFAULT_SMOOTH_THRESHOLD.toDouble()).roundToInt())

    /** Gyro tightening threshold in degrees per second (0 = off), see [GyroFilter.tightening]. */
    var gyroTighteningDegrees: Int = prefs.getInt(KEY_GYRO_TIGHTENING, 0)

    /** Gyro acceleration: sensitivity at fast turns in percent of the slow one (100 = none). */
    var gyroAccelPercent: Int = prefs.getInt(KEY_GYRO_ACCEL, 100)

    var gyroAutoCalibrate: Boolean = prefs.getBoolean(KEY_GYRO_AUTO_CALIBRATE, true)

    /** Measured gyroscope bias of this phone (rad/s, device axes); restored at start-up. */
    var gyroBiasX: Float = prefs.getFloat(KEY_GYRO_BIAS_X, 0f)
    var gyroBiasY: Float = prefs.getFloat(KEY_GYRO_BIAS_Y, 0f)
    var gyroBiasZ: Float = prefs.getFloat(KEY_GYRO_BIAS_Z, 0f)

    fun save() {
        val edit = prefs.edit()
        for (i in 0..1) {
            edit.putInt(KEY_OUTER_DEADZONE + SIDE[i], outerDeadzonePercent[i])
                .putInt(KEY_DEADZONE_SHAPE + SIDE[i], deadzoneShape[i])
                .putInt(KEY_ANCHOR + SIDE[i], stickAnchor[i])
                .putBoolean(KEY_INVERT_X + SIDE[i], invertX[i])
                .putBoolean(KEY_INVERT_Y + SIDE[i], invertY[i])
        }
        edit
            .putString(KEY_HOST, host)
            .putInt(KEY_PORT, port)
            .putString(KEY_SERVER_NAME, serverName)
            .putString(KEY_KNOWN_PCS, knownPcs.serialize())
            .putInt(KEY_RATE, rateHz)
            .putBoolean(KEY_HAPTICS, haptics)
            .putBoolean(KEY_RUMBLE, rumble)
            .putInt(KEY_DEADZONE, deadzonePercent)
            .putInt(KEY_DEADZONE_RIGHT, deadzoneRightPercent)
            .putInt(KEY_MIN_PRESS, minPressMs)
            .putInt(KEY_DPAD_DEADZONE, dpadDeadzonePercent)
            .putFloat(KEY_DPAD_DIAGONAL, dpadDiagonalDegrees)
            .putBoolean(KEY_DPAD_FOUR_WAY, dpadFourWay)
            .putInt(KEY_EDGE_ON, edgeOnPercent)
            .putInt(KEY_EDGE_OFF, edgeOffPercent)
            .putInt(KEY_PEDAL_MIN, pedalMinPercent)
            .putInt(KEY_PEDAL_GAIN, pedalGainPercent)
            .putInt(KEY_PEDAL_CURVE, pedalCurveTenths)
            .putInt(KEY_HAPTIC_INTENSITY, hapticIntensityPercent)
            .putInt(KEY_HAPTIC_EVENTS, hapticEvents)
            .putBoolean(KEY_HAPTICS_DURING_RUMBLE, hapticsDuringRumble)
            .putInt(KEY_RUMBLE_GAIN, rumbleGainPercent)
            .putInt(KEY_RUMBLE_MIX, rumbleMix)
            .putInt(KEY_OUTPUT, outputPreference)
            .putBoolean(KEY_GYRO_INVERT_X, gyroInvertX)
            .putInt(KEY_GYRO_AXIS, gyroAxis)
            .putInt(KEY_GYRO_SENSITIVITY_X, gyroSensitivityXPercent)
            .putInt(KEY_GYRO_SENSITIVITY_Y, gyroSensitivityYPercent)
            .putInt(KEY_GYRO_SMOOTHING, gyroSmoothingDegrees)
            .putInt(KEY_GYRO_TIGHTENING, gyroTighteningDegrees)
            .putInt(KEY_GYRO_ACCEL, gyroAccelPercent)
            .putBoolean(KEY_GYRO_AUTO_CALIBRATE, gyroAutoCalibrate)
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

    fun saveGyroBias(x: Float, y: Float, z: Float) {
        gyroBiasX = x
        gyroBiasY = y
        gyroBiasZ = z
        prefs.edit().putFloat(KEY_GYRO_BIAS_X, x).putFloat(KEY_GYRO_BIAS_Y, y).putFloat(KEY_GYRO_BIAS_Z, z).apply()
    }

    /** Per-mode stick feel and motion switch; defaults come from the mode until the user changes them. */
    class ModeFeel(
        /** Right stick (and gyro) anti-deadzone; the key predates per-stick values, when it was both sticks'. */
        var antiDeadzonePercent: Int,
        /** Right-stick response exponent x 10 (10 = linear). */
        var curveTenths: Int,
        var sensitivityPercent: Int,
        var motion: Boolean,
        var antiDeadzoneLeftPercent: Int = antiDeadzonePercent,
    )

    fun feel(mode: GameMode): ModeFeel {
        val antiDeadzone = prefs.getInt(KEY_ANTI_DEADZONE + mode.id, (mode.antiDeadzone * 100).roundToInt())
        return ModeFeel(
            antiDeadzone,
            prefs.getInt(KEY_CURVE + mode.id, (mode.aimCurve * 10).roundToInt()),
            prefs.getInt(KEY_SENSITIVITY + mode.id, (mode.sensitivity * 100).roundToInt()),
            // Gyro aiming is gated behind the aim button, so it is safe to start on; tilt steering would
            // take over the car of anyone holding the phone at an angle, so it starts off.
            prefs.getBoolean(KEY_MOTION + mode.id, mode.motion == GameMode.MOTION_AIM && mode !== GameModes.CLASSIC),
            prefs.getInt(KEY_ANTI_DEADZONE_LEFT + mode.id, antiDeadzone),
        )
    }

    fun saveFeel(mode: GameMode, feel: ModeFeel) {
        prefs.edit()
            .putInt(KEY_ANTI_DEADZONE + mode.id, feel.antiDeadzonePercent)
            .putInt(KEY_ANTI_DEADZONE_LEFT + mode.id, feel.antiDeadzoneLeftPercent)
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

        /** About one 60 Hz game frame plus a packet: every tap is seen, and a double tap stays two. */
        const val DEFAULT_MIN_PRESS_MS = 20
        const val MAX_MIN_PRESS_MS = 100
        const val DEFAULT_DPAD_DIAGONAL = 22.5f
        const val MAX_GYRO_SMOOTHING_DEGREES = 20
        const val MAX_HAPTIC_INTENSITY_PERCENT = 100
        const val MAX_RUMBLE_GAIN_PERCENT = 200

        private val SIDE = arrayOf(".L", ".R")

        const val MAX_ANTI_DEADZONE_PERCENT = 40
        const val MIN_CURVE_TENTHS = 10
        const val MAX_CURVE_TENTHS = 30
        const val MIN_SENSITIVITY_PERCENT = 50
        const val MAX_SENSITIVITY_PERCENT = 200
        const val MIN_MOTION_SENSITIVITY_PERCENT = 50
        const val MAX_MOTION_SENSITIVITY_PERCENT = 300

        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SERVER_NAME = "serverName"
        private const val KEY_KNOWN_PCS = "knownPcs"
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
        private const val KEY_DEADZONE_RIGHT = "deadzoneRight"
        private const val KEY_MIN_PRESS = "minPress"
        private const val KEY_OUTER_DEADZONE = "outerDeadzone"
        private const val KEY_DEADZONE_SHAPE = "deadzoneShape"
        private const val KEY_ANCHOR = "anchor"
        private const val KEY_INVERT_X = "invertX"
        private const val KEY_INVERT_Y = "invertY"
        private const val KEY_DPAD_DEADZONE = "dpadDeadzone"
        private const val KEY_DPAD_DIAGONAL = "dpadDiagonal"
        private const val KEY_DPAD_FOUR_WAY = "dpadFourWay"
        private const val KEY_EDGE_ON = "edgeOn"
        private const val KEY_EDGE_OFF = "edgeOff"
        private const val KEY_PEDAL_MIN = "pedalMin"
        private const val KEY_PEDAL_GAIN = "pedalGain"
        private const val KEY_PEDAL_CURVE = "pedalCurve"
        private const val KEY_HAPTIC_INTENSITY = "hapticIntensity"
        private const val KEY_HAPTIC_EVENTS = "hapticEvents"
        private const val KEY_HAPTICS_DURING_RUMBLE = "hapticsDuringRumble"
        private const val KEY_RUMBLE_GAIN = "rumbleGain"
        private const val KEY_RUMBLE_MIX = "rumbleMix"
        private const val KEY_OUTPUT = "output"
        private const val KEY_GYRO_INVERT_X = "gyroInvertX"
        private const val KEY_GYRO_AXIS = "gyroAxis"
        private const val KEY_GYRO_SENSITIVITY_X = "gyroSensitivityX"
        private const val KEY_GYRO_SENSITIVITY_Y = "gyroSensitivityY"
        private const val KEY_GYRO_SMOOTHING = "gyroSmoothing"
        private const val KEY_GYRO_TIGHTENING = "gyroTightening"
        private const val KEY_GYRO_ACCEL = "gyroAccel"
        private const val KEY_GYRO_AUTO_CALIBRATE = "gyroAutoCalibrate"
        private const val KEY_GYRO_BIAS_X = "gyroBiasX"
        private const val KEY_GYRO_BIAS_Y = "gyroBiasY"
        private const val KEY_GYRO_BIAS_Z = "gyroBiasZ"
        private const val KEY_ANTI_DEADZONE = "antiDeadzone."
        private const val KEY_ANTI_DEADZONE_LEFT = "antiDeadzoneLeft."
        private const val KEY_CURVE = "curve."
        private const val KEY_SENSITIVITY = "sensitivity."
        private const val KEY_MOTION = "motion."
        private const val KEY_LAYOUT_PREFIX = "layout."
        private const val KEY_LEGACY_LAYOUT = "layout"
    }
}
