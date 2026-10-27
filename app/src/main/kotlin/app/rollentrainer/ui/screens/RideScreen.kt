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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Tune
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
import app.rollentrainer.ble.SensorStatus
import app.rollentrainer.core.engine.EngineState
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.Banner
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.LineChart
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stepper
import app.rollentrainer.ui.components.TargetGauge
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.fields.FieldGrid
import app.rollentrainer.ui.fields.LayoutOptions
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
    var editing by rememberSaveable { mutableStateOf(false) }
    val update: ((AppSettings) -> AppSettings) -> Unit = { container.settings.update(it) }

    fun end(save: Boolean) = scope.launch {
        askEnd = false
        val ride = container.session.finish(save)
        onFinished(ride?.id)
    }

    val trainer = status[SensorRole.TRAINER] ?: SensorStatus(null)
    val banner = when {
        trainer.sensor == null -> "Kein Smart-Trainer gekoppelt. Die Leistung wird nicht vorgegeben."
        trainer.state != ConnectionState.CONNECTED -> "Verbinde mit ${trainer.sensor?.name.orEmpty()} …"
        !s.trainerControl -> "Warte auf Steuerung durch den Trainer …"
        s.engine.workoutSec == 0.0 && s.engine.paused -> "Tritt in die Pedale, um zu starten."
        s.engine.autoPaused -> "Pausiert – tritt weiter, um fortzusetzen."
        s.engine.paused -> "Pausiert"
        else -> null
    }

    val wide = LocalWide.current
    val window = if (fullHistory) Int.MAX_VALUE else 600
    val shown = s.samples.takeLast(window)
    val chartKinds = settings.rideCharts

    val charts: @Composable ColumnScope.(Modifier) -> Unit = { m ->
        if ("power" in chartKinds) LineChart(
            "Leistung", shown.map { it.power?.toFloat() }, Metric.Power, m,
            area = shown.map { it.target?.toFloat() }, areaColor = Metric.Target,
            unit = "W", current = s.displayPower?.toString(), minRange = 200f,
        )
        if ("hr" in chartKinds) LineChart(
            "Herzfrequenz", shown.map { it.hr?.toFloat() }, Metric.HeartRate, m,
            unit = "bpm", current = s.live.heartRate?.toString(), minRange = 160f,
        )
        if ("cadence" in chartKinds) LineChart(
            "Trittfrequenz", shown.map { it.cadence?.toFloat() }, Metric.Cadence, m,
            unit = "rpm", current = s.live.cadence?.toString(), minRange = 100f,
        )
    }
    val showCharts = settings.rideShowCharts && chartKinds.isNotEmpty()

    val sidePanel: @Composable ColumnScope.() -> Unit = {
        if (editing) {
            LayoutOptions(settings, update)
        } else if (showCharts) {
            ChartToggle(fullHistory) { fullHistory = it }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                charts(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }

    Column(
        Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Header(s, status, editing, onToggleEdit = { editing = !editing })
        if (editing) {
            Banner("Tippe auf ein Feld, um es zu ersetzen. × entfernt es, die Pfeile verschieben es, „+ Feld“ fügt eines hinzu.")
        } else {
            banner?.let { Banner(it) }
            s.engine.cue?.let { Banner("💬 $it", color = MaterialTheme.colorScheme.surfaceContainerHigh) }
        }
        s.timeline?.takeIf { settings.rideShowProfile }?.let { tl ->
            WorkoutProfile(
                tl, settings.ftp, Modifier.fillMaxWidth().height(if (wide) 88.dp else 64.dp),
                progressSec = s.engine.workoutSec, samples = s.samples, intensityPct = s.engine.intensityPct,
            )
        }
        if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    Modifier.weight(if (showCharts || editing) 0.45f else 1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    if (settings.rideShowGauge && s.engine.targetWatts != null) TargetGauge(s.displayPower, s.engine.targetWatts)
                    FieldGrid(s, settings, editing, onChange = { keys -> update { it.copy(rideFields = keys) } })
                }
                if (showCharts || editing) {
                    Panel(Modifier.weight(0.55f).fillMaxHeight()) { sidePanel() }
                }
            }
        } else {
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (settings.rideShowGauge && s.engine.targetWatts != null) TargetGauge(s.displayPower, s.engine.targetWatts)
                FieldGrid(s, settings, editing, onChange = { keys -> update { it.copy(rideFields = keys) } })
                if (editing) {
                    Panel { LayoutOptions(settings, update) }
                } else if (showCharts) {
                    Panel {
                        ChartToggle(fullHistory) { fullHistory = it }
                        Spacer(Modifier.height(8.dp))
                        charts(Modifier.fillMaxWidth().height(130.dp).padding(bottom = 8.dp))
                    }
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

@Composable
private fun Header(s: RideState, status: Map<SensorRole, SensorStatus>, editing: Boolean, onToggleEdit: () -> Unit) {
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
            Row(Modifier.padding(end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(st.state)
                Spacer(Modifier.width(6.dp))
                Text(role.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (editing) {
            Button(onClick = onToggleEdit) {
                Icon(Icons.Outlined.Check, null)
                Spacer(Modifier.width(8.dp))
                Text("Fertig")
            }
        } else {
            OutlinedButton(onClick = onToggleEdit) {
                Icon(Icons.Outlined.Tune, null)
                Spacer(Modifier.width(8.dp))
                Text("Anpassen")
            }
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
    val e: EngineState = s.engine
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Button(onClick = { session.togglePause() }, modifier = Modifier.height(56.dp)) {
            Icon(if (e.paused) Icons.Default.PlayArrow else Icons.Default.Pause, null)
            Spacer(Modifier.width(8.dp))
            Text(if (e.paused) "Weiter" else "Pause")
        }
        if (s.timeline != null) {
            Stepper("${e.intensityPct} %", { session.adjustIntensity(-5) }, { session.adjustIntensity(5) }, label = "Intensität")
            FilledTonalButton(onClick = { session.skip() }, modifier = Modifier.height(56.dp)) {
                Icon(Icons.Default.SkipNext, null)
                Spacer(Modifier.width(8.dp))
                Text("Überspringen")
            }
        } else {
            Stepper("${e.targetWatts ?: 0} W", { session.adjustManual(-10) }, { session.adjustManual(10) }, label = "Zielleistung")
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
