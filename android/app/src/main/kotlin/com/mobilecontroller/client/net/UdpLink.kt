package com.mobilecontroller.client.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import com.mobilecontroller.client.GamepadState
import com.mobilecontroller.client.LOG_TAG
import com.mobilecontroller.client.Rumble
import com.mobilecontroller.client.protocol.Protocol
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.PortUnreachableException
import java.net.SocketAddress
import java.net.SocketException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.locks.LockSupport
import kotlin.math.min

/**
 * The UDP link to the PC: one connected socket, a sender thread and a receiver thread.
 *
 * The sender parks until the UI thread unparks it (state changed) or the heartbeat deadline passes,
 * snapshots [state] into one reused 16-byte buffer and sends it through one reused packet on a
 * connected socket (Android then passes a null address to sendto, so nothing is allocated per
 * packet). It outlives pause/resume and activity recreation: keeping the same socket keeps the same
 * source port, which is what keeps the same virtual pad on the PC.
 *
 * Allocation happens only at setup, on reconnect, and in error paths.
 */
class UdpLink(
    val state: GamepadState,
    val rumble: Rumble,
    private val connectivity: ConnectivityManager?,
) {
    /** Told (on the receiver thread) when the PC reports a foreground game that has a game mode. */
    fun interface GameListener {
        fun onGame(mode: GameMode)
    }

    /** Told (on the receiver thread) when the PC's user asks this phone to identify itself (§13). */
    fun interface IdentifyListener {
        fun onIdentify(durationMs: Int)
    }

    @Volatile var gameListener: GameListener? = null
    @Volatile var identifyListener: IdentifyListener? = null

    // §12 STATUS: who this phone is and how it is doing. Written by the UI thread, sent by the sender.
    @Volatile private var deviceId = 0L
    @Volatile private var deviceName = ByteArray(0)
    @Volatile private var profileName = ByteArray(0)
    @Volatile private var appVersion = 0
    @Volatile private var battery = Protocol.BATTERY_UNKNOWN
    @Volatile private var statusFlags = 0
    @Volatile private var outputPreference = Protocol.OUTPUT_NONE
    @Volatile private var statusDirty = false

    /** The current server answered a STATUS: it moves our slot when the socket changes, so BYE is not needed then. */
    @Volatile private var serverKnowsStatus = false

    /** A FEEDBACK with STATUS_ACK arrived on the current socket: the burst of first STATUS packets can stop. */
    @Volatile private var statusAckedOnSocket = false

    private var lastIdentifyToken = -1
    private var lastIdentifyNanos = NEVER

    /** The last detected game's mode, so a repeated GAME (every 2 s) does not re-trigger a switch. */
    @Volatile private var detectedMode: GameMode? = null

    /** The listener could not act on the last detection (e.g. editing): report it again on the next GAME. */
    fun forgetDetectedGame() {
        detectedMode = null
    }
    // Configuration, written by the UI thread.
    @Volatile var host = ""
        private set
    @Volatile var port = Protocol.DEFAULT_PORT
        private set
    @Volatile private var periodNanos = NANOS_PER_SECOND / DEFAULT_RATE_HZ
    @Volatile private var targetGeneration = 0
    @Volatile private var paused = true
    @Volatile private var closed = false
    @Volatile var rateHz = DEFAULT_RATE_HZ
        private set

    private var sender: Thread? = null
    private var receiver: Thread? = null

    // Owned by the sender thread; the receiver only reads the reference.
    @Volatile private var socket: DatagramSocket? = null
    private var boundNetwork: Network? = null
    private var serverAddress: InetAddress? = null

    private val inputBuffer = ByteArray(Protocol.INPUT_SIZE)
    private val inputPacket = DatagramPacket(inputBuffer, inputBuffer.size)
    private val pingBuffer = ByteArray(Protocol.PING_SIZE)
    private val pingPacket = DatagramPacket(pingBuffer, pingBuffer.size)
    private val byeBuffer = ByteArray(Protocol.BYE_SIZE).also(Protocol::writeBye)
    private val byePacket = DatagramPacket(byeBuffer, byeBuffer.size)
    private val statusBuffer = ByteArray(Protocol.MAX_STATUS_SIZE)
    private val statusPacket = DatagramPacket(statusBuffer, statusBuffer.size)
    private var nextPingId = 0
    @Volatile private var lastPingId = 0
    private val pingSentAt = AtomicLongArray(PING_SLOTS)

    // Status, written by the link threads and polled by the UI at most 4 times per second.
    @Volatile var lastFeedbackNanos = NEVER
        private set
    @Volatile var feedbackFlags = 0
        private set
    @Volatile var playerIndex = Protocol.PLAYER_UNKNOWN
        private set
    @Volatile var lastAcceptedSeq = 0
        private set
    /** Smoothed round-trip time in microseconds, or -1 before the first PONG. */
    @Volatile var rttMicros = -1
        private set
    @Volatile private var portUnreachableNanos = NEVER
    @Volatile private var error = 0

    /** Starts the link, or applies a new target / rate and resumes it after [pause]. UI thread. */
    fun start(host: String, port: Int, rateHz: Int) {
        if (closed) return
        if (host != this.host || port != this.port) {
            this.host = host
            this.port = port
            detectedMode = null
            lastFeedbackNanos = NEVER
            portUnreachableNanos = NEVER
            rttMicros = -1
            error = 0
            serverKnowsStatus = false
            targetGeneration++
        }
        setRate(rateHz)
        paused = false
        if (sender == null) {
            val r = Thread(::runReceiver, "mc-receiver").apply { isDaemon = true }
            val s = Thread(::runSender, "mc-sender").apply { isDaemon = true }
            receiver = r
            sender = s
            r.start()
            s.start()
        } else {
            LockSupport.unpark(sender)
            LockSupport.unpark(receiver)
        }
    }

    fun setRate(hz: Int) {
        val rate = hz.coerceIn(MIN_RATE_HZ, MAX_RATE_HZ)
        if (rate != rateHz && socket != null) Log.i(LOG_TAG, "link rate=${rate}Hz")
        rateHz = rate
        periodNanos = NANOS_PER_SECOND / rate
        LockSupport.unpark(sender)
    }

    /** The UI changed [state]: send now instead of at the next heartbeat. Allocation-free. */
    fun wake() = LockSupport.unpark(sender)

    /** §12: this install's id (never 0), the phone's name and the app's build number. UI thread, at start-up. */
    fun setIdentity(deviceId: Long, deviceName: String, appVersion: Int) {
        this.deviceId = deviceId
        this.deviceName = Protocol.encodeName(deviceName)
        this.appVersion = appVersion
        statusChanged()
    }

    /** The active layout's name, shown on the PC. */
    fun setProfileName(name: String) {
        val encoded = Protocol.encodeName(name)
        if (encoded.contentEquals(profileName)) return
        profileName = encoded
        statusChanged()
    }

    fun setBattery(percent: Int, charging: Boolean) {
        val flags = if (charging) statusFlags or Protocol.STATUS_CHARGING else statusFlags and Protocol.STATUS_CHARGING.inv()
        if (percent == battery && flags == statusFlags) return
        battery = percent
        statusFlags = flags
        statusChanged()
    }

    /** [Protocol.STATUS_MENU_OPEN] / [Protocol.STATUS_MOTION_ACTIVE] on or off. */
    fun setStatusFlag(flag: Int, on: Boolean) {
        val flags = if (on) statusFlags or flag else statusFlags and flag.inv()
        if (flags == statusFlags) return
        statusFlags = flags
        statusChanged()
    }

    /** Which pad the phone asks the PC for ([Protocol.OUTPUT_NONE], [Protocol.OUTPUT_XBOX360], [Protocol.OUTPUT_DS4]). */
    fun setOutputPreference(output: Int) {
        if (output == outputPreference) return
        outputPreference = output
        statusChanged()
    }

    private fun statusChanged() {
        statusDirty = true
        LockSupport.unpark(sender)
    }

    /**
     * §3.4 pause: the sender emits one neutral INPUT, then goes silent. The socket stays open so a quick
     * resume keeps the same source port (and therefore the same virtual pad). UI thread: the state is
     * neutralised here, not on the sender, so it can never race with touches after a fast resume.
     */
    fun pause() {
        state.neutralize()
        paused = true
        rumble.stop()
        LockSupport.unpark(sender)
    }

    /** The user is leaving: the sender sends BYE three times and closes the socket. Irreversible. */
    fun close() {
        closed = true
        paused = true
        rumble.stop()
        LockSupport.unpark(sender)
        LockSupport.unpark(receiver)
    }

    fun status(now: Long): Int = when {
        host.isEmpty() -> STATUS_NO_TARGET
        now - lastFeedbackNanos < CONNECTED_NANOS ->
            if ((feedbackFlags and Protocol.FLAG_SERVER_FULL) != 0) STATUS_SERVER_FULL else STATUS_CONNECTED
        error != 0 -> error
        now - portUnreachableNanos < NOT_LISTENING_NANOS -> STATUS_NOT_LISTENING
        else -> STATUS_WAITING
    }

    private fun runSender() {
        try {
            // Same class as the render thread: the input path must not queue behind ordinary app work.
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "sender priority not raised: $e")
        }
        var openedGeneration = -1
        var lastInput = 0L
        var nextPing = 0L
        var nextRouteCheck = 0L
        var retryAt = 0L
        var backoff = MIN_BACKOFF_NANOS
        var neutralSent = true
        var openedAt = 0L
        var nextStatus = Long.MAX_VALUE
        var lastStatus = 0L
        var statusBurst = 0
        while (!closed) {
            if (paused) {
                if (!neutralSent) {
                    neutralSent = true
                    socket?.let {
                        state.snapshot(inputBuffer)
                        transmit(it, inputPacket)
                    }
                }
                LockSupport.park(this)
                continue
            }
            neutralSent = false
            val now = System.nanoTime()
            // §5: never keep rumbling once FEEDBACK stops (a lost "stop" must not leave the phone buzzing).
            // Checked here, at the heartbeat rate, so the receiver can block without a timeout.
            if (rumble.active && now - lastFeedbackNanos > RUMBLE_WATCHDOG_NANOS) rumble.stop()
            var s = socket
            if (s != null && openedGeneration != targetGeneration) {
                closeSocket(s, bye = true)
                s = null
            }
            if (s == null) {
                if (now < retryAt) {
                    LockSupport.parkNanos(this, retryAt - now)
                    continue
                }
                openedGeneration = targetGeneration
                s = openSocket()
                if (s == null) {
                    retryAt = now + backoff
                    backoff = min(backoff * 2, MAX_BACKOFF_NANOS)
                    continue
                }
                lastInput = now - periodNanos
                nextPing = now
                nextRouteCheck = now + ROUTE_CHECK_NANOS
                // §12: STATUS goes out before the first INPUT, so the PC knows who this is before it plugs a pad (or
                // moves this phone's old controller to the new socket), and twice more in case it is lost.
                openedAt = now
                statusBurst = 2
                lastStatus = now
                nextStatus = now + STATUS_BURST_1_NANOS
                sendStatus(s)
            }
            if (now >= nextStatus || (statusDirty && now - lastStatus >= STATUS_MIN_SPACING_NANOS)) {
                sendStatus(s)
                lastStatus = now
                nextStatus = if (statusBurst == 2 && !statusAckedOnSocket) {
                    statusBurst = 1
                    openedAt + STATUS_BURST_2_NANOS
                } else {
                    statusBurst = 0
                    now + STATUS_INTERVAL_NANOS
                }
            }
            // The deadline is a held-back edge of the minimum press (a quick tap's release) falling due.
            if (state.dirty || now >= state.deadlineNanos || now - lastInput >= periodNanos) {
                // §3.4: changes are coalesced to at most one INPUT per 2 ms.
                val wait = lastInput + MIN_SPACING_NANOS - now
                if (wait > 0) {
                    LockSupport.parkNanos(this, wait)
                    continue
                }
                state.snapshot(inputBuffer)
                if (!transmit(s, inputPacket)) {
                    retryAt = now + backoff
                    backoff = min(backoff * 2, MAX_BACKOFF_NANOS)
                    continue
                }
                backoff = MIN_BACKOFF_NANOS
                lastInput = now
            }
            if (now >= nextPing) {
                nextPing = now + PING_INTERVAL_NANOS
                sendPing(s, now)
            }
            if (now >= nextRouteCheck) {
                nextRouteCheck = now + ROUTE_CHECK_NANOS
                // While nothing answers, a better route may have appeared (e.g. Wi-Fi came up after we
                // fell back to the default network). Allocates, but only while disconnected.
                if (now - lastFeedbackNanos >= CONNECTED_NANOS && routeChanged()) {
                    Log.i(LOG_TAG, "route to PC changed, reopening socket")
                    // A server that knows STATUS moves our controller to the new socket; BYE would unplug it first.
                    closeSocket(s, bye = !serverKnowsStatus)
                    continue
                }
            }
            var wakeAt = min(min(lastInput + periodNanos, state.deadlineNanos), min(nextPing, nextRouteCheck))
            wakeAt = min(wakeAt, if (statusDirty) lastStatus + STATUS_MIN_SPACING_NANOS else nextStatus)
            val sleep = wakeAt - System.nanoTime()
            if (sleep > 0 && !state.dirty) LockSupport.parkNanos(this, sleep)
        }
        socket?.let { closeSocket(it, bye = true) }
    }

    /**
     * Blocks in receive() without a timeout: on Android a socket timeout makes every receive go through
     * IoBridge.poll, which allocates a StructPollfd array per call, and every expiry allocates a
     * SocketTimeoutException. Shutdown and reconnect wake it by closing the socket; the rumble watchdog
     * runs on the sender. So this thread is allocation-free in steady state too.
     */
    private fun runReceiver() {
        val buffer = ByteArray(RECEIVE_BUFFER)
        val packet = DatagramPacket(buffer, buffer.size)
        while (!closed) {
            // Keeps receiving while paused (FEEDBACK is then only bookkept, see onDatagram) so stale
            // packets never pile up in the socket buffer and replay old rumble on resume.
            val s = socket
            if (s == null) {
                rumble.stop()
                LockSupport.park(this)
                continue
            }
            try {
                packet.length = buffer.size
                s.receive(packet)
                onDatagram(buffer, packet.length)
            } catch (_: PortUnreachableException) {
                // ICMP port unreachable for an earlier datagram: the PC is up but no server listens.
                portUnreachableNanos = System.nanoTime()
            } catch (_: IOException) {
                // Closed by the sender (reconnect/shutdown) or a transient error the sender will see too.
                if (socket === s && !closed) LockSupport.parkNanos(this, RECEIVE_ERROR_PAUSE_NANOS)
            }
        }
        rumble.stop()
    }

    private fun onDatagram(b: ByteArray, length: Int) {
        when (Protocol.validate(b, length, serverToClient = true)) {
            Protocol.TYPE_FEEDBACK -> {
                playerIndex = Protocol.u8(b, Protocol.FEEDBACK_PLAYER)
                val flags = Protocol.u8(b, Protocol.FEEDBACK_FLAGS)
                feedbackFlags = flags
                if ((flags and Protocol.FLAG_STATUS_ACK) != 0) {
                    serverKnowsStatus = true
                    statusAckedOnSocket = true
                }
                lastAcceptedSeq = Protocol.u16(b, Protocol.FEEDBACK_LAST_SEQ)
                lastFeedbackNanos = System.nanoTime()
                if (paused) {
                    rumble.stop()
                } else {
                    rumble.set(Protocol.u8(b, Protocol.FEEDBACK_LARGE_MOTOR), Protocol.u8(b, Protocol.FEEDBACK_SMALL_MOTOR))
                }
            }
            Protocol.TYPE_PONG -> {
                val id = Protocol.u16(b, Protocol.PONG_ID)
                if (((lastPingId - id) and 0xFFFF) >= PING_SLOTS) return
                val sentAt = pingSentAt.getAndSet(id and (PING_SLOTS - 1), 0L)
                if (sentAt == 0L) return
                val rtt = ((System.nanoTime() - sentAt) / 1000).toInt()
                val smoothed = rttMicros
                rttMicros = if (smoothed < 0) rtt else smoothed + (rtt - smoothed) / 8
            }
            Protocol.TYPE_GAME -> {
                // §10: matched byte-for-byte against the known executables, so this allocates nothing.
                val mode = GameModes.forGame(b, Protocol.GAME_NAME, Protocol.u8(b, Protocol.GAME_NAME_LENGTH))
                if (mode != null && mode !== detectedMode) {
                    detectedMode = mode
                    gameListener?.onGame(mode)
                }
            }
            Protocol.TYPE_COMMAND -> {
                // §13: unknown ops, and known ops with other argument lengths, are ignored.
                if (Protocol.u8(b, Protocol.COMMAND_OP) != Protocol.COMMAND_IDENTIFY || Protocol.u8(b, Protocol.COMMAND_ARG_LENGTH) != 2) return
                val token = Protocol.u8(b, Protocol.COMMAND_ARGS)
                val now = System.nanoTime()
                // The PC sends it three times; act once.
                if (token == lastIdentifyToken && now - lastIdentifyNanos < IDENTIFY_REPEAT_NANOS) return
                lastIdentifyToken = token
                lastIdentifyNanos = now
                val tenths = Protocol.u8(b, Protocol.COMMAND_ARGS + 1)
                identifyListener?.onIdentify(if (tenths == 0) DEFAULT_IDENTIFY_MS else tenths * 100)
            }
        }
    }

    /** Encodes and sends the current STATUS. Sender thread; allocation-free. */
    private fun sendStatus(s: DatagramSocket) {
        val id = deviceId
        statusDirty = false
        if (id == 0L) return
        val rtt = rttMicros
        val tenths = if (rtt < 0) Protocol.RTT_UNKNOWN else min(rtt / 100, Protocol.RTT_UNKNOWN - 1)
        val name = deviceName
        val profile = profileName
        statusPacket.length = Protocol.writeStatus(
            statusBuffer, id, battery, statusFlags, tenths, rateHz, outputPreference, appVersion,
            name, name.size, profile, profile.size,
        )
        transmit(s, statusPacket)
    }

    private fun sendPing(s: DatagramSocket, now: Long) {
        val id = nextPingId
        nextPingId = (id + 1) and 0xFFFF
        pingSentAt.set(id and (PING_SLOTS - 1), now)
        lastPingId = id
        Protocol.writePing(pingBuffer, id, (now / 1000).toInt())
        transmit(s, pingPacket)
    }

    /** Returns false if the socket failed and was closed (the loop then reopens it with backoff). */
    private fun transmit(s: DatagramSocket, p: DatagramPacket): Boolean = try {
        s.send(p)
        true
    } catch (e: IOException) {
        if (e is PortUnreachableException || errno(e) == OsConstants.ECONNREFUSED) {
            // ICMP port unreachable for an earlier datagram (a connected socket reports it on the next
            // send as ECONNREFUSED): the PC is up but nothing listens. The socket itself is fine, so keep
            // it, and with it the source port; the next datagram reaches the server once it starts.
            portUnreachableNanos = System.nanoTime()
            true
        } else {
            fail(s, e)
            false
        }
    } catch (e: RuntimeException) {
        fail(s, e)
        false
    }

    private fun fail(s: DatagramSocket, e: Exception) {
        setError(classify(e), e)
        closeSocket(s, bye = false)
    }

    private fun openSocket(): DatagramSocket? {
        val h = host
        val p = port
        if (h.isEmpty()) return null
        var s: DatagramSocket? = null
        return try {
            val address = InetAddress.getAllByName(h).firstOrNull { it is Inet4Address }
                ?: throw UnknownHostException("$h has no IPv4 address")
            s = DatagramSocket(null as SocketAddress?)
            try {
                // DSCP EF: Wi-Fi WMM maps it to a high-priority access category, which shortens the
                // queueing delay on a busy network. Harmless where it is ignored.
                s.trafficClass = DSCP_EF
            } catch (_: SocketException) {
            }
            // A Wi-Fi without internet can lose the default route to cellular; bind to the network
            // whose subnet contains the PC so LAN packets cannot leave through the wrong interface.
            val network = findNetwork(address)
            network?.bindSocket(s)
            val target = InetSocketAddress(address, p)
            s.connect(target)
            inputPacket.socketAddress = target
            pingPacket.socketAddress = target
            byePacket.socketAddress = target
            statusPacket.socketAddress = target
            statusAckedOnSocket = false
            boundNetwork = network
            serverAddress = address
            state.resetSequence()
            setError(0, null)
            Log.i(
                LOG_TAG,
                "link start target=${address.hostAddress}:$p rate=${rateHz}Hz local=:${s.localPort} " +
                    "network=${network ?: "unbound"}",
            )
            socket = s
            LockSupport.unpark(receiver)
            s
        } catch (e: Exception) {
            s?.close()
            setError(classify(e), e)
            null
        }
    }

    private fun closeSocket(s: DatagramSocket, bye: Boolean) {
        if (socket === s) socket = null
        if (bye) {
            // §7: BYE is unreliable, so it goes out three times.
            repeat(3) {
                try {
                    s.send(byePacket)
                } catch (_: Exception) {
                }
            }
        }
        s.close()
        LockSupport.unpark(receiver)
    }

    private fun routeChanged(): Boolean {
        val address = serverAddress ?: return false
        return findNetwork(address) != boundNetwork
    }

    /** The non-cellular network whose IPv4 subnet contains [address] (Wi-Fi/Ethernet preferred), or null. */
    private fun findNetwork(address: InetAddress): Network? {
        val cm = connectivity ?: return null
        val target = address.address
        return try {
            var best: Network? = null
            var bestScore = 0
            @Suppress("DEPRECATION")
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) continue
                val links = cm.getLinkProperties(n) ?: continue
                val onLink = links.linkAddresses.any {
                    val a = it.address
                    a is Inet4Address && Ipv4.samePrefix(a.address, target, it.prefixLength)
                }
                if (!onLink) continue
                val lan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                val score = if (lan) 2 else 1
                if (score > bestScore) {
                    best = n
                    bestScore = score
                }
            }
            best
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "network lookup failed: $e")
            null
        }
    }

    private fun setError(code: Int, e: Exception?) {
        if (code == error) return
        error = code
        if (e != null) Log.w(LOG_TAG, "link error ${statusName(code)}: $e")
    }

    private fun classify(e: Throwable): Int {
        if (e is UnknownHostException) return STATUS_UNKNOWN_HOST
        val errno = errno(e)
        return if (errno == OsConstants.EPERM || errno == OsConstants.EACCES || e.message?.contains("EPERM") == true) {
            STATUS_BLOCKED
        } else {
            STATUS_NO_ROUTE
        }
    }

    /** The errno behind a libcore socket exception (it wraps an ErrnoException as the cause), or 0. */
    private fun errno(e: Throwable): Int {
        var t: Throwable? = e
        while (t != null) {
            if (t is ErrnoException) return t.errno
            t = t.cause
        }
        return 0
    }

    companion object {
        const val STATUS_WAITING = 1
        const val STATUS_CONNECTED = 2
        const val STATUS_SERVER_FULL = 3
        const val STATUS_NOT_LISTENING = 4
        /** EPERM: Android 17 local network protection (or a firewall/VPN lockdown) blocked the traffic. */
        const val STATUS_BLOCKED = 5
        const val STATUS_NO_ROUTE = 6
        const val STATUS_UNKNOWN_HOST = 7
        const val STATUS_NO_TARGET = 8

        const val DEFAULT_RATE_HZ = 120
        const val MIN_RATE_HZ = 30
        const val MAX_RATE_HZ = 250

        fun statusName(code: Int): String = when (code) {
            STATUS_WAITING -> "WAITING"
            STATUS_CONNECTED -> "CONNECTED"
            STATUS_SERVER_FULL -> "SERVER_FULL"
            STATUS_NOT_LISTENING -> "NOT_LISTENING"
            STATUS_BLOCKED -> "BLOCKED"
            STATUS_NO_ROUTE -> "NO_ROUTE"
            STATUS_UNKNOWN_HOST -> "UNKNOWN_HOST"
            STATUS_NO_TARGET -> "NO_TARGET"
            else -> "NONE"
        }

        private const val NANOS_PER_SECOND = 1_000_000_000L
        private const val NEVER = Long.MIN_VALUE / 4
        private const val MIN_SPACING_NANOS = 2_000_000L
        private const val PING_INTERVAL_NANOS = NANOS_PER_SECOND
        private const val ROUTE_CHECK_NANOS = 2 * NANOS_PER_SECOND
        private const val CONNECTED_NANOS = NANOS_PER_SECOND
        private const val NOT_LISTENING_NANOS = 2 * NANOS_PER_SECOND
        private const val RUMBLE_WATCHDOG_NANOS = 300_000_000L
        private const val MIN_BACKOFF_NANOS = 250_000_000L
        private const val MAX_BACKOFF_NANOS = 2 * NANOS_PER_SECOND
        private const val RECEIVE_ERROR_PAUSE_NANOS = 100_000_000L
        /** Larger than any legal datagram, so an oversized one shows up as invalid instead of truncated-valid. */
        private const val RECEIVE_BUFFER = Protocol.MAX_DATAGRAM + 1
        private const val PING_SLOTS = 16
        private const val DSCP_EF = 0xB8

        // §12 STATUS send policy: on open, at +30 ms and +100 ms until acknowledged, then every second, and on
        // change at most every 100 ms.
        private const val STATUS_BURST_1_NANOS = 30_000_000L
        private const val STATUS_BURST_2_NANOS = 100_000_000L
        private const val STATUS_INTERVAL_NANOS = NANOS_PER_SECOND
        private const val STATUS_MIN_SPACING_NANOS = 100_000_000L

        // §13 IDENTIFY.
        private const val IDENTIFY_REPEAT_NANOS = 5 * NANOS_PER_SECOND
        private const val DEFAULT_IDENTIFY_MS = 3000
    }
}
