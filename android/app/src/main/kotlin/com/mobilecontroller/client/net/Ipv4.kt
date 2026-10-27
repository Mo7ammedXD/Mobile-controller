package com.mobilecontroller.client.net

/** Address arithmetic on raw (network-order) address bytes. Pure Kotlin so it is JVM-testable. */
object Ipv4 {
    /** True when [a] and [b] share their first [prefixLength] bits (i.e. [b] is on [a]'s subnet). */
    fun samePrefix(a: ByteArray, b: ByteArray, prefixLength: Int): Boolean {
        if (a.size != b.size || prefixLength < 0 || prefixLength > a.size * 8) return false
        var bits = prefixLength
        var i = 0
        while (bits > 0) {
            val mask = if (bits >= 8) 0xFF else (0xFF shl (8 - bits)) and 0xFF
            if ((a[i].toInt() and mask) != (b[i].toInt() and mask)) return false
            bits -= 8
            i++
        }
        return true
    }

    /** Directed broadcast address of [address]/[prefixLength], or null for a non-IPv4 or /31-/32 prefix. */
    fun broadcast(address: ByteArray, prefixLength: Int): ByteArray? {
        if (address.size != 4 || prefixLength !in 1..30) return null
        val host = -1 ushr prefixLength
        val ip = ((address[0].toInt() and 0xFF) shl 24) or ((address[1].toInt() and 0xFF) shl 16) or
            ((address[2].toInt() and 0xFF) shl 8) or (address[3].toInt() and 0xFF)
        val b = ip or host
        return byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())
    }
}
