package app.rollentrainer.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rollentrainer.data.AppSettings
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.components.CardShape
import app.rollentrainer.ui.components.SectionTitle

/** One configurable field. In edit mode it shows remove and move controls and opens the picker on tap. */
@Composable
fun FieldTile(
    field: DataField,
    value: FieldValue,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    editing: Boolean = false,
    onClick: () -> Unit = {},
    onRemove: () -> Unit = {},
    onMove: ((Int) -> Unit)? = null,
) {
    val border = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, border, CardShape)
            .clickable(enabled = editing, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(value.accent ?: MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(8.dp))
            Text(
                field.label.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (editing) {
                if (onMove != null) {
                    IconButton(onClick = { onMove(-1) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Outlined.ChevronLeft, "Nach vorne") }
                    IconButton(onClick = { onMove(1) }, modifier = Modifier.size(24.dp)) { Icon(Icons.Outlined.ChevronRight, "Nach hinten") }
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, "Entfernen", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value.text,
                style = if (big) MaterialTheme.typography.displayLarge.copy(fontSize = 84.sp, lineHeight = 88.sp)
                else MaterialTheme.typography.displaySmall,
                maxLines = 1,
                color = if (big) value.accent ?: MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
            )
            if (value.unit.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    value.unit, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = if (big) 14.dp else 6.dp),
                )
            }
        }
        if (value.sub != null) {
            Text(value.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The rider's data page: the first field large, the rest in rows of [columns].
 * In edit mode a trailing "+" tile adds a field.
 */
@Composable
fun FieldGrid(
    s: RideState,
    settings: AppSettings,
    editing: Boolean,
    onChange: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val keys = settings.rideFields
    val fields = keys.mapNotNull { DataField.byKey(it) }
    var pickerFor by remember { mutableStateOf<Int?>(null) } // index to replace, or -1 to append
    val columns = settings.rideColumns.coerceIn(2, 4)

    fun replace(index: Int, field: DataField) {
        val list = keys.toMutableList()
        if (index < 0) list += field.key else list[index] = field.key
        onChange(list)
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        fields.firstOrNull()?.let { f ->
            FieldTile(
                f, f.value(s, settings), Modifier.fillMaxWidth(), big = true, editing = editing,
                onClick = { pickerFor = 0 },
                onRemove = { onChange(keys.drop(1)) },
            )
        }
        val rest = fields.drop(1)
        val slots = rest.size + if (editing) 1 else 0
        val rows = (slots + columns - 1) / columns
        for (r in 0 until rows) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for (c in 0 until columns) {
                    val i = r * columns + c
                    when {
                        i < rest.size -> {
                            val f = rest[i]
                            val index = i + 1
                            FieldTile(
                                f, f.value(s, settings), Modifier.weight(1f).heightIn(min = 92.dp), editing = editing,
                                onClick = { pickerFor = index },
                                onRemove = { onChange(keys.filterIndexed { k, _ -> k != index }) },
                                onMove = { delta ->
                                    val t = index + delta
                                    if (t in 1 until keys.size) {
                                        val l = keys.toMutableList(); l.add(t, l.removeAt(index)); onChange(l)
                                    }
                                },
                            )
                        }
                        i == rest.size && editing -> Box(
                            Modifier.weight(1f).heightIn(min = 92.dp).clip(CardShape)
                                .border(1.dp, MaterialTheme.colorScheme.primary, CardShape)
                                .clickable { pickerFor = -1 },
                            contentAlignment = Alignment.Center,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(Modifier.width(6.dp))
                                Text("Feld", color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        else -> Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
        if (fields.isEmpty() && !editing) {
            Text("Keine Datenfelder. Tippe auf „Anpassen“, um Felder hinzuzufügen.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    pickerFor?.let { index ->
        FieldPickerDialog(
            current = keys.getOrNull(index)?.let { DataField.byKey(it) },
            onPick = { replace(index, it); pickerFor = null },
            onDismiss = { pickerFor = null },
        )
    }
}

/** All fields grouped like a Garmin "Datenfeld wählen" list. */
@Composable
fun FieldPickerDialog(current: DataField?, onPick: (DataField) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Feld hinzufügen" else "Feld ersetzen") },
        text = {
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                FieldGroup.entries.forEach { group ->
                    item(key = "g${group.name}") {
                        Text(group.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                    }
                    DataField.entries.filter { it.group == group }.forEach { f ->
                        item(key = f.key) {
                            val selected = f == current
                            Row(
                                Modifier.fillMaxWidth().clip(CardShape)
                                    .background(if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                                    .clickable { onPick(f) }.padding(horizontal = 8.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(f.label, style = MaterialTheme.typography.bodyLarge)
                                    Text(f.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Layout options shown while editing: presets, columns, charts and profile toggles. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutOptions(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier) {
        SectionTitle("Vorlagen")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = settings.rideFields == DataField.PRESET_MINIMAL, onClick = { update { it.copy(rideFields = DataField.PRESET_MINIMAL, rideColumns = 3) } }, label = { Text("Minimal") })
            FilterChip(selected = settings.rideFields == DataField.PRESET_STANDARD, onClick = { update { it.copy(rideFields = DataField.PRESET_STANDARD, rideColumns = 3) } }, label = { Text("Standard") })
            FilterChip(selected = settings.rideFields == DataField.PRESET_FULL, onClick = { update { it.copy(rideFields = DataField.PRESET_FULL, rideColumns = 4) } }, label = { Text("Alles") })
        }
        SectionTitle("Spalten")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            (2..4).forEach { n ->
                FilterChip(selected = settings.rideColumns == n, onClick = { update { it.copy(rideColumns = n) } }, label = { Text("$n") })
            }
        }
        SectionTitle("Anzeigen")
        ToggleRow("Trainingsprofil", settings.rideShowProfile) { v -> update { it.copy(rideShowProfile = v) } }
        ToggleRow("Verlaufsgraphen", settings.rideShowCharts) { v -> update { it.copy(rideShowCharts = v) } }
        if (settings.rideShowCharts) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
                listOf("power" to "Leistung", "hr" to "Herzfrequenz", "cadence" to "Trittfrequenz").forEach { (k, label) ->
                    val on = k in settings.rideCharts
                    FilterChip(selected = on, onClick = {
                        update { s -> s.copy(rideCharts = if (on) s.rideCharts - k else s.rideCharts + k) }
                    }, label = { Text(label) })
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked, onChange)
    }
}
