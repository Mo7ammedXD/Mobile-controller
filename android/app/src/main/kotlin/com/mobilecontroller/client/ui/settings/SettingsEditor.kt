package com.mobilecontroller.client.ui.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.mobilecontroller.client.Settings
import com.mobilecontroller.client.net.Discovery
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes

/** What the settings screen needs from the running controller. Implemented by MainActivity. */
interface SettingsHost {
    /** The link right now, for the Connection section (polled while it is visible). */
    fun linkStatus(): LinkStatus

    /** Starts a PC search on [port]; [onDone] runs on the main thread. */
    fun findPcs(port: Int, onDone: (List<Discovery.Server>) -> Unit)

    fun motionAvailable(motion: Int): Boolean

    /** Measures the gyro's resting offset (2 s); [onDone] runs on the main thread. */
    fun calibrateGyro(onDone: () -> Unit)

    /** The gyro's current right-stick offset (StickMath-packed), for the live gauge. */
    fun gyroOutput(): Int

    /** Plays a haptic event (Haptics.EVENT_*) as currently edited, even if that event is switched off. */
    fun testHaptic(event: Int)

    /** Closes the settings: [save] keeps the edits, [editLayout] then starts the layout editor. */
    fun closeSettings(save: Boolean, editLayout: Boolean)
}

/** The link's state as the Connection section shows it. */
data class LinkStatus(
    /** A UdpLink.STATUS_* code, or 0 while the local-network permission is missing. */
    val code: Int,
    val host: String,
    val port: Int,
    val rttMs: Int,
    val player: Int,
    val ds4: Boolean,
    val dryRun: Boolean,
)

/**
 * The settings being edited: a copy loaded from storage, so Cancel only drops it and Save writes it back. Every change
 * goes through [edit] / [editFeel], which bump [version]: the screen's sections read it, so they redraw.
 */
class SettingsEditor(context: Context) {
    val settings = Settings(context)

    /** Per-mode feel being edited (the stick shaping and motion switch differ per game mode). */
    private val feels = HashMap<String, Settings.ModeFeel>()

    var version by mutableIntStateOf(0)
        private set

    /** Runs after every change, so the controller can apply the edits live; Cancel restores the saved ones. */
    var onChange: (() -> Unit)? = null

    // Connection: typed text is kept as typed until Save validates it.
    var hostText by mutableStateOf(settings.host)
    var portText by mutableStateOf(settings.port.toString())
    var hostError by mutableStateOf(false)
    var portError by mutableStateOf(false)
    var searching by mutableStateOf(false)
    var found by mutableStateOf<List<Discovery.Server>?>(null)
    var calibrating by mutableStateOf(false)

    /** The PC last picked from a search or the recent list: Save remembers its name, to find it again later. */
    private var picked: KnownPcs.KnownPc? = null

    val mode: GameMode get() = GameModes.byId(settings.modeId)

    fun edit(change: Settings.() -> Unit) {
        settings.change()
        version++
        onChange?.invoke()
    }

    fun feel(mode: GameMode = this.mode): Settings.ModeFeel = feels.getOrPut(mode.id) { settings.feel(mode) }

    fun editFeel(change: Settings.ModeFeel.() -> Unit) {
        feel().change()
        version++
        onChange?.invoke()
    }

    fun pick(pc: KnownPcs.KnownPc) {
        picked = pc
        hostText = pc.host
        portText = pc.port.toString()
        hostError = false
        portError = false
    }

    /** Validates and stores the edits. Returns false (and flags the field) when the address is unusable. */
    fun save(): Boolean {
        val host = hostText.trim()
        val port = portText.trim().toIntOrNull()?.takeIf { it in 1..65535 }
        hostError = host.any { it.isWhitespace() || it == '/' }
        portError = port == null
        if (hostError || port == null) return false
        // The PC's name follows a pick; an address typed by hand has none until the phone connects and learns it.
        val pc = picked
        if (pc != null && pc.host == host && pc.port == port) {
            settings.serverName = pc.name
        } else if (host != settings.host || port != settings.port) {
            settings.serverName = ""
        }
        settings.host = host
        settings.port = port
        for ((id, feel) in feels) settings.saveFeel(GameModes.byId(id), feel)
        settings.save()
        return true
    }
}
