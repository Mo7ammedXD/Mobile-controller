package com.mobilecontroller.client

import com.mobilecontroller.client.ui.CharLine
import org.junit.Assert.assertEquals
import org.junit.Test

class CharLineTest {
    @Test
    fun composesTextAndNumbers() {
        val line = CharLine(64)
        line.append("Connected").append(" · ").append('P').append(1).append(" · ").append(0).append(" ms")
        assertEquals("Connected · P1 · 0 ms", line.toString())
        line.clear().append("Waiting for PC ").append("192.168.1.20").append(':').append(24680)
        assertEquals("Waiting for PC 192.168.1.20:24680", line.toString())
        line.clear().append(-42).append(' ').append(Int.MIN_VALUE).append(' ').append(Int.MAX_VALUE)
        assertEquals("-42 -2147483648 2147483647", line.toString())
    }

    @Test
    fun truncatesAtCapacity() {
        val line = CharLine(5)
        line.append("abc").append(12345).append('x')
        assertEquals("abc12", line.toString())
        assertEquals(5, line.length)
    }
}
