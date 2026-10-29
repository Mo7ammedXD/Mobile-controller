package com.mobilecontroller.client

import com.mobilecontroller.client.ui.GameModes
import com.mobilecontroller.client.ui.Glyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** PlayStation names on the controls while the PC presents the phone as a DualShock 4. */
class GlyphsTest {
    @Test
    fun inputNamesAndCombosAreTranslated() {
        assertEquals("✕", Glyphs.playStation("A"))
        assertEquals("R1+□", Glyphs.playStation("RB+X"))
        assertEquals("L1 / R1", Glyphs.playStation("LB / RB"))
        assertEquals("OPTIONS", Glyphs.playStation("START"))
        assertEquals("L3", Glyphs.playStation("L3"))
    }

    @Test
    fun aHintAfterTheInputIsKept() {
        assertEquals("□ · air roll", Glyphs.playStation("X · air roll"))
        assertEquals("○ · cross", Glyphs.playStation("B · cross"))
    }

    @Test
    fun actionLabelsAndMoveNamesStayAsTheyAre() {
        assertEquals("JUMP", Glyphs.playStation("JUMP"))
        assertEquals("RB+JUMP", Glyphs.playStation("RB+JUMP"))
        assertEquals("J+B", Glyphs.playStation("J+B"))
        assertEquals("LK", Glyphs.playStation("LK"))
        assertEquals("", Glyphs.playStation(""))
    }

    @Test
    fun everyXboxInputShownOnAControlHasAPlayStationName() {
        // Each control names the Xbox input it sends, in its big label (Classic, Arcade) or its small text.
        val xbox = setOf("A", "B", "X", "Y", "LB", "RB", "LT", "RT", "BACK", "START", "GUIDE")
        for (mode in GameModes.ALL) {
            for (spec in mode.controls) {
                for (text in listOf(spec.label, spec.sub)) {
                    val input = text.substringBefore(" · ")
                    val tokens = input.split('+', '/').map { it.trim() }
                    if (input.isEmpty() || !tokens.all { it in xbox }) continue
                    assertNotEquals("${mode.id}/${spec.name}: $text", text, Glyphs.playStation(text))
                }
            }
        }
    }

    @Test
    fun faceButtonsTakeThePlayStationColours() {
        val green = GameModes.CLASSIC.controls.first { it.name == "A" }.color
        assertNotEquals(green, Glyphs.playStationColor(green))
        assertEquals(0xFF123456.toInt(), Glyphs.playStationColor(0xFF123456.toInt()))
    }
}
