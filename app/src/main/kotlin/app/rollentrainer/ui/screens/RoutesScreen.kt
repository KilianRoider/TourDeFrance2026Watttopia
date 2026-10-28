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
import androidx.compose.material3.LinearProgressIndicator
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
import app.rollentrainer.core.route.Stages
import app.rollentrainer.core.model.Ride
import app.rollentrainer.data.SensorRole
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.formatDuration
import kotlin.math.roundToInt

private val FRACTIONS = listOf(1.0 to "Ganz", 0.75 to "¾", 0.5 to "½", 0.25 to "¼")
/** How much of a stage to ride: the whole thing or its finale. */
private val STAGE_CUTS = listOf(0.0 to "Ganze Etappe", 60.0 to "Finale 60 km", 30.0 to "Finale 30 km", 15.0 to "Finale 15 km")
private val PACERS = listOf(0.0 to "Kein Pacer", 2.5 to "2,5 W/kg", 3.0 to "3,0 W/kg", 3.5 to "3,5 W/kg", 4.0 to "4,0 W/kg", 4.5 to "4,5 W/kg", 5.5 to "5,5 W/kg")

/** The famous climbs as virtual roads, with length and pacer choices and the rider's best times. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RoutesScreen(container: AppContainer) {
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val status by container.sensors.status.collectAsStateWithLifecycle()
    val rides by container.rides.rides.collectAsStateWithLifecycle()
    var fraction by rememberSaveable { mutableStateOf(1.0) }
    var stageCutKm by rememberSaveable { mutableStateOf(30.0) }
    var showTour by rememberSaveable { mutableStateOf(true) }
    var pacerWkg by rememberSaveable { mutableStateOf(settings.pacerWkg) }
    var startCandidate by remember { mutableStateOf<Route?>(null) }
    val params = remember(settings.weightKg) { RiderParams.forRider(settings.weightKg) }
    val bests = remember(rides) {
        rides.filter { it.routeId != null && it.routeTimeSec != null }
            .groupBy { it.routeId!! to it.routeFraction }
            .mapValues { (_, list) -> list.minOf { it.routeTimeSec!! } }
    }
    // Per stage: the longest ride done on it (the whole stage beats a finale) with its time.
    val stageResults = remember(rides) {
        rides.filter { it.routeId != null && it.routeTimeSec != null && it.routeId!!.startsWith(Stages.ID_PREFIX) }
            .groupBy { it.routeId!! }
            .mapValues { (_, list) -> list.maxWith(compareBy<Ride> { it.routeFraction }.thenBy { -it.routeTimeSec!! }).let { StageResult(it.routeFraction, it.routeVariant, it.routeTimeSec!!) } }
    }
    val nextStageId = remember(stageResults) { Stages.all.firstOrNull { it.id !in stageResults }?.id }

    fun launch(route: Route) {
        val r = if (route.isStage) (if (stageCutKm > 0) route.finale(stageCutKm) else route) else route.shortened(fraction)
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = showTour, onClick = { showTour = true }, label = { Text("Tour 2026 · ${Stages.all.size} Etappen") })
                    FilterChip(selected = !showTour, onClick = { showTour = false }, label = { Text("Legendäre Anstiege") })
                }
                Spacer(Modifier.height(12.dp))
                if (showTour) {
                    Text("Die Tour 2026, Etappe für Etappe", style = MaterialTheme.typography.headlineLarge)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Alle ${Stages.all.size} Etappen von Barcelona bis Paris, nachgebaut aus den öffentlichen Höhenprofilen. Fahr sie der Reihe nach " +
                            "ganz oder nur das Finale; dein Fortschritt und deine Zeiten werden gesammelt. Der Trainer stellt die Steigung der " +
                            "Straße, dein Tempo kommt aus Leistung, Gewicht und Gefälle.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    TourProgress(stageResults, settings.ftp, settings.weightKg, params)
                    Spacer(Modifier.height(12.dp))
                    Text("Länge", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        STAGE_CUTS.forEach { (km, label) ->
                            FilterChip(selected = stageCutKm == km, onClick = { stageCutKm = km }, label = { Text(label) })
                        }
                    }
                } else {
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
        if (showTour) {
            item(span = { GridItemSpan(maxLineSpan) }) { SpeedExplainer(settings.ftp, settings.weightKg) }
            items(Stages.all, key = { it.id }) { stage ->
                val shown = remember(stage, stageCutKm) { if (stageCutKm > 0) stage.finale(stageCutKm) else stage }
                StageCard(
                    stage = stage,
                    shown = shown,
                    ftp = settings.ftp,
                    params = params,
                    result = stageResults[stage.id],
                    isNext = stage.id == nextStageId,
                    onStart = { start(stage) },
                )
            }
        } else {
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

/** What the rider has done on a stage: the cut ridden and the time. */
data class StageResult(val fraction: Double, val variant: String, val timeSec: Int)

@Composable
private fun TourProgress(results: Map<String, StageResult>, ftp: Int, weightKg: Double, params: RiderParams) {
    val done = results.size
    val whole = results.values.count { it.fraction >= 0.999 }
    val totalSec = results.values.sumOf { it.timeSec }
    Panel(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Fact("$done / ${Stages.all.size}", "Etappen gefahren")
            Fact("$whole", "davon ganz")
            Fact(formatDuration(totalSec), "Gesamtzeit")
            val remaining = Stages.all.filter { it.id !in results }
            if (remaining.isNotEmpty()) {
                val sec = remaining.sumOf { Physics.estimateSeconds(it, ftp * 0.7, params) }
                Fact("≈ ${formatDuration(sec)}", "noch zu fahren (ganz)")
            }
        }
        Spacer(Modifier.height(8.dp))
        LinearProgressIndicator(progress = { done / Stages.all.size.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

/** Shows how weight and watts turn into speed, with the rider's own numbers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SpeedExplainer(ftp: Int, weightKg: Double) {
    val watts = ftp * 0.8
    fun kmh(kg: Double, grade: Double) = Physics.kmh(Physics.steadySpeed(watts, grade, RiderParams.forRider(kg)))
    Panel(Modifier.fillMaxWidth()) {
        Text("So entsteht dein Tempo", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Die Geschwindigkeit folgt aus deiner Leistung, deinem Gewicht (${weightKg.roundToInt()} kg in den Einstellungen) und der Steigung: " +
                "Luftwiderstand, Rollwiderstand und Hangabtrieb. Schwerere Fahrer sind bei gleichen Watt pro Kilo auf der Geraden schneller, " +
                "am Berg langsamer. Bei %.0f W (80 %% deiner FTP) heißt das für dich:".format(watts),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Fact("%.1f km/h".format(kmh(weightKg, 0.0)), "flach")
            Fact("%.1f km/h".format(kmh(weightKg, 4.0)), "4 % Steigung")
            Fact("%.1f km/h".format(kmh(weightKg, 8.0)), "8 % Steigung")
            Fact("%.1f km/h".format(kmh(weightKg, -4.0)), "4 % Gefälle")
            Fact("%.1f / %.1f km/h".format(kmh(weightKg - 8, 0.0), kmh(weightKg - 8, 8.0)), "mit 8 kg weniger: flach / 8 %")
            Fact("%.2f W/kg".format(watts / weightKg), "entspricht")
        }
    }
}

@Composable
private fun StageCard(stage: Route, shown: Route, ftp: Int, params: RiderParams, result: StageResult?, isNext: Boolean, onStart: () -> Unit) {
    val info = stage.stage ?: return
    val share = remember(shown, ftp, params) { Physics.paceShareOfFtp(shown, ftp, params) }
    val watts = ftp * share
    val estimate = remember(shown, watts, params) { Physics.estimateSeconds(shown, watts, params) }
    val avg = remember(shown, watts, params) { Physics.averageKmh(shown, watts, params) }
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Etappe ${info.number}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Text(info.date, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            if (isNext) Text("Als Nächstes", style = MaterialTheme.typography.labelLarge, color = Color(0xFFEAB308))
            else if (result != null) Text("Gefahren", style = MaterialTheme.typography.labelLarge, color = Color(0xFF4CAF50))
        }
        Text("${info.startTown} – ${info.finishTown}", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${info.typeTitle} · ${stage.region}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        RouteProfile(
            stage, null, Modifier.fillMaxWidth().height(72.dp),
            lineColor = MaterialTheme.colorScheme.primary, fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Fact("%.1f km".format(stage.lengthKm), "Länge")
            Fact("${stage.ascentM.roundToInt()} m", "Höhenmeter")
            Fact("${info.climbs.size}", "Anstiege")
            info.climbs.maxByOrNull { it.summitM }?.let { Fact("${it.summitM.roundToInt()} m", it.name.take(18)) }
        }
        if (info.climbs.isNotEmpty()) {
            Spacer(Modifier.height(4.dp))
            Text(
                info.climbs.joinToString(" · ") { "${it.name} (${it.category}, %.1f km, %.1f %%)".format(it.lengthKm, it.avgPct) },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(stage.description, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${shown.variant.ifBlank { "Ganze Etappe" }}: ≈ ${formatDuration(estimate)} bei %.0f W (%.0f %% FTP) · Ø %.1f km/h".format(watts, share * 100, avg),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (result != null) "Deine Zeit: ${formatDuration(result.timeSec)} (${result.variant.ifBlank { "ganze Etappe" }})" else "Noch nicht gefahren",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (result != null) Color(0xFFEAB308) else MaterialTheme.colorScheme.onSurfaceVariant,
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
