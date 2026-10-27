package com.mobilecontroller.client

import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ControlLayoutTest {
    /** Phones from 16:9 to 21:9 (with and without cutout insets), tablets, a foldable's inner screen. */
    private val screens = listOf(
        floatArrayOf(1920f, 1080f), floatArrayOf(2400f, 1080f), floatArrayOf(2340f, 1080f), floatArrayOf(3200f, 1440f),
        floatArrayOf(2520f, 1080f), floatArrayOf(2560f, 1600f), floatArrayOf(2048f, 1536f), floatArrayOf(2208f, 1840f),
    )

    @Test
    fun everyModesDefaultsStayInsideTheSafeAreaWithoutOverlapping() {
        for (mode in GameModes.ALL) {
            for (s in screens) {
                for (inset in floatArrayOf(0f, 110f)) {
                    val l = ControlLayout().apply { load(mode); place(inset, 0f, s[0] - inset / 2, s[1]) }
                    val where = "${mode.id} ${s[0]}x${s[1]} inset $inset"
                    for (c in 0 until l.count) {
                        val n = l.name(c)
                        assertTrue("$where $n left", l.x[c] - l.halfW[c] >= l.left - 0.5f)
                        assertTrue("$where $n right", l.x[c] + l.halfW[c] <= l.right + 0.5f)
                        assertTrue("$where $n top", l.y[c] - l.halfH[c] >= l.top - 0.5f)
                        assertTrue("$where $n bottom", l.y[c] + l.halfH[c] <= l.bottom + 0.5f)
                        for (d in c + 1 until l.count) {
                            val boxes = abs(l.x[c] - l.x[d]) < l.halfW[c] + l.halfW[d] &&
                                abs(l.y[c] - l.y[d]) < l.halfH[c] + l.halfH[d]
                            val circles = Math.hypot((l.x[c] - l.x[d]).toDouble(), (l.y[c] - l.y[d]).toDouble()) < l.r[c] + l.r[d]
                            val overlap = if (l.isRound(c) && l.isRound(d)) circles else boxes
                            assertFalse("$where $n overlaps ${l.name(d)}", overlap)
                        }
                    }
                }
            }
        }
    }

    /**
     * Buttons win hit-tests over floating sticks, so a button inside a stick's zone would steal a thumb
     * that meant to grab the stick (quick chat instead of steering). The classic layout is exempt: it
     * keeps the physical pad's L3 and D-pad beside the left stick on purpose.
     */
    @Test
    fun noGameModeButtonSitsInsideAStickZone() {
        for (mode in GameModes.ALL) {
            if (mode === GameModes.CLASSIC) continue
            for (s in screens) {
                for (inset in floatArrayOf(0f, 110f)) {
                    val l = ControlLayout().apply { load(mode); place(inset, 0f, s[0] - inset / 2, s[1]) }
                    for (st in 0 until l.count) {
                        if (!l.isStick(st)) continue
                        val zone = l.r[st] * ControlLayout.STICK_ZONE
                        for (c in 0 until l.count) {
                            if (l.isStick(c)) continue
                            val intrudes = abs(l.x[c] - l.x[st]) < zone + l.halfW[c] &&
                                abs(l.y[c] - l.y[st]) < zone + l.halfH[c]
                            assertFalse("${mode.id} ${s[0]}x${s[1]} inset $inset ${l.name(c)} inside ${l.name(st)}'s zone", intrudes)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun everyModesDefaultsHitTestToThemselves() {
        for (mode in GameModes.ALL) {
            val l = ControlLayout().apply { load(mode); place(0f, 0f, 2400f, 1080f) }
            for (c in 0 until l.count) {
                val n = "${mode.id} ${l.name(c)}"
                if (l.isStick(c)) {
                    assertEquals(n, -1, l.hitButton(l.x[c], l.y[c]))
                    assertEquals(n, c, l.hitStickZone(l.x[c], l.y[c]))
                } else {
                    assertEquals(n, c, l.hitButton(l.x[c], l.y[c]))
                }
                assertEquals(n, c, l.hitAny(l.x[c], l.y[c]))
            }
        }
    }

    @Test
    fun serializeParseRoundTripPerMode() {
        for (mode in GameModes.ALL) {
            val a = ControlLayout().apply { load(mode); place(0f, 0f, 2400f, 1080f) }
            a.moveTo(1, 2000f, 300f)
            a.resize(0, 1.3f)
            assertTrue(a.customized)
            val text = a.serialize()
            val b = ControlLayout().apply { load(mode); place(0f, 0f, 2400f, 1080f) }
            assertTrue(mode.id, b.parse(text))
            assertTrue(b.customized)
            for (c in 0 until a.count) {
                assertEquals(a.x[c], b.x[c], 1f)
                assertEquals(a.y[c], b.y[c], 1f)
                assertEquals(a.r[c], b.r[c], 1f)
            }
            // Positions are fractions of the area: the same layout scales to another screen.
            val tablet = ControlLayout().apply { load(mode); parse(text); place(0f, 0f, 2560f, 1600f) }
            assertEquals(a.fx[1] * 2560f, tablet.x[1], 1f)
        }
    }

    @Test
    fun aLayoutOnlyLoadsIntoTheModeItWasMadeFor() {
        val rocket = ControlLayout().apply { load(GameModes.ROCKET); place(0f, 0f, 2400f, 1080f) }
        rocket.moveTo(0, 500f, 500f)
        val saved = rocket.serialize()
        val racing = ControlLayout().apply { load(GameModes.RACING); place(0f, 0f, 2400f, 1080f) }
        // RACING has a different number of controls: the ROCKET layout must be refused.
        assertFalse(GameModes.ROCKET.controls.size == GameModes.RACING.controls.size)
        assertFalse(racing.parse(saved))
        assertFalse(racing.customized)
    }

    @Test
    fun layoutSavedBeforeGameModesStillLoadsAsClassic() {
        // Format "1": the original 17 controls, in the order the classic mode keeps.
        val legacy = "1;" + List(17) { "0.5000,0.5000,0.1000" }.joinToString(";")
        val classic = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
        assertTrue(classic.parse(legacy))
        assertEquals(1200f, classic.x[classic.indexOf("A")], 1f)
        val rocket = ControlLayout().apply { load(GameModes.ROCKET); place(0f, 0f, 2400f, 1080f) }
        assertFalse(rocket.parse(legacy))
    }

    @Test
    fun parseRejectsGarbageAndKeepsDefaults() {
        val l = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
        val a = l.indexOf("A")
        val x = l.x[a]
        val n = l.count
        for (bad in listOf(null, "", "2", "3;0.1,0.1,0.1", "2;" + "0.5,0.5,0.1;".repeat(n), "2;" + List(n) { "2,0.5,0.1" }.joinToString(";"))) {
            assertFalse(bad.toString(), l.parse(bad))
        }
        assertFalse(l.customized)
        assertEquals(x, l.x[a], 0f)
    }

    @Test
    fun editsAreClampedInsideTheArea() {
        val l = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
        val b = l.indexOf("B")
        l.moveTo(b, 5000f, -100f)
        assertEquals(2400f - l.halfW[b], l.x[b], 0.5f)
        assertEquals(l.halfH[b], l.y[b], 0.5f)
        repeat(50) { l.resize(b, 1.5f) }
        assertEquals(ControlLayout.MAX_SIZE, l.size[b], 1e-6f)
        assertTrue(l.x[b] + l.halfW[b] <= 2400.5f)
        repeat(50) { l.resize(b, 0.5f) }
        assertEquals(ControlLayout.MIN_SIZE, l.size[b], 1e-6f)
        l.resetToDefaults()
        assertFalse(l.customized)
    }

    @Test
    fun describeIsMachineReadable() {
        val l = ControlLayout().apply { place(0f, 0f, 2400f, 1080f) }
        val a = l.indexOf("A")
        val line = l.describe(a, 0, 0)
        val fields = line.removePrefix("geom ").split(' ').associate { it.substringBefore('=') to it.substringAfter('=') }
        assertEquals("A", fields["name"])
        assertEquals("circle", fields["shape"])
        assertEquals(Math.round(l.x[a]), fields["cx"]!!.toInt())
        assertEquals(4, fields["rect"]!!.split(',').size)
        assertTrue(l.describe(l.indexOf("LS"), 0, 0).contains("shape=stick"))
        assertTrue(l.describe(l.indexOf("LT"), 10, 20).contains("shape=rect"))
        val racing = ControlLayout().apply { load(GameModes.RACING); place(0f, 0f, 2400f, 1080f) }
        assertTrue(racing.describe(racing.indexOf("GAS"), 0, 0).startsWith("geom name=GAS shape=rect"))
    }
}
