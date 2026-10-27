package app.rollentrainer.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.rollentrainer.AppContainer
import app.rollentrainer.data.AppSettings
import app.rollentrainer.ride.MusicPlayer
import app.rollentrainer.ui.components.Panel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Settings of the spinning view: music folder, studio beat, coach voice, volume. */
@Composable
fun SpinningSettingsPanel(container: AppContainer, settings: AppSettings, update: ((AppSettings) -> AppSettings) -> Unit) {
    val context = LocalContext.current
    val folder = settings.musicFolder
    var folderName by remember { mutableStateOf<String?>(null) }
    var fileCount by remember { mutableStateOf<Int?>(null) }

    // Scanning touches the documents provider, so it runs off the main thread whenever the folder changes.
    LaunchedEffect(folder) {
        folderName = null
        fileCount = null
        if (folder.isBlank()) return@LaunchedEffect
        val uri = runCatching { Uri.parse(folder) }.getOrNull() ?: return@LaunchedEffect
        val (name, count) = withContext(Dispatchers.IO) {
            MusicPlayer.folderName(context, uri) to MusicPlayer.scan(context, uri).size
        }
        folderName = name
        fileCount = count
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            MusicPlayer.persist(context, uri)
            update { it.copy(musicFolder = uri.toString()) }
        }
    }

    Panel(Modifier.fillMaxWidth()) {
        Text("Musik", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Aus Lizenzgründen ist keine Musik enthalten. Wähle einen Ordner mit deinen eigenen Titeln auf dem Tablet; " +
                "sie laufen in zufälliger Reihenfolge, Unterordner eine Ebene tief eingeschlossen. Ohne Ordner spielt der Studio-Beat.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        if (folder.isBlank()) {
            Button(onClick = { picker.launch(null) }) { Text("Musikordner wählen") }
        } else {
            Text(folderName ?: "Ordner", style = MaterialTheme.typography.titleMedium)
            Text(
                when (val n = fileCount) {
                    null -> "Suche Titel …"
                    0 -> "Keine Audiodateien gefunden – der Studio-Beat spielt stattdessen."
                    1 -> "1 Titel"
                    else -> "$n Titel"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(null) }) { Text("Anderen Ordner") }
                TextButton(onClick = { update { it.copy(musicFolder = "") } }) { Text("Ordner entfernen") }
            }
        }
        Spacer(Modifier.height(8.dp))
        // The slider keeps a local value while dragging and writes the settings once when the finger lifts.
        var volume by remember(settings.musicVolume) { mutableStateOf(settings.musicVolume.toFloat()) }
        Text("Musiklautstärke ${volume.toInt()} %", style = MaterialTheme.typography.bodyLarge)
        Slider(
            value = volume,
            onValueChange = { v -> volume = v },
            onValueChangeFinished = { update { it.copy(musicVolume = volume.toInt()) } },
            valueRange = 0f..100f,
            steps = 9,
        )
        SwitchRow("Studio-Beat", "Elektronischer Beat im Takt der Ziel-Trittfrequenz, wenn kein Ordner gewählt ist", settings.studioBeat) { v -> update { it.copy(studioBeat = v) } }
        SwitchRow("Coach-Stimme", "Ansagen zu Abschnitten, Countdown und Motivation (braucht eine deutsche Sprachausgabe)", settings.coachVoice) { v -> update { it.copy(coachVoice = v) } }
        SwitchRow("Spinning-Ansicht als Standard", "Trainings starten direkt in der Spinning-Ansicht; Umschalten geht jederzeit", settings.spinningByDefault) { v -> update { it.copy(spinningByDefault = v) } }
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
