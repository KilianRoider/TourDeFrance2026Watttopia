package app.rollentrainer.ui.screens

import android.content.Intent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.Routes
import app.rollentrainer.ride.RaceRecord
import app.rollentrainer.ride.UploadState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.HrZoneBars
import app.rollentrainer.ui.components.LineChart
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.PowerZoneBars
import app.rollentrainer.ui.components.Stars
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
import kotlin.math.abs
import kotlin.math.roundToInt

private val dateFormat = SimpleDateFormat("EEE, d. MMM yyyy · HH:mm", Locale.GERMANY)
private val shortDate = SimpleDateFormat("d. MMM", Locale.GERMANY)

private fun startOfWeek(offsetWeeks: Int = 0): Long = Calendar.getInstance(Locale.GERMANY).apply {
    set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
    set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    add(Calendar.WEEK_OF_YEAR, -offsetWeeks)
}.timeInMillis / 1000

/** Consecutive weeks (including this one or last week) with at least one ride. */
private fun streakWeeks(rides: List<Ride>): Int {
    var streak = 0
    var offset = 0
    val thisWeek = rides.any { it.startEpochSec >= startOfWeek(0) }
    if (!thisWeek) offset = 1
    while (true) {
        val from = startOfWeek(offset)
        val to = startOfWeek(offset - 1)
        if (rides.any { it.startEpochSec in from until to }) { streak++; offset++ } else break
        if (offset > 520) break
    }
    return streak
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HistoryScreen(container: AppContainer, onOpen: (String) -> Unit) {
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val records by container.session.raceRecords.all.collectAsStateWithLifecycle()
    val week = remember(rides) {
        val from = startOfWeek()
        rides.filter { it.startEpochSec >= from }
    }
    val streak = remember(rides) { streakWeeks(rides) }
    // Measured on the space the list gets: on a phone the margins shrink and a ride's numbers move
    // under its name instead of beside it.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(if (compact) 16.dp else 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Verlauf", style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                Panel(Modifier.fillMaxWidth()) {
                    Text("Diese Woche", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(if (compact) 24.dp else 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Stat("Fahrten", week.size.toString())
                        Stat("Zeit", formatMinutes(week.sumOf { it.summary.durationSec }))
                        Stat("TSS", week.sumOf { it.summary.tss }.roundToInt().toString())
                        Stat("Arbeit", "${week.sumOf { it.summary.workKj }} kJ")
                        if (streak > 0) Stat("Serie", "$streak Wo.")
                    }
                    val goal = settings.weeklyGoalTss
                    if (goal > 0) {
                        val tss = week.sumOf { it.summary.tss }
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Wochenziel", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                            Text("${tss.roundToInt()} / $goal TSS", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.height(6.dp))
                        LinearProgressIndicator(progress = { (tss / goal).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        if (tss >= goal) Text("Ziel erreicht!", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
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
                    val rated = ride.laps.filter { it.rated }
                    if (compact) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ride.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                            if (ride.stravaActivityId != null) {
                                Spacer(Modifier.width(8.dp))
                                Text("Strava ✓", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        Text(dateFormat.format(Date(ride.startEpochSec * 1000)) + (ride.rpe?.let { " · RPE $it" } ?: ""),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        RaceLine(records[ride.id], ride.notes)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Stat("Dauer", formatDuration(ride.summary.durationSec))
                            Stat("Ø Leistung", "${ride.summary.avgPower} W")
                            Stat("TSS", ride.summary.tss.roundToInt().toString())
                            if (rated.isNotEmpty()) Stat("Sterne", "${rated.sumOf { it.stars(settings.starTolerance) }}/${rated.size * 3}")
                        }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(ride.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(dateFormat.format(Date(ride.startEpochSec * 1000)) + (ride.rpe?.let { " · RPE $it" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                RaceLine(records[ride.id], ride.notes)
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Stat("Dauer", formatDuration(ride.summary.durationSec))
                                Stat("Ø Leistung", "${ride.summary.avgPower} W")
                                Stat("TSS", ride.summary.tss.roundToInt().toString())
                                if (rated.isNotEmpty()) Stat("Sterne", "${rated.sumOf { it.stars(settings.starTolerance) }}/${rated.size * 3}")
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
    }
}

private fun bestsOf(ride: Ride): Map<Int, Int> = ride.bests.ifEmpty { Analysis.bests(ride.samples) }

/**
 * Length and climbing of a finished route ride as it was ridden. [olderRoad]: the ride ended at another
 * height or climbed more or less than today's route does, so the route's profile has changed since (an
 * update put the measured real road under the built-in routes).
 */
private class Ridden(val lengthM: Double?, val ascentM: Double?, val olderRoad: Boolean)

/**
 * A ride keeps the numbers it was ridden with: its distance and the climbing of its altitude samples.
 * Only while they still match [route] (finish height, length and climbing) are the route's exact
 * figures used. The climbing is null for a ride without altitudes (before Build 11) on a changed route.
 */
private fun riddenOn(ride: Ride, route: Route?): Ridden {
    val alts = ride.samples.mapNotNull { it.altitudeM }
    val ascent = if (alts.size >= 2) alts.zipWithNext().sumOf { (a, b) -> (b - a).coerceAtLeast(0.0) } else null
    val distance = ride.summary.distanceM.takeIf { it > 0 }
    // On the same road the samples end at its finish height and climb what it climbs, to well under 1 %.
    val finishM = route?.points?.lastOrNull()?.elevationM
    val olderRoad = (finishM != null && alts.isNotEmpty() && abs(alts.last() - finishM) > 0.5) ||
        (route != null && ascent != null && abs(ascent - route.ascentM) > maxOf(10.0, 0.03 * route.ascentM))
    val same = route != null && !olderRoad && distance != null && abs(distance - route.lengthM) <= maxOf(50.0, 0.02 * route.lengthM)
    return if (same) Ridden(route.lengthM, route.ascentM, false) else Ridden(distance, ascent, olderRoad)
}

/**
 * A pro-mode ride in the list: "Etappe 12 · Platz 37 als Tadej Pogačar". From the stored result, or from
 * the line pro mode wrote into the notes when only the ride itself is left.
 */
@Composable
private fun RaceLine(record: RaceRecord?, notes: String) {
    val text = record?.historyLine
        ?: notes.lineSequence().firstOrNull { it.startsWith(RaceRecord.NOTES_PREFIX) }?.substringBefore(" · Leistung")
        ?: return
    Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RideDetailScreen(container: AppContainer, rideId: String, onBack: () -> Unit) {
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    val upload by container.session.upload.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val records by container.session.raceRecords.all.collectAsStateWithLifecycle()
    val ride = rides.firstOrNull { it.id == rideId }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var confirmDelete by remember { mutableStateOf(false) }
    var editRpe by remember { mutableStateOf(false) }

    if (ride == null) {
        Column(Modifier.padding(24.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Zurück") }
            Text("Fahrt wird geladen …")
        }
        return
    }
    val s = ride.summary
    val record = records[ride.id]
    // Next to the full pro-mode result the line it wrote into the notes would only repeat it.
    val shownNotes = if (record != null) RaceRecord.userNotes(ride.notes) else ride.notes
    val uploadState = upload?.takeIf { it.first == ride.id }?.second ?: UploadState.Idle
    val workout = ride.workoutId?.let { container.workouts.find(it) }
    val tolerance = settings.starTolerance

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

    // Personal records: best power per window over the last 365 days before this ride.
    val bests = remember(ride.id) { bestsOf(ride) }
    val previousBests = remember(ride.id, rides.size) {
        val from = ride.startEpochSec - 365L * 86400
        val others = rides.filter { it.id != ride.id && it.startEpochSec in from..ride.startEpochSec }
        Analysis.BEST_WINDOWS.associateWith { w -> others.mapNotNull { bestsOf(it)[w] }.maxOrNull() }
    }
    val previousSame = remember(ride.id, rides.size) {
        ride.workoutId?.let { id -> rides.filter { it.workoutId == id && it.startEpochSec < ride.startEpochSec }.maxByOrNull { it.startEpochSec } }
    }

    // On a phone: smaller margins and title, the notes above their button, and the lap table scrolls sideways.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val pad = if (compact) 16.dp else 24.dp
        val gap = if (compact) 24.dp else 32.dp
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(Modifier.padding(start = if (compact) 4.dp else 8.dp, top = 8.dp, end = pad), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
                Column(Modifier.weight(1f)) {
                    Text(
                        ride.name, style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                    Text(dateFormat.format(Date(ride.startEpochSec * 1000)),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Outlined.Delete, "Löschen") }
            }
            Column(Modifier.padding(horizontal = pad), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                Panel(Modifier.fillMaxWidth()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp)) {
                        Stat("Dauer", formatDuration(s.durationSec))
                        Stat("Ø Leistung", "${s.avgPower} W")
                        Stat("NP", "${s.normalizedPower} W")
                        Stat("Max. Leistung", "${s.maxPower} W")
                        Stat("IF", "%.2f".format(s.intensityFactor))
                        Stat("TSS", s.tss.roundToInt().toString())
                        Stat("Arbeit", "${s.workKj} kJ")
                        Stat("W/kg", "%.2f".format(s.avgPower / settings.weightKg))
                        s.avgHr?.let { Stat("Ø Puls", "$it bpm") }
                        s.maxHr?.let { Stat("Max. Puls", "$it bpm") }
                        s.avgCadence?.let { Stat("Ø Trittfrequenz", "$it rpm") }
                        if (s.distanceM > 0) Stat("Distanz", "%.1f km".format(s.distanceM / 1000))
                        Stat("RPE", ride.rpe?.let { "$it/10" } ?: "–")
                    }
                    Spacer(Modifier.height(8.dp))
                    if (compact) {
                        if (shownNotes.isNotBlank()) Text(shownNotes, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { editRpe = true }) { Text(if (ride.rpe == null) "Anstrengung bewerten" else "Bewertung ändern") }
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (shownNotes.isNotBlank()) Text(shownNotes, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            else Spacer(Modifier.weight(1f))
                            TextButton(onClick = { editRpe = true }) { Text(if (ride.rpe == null) "Anstrengung bewerten" else "Bewertung ändern") }
                        }
                    }
                }

                // Pro mode: the place, the gaps and the top of the stage result.
                if (record != null) RaceRecordPanel(record, compact)

                // Virtual route: the climb, the time and the climbing speed.
                val routeId = ride.routeId
                val routeTime = ride.routeTimeSec
                if (routeId != null) {
                    val route = remember(ride.id) { Routes.byId(routeId)?.cut(ride.routeFraction, ride.routeVariant) }
                    // The ride's cut no longer fits today's route (a finale as long as the whole stage is now).
                    val cutLost = route != null && ride.routeVariant.isNotBlank() && route.variant != ride.routeVariant
                    // A finished ride shows what it was ridden with, also when the route's profile has changed since.
                    val ridden = remember(ride.id) { if (routeTime != null) riddenOn(ride, route) else null }
                    Panel(Modifier.fillMaxWidth()) {
                        Text(
                            route?.let { if (cutLost) "${it.name} · ${ride.routeVariant}" else it.displayName } ?: "Virtuelle Strecke",
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            if (ridden != null) {
                                ridden.lengthM?.let { m ->
                                    Stat("Strecke", ridden.ascentM?.let { "%.1f km · %.0f Hm".format(m / 1000, it) } ?: "%.1f km".format(m / 1000))
                                }
                            } else if (route != null && !cutLost) {
                                Stat("Strecke", "%.1f km · %.0f Hm".format(route.lengthKm, route.ascentM))
                            }
                            Stat(if (route?.isStage == true) "Zeit im Ziel" else "Zeit bis oben", routeTime?.let { formatDuration(it) } ?: "nicht beendet")
                            if (ridden != null && routeTime != null && routeTime > 0) {
                                ridden.lengthM?.let { Stat("Ø Tempo", "%.1f km/h".format(it / routeTime * 3.6)) }
                                ridden.ascentM?.let { Stat("VAM", "${(it / routeTime * 3600).roundToInt()} m/h") }
                            }
                        }
                        if (ridden?.olderRoad == true || cutLost) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Auf einer früheren Fassung der Strecke gefahren" + if (ridden != null) "; Länge und Höhenmeter wie damals aufgezeichnet." else ".",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (route != null && !cutLost) {
                            Spacer(Modifier.height(8.dp))
                            RouteProfile(
                                route, null, Modifier.fillMaxWidth().height(72.dp),
                                lineColor = MaterialTheme.colorScheme.primary, fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            )
                        }
                    }
                }

                // FTP suggestion: depends on the kind of test the workout declared.
                val test = if (ride.test != TestKind.NONE) ride.test else if (ride.workoutId == "preset-ramp-test") TestKind.RAMP else TestKind.NONE
                val ftpEstimate = remember(ride.id) { Analysis.ftpEstimate(ride.samples, test == TestKind.RAMP) }
                if (ftpEstimate != null && !ride.ftpAccepted && (test != TestKind.NONE || ftpEstimate > ride.ftp + 2)) {
                    Panel(Modifier.fillMaxWidth()) {
                        Text(
                            when (test) {
                                TestKind.RAMP -> "Rampentest ausgewertet"
                                TestKind.TWENTY_MIN -> "20-Minuten-Test ausgewertet"
                                TestKind.NONE -> "Neue FTP möglich"
                            },
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            when (test) {
                                TestKind.RAMP -> "75 % deiner besten Minute ergeben eine FTP von $ftpEstimate W (bisher ${ride.ftp} W)."
                                TestKind.TWENTY_MIN -> "95 % deiner besten 20 Minuten ergeben eine FTP von $ftpEstimate W (bisher ${ride.ftp} W)."
                                TestKind.NONE -> "95 % deiner besten 20 Minuten ergeben $ftpEstimate W, mehr als deine FTP von ${ride.ftp} W."
                            },
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(onClick = {
                                container.settings.setFtp(ftpEstimate, if (test == TestKind.NONE) "Fahrt" else "Test")
                                scope.launch { container.rides.save(ride.copy(ftpAccepted = true)) }
                            }) { Text("FTP auf $ftpEstimate W setzen") }
                            TextButton(onClick = { scope.launch { container.rides.save(ride.copy(ftpAccepted = true)) } }) { Text("Ignorieren") }
                        }
                    }
                }

                // Best efforts with personal-record markers.
                if (bests.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Bestwerte", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Analysis.BEST_WINDOWS.forEach { w ->
                                val v = bests[w] ?: return@forEach
                                val prev = previousBests[w]
                                val label = when (w) { 5 -> "5 s"; 60 -> "1 min"; 300 -> "5 min"; else -> "20 min" }
                                Column {
                                    Stat(label, "$v W")
                                    when {
                                        prev == null -> Text("erster Wert", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        v > prev -> Text("Rekord (+${v - prev} W)", style = MaterialTheme.typography.labelSmall, color = Color(0xFFEAB308))
                                        else -> Text("Bisher $prev W", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                }
                            }
                        }
                        Text("Rekorde beziehen sich auf die letzten 365 Tage.", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))
                    }
                }

                // Comparison with the last time this workout was ridden.
                previousSame?.let { prev ->
                    Panel(Modifier.fillMaxWidth()) {
                        Text("Vergleich mit dem letzten Mal (${shortDate.format(Date(prev.startEpochSec * 1000))})", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        fun delta(now: Int?, before: Int?, unit: String): String {
                            if (now == null || before == null) return "–"
                            val d = now - before
                            return (if (d > 0) "+" else "") + "$d $unit"
                        }
                        val ratedNow = ride.laps.filter { it.rated }
                        val ratedPrev = prev.laps.filter { it.rated }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(gap), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Stat("Ø Leistung", delta(s.avgPower, prev.summary.avgPower, "W"))
                            Stat("NP", delta(s.normalizedPower, prev.summary.normalizedPower, "W"))
                            Stat("TSS", delta(s.tss.roundToInt(), prev.summary.tss.roundToInt(), ""))
                            Stat("Ø Puls", delta(s.avgHr, prev.summary.avgHr, "bpm"))
                            if (ratedNow.isNotEmpty() && ratedPrev.isNotEmpty()) {
                                Stat("Sterne", delta(ratedNow.sumOf { it.stars(tolerance) }, ratedPrev.sumOf { it.stars(tolerance) }, ""))
                            }
                            Stat("RPE", delta(ride.rpe, prev.rpe, ""))
                        }
                        val hrNow = s.avgHr
                        val hrBefore = prev.summary.avgHr
                        if (hrNow != null && hrBefore != null && s.avgPower >= prev.summary.avgPower && hrNow < hrBefore) {
                            Text("Gleiche oder mehr Leistung bei niedrigerem Puls – ein gutes Zeichen für deine Form.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }

                workout?.let { w ->
                    WorkoutProfile(Timeline(w), ride.ftp, Modifier.fillMaxWidth().height(72.dp),
                        progressSec = s.durationSec.toDouble(), samples = ride.samples)
                }
                if (ride.laps.isNotEmpty()) {
                    Panel(Modifier.fillMaxWidth()) {
                        val rated = ride.laps.filter { it.rated }
                        val totalStars = rated.sumOf { it.stars(tolerance) }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Abschnitte", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                            if (rated.isNotEmpty()) {
                                Text("$totalStars von ${rated.size * 3} Sternen · ${tolerance.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        // Eight columns need about 600 dp; narrower panels scroll the table sideways instead
                        // of squeezing every cell to a few letters.
                        BoxWithConstraints(Modifier.fillMaxWidth()) {
                            val tableWidth = maxOf(maxWidth, 600.dp)
                            Column(Modifier.horizontalScroll(rememberScrollState())) {
                                Column(Modifier.width(tableWidth)) {
                                    Row(Modifier.fillMaxWidth()) {
                                        Text("Abschnitt", Modifier.weight(1.6f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        listOf("Dauer", "Ziel", "Ø W", "Ø Puls", "Ø rpm", "Genauigk.").forEach {
                                            Text(it, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        Spacer(Modifier.weight(1f))
                                    }
                                    ride.laps.forEach { lap ->
                                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Text(lap.label, Modifier.weight(1.6f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            val cells = listOf(
                                                formatDuration(lap.durationSec), lap.targetWatts?.toString() ?: "–", lap.avgPower.toString(),
                                                lap.avgHr?.toString() ?: "–", lap.avgCadence?.toString() ?: "–",
                                                if (lap.rated) lap.accuracyPct?.let { "$it %" } ?: "–" else "–",
                                            )
                                            cells.forEach { Text(it, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), maxLines = 1) }
                                            Stars(lap.stars(tolerance), Modifier.weight(1f), rated = lap.rated)
                                        }
                                    }
                                }
                            }
                        }
                        Text(
                            "Rampen, freie Blöcke und Abschnitte unter 30 s werden nicht bewertet; die ersten 5 s jedes Abschnitts zählen nicht. " +
                                "3 Sterne bei einer Abweichung bis ±${tolerance.pct} % (oder höchstens 10 W), 2 bis ±${tolerance.pct * 2} %, 1 bis ±${tolerance.pct * 3} %.",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
                Panel(Modifier.fillMaxWidth()) {
                    Text("Zeit in Leistungszonen · ${settings.zoneScheme.title}", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    PowerZoneBars(remember(ride.id, settings.zoneScheme) { settings.zoneScheme.timeInZones(ride.samples.map { it.power }, ride.ftp) }, settings.zoneScheme)
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
                    Spacer(Modifier.height(16.dp))
                    val wbal = remember(ride.id, settings.wPrimeKj) { Analysis.wBalSeries(ride.samples, ride.ftp, settings.wPrimeKj * 1000) }
                    LineChart("W'-Bilanz", wbal.map { (it / 1000).toFloat().coerceAtLeast(0f) }, Metric.Target, Modifier.fillMaxWidth().height(chartHeight),
                        unit = "kJ", current = "min %.1f".format((wbal.minOrNull() ?: 0.0) / 1000), minRange = settings.wPrimeKj.toFloat())
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
    }

    if (editRpe) {
        var rpe by remember { mutableStateOf(ride.rpe) }
        var notes by remember { mutableStateOf(ride.notes) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { editRpe = false },
            title = { Text("Anstrengung (RPE)") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        (1..10).forEach { n -> FilterChip(selected = rpe == n, onClick = { rpe = n }, label = { Text(n.toString()) }) }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(notes, { notes = it }, label = { Text("Notiz") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { container.rides.save(ride.copy(rpe = rpe, notes = notes)) }
                    editRpe = false
                }) { Text("Speichern") }
            },
            dismissButton = { TextButton(onClick = { editRpe = false }) { Text("Abbrechen") } },
        )
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
                        container.session.raceRecords.delete(ride.id)
                        onBack()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}
