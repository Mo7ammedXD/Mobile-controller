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
    private val onApply: (editLayout: Boolean) -> Unit,
) {
    private val builder = AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    // Inflated with the dialog's themed context so text and controls match the dialog, not the activity.
    private val root: View = View.inflate(builder.context, R.layout.dialog_settings, null)
    private val mode: Spinner = root.findViewById(R.id.mode)
    private val modeDescription: TextView = root.findViewById(R.id.mode_description)
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
    private val host: EditText = root.findViewById(R.id.host)
    private val port: EditText = root.findViewById(R.id.port)
    private val find: Button = root.findViewById(R.id.find)
    private val discoveryStatus: TextView = root.findViewById(R.id.discovery_status)
    private val servers: LinearLayout = root.findViewById(R.id.servers)
    private val rate: RadioGroup = root.findViewById(R.id.rate)
    private val deadzoneLabel: TextView = root.findViewById(R.id.deadzone_label)
    private val deadzone: SeekBar = root.findViewById(R.id.deadzone)
    private val haptics: Switch = root.findViewById(R.id.haptics)
    private val rumble: Switch = root.findViewById(R.id.rumble)
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

    /** Feel edits per mode while the dialog is open (switching the spinner must not lose them). */
    private val feels = HashMap<String, Settings.ModeFeel>()
    private var shownMode: GameMode = GameModes.byId(settings.modeId)

    init {
        mode.adapter = ArrayAdapter(root.context, android.R.layout.simple_spinner_item, GameModes.ALL.map { it.title })
            .apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        antiDeadzone.max = Settings.MAX_ANTI_DEADZONE_PERCENT
        curve.max = Settings.MAX_CURVE_TENTHS - Settings.MIN_CURVE_TENTHS
        sensitivity.max = (Settings.MAX_SENSITIVITY_PERCENT - Settings.MIN_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        motionSensitivity.max = (Settings.MAX_MOTION_SENSITIVITY_PERCENT - Settings.MIN_MOTION_SENSITIVITY_PERCENT) / SENSITIVITY_STEP
        onProgress(antiDeadzone) { antiDeadzoneLabel.text = activity.getString(R.string.settings_anti_deadzone, it) }
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
        deadzone.max = Settings.MAX_DEADZONE_PERCENT
        deadzone.progress = settings.deadzonePercent
        haptics.isChecked = settings.haptics
        rumble.isChecked = settings.rumble
        floating.isChecked = settings.floatingButtons
        dim.isChecked = settings.dimScreen
        leftHanded.isChecked = settings.leftHanded
        autoMode.isChecked = settings.autoMode

        find.setOnClickListener { startDiscovery() }
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
        gyroWhileAiming.visibility = if (gyro) View.VISIBLE else View.GONE
        gyroInvert.visibility = if (gyro) View.VISIBLE else View.GONE
        val on = motion.isEnabled && motion.isChecked
        gyroWhileAiming.isEnabled = on
        gyroInvert.isEnabled = on
        motionSensitivity.isEnabled = on
    }

    private fun readFeel(m: GameMode) = Settings.ModeFeel(
        antiDeadzone.progress,
        curve.progress + Settings.MIN_CURVE_TENTHS,
        Settings.MIN_SENSITIVITY_PERCENT + sensitivity.progress * SENSITIVITY_STEP,
        // A mode without motion (or a phone without the sensor) keeps whatever was stored before.
        if (motion.isEnabled) motion.isChecked else settings.feel(m).motion,
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
        val pad = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 10f, activity.resources.displayMetrics).toInt()
        val selectable = TypedValue().also {
            root.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }
        for (server in found) {
            servers.addView(
                TextView(root.context).apply {
                    text = activity.getString(
                        R.string.discovery_row, server.name, server.host, server.port, server.padsInUse, server.maxPads,
                    )
                    setPadding(pad, pad, pad, pad)
                    if (selectable.resourceId != 0) setBackgroundResource(selectable.resourceId)
                    setOnClickListener { pick(server) }
                },
            )
        }
        if (autoFill && found.size == 1) pick(found[0])
        autoFill = false
    }

    private fun pick(server: Discovery.Server) {
        host.setText(server.host)
        host.error = null
        port.setText(digits(server.port))
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
        settings.host = h
        settings.port = p
        settings.rateHz = when (rate.checkedRadioButtonId) {
            R.id.rate_60 -> 60
            R.id.rate_90 -> 90
            R.id.rate_120 -> 120
            else -> settings.rateHz
        }
        settings.deadzonePercent = deadzone.progress
        settings.haptics = haptics.isChecked
        settings.rumble = rumble.isChecked
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
    }
}
