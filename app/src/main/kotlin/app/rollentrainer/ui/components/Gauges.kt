package app.rollentrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.rollentrainer.core.metrics.HeartRateZone
import app.rollentrainer.core.metrics.PowerZone
import app.rollentrainer.core.metrics.ZoneScheme
import app.rollentrainer.ui.theme.zoneColor
import app.rollentrainer.ui.theme.zoneColorAt
import kotlin.math.abs

/**
 * Zwift-style target bar: the target sits in the middle, the marker shows where the power is.
 * Green within 5 %, yellow within 10 %, red beyond. The scale spans ±25 % of the target.
 */
@Composable
fun TargetGauge(power: Int?, target: Int?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val onSurface = MaterialTheme.colorScheme.onSurface
    val status = when {
        power == null || target == null || target <= 0 -> null
        abs(power - target) <= target * 0.05 -> Color(0xFF22C55E)
        abs(power - target) <= target * 0.10 -> Color(0xFFEAB308)
        else -> Color(0xFFEF4444)
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("ZIEL", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val diff = if (power != null && target != null) power - target else null
            Text(
                when {
                    target == null -> "–"
                    diff == null -> "$target W"
                    else -> "$target W  ·  ${if (diff > 0) "+" else ""}$diff W"
                },
                style = MaterialTheme.typography.labelLarge, color = status ?: MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Spacer(Modifier.height(6.dp))
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val r = CornerRadius(size.height / 2)
            drawRoundRect(track, Offset.Zero, size, r)
            if (target != null && target > 0) {
                // Tolerance bands around the centre.
                fun x(rel: Double) = ((rel + 0.25) / 0.5 * size.width).toFloat().coerceIn(0f, size.width)
                drawRoundRect(Color(0xFFEAB308).copy(alpha = 0.25f), Offset(x(-0.10), 0f), Size(x(0.10) - x(-0.10), size.height), r)
                drawRoundRect(Color(0xFF22C55E).copy(alpha = 0.35f), Offset(x(-0.05), 0f), Size(x(0.05) - x(-0.05), size.height), r)
                drawLine(onSurface.copy(alpha = 0.6f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), strokeWidth = 2f)
                if (power != null) {
                    val rel = ((power - target).toDouble() / target).coerceIn(-0.25, 0.25)
                    val px = x(rel)
                    drawRoundRect(status ?: onSurface, Offset(px - 5f, -2f), Size(10f, size.height + 4f), CornerRadius(5f))
                }
            }
        }
    }
}

/** Horizontal bars with the share of time in each zone, with the zone colour. */
@Composable
fun ZoneBars(seconds: IntArray, labels: List<String>, colors: List<Color>, modifier: Modifier = Modifier) {
    val total = seconds.sum().coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        seconds.indices.forEach { i ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(labels[i], Modifier.width(96.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Canvas(Modifier.weight(1f).height(12.dp)) {
                    val r = CornerRadius(6f)
                    drawRoundRect(track, Offset.Zero, size, r)
                    val w = size.width * seconds[i] / total
                    if (w > 0) drawRoundRect(colors[i], Offset.Zero, Size(w.coerceAtLeast(4f), size.height), r)
                }
                Spacer(Modifier.width(12.dp))
                Text(formatDuration(seconds[i]), Modifier.width(56.dp), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"))
                Text("${seconds[i] * 100 / total} %", Modifier.width(40.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
fun PowerZoneBars(seconds: IntArray, modifier: Modifier = Modifier) = ZoneBars(
    seconds, PowerZone.entries.map { "${it.name} ${it.label}" }, PowerZone.entries.map { zoneColor(it) }, modifier,
)

/** Zone bars for any scheme (Coggan 7 or Zwift 6). */
@Composable
fun PowerZoneBars(seconds: IntArray, scheme: ZoneScheme, modifier: Modifier = Modifier) = ZoneBars(
    seconds, (0 until scheme.count).map { "${scheme.name(it)} ${scheme.labels[it]}" }, (0 until scheme.count).map { zoneColorAt(it) }, modifier,
)

@Composable
fun HrZoneBars(seconds: IntArray, modifier: Modifier = Modifier) = ZoneBars(
    seconds, HeartRateZone.entries.map { "${it.name} ${it.label}" },
    listOf(Color(0xFF8A94A6), Color(0xFF3B82F6), Color(0xFF22C55E), Color(0xFFF97316), Color(0xFFEF4444)), modifier,
)

/** Three stars for interval accuracy, dimmed ones for the ones not earned; a dash when the lap is not rated. */
@Composable
fun Stars(count: Int, modifier: Modifier = Modifier, rated: Boolean = true) {
    if (!rated) {
        Text("–", modifier, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.outline)
        return
    }
    Text(
        "★".repeat(count.coerceIn(0, 3)) + "☆".repeat(3 - count.coerceIn(0, 3)),
        modifier, style = MaterialTheme.typography.titleMedium,
        color = if (count > 0) Color(0xFFEAB308) else MaterialTheme.colorScheme.outline,
    )
}
