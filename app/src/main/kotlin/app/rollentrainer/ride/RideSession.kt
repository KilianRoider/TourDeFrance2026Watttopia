package app.rollentrainer.ride

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.rollentrainer.ble.LiveMetrics
import app.rollentrainer.ble.SensorHub
import app.rollentrainer.core.engine.EngineConfig
import app.rollentrainer.core.engine.EngineState
import app.rollentrainer.core.engine.ErgState
import app.rollentrainer.core.engine.WorkoutEngine
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.metrics.LapBoundary
import app.rollentrainer.core.metrics.LiveStats
import app.rollentrainer.core.metrics.StatsSnapshot
import app.rollentrainer.core.metrics.WPrimeBalance
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.RideSummary
import app.rollentrainer.core.model.Sample
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.RouteState
import app.rollentrainer.data.RideRepository
import app.rollentrainer.data.SettingsStore
import app.rollentrainer.strava.StravaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt

data class RideState(
    val workout: Workout?,
    val timeline: Timeline?,
    val engine: EngineState,
    val live: LiveMetrics,
    /** Power averaged over the smoothing window, for the big number. */
    val displayPower: Int?,
    val samples: List<Sample>,
    val elapsedSec: Int,
    val trainerControl: Boolean,
    val stats: StatsSnapshot = StatsSnapshot(),
    /** W' balance in joules (Skiba), full at the start. */
    val wBalJ: Double = 0.0,
    val wPrimeJ: Int = 20_000,
    /** PowerMatch offset (meter minus trainer) currently applied, in watts. */
    val powerMatchW: Int? = null,
    /** Position on a virtual route, when riding one. */
    val route: RouteState? = null,
)

sealed interface UploadState {
    data object Idle : UploadState
    data object Running : UploadState
    data class Done(val activityId: Long) : UploadState
    data class Failed(val message: String) : UploadState
}

/** Runs one ride: ticks the workout engine once per second, sends ERG targets and records samples. */
class RideSession(
    private val context: Context,
    private val hub: SensorHub,
    private val settings: SettingsStore,
    private val rides: RideRepository,
    private val strava: StravaClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RideState?>(null)
    val state: StateFlow<RideState?> = _state.asStateFlow()

    private val _upload = MutableStateFlow<Pair<String, UploadState>?>(null)
    /** Upload progress for a ride id. */
    val upload: StateFlow<Pair<String, UploadState>?> = _upload.asStateFlow()

    private var engine: WorkoutEngine? = null
    private var routeEngine: RouteEngine? = null
    private var loop: Job? = null
    private var metronome: Job? = null
    private val samples = mutableListOf<Sample>()
    private var startEpochSec = 0L
    private val sounds = Sounds()
    private var stats = LiveStats(200, 185)
    private var wbal = WPrimeBalance(200)
    /** Sample index at which each workout segment started, with its label. */
    private val lapStarts = mutableListOf<LapBoundary>()
    private var matchOffset = 0.0
    private var cadenceOffSec = 0.0
    private var lastCadenceHintAt = 0.0
    private var released = false
    private var finishedTone = false

    val running: Boolean get() = _state.value != null

    private val _spinningView = MutableStateFlow(false)
    /**
     * The ride is shown as a spinning class. Decided when the ride starts (spinning programmes and the
     * "Spinning-Ansicht als Standard" setting), switchable during the ride; kept here so it survives
     * turning the tablet.
     */
    val spinningView: StateFlow<Boolean> = _spinningView.asStateFlow()
    fun setSpinningView(on: Boolean) { _spinningView.value = on && routeEngine == null }

    fun start(workout: Workout?) = begin(workout, null, emptyList())

    /**
     * Rides a virtual route: the trainer runs in simulation mode with the road's grade (scaled by the
     * trainer-difficulty setting), speed and distance come from the physics, not from the trainer.
     */
    fun startRoute(route: Route, pacers: List<Pacer>) = begin(null, route, pacers)

    private fun begin(workout: Workout?, route: Route?, pacers: List<Pacer>) {
        if (running) return
        val s = settings.value
        val timeline = workout?.let { Timeline(it) }
        val e = WorkoutEngine(timeline, EngineConfig(ftp = s.ftp, autoPauseSec = s.autoPauseSeconds, spiralRpm = s.ergReleaseRpm))
        engine = e
        routeEngine = route?.let { RouteEngine(it, RiderParams.forRider(s.weightKg), pacers) }
        _spinningView.value = route == null && (workout?.id?.startsWith("preset-spin") == true || s.spinningByDefault)
        if (route != null) {
            e.setErg(false)
            e.setGrade(route.gradientAt(0.0) * s.trainerDifficultyPct / 100)
        }
        samples.clear()
        startEpochSec = 0
        stats = LiveStats(s.ftp, s.maxHr)
        wbal = WPrimeBalance(s.ftp, s.wPrimeKj * 1000)
        lapStarts.clear()
        matchOffset = 0.0
        cadenceOffSec = 0.0
        lastCadenceHintAt = 0.0
        released = false
        finishedTone = false
        sounds.prepare(s.soundVolume)
        _state.value = RideState(
            workout, timeline, e.state, LiveMetrics(), null, emptyList(), 0, hub.trainerControl.value,
            wBalJ = wbal.balanceJ, wPrimeJ = s.wPrimeKj * 1000, route = routeEngine?.state,
        )
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        hub.connectSaved()
        loop = scope.launch { rideLoop(e) }
        metronome = scope.launch { metronomeLoop() }
    }

    private suspend fun CoroutineScope.rideLoop(e: WorkoutEngine) {
        var last = SystemClock.elapsedRealtime()
        var lastSegment = -1
        var lastErgState = ErgState.NORMAL
        var wasFinished = false
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now
            val raw = hub.current()
            val cfg = settings.value
            // On a route, rolling on (a descent, the last metres) keeps the ride from auto-pausing.
            val rolling = (routeEngine?.state?.speedMps ?: 0.0) > 0.5
            val st0 = e.tick(dt, raw.power, raw.cadence, moving = rolling)
            // On a virtual route the road sets the grade and the physics the speed; the ride ends at the summit.
            val rs = routeEngine?.let { re ->
                val r = re.tick(dt, raw.power, st0.paused)
                e.setGrade(r.gradePct * cfg.trainerDifficultyPct / 100)
                if (r.finished && !e.state.finished) e.finish()
                r
            }
            val st = e.state
            val m = if (rs != null) raw.copy(speedKmh = rs.speedKmh) else raw
            val timeline = e.timeline

            // Trainer command: ERG target (with PowerMatch) or simulation mode.
            val match = powerMatch(m, cfg.powerMatch)
            val command = st.commandWatts
            when {
                st.finished && !wasFinished -> hub.releaseTrainer()
                st.finished -> Unit
                !st.erg -> hub.setSimulation(st.gradePct)
                command != null -> hub.setTargetPower((command - (match ?: 0)).coerceAtLeast(0))
            }

            if (st.segmentIndex != lastSegment && lastSegment >= 0) stats.newLap()
            // The tick that crosses the summit line still moved the rider, so it is recorded although the ride is now finished.
            val recording = !st0.paused
            if (recording) {
                if (startEpochSec == 0L) startEpochSec = System.currentTimeMillis() / 1000
                if (timeline != null && st.segmentIndex >= 0 && (lapStarts.isEmpty() || st.segmentIndex != lastSegment)) {
                    val seg = timeline.segments.getOrNull(st.segmentIndex)
                    // Ramps and free-ride segments get no stars; a steady segment stays rated even if the intensity is changed.
                    lapStarts += LapBoundary(samples.size, st.segmentLabel.ifBlank { "Abschnitt ${st.segmentIndex + 1}" }, rated = seg != null && seg.erg && !seg.isRamp)
                }
                if (st.continuing && lapStarts.isNotEmpty() && lapStarts.last().label != "Weiterfahren") {
                    lapStarts += LapBoundary(samples.size, "Weiterfahren", rated = false)
                }
                val target = if (st.erg) st.targetWatts else null
                samples += Sample(samples.size, m.power, target, m.heartRate, m.cadence, m.speedKmh, altitudeM = rs?.elevationM)
                stats.add(m.power, target, m.heartRate, m.cadence, m.speedKmh)
            }
            // W' recovers while standing still too, so the balance follows wall-clock time, not recorded samples.
            wbal.update(if (recording) m.power else 0, dt.coerceIn(0.0, 5.0))
            audio(st, m, lastSegment, lastErgState, cfg)
            lastSegment = st.segmentIndex
            lastErgState = st.ergState
            wasFinished = st.finished

            val window = cfg.powerSmoothingSec.coerceAtLeast(1)
            val recent = samples.takeLast(window).mapNotNull { it.power }
            val display = if (st.paused || recent.isEmpty()) m.power else recent.average().roundToInt()
            _state.value = RideState(
                workout = _state.value?.workout,
                timeline = timeline,
                engine = st,
                live = m,
                displayPower = display,
                samples = samples.toList(),
                elapsedSec = samples.size,
                trainerControl = hub.trainerControl.value,
                stats = stats.snapshot(),
                wBalJ = wbal.balanceJ,
                wPrimeJ = cfg.wPrimeKj * 1000,
                powerMatchW = match,
                route = rs,
            )
            delay(1000 - (SystemClock.elapsedRealtime() - now).coerceIn(0, 999))
        }
    }

    /**
     * PowerMatch: the trainer's own reading may be off against a calibrated power meter. The target
     * is shifted by the smoothed difference so the power meter ends up on target. Capped at ±60 W.
     */
    private fun powerMatch(m: LiveMetrics, enabled: Boolean): Int? {
        if (!enabled) { matchOffset = 0.0; return null }
        val meter = m.meterPower ?: return matchOffset.roundToInt().takeIf { it != 0 }
        val trainer = m.trainerPower ?: return matchOffset.roundToInt().takeIf { it != 0 }
        if (meter < 30 || trainer < 30) return matchOffset.roundToInt().takeIf { it != 0 }
        matchOffset = (matchOffset * 0.9 + (meter - trainer) * 0.1).coerceIn(-60.0, 60.0)
        return matchOffset.roundToInt()
    }

    private fun audio(st: EngineState, m: LiveMetrics, lastSegment: Int, lastErg: ErgState, cfg: app.rollentrainer.data.AppSettings) {
        if (!cfg.sounds) return
        sounds.prepare(cfg.soundVolume)
        if (st.finished) {
            if (!finishedTone) { finishedTone = true; sounds.finished() }
            return
        }
        if (st.paused || st.totalSec == 0) return
        when {
            st.segmentIndex != lastSegment && lastSegment >= 0 && cfg.soundSegment -> {
                val prev = _state.value?.timeline?.segments?.getOrNull(lastSegment)
                val cur = st.timelineSegment()
                val harder = cur != null && prev != null && cur.startPower.watts(cfg.ftp) > prev.endPower.watts(cfg.ftp)
                sounds.segment(harder)
            }
            st.segmentRemainingSec in 0..2 && st.nextTargetWatts != null && cfg.soundCountdown -> sounds.countdown()
        }
        if (st.ergState == ErgState.RELEASED && lastErg != ErgState.RELEASED) sounds.released()
        // Cadence hints: ten seconds off target, then a nudge every fifteen seconds.
        val tc = st.targetCadence
        val c = m.cadence
        if (cfg.soundCadenceHints && tc != null && c != null && c > 0 && abs(c - tc) > 10) {
            cadenceOffSec += 1.0
            if (cadenceOffSec >= 10 && st.workoutSec - lastCadenceHintAt >= 15) {
                lastCadenceHintAt = st.workoutSec
                sounds.cadenceHint()
            }
        } else {
            cadenceOffSec = 0.0
        }
    }

    private fun EngineState.timelineSegment() = _state.value?.timeline?.segments?.getOrNull(segmentIndex)

    /** Clicks at the target cadence while a cadence target exists and the ride runs. */
    private suspend fun CoroutineScope.metronomeLoop() {
        while (isActive) {
            val st = _state.value
            val cfg = settings.value
            val rpm = st?.engine?.targetCadence
            if (st == null || !cfg.sounds || !cfg.metronome || rpm == null || st.engine.paused || st.engine.finished) {
                delay(500)
                continue
            }
            sounds.click()
            delay((60_000.0 / rpm.coerceIn(30, 200)).toLong())
        }
    }

    fun togglePause() = engine?.togglePause()

    /** Pauses or resumes explicitly (the button), unlike [togglePause] which only flips the rider's own pause. */
    fun setPaused(paused: Boolean) = engine?.setPaused(paused)
    fun skip() = engine?.skip()
    fun back() = engine?.back()
    fun extend(sec: Int) = engine?.extendCurrent(sec)
    fun bail() = engine?.bail()
    fun continueRiding(watts: Int) = engine?.continueRiding(watts)
    fun adjustIntensity(delta: Int) = engine?.adjustIntensity(delta)
    fun setIntensity(pct: Int) = engine?.setIntensity(pct)
    fun adjustManual(delta: Int) = engine?.adjustManual(delta)
    fun setErg(on: Boolean) = engine?.setErg(on)
    fun adjustGrade(delta: Double) = engine?.adjustGrade(delta)
    fun reengage() = engine?.reengage()

    /** Ends the ride. Returns the saved ride, or null if it was too short to keep. */
    suspend fun finish(save: Boolean = true, rpe: Int? = null, notes: String = ""): Ride? {
        val st = _state.value ?: return null
        loop?.cancel()
        loop = null
        metronome?.cancel()
        metronome = null
        hub.releaseTrainer()
        sounds.release()
        context.stopService(Intent(context, RideService::class.java))
        _state.value = null
        routeEngine = null
        if (!save || samples.size < MIN_SAMPLES) return null

        val ftp = settings.value.ftp
        val list = samples.toList()
        val route = st.route
        val ride = Ride(
            id = UUID.randomUUID().toString(),
            startEpochSec = startEpochSec,
            name = st.workout?.name ?: route?.route?.let { r -> fractionLabel(r.fraction)?.let { "$it ${r.name}" } ?: r.name } ?: "Freie Fahrt",
            workoutId = st.workout?.id,
            routeId = route?.route?.id,
            routeFraction = route?.route?.fraction ?: 1.0,
            routeTimeSec = route?.takeIf { it.finished }?.let { (it.finishTimeSec ?: it.elapsedSec).roundToInt() },
            ftp = ftp,
            samples = list,
            summary = RideSummary.of(list, ftp),
            laps = Analysis.lapsOf(list, lapStarts.toList()),
            bests = Analysis.bests(list),
            rpe = rpe,
            notes = notes,
            test = st.workout?.test ?: TestKind.NONE,
        )
        rides.save(ride)
        if (settings.value.stravaAutoUpload && strava.isConnected) uploadToStrava(ride)
        return ride
    }

    fun uploadToStrava(ride: Ride) {
        scope.launch {
            _upload.value = ride.id to UploadState.Running
            val result = runCatching {
                val file = rides.exportFit(ride)
                val s = ride.summary
                val description = buildString {
                    append(if (ride.routeId != null) "Virtuelle Strecke im Simulationsmodus mit Wattwerk\n" else "Indoor im ERG-Modus mit Wattwerk\n")
                    append("NP ${s.normalizedPower} W · IF ${"%.2f".format(s.intensityFactor)} · TSS ${s.tss.roundToInt()}")
                    ride.rpe?.let { append(" · RPE $it/10") }
                    if (ride.notes.isNotBlank()) append("\n").append(ride.notes)
                }
                strava.upload(file, ride.name, description)
            }
            _upload.value = ride.id to result.fold(
                onSuccess = { id ->
                    rides.save(ride.copy(stravaActivityId = id))
                    UploadState.Done(id)
                },
                onFailure = { UploadState.Failed(it.message ?: "Upload fehlgeschlagen") },
            )
        }
    }

    private companion object {
        const val MIN_SAMPLES = 30
    }
}

/** "½", "¾", "¼" for a shortened route, null for the full one. */
fun fractionLabel(fraction: Double): String? = when {
    fraction >= 0.999 -> null
    kotlin.math.abs(fraction - 0.75) < 0.01 -> "¾"
    kotlin.math.abs(fraction - 0.5) < 0.01 -> "½"
    kotlin.math.abs(fraction - 0.25) < 0.01 -> "¼"
    else -> "${(fraction * 100).roundToInt()} %"
}
