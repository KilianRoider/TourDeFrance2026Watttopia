package app.rollentrainer.ui.screens

import android.util.Log
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ble.ConnectionState
import app.rollentrainer.ble.SensorStatus
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.SensorRole
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteState
import app.rollentrainer.core.route.World
import app.rollentrainer.ride.RaceFeed
import app.rollentrainer.ride.RideState
import app.rollentrainer.ride.RoadSurface
import app.rollentrainer.ui.components.CameraSwitch
import app.rollentrainer.ui.components.EndRideButton
import app.rollentrainer.ui.components.PictureSettings
import app.rollentrainer.ui.components.applyPicture
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.world.FrameState
import app.rollentrainer.ui.world.WorldCache
import app.rollentrainer.ui.world.GeoWorldView
import app.rollentrainer.ui.world.WorldView
import app.rollentrainer.ui.world.geo.GeoScene
import app.rollentrainer.ui.world.supportsWorld3d
import kotlinx.coroutines.CancellationException
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
    // Built-in stages and climbs with a geodata pack in the APK show their real terrain (GeoWorldRenderer);
    // without one, or when it fails to load, the procedural world takes over.
    val hasPack = remember(route.id) { gl3 && GeoScene.hasPack(context.assets, route) }
    var geoFailed by remember(route.id) { mutableStateOf(false) }
    var geoReady by remember(route.id, route.lengthM, route.offsetM) { mutableStateOf(false) }
    val usePack = hasPack && !geoFailed
    val geo by produceState<GeoScene?>(initialValue = null, route.id, route.lengthM, route.offsetM, usePack) {
        // Outside withContext: cancelled while the pack opens (the ride ended, the route changed), withContext
        // drops its result, and the scene it opened would never be closed.
        var opened: GeoScene? = null
        value = if (usePack) {
            try {
                withContext(Dispatchers.IO) {
                    runCatching { GeoScene.open(context.assets, route) }.onFailure { Log.w("WorldView", "geodata pack of ${route.id} unusable", it) }
                        .getOrNull().also { opened = it }
                }
            } catch (e: CancellationException) {
                opened?.close()
                throw e
            }
        } else null
        if (usePack && value == null) geoFailed = true
        val scene = value
        // Called on the GL thread; the state changes go back to the main thread.
        scene?.listener = object : GeoScene.Listener {
            override fun onReady() { container.scope.launch(Dispatchers.Main) { geoReady = true } }
            override fun onFailed(error: Throwable) { container.scope.launch(Dispatchers.Main) { geoFailed = true } }
        }
        // The ambience rolls over cobbles where the pack's road has them.
        val surface: ((Route, Double) -> RoadSurface?)? = scene?.let { sc ->
            { r: Route, d: Double ->
                if (r.id != sc.route.id) null
                else if (sc.pack.path.poseAt(sc.packDistance(d)).flags and PathFlags.COBBLES != 0) RoadSurface.COBBLES
                else RoadSurface.ASPHALT
            }
        }
        if (surface != null) container.session.surfaceAt = surface
        awaitDispose {
            if (surface != null && container.session.surfaceAt === surface) container.session.surfaceAt = null
            scene?.listener = null
            scene?.close()
        }
    }
    // A long route (a stage) is built section by section: the world shows the section the rider is in,
    // the next one is prepared in the background. With a pack the whole route is one world: rider
    // distance and pack distance map 1:1.
    val sections = remember(route.id, route.lengthM, usePack) { if (usePack) 1 else sectionCount(route) }
    val sectionLen = route.lengthM / sections
    // One function for every boundary, so the prefetched section and the one built later are bit-identical
    // (the world cache keys on the exact length and offset).
    fun boundary(i: Int): Double = if (i >= sections) route.lengthM else i * sectionLen
    val sectionIndex = ((rs.distanceM / sectionLen).toInt()).coerceIn(0, sections - 1)
    val sectionStart = boundary(sectionIndex)
    val sectionRoute = remember(route.id, route.lengthM, sectionIndex, sections) {
        if (sections == 1) route else route.section(sectionStart, boundary(sectionIndex + 1))
    }
    val world by produceState<World?>(initialValue = null, sectionRoute.id, sectionRoute.lengthM, usePack) {
        value = if (gl3 && !usePack) withContext(Dispatchers.Default) { WorldCache.get(sectionRoute) } else null
    }
    if (gl3 && !usePack && sections > 1 && sectionIndex < sections - 1) {
        LaunchedEffect(sectionIndex) {
            val next = route.section(boundary(sectionIndex + 1), boundary(sectionIndex + 2))
            withContext(Dispatchers.Default) { WorldCache.get(next) }
        }
    }
    LaunchedEffect(rs, s.engine.paused, sectionStart) {
        // Pro mode across the line: the race goes on so the others ride in (RideSession), and the renderer would
        // stop them with the pause flag. It stays off then; the rider himself gets no speed and no cadence.
        val fieldRidesIn = s.race?.finished == true
        frame.distanceM = rs.distanceM - sectionStart
        frame.speedMps = if (fieldRidesIn) 0.0 else rs.speedMps
        frame.cadenceRpm = if (fieldRidesIn) 0.0 else (s.live.cadence ?: 0).toDouble()
        frame.paused = !fieldRidesIn && (s.engine.paused || rs.finished)
        val p = rs.pacers.firstOrNull()
        // A pacer in another section is out of sight.
        val pd = p?.distanceM?.minus(sectionStart)
        frame.pacerDistanceM = if (pd != null && pd >= -50 && pd <= sectionRoute.lengthM + 50) pd else Double.NaN
        frame.pacerSpeedMps = p?.speedMps ?: 0.0
        // Pro mode, geodata world only: the field around the rider, the rider's place across the road and
        // the team kit of the chosen pro (kit 0, the Wattwerk kit, otherwise).
        val field = if (usePack) container.session.peloton(sectionStart) else null
        frame.peloton = field?.riders
        frame.lateralM = field?.userLateralM ?: Double.NaN
        frame.playerKit = s.pro?.let { RaceFeed.kitOf(it) } ?: 0
        frame.updatedAtMs = android.os.SystemClock.elapsedRealtime()
        frame.pelotonUpdatedAtMs = frame.updatedAtMs
    }
    val status by container.sensors.status.collectAsStateWithLifecycle()
    var askEnd by rememberSaveable { mutableStateOf(false) }
    var finishSeen by rememberSaveable { mutableStateOf(false) }
    // Pro mode: the result takes seconds to compute after "Beenden"; the dialog stays up meanwhile, every button off.
    val finishing by container.session.finishing.collectAsStateWithLifecycle()
    // The end runs on the app's scope and may outlive this composition (the activity recreated meanwhile):
    // it navigates through the newest screen's callback, whose navigator is the one on screen then.
    SideEffect { latestOnFinished = onFinished }
    fun end(save: Boolean) = container.scope.launch {
        val ride = container.session.finish(save)
        askEnd = false
        (latestOnFinished ?: onFinished)(ride?.id)
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

    val gear by container.session.gear.collectAsStateWithLifecycle()
    // Picture settings reach the renderer through the frame state; the "Bild" card changes them live.
    LaunchedEffect(settings) { frame.applyPicture(settings) }
    var pictureOpen by rememberSaveable { mutableStateOf(false) }
    // The metrics panel at the bottom opens and closes. Null until the rider chooses, so the default follows
    // the screen: closed on a phone (portrait or landscape), where the open panel would hide much of the
    // road; open on a tablet. Saveable, so turning the device keeps the choice.
    var panelOpen by rememberSaveable { mutableStateOf<Boolean?>(null) }
    // Camera: behind the bike or through the rider's eyes; the choice is kept for the next ride.
    val setCamera: (Boolean) -> Unit = { fp ->
        frame.firstPerson = fp
        container.settings.update { it.copy(firstPersonView = fp) }
    }
    Box(Modifier.fillMaxSize()) {
        if (!gl3) {
            // Without OpenGL ES 3 the ride still works: a side view of the climb instead of the 3D road.
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF3A6EA5), Color(0xFF9CC3E0), Color(0xFF5E8C3A)))))
            RouteProfile(route, rs, Modifier.fillMaxWidth().fillMaxHeight(0.4f).align(Alignment.Center).padding(horizontal = 24.dp))
        } else if (usePack) {
            geo?.let { GeoWorldView(it, frame, Modifier.fillMaxSize()) }
        } else {
            WorldView(world, frame, Modifier.fillMaxSize())
        }
        if (gl3 && (if (usePack) !geoReady else world == null)) {
            Box(Modifier.fillMaxSize().background(Color(0xFF1B2733)), contentAlignment = Alignment.Center) {
                Text("Strecke wird aufgebaut …", color = Color.White, style = MaterialTheme.typography.titleLarge)
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            // A phone in landscape has little height: smaller type, less padding, the panel starts closed.
            val short = maxHeight < 480.dp
            // A phone in portrait: the virtual gears get their own row under the key values.
            val narrow = maxWidth < 600.dp
            val expanded = panelOpen ?: !(short || narrow)
            // The open part never takes more than about a third (phone in portrait) or 45 % of the height;
            // beyond that it scrolls, so the upper half of the screen always belongs to the road.
            val detailMax = maxHeight * (if (narrow) 0.35f else 0.45f)
            // Captured here: inside the Column the layout scope's DslMarker hides BoxWithConstraintsScope.
            val hudMax = maxHeight * (if (short) 0.5f else 0.55f)
            Column(Modifier.fillMaxSize().padding(horizontal = if (short) 8.dp else 12.dp, vertical = if (short) 6.dp else 12.dp)) {
                TopBar(route, rs, short, narrow, onEnd = { askEnd = true })
                Spacer(Modifier.height(if (short) 4.dp else 8.dp))
                // The race HUD, the pacer line and the landmark toast share the height that is left once the
                // banner, the licence line and the panel below are measured (weighted, so it is measured last):
                // nothing up here can squeeze the panel and its buttons off the screen.
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val hudRoom = minOf(hudMax, maxHeight)
                    // Drawn first: where little height is left (a phone in landscape) it slips under the HUD, whose
                    // lane buttons stay on top.
                    LandmarkToast(toast != null, toastText, short, narrow, Modifier.align(Alignment.BottomCenter))
                    Column(Modifier.fillMaxWidth()) {
                        // Pro mode: rank, gaps and lane buttons under the clock (not on a phone in landscape with the
                        // panel open, which needs that height). Capped; its card scrolls within what the lane buttons leave.
                        val pro = s.pro
                        if (pro != null && !(short && expanded)) {
                            RaceHud(
                                s.race, pro, compact = short || narrow, maxHeight = hudRoom, finishing = finishing,
                                onLeft = { container.session.moveLeft() }, onRight = { container.session.moveRight() },
                                modifier = Modifier.align(Alignment.End),
                            )
                        }
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
                            Hud(Modifier.align(Alignment.Start), vertical = if (short) 4.dp else 8.dp) {
                                Text(text, color = color, style = MaterialTheme.typography.titleSmall)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(if (short) 4.dp else 8.dp))
                s.trainerBanner(status[SensorRole.TRAINER] ?: SensorStatus(null))?.let { msg ->
                    Box(Modifier.align(Alignment.CenterHorizontally).background(Color(0xAA000000), RoundedCornerShape(8.dp)).padding(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(msg, color = Color.White, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(if (short) 4.dp else 8.dp))
                }
                // The geodata licences (ODbL, CC BY) want their sources named wherever the landscape shows:
                // a small line above the panel, out of the way of its buttons; the full text is in the settings.
                if (usePack && geoReady) geo?.let { g ->
                    Text(
                        g.attributionShort, color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, lineHeight = 11.sp, textAlign = TextAlign.End,
                        style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 3f)),
                        modifier = Modifier.align(Alignment.End).padding(bottom = 2.dp),
                    )
                }
                MetricsPanel(
                    s = s,
                    rs = rs,
                    route = route,
                    settings = settings,
                    gear = gear,
                    expanded = expanded,
                    short = short,
                    narrow = narrow,
                    maxDetailHeight = detailMax,
                    showCamera = gl3,
                    onToggle = { panelOpen = !expanded },
                    onPause = { container.session.setPaused(it) },
                    onEnd = { askEnd = true },
                    onShiftDown = { container.session.shiftDown() },
                    onShiftUp = { container.session.shiftUp() },
                    onCamera = setCamera,
                    onPicture = { pictureOpen = !pictureOpen },
                )
            }
            if (pictureOpen && gl3) {
                // Over the road, without a scrim: the effect of every slider shows behind it at once.
                Column(
                    Modifier.align(Alignment.TopStart).padding(top = 64.dp, start = 12.dp).widthIn(max = 360.dp)
                        .heightIn(max = maxHeight * 0.75f)
                        .background(Color(0xE0101418), RoundedCornerShape(12.dp))
                        .verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Bild", color = Color.White, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { pictureOpen = false }) { Text("Fertig") }
                    }
                    PictureSettings(settings, container.settings::update, onLive = { frame.applyPicture(it) }, textColor = Color.White)
                }
            }
        }
    }

    val done = rs.finished && !finishSeen
    if (askEnd || done) {
        AlertDialog(
            onDismissRequest = { if (!done && !finishing) askEnd = false },
            title = { Text(if (done) (if (route.isStage) "Ziel!" else "Oben!") else "Fahrt beenden?") },
            text = {
                // The race result can be long (top ten, the rider's place): it scrolls inside the dialog.
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    val pro = s.pro
                    val race = s.race
                    if (pro != null && race != null) {
                        RaceResultBlock(race, pro, Modifier.fillMaxWidth())
                        Spacer(Modifier.height(12.dp))
                    }
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
                    if (finishing) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(10.dp))
                            Text("Die Wertung wird berechnet\u00A0…")
                        }
                    } else {
                        Text(
                            if (s.elapsedSec < 30) "Die Fahrt ist kürzer als 30 Sekunden und wird nicht gespeichert."
                            else "Die Fahrt wird mit Strecke und Zeit gespeichert.",
                        )
                    }
                }
            },
            confirmButton = {
                Button(onClick = { end(true) }, enabled = !finishing) { Text(if (s.elapsedSec < 30) "Beenden" else "Beenden und speichern") }
            },
            dismissButton = {
                Row {
                    if (s.elapsedSec >= 30) {
                        TextButton(onClick = { end(false) }, enabled = !finishing) {
                            Text("Verwerfen", color = MaterialTheme.colorScheme.error.copy(alpha = if (finishing) 0.38f else 1f))
                        }
                    }
                    if (done) TextButton(onClick = { finishSeen = true; askEnd = false }, enabled = !finishing) { Text("Ansehen") }
                    else TextButton(onClick = { askEnd = false }, enabled = !finishing) { Text(if (rs.finished) "Zurück" else "Weiterfahren") }
                }
            },
        )
    }
}

/** The landmark sign, fading in and out. */
@Composable
private fun LandmarkToast(visible: Boolean, text: String, short: Boolean, narrow: Boolean, modifier: Modifier) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut(), modifier = modifier) {
        Box(Modifier.background(Color(0xFFFFD230), RoundedCornerShape(10.dp)).padding(horizontal = if (narrow) 16.dp else 24.dp, vertical = if (short) 6.dp else 10.dp)) {
            Text(
                text, color = Color(0xFF202020), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
                style = if (short || narrow) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall,
            )
        }
    }
}

/** The newest route ride screen's onFinished, for an end that outlives the composition it started in. Main thread only. */
private var latestOnFinished: ((rideId: String?) -> Unit)? = null

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
private fun Hud(
    modifier: Modifier,
    horizontal: Dp = 14.dp,
    vertical: Dp = 8.dp,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    Column(modifier.background(Color(0xB3101820), RoundedCornerShape(12.dp)).padding(horizontal = horizontal, vertical = vertical), content = content)
}

/** Route and what is left on the left, the clock and the time to the finish on the right. */
@Composable
private fun TopBar(route: Route, rs: RouteState, short: Boolean, narrow: Boolean, onEnd: () -> Unit) {
    val vertical = if (short) 5.dp else 8.dp
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
        // The name gives way first, so a long stage name never pushes the clock off a phone screen.
        Hud(Modifier.weight(1f, fill = false), vertical = vertical) {
            Text(
                route.displayName, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (short || narrow) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            )
            Text(
                if (route.isStage) "noch %.1f km · noch %.0f Hm".format(rs.remainingM / 1000, (route.ascentM - rs.ascentM).coerceAtLeast(0.0))
                else "noch %.1f km · %.0f Hm bis oben".format(rs.remainingM / 1000, (route.summitElevationM - rs.elevationM).coerceAtLeast(0.0)),
                color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (short) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(Modifier.width(8.dp))
        Hud(Modifier, vertical = vertical) {
            val clock = when {
                short -> 22f
                narrow -> 24f
                else -> 30f
            }
            // At the top the clock shows the exact finish time, as the result and the saved ride do.
            Text(
                formatDuration((rs.finishTimeSec ?: rs.elapsedSec).roundToInt()), color = Color.White,
                fontSize = clock.sp, fontWeight = FontWeight.SemiBold, lineHeight = (clock + 2).sp, maxLines = 1,
            )
            val eta = rs.etaSec
            Text(
                if (eta != null) "Ziel in ≈ ${formatDuration(eta)}" else "Ziel in –", color = Color.White.copy(alpha = 0.85f), maxLines = 1,
                style = if (short) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
            )
        }
        // Always on screen, whether the panel below is open or closed.
        Spacer(Modifier.width(8.dp))
        EndRideButton(onEnd, compact = short || narrow)
    }
}

/** One value of the panel: what it is, the number and its unit. */
private class HudValue(val label: String, val value: String, val unit: String, val color: Color = Color.White)

/**
 * The panel at the bottom. Closed it is one slim row with the four values that matter while riding
 * (power, speed, grade, distance to go) plus pause and the virtual gears; open it adds the other
 * values, the elevation profile and the camera switch ("Beenden" sits in the top bar). A tap on the arrow or a swipe on
 * the row opens and closes it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MetricsPanel(
    s: RideState,
    rs: RouteState,
    route: Route,
    settings: AppSettings,
    gear: String?,
    expanded: Boolean,
    short: Boolean,
    narrow: Boolean,
    maxDetailHeight: Dp,
    showCamera: Boolean,
    onToggle: () -> Unit,
    onPause: (Boolean) -> Unit,
    onEnd: () -> Unit,
    onShiftDown: () -> Unit,
    onShiftUp: () -> Unit,
    onCamera: (Boolean) -> Unit,
    onPicture: () -> Unit,
) {
    val valueSp = when {
        short -> 20f
        narrow -> 22f
        else -> 30f
    }
    val showGears = gear != null && !rs.finished
    val key = listOf(
        HudValue("Leistung", s.displayPower?.toString() ?: "–", "W", Color(0xFFFF8A65)),
        HudValue("Tempo", "%.1f".format(rs.speedKmh), "km/h"),
        HudValue("Steigung", "%.1f".format(rs.gradePct), "%", gradeColor(rs.gradePct)),
        HudValue("Noch", "%.1f".format(rs.remainingM / 1000), "km"),
    )
    Hud(Modifier.fillMaxWidth().animateContentSize(), horizontal = 4.dp, vertical = 2.dp) {
        Column(
            Modifier.fillMaxWidth().pointerInput(expanded) {
                // Swipe up opens, swipe down closes, like a bottom sheet; taps still reach the buttons.
                var drag = 0f
                val threshold = 24.dp.toPx()
                detectVerticalDragGestures(
                    onDragStart = { drag = 0f },
                    onDragEnd = { if ((!expanded && drag < -threshold) || (expanded && drag > threshold)) onToggle() },
                ) { change, dy ->
                    change.consume()
                    drag += dy
                }
            },
        ) {
            // The grip shows that the panel can be pulled; the arrow below is the tap target.
            Box(Modifier.fillMaxWidth().padding(top = 3.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.size(width = 32.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.4f)))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onToggle) {
                    Icon(
                        if (expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        contentDescription = if (expanded) "Werte einklappen" else "Alle Werte zeigen",
                        tint = Color.White,
                    )
                }
                Row(Modifier.weight(1f)) {
                    key.forEach { HudMetric(it, valueSp, Modifier.weight(1f)) }
                }
                if (showGears && !narrow) {
                    Spacer(Modifier.width(8.dp))
                    GearControls(gear, short, onShiftDown, onShiftUp)
                }
                Spacer(Modifier.width(4.dp))
                if (rs.finished) {
                    FilledTonalIconButton(onClick = onEnd) { Icon(Icons.Default.Stop, contentDescription = "Beenden") }
                } else {
                    PauseButton(s.engine.userPaused, onPause)
                }
            }
            if (showGears && narrow) {
                GearControls(gear, short, onShiftDown, onShiftUp, Modifier.align(Alignment.CenterHorizontally))
            }
        }
        AnimatedVisibility(expanded, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = maxDetailHeight).verticalScroll(rememberScrollState())
                    .padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
                verticalArrangement = Arrangement.spacedBy(if (short) 6.dp else 8.dp),
            ) {
                val more = listOf(
                    HudValue("W/kg", s.displayPower?.let { "%.1f".format(it / settings.weightKg) } ?: "–", "W/kg", Color(0xFFFFCC80)),
                    HudValue("Trittfrequenz", s.live.cadence?.toString() ?: "–", "rpm"),
                    HudValue("Puls", s.live.heartRate?.toString() ?: "–", "bpm", Color(0xFFEF5350)),
                    HudValue("Höhe", rs.elevationM.roundToInt().toString(), "m"),
                    HudValue("VAM", rs.vamMph.toString(), "m/h"),
                    HudValue("Ø Leistung", s.stats.avgPower.toString(), "W"),
                )
                val smallSp = valueSp * 0.85f
                // Six in a row where there is room, two rows of three on a phone in portrait.
                (if (narrow) more.chunked(3) else listOf(more)).forEach { row ->
                    Row(Modifier.fillMaxWidth()) { row.forEach { HudMetric(it, smallSp, Modifier.weight(1f)) } }
                }
                RouteProfile(
                    route, rs,
                    Modifier.fillMaxWidth().height(
                        when {
                            short -> 34.dp
                            narrow -> 44.dp
                            else -> 56.dp
                        },
                    ),
                )
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (showCamera) {
                        FilledTonalButton(onClick = onPicture, modifier = Modifier.align(Alignment.CenterVertically)) {
                            Icon(Icons.Default.Tune, null)
                            Spacer(Modifier.width(6.dp))
                            Text("Bild")
                        }
                        CameraSwitch(settings.firstPersonView, onCamera, Modifier.align(Alignment.CenterVertically), compact = true, onDark = true)
                    }
                    Text(
                        "Trainer: %d %% der Steigung".format(settings.trainerDifficultyPct),
                        color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.align(Alignment.CenterVertically),
                    )
                }
            }
        }
    }
}

/** Pause, or "Weiter" after a pause by hand. Before the start or in an auto-pause pedalling goes on, so it offers a pause. */
@Composable
private fun PauseButton(userPaused: Boolean, onPause: (Boolean) -> Unit) {
    if (userPaused) {
        Button(onClick = { onPause(false) }, contentPadding = PaddingValues(horizontal = 14.dp)) {
            Icon(Icons.Default.PlayArrow, null)
            Spacer(Modifier.width(4.dp))
            Text("Weiter")
        }
    } else {
        FilledTonalIconButton(onClick = { onPause(true) }) { Icon(Icons.Default.Pause, contentDescription = "Pause") }
    }
}

/** Virtual gears: down is the easier gear, up the harder one; the label shows "50 × 17 · 14/20". */
@Composable
private fun GearControls(gear: String?, short: Boolean, onDown: () -> Unit, onUp: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        FilledTonalButton(onClick = onDown, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.KeyboardArrowDown, "Leichterer Gang") }
        Text(
            gear.orEmpty(), color = Color.White, maxLines = 1, softWrap = false,
            style = if (short) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        FilledTonalButton(onClick = onUp, contentPadding = PaddingValues(horizontal = 12.dp)) { Icon(Icons.Default.KeyboardArrowUp, "Schwererer Gang") }
    }
}

/** Caption over a number that shrinks to its share of the row, so "37,5 km/h" fits a phone too. */
@Composable
private fun HudMetric(v: HudValue, maxSp: Float, modifier: Modifier = Modifier) {
    Column(modifier.padding(horizontal = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            v.label.uppercase(), color = Color.White.copy(alpha = 0.65f), fontSize = 10.sp, lineHeight = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            val fontScale = LocalDensity.current.fontScale
            // Digits are about 0.6 em wide and the unit is set at half size.
            val em = v.value.length * 0.6f + (if (v.unit.isEmpty()) 0f else 0.2f + v.unit.length * 0.3f)
            val size = (maxWidth.value / em / fontScale).coerceIn(10f, maxSp)
            val unitSize = (size * 0.5f).coerceAtLeast(9f)
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    v.value, color = v.color, fontSize = size.sp, lineHeight = (size * 1.1f).sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false,
                )
                if (v.unit.isNotEmpty()) {
                    Spacer(Modifier.width(2.dp))
                    Text(
                        v.unit, color = Color.White.copy(alpha = 0.7f), fontSize = unitSize.sp, lineHeight = (unitSize * 1.2f).sp,
                        maxLines = 1, softWrap = false, modifier = Modifier.padding(bottom = (size * 0.1f).dp),
                    )
                }
            }
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

