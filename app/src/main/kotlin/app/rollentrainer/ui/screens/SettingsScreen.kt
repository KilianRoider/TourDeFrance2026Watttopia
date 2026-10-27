package app.rollentrainer.ui.screens

import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.ble.ScanItem
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.SavedSensor
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.blePermissions
import app.rollentrainer.ui.components.ConnectionDot
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.SectionTitle
import app.rollentrainer.ui.components.Stepper
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
    val left: @Composable () -> Unit = {
        Column {
            SectionTitle("Sensoren")
            SensorsPanel(container, onPair = ::startPairing)
            SectionTitle("Profil")
            ProfilePanel(settings, update)
        }
    }
    val right: @Composable () -> Unit = {
        Column {
            SectionTitle("Training")
            TrainingPanel(settings, update)
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
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp)) {
        Text("Einstellungen", style = MaterialTheme.typography.headlineLarge)
        Spacer(Modifier.height(8.dp))
        if (LocalWide.current) {
            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(1f)) { left() }
                Column(Modifier.weight(1f)) { right() }
            }
        } else {
            left()
            right()
        }
    }

    pairing?.let { role ->
        PairDialog(container, role, onDismiss = { pairing = null })
    }
}

@Composable
private fun SensorsPanel(container: AppContainer, onPair: (SensorRole) -> Unit) {
    val status by container.sensors.status.collectAsStateWithLifecycle()
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
                }
                if (sensor == null) {
                    Button(onClick = { onPair(role) }) { Text("Koppeln") }
                } else {
                    TextButton(onClick = { onPair(role) }) { Text("Ändern") }
                    TextButton(onClick = { container.sensors.forget(role) }) { Text("Entfernen") }
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
            Column(Modifier.widthIn(min = 320.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Suche … Gerät einschalten bzw. kurz in die Pedale treten.", style = MaterialTheme.typography.bodyMedium)
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Spacer(Modifier.height(12.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
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

@Composable
private fun ProfilePanel(settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Stepper("${settings.ftp} W", { update { it.copy(ftp = (it.ftp - 5).coerceAtLeast(50)) } },
                { update { it.copy(ftp = (it.ftp + 5).coerceAtMost(600)) } }, label = "FTP")
            Stepper("%.1f kg".format(settings.weightKg), { update { it.copy(weightKg = (it.weightKg - 0.5).coerceAtLeast(30.0)) } },
                { update { it.copy(weightKg = (it.weightKg + 0.5).coerceAtMost(200.0)) } }, label = "Gewicht")
        }
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalAlignment = Alignment.Bottom) {
            Stepper("${settings.maxHr}", { update { it.copy(maxHr = (it.maxHr - 1).coerceAtLeast(120)) } },
                { update { it.copy(maxHr = (it.maxHr + 1).coerceAtMost(230)) } }, label = "Max. Herzfrequenz")
            Column {
                Text("Leistungsgewicht", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("%.2f W/kg".format(settings.ftp / settings.weightKg), style = MaterialTheme.typography.titleMedium)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            "Kennst du deine FTP nicht? Fahre den „FTP-Rampentest“ und nimm 75 % deiner besten Minute.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
        SwitchRow("Auto-Pause", "Pausiert, wenn du 3 Sekunden nicht trittst, und startet beim ersten Tritt",
            settings.autoPause) { v -> update { it.copy(autoPause = v) } }
        SwitchRow("Töne", "Countdown vor jedem neuen Abschnitt", settings.sounds) { v -> update { it.copy(sounds = v) } }
        SwitchRow("Bildschirm anlassen", "Während des Trainings", settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
        SwitchRow("Powermeter bevorzugen", "Leistung vom Powermeter statt vom Trainer anzeigen und aufzeichnen",
            settings.preferPowerMeter) { v -> update { it.copy(preferPowerMeter = v) } }
        ChoiceRow("Leistung glätten", listOf(1 to "1 s", 3 to "3 s", 5 to "5 s", 10 to "10 s"), settings.powerSmoothingSec) { v ->
            update { it.copy(powerSmoothingSec = v) }
        }
        ChoiceRow(
            "ERG-Schutz bei niedriger Trittfrequenz",
            listOf(0 to "Aus", 50 to "< 50 rpm", 60 to "< 60 rpm", 70 to "< 70 rpm"),
            settings.lowCadenceRpm,
        ) { v -> update { it.copy(lowCadenceRpm = v) } }
        Text(
            "Fällt die Trittfrequenz darunter, senkt die App die Zielleistung um 25 %, damit du wieder auf Touren kommst.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

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
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
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
