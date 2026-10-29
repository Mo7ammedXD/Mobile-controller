package com.mobilecontroller.client.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.DialogInterface
import android.util.TypedValue
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import com.mobilecontroller.client.R
import com.mobilecontroller.client.Settings
import com.mobilecontroller.client.net.Discovery
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.protocol.Protocol
import java.util.Locale

/**
 * The settings dialog: game mode and its feel (stick shaping, motion controls), PC address (typed or
 * found by discovery), send rate, comfort options and the entry into layout editing (of the selected
 * mode). Values are written to [settings] only on Save / Edit layout; [onApply] then pushes them to the
 * running controller.
 */
class SettingsDialog(
    private val activity: Activity,
    private val settings: Settings,
    private val discovery: Discovery,
    /** Whether this phone has the sensor a [GameMode.motion] needs. */
    private val motionAvailable: (Int) -> Boolean,
    /** "Calibrate gyro": measures the resting offset and stores it (not part of Save / Cancel). */
    private val onCalibrateGyro: () -> Unit,
    private val onApply: (editLayout: Boolean) -> Unit,
) {
    private val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    // Inflated with the dialog's themed context so text and controls match the dialog, not the activity.
    private val root: View = View.inflate(builder.context, R.layout.dialog_settings, null)
    private val mode: Spinner = root.findViewById(R.id.mode)
    private val modeDescription: TextView = root.findViewById(R.id.mode_description)
    private val antiDeadzoneLeftLabel: TextView = root.findViewById(R.id.anti_deadzone_left_label)
    private val antiDeadzoneLeft: SeekBar = root.findViewById(R.id.anti_deadzone_left)
    private val antiDeadzoneLabel: TextView = root.findViewById(R.id.anti_deadzone_label)
    private val antiDeadzone: SeekBar = root.findViewById(R.id.anti_deadzone)
    private val curveLabel: TextView = root.findViewById(R.id.curve_label)
    private val curve: SeekBar = root.findViewById(R.id.curve)
    private val sensitivityLabel: TextView = root.findViewById(R.id.sensitivity_label)
    private val sensitivity: SeekBar = root.findViewById(R.id.sensitivity)
    private val motion: Switch = root.findViewById(R.id.motion)
    private val motionNote: TextView = root.findViewById(R.id.motion_note)
    private val gyroWhileAiming: CheckBox = root.findViewById(R.id.gyro_while_aiming)
    private val gyroInvert: CheckBox = root.findViewById(R.id.gyro_invert)
    private val motionSensitivityLabel: TextView = root.findViewById(R.id.motion_sensitivity_label)
    private val motionSensitivity: SeekBar = root.findViewById(R.id.motion_sensitivity)
    private val gyroSmoothingLabel: TextView = root.findViewById(R.id.gyro_smoothing_label)
    private val gyroSmoothing: SeekBar = root.findViewById(R.id.gyro_smoothing)
    private val calibrateGyro: Button = root.findViewById(R.id.calibrate_gyro)
    private val host: EditText = root.findViewById(R.id.host)
    private val port: EditText = root.findViewById(R.id.port)
    private val find: Button = root.findViewById(R.id.find)
    private val discoveryStatus: TextView = root.findViewById(R.id.discovery_status)
    private val servers: LinearLayout = root.findViewById(R.id.servers)
    private val rate: RadioGroup = root.findViewById(R.id.rate)
    private val output: RadioGroup = root.findViewById(R.id.output)
    private val deadzoneLabel: TextView = root.findViewById(R.id.deadzone_label)
    private val deadzone: SeekBar = root.findViewById(R.id.deadzone)
    private val deadzoneRightLabel: TextView = root.findViewById(R.id.deadzone_right_label)
    private val deadzoneRight: SeekBar = root.findViewById(R.id.deadzone_right)
    private val minPressLabel: TextView = root.findViewById(R.id.min_press_label)
    private val minPress: SeekBar = root.findViewById(R.id.min_press)
    private val haptics: Switch = root.findViewById(R.id.haptics)
    private val hapticIntensityLabel: TextView = root.findViewById(R.id.haptic_intensity_label)
    private val hapticIntensity: SeekBar = root.findViewById(R.id.haptic_intensity)
    private val rumble: Switch = root.findViewById(R.id.rumble)
    private val rumbleGainLabel: TextView = root.findViewById(R.id.rumble_gain_label)
    private val rumbleGain: SeekBar = root.findViewById(R.id.rumble_gain)
    private val floating: Switch = root.findViewById(R.id.floating)
    private val dim: Switch = root.findViewById(R.id.dim)
    private val leftHanded: Switch = root.findViewById(R.id.left_handed)
    private val autoMode: Switch = root.findViewById(R.id.auto_mode)

    private val dialog: AlertDialog = builder
        .setTitle(R.string.settings_title)
        .setView(root)
        .setPositiveButton(R.string.save, null)
        .setNeutralButton(R.string.edit_layout, null)
        .setNegativeButton(R.string.cancel, null)
        .create()

    /** First run: the single PC found is filled in automatically. */
    private var autoFill = false

    /** The PC last picked from the search or the recent list, so Save can remember its name. */
    private var picked: KnownPcs.KnownPc? = null

    /** Feel edits per mode while the dialog is open (switching the spinner must not lose them). */
    private val feels = HashMap<String, Settings.ModeFeel>()
    private var shownMode: GameMode = GameModes.byId(settings.modeId)

    init {
        mode.adapter = ArrayAdapter(root.context, android.R.layout.simple_spinner_item, GameModes.ALL.map { it.title })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        antiDeadzoneLeft.max = Settings.MAX_ANTI_DEADZONE_PERCENT
        antiDeadzone.max = Settings.MAX_ANTI_DEADZONE_PERCENT
        curve.max = Settings.MAX_CURVE_TENTHS - Settings.MIN_CURVE_TENTHS
        sensitivity.max = (Settings.MAX_SENSITIVITY_PERCENT - Settings.MIN_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        motionSensitivity.max = (Settings.MAX_MOTION_SENSITIVITY_PERCENT - Settings.MIN_MOTION_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        gyroSmoothing.max = Settings.MAX_GYRO_SMOOTHING_DEGREES
        minPress.max = Settings.MAX_MIN_PRESS_MS / MIN_PRESS_STEP
        hapticIntensity.max = Settings.MAX_HAPTIC_INTENSITY_PERCENT / PERCENT_STEP
        rumbleGain.max = Settings.MAX_RUMBLE_GAIN_PERCENT / PERCENT_STEP
        onProgress(antiDeadzoneLeft) { antiDeadzoneLeftLabel.text = activity.getString(R.string.settings_anti_deadzone_left, it) }
        onProgress(antiDeadzone) { antiDeadzoneLabel.text = activity.getString(R.string.settings_anti_deadzone, it) }
        onProgress(gyroSmoothing) {
            gyroSmoothingLabel.text = if (it == 0) {
                activity.getString(R.string.settings_gyro_smoothing_off)
            } else {
                activity.getString(R.string.settings_gyro_smoothing, it)
            }
        }
        onProgress(minPress) {
            minPressLabel.text = if (it == 0) {
                activity.getString(R.string.settings_min_press_off)
            } else {
                activity.getString(R.string.settings_min_press, it * MIN_PRESS_STEP)
            }
        }
        onProgress(hapticIntensity) { hapticIntensityLabel.text = activity.getString(R.string.settings_haptic_intensity, it * PERCENT_STEP) }
        onProgress(rumbleGain) { rumbleGainLabel.text = activity.getString(R.string.settings_rumble_gain, it * PERCENT_STEP) }
        onProgress(curve) {
            val tenths = it + Settings.MIN_CURVE_TENTHS
            curveLabel.text = if (tenths == Settings.MIN_CURVE_TENTHS) {
                activity.getString(R.string.settings_curve_linear)
            } else {
                activity.getString(R.string.settings_curve, tenths / 10f)
            }
        }
        onProgress(sensitivity) {
            sensitivityLabel.text = activity.getString(R.string.settings_sensitivity, Settings.MIN_SENSITIVITY_PERCENT + it * SENSITIVITY_STEP)
        }
        onProgress(motionSensitivity) {
            motionSensitivityLabel.text =
                activity.getString(R.string.settings_motion_sensitivity, Settings.MIN_MOTION_SENSITIVITY_PERCENT + it * SENSITIVITY_STEP)
        }
        onProgress(deadzone) { deadzoneLabel.text = activity.getString(R.string.settings_deadzone, it) }
        onProgress(deadzoneRight) { deadzoneRightLabel.text = activity.getString(R.string.settings_deadzone_right, it) }

        mode.setSelection(GameModes.ALL.indexOf(shownMode))
        showMode(shownMode)
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>, view: View?, position: Int, id: Long) {
                val next = GameModes.ALL[position.coerceIn(0, GameModes.ALL.size - 1)]
                if (next === shownMode) return
                feels[shownMode.id] = readFeel(shownMode)
                shownMode = next
                showMode(next)
            }

            override fun onNothingSelected(parent: AdapterView<*>) = Unit
        }
        motion.setOnCheckedChangeListener { _, _ -> updateMotionOptions() }
        gyroWhileAiming.isChecked = settings.gyroWhileAiming
        gyroInvert.isChecked = settings.gyroInvertY
        motionSensitivity.progress = (settings.motionSensitivityPercent - Settings.MIN_MOTION_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        gyroSmoothing.progress = settings.gyroSmoothingDegrees
        calibrateGyro.setOnClickListener { onCalibrateGyro() }

        host.setText(settings.host)
        port.setText(digits(settings.port))
        rate.check(
            when (settings.rateHz) {
                60 -> R.id.rate_60
                90 -> R.id.rate_90
                120 -> R.id.rate_120
                else -> View.NO_ID
            },
        )
        output.check(
            when (settings.outputPreference) {
                Protocol.OUTPUT_XBOX360 -> R.id.output_xbox
                Protocol.OUTPUT_DS4 -> R.id.output_ds4
                else -> R.id.output_auto
            },
        )
        deadzone.max = Settings.MAX_DEADZONE_PERCENT
        deadzone.progress = settings.deadzonePercent
        deadzoneRight.max = Settings.MAX_DEADZONE_PERCENT
        deadzoneRight.progress = settings.deadzoneRightPercent
        minPress.progress = settings.minPressMs / MIN_PRESS_STEP
        haptics.isChecked = settings.haptics
        hapticIntensity.progress = settings.hapticIntensityPercent / PERCENT_STEP
        rumble.isChecked = settings.rumble
        rumbleGain.progress = settings.rumbleGainPercent / PERCENT_STEP
        floating.isChecked = settings.floatingButtons
        dim.isChecked = settings.dimScreen
        leftHanded.isChecked = settings.leftHanded
        autoMode.isChecked = settings.autoMode

        find.setOnClickListener { startDiscovery() }
        showRecent()
        dialog.setOnShowListener {
            // Replace the auto-dismissing handlers so invalid input keeps the dialog open.
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { if (commit()) close(editLayout = false) }
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setOnClickListener { if (commit()) close(editLayout = true) }
            dialog.getButton(DialogInterface.BUTTON_NEGATIVE).setOnClickListener { dialog.dismiss() }
        }
        dialog.setOnDismissListener { discovery.cancel() }
    }

    val isShowing: Boolean get() = dialog.isShowing

    fun show(discover: Boolean) {
        dialog.show()
        if (discover) {
            autoFill = host.text.isBlank()
            startDiscovery()
        }
    }

    fun dismiss() = dialog.dismiss()

    private fun close(editLayout: Boolean) {
        dialog.dismiss()
        onApply(editLayout)
    }

    /** Fills the mode description and its feel controls (edits made earlier in this dialog win). */
    private fun showMode(m: GameMode) {
        modeDescription.text = activity.getString(R.string.mode_description, m.bestFor, m.tips)
        val feel = feels[m.id] ?: settings.feel(m)
        antiDeadzoneLeft.progress = feel.antiDeadzoneLeftPercent
        antiDeadzone.progress = feel.antiDeadzonePercent
        curve.progress = feel.curveTenths - Settings.MIN_CURVE_TENTHS
        sensitivity.progress = (feel.sensitivityPercent - Settings.MIN_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        motion.setText(
            when (m.motion) {
                GameMode.MOTION_AIM -> R.string.settings_motion_aim
                GameMode.MOTION_STEER -> R.string.settings_motion_steer
                else -> R.string.settings_motion_none
            },
        )
        val available = m.motion != GameMode.MOTION_NONE && motionAvailable(m.motion)
        motion.isEnabled = available
        motion.isChecked = available && feel.motion
        motionNote.visibility = if (m.motion != GameMode.MOTION_NONE && !available) View.VISIBLE else View.GONE
        motionNote.setText(R.string.settings_motion_unavailable)
        updateMotionOptions()
    }

    private fun updateMotionOptions() {
        val gyro = shownMode.motion == GameMode.MOTION_AIM
        val gyroVisibility = if (gyro) View.VISIBLE else View.GONE
        gyroWhileAiming.visibility = gyroVisibility
        gyroInvert.visibility = gyroVisibility
        gyroSmoothingLabel.visibility = gyroVisibility
        gyroSmoothing.visibility = gyroVisibility
        // Calibration works in any mode (it starts the gyroscope itself), but only means something here.
        calibrateGyro.visibility = if (gyro && motionAvailable(GameMode.MOTION_AIM)) View.VISIBLE else View.GONE
        val on = motion.isEnabled && motion.isChecked
        gyroWhileAiming.isEnabled = on
        gyroInvert.isEnabled = on
        gyroSmoothing.isEnabled = on
        motionSensitivity.isEnabled = on
    }

    private fun readFeel(m: GameMode) = Settings.ModeFeel(
        antiDeadzone.progress,
        curve.progress + Settings.MIN_CURVE_TENTHS,
        Settings.MIN_SENSITIVITY_PERCENT + sensitivity.progress * SENSITIVITY_STEP,
        // A mode without motion (or a phone without the sensor) keeps whatever was stored before.
        if (motion.isEnabled) motion.isChecked else settings.feel(m).motion,
        antiDeadzoneLeft.progress,
    )

    private inline fun onProgress(bar: SeekBar, crossinline show: (Int) -> Unit) {
        show(bar.progress)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) = show(value)
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
    }

    private fun startDiscovery() {
        val p = parsePort() ?: Protocol.DEFAULT_PORT
        find.isEnabled = false
        servers.removeAllViews()
        discoveryStatus.visibility = View.VISIBLE
        discoveryStatus.setText(R.string.discovery_searching)
        discovery.start(p, ::showServers)
    }

    private fun showServers(found: List<Discovery.Server>) {
        find.isEnabled = true
        discoveryStatus.text = if (found.isEmpty()) {
            activity.getString(R.string.discovery_none)
        } else {
            activity.resources.getQuantityString(R.plurals.discovery_found, found.size, found.size)
        }
        for (server in found) {
            addRow(activity.getString(R.string.discovery_row, server.name, server.host, server.port, server.padsInUse, server.maxPads)) {
                pick(KnownPcs.KnownPc(server.name, server.host, server.port))
            }
        }
        if (autoFill && found.size == 1) pick(KnownPcs.KnownPc(found[0].name, found[0].host, found[0].port))
        autoFill = false
    }

    /** PCs this phone used before, one tap each (a search replaces them with what it finds). */
    private fun showRecent() {
        for (pc in settings.knownPcs.all) {
            addRow(activity.getString(R.string.recent_pc, pc.name.ifEmpty { pc.host }, pc.host, pc.port)) { pick(pc) }
        }
    }

    private fun addRow(label: String, onClick: () -> Unit) {
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
        val selectable = TypedValue().also {
            root.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        servers.addView(
            TextView(root.context).apply {
                text = label
                setPadding(pad, pad, pad, pad)
                if (selectable.resourceId != 0) setBackgroundResource(selectable.resourceId)
                setOnClickListener { onClick() }
            },
        )
    }

    private fun pick(pc: KnownPcs.KnownPc) {
        picked = pc
        host.setText(pc.host)
        host.error = null
        port.setText(digits(pc.port))
        port.error = null
    }

    /** Ports are typed and parsed as ASCII digits whatever the locale's numerals. */
    private fun digits(value: Int) = String.format(Locale.ROOT, "%d", value)

    private fun parsePort(): Int? = port.text.toString().trim().toIntOrNull()?.takeIf { it in 1..65535 }

    /** Validates the form and stores it in [settings]. Returns false (with an inline error) if invalid. */
    private fun commit(): Boolean {
        val h = host.text.toString().trim()
        if (h.any { it.isWhitespace() || it == '/' }) {
            host.error = activity.getString(R.string.error_host)
            return false
        }
        val p = parsePort()
        if (p == null) {
            port.error = activity.getString(R.string.error_port)
            return false
        }
        feels[shownMode.id] = readFeel(shownMode)
        for ((id, feel) in feels) settings.saveFeel(GameModes.byId(id), feel)
        settings.modeId = shownMode.id
        // The PC's name follows a pick; an address typed by hand has none until the phone connects and learns it.
        val pc = picked
        if (pc != null && pc.host == h && pc.port == p) {
            settings.serverName = pc.name
        } else if (h != settings.host || p != settings.port) {
            settings.serverName = ""
        }
        settings.host = h
        settings.port = p
        settings.rateHz = when (rate.checkedRadioButtonId) {
            R.id.rate_60 -> 60
            R.id.rate_90 -> 90
            R.id.rate_120 -> 120
            else -> settings.rateHz
        }
        settings.outputPreference = when (output.checkedRadioButtonId) {
            R.id.output_xbox -> Protocol.OUTPUT_XBOX360
            R.id.output_ds4 -> Protocol.OUTPUT_DS4
            else -> Protocol.OUTPUT_NONE
        }
        settings.deadzonePercent = deadzone.progress
        settings.deadzoneRightPercent = deadzoneRight.progress
        settings.minPressMs = minPress.progress * MIN_PRESS_STEP
        settings.haptics = haptics.isChecked
        settings.hapticIntensityPercent = hapticIntensity.progress * PERCENT_STEP
        settings.rumble = rumble.isChecked
        settings.rumbleGainPercent = rumbleGain.progress * PERCENT_STEP
        settings.gyroSmoothingDegrees = gyroSmoothing.progress
        settings.floatingButtons = floating.isChecked
        settings.dimScreen = dim.isChecked
        settings.leftHanded = leftHanded.isChecked
        settings.autoMode = autoMode.isChecked
        settings.gyroWhileAiming = gyroWhileAiming.isChecked
        settings.gyroInvertY = gyroInvert.isChecked
        settings.motionSensitivityPercent = Settings.MIN_MOTION_SENSITIVITY_PERCENT + motionSensitivity.progress * SENSITIVITY_STEP
        settings.save()
        return true
    }

    private companion object {
        const val SENSITIVITY_STEP = 10
        const val MIN_PRESS_STEP = 5
        const val PERCENT_STEP = 10
    }
}
