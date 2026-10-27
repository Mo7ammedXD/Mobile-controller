package com.mobilecontroller.client.ui

/**
 * A reusable, fixed-capacity line of text for Canvas.drawText(char[], ...): the status overlay is rebuilt
 * up to 4 times per second, and composing it here instead of with String templates keeps the UI thread
 * allocation-free in steady state. Text beyond the capacity is cut off.
 */
class CharLine(capacity: Int) {
    val chars = CharArray(capacity)
    var length = 0
        private set

    fun clear(): CharLine {
        length = 0
        return this
    }

    fun append(s: String): CharLine {
        val n = minOf(s.length, chars.size - length)
        s.toCharArray(chars, length, 0, n)
        length += n
        return this
    }

    fun append(c: Char): CharLine {
        if (length < chars.size) chars[length++] = c
        return this
    }

    fun append(value: Int): CharLine {
        if (value < 0) {
            append('-')
            // Negate in Long space so Int.MIN_VALUE prints correctly.
            return appendDigits(-value.toLong())
        }
        return appendDigits(value.toLong())
    }

    override fun toString(): String = String(chars, 0, length)

    private fun appendDigits(v: Long): CharLine {
        var divisor = 1L
        while (v / divisor >= 10) divisor *= 10
        while (divisor > 0) {
            append(('0'.code + (v / divisor % 10).toInt()).toChar())
            divisor /= 10
        }
        return this
    }
}
