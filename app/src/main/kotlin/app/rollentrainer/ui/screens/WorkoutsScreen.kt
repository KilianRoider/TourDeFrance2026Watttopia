package app.rollentrainer.ui.screens

import android.net.Uri
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatMinutes
import app.rollentrainer.ui.theme.zoneColorAt
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private enum class Sort(val title: String) { NAME("Name"), DURATION("Dauer"), TSS("TSS"), INTENSITY("Intensität") }
/** Filters that are not about duration or intensity; they combine like the other groups. */
private enum class Pick(val title: String) { FAVORITES("Favoriten"), OWN("Eigene"), TESTS("Tests") }

/**
 * Duration bands of the filter. A workout on an edge belongs to the lower band (a 45-minute
 * workout is "30–45 min"), because people pick a slot by "at most this long".
 */
internal enum class DurationBand(val title: String, private val maxMin: Int) {
    UP_TO_30("bis 30 min", 30),
    TO_45("30–45 min", 45),
    TO_60("45–60 min", 60),
    TO_90("60–90 min", 90),
    OVER_90("über 90 min", Int.MAX_VALUE);

    companion object {
        fun of(sec: Int): DurationBand {
            val min = (sec + 30) / 60 // same rounding as the minutes printed on the card
            return entries.first { min <= it.maxMin }
        }
    }
}

/** What a workout mainly trains, derived from where its planned work happens. */
internal enum class IntensityBand(val title: String) {
    ENDURANCE("Locker/Ausdauer"),
    TEMPO("Tempo/Sweetspot"),
    THRESHOLD("Schwelle"),
    VO2("VO2max/anaerob"),
}

/**
 * Classifies a workout by the time its targets spend in three ranges: 76–94 % FTP (tempo and
 * sweet spot), 95–105 % (threshold) and from 106 % (VO2max and anaerobic). Ramps are split
 * linearly. Free blocks count with their guide power only where it marks an all-out effort
 * (from 106 %, such as sprints and Zwift's MaxEffort) or the workout is an FTP test, because
 * that is what makes them hard. Any other free block is free riding: its guide is usually just
 * the 100 % default of the editor or of a ZWO <FreeRide> without power, so it counts as endurance.
 *
 * A range is a candidate once it is a real part of the session: hard efforts are short, so one
 * minute in total (at least 2.5 % of the workout) is enough, threshold needs 10 % and tempo 30 %.
 * Among the candidates the hardest wins unless an easier range has clearly more time (time is
 * weighted ×3, ×1.5 and ×1). Warm-ups, recoveries and plain base rides end up as endurance.
 * Spinning classes are ordinary workouts, so they are classified the same way.
 */
internal fun intensityBand(workout: Workout, ftp: Int): IntensityBand {
    val timeline = Timeline(workout)
    val total = timeline.totalSec.coerceAtLeast(1).toDouble()
    var tempo = 0.0
    var threshold = 0.0
    var hard = 0.0
    val test = workout.test != TestKind.NONE
    for (s in timeline.segments) {
        val a = s.startPower.percentOfFtp(ftp)
        val b = s.endPower.percentOfFtp(ftp)
        if (!s.erg && !test && maxOf(a, b) < 106.0) continue
        tempo += secondsInRange(a, b, s.durationSec, 76.0, 95.0)
        threshold += secondsInRange(a, b, s.durationSec, 95.0, 106.0)
        hard += secondsInRange(a, b, s.durationSec, 106.0, Double.MAX_VALUE)
    }
    val candidates = buildList {
        if (hard >= maxOf(60.0, total * 0.025)) add(IntensityBand.VO2 to hard * 3.0)
        if (threshold >= total * 0.10) add(IntensityBand.THRESHOLD to threshold * 1.5)
        if (tempo >= total * 0.30) add(IntensityBand.TEMPO to tempo)
    }
    return candidates.maxByOrNull { it.second }?.first ?: IntensityBand.ENDURANCE
}

/** Seconds a target that runs linearly from [a] to [b] % over [durationSec] spends in [lo, hi). */
private fun secondsInRange(a: Double, b: Double, durationSec: Int, lo: Double, hi: Double): Double {
    if (durationSec <= 0) return 0.0
    if (a == b) return if (a >= lo && a < hi) durationSec.toDouble() else 0.0
    val low = minOf(a, b)
    val high = maxOf(a, b)
    val overlap = (minOf(high, hi) - maxOf(low, lo)).coerceAtLeast(0.0)
    return durationSec * overlap / (high - low)
}

/** Keeps a filter selection across rotation as plain names, which every saved-state bundle can hold. */
private fun <E : Enum<E>> enumSetSaver(entries: List<E>): Saver<Set<E>, Any> = listSaver(
    save = { set -> set.map { it.name } },
    restore = { names -> names.mapNotNull { n -> entries.firstOrNull { it.name == n } }.toSet() },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutsScreen(container: AppContainer, onEdit: (id: String?, copy: Boolean) -> Unit) {
    val workouts by container.workouts.workouts.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var startCandidate by remember { mutableStateOf<Workout?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.NAME) }
    // Within a group the chips are alternatives (OR), across groups all must hold (AND);
    // an empty set means "Alle".
    var durations by rememberSaveable(stateSaver = enumSetSaver(DurationBand.entries)) { mutableStateOf(emptySet<DurationBand>()) }
    var intensities by rememberSaveable(stateSaver = enumSetSaver(IntensityBand.entries)) { mutableStateOf(emptySet<IntensityBand>()) }
    var picks by rememberSaveable(stateSaver = enumSetSaver(Pick.entries)) { mutableStateOf(emptySet<Pick>()) }
    val filtering = durations.isNotEmpty() || intensities.isNotEmpty() || picks.isNotEmpty() || query.isNotBlank()
    fun resetFilters() {
        durations = emptySet()
        intensities = emptySet()
        picks = emptySet()
        query = ""
    }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                val name = displayName(context, uri)
                context.contentResolver.openInputStream(uri)!!.use { container.workouts.import(it, name) }
            }.onSuccess { Toast.makeText(context, "„${it.name}“ importiert", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "Import fehlgeschlagen: ${it.message}", Toast.LENGTH_LONG).show() }
        }
    }

    fun start(w: Workout?) {
        val trainer = status[SensorRole.TRAINER]
        if (trainer?.state != ConnectionState.CONNECTED) {
            startCandidate = w ?: FREE_RIDE
        } else {
            container.session.start(w)
        }
    }

    val ftp = settings.ftp
    val favorites = settings.favoriteWorkoutIds
    val stress = remember(workouts, ftp) { workouts.associate { it.id to Timeline(it).plannedStress(ftp) } }
    val bands = remember(workouts, ftp) { workouts.associate { it.id to intensityBand(it, ftp) } }
    val shown = remember(workouts, query, sort, durations, intensities, picks, favorites, ftp, bands) {
        workouts
            .filter { w ->
                val q = query.trim().lowercase()
                (q.isEmpty() || w.name.lowercase().contains(q) || w.description.lowercase().contains(q)) &&
                    (durations.isEmpty() || DurationBand.of(w.durationSec()) in durations) &&
                    (intensities.isEmpty() || bands[w.id] in intensities) &&
                    (picks.isEmpty() || picks.any { p ->
                        when (p) {
                            Pick.FAVORITES -> w.id in favorites
                            Pick.OWN -> !w.builtIn
                            Pick.TESTS -> w.test != TestKind.NONE
                        }
                    })
            }
            .sortedWith(
                when (sort) {
                    Sort.NAME -> compareBy<Workout> { it.name.lowercase() }
                    Sort.DURATION -> compareBy<Workout> { it.durationSec() }
                    Sort.TSS -> compareByDescending<Workout> { stress[it.id]?.second ?: 0.0 }
                    Sort.INTENSITY -> compareByDescending<Workout> { stress[it.id]?.first ?: 0.0 }
                },
            )
            .sortedByDescending { it.id in favorites }
    }

    // Measured on the space this screen gets, so a phone in landscape behind the navigation rail
    // is treated by its real width and not by the window size.
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val pad = if (compact) 16.dp else 24.dp
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 320.dp),
            contentPadding = PaddingValues(pad),
            horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 16.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column {
                    Text("Trainings", style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(4.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        SensorRole.entries.forEach { role ->
                            val s = status[role]
                            if (s?.sensor != null) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    ConnectionDot(s.state)
                                    Spacer(Modifier.width(6.dp))
                                    Text(role.title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                        if (status.values.none { it.sensor != null }) {
                            Text("Noch keine Sensoren gekoppelt – siehe Einstellungen",
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { onEdit(null, false) }) {
                            Icon(Icons.Default.Add, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Neues Training")
                        }
                        FilledTonalButton(onClick = { start(null) }) {
                            Icon(Icons.Outlined.Speed, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Freie Fahrt")
                        }
                        OutlinedButton(onClick = { importer.launch(arrayOf("*/*")) }) {
                            Icon(Icons.Outlined.FileOpen, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Importieren (.zwo, .mrc, .erg)")
                        }
                    }
                    Spacer(Modifier.height(if (compact) 12.dp else 16.dp))
                    OutlinedTextField(
                        query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Trainings suchen …") },
                        leadingIcon = { Icon(Icons.Outlined.Search, null) },
                    )
                    Spacer(Modifier.height(8.dp))
                    FilterGroup("Dauer", compact) {
                        FilterChip(selected = durations.isEmpty(), onClick = { durations = emptySet() }, label = { Text("Alle") })
                        DurationBand.entries.forEach { b ->
                            FilterChip(
                                selected = b in durations,
                                onClick = { durations = if (b in durations) durations - b else durations + b },
                                label = { Text(b.title) },
                            )
                        }
                    }
                    FilterGroup("Intensität", compact) {
                        FilterChip(selected = intensities.isEmpty(), onClick = { intensities = emptySet() }, label = { Text("Alle") })
                        IntensityBand.entries.forEach { b ->
                            FilterChip(
                                selected = b in intensities,
                                onClick = { intensities = if (b in intensities) intensities - b else intensities + b },
                                label = { Text(b.title) },
                            )
                        }
                    }
                    FilterGroup("Auswahl", compact) {
                        Pick.entries.forEach { p ->
                            FilterChip(
                                selected = p in picks,
                                onClick = { picks = if (p in picks) picks - p else picks + p },
                                label = { Text(p.title) },
                            )
                        }
                    }
                    FilterGroup("Sortieren", compact) {
                        Sort.entries.forEach { o ->
                            FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.title) })
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (filtering) "${shown.size} von ${workouts.size} Trainings" else "${workouts.size} Trainings",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        if (filtering) TextButton(onClick = { resetFilters() }) { Text("Filter zurücksetzen") }
                    }
                    if (shown.isEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Panel(Modifier.fillMaxWidth()) {
                            Text("Kein Training passt", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (workouts.isEmpty()) "Es sind noch keine Trainings vorhanden."
                                else "Mit dieser Suche und diesen Filtern bleibt nichts übrig. Wähle weitere Dauern oder " +
                                    "Intensitäten aus oder setze die Filter zurück.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (filtering) {
                                Spacer(Modifier.height(8.dp))
                                FilledTonalButton(onClick = { resetFilters() }) { Text("Filter zurücksetzen") }
                            }
                        }
                    }
                }
            }
            items(shown, key = { it.id }) { w ->
                WorkoutCard(
                    workout = w,
                    ftp = ftp,
                    favorite = w.id in favorites,
                    onStart = { start(w) },
                    onEdit = { onEdit(w.id, w.builtIn) },
                    onFavorite = {
                        container.settings.update { s ->
                            s.copy(favoriteWorkoutIds = if (w.id in s.favoriteWorkoutIds) s.favoriteWorkoutIds - w.id else s.favoriteWorkoutIds + w.id)
                        }
                    },
                )
            }
        }
    }

    startCandidate?.let { w ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { startCandidate = null },
            title = { Text("Trainer nicht verbunden") },
            text = {
                Text(
                    "Ohne verbundenen Smart-Trainer kann die App keine Leistung vorgeben. " +
                        "Koppel den Trainer in den Einstellungen oder starte trotzdem und verbinde ihn gleich."
                )
            },
            confirmButton = {
                Button(onClick = {
                    container.session.start(w.takeIf { it !== FREE_RIDE })
                    startCandidate = null
                }) { Text("Trotzdem starten") }
            },
            dismissButton = { OutlinedButton(onClick = { startCandidate = null }) { Text("Abbrechen") } },
        )
    }
}

private fun displayName(context: android.content.Context, uri: Uri): String {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) return c.getString(0) ?: ""
    }
    return uri.lastPathSegment.orEmpty()
}

private val FREE_RIDE = Workout(id = "free", name = "Freie Fahrt")

/**
 * A titled line of chips. On a phone each group is one swipeable line under its title, because
 * five wrapped chips per group would push the first workout off the screen; on wider screens the
 * title sits in front and the chips wrap.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterGroup(title: String, compact: Boolean, chips: @Composable () -> Unit) {
    if (compact) {
        Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { chips() }
        }
    } else {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            // 14 dp centres the caption on the first 48 dp chip line.
            Text(
                title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp).padding(top = 14.dp),
            )
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) { chips() }
        }
    }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorkoutCard(workout: Workout, ftp: Int, favorite: Boolean, onStart: () -> Unit, onEdit: () -> Unit, onFavorite: () -> Unit) {
    val timeline = remember(workout) { Timeline(workout) }
    val (intensity, tss) = remember(workout, ftp) { timeline.plannedStress(ftp) }
    val dominant = remember(workout, ftp) { workout.dominantPercent(ftp) }
    Panel(Modifier.fillMaxWidth().clickable(onClick = onStart), padding = 0.dp) {
        WorkoutProfile(timeline, ftp, Modifier.fillMaxWidth().height(96.dp).padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(workout.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatMinutes(workout.durationSec())} · IF ${"%.2f".format(intensity)} · TSS ${tss.roundToInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onFavorite) {
                Icon(if (favorite) Icons.Filled.Star else Icons.Outlined.StarBorder, contentDescription = "Favorit",
                    tint = if (favorite) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onEdit) {
                Icon(if (workout.builtIn) Icons.Outlined.ContentCopy else Icons.Outlined.Edit,
                    contentDescription = if (workout.builtIn) "Kopieren und bearbeiten" else "Bearbeiten")
            }
            IconButton(onClick = onStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Starten", tint = MaterialTheme.colorScheme.primary)
            }
        }
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            if (dominant > 0) {
                val zone = app.rollentrainer.core.metrics.ZoneScheme.COGGAN.indexOf(dominant.toDouble())
                Badge("$dominant % FTP · ${app.rollentrainer.core.metrics.ZoneScheme.COGGAN.labels[zone]}", zoneColorAt(zone))
            }
            when (workout.test) {
                TestKind.RAMP -> Badge("FTP-Test · Rampe", MaterialTheme.colorScheme.primary)
                TestKind.TWENTY_MIN -> Badge("FTP-Test · 20 min", MaterialTheme.colorScheme.primary)
                TestKind.NONE -> Unit
            }
            if (workout.blocks.any { it is app.rollentrainer.core.model.Block.FreeRide }) Badge("mit freien Blöcken", MaterialTheme.colorScheme.onSurfaceVariant)
            if (!workout.builtIn) Badge("Eigenes", MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (workout.description.isNotBlank()) {
            Text(
                workout.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            )
        } else {
            Spacer(Modifier.height(8.dp))
        }
    }
}
