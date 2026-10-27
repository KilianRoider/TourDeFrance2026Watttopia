package app.rollentrainer.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import app.rollentrainer.core.metrics.PowerZone
import app.rollentrainer.core.model.Sample
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.ui.theme.zoneColor
import kotlin.math.ceil
import kotlin.math.max

/** Averages [values] into at most [buckets] points, keeping gaps (null) where no data exists. */
fun downsample(values: List<Float?>, buckets: Int): List<Float?> {
    if (values.size <= buckets || buckets <= 0) return values
    val step = values.size.toDouble() / buckets
    return List(buckets) { b ->
        val from = (b * step).toInt()
        val to = ((b + 1) * step).toInt().coerceAtMost(values.size).coerceAtLeast(from + 1)
        var sum = 0f
        var n = 0
        for (i in from until to) values[i]?.let { sum += it; n++ }
        if (n == 0) null else sum / n
    }
}

/**
 * A minimal line chart over time. [area] is drawn as a filled band behind the line,
 * used for the ERG target behind the measured power.
 */
@Composable
fun LineChart(
    title: String,
    values: List<Float?>,
    color: Color,
    modifier: Modifier = Modifier,
    area: List<Float?>? = null,
    areaColor: Color = Color.Gray,
    unit: String = "",
    current: String? = null,
    minRange: Float = 50f,
) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
            Text(title.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (current != null) {
                Text("$current $unit".trim(), style = MaterialTheme.typography.labelLarge, color = color)
            }
        }
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val buckets = max(2, (size.width / 3f).toInt())
            val line = downsample(values, buckets)
            val band = area?.let { downsample(it, buckets) }
            val all = line.filterNotNull() + band.orEmpty().filterNotNull()
            val top = niceCeil(max(all.maxOrNull() ?: 0f, minRange) * 1.1f)

            // Three faint grid lines with their values.
            for (i in 1..3) {
                val v = top * i / 3f
                val y = size.height - size.height * i / 3f
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
                drawText(measurer, v.toInt().toString(), Offset(4f, y + 2f), labelStyle)
            }
            if (band != null) drawBand(band, top, areaColor)
            drawSeries(line, top, color)
        }
    }
}

private fun niceCeil(v: Float): Float {
    val step = when {
        v <= 100 -> 20f
        v <= 300 -> 50f
        else -> 100f
    }
    return ceil(v / step) * step
}

private fun DrawScope.xAt(i: Int, n: Int) = if (n <= 1) 0f else size.width * i / (n - 1)
private fun DrawScope.yAt(v: Float, top: Float) = size.height - (v / top).coerceIn(0f, 1f) * size.height

private fun DrawScope.drawSeries(values: List<Float?>, top: Float, color: Color) {
    val n = values.size
    if (n < 2) return
    val line = Path()
    val fill = Path()
    var open = false
    var startX = 0f
    var lastX = 0f
    fun closeFill() {
        if (!open) return
        fill.lineTo(lastX, size.height)
        fill.lineTo(startX, size.height)
        fill.close()
        open = false
    }
    values.forEachIndexed { i, v ->
        val x = xAt(i, n)
        if (v == null) {
            closeFill()
            return@forEachIndexed
        }
        val y = yAt(v, top)
        if (!open) {
            line.moveTo(x, y)
            fill.moveTo(x, y)
            startX = x
            open = true
        } else {
            line.lineTo(x, y)
            fill.lineTo(x, y)
        }
        lastX = x
    }
    closeFill()
    drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), color.copy(alpha = 0.02f))))
    drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

private fun DrawScope.drawBand(values: List<Float?>, top: Float, color: Color) {
    val n = values.size
    values.forEachIndexed { i, v ->
        if (v == null) return@forEachIndexed
        val x0 = xAt(i, n)
        val x1 = xAt(i + 1, n).coerceAtMost(size.width)
        val y = yAt(v, top)
        drawRect(color.copy(alpha = 0.18f), Offset(x0, y), Size(max(1f, x1 - x0 + 0.5f), size.height - y))
    }
}

/**
 * The workout as coloured zone bars. Up to [progressSec] the part already ridden is dimmed,
 * and the measured power ([samples]) is drawn on top.
 */
@Composable
fun WorkoutProfile(
    timeline: Timeline,
    ftp: Int,
    modifier: Modifier = Modifier,
    progressSec: Double? = null,
    samples: List<Sample>? = null,
    intensityPct: Int = 100,
) {
    val cursor = MaterialTheme.colorScheme.onSurface
    val dim = MaterialTheme.colorScheme.background
    Canvas(modifier) {
        val total = timeline.totalSec.coerceAtLeast(1)
        val maxPct = max(130.0, timeline.segments.maxOfOrNull {
            max(it.startPower.percentOfFtp(ftp), it.endPower.percentOfFtp(ftp))
        } ?: 100.0) * intensityPct / 100.0
        fun x(sec: Double) = (sec / total * size.width).toFloat()
        fun y(pct: Double) = (size.height - (pct * intensityPct / 100.0 / maxPct) * size.height).toFloat()
        val gap = 1.dp.toPx()
        for (s in timeline.segments) {
            val a = s.startPower.percentOfFtp(ftp)
            val b = s.endPower.percentOfFtp(ftp)
            val x0 = x(s.startSec.toDouble())
            val x1 = max(x0 + 1f, x(s.endSec.toDouble()) - gap)
            val path = Path().apply {
                moveTo(x0, size.height)
                lineTo(x0, y(a))
                lineTo(x1, y(b))
                lineTo(x1, size.height)
                close()
            }
            drawPath(path, zoneColor(PowerZone.of((a + b) / 2)))
        }
        if (progressSec != null) {
            val px = x(progressSec)
            drawRect(dim.copy(alpha = 0.55f), Offset.Zero, Size(px, size.height))
            if (samples != null && samples.size > 1 && ftp > 0) {
                val pts = downsample(samples.map { it.power?.toFloat() }, (px / 2f).toInt().coerceAtLeast(2))
                val path = Path()
                var started = false
                pts.forEachIndexed { i, w ->
                    if (w == null) return@forEachIndexed
                    val xx = px * i / (pts.size - 1).coerceAtLeast(1)
                    val yy = (size.height - (w / ftp * 100.0 / maxPct) * size.height).toFloat().coerceIn(0f, size.height)
                    if (!started) { path.moveTo(xx, yy); started = true } else path.lineTo(xx, yy)
                }
                drawPath(path, cursor.copy(alpha = 0.85f), style = Stroke(1.5.dp.toPx(), join = StrokeJoin.Round))
            }
            drawLine(
                cursor, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
            )
        }
    }
}
