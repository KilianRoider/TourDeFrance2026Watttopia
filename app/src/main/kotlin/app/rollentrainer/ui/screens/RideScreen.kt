package app.rollentrainer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.core.metrics.HeartRateZone
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.Banner
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.LineChart
import app.rollentrainer.ui.components.MetricTile
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stepper
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.theme.Metric
import kotlinx.coroutines.launch

@Composable
fun RideScreen(container: AppContainer, onFinished: (rideId: String?) -> Unit) {
    val state by container.session.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val scope = rememberCoroutineScope()
    var askEnd by remember { mutableStateOf(false) }
    var fullHistory by rememberSaveable { mutableStateOf(false) }

    fun end(save: Boolean) = scope.launch {
        askEnd = false
        val ride = container.session.finish(save)
        onFinished(ride?.id)
    }

    val trainer = status[SensorRole.TRAINER] ?: app.rollentrainer.ble.SensorStatus(null)
    val banner = when {
        trainer.sensor == null -> "Kein Smart-Trainer gekoppelt. Die Leistung wird nicht vorgegeben."
        trainer.state != ConnectionState.CONNECTED -> "Verbinde mit ${trainer.sensor?.name.orEmpty()} …"
        !s.trainerControl -> "Warte auf Steuerung durch den Trainer …"
        s.engine.elapsedOrZero() && s.engine.paused -> "Tritt in die Pedale, um zu starten."
        s.engine.autoPaused -> "Pausiert – tritt weiter, um fortzusetzen."
        s.engine.paused -> "Pausiert"
        else -> null
    }

    val wide = LocalWide.current
    val window = if (fullHistory) Int.MAX_VALUE else 600
    val shown = s.samples.takeLast(window)

    val charts: @Composable ColumnScope.(Modifier) -> Unit = { m ->
        LineChart(
            "Leistung", shown.map { it.power?.toFloat() }, Metric.Power, m,
            area = shown.map { it.target?.toFloat() }, areaColor = Metric.Target,
            unit = "W", current = s.displayPower?.toString(), minRange = 200f,
        )
        LineChart(
            "Herzfrequenz", shown.map { it.hr?.toFloat() }, Metric.HeartRate, m,
            unit = "bpm", current = s.live.heartRate?.toString(), minRange = 160f,
        )
        LineChart(
            "Trittfrequenz", shown.map { it.cadence?.toFloat() }, Metric.Cadence, m,
            unit = "rpm", current = s.live.cadence?.toString(), minRange = 100f,
        )
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(s, status)
        banner?.let { Banner(it) }
        s.timeline?.let { tl ->
            WorkoutProfile(
                tl, settings.ftp, Modifier.fillMaxWidth().height(if (wide) 88.dp else 64.dp),
                progressSec = s.engine.workoutSec, samples = s.samples, intensityPct = s.engine.intensityPct,
            )
        }
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tiles(s, settings.maxHr, Modifier.weight(1f))
                }
                Panel(Modifier.weight(0.58f).fillMaxHeight()) {
                    ChartToggle(fullHistory) { fullHistory = it }
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        charts(Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Tiles(s, settings.maxHr, Modifier)
                Panel {
                    ChartToggle(fullHistory) { fullHistory = it }
                    Spacer(Modifier.height(8.dp))
                    charts(Modifier.fillMaxWidth().height(130.dp).padding(bottom = 8.dp))
                }
            }
        }
        Controls(container, s, onEnd = { askEnd = true })
    }

    if (askEnd || s.engine.finished) {
        val done = s.engine.finished
        AlertDialog(
            onDismissRequest = { if (!done) askEnd = false },
            title = { Text(if (done) "Geschafft!" else "Training beenden?") },
            text = {
                Text(
                    if (s.elapsedSec < 30) "Die Fahrt ist kürzer als 30 Sekunden und wird nicht gespeichert."
                    else "Die Fahrt wird gespeichert" +
                        (if (settings.strava != null && settings.stravaAutoUpload) " und zu Strava hochgeladen." else ".")
                )
            },
            confirmButton = { Button(onClick = { end(true) }) { Text("Beenden und speichern") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { end(false) }) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
                    if (!done) TextButton(onClick = { askEnd = false }) { Text("Weiterfahren") }
                }
            },
        )
    }
}

private fun app.rollentrainer.core.engine.EngineState.elapsedOrZero() = workoutSec == 0.0

@Composable
private fun Header(s: RideState, status: Map<SensorRole, app.rollentrainer.ble.SensorStatus>) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(s.workout?.name ?: "Freie Fahrt", style = MaterialTheme.typography.titleLarge)
            val total = s.engine.totalSec
            Text(
                if (total > 0) "${formatDuration(s.engine.workoutSec.toInt())} von ${formatDuration(total)}"
                else formatDuration(s.elapsedSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SensorRole.entries.forEach { role ->
            val st = status[role] ?: return@forEach
            if (st.sensor == null) return@forEach
            Row(Modifier.padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(st.state)
                Spacer(Modifier.width(6.dp))
                Text(role.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Tiles(s: RideState, maxHr: Int, modifier: Modifier) {
    val target = s.engine.targetWatts
    val power = s.displayPower
    val diff = if (power != null && target != null) power - target else null
    MetricTile(
        "Leistung", power?.toString() ?: "–", "W", modifier.fillMaxWidth(), accent = Metric.Power,
        sub = target?.let { t ->
            "Ziel $t W" + (diff?.let { d -> if (kotlin.math.abs(d) >= 5) "  (${if (d > 0) "+" else ""}$d)" else "" } ?: "")
        },
        big = true,
    )
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        val hr = s.live.heartRate
        MetricTile(
            "Herzfrequenz", hr?.toString() ?: "–", "bpm", Modifier.weight(1f).fillMaxHeight(), accent = Metric.HeartRate,
            sub = hr?.let { val z = HeartRateZone.of(it, maxHr); "${z.name} · ${z.label}" },
        )
        MetricTile(
            "Trittfrequenz", s.live.cadence?.toString() ?: "–", "rpm", Modifier.weight(1f).fillMaxHeight(), accent = Metric.Cadence,
        )
    }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.timeline != null) {
            val seg = s.timeline.segments.getOrNull(s.engine.segmentIndex)
            MetricTile(
                seg?.label?.ifBlank { null } ?: "Abschnitt", formatDuration(s.engine.segmentRemainingSec), "",
                Modifier.weight(1f).fillMaxHeight(),
                sub = s.engine.nextTargetWatts?.let { "Danach $it W" } ?: "Letzter Abschnitt",
            )
            MetricTile(
                "Verbleibend", formatDuration((s.engine.totalSec - s.engine.workoutSec.toInt())), "",
                Modifier.weight(1f).fillMaxHeight(),
                sub = "Fahrzeit ${formatDuration(s.elapsedSec)}",
            )
        } else {
            MetricTile("Fahrzeit", formatDuration(s.elapsedSec), "", Modifier.weight(1f).fillMaxHeight())
            val kj = s.samples.sumOf { it.power ?: 0 } / 1000
            MetricTile("Arbeit", kj.toString(), "kJ", Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun ChartToggle(full: Boolean, onChange: (Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !full, onClick = { onChange(false) }, label = { Text("Letzte 10 min") })
        FilterChip(selected = full, onClick = { onChange(true) }, label = { Text("Ganze Fahrt") })
    }
}

@Composable
private fun Controls(container: AppContainer, s: RideState, onEnd: () -> Unit) {
    val session = container.session
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = { session.togglePause() }, modifier = Modifier.height(56.dp)) {
            Icon(if (s.engine.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
            Spacer(Modifier.width(8.dp))
            Text(if (s.engine.paused) "Weiter" else "Pause")
        }
        if (s.timeline != null) {
            Stepper("${s.engine.intensityPct} %", { session.adjustIntensity(-5) }, { session.adjustIntensity(5) }, label = "Intensität")
            FilledTonalButton(onClick = { session.skip() }, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Default.SkipNext, null)
                Spacer(Modifier.width(8.dp))
                Text("Überspringen")
            }
        } else {
            Stepper("${s.engine.targetWatts ?: 0} W", { session.adjustManual(-10) }, { session.adjustManual(10) }, label = "Zielleistung")
            OutlinedButton(onClick = { session.adjustManual(-50) }) { Text("−50") }
            OutlinedButton(onClick = { session.adjustManual(50) }) { Text("+50") }
        }
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = onEnd, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Beenden")
        }
    }
}
