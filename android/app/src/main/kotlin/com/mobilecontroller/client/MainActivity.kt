package com.mobilecontroller.client

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.net.ConnectivityManager
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Toast
import com.mobilecontroller.client.net.Discovery
import com.mobilecontroller.client.net.UdpLink
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import com.mobilecontroller.client.ui.GamepadView
import com.mobilecontroller.client.ui.SettingsDialog

/**
 * The single, immersive, landscape controller screen.
 *
 * Lifecycle contract: resumed -> the link sends (after Android 17's local-network permission is
 * granted); paused -> one neutral INPUT, then silence; finished -> BYE x3 and the socket closes. The
 * link survives configuration-change recreation (onRetainNonConfigurationInstance), because a new
 * socket means a new source port, which the PC would treat as a brand-new controller.
 */
class MainActivity : Activity(), GamepadView.Listener, UdpLink.GameListener {
    private lateinit var settings: Settings
    private lateinit var link: UdpLink
    private lateinit var view: GamepadView
    private lateinit var discovery: Discovery
    private lateinit var motion: MotionInput
    private var wifiLock: WifiManager.WifiLock? = null

    private var connected = false
    private val dim = Runnable { setBrightness(DIM_BRIGHTNESS) }

    // A game reported by the PC (receiver thread) is applied on the UI thread.
    @Volatile private var detectedMode: GameMode? = null
    private val applyDetectedMode = Runnable { onDetectedMode() }

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) = updateRotation()
    }
    private var settingsDialog: SettingsDialog? = null
    private var permissionDialog: AlertDialog? = null
    private var backCallback: Any? = null

    private var resumed = false
    private var permissionRequestPending = false
    private var permissionDenied = false
    private var firstRun = false
    private var modeApplied = false
    private var lastBackNanos = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = Settings(this)
        val connectivity = getSystemService(ConnectivityManager::class.java)
        link = lastNonConfigurationInstance as? UdpLink
            ?: UdpLink(GamepadState(), Rumble(applicationContext), connectivity)
        discovery = Discovery(connectivity)
        motion = MotionInput(applicationContext, link)
        link.gameListener = this
        view = GamepadView(this, link)
        view.listener = this
        setContentView(view)
        setUpWindow()
        wifiLock = createWifiLock()

        // A relaunch from Recents (or after process death) redelivers the original intent; its extras
        // must not overwrite settings the user has changed since.
        val freshLaunch = savedInstanceState == null &&
            (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0
        val extrasGiven = freshLaunch && applyExtras(intent)
        firstRun = !extrasGiven && settings.host.isEmpty()
        applySettings()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val callback = OnBackInvokedCallback { onBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // singleTask: a second launch (e.g. `am start` with new extras) retargets this instance; onResume follows.
        if (applyExtras(intent)) firstRun = false
        applySettings()
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
        updateDim()
        super.onPause()
    }

    override fun onDestroy() {
        if (link.gameListener === this) link.gameListener = null
        view.removeCallbacks(dim)
        discovery.cancel()
        settingsDialog?.dismiss()
        permissionDialog?.dismiss()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            (backCallback as? OnBackInvokedCallback)?.let(onBackInvokedDispatcher::unregisterOnBackInvokedCallback)
        }
        if (!isChangingConfigurations) link.close()
        super.onDestroy()
    }

    override fun onRetainNonConfigurationInstance(): Any = link

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs and the permission prompt bring the bars back on some versions; a closed dialog (even
        // a cancelled one) may also allow dimming again.
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
        view.haptics = settings.haptics
        val router = view.router
        router.deadzone = settings.deadzonePercent / 100f
        router.floating = settings.floatingButtons
        val feel = settings.feel(mode)
        val antiDeadzone = feel.antiDeadzonePercent / 100f
        val sensitivity = feel.sensitivityPercent / 100f
        router.antiDeadzone[0] = antiDeadzone
        router.antiDeadzone[1] = antiDeadzone
        router.sensitivity[0] = sensitivity
        router.sensitivity[1] = sensitivity
        // The aim curve shapes only the right stick: movement and steering stay linear.
        router.curve[0] = 1f
        router.curve[1] = feel.curveTenths / 10f
        motion.mode = if (feel.motion && motion.isAvailable(mode.motion)) mode.motion else GameMode.MOTION_NONE
        motion.aimGate = if (settings.gyroWhileAiming) mode.aimGate else 0
        motion.sensitivity = settings.motionSensitivityPercent / 100f
        motion.antiDeadzone = antiDeadzone
        motion.invertY = settings.gyroInvertY
        if (resumed) motion.restart()
        link.rumble.enabled = settings.rumble
        if (resumed && hasLocalNetworkPermission()) startLink()
        updateDim()
    }

    private fun updateRotation() {
        val d = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) display else null
        @Suppress("DEPRECATION")
        motion.rotation = d?.rotation ?: windowManager.defaultDisplay.rotation
    }

    private fun startLink() = link.start(settings.host, settings.port, settings.rateHz)

    private fun openSettings(discover: Boolean) {
        if (settingsDialog?.isShowing == true) return
        view.releaseAll()
        settingsDialog = SettingsDialog(this, settings, discovery, motion::isAvailable) { editLayout ->
            applySettings()
            if (editLayout) view.startEditing()
        }.also {
            it.show(discover)
            updateDim()
        }
    }

    override fun onMenu() = openSettings(discover = false)

    override fun onLayoutSaved(serialized: String?) = settings.saveLayout(view.layout.mode.id, serialized)

    override fun onConnectionChanged(connected: Boolean) {
        this.connected = connected
        updateDim()
    }

    override fun onEditingChanged(editing: Boolean) = updateDim()

    /**
     * Nobody looks at the phone mid-game, so once connected the screen dims (less battery and heat, and
     * a phone that stays cool keeps its full CPU speed). Anything that needs the eyes restores it.
     */
    private fun updateDim() {
        view.removeCallbacks(dim)
        val playing = settings.dimScreen && connected && resumed && !view.editing && settingsDialog?.isShowing != true
        if (playing) view.postDelayed(dim, DIM_DELAY_MS) else setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
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
        if (!resumed || view.editing || settingsDialog?.isShowing == true) {
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
        requestPermissions(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK), REQUEST_LOCAL_NETWORK)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_LOCAL_NETWORK) return
        permissionRequestPending = false
        val granted = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
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

    /** Back exits only when pressed twice within 2 s, so a stray edge swipe never ends a game session. */
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

    // Lint cannot see that API 33+ registers an OnBackInvokedCallback in onCreate (predictive back then
    // never calls this); below 33 this is the only back hook a framework Activity has.
    @SuppressLint("GestureBackNavigation")
    @Deprecated("Only reached below API 33; newer versions use the OnBackInvokedCallback.")
    override fun onBackPressed() = onBack()

    private companion object {
        const val EXTRA_HOST = "host"
        const val EXTRA_PORT = "port"
        const val EXTRA_RATE = "rate"
        const val EXTRA_MODE = "mode"
        const val REQUEST_LOCAL_NETWORK = 1
        const val DOUBLE_BACK_NANOS = 2_000_000_000L
        const val DIM_DELAY_MS = 4_000L
        const val DIM_BRIGHTNESS = 0.05f
    }
}
