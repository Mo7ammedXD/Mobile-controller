package com.mobilecontroller.client

import android.content.Context
import android.os.Build
import android.provider.Settings.Global
import java.security.SecureRandom

/**
 * Who this phone is for the PC (PROTOCOL §12): a random id per install, which lets the PC remember per-phone
 * settings and keep the same controller when the phone's socket changes, and a readable name.
 *
 * The id lives in its own preferences file, outside the backup rules (only settings.xml is backed up), so a phone
 * restored from another phone's backup gets an id of its own instead of a copy.
 */
object DeviceIdentity {
    private const val FILE = "identity"
    private const val KEY_ID = "deviceId"

    fun id(context: Context): Long {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val stored = prefs.getLong(KEY_ID, 0L)
        if (stored != 0L) return stored
        val random = SecureRandom()
        var id: Long
        do {
            id = random.nextLong()
        } while (id == 0L)
        prefs.edit().putLong(KEY_ID, id).apply()
        return id
    }

    /** The name the user gave the phone (Settings > About phone), else its model. */
    fun name(context: Context): String =
        Global.getString(context.contentResolver, Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

    /** This app's build number, for the PC's diagnostics. */
    fun appVersion(context: Context): Int = try {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            info.longVersionCode.toInt()
        } else {
            @Suppress("DEPRECATION")
            info.versionCode
        }
    } catch (_: Exception) {
        0
    }
}
