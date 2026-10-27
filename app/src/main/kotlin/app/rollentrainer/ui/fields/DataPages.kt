package app.rollentrainer.ui.fields

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.DataPage
import app.rollentrainer.data.FontScale
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.components.CardShape
import app.rollentrainer.ui.components.SectionTitle
import kotlinx.coroutines.launch

/**
 * Fixed grids like the data pages of a bike computer: every page has one layout and one field per
 * slot. [rows] lists how many tiles each row holds; a row with one tile is the hero row.
 */
enum class PageLayout(val key: String, val title: String, val rows: List<Int>) {
    ONE("one", "1 Feld", listOf(1)),
    HERO3("hero3", "1 + 2", listOf(1, 2)),
    HERO5("hero5", "1 + 4", listOf(1, 2, 2)),
    HERO7("hero7", "1 + 6", listOf(1, 3, 3)),
    HERO9("hero9", "1 + 8", listOf(1, 4, 4)),
    TWO6("two6", "2 + 6", listOf(2, 3, 3)),
    GRID8("grid8", "2 × 4", listOf(2, 2, 2, 2)),
    GRID12("grid12", "3 × 4", listOf(3, 3, 3, 3));

    val slots: Int get() = rows.sum()

    companion object {
        fun byKey(key: String) = entries.firstOrNull { it.key == key } ?: HERO7
    }
}

object PageDefaults {
    val WORKOUT = listOf(
        DataPage("hero7", listOf("power", "target", "segRemaining", "hr", "cadence", "nextSeg", "workoutRemaining")),
        DataPage("grid12", listOf("powerLap", "np", "if", "tss", "hrAvg", "hrZone", "cadenceAvg", "work", "wkg", "accuracy", "progress", "clock")),
    )
    val FREE = listOf(
        DataPage("hero5", listOf("power", "target", "hr", "cadence", "rideTime")),
        DataPage("grid8", listOf("powerAvg", "np", "tss", "work", "hrAvg", "cadenceAvg", "distance", "calories")),
    )

    /** Builds pages from the older flat field list, so nobody loses their layout. */
    fun migrate(fields: List<String>): List<DataPage> {
        if (fields.isEmpty()) return WORKOUT
        val first = when {
            fields.size <= 1 -> PageLayout.ONE
            fields.size <= 3 -> PageLayout.HERO3
            fields.size <= 5 -> PageLayout.HERO5
            fields.size <= 7 -> PageLayout.HERO7
            else -> PageLayout.HERO9
        }
        val pages = mutableListOf(DataPage(first.key, fields.take(first.slots).padTo(first.slots)))
        val rest = fields.drop(first.slots)
        if (rest.isNotEmpty()) pages += DataPage(PageLayout.GRID12.key, rest.take(12).padTo(12))
        return pages
    }

    private fun List<String>.padTo(n: Int) = this + List((n - size).coerceAtLeast(0)) { "" }
}

/** The pages for a workout or a free ride, falling back to defaults (or the migrated old layout). */
fun AppSettings.pages(free: Boolean): List<DataPage> = if (free) {
    freePages.ifEmpty { PageDefaults.FREE }
} else {
    ridePages.ifEmpty { if (rideFields == AppSettings().rideFields) PageDefaults.WORKOUT else PageDefaults.migrate(rideFields) }
}

/** One field in a page. Text size follows the number of tiles in the row and the font scale. */
@Composable
fun FieldTile(
    field: DataField?,
    value: FieldValue?,
    perRow: Int,
    scale: Float,
    modifier: Modifier = Modifier,
    editing: Boolean = false,
    onClick: () -> Unit = {},
) {
    val border = if (editing) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val base = when (perRow) {
        1 -> 92f
        2 -> 58f
        3 -> 42f
        else -> 32f
    } * scale
    Column(
        modifier
            .clip(CardShape)
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, border, CardShape)
            .clickable(enabled = editing, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        if (field == null || value == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                if (editing) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Add, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(6.dp))
                        Text("Feld", color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            return@Column
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(value.accent ?: MaterialTheme.colorScheme.outline))
            Spacer(Modifier.width(8.dp))
            Text(
                field.shortLabel.uppercase(), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        // The value shrinks to the tile's width (digits are about 0.58 em wide), so "1:29:59" or "245 W" never gets cut off.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val units = value.text.length * 0.58f + if (value.unit.isEmpty()) 0f else 0.1f + value.unit.length * 0.28f * 0.6f
            val size = minOf(base, maxWidth.value / units).coerceAtLeast(14f)
            val unitSize = (size * 0.28f).coerceAtLeast(12f)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    value.text,
                    fontSize = size.sp,
                    lineHeight = (size * 1.05f).sp,
                    style = MaterialTheme.typography.displayLarge,
                    maxLines = 1,
                    softWrap = false,
                    color = if (perRow == 1) value.accent ?: MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface,
                )
                if (value.unit.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        value.unit, fontSize = unitSize.sp, lineHeight = (unitSize * 1.2f).sp,
                        style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = (size * 0.12f).dp),
                    )
                }
            }
        }
        if (value.sub != null) {
            Text(value.sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            Spacer(Modifier.height(2.dp))
        }
    }
}

/** One page: the layout's rows, each tile bound to a field key. */
@Composable
private fun PageGrid(
    page: DataPage,
    s: RideState,
    settings: AppSettings,
    fill: Boolean,
    editing: Boolean,
    onSlot: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val layout = PageLayout.byKey(page.layout)
    val scale = settings.rideFontScale.factor
    Column(modifier, verticalArrangement = Arrangement.spacedBy(ROW_GAP)) {
        var slot = 0
        for (perRow in layout.rows) {
            val rowWeight = when (perRow) { 1 -> 1.9f; 2 -> 1.3f; else -> 1f }
            val rowModifier = if (fill) Modifier.fillMaxWidth().weight(rowWeight) else Modifier.fillMaxWidth().height(rowHeight(perRow, scale))
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (c in 0 until perRow) {
                    val index = slot++
                    val key = page.fields.getOrNull(index).orEmpty()
                    val field = DataField.byKey(key)
                    FieldTile(
                        field, field?.value(s, settings), perRow, scale,
                        Modifier.weight(1f).fillMaxHeight(), editing = editing, onClick = { onSlot(index) },
                    )
                }
            }
        }
    }
}

private val ROW_GAP = 8.dp

/** Height of one tile row in the scrolling (phone) layout; tablets fill the available height instead. */
private fun rowHeight(perRow: Int, scale: Float) = when (perRow) { 1 -> 168.dp; 2 -> 118.dp; else -> 96.dp } * scale

/** Height of a whole page in the scrolling layout, so that the pager keeps one height across pages. */
private fun pageHeight(layout: PageLayout, scale: Float) =
    layout.rows.fold(0.dp) { acc, perRow -> acc + rowHeight(perRow, scale) } + ROW_GAP * (layout.rows.size - 1)

/**
 * The swipeable data pages with dots below. In edit mode a toolbar changes the layout, adds or
 * removes pages, and tapping a slot picks its field.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DataPages(
    s: RideState,
    settings: AppSettings,
    pages: List<DataPage>,
    editing: Boolean,
    fill: Boolean,
    onChange: (List<DataPage>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })
    LaunchedEffect(pages.size) {
        if (pagerState.currentPage >= pages.size && pages.isNotEmpty()) pagerState.scrollToPage(pages.size - 1)
    }
    var picker by remember { mutableStateOf<Pair<Int, Int>?>(null) } // page, slot
    val scope = rememberCoroutineScope()

    /** Reorders the pages and keeps the pager on the page that moved, so the toolbar keeps editing it. */
    fun move(from: Int, to: Int) {
        onChange(pages.toMutableList().also { it.add(to, it.removeAt(from)) })
        scope.launch { pagerState.scrollToPage(to) }
    }

    fun setPage(index: Int, page: DataPage) = onChange(pages.toMutableList().also { it[index] = page })

    Column(modifier) {
        if (editing) {
            val current = pagerState.currentPage.coerceIn(0, pages.lastIndex.coerceAtLeast(0))
            val page = pages.getOrNull(current)
            Column(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seite ${current + 1} von ${pages.size}", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (current > 0) move(current, current - 1)
                    }, enabled = current > 0) { Icon(Icons.Outlined.ChevronLeft, "Seite nach links verschieben") }
                    IconButton(onClick = {
                        if (current < pages.lastIndex) move(current, current + 1)
                    }, enabled = current < pages.lastIndex) { Icon(Icons.Outlined.ChevronRight, "Seite nach rechts verschieben") }
                    IconButton(onClick = { if (pages.size > 1) onChange(pages.filterIndexed { i, _ -> i != current }) }, enabled = pages.size > 1) {
                        Icon(Icons.Outlined.Delete, "Seite löschen", tint = MaterialTheme.colorScheme.error)
                    }
                    OutlinedButton(onClick = {
                        if (pages.size < 6) onChange(pages + DataPage(PageLayout.HERO5.key, List(PageLayout.HERO5.slots) { "" }))
                    }, enabled = pages.size < 6) {
                        Icon(Icons.Default.Add, null)
                        Spacer(Modifier.width(4.dp))
                        Text("Seite")
                    }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PageLayout.entries.forEach { l ->
                        FilterChip(
                            selected = page?.layout == l.key,
                            onClick = {
                                if (page != null) {
                                    val kept = page.fields.filter { it.isNotEmpty() }.take(l.slots)
                                    setPage(current, DataPage(l.key, kept + List(l.slots - kept.size) { "" }))
                                }
                            },
                            label = { Text(l.title) },
                        )
                    }
                }
            }
        }
        // In the scrolling layout every page gets the height of the tallest one, so the charts below don't jump while swiping.
        val scale = settings.rideFontScale.factor
        val fixedHeight = pages.maxOfOrNull { pageHeight(PageLayout.byKey(it.layout), scale) } ?: pageHeight(PageLayout.HERO7, scale)
        HorizontalPager(
            state = pagerState,
            modifier = if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth().height(fixedHeight),
            pageSpacing = 16.dp,
            userScrollEnabled = pages.size > 1,
        ) { index ->
            val page = pages.getOrNull(index) ?: return@HorizontalPager
            PageGrid(page, s, settings, fill, editing, onSlot = { slot -> picker = index to slot }, Modifier.fillMaxSize())
        }
        if (pages.size > 1) {
            Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.Center) {
                repeat(pages.size) { i ->
                    val active = i == pagerState.currentPage
                    Box(
                        Modifier.padding(horizontal = 3.dp).size(if (active) 8.dp else 6.dp).clip(CircleShape)
                            .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                    )
                }
            }
        }
    }

    picker?.let { (pageIndex, slot) ->
        val page = pages.getOrNull(pageIndex)
        if (page == null) { picker = null; return@let }
        FieldPickerDialog(
            current = page.fields.getOrNull(slot)?.let { DataField.byKey(it) },
            onPick = { f ->
                val fields = page.fields.toMutableList()
                while (fields.size <= slot) fields += ""
                fields[slot] = f?.key.orEmpty()
                setPage(pageIndex, page.copy(fields = fields))
                picker = null
            },
            onDismiss = { picker = null },
        )
    }
}

/** All fields grouped like a Garmin "Datenfeld wählen" list; null picks an empty slot. */
@Composable
fun FieldPickerDialog(current: DataField?, onPick: (DataField?) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (current == null) "Feld wählen" else "Feld ersetzen") },
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
        confirmButton = { if (current != null) TextButton(onClick = { onPick(null) }) { Text("Feld leeren") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

/** Options for the ride screen apart from the pages: text size, profile, gauge, charts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayoutOptions(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit, modifier: Modifier = Modifier, showReset: Boolean = true) {
    Column(modifier) {
        SectionTitle("Schriftgröße")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FontScale.entries.forEach { f ->
                FilterChip(selected = settings.rideFontScale == f, onClick = { update { it.copy(rideFontScale = f) } }, label = { Text(f.title) })
            }
        }
        SectionTitle("Anzeigen")
        ToggleRow("Trainingsprofil", settings.rideShowProfile) { v -> update { it.copy(rideShowProfile = v) } }
        ToggleRow("Zielbalken (Leistung zu Ziel)", settings.rideShowGauge) { v -> update { it.copy(rideShowGauge = v) } }
        ToggleRow("Verlaufsgraphen", settings.rideShowCharts) { v -> update { it.copy(rideShowCharts = v) } }
        if (settings.rideShowCharts) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 16.dp)) {
                listOf("power" to "Leistung", "hr" to "Herzfrequenz", "cadence" to "Trittfrequenz", "wbal" to "W'-Bilanz").forEach { (k, label) ->
                    val on = k in settings.rideCharts
                    FilterChip(selected = on, onClick = {
                        update { s -> s.copy(rideCharts = if (on) s.rideCharts - k else s.rideCharts + k) }
                    }, label = { Text(label) })
                }
            }
        }
        if (showReset) {
            SectionTitle("Datenseiten")
            Text(
                "Die Seiten stellst du im Trainingsbildschirm über „Anpassen“ zusammen: Raster wählen, Felder antippen, nach links wischen für die nächste Seite.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = { update { it.copy(ridePages = emptyList(), freePages = emptyList(), rideFields = AppSettings().rideFields) } }) {
                Text("Datenseiten zurücksetzen")
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
