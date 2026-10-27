package app.rollentrainer.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.ui.components.HrZoneBars
import app.rollentrainer.ui.components.PowerZoneBars
import app.rollentrainer.ui.components.Stars
import app.rollentrainer.ride.UploadState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.LineChart
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stat
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.components.formatMinutes
import app.rollentrainer.ui.theme.Metric
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val dateFormat = SimpleDateFormat("EEE, d. MMM yyyy · HH:mm", Locale.GERMANY)

private fun startOfWeek(): Long = Calendar.getInstance(Locale.GERMANY).apply {
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
}.timeInMillis / 1000

@Composable
fun HistoryScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    val week = remember(rides) {
        val from = startOfWeek()
        rides.filter { it.startEpochSec >= from }
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Verlauf", style = MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Panel(Modifier.fillMaxWidth()) {
                Text("Diese Woche", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                    Stat("Fahrten", week.size.toString())
                    Stat("Zeit", formatMinutes(week.sumOf { it.summary.durationSec }))
                    Stat("TSS", week.sumOf { it.summary.tss }.roundToInt().toString())
                    Stat("Arbeit", "${week.sumOf { it.summary.workKj }} kJ")
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (rides.isEmpty()) {
            item {
                Text("Noch keine Fahrten. Nach deinem ersten Training erscheint es hier.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        items(rides, key = { it.id }) { ride ->
            Panel(Modifier.fillMaxWidth().clickable { onOpen(ride.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ride.name, style = MaterialTheme.typography.titleMedium)
                        Text(dateFormat.format(Date(ride.startEpochSec * 1000)),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        Stat("Dauer", formatDuration(ride.summary.durationSec))
                        Stat("Ø Watt", ride.summary.avgPower.toString())
                        Stat("TSS", ride.summary.tss.roundToInt().toString())
                    }
                    if (ride.stravaActivityId != null) {
                        Spacer(Modifier.width(16.dp))
                        Text("Strava ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideDetailScreen(container: AppContainer, rideId: String, onBack: () -> Unit) {
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    val upload by container.session.upload.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val ride = rides.firstOrNull { it.id == rideId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }

    if (ride == null) {
        Column(Modifier.padding(24.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Fahrt wird geladen …")
        }
        return
    }
    val s = ride.summary
    val uploadState = upload?.takeIf { it.first == ride.id }?.second ?: UploadState.Idle
    val workout = ride.workoutId?.let { container.workouts.find(it) }

    fun shareFit() = scope.launch {
        val file = container.rides.exportFit(ride)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "FIT-Datei teilen"))
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Column(Modifier.weight(1f)) {
                Text(ride.name, style = MaterialTheme.typography.headlineMedium)
                Text(dateFormat.format(Date(ride.startEpochSec * 1000)),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Löschen") }
        }
        Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Panel(Modifier.fillMaxWidth()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Dauer", formatDuration(s.durationSec))
                    Stat("Ø Leistung", "${s.avgPower} W")
                    Stat("NP", "${s.normalizedPower} W")
                    Stat("Max", "${s.maxPower} W")
                    Stat("IF", "%.2f".format(s.intensityFactor))
                    Stat("TSS", s.tss.roundToInt().toString())
                    Stat("Arbeit", "${s.workKj} kJ")
                    Stat("W/kg", "%.2f".format(s.avgPower / settings.weightKg))
                    s.avgHr?.let { Stat("Ø Puls", "$it bpm") }
                    s.maxHr?.let { Stat("Max Puls", "$it bpm") }
                    s.avgCadence?.let { Stat("Ø Trittfrequenz", "$it rpm") }
                    if (s.distanceM > 0) Stat("Distanz", "%.1f km".format(s.distanceM / 1000))
                }
            }
            // FTP suggestion: 95 % of the best 20 minutes, or 75 % of the best minute after a ramp test.
            val rampTest = ride.workoutId == "preset-ramp-test"
            val ftpEstimate = remember(ride.id) { Analysis.ftpEstimate(ride.samples, rampTest) }
            if (ftpEstimate != null && !ride.ftpAccepted && (rampTest || ftpEstimate > ride.ftp + 2)) {
                Panel(Modifier.fillMaxWidth()) {
                    Text(if (rampTest) "Rampentest ausgewertet" else "Neue FTP möglich", style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (rampTest) "75 % deiner besten Minute ergeben eine FTP von $ftpEstimate W (bisher ${ride.ftp} W)."
                        else "95 % deiner besten 20 Minuten ergeben $ftpEstimate W, mehr als deine FTP von ${ride.ftp} W.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = {
                            container.settings.update { it.copy(ftp = ftpEstimate) }
                            scope.launch { container.rides.save(ride.copy(ftpAccepted = true)) }
                        }) { Text("FTP auf $ftpEstimate W setzen") }
                        TextButton(onClick = { scope.launch { container.rides.save(ride.copy(ftpAccepted = true)) } }) { Text("Ignorieren") }
                    }
                }
            }
            workout?.let { w ->
                WorkoutProfile(Timeline(w), ride.ftp, Modifier.fillMaxWidth().height(72.dp),
                    progressSec = s.durationSec.toDouble(), samples = ride.samples)
            }
            if (ride.laps.isNotEmpty()) {
                Panel(Modifier.fillMaxWidth()) {
                    val totalStars = ride.laps.sumOf { it.stars }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Abschnitte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        Text("$totalStars von ${ride.laps.size * 3} Sternen", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Text("Abschnitt", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        listOf("Dauer", "Ziel", "Ø W", "Ø Puls", "Ø rpm", "Genau").forEach {
                            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.weight(1f))
                    }
                    ride.laps.forEach { lap ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(lap.label, Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
                            val cells = listOf(
                                formatDuration(lap.durationSec), lap.targetWatts?.toString() ?: "–", lap.avgPower.toString(),
                                lap.avgHr?.toString() ?: "–", lap.avgCadence?.toString() ?: "–", lap.accuracyPct?.let { "$it %" } ?: "–",
                            )
                            cells.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum")) }
                            Stars(lap.stars, Modifier.weight(1f))
                        }
                    }
                }
            }
            Panel(Modifier.fillMaxWidth()) {
                Text("Zeit in Leistungszonen", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                PowerZoneBars(remember(ride.id) { Analysis.timeInZones(ride.samples, ride.ftp) })
                if (s.avgHr != null) {
                    Spacer(Modifier.height(16.dp))
                    Text("Zeit in Pulszonen", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    HrZoneBars(remember(ride.id, settings.maxHr) { Analysis.timeInHrZones(ride.samples, settings.maxHr) })
                }
            }
            val chartHeight = if (LocalWide.current) 180.dp else 140.dp
            Panel(Modifier.fillMaxWidth()) {
                LineChart("Leistung", ride.samples.map { it.power?.toFloat() }, Metric.Power, Modifier.fillMaxWidth().height(chartHeight),
                    area = ride.samples.map { it.target?.toFloat() }, areaColor = Metric.Target, unit = "W",
                    current = "Ø ${s.avgPower}", minRange = 200f)
                Spacer(Modifier.height(16.dp))
                LineChart("Herzfrequenz", ride.samples.map { it.hr?.toFloat() }, Metric.HeartRate, Modifier.fillMaxWidth().height(chartHeight),
                    unit = "bpm", current = s.avgHr?.let { "Ø $it" }, minRange = 160f)
                Spacer(Modifier.height(16.dp))
                LineChart("Trittfrequenz", ride.samples.map { it.cadence?.toFloat() }, Metric.Cadence, Modifier.fillMaxWidth().height(chartHeight),
                    unit = "rpm", current = s.avgCadence?.let { "Ø $it" }, minRange = 100f)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    ride.stravaActivityId != null || uploadState is UploadState.Done -> OutlinedButton(onClick = {
                        val id = ride.stravaActivityId ?: (uploadState as UploadState.Done).activityId
                        context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.strava.com/activities/$id")))
                    }) { Text("Auf Strava ansehen") }
                    uploadState is UploadState.Running -> Button(onClick = {}, enabled = false) { Text("Lade zu Strava hoch …") }
                    else -> Button(onClick = { container.session.uploadToStrava(ride) }, enabled = settings.strava != null) {
                        Icon(Icons.Outlined.CloudUpload, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Zu Strava hochladen")
                    }
                }
                OutlinedButton(onClick = { shareFit() }) {
                    Icon(Icons.Outlined.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text("FIT-Datei teilen")
                }
            }
            if (uploadState is UploadState.Failed) {
                Text(uploadState.message, color = MaterialTheme.colorScheme.error)
            }
            if (settings.strava == null) {
                Text(
                    "Für den Strava-Upload verbinde dein Konto in den Einstellungen. " +
                        "Für Garmin Connect: FIT-Datei teilen und auf connect.garmin.com über „Daten importieren“ hochladen.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Fahrt löschen?") },
            text = { Text("Die Fahrt wird nur in der App gelöscht, nicht auf Strava.") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        container.rides.delete(ride.id)
                        onBack()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
