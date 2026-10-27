package com.mobilecontroller.client

import com.mobilecontroller.client.input.TouchRouter
import com.mobilecontroller.client.ui.ControlLayout
import com.mobilecontroller.client.ui.ControlSpec
import com.mobilecontroller.client.ui.GameMode
import com.mobilecontroller.client.ui.GameModes
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.min

/**
 * Renders every game mode's default layout to PNG, with the two thumbs' comfortable reach drawn
 * underneath, for reviewing ergonomics and for docs/GAME_MODES.md. Only runs when the environment
 * variable LAYOUT_PREVIEW_DIR names an output folder:
 *
 *     LAYOUT_PREVIEW_DIR=out gradlew testDebugUnitTest --tests '*LayoutPreviewTest' --rerun
 */
class LayoutPreviewTest {
    @Test
    fun renderPreviews() {
        val dir = System.getenv("LAYOUT_PREVIEW_DIR")
        assumeTrue("set LAYOUT_PREVIEW_DIR to render", !dir.isNullOrBlank())
        System.setProperty("java.awt.headless", "true")
        File(dir!!).mkdirs()
        for (mode in GameModes.ALL) {
            render(mode, 2400, 1080, File(dir, "${mode.id}.png"))
        }
        render(GameModes.FOOTBALL, 2560, 1600, File(dir, "football-tablet.png"))
    }

    private fun render(mode: GameMode, w: Int, h: Int, out: File) {
        val l = ControlLayout().apply { load(mode); place(w * 0.015f, h * 0.015f, w * 0.985f, h - h * 0.015f) }
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color(0x0D1117)
        g.fillRect(0, 0, w, h)
        drawThumbZones(g, l)
        for (c in 0 until l.count) drawControl(g, l, c)
        // Title at the bottom centre, where no mode puts a control.
        val title = mode.title
        g.font = Font(Font.SANS_SERIF, Font.BOLD, (l.unit * 0.035f).toInt())
        g.color = Color(0x6A7888)
        g.drawString(title, (l.left + l.right) / 2 - g.fontMetrics.stringWidth(title) / 2f, l.bottom - l.unit * 0.02f)
        g.dispose()
        ImageIO.write(img, "png", out)
    }

    /** Comfortable thumb arcs: a band 0.22u-0.62u from each bottom corner, where thumbs rest and roll. */
    private fun drawThumbZones(g: Graphics2D, l: ControlLayout) {
        val u = min(l.height, l.width / 2.1f)
        g.color = Color(0x1A, 0x3D, 0x2A)
        for (cx in floatArrayOf(l.left, l.right)) {
            val outer = 0.62f * u
            val inner = 0.22f * u
            val area = java.awt.geom.Area(Ellipse2D.Float(cx - outer, l.bottom - outer, 2 * outer, 2 * outer))
            area.subtract(java.awt.geom.Area(Ellipse2D.Float(cx - inner, l.bottom - inner, 2 * inner, 2 * inner)))
            g.fill(area)
        }
    }

    private fun drawControl(g: Graphics2D, l: ControlLayout, c: Int) {
        val s = l.spec(c)
        val x = l.x[c]
        val y = l.y[c]
        val hw = l.halfW[c]
        val hh = l.halfH[c]
        val color = Color(s.color and 0xFFFFFF)
        val faint = Color(color.red, color.green, color.blue, 0x38)
        when (s.kind) {
            ControlSpec.STICK -> {
                val r = l.r[c]
                g.color = Color(0x3B4757)
                g.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(8f, 8f), 0f)
                val zone = r * ControlLayout.STICK_ZONE
                g.draw(RoundRectangle2D.Float(x - zone, y - zone, 2 * zone, 2 * zone, zone * 0.4f, zone * 0.4f))
                if (s.mask != 0) {
                    val ring = r * TouchRouter.EDGE_ON
                    g.color = Color(0xFF8A3D)
                    g.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, floatArrayOf(14f, 10f), 0f)
                    g.draw(Ellipse2D.Float(x - ring, y - ring, 2 * ring, 2 * ring))
                    label(g, s.label, x, y - ring - r * 0.1f, r * 0.17f, Color(0xFF8A3D))
                }
                g.stroke = BasicStroke(r * 0.04f)
                g.color = Color(0x1C232D)
                g.fill(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
                g.color = Color(0x3B4757)
                g.draw(Ellipse2D.Float(x - r, y - r, 2 * r, 2 * r))
                g.color = Color(0x4A5868)
                g.fill(Ellipse2D.Float(x - r * 0.45f, y - r * 0.45f, r * 0.9f, r * 0.9f))
                label(g, s.name, x, y + r * 0.7f, r * 0.16f, Color(0x8090A0))
            }
            ControlSpec.DPAD -> {
                val r = l.r[c]
                val a = r * 0.34f
                g.color = Color(0x1C232D)
                g.fill(RoundRectangle2D.Float(x - a, y - r, 2 * a, 2 * r, a * 0.7f, a * 0.7f))
                g.fill(RoundRectangle2D.Float(x - r, y - a, 2 * r, 2 * a, a * 0.7f, a * 0.7f))
                g.color = Color(0x3B4757)
                g.stroke = BasicStroke(r * 0.025f)
                g.draw(RoundRectangle2D.Float(x - a, y - r, 2 * a, 2 * r, a * 0.7f, a * 0.7f))
                g.draw(RoundRectangle2D.Float(x - r, y - a, 2 * r, 2 * a, a * 0.7f, a * 0.7f))
            }
            ControlSpec.MENU -> {
                g.color = Color(0x1C232D)
                g.fill(Ellipse2D.Float(x - hw, y - hh, 2 * hw, 2 * hh))
                label(g, "⚙", x, y, hh * 1.2f, Color(0xB8C4D0))
            }
            else -> {
                val round = s.aspectW == 1f && s.aspectH == 1f
                val arc = if (s.kind == ControlSpec.PEDAL) hw else 2 * min(hw, hh)
                val shape = if (round) Ellipse2D.Float(x - hw, y - hh, 2 * hw, 2 * hh) else RoundRectangle2D.Float(x - hw, y - hh, 2 * hw, 2 * hh, arc, arc)
                g.color = faint
                g.fill(shape)
                g.color = color
                g.stroke = BasicStroke(maxOf(min(hw, hh) * 0.07f, 2f))
                g.draw(shape)
                val m = min(hw, hh)
                val ly = if (s.kind == ControlSpec.PEDAL) y - hh * 0.6f else y
                val box = if (s.kind == ControlSpec.PEDAL) hw else m
                val size = fit(g, s.label, if (s.label.length <= 2) box * 0.9f else box * 0.55f, hw * 1.5f)
                label(g, s.label, x, if (s.sub.isEmpty()) ly else ly - box * 0.14f, size, color)
                if (s.sub.isNotEmpty()) label(g, s.sub, x, ly + box * 0.5f, fit(g, s.sub, box * 0.3f, hw * 1.5f), Color(color.red, color.green, color.blue, 0xB0))
            }
        }
    }

    private fun fit(g: Graphics2D, s: String, size: Float, maxWidth: Float): Float {
        if (s.isEmpty()) return size
        val w = g.getFontMetrics(Font(Font.SANS_SERIF, Font.BOLD, 100)).stringWidth(s)
        return min(size, maxWidth * 100f / w)
    }

    private fun label(g: Graphics2D, s: String, x: Float, y: Float, size: Float, color: Color) {
        if (s.isEmpty() || size < 1f) return
        g.font = Font(Font.SANS_SERIF, Font.BOLD, size.toInt().coerceAtLeast(1))
        val fm = g.fontMetrics
        g.color = color
        g.drawString(s, x - fm.stringWidth(s) / 2f, y + (fm.ascent - fm.descent) / 2f)
    }
}
