package app.rollentrainer.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
private val StudioDim = Color(0xFF9AA3AF)
private val StudioPanel = Color(0x14FFFFFF)
private val StudioAccent = Color(0xFFFF5A1F)
private val PanelShape = RoundedCornerShape(20.dp)

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

    Box(Modifier.fillMaxSize().background(StudioBlack)) {
        LightShow(clock, color, mood, if (e.paused) 0 else bpm, Modifier.fillMaxSize())
        Column(
            Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StudioHeader(s, settings.zoneScheme, ftp, onStandardView)
            if (wide) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Riders(s.live.cadence, e.targetCadence, color, standing, e.paused, Modifier.weight(0.28f).fillMaxHeight())
                    Column(Modifier.weight(0.4f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                        PowerRing(s.displayPower, target, color, clock, bpm, mood, scale, Modifier.weight(1f).fillMaxWidth())
                        Tiles(s, track, playing, musicEnabled, bpm)
                    }
                    SegmentCard(s, color, targetPct, scale, Modifier.weight(0.32f).fillMaxHeight())
                }
            } else {
                Row(Modifier.weight(0.5f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    PowerRing(s.displayPower, target, color, clock, bpm, mood, scale * 0.8f, Modifier.weight(0.55f).fillMaxHeight())
                    SegmentCard(s, color, targetPct, scale * 0.85f, Modifier.weight(0.45f).fillMaxHeight())
                }
                Riders(s.live.cadence, e.targetCadence, color, standing, e.paused, Modifier.fillMaxWidth().height(110.dp))
                Tiles(s, track, playing, musicEnabled, bpm)
            }
            CoachBanner(coachLine, s, scale)
            Controls(container, audio, s, musicEnabled, onStandardView, onEnd)
        }
        if (e.finished) FinishedOverlay(s, onStandardView, onEnd)
    }
}

/** A frame clock in seconds, read inside draw lambdas so only the canvases redraw, nothing recomposes. */
@Composable
private fun rememberClock(): FloatState {
    val t = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { now -> t.floatValue = (now - start) / 1_000_000_000f }
    }
    return t
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
    return CoachScript.isStanding(label, cue)
}

/**
 * Spotlights, sweeping beams, particles and strobes behind everything. All of it is a function of
 * the clock, the beat and the mood, so there is no per-frame state.
 */
@Composable
private fun LightShow(clock: FloatState, color: Color, mood: Int, bpm: Int, modifier: Modifier) {
    // Its own layer, so the per-frame redraw re-records only this node and not every text and button on top.
    Spacer(
        modifier.graphicsLayer().drawWithCache {
            val w = size.width
            val h = size.height
            val side = min(w, h)
            if (side < 1f) return@drawWithCache onDrawBehind { }
            // Brushes and the beam path are built once per size and colour; a frame only moves, scales and fades them.
            val spotR = side * 0.42f
            val spot = Brush.radialGradient(listOf(color, Color.Transparent), center = Offset.Zero, radius = spotR)
            val origins = listOf(Offset(0f, -h * 0.05f), Offset(w, -h * 0.05f))
            val beamBrushes = origins.map { o -> Brush.linearGradient(listOf(Color.White, Color.Transparent), start = o, end = Offset(o.x, o.y + h)) }
            val beam = Path()
            val shade = Brush.verticalGradient(listOf(Color.Transparent, StudioBlack.copy(alpha = 0.75f)), startY = h * 0.5f, endY = h)
            val dot = 1.5.dp.toPx()
            onDrawBehind {
                val t = clock.floatValue
                val beat = if (bpm > 0) t * bpm / 60f else 0f
                val phase = beat - floor(beat)
                val pulse = if (bpm > 0) exp(-phase * 5f) else 0.3f
                val speed = 0.12f + 0.18f * mood

                // Soft spotlights drifting across the floor, pulsing with the beat.
                for (i in 0 until 4) {
                    val a = t * speed * (1f + 0.25f * i) + i * 1.9f
                    val cx = w * (0.5f + 0.42f * cos(a))
                    val cy = h * (0.4f + 0.3f * sin(a * 0.7f + i))
                    val r = side * (0.3f + 0.12f * pulse)
                    translate(cx, cy) {
                        scale(r / spotR, pivot = Offset.Zero) {
                            drawCircle(spot, radius = spotR, center = Offset.Zero, alpha = 0.16f + 0.16f * pulse)
                        }
                    }
                }
                // Beams from the top corners sweeping the room, faster when it gets hard.
                val beamAlpha = 0.05f + 0.05f * mood
                for (i in 0 until 2) {
                    val origin = origins[i]
                    val base = if (i == 0) 0.25f else -0.25f
                    val ang = PI.toFloat() / 2 + base + sin(t * (0.5f + 0.4f * mood) + i * 2.1f) * 0.55f
                    val len = w + h
                    val half = 0.035f
                    beam.reset()
                    beam.moveTo(origin.x, origin.y)
                    beam.lineTo(origin.x + len * cos(ang - half), origin.y + len * sin(ang - half))
                    beam.lineTo(origin.x + len * cos(ang + half), origin.y + len * sin(ang + half))
                    beam.close()
                    drawPath(beam, beamBrushes[i], alpha = (beamAlpha + 0.08f * pulse).coerceIn(0f, 1f))
                }
                // Particles rising in hard efforts; positions come from the index, so nothing needs storing.
                if (mood >= 2) {
                    val count = if (mood == 3) 60 else 30
                    for (i in 0 until count) {
                        val fx = frac(i * 0.618f)
                        val fy = frac(i * 0.377f + t * (0.04f + 0.06f * frac(i * 0.21f)))
                        val x = w * (fx + 0.02f * sin(t + i))
                        val y = h * (1f - fy)
                        drawCircle(color.copy(alpha = 0.25f + 0.35f * frac(i * 0.53f)), radius = dot + dot * frac(i * 0.71f), center = Offset(x, y))
                    }
                }
                // Strobe on the beat during sprints; short and dim so it stays pleasant.
                if (mood == 3 && bpm > 0 && phase < 0.08f) {
                    drawRect(Color.White.copy(alpha = 0.10f * (1f - phase / 0.08f)))
                }
                // Darken the lower part so the controls and text stay readable.
                drawRect(shade)
            }
        }
    )
}

private fun frac(x: Float): Float = x - floor(x)

@Composable
private fun StudioHeader(s: RideState, scheme: ZoneScheme, ftp: Int, onStandardView: () -> Unit) {
    val e = s.engine
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.workout?.name ?: "Freie Fahrt", color = StudioText, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val total = e.totalSec
                Text(
                    if (total > 0) "${formatDuration(e.workoutSec.toInt())} von ${formatDuration(total)}" else formatDuration(s.elapsedSec),
                    color = StudioDim, style = MaterialTheme.typography.bodyMedium,
                )
            }
            Text(
                when {
                    e.workoutSec == 0.0 && e.paused -> "Tritt in die Pedale, dann geht's los"
                    e.autoPaused -> "Pausiert – tritt weiter"
                    e.paused -> "Pausiert"
                    else -> ""
                },
                color = StudioAccent, style = MaterialTheme.typography.titleMedium,
            )
        }
        val tl = s.timeline
        if (tl != null) {
            Spacer(Modifier.height(6.dp))
            ProfileBar(tl, ftp, scheme, e.workoutSec, e.intensityPct, Modifier.fillMaxWidth().height(26.dp))
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

/** Power against the target on a 270° ring; the ring is full at 130 % of the target, the tick marks 100 %. */
@Composable
private fun PowerRing(power: Int?, target: Int?, color: Color, clock: FloatState, bpm: Int, mood: Int, textScale: Float, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
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
                val haloR = d * 0.5f
                val haloBrush = Brush.radialGradient(listOf(color, Color.Transparent), center = mid, radius = haloR)
                val tickWidth = 3.dp.toPx()
                val start = 135f
                val sweep = 270f
                onDrawBehind {
                    val t = clock.floatValue
                    val beat = t * bpm / 60f
                    val pulse = exp(-(beat - floor(beat)) * 6f)
                    // Halo that breathes with the beat, stronger in hard efforts.
                    val halo = d * (0.5f + 0.04f * pulse * (0.5f + 0.5f * mood))
                    scale(halo / haloR, pivot = mid) {
                        drawCircle(haloBrush, radius = haloR, center = mid, alpha = 0.18f + 0.12f * pulse)
                    }
                    drawArc(Color.White.copy(alpha = 0.10f), start, sweep, false, topLeft, arc, style = ringStyle)
                    val frac = if (target != null && target > 0 && power != null) (power / (target * 1.3f)).coerceIn(0f, 1f) else 0f
                    if (frac > 0f) {
                        drawArc(color, start, sweep * frac, false, topLeft, arc, style = ringStyle)
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
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                power?.toString() ?: "–",
                color = StudioText, fontSize = (92f * textScale).sp, lineHeight = (96f * textScale).sp,
                fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge, maxLines = 1,
            )
            Text("WATT", color = StudioDim, style = MaterialTheme.typography.labelLarge)
            if (target != null) {
                Spacer(Modifier.height(4.dp))
                Text("Ziel $target W", color = color, fontSize = (22f * textScale).sp, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleLarge)
            }
        }
    }
}

/** Heart rate, cadence and what is playing, under the ring. */
@Composable
private fun Tiles(s: RideState, track: String?, playing: Boolean, musicEnabled: Boolean, bpm: Int) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tile("Puls", s.live.heartRate?.toString() ?: "–", "bpm", Metric.HeartRate, Modifier.weight(1f))
        Tile("Trittfrequenz", s.live.cadence?.toString() ?: "–", "rpm", Metric.Cadence, Modifier.weight(1f))
        Box(Modifier.weight(1.4f).background(StudioPanel, PanelShape).padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(if (musicEnabled) Icons.Default.MusicNote else Icons.Default.VolumeOff, null, tint = if (playing) StudioAccent else StudioDim)
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        when {
                            !musicEnabled -> "Musik aus"
                            track != null -> track
                            playing -> "Studio-Beat · $bpm BPM"
                            else -> "Musik pausiert"
                        },
                        color = StudioText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (track != null) "Eigene Musik" else "Takt folgt der Trittfrequenz", color = StudioDim, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, accent: Color, modifier: Modifier) {
    Column(modifier.background(StudioPanel, PanelShape).padding(horizontal = 12.dp, vertical = 8.dp)) {
        Text(label.uppercase(), color = accent, style = MaterialTheme.typography.labelMedium)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = StudioText, style = MaterialTheme.typography.headlineMedium, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Text(unit, color = StudioDim, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(bottom = 4.dp))
        }
    }
}

/** What to do right now: label, target, cadence, the remaining time big, and what follows. */
@Composable
private fun SegmentCard(s: RideState, color: Color, targetPct: Double, scale: Float, modifier: Modifier) {
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

/** The coach's line, scaled in when it changes. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
private fun CoachBanner(line: String?, s: RideState, scale: Float) {
    val text = line ?: s.engine.cue
    Box(Modifier.fillMaxWidth().heightIn(min = 52.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = text,
            transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.7f)).togetherWith(fadeOut()) },
            label = "coach",
        ) { t ->
            if (t != null) {
                Text(
                    t, color = StudioText, fontSize = (26f * scale).sp, lineHeight = (30f * scale).sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            } else {
                Spacer(Modifier.height(1.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Controls(container: AppContainer, audio: SpinningAudio, s: RideState, musicEnabled: Boolean, onStandardView: () -> Unit, onEnd: () -> Unit) {
    val session = container.session
    val e = s.engine
    val inWorkout = s.timeline != null && !e.continuing && !e.finished
    // Under the finished overlay nothing here may react, not even through the keyboard or TalkBack.
    val on = !e.finished
    FlowRow(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // "Weiter" only after a pause by hand; before the start or in an auto-pause pedalling goes on.
        val resume = e.userPaused
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
}

@Composable
private fun StudioButton(text: String, icon: ImageVector? = null, primary: Boolean = false, enabled: Boolean = true, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = if (primary) StudioAccent else Color.White.copy(alpha = 0.12f),
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

@Composable
private fun FinishedOverlay(s: RideState, onStandardView: () -> Unit, onEnd: () -> Unit) {
    // The pointer input makes the overlay a hit target, so taps on the dimmed area stop here instead of
    // reaching the controls underneath; its own buttons still work.
    Box(
        Modifier.fillMaxSize().background(StudioBlack.copy(alpha = 0.8f)).pointerInput(Unit) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
            Text("Geschafft!", color = StudioText, fontSize = 64.sp, fontWeight = FontWeight.Black, style = MaterialTheme.typography.displayLarge)
            val st = s.stats
            Text(
                "${formatDuration(st.seconds)} · ${st.avgPower} W · NP ${st.normalizedPower} W · TSS ${st.tss.roundToInt()}",
                color = StudioDim, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StudioButton("Beenden und speichern", Icons.Default.Stop, primary = true, onClick = onEnd)
                StudioButton("Normale Ansicht", Icons.Outlined.GridView, onClick = onStandardView)
            }
        }
    }
}

/**
 * A row of stylised riders seen from the side. The instructor is in front and pedals at the target
 * cadence; the riders behind pedal at the rider's real cadence, so the picture shows at a glance
 * whether the legs keep up with the class.
 */
@Composable
private fun Riders(cadence: Int?, targetCadence: Int?, color: Color, standing: Boolean, paused: Boolean, modifier: Modifier) {
    val riderCrank = rememberCrank(cadence ?: 0, !paused)
    val coachCrank = rememberCrank(targetCadence ?: 85, !paused, phase = 1.2f)
    // Own layer: the cranks turn every frame without re-recording the rest of the screen.
    Canvas(modifier.graphicsLayer()) {
        val w = size.width
        val h = size.height
        // Units: one unit is the wheel radius; a bike with rider is about 4.4 units wide and 4.6 high.
        val unitCoach = min(h / 5.2f, w / 7.5f)
        val unitRider = unitCoach * 0.62f
        // The wide layout can squeeze this to nothing (tall controls, split screen); nothing to draw then.
        if (unitRider < 1f) return@Canvas
        val ground = h * 0.95f
        val coachAngle = coachCrank.floatValue
        val riderAngle = riderCrank.floatValue
        // Riders behind, slightly offset and dimmer, like the back rows of the studio.
        for (i in 0 until 3) {
            val ox = w * (0.12f + 0.2f * i)
            val oy = ground - unitRider * 0.9f - unitRider * 0.25f * (2 - i)
            drawRider(Offset(ox, oy), unitRider, riderAngle + i * 1.7f, color.copy(alpha = 0.45f + 0.12f * i), standing)
        }
        drawRider(Offset(w * 0.72f, ground), unitCoach, coachAngle, color, standing, front = true)
        // Floor line under the front row.
        drawLine(color.copy(alpha = 0.35f), Offset(0f, ground), Offset(w, ground), strokeWidth = 2.dp.toPx())
    }
}

/**
 * Draws a bike and a rider as strokes. [origin] is the ground point under the bottom bracket,
 * [unit] the wheel radius in pixels; y grows upwards in bike coordinates.
 */
private fun DrawScope.drawRider(origin: Offset, unit: Float, crank: Float, color: Color, standing: Boolean, front: Boolean = false) {
    fun p(x: Float, y: Float) = Offset(origin.x + x * unit, origin.y - y * unit)
    val thin = unit * 0.09f
    val thick = unit * 0.2f
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
    drawCircle(color, unit * 0.3f, head)
    leg(hip, bb, unit, crank, color, thick)
    if (front) {
        // The instructor's little podium.
        drawLine(color.copy(alpha = 0.5f), p(-2.3f, -0.02f), p(2.3f, -0.02f), thin * 1.5f, StrokeCap.Round)
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
