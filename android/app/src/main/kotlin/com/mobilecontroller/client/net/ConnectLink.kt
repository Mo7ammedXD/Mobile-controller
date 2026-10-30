package com.mobilecontroller.client.net

import com.mobilecontroller.client.protocol.Protocol
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder

/**
 * The link the desktop app shows as a QR code: `mobilecontroller://connect?host=192.168.1.20&port=24680&name=Den`.
 * A phone camera opens it with this app, which then asks before connecting. Pure Kotlin, so it is unit-tested on the
 * JVM; everything in the link is untrusted, so anything unexpected makes [parse] return null.
 */
data class ConnectLink(val host: String, val port: Int, val name: String?) {
    companion object {
        const val SCHEME = "mobilecontroller"
        const val HOST = "connect"
        private const val MAX_NAME_CHARS = 64
        private val HOST_NAME = Regex("^[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,62}[A-Za-z0-9])?)*$")

        fun parse(link: String?): ConnectLink? {
            if (link == null) return null
            val uri = try {
                URI(link.trim())
            } catch (_: URISyntaxException) {
                return null
            }
            if (!SCHEME.equals(uri.scheme, ignoreCase = true) || !HOST.equals(uri.host, ignoreCase = true)) return null
            val query = queryOf(uri.rawQuery ?: return null)
            val host = query["host"]?.trim()?.takeIf { isHost(it) } ?: return null
            val port = query["port"]?.let { it.toIntOrNull() ?: return null } ?: Protocol.DEFAULT_PORT
            if (port !in 1..65535) return null
            val name = query["name"]?.trim()?.filter { !it.isISOControl() }?.take(MAX_NAME_CHARS)?.takeIf { it.isNotEmpty() }
            return ConnectLink(host, port, name)
        }

        private fun queryOf(raw: String): Map<String, String> {
            val result = HashMap<String, String>()
            for (pair in raw.split('&')) {
                val eq = pair.indexOf('=')
                if (eq <= 0) continue
                val key = decode(pair.substring(0, eq)) ?: continue
                val value = decode(pair.substring(eq + 1)) ?: continue
                result.putIfAbsent(key, value)
            }
            return result
        }

        // The charset-name overload: decode(String, Charset) is API 33 and would crash below Android 13. The JVM unit
        // tests cannot catch that, since the desktop JDK has both.
        private fun decode(text: String): String? = try {
            URLDecoder.decode(text, "UTF-8")
        } catch (_: IllegalArgumentException) {
            null
        }

        /** A dotted-quad IPv4 address or a host name; never a URL, a path or anything with spaces. */
        private fun isHost(text: String): Boolean {
            if (text.isEmpty() || text.length > 253) return false
            val parts = text.split('.')
            if (parts.size == 4 && parts.all { it.isNotEmpty() && it.length <= 3 && it.all(Char::isDigit) }) {
                return parts.all { it.toInt() <= 255 }
            }
            return HOST_NAME.matches(text) && !text.all { it.isDigit() || it == '.' }
        }
    }
}
