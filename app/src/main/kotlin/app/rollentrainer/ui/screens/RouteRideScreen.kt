package app.rollentrainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.ble.SensorStatus
import app.rollentrainer.data.SensorRole
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteState
import app.rollentrainer.core.route.World
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.world.FrameState
import app.rollentrainer.ui.world.WorldCache
import app.rollentrainer.ui.world.WorldView
import app.rollentrainer.ui.world.supportsWorld3d
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Riding a virtual route: the 3D world fills the screen and a translucent head-up display sits on
 * top of it. The trainer is in simulation mode, so there are no targets, only the road.
 */
@Composable
fun RouteRideScreen(container: AppContainer, onFinished: (rideId: String?) -> Unit) {
    val state by container.session.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val s = state ?: return
    val rs = s.route ?: return
    val route = rs.route
    val frame = remember { FrameState().also { it.firstPerson = container.settings.state.value.firstPersonView } }
    val context = LocalContext.current
    val gl3 = remember { supportsWorld3d(context) }
    // A long route (a stage) is built section by section: the world shows the section the rider is in,
    // the next one is prepared in the background.
    val sections = remember(route.id, route.lengthM) { sectionCount(route) }
    val sectionLen = route.lengthM / sections
    // One function for every boundary, so the prefetched section and the one built later are bit-identical
    // (the world cache keys on the exact length and offset).
    fun boundary(i: Int): Double = if (i >= sections) route.lengthM else i * sectionLen
    val sectionIndex = ((rs.distanceM / sectionLen).toInt()).coerceIn(0, sections - 1)
    val sectionStart = boundary(sectionIndex)
    val sectionRoute = remember(route.id, route.lengthM, sectionIndex) {
        if (sections == 1) route else route.section(sectionStart, boundary(sectionIndex + 1))
    }
    val world by produceState<World?>(initialValue = null, sectionRoute.id, sectionRoute.lengthM) {
        value = if (gl3) withContext(Dispatchers.Default) { WorldCache.get(sectionRoute) } else null
    }
    if (gl3 && sections > 1 && sectionIndex < sections - 1) {
        LaunchedEffect(sectionIndex) {
            val next = route.section(boundary(sectionIndex + 1), boundary(sectionIndex + 2))
            withContext(Dispatchers.Default) { WorldCache.get(next) }
        }
    }
    LaunchedEffect(rs, s.engine.paused, sectionStart) {
        frame.distanceM = rs.distanceM - sectionStart
        frame.speedMps = rs.speedMps
        frame.cadenceRpm = (s.live.cadence ?: 0).toDouble()
        frame.paused = s.engine.paused || rs.finished
        val p = rs.pacers.firstOrNull()
        // A pacer in another section is out of sight.
        val pd = p?.distanceM?.minus(sectionStart)
        frame.pacerDistanceM = if (pd != null && pd >= -50 && pd <= sectionRoute.lengthM + 50) pd else Double.NaN
        frame.pacerSpeedMps = p?.speedMps ?: 0.0
        frame.updatedAtMs = android.os.SystemClock.elapsedRealtime()
    }
    val status by container.sensors.status.collectAsStateWithLifecycle()
    var askEnd by rememberSaveable { mutableStateOf(false) }
    var finishSeen by rememberSaveable { mutableStateOf(false) }
    fun end(save: Boolean) = container.scope.launch {
        askEnd = false
        val ride = container.session.finish(save)
        onFinished(ride?.id)
    }

    // Landmark sign as a short toast: shown when passed, hidden five seconds later. The passed landmark
    // is only set for one tick, so showing and hiding are two separate effects.
    var toast by remember { mutableStateOf<String?>(null) }
    // The text stays while the sign fades out.
    var toastText by remember { mutableStateOf("") }
    LaunchedEffect(rs.passedLandmark) {
        rs.passedLandmark?.let { toast = it.label; toastText = it.label }
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(5000); toast = null }
    }

    val wide = LocalWide.current
    Box(Modifier.fillMaxSize()) {
        if (!gl3) {
            // Without OpenGL ES 3 the ride still works: a side view of the climb instead of the 3D road.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A6EA5), Color(0xFF9CC3E0), Color(0xFF5E8C3A)))))
            RouteProfile(route, rs, Modifier.fillMaxWidth().height(320.dp).align(Alignment.Center).padding(horizontal = 24.dp))
        } else {
            WorldView(world, frame, Modifier.fillMaxSize())
        }
        if (gl3 && world == null) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B2733)), contentAlignment = Alignment.Center) {
                Text("Strecke wird aufgebaut …", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp)) {
            // Top: route, remaining, time.
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
                Hud(Modifier) {
                    Text(route.displayName, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (route.isStage) "noch %.1f km · noch %.0f Hm".format(rs.remainingM / 1000, (route.ascentM - rs.ascentM).coerceAtLeast(0.0))
                        else "noch %.1f km · %.0f Hm bis oben".format(rs.remainingM / 1000, (route.summitElevationM - rs.elevationM).coerceAtLeast(0.0)),
                        color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.weight(1f))
                Hud(Modifier) {
                    // At the top the clock shows the exact finish time, as the result and the saved ride do.
                    Text(formatDuration((rs.finishTimeSec ?: rs.elapsedSec).roundToInt()), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 32.sp)
                    val eta = rs.etaSec
                    Text(if (eta != null) "Ziel in ≈ ${formatDuration(eta)}" else "Ziel in –", color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(8.dp))
            val pacer = rs.pacers.firstOrNull()
            if (pacer != null) {
                val gap = pacer.gapM
                val behind = if (rs.finished) rs.beatenBy(pacer) else gap > 1
                val text = when {
                    rs.finished -> if (behind) "${pacer.name} war schneller" else "${pacer.name} geschlagen"
                    pacer.finished -> "${pacer.name} ist oben"
                    gap > 1 -> "${pacer.name} ${gap.roundToInt()} m voraus"
                    gap < -1 -> "${pacer.name} ${(-gap).roundToInt()} m hinter dir"
                    else -> "Rad an Rad mit ${pacer.name}"
                }
                val color = if (behind || (pacer.finished && !rs.finished)) Color(0xFFFFB74D) else Color(0xFF81C784)
                Hud(Modifier.align(Alignment.Start)) {
                    Text(text, color = color, style = MaterialTheme.typography.titleSmall)
                }
            }
            Spacer(Modifier.weight(1f))
            AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Box(Modifier.background(Color(0xFFFFD230), RoundedCornerShape(10.dp)).padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Text(toastText, color = Color(0xFF202020), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(8.dp))
            s.trainerBanner(status[SensorRole.TRAINER] ?: SensorStatus(null))?.let { msg ->
                Box(Modifier.align(Alignment.CenterHorizontally).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }
            // Bottom: metrics and the profile with the rider on it.
            Hud(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Metric("Leistung", s.displayPower?.toString() ?: "–", "W", wide, Color(0xFFFF8A65))
                    Metric("W/kg", s.displayPower?.let { "%.1f".format(it / settings.weightKg) } ?: "–", "W/kg", wide, Color(0xFFFFCC80))
                    Metric("Tempo", "%.1f".format(rs.speedKmh), "km/h", wide)
                    Metric("Steigung", "%.1f".format(rs.gradePct), "%", wide, gradeColor(rs.gradePct))
                    Metric("Trittfrequenz", s.live.cadence?.toString() ?: "–", "rpm", wide)
                    Metric("Puls", s.live.heartRate?.toString() ?: "–", "bpm", wide, Color(0xFFEF5350))
                    if (wide) Metric("VAM", rs.vamMph.toString(), "m/h", wide)
                    Metric("Höhe", rs.elevationM.roundToInt().toString(), "m", wide)
                    if (wide) Metric("Ø Leistung", s.stats.avgPower.toString(), "W", wide)
                }
                Spacer(Modifier.height(8.dp))
                RouteProfile(route, rs, Modifier.fillMaxWidth().height(if (wide) 64.dp else 48.dp))
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    // "Weiter" only after the rider paused by hand; before the start or in an auto-pause pedalling
                    // goes on, so the button offers a pause. At the top there is nothing to pause.
                    if (!rs.finished) {
                        val resume = s.engine.userPaused
                        Button(onClick = { container.session.setPaused(!resume) }) {
                            Icon(if (resume) Icons.Default.PlayArrow else Icons.Default.Pause, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (resume) "Weiter" else "Pause")
                        }
                    }
                    FilledTonalButton(onClick = { askEnd = true }) {
                        Icon(Icons.Default.Stop, null)
                        Spacer(Modifier.width(6.dp))
                        Text("Beenden")
                    }
                    // Camera: behind the bike or through the rider's eyes; the choice is kept for the next ride.
                    if (gl3) {
                        FilledTonalButton(onClick = {
                            val fp = !settings.firstPersonView
                            frame.firstPerson = fp
                            container.settings.update { it.copy(firstPersonView = fp) }
                        }) {
                            Icon(Icons.Default.Videocam, null)
                            Spacer(Modifier.width(6.dp))
                            Text(if (settings.firstPersonView) "Ego" else "Verfolger")
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    val gear by container.session.gear.collectAsStateWithLifecycle()
                    if (gear != null && !rs.finished) {
                        // Virtual gears: down is the easier gear, up the harder one; the label shows "50 × 17 · 14/20".
                        FilledTonalButton(onClick = { container.session.shiftDown() }) { Icon(Icons.Default.KeyboardArrowDown, "Leichterer Gang") }
                        Text(
                            gear ?: "",
                            color = Color.White, style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        FilledTonalButton(onClick = { container.session.shiftUp() }) { Icon(Icons.Default.KeyboardArrowUp, "Schwererer Gang") }
                        Spacer(Modifier.width(12.dp))
                    }
                    Text(
                        "Trainer: %d %% der Steigung".format(settings.trainerDifficultyPct),
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }

    val done = rs.finished && !finishSeen
    if (askEnd || done) {
        AlertDialog(
            onDismissRequest = { if (!done) askEnd = false },
            title = { Text(if (done) "Oben!" else "Fahrt beenden?") },
            text = {
                Column {
                    if (done) {
                        Text(
                            "${route.name} in ${formatDuration((rs.finishTimeSec ?: rs.elapsedSec).roundToInt())} · Ø ${s.stats.avgPower} W · " +
                                "Ø %.1f km/h".format(route.lengthM / (rs.finishTimeSec ?: rs.elapsedSec).coerceAtLeast(1.0) * 3.6),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        rs.pacers.firstOrNull()?.let { p ->
                            Spacer(Modifier.height(6.dp))
                            Text(if (rs.beatenBy(p)) "${p.name} war schneller." else "Du hast ${p.name} geschlagen!")
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    Text(
                        if (s.elapsedSec < 30) "Die Fahrt ist kürzer als 30 Sekunden und wird nicht gespeichert."
                        else "Die Fahrt wird mit Strecke und Zeit gespeichert.",
                    )
                }
            },
            confirmButton = { Button(onClick = { end(true) }) { Text(if (s.elapsedSec < 30) "Beenden" else "Beenden und speichern") } },
            dismissButton = {
                Row {
                    if (s.elapsedSec >= 30) TextButton(onClick = { end(false) }) { Text("Verwerfen", color = MaterialTheme.colorScheme.error) }
                    if (done) TextButton(onClick = { finishSeen = true; askEnd = false }) { Text("Ansehen") }
                    else TextButton(onClick = { askEnd = false }) { Text(if (rs.finished) "Zurück" else "Weiterfahren") }
                }
            },
        )
    }
}

/** One line above the metrics; the pause comes first, so a stopped clock is never hidden by a trainer note. */
private fun RideState.trainerBanner(trainer: SensorStatus): String? {
    val beforeStart = engine.workoutSec == 0.0 && engine.paused
    return when {
        route?.finished == true || engine.finished -> "Ziel erreicht"
        engine.userPaused -> "Pausiert – tippe auf Weiter."
        engine.autoPaused -> "Pausiert – tritt weiter, um fortzusetzen."
        trainer.sensor == null -> if (beforeStart) "Ohne Smart-Trainer: tritt in die Pedale, um loszufahren. Die Steigung ist nur zu sehen."
        else "Ohne Smart-Trainer: die Steigung ist nur zu sehen, nicht zu spüren."
        trainer.state != ConnectionState.CONNECTED -> "Verbinde mit ${trainer.sensor?.name.orEmpty()} …"
        !trainerControl -> "Warte auf Steuerung durch den Trainer …"
        beforeStart -> "Tritt in die Pedale, um loszufahren."
        engine.paused -> "Pausiert"
        else -> null
    }
}

/** Routes longer than a climb are built in sections of about 8 km. */
private fun sectionCount(route: Route): Int =
    if (route.lengthM <= 22_000) 1 else kotlin.math.ceil(route.lengthM / 8000.0).toInt().coerceAtLeast(2)

private fun gradeColor(g: Double): Color = when {
    g >= 10 -> Color(0xFFEF5350)
    g >= 7 -> Color(0xFFFF8A65)
    g >= 4 -> Color(0xFFFFD54F)
    g > 0.5 -> Color(0xFFAED581)
    g < -2 -> Color(0xFF64B5F6)
    else -> Color.White
}

/** Translucent dark card for text over the 3D view. */
@Composable
private fun Hud(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(modifier.background(Color(0xB3101820), RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 8.dp), content = content)
}

@Composable
private fun Metric(label: String, value: String, unit: String, wide: Boolean, color: Color = Color.White) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label.uppercase(), color = Color.White.copy(alpha = 0.65f), fontSize = 11.sp, lineHeight = 12.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = if (wide) 34.sp else 24.sp, lineHeight = if (wide) 36.sp else 26.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(3.dp))
            Text(unit, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** Elevation profile with the rider and the pacer as dots. */
@Composable
fun RouteProfile(route: Route, rs: RouteState?, modifier: Modifier = Modifier, lineColor: Color = Color.White, fillColor: Color = Color(0x66FFFFFF)) {
    Canvas(modifier) {
        val pts = route.points
        if (pts.size < 2) return@Canvas
        val minE = pts.minOf { it.elevationM }
        val maxE = pts.maxOf { it.elevationM }
        val span = (maxE - minE).coerceAtLeast(10.0)
        fun x(d: Double) = (d / route.lengthM * size.width).toFloat()
        fun y(e: Double) = (size.height - (e - minE) / span * (size.height * 0.85)).toFloat()
        val path = Path()
        path.moveTo(0f, size.height)
        pts.forEach { path.lineTo(x(it.distanceM), y(it.elevationM)) }
        path.lineTo(size.width, size.height)
        path.close()
        drawPath(path, fillColor)
        val line = Path()
        pts.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.distanceM), y(p.elevationM)) else line.lineTo(x(p.distanceM), y(p.elevationM)) }
        drawPath(line, lineColor, style = Stroke(width = 3f, cap = StrokeCap.Round))
        if (rs != null) {
            rs.pacers.firstOrNull()?.let { p ->
                drawCircle(Color(0xFF42A5F5), 7f, Offset(x(p.distanceM), y(route.elevationAt(p.distanceM))))
            }
            drawCircle(Color(0xFFFF5252), 9f, Offset(x(rs.distanceM), y(rs.elevationM)))
            drawCircle(Color.White, 9f, Offset(x(rs.distanceM), y(rs.elevationM)), style = Stroke(2f))
        }
    }
}

