package com.mobilecontroller.client.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.mobilecontroller.client.input.StickMath
import kotlin.math.min
import kotlin.math.sqrt

/** How the stick's output (up) follows the thumb's travel (across), with the deadzones and shaping applied. */
@Composable
fun CurveGraph(inner: Float, outer: Float, antiDeadzone: Float, curve: Float, sensitivity: Float, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outline
    Canvas(modifier.fillMaxWidth().height(140.dp).padding(vertical = 8.dp)) {
        val w = size.width
        val h = size.height
        drawLine(grid, Offset(0f, h), Offset(w, h), 1.5f)
        drawLine(grid, Offset(0f, 0f), Offset(0f, h), 1.5f)
        drawLine(grid, Offset(0f, h), Offset(w, 0f), 1f)
        val path = Path()
        val steps = 100
        for (i in 0..steps) {
            val travel = i / steps.toFloat()
            // Same as StickMath.stick along one direction.
            val out = if (travel <= inner) 0f else StickMath.shape(min(1f, (travel - inner) / (1f - inner - outer) * sensitivity), antiDeadzone, curve)
            val x = travel * w
            val y = h - out * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 4f))
    }
}

/**
 * A stick to try the settings on: drag in the circle, and the numbers are exactly what the game would receive
 * (StickMath with the edited deadzones and shaping).
 */
@Composable
fun StickTestPad(
    inner: Float, outer: Float, shape: Int, antiDeadzone: Float, curve: Float, sensitivity: Float, invertX: Boolean, invertY: Boolean,
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var packed by remember { mutableIntStateOf(0) }
    val ring = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.primary
    Column {
        Canvas(
            Modifier.size(170.dp).aspectRatio(1f).pointerInput(inner, outer, shape, antiDeadzone, curve, sensitivity, invertX, invertY) {
                val radius = size.width * 0.4f
                val centre = Offset(size.width / 2f, size.height / 2f)
                fun update(position: Offset) {
                    val d = position - centre
                    val length = sqrt(d.x * d.x + d.y * d.y)
                    knob = if (length > radius) d * (radius / length) else d
                    var p = StickMath.stick(d.x, d.y, radius, inner, outer, shape, antiDeadzone, curve, sensitivity)
                    var x = StickMath.unpackX(p)
                    var y = StickMath.unpackY(p)
                    if (invertX) x = -x
                    if (invertY) y = -y
                    p = StickMath.pack(x, y)
                    packed = p
                }
                detectDragGestures(
                    onDragStart = { update(it) },
                    onDragEnd = {
                        knob = Offset.Zero
                        packed = 0
                    },
                    onDrag = { change, _ -> update(change.position) },
                )
            },
        ) {
            val radius = size.width * 0.4f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(ring, radius, centre, style = Stroke(width = 3f))
            if (inner > 0f) drawCircle(ring, radius * inner, centre, style = Stroke(width = 2f))
            if (outer > 0f) drawCircle(ring, radius * (1f - outer), centre, style = Stroke(width = 2f))
            drawCircle(accent, radius * 0.3f, centre + knob)
        }
        Text(
            "X ${StickMath.unpackX(packed)}   Y ${StickMath.unpackY(packed)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** What the gyro sends right now: a dot inside the right stick's circle (refreshed every frame while visible). */
@Composable
fun GyroGauge(read: () -> Int) {
    var packed by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { packed = read() }
    }
    val ring = MaterialTheme.colorScheme.outline
    val accent = MaterialTheme.colorScheme.secondary
    Canvas(Modifier.size(120.dp)) {
        val radius = size.minDimension * 0.45f
        val centre = Offset(size.width / 2f, size.height / 2f)
        drawCircle(ring, radius, centre, style = Stroke(width = 3f))
        drawLine(ring, Offset(centre.x - radius, centre.y), Offset(centre.x + radius, centre.y), 1f)
        drawLine(ring, Offset(centre.x, centre.y - radius), Offset(centre.x, centre.y + radius), 1f)
        // +Y is up on the wire, down on screen.
        val x = StickMath.unpackX(packed) / StickMath.AXIS_MAX.toFloat()
        val y = StickMath.unpackY(packed) / StickMath.AXIS_MAX.toFloat()
        drawCircle(accent, radius * 0.15f, Offset(centre.x + x * radius, centre.y - y * radius))
    }
}
