package com.mobilecontroller.client.ui.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** The controller surface's palette (GamepadView), so settings and controller look like one app. */
private val Colors = darkColorScheme(
    primary = Color(0xFF4FA3FF),
    onPrimary = Color(0xFF00182E),
    secondary = Color(0xFF6FD08C),
    background = Color(0xFF0D1117),
    onBackground = Color(0xFFDCE6F0),
    surface = Color(0xFF0D1117),
    onSurface = Color(0xFFDCE6F0),
    surfaceVariant = Color(0xFF1C232D),
    onSurfaceVariant = Color(0xFFB8C4D0),
    surfaceContainer = Color(0xFF161B22),
    surfaceContainerHigh = Color(0xFF1C232D),
    outline = Color(0xFF3B4757),
    error = Color(0xFFFF6B6B),
)

@Composable
fun ControllerTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = Colors, content = content)

/** A group of related settings. */
@Composable
fun SettingsCard(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth().padding(bottom = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            content()
        }
    }
}

/** Secondary text under a setting: what it does, in a sentence. */
@Composable
fun Caption(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** A labelled slider over whole numbers from [min] to [max] in [step]s, with its value shown as [format] says. */
@Composable
fun SliderRow(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    onChange: (Int) -> Unit,
    step: Int = 1,
    caption: String? = null,
    enabled: Boolean = true,
    format: (Int) -> String = { it.toString() },
) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
            Text(format(value), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(((it - min) / step).roundToInt() * step + min) },
            valueRange = min.toFloat()..max.toFloat(),
            steps = ((max - min) / step - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.semantics { contentDescription = label },
        )
        if (caption != null) Caption(caption)
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit, caption: String? = null, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(label, style = MaterialTheme.typography.bodyLarge)
            if (caption != null) Caption(caption)
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

/** One choice out of a few, as chips: [options] are value to label. */
@Composable
fun ChoiceRow(label: String, options: List<Pair<Int, String>>, selected: Int, onSelect: (Int) -> Unit, caption: String? = null, enabled: Boolean = true) {
    Column(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        if (label.isNotEmpty()) Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((value, text) in options) {
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(text) }, enabled = enabled)
            }
        }
        if (caption != null) Caption(caption)
    }
}

fun percent(value: Int) = "$value %"

fun millis(value: Int) = "$value ms"

fun degreesPerSecond(value: Int) = "$value °/s"
