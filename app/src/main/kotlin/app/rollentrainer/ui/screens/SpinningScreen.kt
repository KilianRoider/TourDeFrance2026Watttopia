package app.rollentrainer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.FloatState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.core.coach.CoachScript
import app.rollentrainer.core.metrics.ZoneScheme
import app.rollentrainer.core.model.Segment
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.ride.RideState
import app.rollentrainer.ride.SpinningAudio
import app.rollentrainer.ui.LocalWide
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.theme.Metric
import app.rollentrainer.ui.theme.zoneColorAt
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The studio is dark whatever the system theme; these colours never come from MaterialTheme. */
private val StudioBlack = Color(0xFF050609)
private val StudioText = Color(0xFFF4F6F8)
private val StudioDim = Color(0xFFB4BCC8)
/** Translucent dark pill behind every piece of text, so the HUD stays readable on top of the lights. */
private val StudioPanel = Color(0x9A0A0C14)
private val StudioAccent = Color(0xFFFF5A1F)
private val PanelShape = RoundedCornerShape(20.dp)

/**
 * The light palettes, one per mood (recovery, steady, hard, sprint), as hue/saturation/value so the
 * whole show can drift in hue over time. Entry 0 is the accent of the ring and the coach line, entry 1
 * the riders' rim light; the beams, walls and washes cycle through all of them.
 */
private class Palette(val hues: FloatArray, val sats: FloatArray, val vals: FloatArray) {
    val size: Int get() = hues.size
}

private val Palettes = arrayOf(
    // Recovery: deep blue, violet, teal, indigo.
    Palette(floatArrayOf(228f, 272f, 178f, 250f), floatArrayOf(1f, 0.95f, 1f, 1f), floatArrayOf(1f, 0.95f, 0.9f, 1f)),
    // Steady: magenta, blue, cyan, purple.
    Palette(floatArrayOf(312f, 222f, 188f, 280f), floatArrayOf(1f, 1f, 1f, 0.95f), floatArrayOf(1f, 1f, 1f, 1f)),
    // Hard: orange, red, pink, amber.
    Palette(floatArrayOf(24f, 2f, 334f, 42f), floatArrayOf(1f, 1f, 0.95f, 1f), floatArrayOf(1f, 1f, 1f, 1f)),
    // Sprint: yellow, white, red, warm yellow.
    Palette(floatArrayOf(54f, 50f, 358f, 44f), floatArrayOf(1f, 0.06f, 1f, 0.9f), floatArrayOf(1f, 1f, 1f, 1f)),
)

/** Hue drift: the brushes are rebuilt every [DriftStepSec] seconds, the per-frame colours drift continuously. */
private const val DriftStepSec = 15f
private const val DriftDegPerSec = 0.8f
private const val DriftStepDeg = DriftStepSec * DriftDegPerSec
private val TwoPi = (2 * PI).toFloat()

/**
 * Spinning-class view of a running ride: light show, power ring, animated riders, the instructor's
 * lines and the music. The ride itself is the same [RideState] as in the normal view, so switching
 * views loses nothing. Audio starts when the screen appears and pauses when it leaves.
 */
@Composable
fun SpinningScreen(container: AppContainer, audio: SpinningAudio, onStandardView: () -> Unit, onEnd: () -> Unit) {
    val state by container.session.state.collectAsStateWithLifecycle()
    val settings by container.settings.state.collectAsStateWithLifecycle()
    val track by audio.track.collectAsStateWithLifecycle()
    val coachLine by audio.coachLine.collectAsStateWithLifecycle()
    val mood by audio.mood.collectAsStateWithLifecycle()
    val bpm by audio.beatBpm.collectAsStateWithLifecycle()
    val playing by audio.playing.collectAsStateWithLifecycle()
    val musicEnabled by audio.musicEnabled.collectAsStateWithLifecycle()

    DisposableEffect(audio) {
        audio.setActive(true)
        onDispose { audio.setActive(false) }
    }

    val s = state ?: return
    val e = s.engine
    val ftp = settings.ftp
    val target = e.targetWatts
    val targetPct = if (target != null && ftp > 0) target * 100.0 / ftp else 0.0
    val color = if (target == null) StudioDim else zoneColorAt(settings.zoneScheme.indexOf(targetPct))
    val segment: Segment? = s.timeline?.segments?.getOrNull(e.segmentIndex)
    val standing = isStandingNow(e.segmentLabel, e.cue, segment, e.workoutSec)
    val scale = settings.rideFontScale.factor
    val clock = rememberClock()
    val wide = LocalWide.current
    // The palette accent follows the mood and drifts in hue; eased, so a mood change sweeps instead of jumping.
    val palette = Palettes[mood.coerceIn(0, Palettes.size - 1)]
    val drift = clock.driftStep.intValue * DriftStepDeg
    val accent by animateColorAsState(paletteEntry(palette, 0, drift), tween(1500), label = "accent")
    val rim by animateColorAsState(paletteEntry(palette, 1, drift), tween(1500), label = "rim")
    val liveBpm = if (e.paused) 0 else bpm

    Box(Modifier.fillMaxSize().background(StudioBlack)) {
        LightShow(clock.seconds, palette, mood, liveBpm, clock.driftStep.intValue, Modifier.fillMaxSize())
        // Decided on the space the HUD really gets: a phone in landscape is "wide" for the app but only
        // about 330 dp tall, a phone in portrait is narrow. Both get their own arrangement.
        BoxWithConstraints(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
            val landscapePhone = maxHeight < 480.dp && maxWidth > maxHeight * 1.3f
            val narrow = maxWidth < 600.dp
            val ridersBesideRing = maxHeight >= 380.dp
            val ridersInPortrait = maxHeight >= 700.dp
            when {
                landscapePhone -> Row(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Ring and coach on the left, everything to read and press on the right; the right
                    // column scrolls on the smallest phones instead of cutting the buttons off.
                    Column(Modifier.weight(0.42f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PowerRing(s.displayPower, target, color, accent, clock.seconds, liveBpm, mood, scale, Modifier.weight(1f).fillMaxWidth())
                        if (ridersBesideRing) {
                            Riders(s.live.cadence, e.targetCadence, accent, rim, standing, e.paused, clock.seconds, liveBpm, Modifier.fillMaxWidth().height(72.dp))
                        }
                        CoachBanner(coachLine, s, accent, scale * 0.7f, minHeight = 40.dp)
                    }
                    Column(
                        Modifier.weight(0.58f).fillMaxHeight().verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        StudioHeader(s, settings.zoneScheme, ftp, onStandardView, compact = true)
                        SegmentCard(s, color, targetPct, scale * 0.8f, Modifier.fillMaxWidth(), compact = true)
                        Tiles(s, track, playing, musicEnabled, bpm, compact = true)
                        Controls(container, audio, s, musicEnabled, onStandardView, onEnd, compact = true)
                    }
                }
                narrow -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Phone in portrait: the ring is the hero, the segment reads as one wide card, the
                    // riders only come along when the phone is tall enough.
                    StudioHeader(s, settings.zoneScheme, ftp, onStandardView, compact = true)
                    PowerRing(s.displayPower, target, color, accent, clock.seconds, liveBpm, mood, scale, Modifier.weight(1f).fillMaxWidth())
                    SegmentCard(s, color, targetPct, scale * 0.85f, Modifier.fillMaxWidth(), compact = true)
                    if (ridersInPortrait) {
                        Riders(s.live.cadence, e.targetCadence, accent, rim, standing, e.paused, clock.seconds, liveBpm, Modifier.fillMaxWidth().height(90.dp))
                    }
                    Tiles(s, track, playing, musicEnabled, bpm, compact = true)
                    CoachBanner(coachLine, s, accent, scale * 0.8f, minHeight = 44.dp)
                    Controls(container, audio, s, musicEnabled, onStandardView, onEnd, compact = true)
                }
                else -> Column(
                    Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    StudioHeader(s, settings.zoneScheme, ftp, onStandardView, compact = false)
                    if (wide) {
                        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Riders(s.live.cadence, e.targetCadence, accent, rim, standing, e.paused, clock.seconds, liveBpm, Modifier.weight(0.28f).fillMaxHeight())
                            Column(Modifier.weight(0.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                                PowerRing(s.displayPower, target, color, accent, clock.seconds, liveBpm, mood, scale, Modifier.weight(1f).fillMaxWidth())
                                Tiles(s, track, playing, musicEnabled, bpm, compact = false)
                            }
                            SegmentCard(s, color, targetPct, scale, Modifier.weight(0.32f).fillMaxHeight(), compact = false)
                        }
                    } else {
                        Row(Modifier.weight(0.5f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            PowerRing(s.displayPower, target, color, accent, clock.seconds, liveBpm, mood, scale * 0.8f, Modifier.weight(0.55f).fillMaxHeight())
                            SegmentCard(s, color, targetPct, scale * 0.85f, Modifier.weight(0.45f).fillMaxHeight(), compact = false)
                        }
                        Riders(s.live.cadence, e.targetCadence, accent, rim, standing, e.paused, clock.seconds, liveBpm, Modifier.fillMaxWidth().height(110.dp))
                        Tiles(s, track, playing, musicEnabled, bpm, compact = false)
                    }
                    CoachBanner(coachLine, s, accent, scale)
                    Controls(container, audio, s, musicEnabled, onStandardView, onEnd, compact = false)
                }
            }
        }
        if (e.finished) FinishedOverlay(s, onStandardView, onEnd)
    }
}

/** The frame clock in seconds plus the coarse hue-drift step that re-keys the cached brushes. */
private class StudioClock {
    val seconds = mutableFloatStateOf(0f)
    val driftStep = mutableIntStateOf(0)
}

/** A single frame clock, read inside draw lambdas so only the canvases redraw, nothing recomposes. */
@Composable
private fun rememberClock(): StudioClock {
    val c = remember { StudioClock() }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val sec = (now - start) / 1_000_000_000f
            c.seconds.floatValue = sec
            // Same value, no invalidation: the step only notifies its readers every DriftStepSec seconds.
            c.driftStep.intValue = (sec / DriftStepSec).toInt()
        }
    }
    return c
}

/** Crank angle in radians that turns at [rpm], integrated per frame so cadence changes are smooth. */
@Composable
private fun rememberCrank(rpm: Int, running: Boolean, phase: Float = 0f): FloatState {
    val angle = remember { mutableFloatStateOf(phase) }
    val latestRpm = rememberUpdatedState(if (running) rpm else 0)
    LaunchedEffect(Unit) {
        var last = withFrameNanos { it }
        while (true) withFrameNanos { now ->
            val dt = (now - last) / 1_000_000_000f
            last = now
            angle.floatValue += latestRpm.value / 60f * 2f * PI.toFloat() * dt
        }
    }
    return angle
}

/** Standing follows the same rule as the coach's calls (cue first, then label); jumps alternate every eight seconds. */
private fun isStandingNow(label: String, cue: String?, segment: Segment?, workoutSec: Double): Boolean {
    if (label.contains("Jump", ignoreCase = true) && segment != null) {
        val inSeg = (workoutSec - segment.startSec).coerceAtLeast(0.0)
        return (inSeg / 8).toInt() % 2 == 1
    }
    if (segment == null) return CoachScript.isStanding(label, cue)
    // The last call so far stays in force: "Letzte Minute – aus dem Sattel" lasts the whole minute.
    val inSeg = workoutSec - segment.startSec
    return CoachScript.isStanding(label, segment.cues.filter { it.offsetSec <= inSeg }.map { it.text })
}

/** HSV to an sRGB [Color]; the hue may be any angle, saturation and value are clamped. Allocation-free. */
private fun hsv(hue: Float, sat: Float, value: Float): Color {
    var hh = hue % 360f
    if (hh < 0f) hh += 360f
    val s = sat.coerceIn(0f, 1f)
    val v = value.coerceIn(0f, 1f)
    val c = v * s
    val x = c * (1f - abs((hh / 60f) % 2f - 1f))
    val m = v - c
    var r = 0f
    var g = 0f
    var b = 0f
    when ((hh / 60f).toInt() % 6) {
        0 -> { r = c; g = x }
        1 -> { r = x; g = c }
        2 -> { g = c; b = x }
        3 -> { g = x; b = c }
        4 -> { r = x; b = c }
        else -> { r = c; b = x }
    }
    return Color((r + m).coerceIn(0f, 1f), (g + m).coerceIn(0f, 1f), (b + m).coerceIn(0f, 1f))
}

/** Palette entry [k], hue-shifted by [driftDeg]. */
private fun paletteEntry(p: Palette, k: Int, driftDeg: Float): Color = hsv(p.hues[k] + driftDeg, p.sats[k], p.vals[k])

/** The palette as a closed colour cycle: [u] runs once through all entries per unit, blending between neighbours. */
private fun paletteColor(p: Palette, u: Float, driftDeg: Float): Color {
    val n = p.size
    val x = frac(u) * n
    val k = x.toInt().coerceIn(0, n - 1)
    val k2 = (k + 1) % n
    val f = x - k
    val sm = f * f * (3f - 2f * f)
    var dh = p.hues[k2] - p.hues[k]
    if (dh > 180f) dh -= 360f else if (dh < -180f) dh += 360f
    return hsv(
        p.hues[k] + dh * sm + driftDeg,
        p.sats[k] + (p.sats[k2] - p.sats[k]) * sm,
        p.vals[k] + (p.vals[k2] - p.vals[k]) * sm,
    )
}

private fun frac(x: Float): Float = x - floor(x)

/**
 * The studio's light show behind everything: an LED wall running colour waves, eight moving heads on
 * a top rail and the floor with volumetric cones that sweep and cross, floor washes, a mirror-ball
 * particle field and beat strobes. Everything is a function of the clock, the beat and the mood, so
 * there is no per-frame state; brushes and paths are cached per size, palette and drift step.
 */
@Composable
private fun LightShow(clock: FloatState, palette: Palette, mood: Int, bpm: Int, driftStep: Int, modifier: Modifier) {
    // Its own layer, so the per-frame redraw re-records only this node and not every text and button on top.
    Spacer(
        modifier.graphicsLayer().drawWithCache {
            val w = size.width
            val h = size.height
            val side = min(w, h)
            if (side < 1f) return@drawWithCache onDrawBehind { }
            val n = palette.size
            val drift = driftStep * DriftStepDeg
            // One colour per palette entry; a frame picks, crossfades, moves and scales what is built here.
            val colors = Array(n) { paletteEntry(palette, it, drift) }
            val sparkle = Array(n) { lerp(colors[it], Color.White, 0.55f) }
            val spill = Brush.horizontalGradient(colors.toList() + colors[0], startX = 0f, endX = w)
            // Beam cones point down (+y) from the origin; a rotation aims them. Wide haze, coloured cone, white core.
            val coneLen = w + h
            fun cone(half: Float) = Path().apply {
                moveTo(0f, 0f)
                lineTo(-coneLen * sin(half), coneLen * cos(half))
                lineTo(coneLen * sin(half), coneLen * cos(half))
                close()
            }
            val coneWide = cone(0.13f)
            val coneMid = cone(0.055f)
            val coneCore = cone(0.012f)
            val beamBrushes = Array(n) {
                Brush.linearGradient(
                    listOf(colors[it], colors[it].copy(alpha = 0.45f), Color.Transparent),
                    start = Offset.Zero, end = Offset(0f, coneLen * 0.85f),
                )
            }
            val coreBrush = Brush.linearGradient(listOf(Color.White, Color.White.copy(alpha = 0.3f), Color.Transparent), start = Offset.Zero, end = Offset(0f, coneLen * 0.55f))
            val lampR = 4.dp.toPx()
            val lamp = Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0.5f), Color.Transparent), center = Offset.Zero, radius = lampR * 4f)
            // Floor washes: a unit radial disc that is translated and squashed into ellipses.
            val washR = side * 0.45f
            val washBrushes = Array(n) {
                Brush.radialGradient(listOf(colors[it], colors[it].copy(alpha = 0.35f), Color.Transparent), center = Offset.Zero, radius = washR)
            }
            // LED wall across the back wall.
            val cols = (w / 30.dp.toPx()).toInt().coerceIn(12, 64)
            val rows = (h * 0.56f / 44.dp.toPx()).toInt().coerceIn(3, 9)
            val cellW = w / cols
            val cellH = h * 0.56f / rows
            val cell = Size(cellW * 0.74f, cellH * 0.6f)
            val cellDx = (cellW - cell.width) / 2
            val cellDy = (cellH - cell.height) / 2
            val ledAlpha = 0.40f + 0.06f * mood
            val dot = 1.5.dp.toPx()
            val topY = h * 0.012f
            val floorY = h * 0.985f
            val shade = Brush.verticalGradient(listOf(Color.Transparent, StudioBlack.copy(alpha = 0.72f)), startY = h * 0.55f, endY = h)
            onDrawBehind {
                val t = clock.floatValue
                val live = bpm > 0
                // Paused: a slow virtual beat keeps the colours moving while the pulse just breathes.
                val beat = if (live) t * bpm / 60f else t * 0.25f
                val phase = frac(beat)
                val beatIdx = floor(beat)
                val pulse = if (live) exp(-phase * 5f) else 0.4f + 0.3f * sin(t * 1.2f)
                val speed = if (live) 0.3f + 0.25f * mood else 0.12f
                val hue = t * DriftDegPerSec

                // Colour spill over the whole room, so it is never dark between the lights.
                drawRect(spill, alpha = 0.14f + 0.10f * pulse)

                // LED wall: palette waves scroll across the width, a brightness wave and a chase hit on the beat.
                for (c in 0 until cols) {
                    val u = c.toFloat() / cols
                    val ledColor = paletteColor(palette, u * 1.5f - t * 0.05f, hue)
                    val hit = frac(c * 0.618f + beatIdx * 0.382f)
                    val flash = if (live && hit > 0.78f && phase < 0.35f) 1f - phase / 0.35f else 0f
                    val x = c * cellW + cellDx
                    for (r in 0 until rows) {
                        val wave = 0.5f + 0.5f * sin((u * 2f + r * 0.08f - beat * 0.25f) * TwoPi)
                        val b = (0.12f + 0.6f * wave * wave + 0.6f * flash) * (0.7f + 0.3f * pulse)
                        drawRect(ledColor, Offset(x, r * cellH + cellDy), cell, alpha = (b * ledAlpha).coerceIn(0f, 1f))
                    }
                }

                // Floor washes: big soft ellipses drifting over the floor, each cycling through the palette.
                val washA = 0.28f + 0.22f * pulse
                for (i in 0 until 5) {
                    val a = t * speed * (0.45f + 0.15f * i) + i * 1.3f
                    val cx = w * (0.5f + 0.45f * sin(a))
                    val cy = h * (0.74f + 0.1f * cos(a * 0.6f + i))
                    val sel = frac(i * 0.37f + t * 0.03f + mood * 0.21f) * n
                    val k = sel.toInt().coerceIn(0, n - 1)
                    val xf = ((sel - k - 0.75f) * 4f).coerceIn(0f, 1f)
                    val sx = side * (0.4f + 0.1f * pulse) / washR
                    translate(cx, cy) {
                        scale(sx, sx * 0.38f, Offset.Zero) {
                            drawCircle(washBrushes[k], radius = washR, center = Offset.Zero, alpha = washA * (1f - xf), blendMode = BlendMode.Plus)
                            if (xf > 0f) drawCircle(washBrushes[(k + 1) % n], radius = washR, center = Offset.Zero, alpha = washA * xf, blendMode = BlendMode.Plus)
                        }
                    }
                }

                // Moving heads: four on the top rail aiming down, four on the floor aiming up. Neighbours
                // swing in counter-phase so the beams cross; each head cycles its own colour.
                val beamA = (0.10f + 0.05f * mood) * (0.6f + 0.4f * pulse)
                val swingHz = if (live) 0.55f + 0.3f * mood else 0.2f
                val swingDeg = 26f + 12f * mood
                for (i in 0 until 8) {
                    val top = i < 4
                    val j = i % 4
                    val ox = if (top) w * (0.1f + 0.2667f * j) else w * (0.2f + 0.2f * j)
                    val oy = if (top) topY else floorY
                    val swing = sin(t * swingHz * (1f + 0.13f * j) + i * 1.9f) * swingDeg * (if (j % 2 == 0) 1f else -1f)
                    val deg = (if (top) 0f else 180f) + swing
                    val sel = frac(i * 0.31f + t * (0.06f + 0.02f * mood)) * n
                    val k = sel.toInt().coerceIn(0, n - 1)
                    val xf = ((sel - k - 0.75f) * 4f).coerceIn(0f, 1f)
                    val k2 = (k + 1) % n
                    translate(ox, oy) {
                        rotate(deg, Offset.Zero) {
                            drawPath(coneWide, beamBrushes[k], alpha = beamA * 0.45f * (1f - xf), blendMode = BlendMode.Plus)
                            drawPath(coneMid, beamBrushes[k], alpha = beamA * (1f - xf), blendMode = BlendMode.Plus)
                            if (xf > 0f) {
                                drawPath(coneWide, beamBrushes[k2], alpha = beamA * 0.45f * xf, blendMode = BlendMode.Plus)
                                drawPath(coneMid, beamBrushes[k2], alpha = beamA * xf, blendMode = BlendMode.Plus)
                            }
                            drawPath(coneCore, coreBrush, alpha = beamA * 0.7f, blendMode = BlendMode.Plus)
                        }
                        // The lamp itself: a coloured head with a white hot spot.
                        drawCircle(colors[k], radius = lampR * 2.2f, center = Offset.Zero, alpha = 0.55f + 0.45f * pulse)
                        drawCircle(lamp, radius = lampR * 4f, center = Offset.Zero, alpha = 0.5f + 0.5f * pulse, blendMode = BlendMode.Plus)
                    }
                }

                // Mirror ball: small tinted dots drifting slowly across the room and twinkling, brighter on the beat.
                val count = 60 + 20 * mood
                for (i in 0 until count) {
                    val fx = frac(i * 0.618f)
                    val fy = frac(i * 0.377f)
                    val x = w * frac(fx + t * (0.012f + 0.02f * frac(i * 0.21f)))
                    val y = h * (0.04f + 0.86f * frac(fy + 0.05f * sin(t * 0.3f + i)))
                    val tw = frac(i * 0.53f + t * (0.3f + 0.6f * frac(i * 0.77f)))
                    val twinkle = tw * tw * tw
                    drawCircle(
                        sparkle[i % n], radius = dot * (0.6f + 0.9f * frac(i * 0.71f)), center = Offset(x, y),
                        alpha = (0.2f + 0.8f * twinkle) * (0.55f + 0.45f * pulse),
                    )
                }

                // Strobe on the beat in hard efforts (every other beat) and sprints (every beat); short and dim.
                if (live && mood >= 2) {
                    val every = if (mood == 3) 1 else 2
                    if (beatIdx.toInt() % every == 0 && phase < 0.07f) {
                        drawRect(Color.White, alpha = (if (mood == 3) 0.16f else 0.09f) * (1f - phase / 0.07f))
                    }
                }
                // Darken the lower part so the controls and text stay readable.
                drawRect(shade)
            }
        }
    )
}

@Composable
private fun StudioHeader(s: RideState, scheme: ZoneScheme, ftp: Int, onStandardView: () -> Unit, compact: Boolean) {
    val e = s.engine
    val status = when {
        e.workoutSec == 0.0 && e.paused -> "Tritt in die Pedale, dann geht's los"
        e.autoPaused -> "Pausiert – tritt weiter"
        e.paused -> "Pausiert"
        else -> ""
    }
    Column(
        Modifier.fillMaxWidth().background(StudioPanel, PanelShape)
            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 6.dp else 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    s.workout?.name ?: "Freie Fahrt", color = StudioText,
                    style = if (compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val total = e.totalSec
                Text(
                    if (total > 0) "${formatDuration(e.workoutSec.toInt())} von ${formatDuration(total)}" else formatDuration(s.elapsedSec),
                    color = StudioDim, style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                // On a phone the status goes under the time; beside it, it would squeeze the name away.
                if (compact && status.isNotEmpty()) {
                    Text(status, color = StudioAccent, style = MaterialTheme.typography.labelLarge, maxLines = 2)
                }
            }
            if (!compact && status.isNotEmpty()) {
                Text(
                    status, color = StudioAccent, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.End,
                    maxLines = 2, modifier = Modifier.weight(1f, fill = false).padding(start = 12.dp),
                )
            }
        }
        val tl = s.timeline
        if (tl != null) {
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            ProfileBar(tl, ftp, scheme, e.workoutSec, e.intensityPct, Modifier.fillMaxWidth().height(if (compact) 16.dp else 26.dp))
        }
    }
}

/** The workout profile as zone-coloured bars; what is ridden is bright, what is ahead is dim. */
@Composable
private fun ProfileBar(timeline: Timeline, ftp: Int, scheme: ZoneScheme, progressSec: Double, intensityPct: Int, modifier: Modifier) {
    Canvas(modifier) {
        val total = timeline.totalSec.coerceAtLeast(1)
        val maxPct = (timeline.segments.maxOfOrNull { maxOf(it.startPower.percentOfFtp(ftp), it.endPower.percentOfFtp(ftp)) } ?: 100.0).coerceAtLeast(120.0)
        val gap = 1.dp.toPx()
        fun x(sec: Double) = (sec / total * size.width).toFloat()
        fun y(pct: Double) = (size.height - (pct / maxPct).coerceIn(0.0, 1.0) * size.height).toFloat()
        for (seg in timeline.segments) {
            val a = seg.startPower.percentOfFtp(ftp) * intensityPct / 100.0
            val b = seg.endPower.percentOfFtp(ftp) * intensityPct / 100.0
            val x0 = x(seg.startSec.toDouble())
            val x1 = maxOf(x0 + 1f, x(seg.endSec.toDouble()) - gap)
            val past = seg.endSec <= progressSec
            val color = zoneColorAt(scheme.indexOf((a + b) / 2))
            val path = Path().apply {
                moveTo(x0, size.height)
                lineTo(x0, y(a))
                lineTo(x1, y(b))
                lineTo(x1, size.height)
                close()
            }
            drawPath(path, color.copy(alpha = if (past) 0.95f else 0.4f))
        }
        val cx = x(progressSec)
        drawLine(Color.White, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 2.dp.toPx())
    }
}

/**
 * Power against the target on a 270° ring; the ring is full at 130 % of the target, the tick marks 100 %.
 * The ring, its halo and the beat ripple take the palette [accent]; the target line keeps the zone colour.
 */
@Composable
private fun PowerRing(power: Int?, target: Int?, zone: Color, accent: Color, clock: FloatState, bpm: Int, mood: Int, textScale: Float, modifier: Modifier) {
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        // The number is capped at about a third of the ring's diameter, so the smaller ring of a phone
        // keeps "245" and the target inside it (sizes are sp, the ring is dp: the font scale converts).
        val side = minOf(maxWidth, maxHeight).value.coerceAtMost(4000f)
        val powerSp = minOf(92f * textScale, side * 0.3f / fontScale).coerceAtLeast(24f)
        val targetSp = minOf(22f * textScale, maxOf(14f, powerSp * 0.3f))
        val padH = if (side < 240f) 12.dp else 20.dp
        // Own layer and cached brush/stroke: the halo pulses every frame without re-recording the numbers.
        Spacer(
            Modifier.fillMaxSize().graphicsLayer().drawWithCache {
                val d = size.minDimension
                if (d < 1f) return@drawWithCache onDrawBehind { }
                val stroke = d * 0.075f
                val inset = stroke * 0.5f + d * 0.03f
                val arc = Size(d - 2 * inset, d - 2 * inset)
                val mid = Offset(size.width / 2, size.height / 2)
                val topLeft = Offset((size.width - arc.width) / 2, (size.height - arc.height) / 2)
                val ringStyle = Stroke(stroke, cap = StrokeCap.Round)
                val rippleStyle = Stroke(2.dp.toPx())
                val haloR = d * 0.5f
                val haloBrush = Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.4f), Color.Transparent), center = mid, radius = haloR)
                val arcGlow = lerp(accent, Color.White, 0.35f)
                val tickWidth = 3.dp.toPx()
                val start = 135f
                val sweep = 270f
                onDrawBehind {
                    val t = clock.floatValue
                    val beat = if (bpm > 0) t * bpm / 60f else t * 0.25f
                    val phase = frac(beat)
                    val pulse = if (bpm > 0) exp(-phase * 6f) else 0.4f + 0.3f * sin(t * 1.2f)
                    // Halo that breathes with the beat, stronger in hard efforts.
                    val halo = d * (0.5f + 0.04f * pulse * (0.5f + 0.5f * mood))
                    scale(halo / haloR, pivot = mid) {
                        drawCircle(haloBrush, radius = haloR, center = mid, alpha = 0.22f + 0.16f * pulse)
                    }
                    // A ripple that leaves the ring on every beat.
                    if (bpm > 0) {
                        drawCircle(accent, radius = arc.width / 2 + stroke * (0.6f + 1.6f * phase), center = mid, alpha = 0.45f * (1f - phase), style = rippleStyle)
                    }
                    drawArc(Color.White.copy(alpha = 0.12f), start, sweep, false, topLeft, arc, style = ringStyle)
                    val fill = if (target != null && target > 0 && power != null) (power / (target * 1.3f)).coerceIn(0f, 1f) else 0f
                    if (fill > 0f) {
                        drawArc(accent, start, sweep * fill, false, topLeft, arc, style = ringStyle)
                        drawArc(arcGlow, start, sweep * fill, false, topLeft, arc, alpha = 0.35f * pulse, style = ringStyle)
                    }
                    if (target != null && target > 0) {
                        val ang = (start + sweep / 1.3f) * PI.toFloat() / 180f
                        val r0 = arc.width / 2 - stroke * 0.8f
                        val r1 = arc.width / 2 + stroke * 0.8f
                        drawLine(
                            Color.White, Offset(mid.x + r0 * cos(ang), mid.y + r0 * sin(ang)),
                            Offset(mid.x + r1 * cos(ang), mid.y + r1 * sin(ang)), strokeWidth = tickWidth, cap = StrokeCap.Round,
                        )
                    }
                }
            }
        )
        Column(
            Modifier.background(StudioPanel, PanelShape).padding(horizontal = padH, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                power?.toString() ?: "–",
                color = StudioText, fontSize = powerSp.sp, lineHeight = (powerSp * 1.04f).sp,
                fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge, maxLines = 1, softWrap = false,
            )
            Text("WATT", color = StudioDim, style = MaterialTheme.typography.labelLarge)
            if (target != null) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Ziel $target W", color = zone, fontSize = targetSp.sp, fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** Heart rate, cadence and what is playing, under the ring. */
@Composable
private fun Tiles(s: RideState, track: String?, playing: Boolean, musicEnabled: Boolean, bpm: Int, compact: Boolean) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
        // Short captions on a phone: "TRITTFREQUENZ" alone is wider than a third of the screen.
        Tile("Puls", s.live.heartRate?.toString() ?: "–", "bpm", Metric.HeartRate, compact, Modifier.weight(1f))
        Tile(if (compact) "Tritt" else "Trittfrequenz", s.live.cadence?.toString() ?: "–", "rpm", Metric.Cadence, compact, Modifier.weight(1f))
        Box(
            Modifier.weight(1.4f).background(StudioPanel, PanelShape)
                .padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (musicEnabled) Icons.Default.MusicNote else Icons.Default.VolumeOff, null, tint = if (playing) StudioAccent else StudioDim)
                Spacer(Modifier.width(if (compact) 6.dp else 8.dp))
                Column {
                    Text(
                        when {
                            !musicEnabled -> "Musik aus"
                            track != null -> track
                            playing -> "Studio-Beat · $bpm BPM"
                            else -> "Musik pausiert"
                        },
                        color = StudioText, style = if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (track != null) "Eigene Musik" else "Takt folgt der Trittfrequenz", color = StudioDim,
                        style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, accent: Color, compact: Boolean, modifier: Modifier) {
    Column(modifier.background(StudioPanel, PanelShape).padding(horizontal = if (compact) 10.dp else 12.dp, vertical = 8.dp)) {
        Text(label.uppercase(), color = accent, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value, color = StudioText, maxLines = 1, softWrap = false,
                style = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            )
            Spacer(Modifier.width(4.dp))
            Text(
                unit, color = StudioDim, maxLines = 1, softWrap = false,
                style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge,
                modifier = Modifier.padding(bottom = if (compact) 3.dp else 4.dp),
            )
        }
    }
}

/** What to do right now: label, target, cadence, the remaining time big, and what follows. */
@Composable
private fun SegmentCard(s: RideState, color: Color, targetPct: Double, scale: Float, modifier: Modifier, compact: Boolean) {
    if (compact) {
        CompactSegmentCard(s, color, targetPct, scale, modifier)
        return
    }
    val e = s.engine
    val next = e.nextSegment
    Column(modifier.background(StudioPanel, PanelShape).padding(16.dp), verticalArrangement = Arrangement.Center) {
        Text("JETZT", color = color, style = MaterialTheme.typography.labelLarge)
        Text(
            e.segmentLabel.ifBlank { if (s.timeline == null) "Freie Fahrt" else "Abschnitt ${e.segmentIndex + 1}" },
            color = StudioText, fontSize = (26f * scale).sp, lineHeight = (30f * scale).sp, fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(8.dp))
        val target = e.targetWatts
        if (target != null) {
            Text(
                if (e.erg) "$target W · ${targetPct.roundToInt()} %" else "frei · so hart du kannst",
                color = StudioText, fontSize = (20f * scale).sp, style = MaterialTheme.typography.titleLarge,
            )
        }
        e.targetCadence?.let { Text("$it rpm", color = Metric.Cadence, fontSize = (20f * scale).sp, style = MaterialTheme.typography.titleLarge) }
        if (s.timeline != null && !e.finished) {
            Spacer(Modifier.height(10.dp))
            val rem = e.segmentRemainingSec
            val countdown = next != null && rem in 0..2 && !e.paused
            Text(
                if (countdown) "${rem + 1}" else formatDuration(rem),
                color = if (countdown) StudioAccent else StudioText, fontSize = (56f * scale).sp, lineHeight = (60f * scale).sp,
                fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayMedium, maxLines = 1,
            )
            Text("verbleibend", color = StudioDim, style = MaterialTheme.typography.labelLarge)
        }
        if (next != null) {
            Spacer(Modifier.height(12.dp))
            Text("GLEICH", color = StudioDim, style = MaterialTheme.typography.labelMedium)
            Text(
                buildString {
                    append(next.label.ifBlank { "Abschnitt" })
                    append(" · ").append(formatDuration(next.durationSec))
                    if (!next.erg) append(" · frei") else e.nextTargetWatts?.let { append(" · $it W") }
                    next.cadence?.let { append(" · $it rpm") }
                },
                color = StudioText, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
            )
        }
        if (e.intensityPct != 100) {
            Spacer(Modifier.height(8.dp))
            Text("Intensität ${e.intensityPct} %", color = StudioAccent, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/**
 * The segment card of a phone: one wide card, what to do on the left and the remaining time on
 * the right, then one line for what follows. The stacked tablet card would need 300 dp of height.
 */
@Composable
private fun CompactSegmentCard(s: RideState, color: Color, targetPct: Double, scale: Float, modifier: Modifier) {
    val e = s.engine
    val next = e.nextSegment
    Column(modifier.background(StudioPanel, PanelShape).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("JETZT", color = color, style = MaterialTheme.typography.labelMedium)
                Text(
                    e.segmentLabel.ifBlank { if (s.timeline == null) "Freie Fahrt" else "Abschnitt ${e.segmentIndex + 1}" },
                    color = StudioText, fontSize = (24f * scale).sp, lineHeight = (28f * scale).sp, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.headlineSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                val target = e.targetWatts
                val line = buildString {
                    if (target != null) append(if (e.erg) "$target W · ${targetPct.roundToInt()} %" else "frei · so hart du kannst")
                    e.targetCadence?.let { if (isNotEmpty()) append(" · "); append("$it rpm") }
                }
                if (line.isNotEmpty()) {
                    Text(line, color = StudioText, fontSize = (18f * scale).sp, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (s.timeline != null && !e.finished) {
                val rem = e.segmentRemainingSec
                val countdown = next != null && rem in 0..2 && !e.paused
                Column(Modifier.padding(start = 12.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        if (countdown) "${rem + 1}" else formatDuration(rem),
                        color = if (countdown) StudioAccent else StudioText, fontSize = (40f * scale).sp, lineHeight = (44f * scale).sp,
                        fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayMedium, maxLines = 1, softWrap = false,
                    )
                    Text("verbleibend", color = StudioDim, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        if (next != null || e.intensityPct != 100) {
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    if (next != null) {
                        append("Gleich: ").append(next.label.ifBlank { "Abschnitt" })
                        append(" · ").append(formatDuration(next.durationSec))
                        if (!next.erg) append(" · frei") else e.nextTargetWatts?.let { append(" · $it W") }
                    }
                    if (e.intensityPct != 100) {
                        if (isNotEmpty()) append(" · ")
                        append("Intensität ${e.intensityPct} %")
                    }
                },
                color = StudioDim, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The coach's line in the palette accent on a dark pill, scaled in when it changes. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun CoachBanner(line: String?, s: RideState, accent: Color, scale: Float, minHeight: Dp = 52.dp) {
    val text = line ?: s.engine.cue
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)).togetherWith(fadeOut()) },
            label = "coach",
        ) { t ->
            if (t != null) {
                Text(
                    t, color = lerp(accent, Color.White, 0.4f), fontSize = (26f * scale).sp, lineHeight = (30f * scale).sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.background(StudioPanel, PanelShape)
                        .padding(horizontal = if (minHeight < 52.dp) 12.dp else 18.dp, vertical = if (minHeight < 52.dp) 6.dp else 8.dp),
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(
    container: AppContainer,
    audio: SpinningAudio,
    s: RideState,
    musicEnabled: Boolean,
    onStandardView: () -> Unit,
    onEnd: () -> Unit,
    compact: Boolean,
) {
    val session = container.session
    val e = s.engine
    val inWorkout = s.timeline != null && !e.continuing && !e.finished
    // Under the finished overlay nothing here may react, not even through the keyboard or TalkBack.
    val on = !e.finished
    // "Weiter" only after a pause by hand; before the start or in an auto-pause pedalling goes on.
    val resume = e.userPaused
    val buttons: @Composable () -> Unit = {
        StudioButton(if (resume) "Weiter" else "Pause", if (resume) Icons.Default.PlayArrow else Icons.Default.Pause, primary = true, enabled = on) { session.setPaused(!resume) }
        if (inWorkout) {
            StudioButton("Abschnitt", Icons.Default.SkipPrevious, enabled = on) { session.back() }
            StudioButton("Abschnitt", Icons.Default.SkipNext, enabled = on) { session.skip() }
            StudioButton("−5 %", enabled = on) { session.adjustIntensity(-5) }
            StudioButton("+5 %", enabled = on) { session.adjustIntensity(5) }
        }
        StudioButton("Nächster Titel", Icons.Default.MusicNote, enabled = on) { audio.nextTrack() }
        StudioButton(if (musicEnabled) "Musik aus" else "Musik an", if (musicEnabled) Icons.Default.VolumeOff else Icons.Default.VolumeUp, enabled = on) { audio.toggleMusic() }
        StudioButton("Normale Ansicht", Icons.Outlined.GridView, enabled = on, onClick = onStandardView)
        StudioButton("Beenden", Icons.Default.Stop, enabled = on, onClick = onEnd)
    }
    if (compact) {
        // One line that scrolls sideways: wrapped, the nine buttons would take four lines of a phone.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) { buttons() }
    } else {
        FlowRow(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) { buttons() }
    }
}

@Composable
private fun StudioButton(text: String, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) StudioAccent else StudioPanel,
            contentColor = Color.White,
        ),
        modifier = Modifier.height(48.dp),
    ) {
        if (icon != null) {
            Icon(icon, null)
            Spacer(Modifier.width(6.dp))
        }
        Text(text, maxLines = 1)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FinishedOverlay(s: RideState, onStandardView: () -> Unit, onEnd: () -> Unit) {
    // The pointer input makes the overlay a hit target, so taps on the dimmed area stop here instead of
    // reaching the controls underneath; its own buttons still work.
    BoxWithConstraints(
        Modifier.fillMaxSize().background(StudioBlack.copy(alpha = 0.8f)).pointerInput(Unit) { }
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentAlignment = Alignment.Center,
    ) {
        // "Geschafft!" at 64 sp is wider than a phone in portrait.
        val titleSp = if (maxWidth < 600.dp || maxHeight < 480.dp) 44f else 64f
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(24.dp),
        ) {
            Text("Geschafft!", color = StudioText, fontSize = titleSp.sp, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge, maxLines = 1)
            val st = s.stats
            Text(
                "${formatDuration(st.seconds)} · ${st.avgPower} W · NP ${st.normalizedPower} W · TSS ${st.tss.roundToInt()}",
                color = StudioDim, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StudioButton("Beenden und speichern", Icons.Default.Stop, primary = true, onClick = onEnd)
                StudioButton("Normale Ansicht", Icons.Outlined.GridView, onClick = onStandardView)
            }
        }
    }
}

/**
 * A row of stylised riders seen from the side. The instructor is in front and pedals at the target
 * cadence; the riders behind pedal at the rider's real cadence, so the picture shows at a glance
 * whether the legs keep up with the class. The silhouettes get a rim light in [rim] and a coloured
 * floor glow in [accent] that breathes with the beat.
 */
@Composable
private fun Riders(cadence: Int?, targetCadence: Int?, accent: Color, rim: Color, standing: Boolean, paused: Boolean, clock: FloatState, bpm: Int, modifier: Modifier) {
    val riderCrank = rememberCrank(cadence ?: 0, !paused)
    val coachCrank = rememberCrank(targetCadence ?: 85, !paused, phase = 1.2f)
    // Own layer and cached glow brush: the cranks turn every frame without re-recording the rest of the screen.
    Spacer(
        modifier.graphicsLayer().drawWithCache {
            val w = size.width
            val h = size.height
            // Units: one unit is the wheel radius; a bike with rider is about 4.4 units wide and 4.6 high.
            val unitCoach = min(h / 5.2f, w / 7.5f)
            val unitRider = unitCoach * 0.62f
            // The wide layout can squeeze this to nothing (tall controls, split screen); nothing to draw then.
            if (unitRider < 1f) return@drawWithCache onDrawBehind { }
            val ground = h * 0.95f
            val glowR = unitCoach * 2.6f
            val glow = Brush.radialGradient(listOf(accent, accent.copy(alpha = 0.35f), Color.Transparent), center = Offset.Zero, radius = glowR)
            // Pale, tinted silhouettes so the coloured rim light reads as light hitting them.
            val body = lerp(accent, Color.White, 0.6f)
            val lineW = 2.dp.toPx()
            onDrawBehind {
                val t = clock.floatValue
                val pulse = if (bpm > 0) exp(-frac(t * bpm / 60f) * 5f) else 0.4f + 0.3f * sin(t * 1.2f)
                val coachAngle = coachCrank.floatValue
                val riderAngle = riderCrank.floatValue
                // Coloured floor glow under every bike, the instructor's the largest.
                for (i in 0 until 3) {
                    val ox = w * (0.12f + 0.2f * i)
                    val oy = ground - unitRider * 0.25f * (2 - i)
                    translate(ox, oy) {
                        scale(0.62f, 0.2f, Offset.Zero) {
                            drawCircle(glow, radius = glowR, center = Offset.Zero, alpha = 0.3f + 0.25f * pulse, blendMode = BlendMode.Plus)
                        }
                    }
                }
                translate(w * 0.72f, ground) {
                    scale(1f, 0.26f, Offset.Zero) {
                        drawCircle(glow, radius = glowR, center = Offset.Zero, alpha = 0.4f + 0.35f * pulse, blendMode = BlendMode.Plus)
                    }
                }
                // Riders behind, slightly offset and dimmer, like the back rows of the studio.
                for (i in 0 until 3) {
                    val ox = w * (0.12f + 0.2f * i)
                    val oy = ground - unitRider * 0.9f - unitRider * 0.25f * (2 - i)
                    val fade = 0.5f + 0.12f * i
                    drawLitRider(Offset(ox, oy), unitRider, riderAngle + i * 1.7f, body.copy(alpha = fade), rim.copy(alpha = fade), standing)
                }
                drawLitRider(Offset(w * 0.72f, ground), unitCoach, coachAngle, body, rim, standing, front = true)
                // Floor line under the front row.
                drawLine(rim.copy(alpha = 0.5f), Offset(0f, ground), Offset(w, ground), strokeWidth = lineW)
            }
        }
    )
}

/** A rider with a soft and a sharp rim light in [rim] behind the [body] silhouette. */
private fun DrawScope.drawLitRider(origin: Offset, unit: Float, crank: Float, body: Color, rim: Color, standing: Boolean, front: Boolean = false) {
    drawRider(origin, unit, crank, rim.copy(alpha = rim.alpha * 0.22f), standing, front, widen = 3.4f)
    drawRider(origin, unit, crank, rim.copy(alpha = rim.alpha * 0.65f), standing, front, widen = 1.9f)
    drawRider(origin, unit, crank, body, standing, front)
}

/**
 * Draws a bike and a rider as strokes. [origin] is the ground point under the bottom bracket,
 * [unit] the wheel radius in pixels; y grows upwards in bike coordinates. [widen] thickens every
 * stroke for the rim-light passes.
 */
private fun DrawScope.drawRider(origin: Offset, unit: Float, crank: Float, color: Color, standing: Boolean, front: Boolean = false, widen: Float = 1f) {
    fun p(x: Float, y: Float) = Offset(origin.x + x * unit, origin.y - y * unit)
    val extra = unit * 0.07f * (widen - 1f)
    val thin = unit * 0.09f + extra
    val thick = unit * 0.2f + extra
    val bike = color.copy(alpha = color.alpha * 0.7f)
    val rw = p(-1.5f, 1f)
    val fw = p(1.5f, 1f)
    val bb = p(0f, 0.95f)
    val seat = p(-0.55f, 2.45f)
    val bar = p(0.95f, 2.3f)
    drawCircle(bike, unit, rw, style = Stroke(thin))
    drawCircle(bike, unit, fw, style = Stroke(thin))
    drawCircle(bike, unit * 0.22f, bb, style = Stroke(thin))
    drawLine(bike, rw, bb, thin, StrokeCap.Round)
    drawLine(bike, bb, seat, thin, StrokeCap.Round)
    drawLine(bike, seat, bar, thin, StrokeCap.Round)
    drawLine(bike, bar, bb, thin, StrokeCap.Round)
    drawLine(bike, seat, rw, thin, StrokeCap.Round)
    drawLine(bike, bar, fw, thin, StrokeCap.Round)
    drawLine(bike, p(-0.8f, 2.5f), p(-0.3f, 2.5f), thin, StrokeCap.Round) // saddle

    // Rider: hip and shoulder move up and forward when standing.
    val hip = if (standing) p(-0.05f, 3.0f) else p(-0.55f, 2.6f)
    val shoulder = if (standing) p(0.6f, 3.85f) else p(0.45f, 3.5f)
    val head = if (standing) p(0.85f, 4.3f) else p(0.75f, 3.9f)
    // Far leg first, dimmer, half a turn ahead.
    leg(hip, bb, unit, crank + PI.toFloat(), color.copy(alpha = color.alpha * 0.45f), thick)
    drawLine(color, hip, shoulder, thick, StrokeCap.Round)
    drawLine(color, shoulder, bar, thin * 1.3f, StrokeCap.Round)
    drawCircle(color, unit * 0.3f + extra * 0.5f, head)
    leg(hip, bb, unit, crank, color, thick)
    if (front) {
        // The instructor's little podium.
        drawLine(color.copy(alpha = color.alpha * 0.5f), p(-2.3f, -0.02f), p(2.3f, -0.02f), thin * 1.5f, StrokeCap.Round)
    }
}

/** Thigh and shin from the hip to the pedal, the knee found by two-link inverse kinematics. */
private fun DrawScope.leg(hip: Offset, bb: Offset, unit: Float, crank: Float, color: Color, width: Float) {
    val crankLen = unit * 0.38f
    // Screen y grows downwards, so a positive sine moves the pedal down, which reads as forward rotation.
    val pedal = Offset(bb.x + crankLen * cos(crank), bb.y + crankLen * sin(crank))
    val l1 = unit * 1.35f
    val l2 = unit * 1.3f
    val dx = pedal.x - hip.x
    val dy = pedal.y - hip.y
    // The range stays valid even for a tiny unit, where l1 + l2 would fall below the lower bound.
    val d = sqrt(dx * dx + dy * dy).coerceIn(0.1f, (l1 + l2 - 0.01f).coerceAtLeast(0.1f))
    val base = atan2(dy, dx)
    val cosA = ((l1 * l1 + d * d - l2 * l2) / (2 * l1 * d)).coerceIn(-1f, 1f)
    val a = acos(cosA)
    // The knee points forward (screen right), which is the solution rotated clockwise on screen.
    val knee = Offset(hip.x + l1 * cos(base - a), hip.y + l1 * sin(base - a))
    drawLine(color, hip, knee, width, StrokeCap.Round)
    drawLine(color, knee, pedal, width, StrokeCap.Round)
    drawLine(color, Offset(pedal.x - unit * 0.15f, pedal.y), Offset(pedal.x + unit * 0.15f, pedal.y), width * 0.6f, StrokeCap.Round)
}
