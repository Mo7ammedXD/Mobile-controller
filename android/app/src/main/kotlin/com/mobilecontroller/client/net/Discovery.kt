package com.mobilecontroller.client.net

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.mobilecontroller.client.LOG_TAG
import com.mobilecontroller.client.protocol.Protocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ThreadLocalRandom

/**
 * "Find PC" (PROTOCOL §8): broadcasts DISCOVER and collects DISCOVER_REPLYs for about a second.
 * Runs on its own short-lived thread and allocates freely; it never touches the input path.
 */
class Discovery(private val connectivity: ConnectivityManager?) {
    class Server(val name: String, val host: String, val port: Int, val padsInUse: Int, val maxPads: Int)

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var generation = 0

    /** Starts a scan; [onDone] runs on the main thread unless [cancel] is called first. */
    fun start(port: Int, onDone: (List<Server>) -> Unit) {
        val gen = ++generation
        Thread({
            val found = try {
                scan(port)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "discovery failed: $e")
                emptyList()
            }
            main.post { if (gen == generation) onDone(found) }
        }, "mc-discovery").apply { isDaemon = true }.start()
    }

    fun cancel() {
        generation++
    }

    private fun scan(port: Int): List<Server> {
        val nonce = ThreadLocalRandom.current().nextInt(0x10000)
        val request = ByteArray(Protocol.DISCOVER_SIZE).also { Protocol.writeDiscover(it, nonce) }
        val sockets = ArrayList<DatagramSocket>()
        val found = LinkedHashMap<String, Server>()
        try {
            // An unbound socket follows the default network and also reaches USB/hotspot tethering
            // subnets; a socket bound to each LAN network covers a Wi-Fi that lost the default route to
            // cellular because it has no internet.
            openSocket(null)?.let(sockets::add)
            for (network in lanNetworks()) openSocket(network)?.let(sockets::add)
            val targets = broadcastTargets()
            Log.i(LOG_TAG, "discovery port=$port sockets=${sockets.size} targets=${targets.joinToString { it.hostAddress.orEmpty() }}")
            val buffer = ByteArray(Protocol.MAX_DATAGRAM + 1)
            val packet = DatagramPacket(buffer, buffer.size)
            val start = System.nanoTime()
            var rounds = 0
            while (true) {
                val elapsed = System.nanoTime() - start
                if (elapsed >= SCAN_NANOS) break
                // Wi-Fi broadcasts are unacknowledged and often lost, so the request goes out twice.
                if (rounds < SEND_ROUNDS && elapsed >= rounds * RESEND_NANOS) {
                    rounds++
                    for (s in sockets) for (t in targets) send(s, request, t, port)
                }
                for (s in sockets) {
                    try {
                        packet.length = buffer.size
                        s.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    } catch (_: IOException) {
                        continue
                    }
                    if (Protocol.validate(buffer, packet.length, serverToClient = true) != Protocol.TYPE_DISCOVER_REPLY) continue
                    if (Protocol.u16(buffer, Protocol.REPLY_NONCE) != nonce) continue
                    val host = packet.address.hostAddress ?: continue
                    val key = "$host:${packet.port}"
                    if (key in found) continue
                    val name = Protocol.replyName(buffer).ifBlank { host }
                    found[key] = Server(
                        name, host, packet.port,
                        Protocol.u8(buffer, Protocol.REPLY_PADS_IN_USE), Protocol.u8(buffer, Protocol.REPLY_MAX_PADS),
                    )
                }
            }
        } finally {
            for (s in sockets) s.close()
        }
        Log.i(LOG_TAG, "discovery found ${found.size}: ${found.keys.joinToString()}")
        return ArrayList(found.values)
    }

    private fun openSocket(network: Network?): DatagramSocket? {
        val s = DatagramSocket(null as SocketAddress?)
        return try {
            s.broadcast = true
            s.soTimeout = POLL_MS
            network?.bindSocket(s)
            s.bind(null)
            s
        } catch (e: IOException) {
            Log.w(LOG_TAG, "discovery socket on ${network ?: "default"} failed: $e")
            s.close()
            null
        }
    }

    private fun send(s: DatagramSocket, request: ByteArray, target: InetAddress, port: Int) {
        try {
            s.send(DatagramPacket(request, request.size, target, port))
        } catch (_: IOException) {
            // No route from this socket's network to that broadcast domain: expected, another socket covers it.
        }
    }

    /** Non-cellular networks with an IPv4 address (Wi-Fi, Ethernet, ...). */
    private fun lanNetworks(): List<Network> {
        val cm = connectivity ?: return emptyList()
        return try {
            @Suppress("DEPRECATION")
            cm.allNetworks.filter { n ->
                val caps = cm.getNetworkCapabilities(n)
                caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
                    cm.getLinkProperties(n)?.linkAddresses?.any { it.address is Inet4Address } == true
            }
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "network lookup failed: $e")
            emptyList()
        }
    }

    /** 255.255.255.255 plus the directed broadcast address of every up IPv4 interface and LAN network. */
    private fun broadcastTargets(): List<InetAddress> {
        val targets = LinkedHashSet<InetAddress>()
        targets += InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))
        try {
            for (nif in NetworkInterface.getNetworkInterfaces() ?: return targets.toList()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (a in nif.interfaceAddresses) {
                    val b = a.broadcast
                    if (b is Inet4Address) targets += b
                }
            }
        } catch (e: IOException) {
            Log.w(LOG_TAG, "interface enumeration failed: $e")
        }
        val cm = connectivity
        if (cm != null) {
            for (n in lanNetworks()) {
                for (la in cm.getLinkProperties(n)?.linkAddresses.orEmpty()) {
                    val a = la.address as? Inet4Address ?: continue
                    Ipv4.broadcast(a.address, la.prefixLength)?.let { targets += InetAddress.getByAddress(it) }
                }
            }
        }
        return targets.toList()
    }

    private companion object {
        const val SCAN_NANOS = 1_000_000_000L
        const val RESEND_NANOS = 300_000_000L
        const val SEND_ROUNDS = 2
        const val POLL_MS = 20
    }
}
