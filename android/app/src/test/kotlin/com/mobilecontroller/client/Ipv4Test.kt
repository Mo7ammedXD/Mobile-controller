package com.mobilecontroller.client

import com.mobilecontroller.client.net.Ipv4
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv4Test {
    private fun ip(a: Int, b: Int, c: Int, d: Int) = byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte())

    @Test
    fun samePrefix() {
        assertTrue(Ipv4.samePrefix(ip(192, 168, 1, 37), ip(192, 168, 1, 20), 24))
        assertFalse(Ipv4.samePrefix(ip(192, 168, 1, 37), ip(192, 168, 2, 20), 24))
        assertTrue(Ipv4.samePrefix(ip(10, 0, 2, 15), ip(10, 0, 2, 2), 24))
        assertTrue(Ipv4.samePrefix(ip(172, 20, 10, 2), ip(172, 20, 10, 14), 28))
        assertFalse(Ipv4.samePrefix(ip(172, 20, 10, 2), ip(172, 20, 10, 17), 28))
        assertTrue(Ipv4.samePrefix(ip(1, 2, 3, 4), ip(9, 9, 9, 9), 0))
        assertFalse(Ipv4.samePrefix(ip(1, 2, 3, 4), ip(1, 2, 3, 4), 33))
        assertFalse(Ipv4.samePrefix(ip(1, 2, 3, 4), ByteArray(16), 8))
    }

    @Test
    fun broadcast() {
        assertArrayEquals(ip(192, 168, 1, 255), Ipv4.broadcast(ip(192, 168, 1, 37), 24))
        assertArrayEquals(ip(172, 20, 10, 15), Ipv4.broadcast(ip(172, 20, 10, 2), 28))
        assertArrayEquals(ip(10, 255, 255, 255), Ipv4.broadcast(ip(10, 1, 2, 3), 8))
        assertNull(Ipv4.broadcast(ip(10, 1, 2, 3), 31))
        assertNull(Ipv4.broadcast(ByteArray(16), 64))
    }
}
