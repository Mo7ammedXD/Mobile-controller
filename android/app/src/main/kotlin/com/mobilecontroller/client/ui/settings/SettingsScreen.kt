package com.mobilecontroller.client.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.mobilecontroller.client.DeviceIdentity
import com.mobilecontroller.client.Haptics
import com.mobilecontroller.client.R
import com.mobilecontroller.client.Rumble
import com.mobilecontroller.client.Settings
import com.mobilecontroller.client.input.GyroFilter
import com.mobilecontroller.client.input.StickMath
import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import kotlinx.coroutines.delay

private val Sections = listOf(
    R.string.section_connection, R.string.section_mode, R.string.section_sticks, R.string.section_buttons,
    R.string.section_motion, R.string.section_feedback, R.string.section_display, R.string.section_about,
)

/**
 * The settings, full screen over the controller (the activity stays resumed, so the PC keeps the controller and the
 * previews use the real sensors). Sections on the left, their settings on the right; Done saves, Cancel drops the
 * edits, Back is Done.
 */
@Composable
fun SettingsScreen(editor: SettingsEditor, host: SettingsHost, startSearch: Boolean) {
    var section by rememberSaveable { mutableIntStateOf(0) }

    fun done(editLayout: Boolean) {
        if (editor.save()) host.closeSettings(save = true, editLayout = editLayout) else section = 0
    }
    BackHandler { done(editLayout = false) }
    LaunchedEffect(Unit) { if (startSearch) search(editor, host, autoFill = true) }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_title), Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                TextButton(onClick = { host.closeSettings(save = false, editLayout = false) }) { Text(stringResource(R.string.cancel)) }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { done(editLayout = true) }) { Text(stringResource(R.string.edit_layout)) }
                Spacer(Modifier.width(8.dp))
                Button(onClick = { done(editLayout = false) }) { Text(stringResource(R.string.done)) }
            }
            Row(Modifier.weight(1f)) {
                Column(Modifier.width(190.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(start = 8.dp)) {
                    Sections.forEachIndexed { i, title ->
                        RailItem(stringResource(title), selected = i == section) { section = i }
                    }
                }
                key(section) {
                    Column(
                        Modifier.weight(1f).fillMaxHeight().imePadding().verticalScroll(rememberScrollState())
                            .padding(start = 8.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        when (section) {
                            0 -> ConnectionSection(editor, host)
                            1 -> ModeSection(editor)
                            2 -> SticksSection(editor)
                            3 -> ButtonsSection(editor)
                            4 -> MotionSection(editor, host)
                            5 -> FeedbackSection(editor, host)
                            6 -> DisplaySection(editor)
                            else -> AboutSection()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RailItem(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.background,
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
    ) {
        Text(
            label,
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

private fun search(editor: SettingsEditor, host: SettingsHost, autoFill: Boolean) {
    val port = editor.portText.trim().toIntOrNull()?.takeIf { it in 1..65535 } ?: Protocol.DEFAULT_PORT
    val blank = editor.hostText.isBlank()
    editor.searching = true
    editor.found = null
    host.findPcs(port) { servers ->
        editor.found = servers
        editor.searching = false
        // First run: the single PC found is filled in.
        if (autoFill && blank && servers.size == 1) servers[0].let { editor.pick(KnownPcs.KnownPc(it.name, it.host, it.port)) }
    }
}

// --- Connection -------------------------------------------------------------------------------------------------

@Composable
private fun ConnectionSection(editor: SettingsEditor, host: SettingsHost) {
    editor.version
    val s = editor.settings
    var status by remember { mutableStateOf(host.linkStatus()) }
    LaunchedEffect(Unit) {
        while (true) {
            status = host.linkStatus()
            delay(500)
        }
    }

    SettingsCard(stringResource(R.string.connection_status)) {
        Text(statusText(status), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
    }

    SettingsCard(stringResource(R.string.settings_pc)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = editor.hostText,
                onValueChange = {
                    editor.hostText = it
                    editor.hostError = false
                },
                label = { Text(stringResource(R.string.hint_host)) },
                isError = editor.hostError,
                supportingText = if (editor.hostError) ({ Text(stringResource(R.string.error_host)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = editor.portText,
                onValueChange = {
                    editor.portText = it.filter(Char::isDigit).take(5)
                    editor.portError = false
                },
                label = { Text(stringResource(R.string.hint_port)) },
                isError = editor.portError,
                supportingText = if (editor.portError) ({ Text(stringResource(R.string.error_port)) }) else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.width(120.dp),
            )
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            FilledTonalButton(onClick = { search(editor, host, autoFill = false) }, enabled = !editor.searching) {
                Text(stringResource(R.string.find_pc))
            }
        }
        if (editor.searching) {
            Caption(stringResource(R.string.discovery_searching), Modifier.padding(top = 8.dp))
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        editor.found?.let { found ->
            Caption(
                if (found.isEmpty()) stringResource(R.string.discovery_none)
                else pluralStringResource(R.plurals.discovery_found, found.size, found.size),
                Modifier.padding(top = 8.dp),
            )
            for (server in found) {
                PickRow(stringResource(R.string.discovery_row, server.name, server.host, server.port, server.padsInUse, server.maxPads)) {
                    editor.pick(KnownPcs.KnownPc(server.name, server.host, server.port))
                }
            }
        }
        for (pc in s.knownPcs.all) {
            PickRow(stringResource(R.string.recent_pc, pc.name.ifEmpty { pc.host }, pc.host, pc.port)) { editor.pick(pc) }
        }
    }

    SettingsCard(stringResource(R.string.settings_output)) {
        ChoiceRow(
            label = "",
            options = listOf(
                Protocol.OUTPUT_NONE to stringResource(R.string.output_auto),
                Protocol.OUTPUT_XBOX360 to stringResource(R.string.output_xbox),
                Protocol.OUTPUT_DS4 to stringResource(R.string.output_ds4),
            ),
            selected = s.outputPreference,
            onSelect = { v -> editor.edit { outputPreference = v } },
            caption = stringResource(R.string.output_caption),
        )
    }

    SettingsCard(stringResource(R.string.settings_rate)) {
        SliderRow(
            stringResource(R.string.settings_rate), s.rateHz.coerceIn(UdpLink.MIN_RATE_HZ, UdpLink.MAX_RATE_HZ),
            UdpLink.MIN_RATE_HZ, UdpLink.MAX_RATE_HZ, { v -> editor.edit { rateHz = v } }, step = 10,
            caption = stringResource(R.string.rate_caption), format = { "$it Hz" },
        )
        SwitchRow(
            stringResource(R.string.settings_auto_mode), s.autoMode, { v -> editor.edit { autoMode = v } },
            caption = stringResource(R.string.auto_mode_caption),
        )
    }
}

@Composable
private fun PickRow(text: String, onClick: () -> Unit) {
    Text(
        text,
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 4.dp),
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}

@Composable
private fun statusText(status: LinkStatus): String = when (status.code) {
    0 -> stringResource(R.string.status_permission)
    UdpLink.STATUS_CONNECTED -> buildList {
        add(stringResource(R.string.status_connected))
        when {
            status.dryRun -> add(stringResource(R.string.status_dry_run))
            status.ds4 -> add(stringResource(R.string.status_ds4))
            status.player in 0..3 -> add(stringResource(R.string.status_player, status.player + 1))
        }
        if (status.rttMs >= 0) add(stringResource(R.string.status_rtt, status.rttMs))
    }.joinToString("  ·  ")
    UdpLink.STATUS_WAITING -> stringResource(R.string.status_waiting) + "  ·  ${status.host}:${status.port}"
    UdpLink.STATUS_NOT_LISTENING -> stringResource(R.string.status_not_listening) + "  ·  ${status.host}:${status.port}"
    UdpLink.STATUS_SERVER_FULL -> stringResource(R.string.status_server_full)
    UdpLink.STATUS_BLOCKED -> stringResource(R.string.status_blocked)
    UdpLink.STATUS_NO_ROUTE -> stringResource(R.string.status_no_route)
    UdpLink.STATUS_UNKNOWN_HOST -> stringResource(R.string.status_unknown_host) + "  ·  ${status.host}"
    else -> stringResource(R.string.status_no_target)
}

// --- Game mode ------------------------------------------------------------------------------------------------

@Composable
private fun ModeSection(editor: SettingsEditor) {
    editor.version
    val s = editor.settings
    for (mode in GameModes.ALL) {
        val selected = mode.id == s.modeId
        Card(
            onClick = { editor.edit { modeId = mode.id } },
            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            colors = CardDefaults.cardColors(
                containerColor = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainer,
            ),
            border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(mode.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Caption(stringResource(R.string.mode_best_for, mode.bestFor))
                Caption(mode.tips)
            }
        }
    }
    Caption(stringResource(R.string.edit_layout_caption), Modifier.padding(bottom = 8.dp))
    SettingsCard(stringResource(R.string.section_mode)) {
        SwitchRow(
            stringResource(R.string.settings_floating), s.floatingButtons, { v -> editor.edit { floatingButtons = v } },
            caption = stringResource(R.string.floating_caption),
        )
        SwitchRow(stringResource(R.string.settings_left_handed), s.leftHanded, { v -> editor.edit { leftHanded = v } })
    }
}

// --- Sticks ---------------------------------------------------------------------------------------------------

@Composable
private fun SticksSection(editor: SettingsEditor) {
    editor.version
    val s = editor.settings
    val feel = editor.feel()
    var stick by rememberSaveable { mutableIntStateOf(1) }

    ChoiceRow(
        label = "",
        options = listOf(0 to stringResource(R.string.stick_left), 1 to stringResource(R.string.stick_right)),
        selected = stick,
        onSelect = { stick = it },
    )
    val inner = if (stick == 0) s.deadzonePercent else s.deadzoneRightPercent
    val antiDeadzone = if (stick == 0) feel.antiDeadzoneLeftPercent else feel.antiDeadzonePercent
    val curve = if (stick == 0) 10 else feel.curveTenths

    SettingsCard(stringResource(if (stick == 0) R.string.stick_left else R.string.stick_right)) {
        SliderRow(
            stringResource(R.string.deadzone), inner, 0, Settings.MAX_DEADZONE_PERCENT,
            { v -> editor.edit { if (stick == 0) deadzonePercent = v else deadzoneRightPercent = v } },
            caption = stringResource(R.string.deadzone_caption), format = ::percent,
        )
        SliderRow(
            stringResource(R.string.outer_deadzone), s.outerDeadzonePercent[stick], 0, 30,
            { v -> editor.edit { outerDeadzonePercent[stick] = v } },
            caption = stringResource(R.string.outer_deadzone_caption), format = ::percent,
        )
        ChoiceRow(
            stringResource(R.string.deadzone_shape),
            listOf(
                StickMath.DZ_RADIAL to stringResource(R.string.shape_round),
                StickMath.DZ_AXIAL to stringResource(R.string.shape_axial),
                StickMath.DZ_SNAP to stringResource(R.string.shape_snap),
            ),
            s.deadzoneShape[stick], { v -> editor.edit { deadzoneShape[stick] = v } },
            caption = stringResource(R.string.shape_caption),
        )
        ChoiceRow(
            stringResource(R.string.anchor),
            listOf(
                TouchRouter.ANCHOR_FLOATING to stringResource(R.string.anchor_floating),
                TouchRouter.ANCHOR_FIXED to stringResource(R.string.anchor_fixed),
                TouchRouter.ANCHOR_FOLLOW to stringResource(R.string.anchor_follow),
            ),
            s.stickAnchor[stick], { v -> editor.edit { stickAnchor[stick] = v } },
            caption = stringResource(R.string.anchor_caption),
        )
        SwitchRow(stringResource(R.string.invert_x), s.invertX[stick], { v -> editor.edit { invertX[stick] = v } })
        SwitchRow(stringResource(R.string.invert_y), s.invertY[stick], { v -> editor.edit { invertY[stick] = v } })
    }

    SettingsCard(stringResource(R.string.feel_title, editor.mode.title)) {
        SliderRow(
            stringResource(R.string.anti_deadzone), antiDeadzone, 0, Settings.MAX_ANTI_DEADZONE_PERCENT,
            { v -> editor.editFeel { if (stick == 0) antiDeadzoneLeftPercent = v else antiDeadzonePercent = v } },
            caption = stringResource(R.string.anti_deadzone_caption), format = ::percent,
        )
        SliderRow(
            stringResource(R.string.sensitivity), feel.sensitivityPercent, Settings.MIN_SENSITIVITY_PERCENT, Settings.MAX_SENSITIVITY_PERCENT,
            { v -> editor.editFeel { sensitivityPercent = v } }, step = 10,
            caption = stringResource(R.string.sensitivity_caption), format = ::percent,
        )
        if (stick == 1) {
            SliderRow(
                stringResource(R.string.aim_curve), feel.curveTenths, Settings.MIN_CURVE_TENTHS, Settings.MAX_CURVE_TENTHS,
                { v -> editor.editFeel { curveTenths = v } },
                caption = stringResource(R.string.aim_curve_caption), format = { "%.1f".format(it / 10f) },
            )
        }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SettingsCard(stringResource(R.string.curve_title), Modifier.weight(1f)) {
            CurveGraph(
                inner = inner / 100f, outer = s.outerDeadzonePercent[stick] / 100f, antiDeadzone = antiDeadzone / 100f,
                curve = curve / 10f, sensitivity = feel.sensitivityPercent / 100f,
            )
            Caption(stringResource(R.string.curve_caption))
        }
        SettingsCard(stringResource(R.string.test_pad), Modifier.weight(1f)) {
            StickTestPad(
                inner = inner / 100f, outer = s.outerDeadzonePercent[stick] / 100f, shape = s.deadzoneShape[stick],
                antiDeadzone = antiDeadzone / 100f, curve = curve / 10f, sensitivity = feel.sensitivityPercent / 100f,
                invertX = s.invertX[stick], invertY = s.invertY[stick],
            )
            Caption(stringResource(R.string.test_pad_caption))
        }
    }
}

// --- Buttons --------------------------------------------------------------------------------------------------

@Composable
private fun ButtonsSection(editor: SettingsEditor) {
    editor.version
    val s = editor.settings
    SettingsCard(stringResource(R.string.min_press)) {
        SliderRow(
            stringResource(R.string.min_press), s.minPressMs, 0, Settings.MAX_MIN_PRESS_MS, { v -> editor.edit { minPressMs = v } },
            step = 5, caption = stringResource(R.string.min_press_caption), format = ::millis,
        )
    }
    SettingsCard(stringResource(R.string.dpad_title)) {
        SliderRow(
            stringResource(R.string.dpad_deadzone), s.dpadDeadzonePercent, 5, 50, { v -> editor.edit { dpadDeadzonePercent = v } },
            format = ::percent,
        )
        SliderRow(
            stringResource(R.string.dpad_diagonal), s.dpadDiagonalDegrees.toInt(), 10, 40,
            { v -> editor.edit { dpadDiagonalDegrees = if (v == 22) Settings.DEFAULT_DPAD_DIAGONAL else v.toFloat() } },
            caption = stringResource(R.string.dpad_diagonal_caption), format = { "$it °" }, enabled = !s.dpadFourWay,
        )
        SwitchRow(
            stringResource(R.string.dpad_four_way), s.dpadFourWay, { v -> editor.edit { dpadFourWay = v } },
            caption = stringResource(R.string.dpad_four_way_caption),
        )
    }
    SettingsCard(stringResource(R.string.edge_title)) {
        SliderRow(
            stringResource(R.string.edge_on), s.edgeOnPercent, 110, 170,
            { v -> editor.edit { edgeOnPercent = v; edgeOffPercent = minOf(edgeOffPercent, v - 5) } },
            step = 5, format = ::percent,
        )
        SliderRow(
            stringResource(R.string.edge_off), s.edgeOffPercent.coerceAtMost(s.edgeOnPercent - 5), 100, s.edgeOnPercent - 5,
            { v -> editor.edit { edgeOffPercent = v } }, step = 5, caption = stringResource(R.string.edge_caption), format = ::percent,
        )
    }
    SettingsCard(stringResource(R.string.pedal_title)) {
        SliderRow(stringResource(R.string.pedal_min), s.pedalMinPercent, 0, 60, { v -> editor.edit { pedalMinPercent = v } }, step = 5, format = ::percent)
        SliderRow(stringResource(R.string.pedal_gain), s.pedalGainPercent, 80, 200, { v -> editor.edit { pedalGainPercent = v } }, step = 10, format = ::percent)
        SliderRow(
            stringResource(R.string.pedal_curve), s.pedalCurveTenths, 5, 25, { v -> editor.edit { pedalCurveTenths = v } },
            caption = stringResource(R.string.pedal_caption), format = { "%.1f".format(it / 10f) },
        )
    }
}

// --- Motion ---------------------------------------------------------------------------------------------------

@Composable
private fun MotionSection(editor: SettingsEditor, host: SettingsHost) {
    editor.version
    val s = editor.settings
    val mode = editor.mode
    val feel = editor.feel()
    val available = mode.motion != GameMode.MOTION_NONE && host.motionAvailable(mode.motion)
    val gyro = mode.motion == GameMode.MOTION_AIM

    SettingsCard(mode.title) {
        when {
            mode.motion == GameMode.MOTION_NONE -> Caption(stringResource(R.string.settings_motion_none))
            !available -> Caption(stringResource(R.string.settings_motion_unavailable))
            else -> SwitchRow(
                stringResource(if (gyro) R.string.settings_motion_aim else R.string.settings_motion_steer),
                feel.motion, { v -> editor.editFeel { motion = v } },
            )
        }
        if (available) {
            SliderRow(
                stringResource(R.string.motion_sensitivity), s.motionSensitivityPercent,
                Settings.MIN_MOTION_SENSITIVITY_PERCENT, Settings.MAX_MOTION_SENSITIVITY_PERCENT,
                { v -> editor.edit { motionSensitivityPercent = v } }, step = 10, format = ::percent, enabled = feel.motion,
            )
        }
    }
    if (!available || !gyro) return

    SettingsCard(stringResource(R.string.settings_motion_aim)) {
        SwitchRow(stringResource(R.string.settings_gyro_while_aiming), s.gyroWhileAiming, { v -> editor.edit { gyroWhileAiming = v } })
        SliderRow(stringResource(R.string.gyro_sens_x), s.gyroSensitivityXPercent, 50, 200, { v -> editor.edit { gyroSensitivityXPercent = v } }, step = 10, format = ::percent)
        SliderRow(stringResource(R.string.gyro_sens_y), s.gyroSensitivityYPercent, 50, 200, { v -> editor.edit { gyroSensitivityYPercent = v } }, step = 10, format = ::percent)
        SwitchRow(stringResource(R.string.gyro_invert_x), s.gyroInvertX, { v -> editor.edit { gyroInvertX = v } })
        SwitchRow(stringResource(R.string.gyro_invert_y), s.gyroInvertY, { v -> editor.edit { gyroInvertY = v } })
        ChoiceRow(
            stringResource(R.string.gyro_axis),
            listOf(
                GyroFilter.AXIS_PLAYER to stringResource(R.string.axis_player),
                GyroFilter.AXIS_LOCAL to stringResource(R.string.axis_local),
                GyroFilter.AXIS_ROLL to stringResource(R.string.axis_roll),
            ),
            s.gyroAxis, { v -> editor.edit { gyroAxis = v } },
        )
        SliderRow(
            stringResource(R.string.gyro_smoothing), s.gyroSmoothingDegrees, 0, Settings.MAX_GYRO_SMOOTHING_DEGREES,
            { v -> editor.edit { gyroSmoothingDegrees = v } }, caption = stringResource(R.string.gyro_smoothing_caption), format = ::degreesPerSecond,
        )
        SliderRow(
            stringResource(R.string.gyro_tightening), s.gyroTighteningDegrees, 0, 10,
            { v -> editor.edit { gyroTighteningDegrees = v } }, caption = stringResource(R.string.gyro_tightening_caption), format = ::degreesPerSecond,
        )
        SliderRow(
            stringResource(R.string.gyro_accel), s.gyroAccelPercent, 100, 300,
            { v -> editor.edit { gyroAccelPercent = v } }, step = 10, caption = stringResource(R.string.gyro_accel_caption), format = ::percent,
        )
    }

    SettingsCard(stringResource(R.string.gyro_live)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GyroGauge(host::gyroOutput)
            Column(Modifier.weight(1f).padding(start = 16.dp)) {
                SwitchRow(
                    stringResource(R.string.gyro_auto_calibrate), s.gyroAutoCalibrate, { v -> editor.edit { gyroAutoCalibrate = v } },
                    caption = stringResource(R.string.gyro_auto_calibrate_caption),
                )
                Caption(stringResource(R.string.calibrate_caption), Modifier.padding(top = 10.dp))
                FilledTonalButton(
                    onClick = {
                        editor.calibrating = true
                        host.calibrateGyro { editor.calibrating = false }
                    },
                    enabled = !editor.calibrating,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Text(stringResource(if (editor.calibrating) R.string.gyro_calibrating else R.string.calibrate_gyro))
                }
            }
        }
    }
}

// --- Feedback -------------------------------------------------------------------------------------------------

private val HapticEvents = listOf(
    Haptics.EVENT_PRESS to R.string.haptic_press,
    Haptics.EVENT_RIM to R.string.haptic_rim,
    Haptics.EVENT_CONFIRM to R.string.haptic_confirm,
    Haptics.EVENT_REJECT to R.string.haptic_reject,
)

@Composable
private fun FeedbackSection(editor: SettingsEditor, host: SettingsHost) {
    editor.version
    val s = editor.settings
    SettingsCard(stringResource(R.string.settings_haptics)) {
        SwitchRow(stringResource(R.string.settings_haptics), s.haptics, { v -> editor.edit { haptics = v } }, caption = stringResource(R.string.haptics_caption))
        SliderRow(
            stringResource(R.string.strength), s.hapticIntensityPercent, 10, Settings.MAX_HAPTIC_INTENSITY_PERCENT,
            { v -> editor.edit { hapticIntensityPercent = v } }, step = 10, format = ::percent, enabled = s.haptics,
        )
        for ((event, label) in HapticEvents) {
            val bit = 1 shl event
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SwitchRow(
                        stringResource(label), (s.hapticEvents and bit) != 0,
                        { on -> editor.edit { hapticEvents = if (on) hapticEvents or bit else hapticEvents and bit.inv() } },
                        enabled = s.haptics,
                    )
                }
                TextButton(onClick = { host.testHaptic(event) }, enabled = s.haptics, modifier = Modifier.padding(start = 8.dp, top = 10.dp)) {
                    Text(stringResource(R.string.test))
                }
            }
        }
        SwitchRow(
            stringResource(R.string.haptics_during_rumble), s.hapticsDuringRumble, { v -> editor.edit { hapticsDuringRumble = v } },
            caption = stringResource(R.string.haptics_during_rumble_caption), enabled = s.haptics,
        )
    }
    SettingsCard(stringResource(R.string.settings_rumble)) {
        SwitchRow(stringResource(R.string.settings_rumble), s.rumble, { v -> editor.edit { rumble = v } })
        SliderRow(
            stringResource(R.string.strength), s.rumbleGainPercent, 0, Settings.MAX_RUMBLE_GAIN_PERCENT,
            { v -> editor.edit { rumbleGainPercent = v } }, step = 10, format = ::percent, enabled = s.rumble,
        )
        ChoiceRow(
            stringResource(R.string.rumble_mix),
            listOf(
                Rumble.MIX_MAX to stringResource(R.string.mix_max),
                Rumble.MIX_SUM to stringResource(R.string.mix_sum),
                Rumble.MIX_LARGE to stringResource(R.string.mix_large),
                Rumble.MIX_SMALL to stringResource(R.string.mix_small),
            ),
            s.rumbleMix, { v -> editor.edit { rumbleMix = v } }, caption = stringResource(R.string.rumble_caption), enabled = s.rumble,
        )
    }
}

// --- Display and About ----------------------------------------------------------------------------------------

@Composable
private fun DisplaySection(editor: SettingsEditor) {
    editor.version
    SettingsCard(stringResource(R.string.section_display)) {
        SwitchRow(
            stringResource(R.string.settings_dim), editor.settings.dimScreen, { v -> editor.edit { dimScreen = v } },
            caption = stringResource(R.string.dim_caption),
        )
    }
}

@Composable
private fun AboutSection() {
    val context = LocalContext.current
    val version = remember {
        try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
        } catch (_: Exception) {
            "?"
        }
    }
    val name = remember { DeviceIdentity.name(context) }
    val id = remember { "%016X".format(DeviceIdentity.id(context)) }
    SettingsCard(stringResource(R.string.section_about)) {
        Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodyLarge)
        Caption(stringResource(R.string.about_version, version), Modifier.padding(top = 12.dp))
        Caption(stringResource(R.string.about_device, name))
        Caption(stringResource(R.string.about_device_id, id))
        Caption(stringResource(R.string.about_protocol, Protocol.VERSION))
    }
}
