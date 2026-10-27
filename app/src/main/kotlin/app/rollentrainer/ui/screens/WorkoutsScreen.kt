package app.rollentrainer.ui.screens

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatMinutes
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkoutsScreen(container: AppContainer, onEdit: (id: String?, copy: Boolean) -> Unit) {
    val workouts by container.workouts.workouts.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var startCandidate by remember { mutableStateOf<Workout?>(null) }

    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)!!.use { container.workouts.import(it) }
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
                        Text("Zwift-Datei")
                    }
                }
            }
        }
        items(workouts, key = { it.id }) { w ->
            WorkoutCard(
                workout = w,
                ftp = settings.ftp,
                onStart = { start(w) },
                onEdit = { onEdit(w.id, w.builtIn) },
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

private val FREE_RIDE = Workout(id = "free", name = "Freie Fahrt")

@Composable
private fun WorkoutCard(workout: Workout, ftp: Int, onStart: () -> Unit, onEdit: () -> Unit) {
    val timeline = remember(workout) { Timeline(workout) }
    val (intensity, tss) = remember(workout, ftp) { timeline.plannedStress(ftp) }
    Panel(Modifier.fillMaxWidth().clickable(onClick = onStart), padding = 0.dp) {
        WorkoutProfile(timeline, ftp, Modifier.fillMaxWidth().height(96.dp).padding(start = 16.dp, end = 16.dp, top = 16.dp))
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 12.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(workout.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${formatMinutes(workout.durationSec())} · IF ${"%.2f".format(intensity)} · TSS ${tss.roundToInt()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) {
                Icon(if (workout.builtIn) Icons.Outlined.ContentCopy else Icons.Outlined.Edit,
                    contentDescription = if (workout.builtIn) "Kopieren und bearbeiten" else "Bearbeiten")
            }
            IconButton(onClick = onStart) {
                Icon(Icons.Default.PlayArrow, contentDescription = "Starten", tint = MaterialTheme.colorScheme.primary)
            }
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
