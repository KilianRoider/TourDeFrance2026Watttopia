package app.rollentrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.ble.SensorStatus
import app.rollentrainer.core.engine.EngineState
import app.rollentrainer.core.engine.ErgState
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.Banner
import app.rollentrainer.ui.components.CardShape
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.LineChart
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stepper
import app.rollentrainer.ui.components.TargetGauge
import app.rollentrainer.ui.components.WorkoutProfile
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.fields.DataPages
import app.rollentrainer.ui.fields.LayoutOptions
import app.rollentrainer.ui.fields.pages
import app.rollentrainer.ui.theme.Metric
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

@Composable
fun RideScreen(
    container: AppContainer,
    onSpinningView: (() -> Unit)? = null,
    askEndFirst: Boolean = false,
    /** The end dialog was closed without ending (only reported for a dialog opened via [askEndFirst]). */
    onEndCancelled: () -> Unit = {},
    onFinished: (rideId: String?) -> Unit,
) {
    val state by container.session.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val s = state ?: return
    // Saveable, so turning the tablet keeps an open end dialog, the rating and the note.
    var askEnd by rememberSaveable { mutableStateOf(askEndFirst) }
    var askRpe by rememberSaveable { mutableStateOf(false) }
    var finishSeen by rememberSaveable { mutableStateOf(false) }
    var fullHistory by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    val update: ((AppSettings) -> AppSettings) -> Unit = { container.settings.update(it) }
    val free = s.timeline == null
    val pages = settings.pages(free)
    val onPages: (List<app.rollentrainer.data.DataPage>) -> Unit = { p -> update { if (free) it.copy(freePages = p) else it.copy(ridePages = p) } }

    // Runs in the app scope: the screen leaves the composition while the ride is being saved, which would
    // cancel a screen-bound coroutine before the report is opened and the Strava upload is started.
    fun end(save: Boolean, rpe: Int? = null, notes: String = "") = container.scope.launch {
        askEnd = false
        askRpe = false
        val ride = container.session.finish(save, rpe, notes)
        onFinished(ride?.id)
    }

    val trainer = status[SensorRole.TRAINER] ?: SensorStatus(null)
    // The pause comes first: with or without a trainer the rider must see that the clock stands still.
    val beforeStart = s.engine.workoutSec == 0.0 && s.engine.paused
    val banner = when {
        s.engine.finished -> null
        s.engine.userPaused -> "Pausiert – tippe auf Weiter."
        s.engine.autoPaused -> "Pausiert – tritt weiter, um fortzusetzen."
        trainer.sensor == null -> if (beforeStart) "Kein Smart-Trainer gekoppelt. Tritt in die Pedale, um zu starten."
        else "Kein Smart-Trainer gekoppelt. Die Leistung wird nicht vorgegeben."
        trainer.state != ConnectionState.CONNECTED -> "Verbinde mit ${trainer.sensor?.name.orEmpty()} …"
        !s.trainerControl -> "Warte auf Steuerung durch den Trainer …"
        beforeStart -> "Tritt in die Pedale, um zu starten."
        s.engine.paused -> "Pausiert"
        else -> null
    }

    val wide = LocalWide.current
    val window = if (fullHistory) Int.MAX_VALUE else 600
    val shown = s.samples.takeLast(window)
    val chartKinds = settings.rideCharts
    val wbalSeries = remember(s.samples.size, settings.ftp, settings.wPrimeKj, "wbal" in chartKinds) {
        if ("wbal" in chartKinds) Analysis.wBalSeries(s.samples, settings.ftp, settings.wPrimeKj * 1000) else DoubleArray(0)
    }

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
        if ("wbal" in chartKinds) LineChart(
            "W'-Bilanz", wbalSeries.takeLast(shown.size).map { (it / 1000).toFloat().coerceAtLeast(0f) }, Metric.Target, m,
            unit = "kJ", current = "%.1f".format(s.wBalJ / 1000), minRange = (s.wPrimeJ / 1000f),
        )
    }
    val showCharts = settings.rideShowCharts && chartKinds.isNotEmpty()

    val sidePanel: @Composable ColumnScope.() -> Unit = {
        if (editing) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                Text(
                    "Raster wählen, Felder antippen, Seiten hinzufügen. Jede Seite ist ein festes Raster wie beim Radcomputer.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LayoutOptions(settings, update, showReset = false)
            }
        } else if (showCharts) {
            ChartToggle(fullHistory) { fullHistory = it }
            Spacer(Modifier.height(8.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                charts(Modifier.fillMaxWidth().weight(1f))
            }
        }
    }

    val gauge: @Composable () -> Unit = {
        if (settings.rideShowGauge && s.engine.erg && s.engine.targetWatts != null) TargetGauge(s.displayPower, s.engine.targetWatts)
    }
    val pagesBlock: @Composable ColumnScope.(Boolean) -> Unit = { fill ->
        Box(if (fill) Modifier.fillMaxWidth().weight(1f) else Modifier.fillMaxWidth()) {
            DataPages(s, settings, pages, editing, fill, onPages, Modifier.fillMaxSize())
            if (!editing) CountdownOverlay(s)
        }
    }
    val fieldsBlock: @Composable ColumnScope.(Boolean) -> Unit = { fill ->
        gauge()
        if (!editing) NextPreview(s)
        pagesBlock(fill)
    }
    val banners: @Composable () -> Unit = {
        if (editing) {
            Banner("Tippe auf ein Feld, um es zu wählen oder zu ersetzen. Oben wählst du das Raster und legst weitere Seiten an.")
        } else {
            banner?.let { Banner(it) }
            if (s.engine.ergState == ErgState.RELEASED || s.engine.ergState == ErgState.RE_ENGAGING) {
                ActionBanner(
                    if (s.engine.ergState == ErgState.RELEASED) "ERG gelöst – tritt wieder an, das Ziel kommt von selbst zurück." else "ERG steigt wieder auf das Ziel …",
                    action = "Sofort volles Ziel",
                ) { container.session.reengage() }
            }
            s.engine.cue?.let { Banner("💬 $it", color = MaterialTheme.colorScheme.surfaceContainerHigh) }
        }
    }
    val profile: @Composable (Dp) -> Unit = { h ->
        s.timeline?.takeIf { settings.rideShowProfile }?.let { tl ->
            WorkoutProfile(
                tl, settings.ftp, Modifier.fillMaxWidth().height(h),
                progressSec = s.engine.workoutSec, samples = s.samples, intensityPct = s.engine.intensityPct,
            )
        }
    }
    val belowFields: @Composable () -> Unit = {
        if (editing) {
            Panel { LayoutOptions(settings, update, showReset = false) }
        } else if (showCharts) {
            Panel {
                ChartToggle(fullHistory) { fullHistory = it }
                Spacer(Modifier.height(8.dp))
                charts(Modifier.fillMaxWidth().height(if (wide) 130.dp else 110.dp).padding(bottom = 8.dp))
            }
        }
    }

    // The layout follows the space the ride really gets. A phone in landscape counts as "wide" for the
    // app (≥ 720 dp) but is only about 360 dp tall, and a phone in portrait is narrow: both get their
    // own arrangement so that the data fields keep most of the screen.
    BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        // Landscape by shape, not by width: a 640 × 360 dp phone is under 600 dp wide once the
        // navigation bar is taken off, and stacking everything there leaves no room for the fields.
        val landscapePhone = maxHeight < 480.dp && maxWidth > maxHeight * 1.3f
        val narrow = maxWidth < 600.dp
        val sideWidth = (maxWidth * 0.4f).coerceIn(240.dp, 360.dp)
        when {
            landscapePhone -> Column(
                Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Phone in landscape: the fields take the full height on the left; everything else
                // scrolls in a column on the right, the target bar first so it is always in view.
                Header(s, status, editing, compact = true, onToggleEdit = { editing = !editing }, onSpinningView = onSpinningView)
                // While editing, the toolbar of the pages (page row and layout chips) takes half of the
                // low height and filling would squeeze the tiles to slivers: the pages keep their
                // fixed row heights instead and the column scrolls. One Column for both, so the pager
                // stays on its page when editing starts or ends.
                val pagesScroll = rememberScrollState()
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(
                        if (editing) Modifier.weight(1f).fillMaxHeight().verticalScroll(pagesScroll)
                        else Modifier.weight(1f).fillMaxHeight(),
                    ) { pagesBlock(!editing) }
                    Column(
                        Modifier.width(sideWidth).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        gauge()
                        if (!editing) NextPreview(s)
                        banners()
                        profile(52.dp)
                        Controls(container, s, settings, compact = true, onEnd = { askEnd = true })
                        belowFields()
                    }
                }
            }
            narrow -> Column(
                Modifier.fillMaxSize().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Phone in portrait: fields and charts scroll, the controls stay at the bottom.
                Header(s, status, editing, compact = true, onToggleEdit = { editing = !editing }, onSpinningView = onSpinningView)
                banners()
                profile(56.dp)
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    fieldsBlock(false)
                    belowFields()
                }
                Controls(container, s, settings, compact = true, onEnd = { askEnd = true })
            }
            else -> Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Header(s, status, editing, compact = false, onToggleEdit = { editing = !editing }, onSpinningView = onSpinningView)
                banners()
                profile(if (wide) 88.dp else 64.dp)
                if (wide) {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(
                            Modifier.weight(if (showCharts || editing) 0.5f else 1f).fillMaxHeight(),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) { fieldsBlock(true) }
                        if (showCharts || editing) {
                            Panel(Modifier.weight(0.5f).fillMaxHeight()) { sidePanel() }
                        }
                    }
                } else {
                    Column(
                        Modifier.weight(1f).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        fieldsBlock(false)
                        belowFields()
                    }
                }
                Controls(container, s, settings, compact = false, onEnd = { askEnd = true })
            }
        }
    }

    val done = s.engine.finished && !finishSeen
    if ((askEnd || done) && !askRpe) {
        AlertDialog(
            onDismissRequest = { if (!done) { askEnd = false; onEndCancelled() } },
            title = { Text(if (done) "Geschafft!" else if (s.workout == null) "Fahrt beenden?" else "Training beenden?") },
            text = {
                Column {
                    if (done) {
                        val st = s.stats
                        Text("${formatDuration(st.seconds)} · ${st.avgPower} W · NP ${st.normalizedPower} W · TSS ${st.tss.roundToInt()}", style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (s.elapsedSec < 30) "Die Fahrt ist kürzer als 30 Sekunden und wird nicht gespeichert."
                        else "Die Fahrt wird gespeichert" +
                            (if (settings.strava != null && settings.stravaAutoUpload) " und zu Strava hochgeladen." else ".")
                    )
                }
            },
            confirmButton = {
                Button(onClick = { if (s.elapsedSec < 30) end(true) else askRpe = true }) { Text(if (s.elapsedSec < 30) "Beenden" else "Beenden und speichern") }
            },
            dismissButton = {
                Row {
                    if (s.elapsedSec >= 30) TextButton(onClick = { end(false) }) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
                    if (done) {
                        TextButton(onClick = {
                            finishSeen = true
                            askEnd = false
                            container.session.continueRiding((settings.ftp * 0.55).roundToInt())
                            // An end that came from the spinning class goes back to the class.
                            onEndCancelled()
                        }) { Text("Weiterfahren") }
                    } else {
                        TextButton(onClick = { askEnd = false; onEndCancelled() }) { Text("Weiterfahren") }
                    }
                }
            },
        )
    }
    if (askRpe) {
        RpeDialog(
            onSave = { rpe, notes -> end(true, rpe, notes) },
            onSkip = { end(true) },
            onDismiss = { askRpe = false; if (s.engine.finished) finishSeen = false },
        )
    }
}

/** Borg CR10 scale as every training platform asks for it after a workout. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RpeDialog(onSave: (Int?, String) -> Unit, onSkip: () -> Unit, onDismiss: () -> Unit) {
    var rpe by rememberSaveable { mutableStateOf<Int?>(null) }
    var notes by rememberSaveable { mutableStateOf("") }
    val labels = mapOf(1 to "sehr leicht", 2 to "leicht", 3 to "locker", 4 to "moderat", 5 to "etwas hart", 6 to "hart", 7 to "sehr hart", 8 to "sehr, sehr hart", 9 to "extrem hart", 10 to "maximal")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Wie anstrengend war es?") },
        text = {
            // Scrolls when the keyboard or a phone in landscape leaves little height.
            Column(Modifier.verticalScroll(rememberScrollState())) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    (1..10).forEach { n ->
                        FilterChip(selected = rpe == n, onClick = { rpe = n }, label = { Text(n.toString()) })
                    }
                }
                Text(
                    rpe?.let { "$it – ${labels[it]}" } ?: "1 = sehr leicht, 10 = maximal",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(notes, { notes = it }, label = { Text("Notiz (optional)") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { Button(onClick = { onSave(rpe, notes) }) { Text("Speichern") } },
        dismissButton = { TextButton(onClick = onSkip) { Text("Ohne Bewertung") } },
    )
}

@Composable
private fun ActionBanner(text: String, action: String, onAction: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

/** "Gleich: …" – what comes next, shown during the last 15 seconds of a segment. */
@Composable
private fun NextPreview(s: RideState) {
    val next = s.engine.nextSegment ?: return
    if (s.engine.paused || s.engine.segmentRemainingSec > 15 || s.engine.continuing) return
    val target = s.engine.nextTargetWatts
    val current = s.engine.targetWatts
    val arrow = when {
        target == null || current == null -> ""
        target > current -> " ▲"
        target < current -> " ▼"
        else -> ""
    }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = CardShape, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("GLEICH", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                buildString {
                    append(next.label.ifBlank { "Abschnitt" })
                    append(" · ").append(formatDuration(next.durationSec))
                    if (!next.erg) append(" · frei") else target?.let { append(" · $it W").append(arrow) }
                    next.cadence?.let { append(" · $it rpm") }
                },
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Big 3-2-1 over the fields right before a segment change. */
@Composable
private fun BoxScope.CountdownOverlay(s: RideState) {
    val rem = s.engine.segmentRemainingSec
    if (s.engine.paused || s.engine.nextSegment == null || rem !in 0..2 || s.engine.continuing) return
    // matchParentSize covers the data pages in the scrolling phone layout too, where the Box has no bounded height.
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(
        Modifier.matchParentSize().background(MaterialTheme.colorScheme.background.copy(alpha = 0.55f), CardShape),
        contentAlignment = Alignment.Center,
    ) {
        // 140 sp on a tablet; over the low field area of a phone in landscape the number shrinks so
        // that it and the label below still fit.
        val numberSp = ((maxHeight.value - 40f * fontScale) / 1.1f / fontScale).coerceIn(40f, 140f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${rem + 1}", fontSize = numberSp.sp, lineHeight = (numberSp * 1.07f).sp, style = MaterialTheme.typography.displayLarge, color = MaterialTheme.colorScheme.primary)
            val next = s.engine.nextSegment
            val target = s.engine.nextTargetWatts
            Text(
                (next?.label?.ifBlank { "Nächster Abschnitt" } ?: "") + (if (next?.erg == false) " · frei" else target?.let { " · $it W" } ?: ""),
                style = if (numberSp < 100f) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun Header(
    s: RideState,
    status: Map<SensorRole, SensorStatus>,
    editing: Boolean,
    compact: Boolean,
    onToggleEdit: () -> Unit,
    onSpinningView: (() -> Unit)? = null,
) {
    // Everything beside the title is measured before it; the sensor names only come along where they
    // leave the title its room (four of them plus two buttons need about 700 dp).
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val sensorNames = maxWidth >= 1000.dp
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.workout?.name ?: "Freie Fahrt",
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val total = s.engine.totalSec
                Text(
                    when {
                        s.engine.continuing -> "Weiterfahren · ${formatDuration(s.elapsedSec)}"
                        total > 0 -> "${formatDuration(s.engine.workoutSec.toInt())} von ${formatDuration(total)}"
                        else -> formatDuration(s.elapsedSec)
                    },
                    style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (compact) {
                // Only the dots on a phone: the sensor names would push the buttons off the screen.
                Row(Modifier.padding(horizontal = 8.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    SensorRole.entries.forEach { role ->
                        val st = status[role]
                        if (st?.sensor != null) ConnectionDot(st.state)
                    }
                }
                if (editing) {
                    Button(onClick = onToggleEdit) {
                        Icon(Icons.Outlined.Check, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Fertig")
                    }
                } else {
                    if (onSpinningView != null) {
                        IconButton(onClick = onSpinningView) { Icon(Icons.Outlined.MusicNote, "Spinning-Ansicht") }
                    }
                    IconButton(onClick = onToggleEdit) { Icon(Icons.Outlined.Tune, "Anzeige anpassen") }
                }
            } else {
                SensorRole.entries.forEach { role ->
                    val st = status[role]
                    if (st?.sensor != null) {
                        Row(Modifier.padding(end = if (sensorNames) 16.dp else 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            ConnectionDot(st.state)
                            if (sensorNames) {
                                Spacer(Modifier.width(6.dp))
                                Text(role.title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                        }
                    }
                }
                if (editing) {
                    Button(onClick = onToggleEdit) {
                        Icon(Icons.Outlined.Check, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Fertig")
                    }
                } else {
                    if (onSpinningView != null) {
                        OutlinedButton(onClick = onSpinningView, modifier = Modifier.padding(end = 8.dp)) {
                            Icon(Icons.Outlined.MusicNote, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Spinning")
                        }
                    }
                    OutlinedButton(onClick = onToggleEdit) {
                        Icon(Icons.Outlined.Tune, null)
                        Spacer(Modifier.width(8.dp))
                        Text("Anpassen")
                    }
                }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(container: AppContainer, s: RideState, settings: AppSettings, compact: Boolean, onEnd: () -> Unit) {
    val session = container.session
    val e: EngineState = s.engine
    val inWorkout = s.timeline != null && !e.continuing && !e.finished
    // "Weiter" only after a pause by hand; before the start and in an auto-pause pedalling resumes.
    val resume = e.userPaused
    if (compact) {
        // On a phone the wrapped row of a tablet would take a third of the screen: the adjustments
        // move into one line that scrolls sideways, pause and end stay in a fixed line below. Below
        // 380 dp the skip buttons join the scrolling line, so "Pause" and "Beenden" keep their words.
        // What must be in view without swiping leads the scrolling line, ahead of extend and
        // intensity: the ramp test's way out (the workout tells the exhausted rider to tap it) and
        // the ERG switch. Outside the ramp test the skip buttons come first; they are narrower than
        // the grade stepper that follows the switch while ERG is off.
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val roomy = maxWidth >= 380.dp
            val ramp = inWorkout && s.workout?.test == TestKind.RAMP
            val steps = inWorkout && !roomy
            val stepButtons: @Composable () -> Unit = {
                FilledTonalIconButton(onClick = { session.back() }) { Icon(Icons.Default.SkipPrevious, "Abschnitt zurück") }
                FilledTonalIconButton(onClick = { session.skip() }) { Icon(Icons.Default.SkipNext, "Überspringen") }
            }
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (ramp) FilledTonalButton(onClick = { session.bail() }) { Text("Ich bin platt") }
                    if (steps && !ramp) stepButtons()
                    ErgControls(s, onErg = { session.setErg(it) }, onGrade = { session.adjustGrade(it) })
                    if (steps && ramp) stepButtons()
                    Adjustments(s, inWorkout, container, compact = true)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { session.setPaused(!resume) },
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(if (resume) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (resume) "Weiter" else "Pause", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    if (inWorkout && roomy) {
                        FilledTonalIconButton(onClick = { session.back() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipPrevious, "Abschnitt zurück") }
                        FilledTonalIconButton(onClick = { session.skip() }, modifier = Modifier.size(52.dp)) { Icon(Icons.Default.SkipNext, "Überspringen") }
                    }
                    OutlinedButton(
                        onClick = onEnd,
                        modifier = Modifier.weight(1f).height(52.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp),
                    ) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Beenden", maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        return
    }
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(onClick = { session.setPaused(!resume) }, modifier = Modifier.height(56.dp)) {
            Icon(if (resume) Icons.Default.PlayArrow else Icons.Default.Pause, null)
            Spacer(Modifier.width(8.dp))
            Text(if (resume) "Weiter" else "Pause")
        }
        if (inWorkout) {
            FilledTonalIconButton(onClick = { session.back() }, modifier = Modifier.height(56.dp)) { Icon(Icons.Default.SkipPrevious, "Abschnitt zurück") }
            FilledTonalIconButton(onClick = { session.skip() }, modifier = Modifier.height(56.dp)) { Icon(Icons.Default.SkipNext, "Überspringen") }
        }
        Adjustments(s, inWorkout, container, compact = false)
        OutlinedButton(onClick = onEnd, modifier = Modifier.height(56.dp)) {
            Icon(Icons.Default.Stop, null)
            Spacer(Modifier.width(8.dp))
            Text("Beenden")
        }
    }
}

/**
 * Extend, intensity or target and, on a tablet, the ramp test's bail button and ERG. Emitted as
 * separate children so that the tablet's FlowRow can wrap between them and the phone's scrolling
 * row lines them up; the phone ([compact]) places bail and ERG itself, where they are in view.
 */
@Composable
private fun Adjustments(s: RideState, inWorkout: Boolean, container: AppContainer, compact: Boolean) {
    val session = container.session
    val e: EngineState = s.engine
    if (inWorkout) {
        OutlinedButton(onClick = { session.extend(60) }) { Text("+1 min") }
        OutlinedButton(onClick = { session.extend(10) }) { Text("+10 s") }
        Stepper("${e.intensityPct} %", { session.adjustIntensity(-1) }, { session.adjustIntensity(1) }, label = "Intensität")
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallChip("−10") { session.adjustIntensity(-10) }
                SmallChip("−5") { session.adjustIntensity(-5) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                SmallChip("+5") { session.adjustIntensity(5) }
                SmallChip("+10") { session.adjustIntensity(10) }
            }
        }
        if (e.intensityPct != 100) TextButton(onClick = { session.setIntensity(100) }) { Text("100 %") }
        if (!compact && s.workout?.test == TestKind.RAMP) {
            Button(onClick = { session.bail() }, colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors()) { Text("Ich bin platt") }
        }
    } else {
        Stepper("${e.targetWatts ?: 0} W", { session.adjustManual(-5) }, { session.adjustManual(5) }, label = "Zielleistung")
        SmallChip("−25") { session.adjustManual(-25) }
        SmallChip("+25") { session.adjustManual(25) }
    }
    if (!compact) ErgControls(s, onErg = { session.setErg(it) }, onGrade = { session.adjustGrade(it) })
}

@Composable
private fun SmallChip(text: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(text) }, modifier = Modifier.height(28.dp))
}

/** ERG on/off; when off, a grade stepper for the simulated road. Free-ride blocks switch ERG off by themselves. */
@Composable
private fun ErgControls(s: RideState, onErg: (Boolean) -> Unit, onGrade: (Double) -> Unit) {
    val seg = s.timeline?.segments?.getOrNull(s.engine.segmentIndex)
    val blockFree = seg?.erg == false
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = s.engine.erg,
            onClick = { if (!blockFree) onErg(!s.engine.erg) },
            enabled = !blockFree,
            label = { Text(if (blockFree) "ERG aus (frei)" else if (s.engine.erg) "ERG an" else "ERG aus") },
        )
        if (!s.engine.erg) {
            Stepper("%.1f %%".format(s.engine.gradePct), { onGrade(-0.5) }, { onGrade(0.5) }, label = "Steigung")
        }
    }
}
