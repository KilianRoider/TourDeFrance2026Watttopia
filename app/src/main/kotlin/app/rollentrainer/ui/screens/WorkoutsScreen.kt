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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
private enum class Filter(val title: String) {
    ALL("Alle"), FAVORITES("Favoriten"), OWN("Eigene"), TESTS("Tests"), SHORT("≤ 45 min"),
    BASE("Grundlage"), SWEET("Sweet Spot & Schwelle"), HARD("VO2max & härter"),
}

/** Zone-like category of a workout from its dominant intensity. */
private fun category(w: Workout, ftp: Int): Filter? = when (val p = w.dominantPercent(ftp)) {
    0 -> if (w.blocks.any { it is app.rollentrainer.core.model.Block.FreeRide }) Filter.HARD else Filter.BASE
    in 1..75 -> Filter.BASE
    in 76..105 -> Filter.SWEET
    else -> if (p > 0) Filter.HARD else null
}

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
    var filter by rememberSaveable { mutableStateOf(Filter.ALL) }

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
    val shown = remember(workouts, query, sort, filter, favorites, ftp) {
        workouts
            .filter { w ->
                val q = query.trim().lowercase()
                (q.isEmpty() || w.name.lowercase().contains(q) || w.description.lowercase().contains(q)) && when (filter) {
                    Filter.ALL -> true
                    Filter.FAVORITES -> w.id in favorites
                    Filter.OWN -> !w.builtIn
                    Filter.TESTS -> w.test != TestKind.NONE
                    Filter.SHORT -> w.durationSec() <= 45 * 60
                    Filter.BASE, Filter.SWEET, Filter.HARD -> category(w, ftp) == filter
                }
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

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 320.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Trainings", style = MaterialTheme.typography.headlineLarge)
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
                Spacer(Modifier.height(16.dp))
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
                Spacer(Modifier.height(16.dp))
                OutlinedTextField(
                    query, { query = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Trainings suchen …") },
                    leadingIcon = { Icon(Icons.Outlined.Search, null) },
                )
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Filter.entries.forEach { f ->
                        FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.title) })
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Sortieren:", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Sort.entries.forEach { o ->
                            FilterChip(selected = sort == o, onClick = { sort = o }, label = { Text(o.title) })
                        }
                    }
                }
                if (shown.isEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Kein Training passt zu Suche und Filter.", color = MaterialTheme.colorScheme.onSurfaceVariant)
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

@Composable
private fun Badge(text: String, color: Color) {
    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.18f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

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
        Row(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
