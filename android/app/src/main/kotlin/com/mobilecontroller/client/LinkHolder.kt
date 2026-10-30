package com.mobilecontroller.client

import android.app.Application
import android.net.ConnectivityManager
import androidx.lifecycle.AndroidViewModel
import com.mobilecontroller.client.net.UdpLink

/**
 * Keeps the link alive across activity recreation: a new socket means a new source port, which the PC would treat as
 * a brand-new controller. Cleared (BYE x3, socket closed, vibrator released) only when the activity really finishes.
 */
class LinkHolder(application: Application) : AndroidViewModel(application) {
    val link = UdpLink(
        GamepadState(),
        Rumble(Haptics(application)),
        application.getSystemService(ConnectivityManager::class.java),
    )

    override fun onCleared() {
        link.close()
        link.rumble.haptics.release()
    }
}
