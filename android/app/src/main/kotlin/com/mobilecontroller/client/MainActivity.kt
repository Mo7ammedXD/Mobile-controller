package com.mobilecontroller.client

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.ViewModelProvider
import com.mobilecontroller.client.net.ConnectLink
import com.mobilecontroller.client.net.Discovery
import com.mobilecontroller.client.net.KnownPcs
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import com.mobilecontroller.client.ui.GamepadView
import com.mobilecontroller.client.ui.settings.ControllerTheme
import com.mobilecontroller.client.ui.settings.LinkStatus
import com.mobilecontroller.client.ui.settings.SettingsEditor
import com.mobilecontroller.client.ui.settings.SettingsHost
import com.mobilecontroller.client.ui.settings.SettingsScreen

/**
 * The single, immersive, landscape controller screen.
 *
 * Lifecycle contract: resumed -> the link sends (after Android 17's local-network permission is
 * granted); paused -> one neutral INPUT, then silence; finished -> BYE x3 and the socket closes. The
 * link lives in a ViewModel ([LinkHolder]) and so survives a recreation, because a new socket means a
 * new source port, which the PC would treat as a brand-new controller.
 *
 * The settings are a Compose screen laid over the controller in the same activity: the activity stays
 * resumed, so the link keeps the PC's pad plugged in (a separate activity would pause it), and the
 * screen's previews use the running sensors and vibrator. Edits apply live and Cancel restores them.
 */
class MainActivity : ComponentActivity(), GamepadView.Listener, UdpLink.GameListener, UdpLink.IdentifyListener, SettingsHost {
    private lateinit var settings: Settings
    private lateinit var link: UdpLink
    private lateinit var root: FrameLayout
    private lateinit var view: GamepadView
    private lateinit var discovery: Discovery
    private lateinit var motion: MotionInput
    private var wifiLock: WifiManager.WifiLock? = null

    private var connected = false
    private val dim = Runnable { setBrightness(DIM_BRIGHTNESS) }

    // §12: the battery level goes to the PC with STATUS; polled while the controller is in front.
    private val batteryTick = object : Runnable {
        override fun run() {
            updateBattery()
            view.postDelayed(this, BATTERY_POLL_MS)
        }
    }

    // A known PC that stopped answering is looked for by name: its address may have changed (a new DHCP lease).
    private var disconnectedSince = 0L
    private var rediscoverAt = 0L
    private var rediscoverBackoff = REDISCOVER_MIN_MS
    private val rediscoverTick = object : Runnable {
        override fun run() {
            maybeRediscover()
            view.postDelayed(this, REDISCOVER_CHECK_MS)
        }
    }

    // A game reported by the PC (receiver thread) is applied on the UI thread.
    @Volatile private var detectedMode: GameMode? = null
    private val applyDetectedMode = Runnable { onDetectedMode() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = updateRotation()
    }
    // The settings screen while it is open: its view over the controller, and the edits it holds.
    private var settingsView: ComposeView? = null
    private var editor: SettingsEditor? = null
    private val settingsOpen get() = settingsView != null

    private var permissionDialog: AlertDialog? = null
    private val permissionRequest = registerForActivityResult(ActivityResultContracts.RequestPermission(), ::onPermissionResult)

    private var resumed = false
    private var permissionRequestPending = false
    private var permissionDenied = false
    private var firstRun = false
    private var modeApplied = false
    private var lastBackNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        link = ViewModelProvider(this)[LinkHolder::class.java].link
        discovery = Discovery(getSystemService(ConnectivityManager::class.java))
        motion = MotionInput(applicationContext, link)
        link.gameListener = this
        link.identifyListener = this
        link.setIdentity(DeviceIdentity.id(this), DeviceIdentity.name(this), DeviceIdentity.appVersion(this))
        view = GamepadView(this, link)
        view.listener = this
        root = FrameLayout(this)
        root.addView(view, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        setContentView(root)
        setUpWindow()
        wifiLock = createWifiLock()

        // A relaunch from Recents (or after process death) redelivers the original intent; its extras
        // must not overwrite settings the user has changed since.
        val freshLaunch = savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        val extrasGiven = freshLaunch && applyExtras(intent)
        val linked = freshLaunch && handleLink(intent)
        firstRun = !extrasGiven && !linked && settings.host.isEmpty()
        applySettings()

        // The settings screen registers its own Back (= Done) on top of this one while it is open.
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = onBack()
            },
        )
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // A new target must not be overwritten by the open screen's Save: its edits are dropped.
        if (settingsOpen && (intent.action == Intent.ACTION_VIEW || intent.hasExtra(EXTRA_HOST) || intent.hasExtra(EXTRA_MODE))) {
            closeSettings(save = false, editLayout = false)
        }
        // singleTask: a second launch (e.g. `am start` with new extras) retargets this instance; onResume follows.
        if (applyExtras(intent)) firstRun = false
        if (handleLink(intent)) firstRun = false
        applySettings()
    }

    /**
     * The desktop app's QR code (`mobilecontroller://connect?host=...`): shows where it points and connects only when
     * the player agrees, since anything can put such a link on a web page. Returns true if [intent] was such a link.
     */
    private fun handleLink(intent: Intent?): Boolean {
        if (intent?.action != Intent.ACTION_VIEW || intent.data == null) return false
        val link = ConnectLink.parse(intent.dataString)
        // Consumed: a relaunch from Recents must not ask again.
        setIntent(Intent(intent).setData(null).setAction(Intent.ACTION_MAIN))
        if (link == null) {
            Toast.makeText(this, R.string.link_invalid, Toast.LENGTH_LONG).show()
            return true
        }
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.link_title)
            .setMessage(getString(R.string.link_message, link.name ?: link.host, link.host, link.port))
            .setPositiveButton(R.string.link_connect) { _, _ -> connectTo(link.host, link.port, link.name.orEmpty()) }
            .setNegativeButton(R.string.cancel, null)
            .show()
        return true
    }

    private fun connectTo(host: String, port: Int, name: String) {
        settings.host = host
        settings.port = port
        settings.serverName = name
        settings.save()
        firstRun = false
        applySettings()
        if (resumed && hasLocalNetworkPermission()) startLink()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        hideSystemBars()
        try {
            wifiLock?.acquire()
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "wifi lock: $e")
        }
        view.startStatusUpdates()
        view.post(batteryTick)
        view.postDelayed(rediscoverTick, REDISCOVER_CHECK_MS)
        getSystemService(DisplayManager::class.java)?.registerDisplayListener(displayListener, null)
        updateRotation()
        motion.restart()
        when {
            hasLocalNetworkPermission() -> onPermissionReady()
            permissionDenied -> showPermissionDialog()
            else -> requestLocalNetworkPermission()
        }
    }

    override fun onPause() {
        resumed = false
        motion.stop()
        getSystemService(DisplayManager::class.java)?.unregisterDisplayListener(displayListener)
        view.releaseAll()
        link.pause()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        view.stopStatusUpdates()
        view.removeCallbacks(batteryTick)
        view.removeCallbacks(rediscoverTick)
        updateDim()
        super.onPause()
    }

    override fun onDestroy() {
        if (link.gameListener === this) link.gameListener = null
        if (link.identifyListener === this) link.identifyListener = null
        view.removeCallbacks(dim)
        discovery.cancel()
        permissionDialog?.dismiss()
        // The link closes with its LinkHolder, when the activity finishes for good.
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the keyboard and the permission prompt bring the bars back on some versions; a closed
        // dialog (even a cancelled one) may also allow dimming again.
        if (hasFocus) {
            hideSystemBars()
            updateDim()
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Settings and test hooks

    /**
     * Test hook / deep configuration: `--es host <ip> --ei port <port> --ei rate <hz> --es mode <id>`.
     * Values are persisted like settings typed by the user. Returns true when a host was given.
     */
    private fun applyExtras(intent: Intent?): Boolean {
        val mode = intent?.getStringExtra(EXTRA_MODE)
        if (mode != null && GameModes.ALL.any { it.id == mode }) {
            settings.modeId = mode
            settings.save()
            Log.i(LOG_TAG, "extras mode=$mode")
        }
        val host = intent?.getStringExtra(EXTRA_HOST)?.trim() ?: return false
        settings.host = host
        val port = intent.getIntExtra(EXTRA_PORT, -1)
        if (port in 1..65535) settings.port = port
        val rate = intent.getIntExtra(EXTRA_RATE, -1)
        if (rate > 0) settings.rateHz = rate.coerceIn(UdpLink.MIN_RATE_HZ, UdpLink.MAX_RATE_HZ)
        settings.save()
        Log.i(LOG_TAG, "extras host=$host port=${settings.port} rate=${settings.rateHz}")
        return true
    }

    private fun applySettings() {
        val mode = GameModes.byId(settings.modeId)
        if (view.layout.mode !== mode || !modeApplied) {
            modeApplied = true
            view.applyMode(mode, settings.layoutFor(mode.id))
            Log.i(LOG_TAG, "mode ${mode.id}")
        }
        view.setMirrored(settings.leftHanded)
        val feel = settings.feel(mode)
        applyFeel(settings, mode, feel)
        motion.mode = motionFor(mode, feel)
        if (resumed) motion.restart()
        link.setProfileName(mode.title)
        link.setOutputPreference(settings.outputPreference)
        link.setStatusFlag(Protocol.STATUS_MOTION_ACTIVE, motion.mode != GameMode.MOTION_NONE)
        if (resumed && hasLocalNetworkPermission()) startLink()
        updateDim()
    }

    /**
     * Everything about how input feels and feedback plays, from [s] (the saved settings, or the settings screen's
     * edits while it is open). Not the layout, link target or PC-side output: those change only on Save.
     */
    private fun applyFeel(s: Settings, mode: GameMode, feel: Settings.ModeFeel) {
        view.haptics = s.haptics
        link.state.minPressNanos = s.minPressMs * NANOS_PER_MS
        val router = view.router
        router.innerDeadzone[0] = s.deadzonePercent / 100f
        router.innerDeadzone[1] = s.deadzoneRightPercent / 100f
        for (i in 0..1) {
            router.outerDeadzone[i] = s.outerDeadzonePercent[i] / 100f
            router.deadzoneShape[i] = s.deadzoneShape[i]
            router.anchor[i] = s.stickAnchor[i]
            router.invertX[i] = s.invertX[i]
            router.invertY[i] = s.invertY[i]
        }
        router.dpadDeadzone = s.dpadDeadzonePercent / 100f
        router.dpadDiagonal = Math.toRadians(s.dpadDiagonalDegrees.toDouble()).toFloat()
        router.dpadFourWay = s.dpadFourWay
        router.edgeOnRadius = s.edgeOnPercent / 100f
        // The release ring must sit inside the start ring, or the edge action would flicker.
        router.edgeOffRadius = minOf(s.edgeOffPercent, s.edgeOnPercent - 5) / 100f
        router.pedalMin = s.pedalMinPercent / 100f
        router.pedalGain = s.pedalGainPercent / 100f
        router.pedalCurve = s.pedalCurveTenths / 10f
        router.floating = s.floatingButtons
        val antiDeadzone = feel.antiDeadzonePercent / 100f
        val sensitivity = feel.sensitivityPercent / 100f
        router.antiDeadzone[0] = feel.antiDeadzoneLeftPercent / 100f
        router.antiDeadzone[1] = antiDeadzone
        router.sensitivity[0] = sensitivity
        router.sensitivity[1] = sensitivity
        // The aim curve shapes only the right stick: movement and steering stay linear.
        router.curve[0] = 1f
        router.curve[1] = feel.curveTenths / 10f
        motion.aimGate = if (s.gyroWhileAiming) mode.aimGate else 0
        motion.sensitivity = s.motionSensitivityPercent / 100f
        motion.antiDeadzone = antiDeadzone
        applyGyro(s, antiDeadzone)
        link.rumble.enabled = s.rumble
        link.rumble.gain = s.rumbleGainPercent / 100f
        link.rumble.mix = s.rumbleMix
        val haptics = link.rumble.haptics
        haptics.configure(s.hapticIntensityPercent / 100f)
        haptics.enabledEvents = s.hapticEvents
        haptics.playDuringRumble = s.hapticsDuringRumble
    }

    private fun motionFor(mode: GameMode, feel: Settings.ModeFeel): Int =
        if (feel.motion && motion.isAvailable(mode.motion)) mode.motion else GameMode.MOTION_NONE

    private fun applyGyro(s: Settings, antiDeadzone: Float) {
        val gyro = motion.gyro
        gyro.sensitivity = s.motionSensitivityPercent / 100f
        gyro.sensitivityX = s.gyroSensitivityXPercent / 100f
        gyro.sensitivityY = s.gyroSensitivityYPercent / 100f
        gyro.invertX = s.gyroInvertX
        gyro.invertY = s.gyroInvertY
        gyro.axisMode = s.gyroAxis
        gyro.antiDeadzone = antiDeadzone
        gyro.smoothThreshold = Math.toRadians(s.gyroSmoothingDegrees.toDouble()).toFloat()
        gyro.tightening = Math.toRadians(s.gyroTighteningDegrees.toDouble()).toFloat()
        gyro.accelGain = s.gyroAccelPercent / 100f
        gyro.autoCalibrate = s.gyroAutoCalibrate
        // The stored bias is this phone's last measurement; automatic calibration refines it from there. Always the
        // saved one: a calibration while the settings screen is open stores it there, not in the screen's edits.
        if (!gyro.calibrating) {
            gyro.biasX = settings.gyroBiasX
            gyro.biasY = settings.gyroBiasY
            gyro.biasZ = settings.gyroBiasZ
        }
    }

    private fun updateRotation() {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
        @Suppress("DEPRECATION")
        motion.rotation = d?.rotation ?: windowManager.defaultDisplay.rotation
    }

    private fun startLink() = link.start(settings.host, settings.port, settings.rateHz)

    // ---------------------------------------------------------------------------------------------
    // The settings screen

    private fun openSettings(discover: Boolean) {
        if (settingsOpen) return
        view.releaseAll()
        // A search still running from rediscovery would retarget behind the screen, and its Save would undo that.
        discovery.cancel()
        val editor = SettingsEditor(this)
        editor.onChange = { previewSettings(editor) }
        this.editor = editor
        val screen = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent { ControllerTheme { SettingsScreen(editor, this@MainActivity, startSearch = discover) } }
        }
        settingsView = screen
        root.addView(screen, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        updateDim()
    }

    /** The screen's edits, applied as they are made: the player feels a deadzone or haptic strength before saving. */
    private fun previewSettings(editor: SettingsEditor) {
        val mode = editor.mode
        val feel = editor.feel()
        applyFeel(editor.settings, mode, feel)
        // Sensors start and stop only when the motion control itself changes, not for every slider step.
        val motionMode = motionFor(mode, feel)
        if (motionMode != motion.mode) {
            motion.mode = motionMode
            if (resumed) motion.restart()
            link.setStatusFlag(Protocol.STATUS_MOTION_ACTIVE, motionMode != GameMode.MOTION_NONE)
        }
    }

    override fun closeSettings(save: Boolean, editLayout: Boolean) {
        val screen = settingsView ?: return
        settingsView = null
        editor = null
        discovery.cancel()
        root.removeView(screen)
        // The screen saved its edits already; load them as stored. Without saving, this restores the previews.
        if (save) settings = Settings(this)
        applySettings()
        if (editLayout) view.startEditing()
        hideSystemBars()
        updateDim()
    }

    override fun linkStatus(): LinkStatus {
        val code = if (view.permissionMissing) 0 else link.status(System.nanoTime())
        val connected = code == UdpLink.STATUS_CONNECTED
        val flags = link.feedbackFlags
        val rtt = link.rttMicros
        return LinkStatus(
            code = code,
            host = link.host,
            port = link.port,
            rttMs = if (connected && rtt >= 0) (rtt + 500) / 1000 else -1,
            player = if (connected) link.playerIndex else -1,
            ds4 = connected && (flags and Protocol.FLAG_TARGET_DS4) != 0,
            dryRun = connected && (flags and Protocol.FLAG_DRY_RUN) != 0,
        )
    }

    override fun findPcs(port: Int, onDone: (List<Discovery.Server>) -> Unit) = discovery.start(port, onDone)

    override fun motionAvailable(motion: Int): Boolean = this.motion.isAvailable(motion)

    /** "Calibrate gyro": the phone should lie still for the next two seconds. */
    override fun calibrateGyro(onDone: () -> Unit) {
        if (!motion.isAvailable(GameMode.MOTION_AIM)) {
            onDone()
            return
        }
        motion.calibrate { x, y, z ->
            settings.saveGyroBias(x, y, z)
            Toast.makeText(this, R.string.gyro_calibrated, Toast.LENGTH_SHORT).show()
            onDone()
        }
    }

    override fun gyroOutput(): Int = motion.preview

    override fun testHaptic(event: Int) = link.rumble.haptics.test(event)

    override fun onMenu() = openSettings(discover = false)

    override fun onLayoutSaved(serialized: String?) = settings.saveLayout(view.layout.mode.id, serialized)

    override fun onConnectionChanged(connected: Boolean) {
        this.connected = connected
        updateDim()
        if (!connected) {
            disconnectedSince = SystemClock.elapsedRealtime()
            return
        }
        rediscoverBackoff = REDISCOVER_MIN_MS
        rediscoverAt = 0L
        val pc = KnownPcs.KnownPc(settings.serverName, settings.host, settings.port)
        settings.knownPcs.remember(pc)
        settings.save()
        // The open screen saves its own copy of the list; it must not drop this PC.
        editor?.settings?.knownPcs?.remember(pc)
        // A typed-in address has no name yet: one quiet search learns it, so the PC can be found again later.
        if (settings.serverName.isEmpty() && !settingsOpen) learnServerName()
    }

    private fun learnServerName() {
        val host = settings.host
        val port = settings.port
        discovery.start(port) { found ->
            val match = found.firstOrNull { it.host == host && it.port == port } ?: return@start
            if (settings.host != host || settings.serverName.isNotEmpty()) return@start
            settings.serverName = match.name
            settings.knownPcs.remember(KnownPcs.KnownPc(match.name, host, port))
            settings.save()
        }
    }

    /** Every few seconds while the PC is lost: search for it by name, backing off from 5 to 30 s. */
    private fun maybeRediscover() {
        val name = settings.serverName
        if (name.isEmpty() || connected || !resumed || settingsOpen || !hasLocalNetworkPermission()) return
        val now = SystemClock.elapsedRealtime()
        if (now - disconnectedSince < REDISCOVER_AFTER_MS || now < rediscoverAt) return
        rediscoverAt = now + rediscoverBackoff
        rediscoverBackoff = minOf(rediscoverBackoff * 2, REDISCOVER_MAX_MS)
        discovery.start(settings.port) { found ->
            val match = found.firstOrNull { it.name == name } ?: return@start
            if (connected || (match.host == settings.host && match.port == settings.port)) return@start
            Log.i(LOG_TAG, "rediscovered $name at ${match.host}:${match.port} (was ${settings.host}:${settings.port})")
            settings.host = match.host
            settings.port = match.port
            settings.save()
            applySettings()
            Toast.makeText(this, getString(R.string.pc_found_again, name, match.host), Toast.LENGTH_SHORT).show()
        }
    }

    override fun onEditingChanged(editing: Boolean) = updateDim()

    /**
     * Nobody looks at the phone mid-game, so once connected the screen dims (less battery and heat, and
     * a phone that stays cool keeps its full CPU speed). Anything that needs the eyes restores it.
     */
    private fun updateDim() {
        view.removeCallbacks(dim)
        val menuOpen = view.editing || settingsOpen
        // The PC shows "in settings" instead of wondering why this controller went neutral.
        link.setStatusFlag(Protocol.STATUS_MENU_OPEN, menuOpen)
        val playing = settings.dimScreen && connected && resumed && !menuOpen
        if (playing) view.postDelayed(dim, DIM_DELAY_MS) else setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun updateBattery() {
        val battery = getSystemService(BatteryManager::class.java) ?: return
        val percent = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        link.setBattery(if (percent in 0..100) percent else Protocol.BATTERY_UNKNOWN, battery.isCharging)
    }

    /** Receiver thread: the PC's user wants to know which phone this is (§13). */
    override fun onIdentify(durationMs: Int) {
        view.post {
            view.identify(durationMs)
            link.rumble.haptics.play(Haptics.EVENT_IDENTIFY)
        }
    }

    private fun setBrightness(value: Float) {
        if (window.attributes.screenBrightness == value) return
        window.attributes = window.attributes.apply { screenBrightness = value }
    }

    // ---------------------------------------------------------------------------------------------
    // Automatic game modes (PROTOCOL §10)

    /** Receiver thread: the PC reported a game that has a mode. */
    override fun onGame(mode: GameMode) {
        detectedMode = mode
        view.post(applyDetectedMode)
    }

    private fun onDetectedMode() {
        val mode = detectedMode ?: return
        if (!settings.autoMode || mode === view.layout.mode) return
        if (!resumed || view.editing || settingsOpen) {
            // Not now: the PC repeats GAME every 2 s, so let the next one try again.
            link.forgetDetectedGame()
            return
        }
        settings.modeId = mode.id
        settings.save()
        applySettings()
        Toast.makeText(this, getString(R.string.mode_detected, mode.title), Toast.LENGTH_SHORT).show()
    }

    // ---------------------------------------------------------------------------------------------
    // Android 17 local network protection

    private fun hasLocalNetworkPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN ||
            checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

    private fun onPermissionReady() {
        view.permissionMissing = false
        permissionDenied = false
        permissionDialog?.dismiss()
        startLink()
        if (firstRun) {
            firstRun = false
            openSettings(discover = true)
        }
    }

    private fun requestLocalNetworkPermission() {
        view.permissionMissing = true
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN || permissionRequestPending) return
        permissionRequestPending = true
        permissionRequest.launch(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    private fun onPermissionResult(granted: Boolean) {
        permissionRequestPending = false
        permissionDenied = !granted
        Log.i(LOG_TAG, "local network permission ${if (granted) "granted" else "denied"}")
        // Usually onResume follows and acts on this; if the prompt did not pause us, act now.
        if (resumed) {
            if (granted) onPermissionReady() else showPermissionDialog()
        }
    }

    private fun showPermissionDialog() {
        view.permissionMissing = true
        if (permissionDialog?.isShowing == true) return
        permissionDialog = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.permission_title)
            .setMessage(R.string.permission_message)
            .setCancelable(false)
            .setPositiveButton(R.string.permission_retry) { _, _ ->
                permissionDenied = false
                requestLocalNetworkPermission()
            }
            .setNegativeButton(R.string.permission_settings) { _, _ ->
                startActivity(
                    Intent(ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            .setNeutralButton(R.string.exit) { _, _ -> finish() }
            .show()
    }

    // ---------------------------------------------------------------------------------------------
    // Window and back

    private fun setUpWindow() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Edge-to-edge is enforced (and this call deprecated) from API 35; it is only needed below.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                @Suppress("DEPRECATION")
                window.setDecorFitsSystemWindows(false)
            }
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes = window.attributes.apply {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
        }
    }

    private fun hideSystemBars() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                it.hide(WindowInsets.Type.systemBars())
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
    }

    /** Wi-Fi power save batches packets and adds tens of milliseconds of jitter; this lock disables it. */
    private fun createWifiLock(): WifiManager.WifiLock? {
        val wifi = applicationContext.getSystemService(WifiManager::class.java) ?: return null
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            WifiManager.WIFI_MODE_FULL_LOW_LATENCY
        } else {
            @Suppress("DEPRECATION")
            WifiManager.WIFI_MODE_FULL_HIGH_PERF
        }
        return try {
            wifi.createWifiLock(mode, "MobileController:input").apply { setReferenceCounted(false) }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "wifi lock unavailable: $e")
            null
        }
    }

    /**
     * Back exits only when pressed twice within 2 s, so a stray edge swipe never ends a game session. (The settings
     * screen handles Back itself while it is open.)
     */
    private fun onBack() {
        val now = System.nanoTime()
        when {
            view.editing -> view.cancelEditing()
            now - lastBackNanos < DOUBLE_BACK_NANOS -> finish()
            else -> {
                lastBackNanos = now
                Toast.makeText(this, R.string.back_again, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RATE = "rate"
        const val EXTRA_MODE = "mode"
        const val DOUBLE_BACK_NANOS = 2_000_000_000L
        const val DIM_DELAY_MS = 4_000L
        const val DIM_BRIGHTNESS = 0.05f
        const val NANOS_PER_MS = 1_000_000L
        const val BATTERY_POLL_MS = 30_000L
        const val REDISCOVER_CHECK_MS = 2_500L
        const val REDISCOVER_AFTER_MS = 5_000L
        const val REDISCOVER_MIN_MS = 5_000L
        const val REDISCOVER_MAX_MS = 30_000L
    }
}
