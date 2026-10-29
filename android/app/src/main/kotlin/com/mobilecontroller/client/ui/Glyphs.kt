package com.mobilecontroller.client.ui

/**
 * PlayStation names for the Xbox inputs the controls send: when the PC presents this phone as a DualShock 4
 * (FEEDBACK `TARGET_DS4`), games show PlayStation prompts, so the phone should too. Only the input text changes
 * ("A" -> "✕", "RB+X" -> "R1+□"); action labels such as "JUMP" stay. Called when a mode is applied or the pad kind
 * changes, never per frame.
 */
object Glyphs {
    private val PLAYSTATION = mapOf(
        "A" to "✕", "B" to "○", "X" to "□", "Y" to "△",
        "LB" to "L1", "RB" to "R1", "LT" to "L2", "RT" to "R2",
        "BACK" to "SHARE", "START" to "OPTIONS", "GUIDE" to "PS",
        "VIEW" to "SHARE", "MENU" to "OPTIONS", "L3" to "L3", "R3" to "R3",
    )
    private val TOKEN = Regex("[A-Za-z0-9]+")

    // GameModes' Xbox face colours -> the PlayStation symbol colours (cross blue, circle red, square pink, triangle green).
    private val COLORS = mapOf(
        0xFF5DC21E.toInt() to 0xFF7CB2E8.toInt(),
        0xFFE5383B.toInt() to 0xFFFF6B6B.toInt(),
        0xFF2D8CFF.toInt() to 0xFFE79AC8.toInt(),
        0xFFF5C518.toInt() to 0xFF40D9A8.toInt(),
    )

    /**
     * [text] with its Xbox input names replaced, combos included ("RB+X" -> "R1+□"), and a hint after " · " kept
     * ("X · air roll" -> "□ · air roll"). Only an input part made entirely of input names is translated: an action
     * label ("JUMP", "RB+JUMP") or a move name ("LK") is returned as is.
     */
    fun playStation(text: String): String {
        val hint = text.indexOf(HINT)
        val input = if (hint < 0) text else text.substring(0, hint)
        val tokens = TOKEN.findAll(input).toList()
        if (tokens.isEmpty() || tokens.any { it.value !in PLAYSTATION }) return text
        val translated = TOKEN.replace(input) { PLAYSTATION.getValue(it.value) }
        return if (hint < 0) translated else translated + text.substring(hint)
    }

    private const val HINT = " · "

    /** The PlayStation colour of a face button drawn in its Xbox colour; other colours are unchanged. */
    fun playStationColor(color: Int): Int = COLORS[color] ?: color
}
