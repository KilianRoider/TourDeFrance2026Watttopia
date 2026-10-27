package app.rollentrainer.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rollentrainer.ble.ConnectionState

val CardShape = RoundedCornerShape(16.dp)

/** A flat surface with a hairline border, the basic container of the app. */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CardShape)
            .padding(padding),
        content = content,
    )
}

/** A live value: small caption, big number, unit. */
@Composable
fun MetricTile(
    label: String,
    value: String,
    unit: String,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.onSurface,
    sub: String? = null,
    big: Boolean = false,
) {
    Panel(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Spacer(Modifier.width(8.dp))
            Text(label.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = if (big) MaterialTheme.typography.displayLarge.copy(fontSize = 88.sp, lineHeight = 92.sp)
                else MaterialTheme.typography.displayMedium,
                maxLines = 1,
            )
            Spacer(Modifier.width(6.dp))
            Text(unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = if (big) 14.dp else 8.dp))
        }
        if (sub != null) {
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** A small statistic for summaries: caption over value. */
@Composable
fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.copy(fontFeatureSettings = "tnum"))
    }
}

/** Minus, value, plus. */
@Composable
fun Stepper(
    value: String,
    onMinus: () -> Unit,
    onPlus: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
) {
    Column(modifier) {
        if (label != null) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            FilledTonalIconButton(onClick = onMinus, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Remove, contentDescription = "Weniger")
            }
            Text(value, style = MaterialTheme.typography.titleMedium.copy(fontFeatureSettings = "tnum"),
                modifier = Modifier.width(76.dp), maxLines = 1, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            FilledTonalIconButton(onClick = onPlus, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Default.Add, contentDescription = "Mehr")
            }
        }
    }
}

@Composable
fun ConnectionDot(state: ConnectionState, modifier: Modifier = Modifier) {
    val color = when (state) {
        ConnectionState.CONNECTED -> Color(0xFF22C55E)
        ConnectionState.CONNECTING -> Color(0xFFEAB308)
        ConnectionState.DISCONNECTED -> MaterialTheme.colorScheme.outline
    }
    Box(modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = modifier.padding(top = 8.dp, bottom = 8.dp))
}

@Composable
fun Banner(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primaryContainer) {
    Surface(color = color, shape = CardShape, modifier = modifier.fillMaxWidth()) {
        Text(text, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

fun formatDuration(sec: Int): String {
    val s = sec.coerceAtLeast(0)
    val h = s / 3600
    val m = s % 3600 / 60
    val r = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
}

fun formatMinutes(sec: Int): String {
    val m = (sec + 30) / 60
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}
