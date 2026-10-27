package app.rollentrainer.core.engine

import app.rollentrainer.core.model.Segment
import app.rollentrainer.core.model.Timeline
import kotlin.math.roundToInt

/** Tunables that come from the settings screen. */
data class EngineConfig(
    val ftp: Int,
    /** Seconds without pedaling before the clock stops; 0 disables auto-pause. */
    val autoPauseSec: Double = 3.0,
    /** Below this cadence the ERG target is released so the rider can spin up again; 0 disables it. */
    val spiralRpm: Int = 50,
    /** Simulated grade in free-ride segments, in percent. */
    val gradePct: Double = 0.0,
    /** The next segment's target is sent this early so the trainer has settled when it begins. */
    val earlyTargetSec: Double = 2.0,
)

/** How ERG currently treats the rider. */
enum class ErgState {
    /** Target applied. */
    NORMAL,
    /** Target released because the rider bogged down; a light target is sent. */
    RELEASED,
    /** Cadence is back; the target ramps up over a few seconds. */
    RE_ENGAGING,
}

/** What the ride screen shows about the workout at one moment. */
data class EngineState(
    val workoutSec: Double = 0.0,
    val totalSec: Int = 0,
    val segmentIndex: Int = -1,
    val segmentRemainingSec: Int = 0,
    /** The rider's target in the current segment, scaled by intensity. */
    val targetWatts: Int? = null,
    /** What is actually sent to the trainer right now (early switch, release, ramp-in). Null in simulation mode. */
    val commandWatts: Int? = null,
    val nextTargetWatts: Int? = null,
    val nextSegment: Segment? = null,
    val intensityPct: Int = 100,
    val paused: Boolean = true,
    val autoPaused: Boolean = false,
    val finished: Boolean = false,
    val targetCadence: Int? = null,
    /** Coaching text for the current moment, or null. */
    val cue: String? = null,
    val segmentLabel: String = "",
    /** ERG switched on by the rider and the segment allows it. */
    val erg: Boolean = true,
    val ergState: ErgState = ErgState.NORMAL,
    /** Simulated grade sent in simulation mode. */
    val gradePct: Double = 0.0,
    /** Riding on after the workout ended, with a manual target. */
    val continuing: Boolean = false,
)

/**
 * Drives a workout: advances time while the rider pedals, computes the target power and
 * supports pause, skip, back, extend, an intensity offset and an ERG release when the rider bogs down.
 * A null [initialTimeline] is a free ride with a manually set target.
 */
class WorkoutEngine(
    initialTimeline: Timeline?,
    private val config: EngineConfig,
) {
    constructor(timeline: Timeline?, ftp: Int, autoPause: Boolean = true) :
        this(timeline, EngineConfig(ftp, if (autoPause) 3.0 else 0.0))

    var timeline: Timeline? = initialTimeline
        private set

    private var workoutSec = 0.0
    private var userPaused = false
    private var started = false
    private var idleSec = 0.0
    private var intensity = 100
    private var manualWatts = 150
    private var finished = false
    private var continuing = false
    private var ergOn = true
    private var grade = config.gradePct
    private var ergState = ErgState.NORMAL
    private var lowSec = 0.0
    private var goodSec = 0.0
    private var reengageSec = 0.0

    var state: EngineState = compute()
        private set

    /** Advances the clock by [dtSec] using the latest power and cadence readings. */
    fun tick(dtSec: Double, power: Int?, cadence: Int?): EngineState {
        val pedaling = (power ?: 0) > 15 || (cadence ?: 0) > 25
        if (pedaling) {
            started = true
            idleSec = 0.0
        } else {
            idleSec += dtSec
        }
        if (isRunning()) {
            workoutSec += dtSec
            val tl = timeline
            if (tl != null && !continuing && workoutSec >= tl.totalSec) {
                workoutSec = tl.totalSec.toDouble()
                finished = true
            }
        }
        updateErgState(dtSec, power, cadence)
        state = compute()
        return state
    }

    /** Compatibility with the simple form used in tests. */
    fun tick(dtSec: Double, pedaling: Boolean): EngineState = tick(dtSec, if (pedaling) 100 else 0, if (pedaling) 90 else 0)

    private fun updateErgState(dt: Double, power: Int?, cadence: Int?) {
        val target = currentTarget()
        // Without a cadence reading there is nothing to judge the "spiral" by, so ERG stays normal.
        if (config.spiralRpm <= 0 || target == null || cadence == null || !ergActive() || !isRunning()) {
            ergState = ErgState.NORMAL
            lowSec = 0.0; goodSec = 0.0; reengageSec = 0.0
            return
        }
        // A block that prescribes a low cadence (strength drill at 55 rpm) must not count as bogging down.
        val release = minOf(config.spiralRpm, (currentSegment()?.cadence ?: Int.MAX_VALUE) - 10).coerceAtLeast(20)
        val recover = release + 10
        val bogged = cadence in 1 until release
        when (ergState) {
            ErgState.NORMAL -> {
                lowSec = if (bogged) lowSec + dt else 0.0
                if (lowSec >= RELEASE_AFTER_SEC) {
                    ergState = ErgState.RELEASED
                    lowSec = 0.0; goodSec = 0.0
                }
            }
            ErgState.RELEASED -> {
                goodSec = if (cadence >= recover) goodSec + dt else 0.0
                if (goodSec >= REENGAGE_AFTER_SEC) {
                    ergState = ErgState.RE_ENGAGING
                    reengageSec = 0.0
                }
            }
            ErgState.RE_ENGAGING -> {
                reengageSec += dt
                if (reengageSec >= RAMP_IN_SEC) ergState = ErgState.NORMAL
                else if (bogged) { ergState = ErgState.RELEASED; goodSec = 0.0 }
            }
        }
    }

    /** The clock runs once the rider starts pedaling, unless paused by hand or by stopping. */
    private fun isRunning(): Boolean =
        started && !userPaused && !finished && !(config.autoPauseSec > 0 && idleSec >= config.autoPauseSec)

    private fun autoPaused() = started && !userPaused && config.autoPauseSec > 0 && idleSec >= config.autoPauseSec

    fun togglePause() = setPaused(!userPaused)

    fun setPaused(paused: Boolean) {
        userPaused = paused
        if (!paused) started = true
        state = compute()
    }

    /** Jumps to the start of the next segment. */
    fun skip() {
        val tl = timeline ?: return
        val i = tl.segmentIndexAt(workoutSec)
        if (i < 0) return
        if (i + 1 < tl.segments.size) {
            workoutSec = tl.segments[i + 1].startSec.toDouble()
        } else {
            workoutSec = tl.totalSec.toDouble()
            finished = true
        }
        state = compute()
    }

    /** Restarts the current segment, or the previous one when less than 5 s of the current one are ridden. */
    fun back() {
        val tl = timeline ?: return
        val i = tl.segmentIndexAt(workoutSec)
        if (i < 0) return
        val seg = tl.segments[i]
        workoutSec = if (workoutSec - seg.startSec < 5 && i > 0) tl.segments[i - 1].startSec.toDouble() else seg.startSec.toDouble()
        state = compute()
    }

    /** Makes the current segment [sec] seconds longer; TrainerRoad does this for warm-up and cool-down. */
    fun extendCurrent(sec: Int) {
        val tl = timeline ?: return
        val i = tl.segmentIndexAt(workoutSec)
        if (i < 0) return
        timeline = tl.withSegmentExtended(i, sec)
        state = compute()
    }

    /** Ramp test: ends the test immediately and jumps to the final (cool-down) segment. */
    fun bail() {
        val tl = timeline ?: return
        val last = tl.segments.lastIndex
        if (last < 0) return
        workoutSec = tl.segments[last].startSec.toDouble()
        state = compute()
    }

    /** After the workout: keep recording with a manual target, like Zwift's "continue riding". */
    fun continueRiding(watts: Int) {
        if (!finished) return
        finished = false
        continuing = true
        manualWatts = watts.coerceIn(0, 2000)
        state = compute()
    }

    /** Changes the intensity offset, e.g. +5 makes every target 5 % harder. */
    fun adjustIntensity(deltaPct: Int) = setIntensity(intensity + deltaPct)

    fun setIntensity(pct: Int) {
        intensity = pct.coerceIn(50, 150)
        state = compute()
    }

    /** Free ride only: changes the manual target. */
    fun adjustManual(deltaWatts: Int) = setManual(manualWatts + deltaWatts)

    fun setManual(watts: Int) {
        manualWatts = watts.coerceIn(0, 2000)
        state = compute()
    }

    /** Switches ERG on or off by hand; off means simulation mode with [gradePct]. */
    fun setErg(on: Boolean) {
        ergOn = on
        ergState = ErgState.NORMAL
        state = compute()
    }

    fun adjustGrade(deltaPct: Double) {
        grade = (grade + deltaPct).coerceIn(-5.0, 15.0)
        state = compute()
    }

    /** Rider asks for the full target right now after a release. */
    fun reengage() {
        ergState = ErgState.NORMAL
        lowSec = 0.0
        state = compute()
    }

    fun finish() {
        finished = true
        continuing = false
        state = compute()
    }

    private fun scaled(w: Int?): Int? = w?.let { (it * intensity / 100.0).roundToInt() }

    private fun currentSegment(): Segment? {
        val tl = timeline ?: return null
        return tl.segments.getOrNull(tl.segmentIndexAt(workoutSec))
    }

    private fun currentTarget(): Int? {
        val tl = timeline
        if (tl == null || continuing) return manualWatts
        if (finished) return null
        return scaled(tl.targetWattsAt(workoutSec, config.ftp))
    }

    private fun ergActive(): Boolean = ergOn && (currentSegment()?.erg ?: true)

    private fun compute(): EngineState {
        val tl = timeline
        val paused = !isRunning()
        val erg = ergActive()
        if (tl == null || continuing) {
            val target = manualWatts
            return EngineState(
                workoutSec = workoutSec,
                totalSec = tl?.totalSec ?: 0,
                segmentIndex = -1,
                targetWatts = target,
                commandWatts = if (erg) commandFor(target, null) else null,
                intensityPct = 100,
                paused = paused,
                autoPaused = autoPaused(),
                finished = finished,
                erg = erg,
                ergState = ergState,
                gradePct = grade,
                continuing = continuing,
            )
        }
        val i = tl.segmentIndexAt(workoutSec)
        val seg = tl.segments.getOrNull(i)
        val next = tl.segments.getOrNull(i + 1)
        val target = if (finished) null else scaled(tl.targetWattsAt(workoutSec, config.ftp))
        val remaining = seg?.let { (it.endSec - workoutSec) } ?: 0.0
        // Send the next target a moment early so the trainer has changed when the segment starts.
        val early = if (next != null && next.erg && remaining <= config.earlyTargetSec) scaled(next.startPower.watts(config.ftp)) else null
        return EngineState(
            workoutSec = workoutSec,
            totalSec = tl.totalSec,
            segmentIndex = i,
            segmentRemainingSec = remaining.toInt().coerceAtLeast(0),
            targetWatts = target,
            commandWatts = if (erg && target != null) commandFor(target, early) else null,
            nextTargetWatts = scaled(next?.startPower?.watts(config.ftp)),
            nextSegment = next,
            intensityPct = intensity,
            paused = paused,
            autoPaused = autoPaused(),
            finished = finished,
            targetCadence = seg?.cadence,
            cue = seg?.cueAt(workoutSec - seg.startSec)?.text,
            segmentLabel = seg?.label.orEmpty(),
            erg = erg,
            ergState = ergState,
            gradePct = grade,
            continuing = false,
        )
    }

    private fun commandFor(target: Int, early: Int?): Int = when (ergState) {
        ErgState.NORMAL -> early ?: target
        ErgState.RELEASED -> (target * RELEASED_FRACTION).roundToInt()
        ErgState.RE_ENGAGING -> {
            val f = RELEASED_FRACTION + (1 - RELEASED_FRACTION) * (reengageSec / RAMP_IN_SEC).coerceIn(0.0, 1.0)
            (target * f).roundToInt()
        }
    }

    companion object {
        const val AUTO_PAUSE_SEC = 3.0
        const val RELEASE_AFTER_SEC = 3.0
        const val REENGAGE_AFTER_SEC = 2.0
        const val RAMP_IN_SEC = 3.0
        const val RELEASED_FRACTION = 0.4
    }
}
