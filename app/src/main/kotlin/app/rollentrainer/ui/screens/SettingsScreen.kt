package app.rollentrainer.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.ble.ScanItem
import app.rollentrainer.core.metrics.StarTolerance
import app.rollentrainer.core.metrics.ZoneScheme
import app.rollentrainer.core.route.Drivetrain
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.SavedSensor
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.blePermissions
import app.rollentrainer.ui.components.CameraSwitch
import app.rollentrainer.ui.components.PictureSettings
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.SectionTitle
import app.rollentrainer.ui.components.Stepper
import app.rollentrainer.ui.fields.LayoutOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch

@Composable
fun SettingsScreen(container: AppContainer, stravaMessage: MutableStateFlow<String?>) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val message by stravaMessage.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var pairing by remember { mutableStateOf<SensorRole?>(null) }

    LaunchedEffect(message) {
        message?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            stravaMessage.value = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) container.sensors.connectSaved()
        else Toast.makeText(context, "Ohne Bluetooth-Berechtigung können keine Sensoren gefunden werden.", Toast.LENGTH_LONG).show()
    }

    fun startPairing(role: SensorRole) {
        val missing = blePermissions().filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
        else if (!container.scanner.isEnabled) Toast.makeText(context, "Bitte Bluetooth einschalten.", Toast.LENGTH_LONG).show()
        else pairing = role
    }

    val update: ((AppSettings) -> AppSettings) -> Unit = { container.settings.update(it) }

    // The decision is made on the space this screen really gets (after the navigation rail and
    // system bars), not on LocalWide: a phone in landscape counts as "wide" there but two 300 dp
    // columns of settings would be cramped, and on a short screen a smaller margin wins rows.
    // The height comes from the configuration, which the keyboard leaves alone: the content
    // height shrinks while it is open, and the page would jump to the smaller margin as you type.
    val shortScreen = LocalConfiguration.current.screenHeightDp < 480
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val twoColumns = maxWidth >= 760.dp
        val pad = if (compact || shortScreen) 16.dp else 24.dp
        val left: @Composable () -> Unit = {
            Column {
                SectionTitle("Sensoren")
                SensorsPanel(container, onPair = ::startPairing)
                SectionTitle("Profil")
                ProfilePanel(container, settings, update)
                SectionTitle("Töne")
                SoundsPanel(settings, update)
            }
        }
        val right: @Composable () -> Unit = {
            Column {
                SectionTitle("Training")
                TrainingPanel(settings, update)
                SectionTitle("Spinning-Class")
                SpinningSettingsPanel(container, settings, update)
                SectionTitle("Virtuelle Strecken")
                RoutesPanel(settings, update)
                SectionTitle("Trainingsbildschirm")
                Panel(Modifier.fillMaxWidth()) {
                    LayoutOptions(settings, update)
                }
                SectionTitle("Strava")
                StravaPanel(container, settings, update)
                SectionTitle("Garmin Connect")
                Panel(Modifier.fillMaxWidth()) {
                    Text(
                        "Garmin lässt derzeit keine neuen Apps an die Connect-Schnittstelle. " +
                            "Teile die FIT-Datei einer Fahrt (Verlauf → Fahrt → „FIT-Datei teilen“) und lade sie auf " +
                            "connect.garmin.com unter „Daten importieren“ hoch. Training Load und Trainingszustand werden dann übernommen.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // The licences of the geodata packs ask for this attribution (see tools/geodata/README.md).
                SectionTitle("Kartendaten")
                Panel(Modifier.fillMaxWidth()) {
                    Text(
                        DATA_SOURCES.joinToString("\n\n"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad)) {
            Text("Einstellungen", style = if (compact) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(8.dp))
            if (twoColumns) {
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f)) { left() }
                    Column(Modifier.weight(1f)) { right() }
                }
            } else {
                left()
                right()
            }
        }
    }

    pairing?.let { role ->
        PairDialog(container, role, onDismiss = { pairing = null })
    }
}

@Composable
private fun SensorsPanel(container: AppContainer, onPair: (SensorRole) -> Unit) {
    val status by container.sensors.status.collectAsStateWithLifecycle()
    // On a narrow panel the two text buttons go under the name; beside it they would squeeze it to a
    // few letters per line. Measured on the panel, not the screen: a column of the two-column layout
    // is no wider than a phone. A large font needs the room sooner.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val buttonsBelow = maxWidth < 440.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
            SensorRole.entries.forEachIndexed { i, role ->
                if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                val st = status[role]
                val sensor = st?.sensor
                val state = st?.state ?: ConnectionState.DISCONNECTED
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ConnectionDot(state)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(role.title, style = MaterialTheme.typography.titleMedium)
                        val detail = when {
                            sensor == null -> role.hint
                            else -> buildString {
                                append(sensor.name)
                                append(" · ")
                                append(
                                    when (state) {
                                        ConnectionState.CONNECTED -> "verbunden"
                                        ConnectionState.CONNECTING -> "verbinde …"
                                        ConnectionState.DISCONNECTED -> "getrennt"
                                    }
                                )
                                st?.battery?.let { append(" · Akku $it %") }
                            }
                        }
                        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (sensor != null && buttonsBelow) {
                            Row {
                                TextButton(onClick = { onPair(role) }) { Text("Ändern") }
                                TextButton(onClick = { container.sensors.forget(role) }) { Text("Entfernen") }
                            }
                        }
                    }
                    if (sensor == null) {
                        Button(onClick = { onPair(role) }) { Text("Koppeln") }
                    } else if (!buttonsBelow) {
                        TextButton(onClick = { onPair(role) }) { Text("Ändern") }
                        TextButton(onClick = { container.sensors.forget(role) }) { Text("Entfernen") }
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(
        "Der Van Rysel D100 erlaubt nur eine Bluetooth-Verbindung: Schließe andere Apps wie Zwift, bevor du koppelst. " +
            "Die Trittfrequenz des D100 ist ungenau, ein Kurbelsensor hilft.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun PairDialog(container: AppContainer, role: SensorRole, onDismiss: () -> Unit) {
    val found = remember { mutableStateMapOf<String, ScanItem>() }
    var showAll by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        container.scanner.scan()
            .catch { error = it.message }
            .collect { found[it.address] = it }
    }
    val list = found.values
        .filter { showAll || it.matches(role) }
        .sortedByDescending { it.rssi }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${role.title} koppeln") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Suche … Gerät einschalten bzw. kurz in die Pedale treten.", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(12.dp))
                // weight(fill = false): on a phone in landscape the list shrinks and scrolls instead of
                // pushing the "show all devices" switch out of the dialog.
                LazyColumn(Modifier.weight(1f, fill = false).heightIn(max = 320.dp)) {
                    items(list, key = { it.address }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                container.sensors.pair(role, SavedSensor(item.address, item.name))
                                onDismiss()
                            }.padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.name, style = MaterialTheme.typography.titleMedium)
                                Text(item.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text("${item.rssi} dBm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (list.isEmpty()) {
                    Text("Noch nichts gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(showAll, { showAll = it })
                    Spacer(Modifier.width(8.dp))
                    Text("Alle Bluetooth-Geräte zeigen", style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ProfilePanel(container: AppContainer, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper("${settings.ftp} W", { container.settings.setFtp(settings.ftp - 5, "Manuell") },
                { container.settings.setFtp(settings.ftp + 5, "Manuell") }, label = "FTP")
            Stepper("%.1f kg".format(settings.weightKg), { update { it.copy(weightKg = (it.weightKg - 0.5).coerceAtLeast(30.0)) } },
                { update { it.copy(weightKg = (it.weightKg + 0.5).coerceAtMost(200.0)) } }, label = "Gewicht")
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Stepper("${settings.maxHr}", { update { it.copy(maxHr = (it.maxHr - 1).coerceAtLeast(120)) } },
                { update { it.copy(maxHr = (it.maxHr + 1).coerceAtMost(230)) } }, label = "Max. Herzfrequenz")
            Column {
                Text("Leistungsgewicht", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%.2f W/kg".format(settings.ftp / settings.weightKg), style = MaterialTheme.typography.titleMedium)
            }
            Stepper("${settings.wPrimeKj} kJ", { update { it.copy(wPrimeKj = (it.wPrimeKj - 1).coerceAtLeast(5)) } },
                { update { it.copy(wPrimeKj = (it.wPrimeKj + 1).coerceAtMost(50)) } }, label = "W' (anaerob)")
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Kennst du deine FTP nicht? Fahre den „FTP-Rampentest“ oder den „20-Minuten-FTP-Test“ – die App schlägt danach den Wert vor. " +
                "W' liegt meist zwischen 15 und 25 kJ; es steuert nur die W'-Bilanz-Anzeige.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (settings.ftpHistory.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text("FTP-Verlauf", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val df = java.text.SimpleDateFormat("d. MMM yyyy", java.util.Locale.GERMANY)
            settings.ftpHistory.takeLast(8).reversed().forEach { e ->
                Row(Modifier.padding(vertical = 2.dp)) {
                    Text(df.format(java.util.Date(e.epochSec * 1000)), Modifier.width(120.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${e.ftp} W", style = MaterialTheme.typography.bodySmall)
                    if (e.source.isNotBlank()) Text(" · ${e.source}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun RoutesPanel(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        ChoiceRow(
            "Trainer-Schwierigkeit",
            listOf(25 to "25 %", 50 to "50 %", 75 to "75 %", 100 to "100 %"),
            settings.trainerDifficultyPct,
        ) { v -> update { it.copy(trainerDifficultyPct = v) } }
        Text(
            "Wie viel der Steigung der Trainer wirklich simuliert. Bei 50 % fühlt sich ein 10-%-Anstieg wie 5 % an, " +
                "dein Tempo auf der Strecke bleibt trotzdem das echte – wie bei Zwift. 100 % ist am realistischsten, " +
                "braucht aber kleine Gänge.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Text("Bild der 3D-Welt", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Auch während der Fahrt über „Bild“ im aufgeklappten Panel, dann siehst du jede Änderung sofort.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        PictureSettings(settings, update)
        Text("Kamera", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(top = 8.dp))
        Spacer(Modifier.height(8.dp))
        CameraSwitch(
            firstPerson = settings.firstPersonView,
            onChange = { v -> update { it.copy(firstPersonView = v) } },
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "Lässt sich während der Fahrt jederzeit umschalten.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        SwitchRow(
            "Virtuelle Schaltung",
            "In der Fahrt schaltest du mit zwei Tasten; der Trainer stellt den Widerstand zum Gang",
            settings.virtualGears,
        ) { v -> update { it.copy(virtualGears = v) } }
        if (settings.virtualGears) {
            ChoiceRow("Kettenblätter", Drivetrain.CHAINRINGS.keys.map { it to it }, settings.chainrings) { v -> update { it.copy(chainrings = v) } }
            ChoiceRow("Kassette", Drivetrain.CASSETTES.keys.map { it to it }, settings.cassette) { v -> update { it.copy(cassette = v) } }
            val gears = Drivetrain.of(settings.chainrings, settings.cassette).gears
            Text(
                "${gears.size} Gänge von ${gears.first().label} bis ${gears.last().label}. Schalte dein echtes Rad in einen mittleren Gang " +
                    "(etwa 50 × 17) und lass es dort: geschaltet wird in der App, der Trainer macht den Gang über den Widerstand fühlbar. " +
                    "Mit einem leichteren Gang wird die Steigung flacher, mit einem schwereren steiler – wie auf der Straße.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SoundsPanel(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        SwitchRow("Töne", "Alle Signale während der Fahrt", settings.sounds) { v -> update { it.copy(sounds = v) } }
        if (settings.sounds) {
            // The slider keeps a local value while dragging and writes the settings once when the finger lifts.
            var volume by remember(settings.soundVolume) { mutableStateOf(settings.soundVolume.toFloat()) }
            Text("Lautstärke ${volume.toInt()} %", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = volume,
                onValueChange = { v -> volume = v },
                onValueChangeFinished = { update { it.copy(soundVolume = volume.toInt()) } },
                valueRange = 0f..100f,
                steps = 9,
            )
            SwitchRow("Countdown", "Drei Töne vor jedem neuen Abschnitt", settings.soundCountdown) { v -> update { it.copy(soundCountdown = v) } }
            SwitchRow("Abschnittswechsel", "Signal beim Start eines Abschnitts, heller wenn es härter wird", settings.soundSegment) { v -> update { it.copy(soundSegment = v) } }
            SwitchRow("Trittfrequenz-Hinweise", "Wenn du länger als 10 s mehr als 10 rpm vom Ziel entfernt bist", settings.soundCadenceHints) { v -> update { it.copy(soundCadenceHints = v) } }
            SwitchRow("Metronom", "Klickt im Takt der Ziel-Trittfrequenz", settings.metronome) { v -> update { it.copy(metronome = v) } }
        }
        // Not a signal: the race sounds have their own switch and level, independent of the tones above.
        SwitchRow(
            "Streckenatmosphäre",
            "Fahrtwind, Reifen, Zuschauer und das Peloton auf virtuellen Strecken – am Berg mit Kuhglocken, im Ziel mit Sprecher",
            settings.ambience,
        ) { v -> update { it.copy(ambience = v) } }
        if (settings.ambience) {
            var level by remember(settings.ambienceVolume) { mutableStateOf(settings.ambienceVolume.toFloat()) }
            Text("Lautstärke Atmosphäre ${level.toInt()} %", style = MaterialTheme.typography.bodyLarge)
            Slider(
                value = level,
                onValueChange = { v -> level = v },
                onValueChangeFinished = { update { it.copy(ambienceVolume = level.toInt()) } },
                valueRange = 0f..100f,
                steps = 9,
            )
        }
    }
}

@Composable
private fun SwitchRow(title: String, sub: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceRow(title: String, options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Column(Modifier.padding(vertical = 8.dp)) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(4.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { (value, label) ->
                FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
            }
        }
    }
}

@Composable
private fun TrainingPanel(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        SwitchRow("Auto-Pause", "Die Uhr stoppt, wenn du nicht trittst, und läuft beim ersten Tritt weiter",
            settings.autoPause) { v -> update { it.copy(autoPause = v) } }
        if (settings.autoPause) {
            ChoiceRow("Auto-Pause nach", listOf(3 to "3 s", 5 to "5 s", 10 to "10 s"), settings.autoPauseSec) { v -> update { it.copy(autoPauseSec = v) } }
        }
        SwitchRow("Bildschirm anlassen", "Während des Trainings", settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
        SwitchRow("Powermeter bevorzugen", "Leistung vom Powermeter statt vom Trainer anzeigen und aufzeichnen",
            settings.preferPowerMeter) { v -> update { it.copy(preferPowerMeter = v) } }
        SwitchRow("PowerMatch", "Die Zielleistung wird so korrigiert, dass dein Powermeter das Ziel trifft; angezeigt und aufgezeichnet wird dann das Powermeter (braucht gekoppelten Powermeter)",
            settings.powerMatch) { v -> update { it.copy(powerMatch = v) } }
        ChoiceRow("Leistung glätten", listOf(1 to "1 s", 3 to "3 s", 5 to "5 s", 10 to "10 s"), settings.powerSmoothingSec) { v ->
            update { it.copy(powerSmoothingSec = v) }
        }
        ChoiceRow(
            "ERG lösen bei niedriger Trittfrequenz",
            listOf(0 to "Aus", 50 to "< 50 rpm", 60 to "< 60 rpm", 70 to "< 70 rpm"),
            settings.ergReleaseRpm,
        ) { v -> update { it.copy(ergReleaseRpm = v) } }
        Text(
            "Fällt die Trittfrequenz 3 s lang darunter („Spirale des Todes“), nimmt die App das Ziel auf 40 % zurück. Trittst du 2 s lang " +
                "wieder mindestens 10 rpm über der Schwelle, steigt das Ziel in 3 s zurück – oder sofort über „Sofort volles Ziel“. " +
                "Bei Blöcken mit niedriger Ziel-Trittfrequenz liegt die Schwelle 10 rpm unter deren Ziel. " +
                "Braucht eine Trittfrequenz-Messung vom Trainer oder Sensor.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow("Sterne-Bewertung", StarTolerance.entries.map { it to "${it.title} (±${it.pct} %)" }, settings.starTolerance) { v -> update { it.copy(starTolerance = v) } }
        val tol = settings.starTolerance.pct
        Text(
            "3 Sterne bei einer Abweichung bis ±$tol % (oder höchstens 10 W), 2 bis ±${tol * 2} %, 1 bis ±${tol * 3} %. " +
                "Abweichung = mittlerer Abstand zum Ziel im Abschnitt.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        ChoiceRow("Zonenschema", ZoneScheme.entries.map { it to it.title }, settings.zoneScheme) { v -> update { it.copy(zoneScheme = v) } }
        Spacer(Modifier.height(8.dp))
        Stepper(
            if (settings.weeklyGoalTss == 0) "Aus" else "${settings.weeklyGoalTss} TSS",
            { update { it.copy(weeklyGoalTss = (it.weeklyGoalTss - 25).coerceAtLeast(0)) } },
            { update { it.copy(weeklyGoalTss = (it.weeklyGoalTss + 25).coerceAtMost(1500)) } },
            label = "Wochenziel (TSS)",
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StravaPanel(container: AppContainer, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    Panel(Modifier.fillMaxWidth()) {
        val auth = settings.strava
        if (auth != null) {
            Text("Verbunden" + if (auth.athleteName.isNotBlank()) " als ${auth.athleteName}" else "", style = MaterialTheme.typography.titleMedium)
            SwitchRow("Automatisch hochladen", "Nach jeder gespeicherten Fahrt", settings.stravaAutoUpload) { v ->
                update { it.copy(stravaAutoUpload = v) }
            }
            OutlinedButton(onClick = { container.strava.disconnect() }) { Text("Trennen") }
        } else {
            Text(
                "Lege einmalig unter strava.com/settings/api eine eigene API-Anwendung an " +
                    "(„Authorization Callback Domain“: localhost) und trage Client-ID und Client-Secret hier ein.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            var id by remember(settings.stravaClientId) { mutableStateOf(settings.stravaClientId) }
            var secret by remember(settings.stravaClientSecret) { mutableStateOf(settings.stravaClientSecret) }
            OutlinedTextField(id, { id = it.trim() }, label = { Text("Client-ID") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(secret, { secret = it.trim() }, label = { Text("Client-Secret") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    enabled = id.isNotBlank() && secret.isNotBlank(),
                    onClick = {
                        update { it.copy(stravaClientId = id, stravaClientSecret = secret) }
                        context.startActivity(Intent(Intent.ACTION_VIEW, container.strava.authorizeUri()))
                    },
                ) { Text("Mit Strava verbinden") }
                TextButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.strava.com/settings/api")))
                }) { Text("API-Seite öffnen") }
            }
        }
    }
}

/** Where the landscape of the real routes comes from, with the attribution their licences require. */
private val DATA_SOURCES = listOf(
    "Die Strecken mit echter Landschaft beruhen auf offenen Geodaten. Die daraus gebauten Kartenpakete stehen unter der " +
        "Open Database License (ODbL 1.0).",
    "Straßen, Gebäude und Gewässer: © OpenStreetMap-Mitwirkende (ODbL), bereitgestellt von der Overture Maps Foundation " +
        "(CDLA Permissive 2.0 und ODbL).",
    "Höhenmodell: Copernicus DEM GLO-30, © DLR e.V. 2010–2014 und © Airbus Defence and Space GmbH 2014–2018, " +
        "bereitgestellt unter COPERNICUS von der Europäischen Union und der ESA.",
    "Landbedeckung und Satellitenbild: ESA WorldCover 2021 und sein Sentinel-2-Mosaik (CC BY 4.0), enthält veränderte " +
        "Copernicus-Sentinel-Daten 2021.",
    "Baumhöhen: Meta und World Resources Institute, High Resolution Canopy Height Maps (CC BY 4.0).",
)
