package app.rollentrainer.ride

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.core.content.ContextCompat
import app.rollentrainer.ble.LiveMetrics
import app.rollentrainer.ble.SensorHub
import app.rollentrainer.core.engine.EngineState
import app.rollentrainer.core.engine.WorkoutEngine
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.RideSummary
import app.rollentrainer.core.model.Sample
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
    private val samples = mutableListOf<Sample>()
    private var startEpochSec = 0L
    private var tone: ToneGenerator? = null

    val running: Boolean get() = _state.value != null

    fun start(workout: Workout?) {
        if (running) return
        val s = settings.value
        val timeline = workout?.let { Timeline(it) }
        val e = WorkoutEngine(timeline, s.ftp, s.autoPause)
        engine = e
        samples.clear()
        startEpochSec = 0
        tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }.getOrNull()
        _state.value = RideState(workout, timeline, e.state, LiveMetrics(), null, emptyList(), 0, hub.trainerControl.value)
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        hub.connectSaved()
        loop = scope.launch { rideLoop(e, timeline) }
    }

    private suspend fun CoroutineScope.rideLoop(e: WorkoutEngine, timeline: Timeline?) {
        var last = SystemClock.elapsedRealtime()
        var lastSegment = -1
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now
            val m = hub.current()
            val pedaling = (m.power ?: 0) > 15 || (m.cadence ?: 0) > 25
            val st = e.tick(dt, pedaling)

            st.targetWatts?.let { hub.setTargetPower(softened(it, m)) }

            if (!st.paused) {
                if (startEpochSec == 0L) startEpochSec = System.currentTimeMillis() / 1000
                samples += Sample(samples.size, m.power, st.targetWatts, m.heartRate, m.cadence, m.speedKmh)
            }
            cues(st, lastSegment)
            lastSegment = st.segmentIndex

            val window = settings.value.powerSmoothingSec.coerceAtLeast(1)
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
            )
            if (st.finished) break
            delay(1000 - (SystemClock.elapsedRealtime() - now).coerceIn(0, 999))
        }
    }

    /**
     * Below the configured cadence the target is reduced, so the rider can spin up again
     * instead of grinding against full resistance (the ERG "spiral of death").
     */
    private fun softened(target: Int, m: LiveMetrics): Int {
        val limit = settings.value.lowCadenceRpm
        val cad = m.cadence ?: return target
        return if (limit > 0 && cad in 1 until limit) (target * 0.75).roundToInt() else target
    }

    private fun cues(st: EngineState, lastSegment: Int) {
        if (!settings.value.sounds || st.paused || st.totalSec == 0) return
        val t = tone ?: return
        when {
            st.segmentIndex != lastSegment && lastSegment >= 0 -> t.startTone(ToneGenerator.TONE_PROP_ACK, 400)
            st.segmentRemainingSec in 1..3 && st.nextTargetWatts != null -> t.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
    }

    fun togglePause() = engine?.togglePause()
    fun skip() = engine?.skip()
    fun adjustIntensity(delta: Int) = engine?.adjustIntensity(delta)
    fun adjustManual(delta: Int) = engine?.adjustManual(delta)

    /** Ends the ride. Returns the saved ride, or null if it was too short to keep. */
    suspend fun finish(save: Boolean = true): Ride? {
        val st = _state.value ?: return null
        loop?.cancel()
        loop = null
        hub.releaseTrainer()
        tone?.release()
        tone = null
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
                    append("Indoor im ERG-Modus mit der Rollentrainer-App\n")
                    append("NP ${s.normalizedPower} W · IF ${"%.2f".format(s.intensityFactor)} · TSS ${s.tss.roundToInt()}")
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
