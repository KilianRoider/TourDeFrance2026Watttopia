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
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceEngine
import app.rollentrainer.core.race.RaceEvent
import app.rollentrainer.core.race.RaceEventKind
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.StageStyle
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.Drivetrain
import app.rollentrainer.core.route.Gearing
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.VirtualGears
import app.rollentrainer.core.route.RouteState
import app.rollentrainer.data.AppSettings
import app.rollentrainer.data.RideRepository
import app.rollentrainer.data.SettingsStore
import app.rollentrainer.strava.StravaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.random.Random

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
    /** Pro mode: the pro the rider is today; set from the start, also while the field is still being set up. */
    val pro: ProRider? = null,
    /** Pro mode: the race after the last tick ([route] is its route state); null until the field is set up. */
    val race: RaceState? = null,
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
    /** The route of a route ride, plain or pro mode. */
    private var routeRide: Route? = null
    /** Pro mode: the pro, the race once it is set up (built in the background), and its feed for the 3D world. */
    private var pro: ProRider? = null
    @Volatile private var race: RaceEngine? = null
    private var raceJob: Job? = null
    private var raceSeed = 0L
    @Volatile private var feed: RaceFeed? = null
    /** Where a pro-mode ride stands while its field is still being set up: at the start (or, in the demo, where it will begin). */
    private var waitingRoute: RouteState? = null
    /** Demo mode only: keeps the pro in the bunch (see [demoStartRace]). */
    private var follower: BunchFollower? = null
    private var demoRaceTargetM = 0.0
    private val _finishing = MutableStateFlow(false)
    /** True while a pro-mode ride is being ended: the riders still on the road are raced to the line for the result. */
    val finishing: StateFlow<Boolean> = _finishing.asStateFlow()
    /** Stage results of pro-mode rides, by ride id. */
    val raceRecords = RaceRecords(context, scope)
    /** Race sounds of a route ride; created with the first one and kept, its clips are freed after each ride. */
    private var ambience: Ambience? = null
    private var ambienceRunning = false

    /**
     * Spectators along the road for the ambience, 0..1 at a distance of the ridden route. The default
     * estimates them from the profile; the geodata world knows the real crowd and can replace it with one
     * assignment, e.g. `session.crowdDensity = { _, s -> dressing.crowdDensityAt(ctx, s) }`.
     */
    @Volatile var crowdDensity: (route: Route, distanceM: Double) -> Double = AmbienceCues::crowdDensity
    private var gears: VirtualGears? = null
    private val _gear = MutableStateFlow<String?>(null)
    /** The virtual gear as "50 × 17 · 14/20", or null without virtual shifting. */
    val gear: StateFlow<String?> = _gear.asStateFlow()
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
    fun setSpinningView(on: Boolean) { _spinningView.value = on && routeRide == null }

    fun start(workout: Workout?) = begin(workout, null, emptyList(), null)

    /**
     * Rides a virtual route: the trainer runs in simulation mode with the road's grade (scaled by the
     * trainer-difficulty setting), speed and distance come from the physics, not from the trainer.
     */
    fun startRoute(route: Route, pacers: List<Pacer>) = begin(null, route, pacers, null)

    /**
     * Pro mode: rides [route] (a Tour stage or its finale) as the pro [riderId] of [Tour2026.roster] in the
     * simulated field. His power is the rider's times the pro's FTP over the rider's, the physics are the
     * pro's with drafting; trainer, gears and recording work as on any route. The field is set up in the
     * background (a time trial first rides everybody who starts earlier); until then the ride waits at
     * the start as before the first pedal stroke. No pacer: the field is the competition.
     */
    fun startRace(route: Route, riderId: Int) {
        val rider = Tour2026.roster.riders.getOrNull(riderId) ?: return
        begin(null, route, emptyList(), rider)
    }

    /** Pro mode: moves the rider one lane to the left or right in the bunch (held for a few seconds). */
    fun moveLeft() { race?.moveLeft() }
    fun moveRight() { race?.moveRight() }

    /**
     * Pro mode, for the 3D world: the riders around the rider (see [RaceFeed.view]); null without a race.
     * [offsetM] is subtracted from every distance (the start of the world section on screen). Cheap
     * enough to call once per ride state; the result is immutable and may be handed to the GL thread.
     */
    fun peloton(offsetM: Double = 0.0, behindM: Double = 300.0, aheadM: Double = 1500.0): PelotonView? =
        feed?.view(offsetM, behindM, aheadM)

    /** Pro mode: the race itself, e.g. for the riders' names and teams; null without one or while it is set up. */
    val raceEngine: RaceEngine? get() = race

    /**
     * Demo mode only (CI screenshots, see DemoMode): rides the running route ahead to [distanceM] at a steady
     * [powerW] in one go, so a screenshot can start anywhere on it. Stops short of the finish, which would end the ride.
     */
    fun demoAdvanceTo(distanceM: Double, powerW: Int) {
        val re = routeEngine ?: return
        val target = distanceM.coerceAtMost(re.route.lengthM - 200)
        var rs = re.state
        var ticks = 0
        // Coarse steps while far away keep a 200 km stage quick on the main thread; fine ones for the last metres.
        while (rs.distanceM < target && !rs.finished && ticks++ < 200_000) {
            rs = re.tick(if (target - rs.distanceM > 500) 5.0 else 1.0, powerW, paused = false)
        }
        _state.value = _state.value?.copy(route = rs)
    }

    /**
     * Demo mode only (CI screenshots): pro mode on [route] as [riderId], with the race ridden ahead to
     * [distanceM] in the background before it is shown (the screen meanwhile builds the world there), and
     * the pro kept in the bunch from then on whatever the simulated trainer reports.
     */
    fun demoStartRace(route: Route, riderId: Int, distanceM: Double) {
        if (running) return
        demoRaceTargetM = distanceM.coerceIn(0.0, route.lengthM - 300)
        startRace(route, riderId)
        if (pro == null) demoRaceTargetM = 0.0
    }

    private fun begin(workout: Workout?, route: Route?, pacers: List<Pacer>, rider: ProRider?) {
        if (running) return
        val s = settings.value
        val timeline = workout?.let { Timeline(it) }
        val e = WorkoutEngine(timeline, EngineConfig(ftp = s.ftp, autoPauseSec = s.autoPauseSeconds, spiralRpm = s.ergReleaseRpm))
        engine = e
        val proRider = if (route != null) rider else null
        routeRide = route
        pro = proRider
        race = null
        feed = null
        follower = null
        routeEngine = if (route != null && proRider == null) RouteEngine(route, RiderParams.forRider(s.weightKg), pacers) else null
        waitingRoute = if (route != null && proRider != null) {
            val at = demoRaceTargetM
            RouteState(route, distanceM = at, gradePct = route.gradientAt(at), elevationM = route.elevationAt(at), ascentM = route.ascentUntil(at))
        } else {
            null
        }
        gears = if (route != null && s.virtualGears) VirtualGears(Drivetrain.of(s.chainrings, s.cassette)) else null
        _gear.value = gears?.label
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
        _finishing.value = false
        _state.value = RideState(
            workout, timeline, e.state, LiveMetrics(), null, emptyList(), 0, hub.trainerControl.value,
            wBalJ = wbal.balanceJ, wPrimeJ = s.wPrimeKj * 1000, route = routeEngine?.state ?: waitingRoute, pro = proRider,
        )
        ContextCompat.startForegroundService(context, Intent(context, RideService::class.java))
        hub.connectSaved()
        if (route != null && proRider != null) raceJob = scope.launch { setUpRace(route, proRider, s.ftp) }
        if (route != null) ambienceStart(s)
        loop = scope.launch { rideLoop(e) }
        metronome = scope.launch { metronomeLoop() }
    }

    /**
     * Builds the field off the main thread: a road stage takes a moment, a time trial rides everybody who
     * starts before the rider first. The seed is new for every ride, so no two races are the same.
     */
    private suspend fun setUpRace(route: Route, rider: ProRider, userFtp: Int) {
        val seed = Random.nextLong()
        raceSeed = seed
        val target = demoRaceTargetM
        demoRaceTargetM = 0.0
        val demo = if (target > 0) BunchFollower(rider, rider.ftpW.toDouble() / userFtp.coerceAtLeast(50)) else null
        val engine = withContext(Dispatchers.Default) {
            RaceEngine(route, Tour2026.roster, rider.id, userFtp, seed).also { re ->
                if (demo != null) {
                    // Coarse ticks: the race is deterministic per sub step, the tick length only sets how
                    // often the follower adjusts; 200 km take seconds this way.
                    var st = re.state
                    var guard = 0
                    while (st.route.distanceM < target && !st.finished && guard++ < 100_000) st = re.tick(5.0, demo.powerW(st))
                }
            }
        }
        val f = RaceFeed(engine.riders).also { it.update(engine::copyFrame) }
        follower = demo
        feed = f
        race = engine
        waitingRoute = null
        _state.value = _state.value?.copy(route = engine.state.route, race = engine.state)
    }

    private suspend fun CoroutineScope.rideLoop(e: WorkoutEngine) {
        var last = SystemClock.elapsedRealtime()
        var lastSegment = -1
        var lastErgState = ErgState.NORMAL
        var wasFinished = false
        var lastRoute: RouteState? = routeEngine?.state ?: waitingRoute
        var lastRace: RaceState? = null
        while (isActive) {
            val now = SystemClock.elapsedRealtime()
            val dt = (now - last) / 1000.0
            last = now
            val raw = hub.current()
            val cfg = settings.value
            val re = race
            // Pro mode before the field is set up: nobody may start yet, so the clock and the recording wait too.
            val waiting = pro != null && re == null
            // On a route, rolling on (a descent, the last metres) keeps the ride from auto-pausing.
            val rolling = (lastRoute?.speedMps ?: 0.0) > 0.5
            val st0 = if (waiting) e.tick(dt, 0, 0) else e.tick(dt, raw.power, raw.cadence, moving = rolling)
            // Pro mode: the race moves everybody, the rider with his scaled power. A pause stops the whole
            // field; once the rider is across the line the others ride in, so the result fills up.
            val raceState = re?.let { engine ->
                val power = follower?.powerW(lastRace) ?: raw.power
                val paused = st0.paused && lastRace?.finished != true
                // 184 riders in tenth-second steps: a few milliseconds a tick, kept off the main thread.
                withContext(Dispatchers.Default) {
                    engine.tick(dt, power, paused).also { feed?.update(engine::copyFrame) }
                }
            }
            // On a virtual route the road sets the grade and the physics the speed; the ride ends at the summit.
            val rs = (raceState?.route ?: routeEngine?.tick(dt, raw.power, st0.paused) ?: waitingRoute)?.also { r ->
                setTrainerGrade(e, r, raw, cfg, raceState?.draftReduction ?: 0.0)
                if (r.finished && !e.state.finished) e.finish()
            }
            lastRoute = rs
            if (raceState != null) lastRace = raceState
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
            // While the field is set up nothing is recorded, even if the rider has taken the pause off by hand.
            val recording = !st0.paused && !waiting
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
            if (rs != null) ambienceTick(rs, raceState, raw, st, cfg)
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
                pro = pro,
                race = raceState ?: lastRace,
            )
            delay(1000 - (SystemClock.elapsedRealtime() - now).coerceIn(0, 999))
        }
    }

    /**
     * The road's grade for the trainer, scaled by the trainer difficulty and shaped by the virtual gear.
     * Pro mode adds the bunch: the air resistance the draft takes away (a share [draft] of it) is taken
     * off as an equivalent grade, so riding in the wheels feels easier, as the trainer has no way to be
     * told the wind directly here. On a climb the speeds are low and the relief disappears by itself.
     */
    private fun setTrainerGrade(e: WorkoutEngine, r: RouteState, raw: LiveMetrics, cfg: AppSettings, draft: Double) {
        val road = r.gradePct * cfg.trainerDifficultyPct / 100
        val params = RiderParams.forRider(cfg.weightKg)
        // The flywheel speed decides how the virtual gear feels: the trainer's own speed, else cadence.
        val fly = raw.speedKmh?.div(3.6)?.takeIf { it > 0.3 }
            ?: Gearing.flywheelFromCadence(raw.cadence ?: 0, VirtualGears.NEUTRAL_RATIO).takeIf { it > 0.3 }
            ?: r.speedMps
        val g = gears
        val grade = if (g == null) road else Gearing.feelGrade(road, g.gear.ratio, VirtualGears.NEUTRAL_RATIO, fly, params)
        if (draft <= 0.0) { e.setGrade(grade); return }
        val relief = draft * 0.5 * params.airDensity * params.cda * fly * fly / (params.totalMassKg * Physics.G) * 100
        e.setGrade((grade - relief.coerceIn(0.0, MAX_DRAFT_RELIEF_PCT) * cfg.trainerDifficultyPct / 100).coerceAtLeast(Gearing.MIN_GRADE))
    }

    // ---- ambience ----

    private fun ambienceStart(cfg: AppSettings) {
        if (!cfg.ambience) return
        val a = ambience ?: runCatching { Ambience(context) }.getOrNull()?.also { ambience = it } ?: return
        a.setVolume(cfg.ambienceVolume / 100f)
        a.start()
        ambienceRunning = true
    }

    /**
     * Once a second on a route: what the ambience should sound like. A pause by hand silences it (the
     * rider may have stepped off); an automatic pause keeps the crowd, only the wind and tyres stop.
     */
    private fun ambienceTick(rs: RouteState, raceState: RaceState?, raw: LiveMetrics, st: EngineState, cfg: AppSettings) {
        val wanted = cfg.ambience && !st.userPaused
        if (wanted && !ambienceRunning) ambienceStart(cfg)
        if (!wanted && ambienceRunning) { ambience?.stop(); ambienceRunning = false }
        val a = ambience ?: return
        if (!ambienceRunning) return
        a.setVolume(cfg.ambienceVolume / 100f)
        val route = rs.route
        val climbing = rs.gradePct > AmbienceCues.CLIMB_PCT
        val inBunch = raceState != null && raceState.style != StageStyle.ITT && !raceState.finished &&
            (raceState.userGroupInfo?.size ?: 0) >= 6
        a.update(
            speedKmh = if (st.paused || rs.finished) 0.0 else rs.speedKmh,
            pedalling = (raw.cadence ?: 0) > 0 || (raw.power ?: 0) > 10,
            crowdDensity = runCatching { crowdDensity(route, rs.distanceM) }.getOrDefault(0.0),
            climbing = climbing,
            surface = AmbienceCues.surface(route, rs.distanceM),
            nearFinish = AmbienceCues.finishesAtEnd(route) && rs.remainingM < 1000,
            inPeloton = inBunch,
            cowbells = AmbienceCues.cowbells(route, rs),
        )
        if (rs.passedLandmark != null) a.triggerPass()
        // The rider's own finish and his attacks get a cheer from the roadside.
        val user = pro?.id
        if (raceState != null && user != null && raceState.newEvents.any { cheered(it, user) }) a.triggerPass()
    }

    /**
     * Race events the roadside cheers: the rider's own finish, his attacks, the break he is in and the gap
     * he opens; not another rider's stage win, nor the rider being dropped.
     */
    private fun cheered(e: RaceEvent, user: Int): Boolean = when (e.kind) {
        RaceEventKind.FINISH, RaceEventKind.ATTACK, RaceEventKind.BREAKAWAY -> user in e.riderIds
        // "Du attackierst!" names the rider; the gap he opens and the one he loses name nobody.
        RaceEventKind.USER -> user in e.riderIds || e.text.startsWith("Du hast eine Lücke")
        else -> false
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

    fun shiftUp() { gears?.let { if (it.up()) _gear.value = it.label } }
    fun shiftDown() { gears?.let { if (it.down()) _gear.value = it.label } }

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
        // A second tap on "Beenden" while the result is being computed must not end the ride twice.
        if (_finishing.value) return null
        val st = _state.value ?: return null
        val stopped = loop
        stopped?.cancel()
        loop = null
        metronome?.cancel()
        metronome = null
        raceJob?.cancel()
        raceJob = null
        hub.releaseTrainer()
        sounds.release()
        ambience?.release()
        ambienceRunning = false
        val id = UUID.randomUUID().toString()
        val keep = save && samples.size >= MIN_SAMPLES
        val rider = pro
        val raceEngine = race
        // Pro mode: the stage result. Across the line, the riders still on the road are raced to it first
        // (seconds of simulation for a gruppetto, while the ride screen stays up); a rider who stops early
        // abandons and is listed where he was on the road.
        val record = if (keep && rider != null && raceEngine != null) {
            _finishing.value = true
            try {
                // A race tick may still be running on another thread; the result must see the field at rest.
                stopped?.join()
                withContext(Dispatchers.Default) {
                    runCatching {
                        val (now, result) = raceEngine.finalSnapshot()
                        RaceRecord.of(id, raceEngine.route, rider, settings.value.ftp, now, result, raceSeed)
                    }.getOrNull()
                }
            } finally {
                _finishing.value = false
            }
        } else {
            null
        }
        context.stopService(Intent(context, RideService::class.java))
        _state.value = null
        routeEngine = null
        routeRide = null
        pro = null
        race = null
        feed = null
        follower = null
        waitingRoute = null
        gears = null
        _gear.value = null
        if (!keep) return null

        val ftp = settings.value.ftp
        val list = samples.toList()
        val route = st.route
        val ride = Ride(
            id = id,
            startEpochSec = startEpochSec,
            name = st.workout?.name ?: route?.route?.displayName ?: "Freie Fahrt",
            workoutId = st.workout?.id,
            routeId = route?.route?.id,
            routeFraction = route?.route?.fraction ?: 1.0,
            routeVariant = route?.route?.variant ?: "",
            routeTimeSec = route?.takeIf { it.finished }?.let { (it.finishTimeSec ?: it.elapsedSec).roundToInt() },
            ftp = ftp,
            samples = list,
            summary = RideSummary.of(list, ftp),
            laps = Analysis.lapsOf(list, lapStarts.toList()),
            bests = Analysis.bests(list),
            rpe = rpe,
            notes = listOfNotNull(record?.notesLine, notes.takeIf { it.isNotBlank() }).joinToString("\n"),
            test = st.workout?.test ?: TestKind.NONE,
        )
        record?.let { raceRecords.save(it) }
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
                    append(
                        when {
                            raceRecords.find(ride.id) != null -> "Virtuelle Tour-Etappe im Profi-Modus mit Wattwerk\n"
                            ride.routeId != null -> "Virtuelle Strecke im Simulationsmodus mit Wattwerk\n"
                            else -> "Indoor im ERG-Modus mit Wattwerk\n"
                        },
                    )
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
        /** Most grade the draft can take off: about what sitting deep in a bunch at 50 km/h saves. */
        const val MAX_DRAFT_RELIEF_PCT = 2.5
    }
}
