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

    fun start(workout: Workout?) {
        if (running) return
        val s = settings.value
        val timeline = workout?.let { Timeline(it) }
        val e = WorkoutEngine(timeline, EngineConfig(ftp = s.ftp, autoPauseSec = s.autoPauseSeconds, spiralRpm = s.ergReleaseRpm))
        engine = e
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
        _state.value = RideState(workout, timeline, e.state, LiveMetrics(), null, emptyList(), 0, hub.trainerControl.value, wBalJ = wbal.balanceJ, wPrimeJ = s.wPrimeKj * 1000)
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
            val m = hub.current()
            val cfg = settings.value
            val st = e.tick(dt, m.power, m.cadence)
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
            if (!st.paused) {
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
                samples += Sample(samples.size, m.power, target, m.heartRate, m.cadence, m.speedKmh)
                stats.add(m.power, target, m.heartRate, m.cadence, m.speedKmh)
            }
            // W' recovers while standing still too, so the balance follows wall-clock time, not recorded samples.
            wbal.update(if (st.paused) 0 else m.power, dt.coerceIn(0.0, 5.0))
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
        if (!save || samples.size < MIN_SAMPLES) return null

        val ftp = settings.value.ftp
        val list = samples.toList()
        val ride = Ride(
            id = UUID.randomUUID().toString(),
            startEpochSec = startEpochSec,
            name = st.workout?.name ?: "Freie Fahrt",
            workoutId = st.workout?.id,
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
                    append("Indoor im ERG-Modus mit Wattwerk\n")
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
