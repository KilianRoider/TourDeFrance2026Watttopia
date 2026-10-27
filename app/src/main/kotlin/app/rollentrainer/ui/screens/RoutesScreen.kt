package app.rollentrainer.ui.screens

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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.Routes
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.formatDuration
import kotlin.math.roundToInt

private val FRACTIONS = listOf(1.0 to "Ganz", 0.75 to "¾", 0.5 to "½", 0.25 to "¼")
private val PACERS = listOf(0.0 to "Kein Pacer", 2.5 to "2,5 W/kg", 3.0 to "3,0 W/kg", 3.5 to "3,5 W/kg", 4.0 to "4,0 W/kg", 4.5 to "4,5 W/kg", 5.5 to "5,5 W/kg")

/** The famous climbs as virtual roads, with length and pacer choices and the rider's best times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutesScreen(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    var fraction by rememberSaveable { mutableStateOf(1.0) }
    var pacerWkg by rememberSaveable { mutableStateOf(settings.pacerWkg) }
    var startCandidate by remember { mutableStateOf<Route?>(null) }
    val params = remember(settings.weightKg) { RiderParams.forRider(settings.weightKg) }
    val bests = remember(rides) {
        rides.filter { it.routeId != null && it.routeTimeSec != null }
            .groupBy { it.routeId!! to it.routeFraction }
            .mapValues { (_, list) -> list.minOf { it.routeTimeSec!! } }
    }

    fun launch(route: Route) {
        val r = route.shortened(fraction)
        val pacers = if (pacerWkg > 0) listOf(Pacer("Pacer", (pacerWkg * settings.weightKg).roundToInt())) else emptyList()
        container.settings.update { it.copy(pacerWkg = pacerWkg) }
        container.session.startRoute(r, pacers)
    }

    fun start(route: Route) {
        if (status[SensorRole.TRAINER]?.state != ConnectionState.CONNECTED) startCandidate = route else launch(route)
    }

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 340.dp),
        contentPadding = PaddingValues(24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column {
                Text("Legendäre Anstiege", style = MaterialTheme.typography.headlineLarge)
                Spacer(Modifier.height(4.dp))
                Text(
                    "Die berühmten Berge der großen Rundfahrten, nachgebaut aus öffentlichen Höhenprofilen. Der Trainer simuliert die " +
                        "Steigung, dein Tempo folgt der Physik aus Leistung, Gewicht und Gefälle.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Text("Länge", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FRACTIONS.forEach { (f, label) ->
                        FilterChip(selected = fraction == f, onClick = { fraction = f }, label = { Text(label) })
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Pacer (fährt mit konstanter Leistung)", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PACERS.forEach { (w, label) ->
                        val watts = (w * settings.weightKg).roundToInt()
                        FilterChip(selected = pacerWkg == w, onClick = { pacerWkg = w }, label = { Text(if (w > 0) "$label · $watts W" else label) })
                    }
                }
            }
        }
        items(Routes.all, key = { it.id }) { route ->
            val shown = remember(route, fraction) { route.shortened(fraction) }
            val estimate = remember(shown, settings.ftp, params) { Physics.estimateSeconds(shown, settings.ftp * 0.9, params) }
            RouteCard(
                route = shown,
                estimateSec = estimate,
                bestSec = bests[route.id to shown.fraction],
                onStart = { start(route) },
            )
        }
    }

    startCandidate?.let { r ->
        AlertDialog(
            onDismissRequest = { startCandidate = null },
            title = { Text("Trainer nicht verbunden") },
            text = { Text("Ohne verbundenen Smart-Trainer bleibt die Straße flach. Koppel den Trainer in den Einstellungen oder starte trotzdem.") },
            confirmButton = { Button(onClick = { startCandidate = null; launch(r) }) { Text("Trotzdem starten") } },
            dismissButton = { TextButton(onClick = { startCandidate = null }) { Text("Abbrechen") } },
        )
    }
}

@Composable
private fun RouteCard(route: Route, estimateSec: Int, bestSec: Int?, onStart: () -> Unit) {
    Panel(Modifier.fillMaxWidth()) {
        Text(route.name, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(route.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        RouteProfile(
            route, null, Modifier.fillMaxWidth().height(72.dp),
            lineColor = MaterialTheme.colorScheme.primary, fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Fact("%.1f km".format(route.lengthKm), "Länge")
            Fact("${route.ascentM.roundToInt()} m", "Höhenmeter")
            Fact("%.1f %%".format(route.avgGradientPct), "Ø Steigung")
            Fact("%.0f %%".format(route.maxGradientPct), "max.")
            if (route.hairpins > 0) Fact("${route.hairpins}", "Kehren")
        }
        Spacer(Modifier.height(8.dp))
        Text(route.description, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("≈ ${formatDuration(estimateSec)} bei 90 % FTP", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    if (bestSec != null) "Deine Bestzeit: ${formatDuration(bestSec)}" else "Noch keine Zeit",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (bestSec != null) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(onClick = onStart) {
                Icon(Icons.Default.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text("Fahren")
            }
        }
    }
}

@Composable
private fun Fact(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
