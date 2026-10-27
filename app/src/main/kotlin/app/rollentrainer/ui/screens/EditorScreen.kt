package app.rollentrainer.ui.screens

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.Button
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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.core.metrics.PowerZone
import app.rollentrainer.core.model.Block
import app.rollentrainer.core.model.Cue
import app.rollentrainer.core.model.Power
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.PowerUnit
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.zwo.Zwo
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stat
import app.rollentrainer.ui.components.Stepper
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.components.formatMinutes
import app.rollentrainer.ui.theme.zoneColor
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.roundToInt

private fun newId() = UUID.randomUUID().toString()

/** Builds a workout from blocks. Built-in workouts are copied before editing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditorScreen(container: AppContainer, workoutId: String?, copy: Boolean, onDone: () -> Unit) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val ftp = settings.ftp
    val original = remember(workoutId) { workoutId?.let { container.workouts.find(it) } }
    val id = remember { if (original == null || copy) newId() else original.id }
    var name by remember { mutableStateOf(original?.let { if (copy) "${it.name} (Kopie)" else it.name } ?: "Neues Training") }
    var description by remember { mutableStateOf(original?.description.orEmpty()) }
    var test by remember { mutableStateOf(original?.test ?: TestKind.NONE) }
    val blocks = remember {
        mutableStateListOf<Block>().apply {
            addAll(original?.blocks ?: listOf(
                Block.Ramp(newId(), 600, Power.pct(45), Power.pct(70), "Einfahren"),
                Block.Intervals(newId(), 4, 300, Power.pct(100), 180, Power.pct(55)),
                Block.Ramp(newId(), 480, Power.pct(65), Power.pct(40), "Ausfahren"),
            ))
        }
    }
    var confirmDelete by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val workout = Workout(id, name.ifBlank { "Training" }, description, blocks.toList(), test = test)
    val timeline = remember(workout) { Timeline(workout) }

    fun save() = scope.launch {
        container.workouts.save(workout)
        onDone()
    }

    fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/xml"
            putExtra(Intent.EXTRA_SUBJECT, "${workout.name}.zwo")
            putExtra(Intent.EXTRA_TEXT, Zwo.write(workout, ftp))
        }
        context.startActivity(Intent.createChooser(intent, "Training teilen"))
    }

    val overview: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Beschreibung") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            Panel {
                WorkoutProfile(timeline, ftp, Modifier.fillMaxWidth().height(120.dp))
                Spacer(Modifier.height(16.dp))
                val (intensity, tss) = timeline.plannedStress(ftp)
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Stat("Dauer", formatMinutes(timeline.totalSec))
                    Stat("IF", "%.2f".format(intensity))
                    Stat("TSS", tss.roundToInt().toString())
                    Stat("FTP", "$ftp W")
                }
            }
            AddBlockButtons { blocks.add(it) }
            Column {
                Text("Auswertung als FTP-Test", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = test == TestKind.NONE, onClick = { test = TestKind.NONE }, label = { Text("Kein Test") })
                    FilterChip(selected = test == TestKind.RAMP, onClick = { test = TestKind.RAMP }, label = { Text("Rampentest (75 % der besten Minute)") })
                    FilterChip(selected = test == TestKind.TWENTY_MIN, onClick = { test = TestKind.TWENTY_MIN }, label = { Text("20-min-Test (95 %)") })
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { save() }, enabled = blocks.isNotEmpty()) { Text("Speichern") }
                OutlinedButton(onClick = { share() }) {
                    Icon(Icons.Outlined.Share, null)
                    Spacer(Modifier.width(8.dp))
                    Text(".zwo teilen")
                }
                if (original != null && !copy) {
                    TextButton(onClick = { confirmDelete = true }) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }

    val blockItems: LazyListScope.() -> Unit = {
        itemsIndexed(blocks, key = { _, b -> b.id }) { index, block ->
            BlockCard(
                block = block,
                ftp = ftp,
                onChange = { blocks[index] = it },
                onMove = { delta ->
                    val target = index + delta
                    if (target in blocks.indices) blocks.add(target, blocks.removeAt(index))
                },
                onDuplicate = { blocks.add(index + 1, copyWithNewId(block)) },
                onDelete = { blocks.removeAt(index) },
                canUp = index > 0,
                canDown = index < blocks.lastIndex,
            )
        }
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 8.dp, top = 8.dp, end = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text(if (original == null || copy) "Training erstellen" else "Training bearbeiten", style = MaterialTheme.typography.headlineMedium)
        }
        if (LocalWide.current) {
            Row(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Box(Modifier.width(420.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(vertical = 16.dp)) { overview() }
                LazyColumn(
                    Modifier.weight(1f).fillMaxHeight(),
                    contentPadding = PaddingValues(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    content = blockItems,
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { overview() }
                blockItems()
            }
        }
    }

    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Training löschen?") },
            confirmButton = {
                Button(onClick = {
                    scope.launch {
                        container.workouts.delete(id)
                        onDone()
                    }
                }) { Text("Löschen") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Abbrechen") } },
        )
    }
}

private fun copyWithNewId(b: Block): Block = when (b) {
    is Block.Steady -> b.copy(id = newId())
    is Block.Ramp -> b.copy(id = newId())
    is Block.Intervals -> b.copy(id = newId())
    is Block.FreeRide -> b.copy(id = newId())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddBlockButtons(onAdd: (Block) -> Unit) {
    Column {
        Text("Block hinzufügen", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAdd(Block.Steady(newId(), 300, Power.pct(65))) }) { Text("Konstant") }
            OutlinedButton(onClick = { onAdd(Block.Intervals(newId(), 5, 60, Power.pct(120), 60, Power.pct(50))) }) { Text("Intervalle") }
            OutlinedButton(onClick = { onAdd(Block.Ramp(newId(), 600, Power.pct(45), Power.pct(75), "Einfahren")) }) { Text("Rampe hoch") }
            OutlinedButton(onClick = { onAdd(Block.Ramp(newId(), 480, Power.pct(65), Power.pct(40), "Ausfahren")) }) { Text("Rampe runter") }
            OutlinedButton(onClick = { onAdd(Block.FreeRide(newId(), 60, "Frei", Power.pct(100))) }) { Text("Frei (ERG aus)") }
        }
        Text(
            "Frei: Der Trainer schaltet ERG ab und simuliert eine flache Straße – für Sprints und Tests, bei denen du die Leistung selbst wählst.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

/** Duration steps grow with the length, so long blocks don't need many taps. */
private fun stepDuration(sec: Int, up: Boolean): Int {
    val step = when {
        sec < 120 || (sec == 120 && !up) -> 10
        sec < 600 || (sec == 600 && !up) -> 30
        else -> 60
    }
    return (if (up) sec + step else sec - step).coerceIn(10, 4 * 3600)
}

private fun stepPower(p: Power, up: Boolean): Power {
    val step = 5
    return p.copy(value = (p.value + if (up) step else -step).coerceIn(0, if (p.unit == PowerUnit.WATTS) 2000 else 300))
}

private fun toggleUnit(p: Power, ftp: Int): Power = when (p.unit) {
    PowerUnit.PERCENT_FTP -> Power.watts(p.watts(ftp))
    PowerUnit.WATTS -> Power.pct(p.percentOfFtp(ftp).roundToInt())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCard(
    block: Block,
    ftp: Int,
    onChange: (Block) -> Unit,
    onMove: (Int) -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    canUp: Boolean,
    canDown: Boolean,
) {
    val (title, mainPower) = when (block) {
        is Block.Steady -> "Konstant" to block.power
        is Block.Ramp -> (if (block.to.percentOfFtp(ftp) >= block.from.percentOfFtp(ftp)) "Rampe hoch" else "Rampe runter") to block.to
        is Block.Intervals -> "Intervalle" to block.onPower
        is Block.FreeRide -> "Frei (ERG aus)" to block.guide
    }
    val zone = PowerZone.of(mainPower.percentOfFtp(ftp))
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(width = 6.dp, height = 28.dp).clip(RoundedCornerShape(3.dp)).background(zoneColor(zone)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${formatDuration(block.durationSec())} · ${zone.name} ${zone.label}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = { onMove(-1) }, enabled = canUp) { Icon(Icons.Outlined.KeyboardArrowUp, "Nach oben") }
            IconButton(onClick = { onMove(1) }, enabled = canDown) { Icon(Icons.Outlined.KeyboardArrowDown, "Nach unten") }
            IconButton(onClick = onDuplicate) { Icon(Icons.Outlined.ContentCopy, "Duplizieren") }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.Delete, "Entfernen") }
        }
        Spacer(Modifier.height(12.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when (block) {
                is Block.Steady -> {
                    Stepper(formatDuration(block.durationSec), { onChange(block.copy(durationSec = stepDuration(block.durationSec, false))) },
                        { onChange(block.copy(durationSec = stepDuration(block.durationSec, true))) }, label = "Dauer")
                    PowerStepper("Leistung", block.power, ftp) { onChange(block.copy(power = it)) }
                    CadenceStepper("Trittfrequenz", block.cadence) { onChange(block.copy(cadence = it)) }
                }
                is Block.Ramp -> {
                    Stepper(formatDuration(block.durationSec), { onChange(block.copy(durationSec = stepDuration(block.durationSec, false))) },
                        { onChange(block.copy(durationSec = stepDuration(block.durationSec, true))) }, label = "Dauer")
                    PowerStepper("Von", block.from, ftp) { onChange(block.copy(from = it)) }
                    PowerStepper("Bis", block.to, ftp) { onChange(block.copy(to = it)) }
                    CadenceStepper("Trittfrequenz", block.cadence) { onChange(block.copy(cadence = it)) }
                }
                is Block.FreeRide -> {
                    Stepper(formatDuration(block.durationSec), { onChange(block.copy(durationSec = stepDuration(block.durationSec, false))) },
                        { onChange(block.copy(durationSec = stepDuration(block.durationSec, true))) }, label = "Dauer")
                    PowerStepper("Richtwert (nur Anzeige)", block.guide, ftp) { onChange(block.copy(guide = it)) }
                    CadenceStepper("Trittfrequenz", block.cadence) { onChange(block.copy(cadence = it)) }
                    OutlinedTextField(block.label, { onChange(block.copy(label = it)) }, label = { Text("Name") }, singleLine = true)
                }
                is Block.Intervals -> {
                    Stepper("${block.repeat} ×", { onChange(block.copy(repeat = (block.repeat - 1).coerceAtLeast(1))) },
                        { onChange(block.copy(repeat = (block.repeat + 1).coerceAtMost(50))) }, label = "Wiederholungen")
                    Stepper(formatDuration(block.onSec), { onChange(block.copy(onSec = stepDuration(block.onSec, false))) },
                        { onChange(block.copy(onSec = stepDuration(block.onSec, true))) }, label = "Belastung")
                    PowerStepper("Leistung", block.onPower, ftp) { onChange(block.copy(onPower = it)) }
                    CadenceStepper("Trittfrequenz", block.cadence) { onChange(block.copy(cadence = it)) }
                    Stepper(formatDuration(block.offSec), { onChange(block.copy(offSec = stepDuration(block.offSec, false))) },
                        { onChange(block.copy(offSec = stepDuration(block.offSec, true))) }, label = "Erholung")
                    PowerStepper("Leistung", block.offPower, ftp) { onChange(block.copy(offPower = it)) }
                    CadenceStepper("Trittfr. Erholung", block.offCadence) { onChange(block.copy(offCadence = it)) }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        CueEditor(block.cues, maxSec = if (block is Block.Intervals) block.onSec else block.durationSec()) { onChange(withCues(block, it)) }
    }
}

private fun withCues(b: Block, cues: List<Cue>): Block = when (b) {
    is Block.Steady -> b.copy(cues = cues)
    is Block.Ramp -> b.copy(cues = cues)
    is Block.Intervals -> b.copy(cues = cues)
    is Block.FreeRide -> b.copy(cues = cues)
}

/** Coaching texts with the moment they appear, like Zwift's text events. Intervals show them in the first repetition. */
@Composable
private fun CueEditor(cues: List<Cue>, maxSec: Int, onChange: (List<Cue>) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Hinweise während des Blocks", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        cues.forEachIndexed { i, cue ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Stepper(
                    formatDuration(cue.offsetSec),
                    onMinus = { onChange(cues.toMutableList().also { it[i] = cue.copy(offsetSec = (cue.offsetSec - stepFor(cue.offsetSec)).coerceAtLeast(0)) }.sortedBy { it.offsetSec }) },
                    onPlus = { onChange(cues.toMutableList().also { it[i] = cue.copy(offsetSec = (cue.offsetSec + stepFor(cue.offsetSec)).coerceAtMost((maxSec - 1).coerceAtLeast(0))) }.sortedBy { it.offsetSec }) },
                )
                OutlinedTextField(
                    cue.text, { t -> onChange(cues.toMutableList().also { it[i] = cue.copy(text = t) }) },
                    singleLine = true, modifier = Modifier.weight(1f), placeholder = { Text("z. B. „Ruhig atmen, Trittfrequenz hoch halten“") },
                )
                IconButton(onClick = { onChange(cues.filterIndexed { k, _ -> k != i }) }) { Icon(Icons.Outlined.Delete, "Hinweis entfernen") }
            }
        }
        TextButton(onClick = {
            val next = (cues.maxOfOrNull { it.offsetSec }?.plus(30) ?: 0).coerceAtMost((maxSec - 1).coerceAtLeast(0))
            onChange(cues + Cue(next, ""))
        }) { Text("+ Hinweis") }
    }
}

private fun stepFor(sec: Int) = if (sec < 60) 5 else if (sec < 300) 15 else 30

/** Optional cadence target: off below 50 rpm. */
@Composable
private fun CadenceStepper(label: String, value: Int?, onChange: (Int?) -> Unit) {
    Stepper(
        value?.let { "$it rpm" } ?: "Aus",
        onMinus = { onChange(value?.let { v -> (v - 5).takeIf { it >= 50 } }) },
        onPlus = { onChange(((value ?: 45) + 5).coerceAtMost(130)) },
        label = label,
    )
}

@Composable
private fun PowerStepper(label: String, power: Power, ftp: Int, onChange: (Power) -> Unit) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            FilterChip(
                selected = power.unit == PowerUnit.WATTS,
                onClick = { onChange(toggleUnit(power, ftp)) },
                label = { Text(if (power.unit == PowerUnit.WATTS) "Watt" else "% FTP") },
                modifier = Modifier.height(28.dp),
            )
        }
        val hint = if (power.unit == PowerUnit.PERCENT_FTP) "${power.watts(ftp)} W" else "${power.percentOfFtp(ftp).roundToInt()} %"
        Stepper(power.label(), { onChange(stepPower(power, false)) }, { onChange(stepPower(power, true)) })
        Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 40.dp))
    }
}
