package app.rollentrainer.core.engine

import app.rollentrainer.core.model.Timeline
import kotlin.math.roundToInt

/** What the ride screen shows about the workout at one moment. */
data class EngineState(
    val workoutSec: Double = 0.0,
    val totalSec: Int = 0,
    val segmentIndex: Int = -1,
    val segmentRemainingSec: Int = 0,
    val targetWatts: Int? = null,
    val nextTargetWatts: Int? = null,
    val intensityPct: Int = 100,
    val paused: Boolean = true,
    val autoPaused: Boolean = false,
    val finished: Boolean = false,
)

/**
 * Drives a workout in ERG mode: advances time while the rider pedals,
 * computes the target power and supports pause, skip and an intensity offset.
 * A null [timeline] is a free ride with a manually set target.
 */
class WorkoutEngine(
    private val timeline: Timeline?,
    private val ftp: Int,
    private val autoPause: Boolean = true,
) {
    private var workoutSec = 0.0
    private var userPaused = false
    private var started = false
    private var idleSec = 0.0
    private var intensity = 100
    private var manualWatts = 150
    private var finished = false

    var state: EngineState = compute()
        private set

    /** Advances the clock by [dtSec]. [pedaling] is true when the rider produces power or cadence. */
    fun tick(dtSec: Double, pedaling: Boolean): EngineState {
        if (pedaling) {
            started = true
            idleSec = 0.0
        } else {
            idleSec += dtSec
        }
        if (isRunning()) {
            workoutSec += dtSec
            if (timeline != null && workoutSec >= timeline.totalSec) {
                workoutSec = timeline.totalSec.toDouble()
                finished = true
            }
        }
        state = compute()
        return state
    }

    /** The clock runs once the rider starts pedaling, unless paused by hand or by stopping. */
    private fun isRunning(): Boolean =
        started && !userPaused && !finished && !(autoPause && idleSec >= AUTO_PAUSE_SEC)

    fun togglePause() {
        userPaused = !userPaused
        if (!userPaused) started = true
        state = compute()
    }

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

    /** Changes the intensity offset, e.g. +5 makes every target 5 % harder. */
    fun adjustIntensity(deltaPct: Int) {
        intensity = (intensity + deltaPct).coerceIn(50, 150)
        state = compute()
    }

    /** Free ride only: changes the manual target. */
    fun adjustManual(deltaWatts: Int) {
        manualWatts = (manualWatts + deltaWatts).coerceIn(0, 2000)
        state = compute()
    }

    fun setManual(watts: Int) {
        manualWatts = watts.coerceIn(0, 2000)
        state = compute()
    }

    fun finish() {
        finished = true
        state = compute()
    }

    private fun scaled(w: Int?): Int? = w?.let { (it * intensity / 100.0).roundToInt() }

    private fun compute(): EngineState {
        val tl = timeline
        val paused = !isRunning()
        if (tl == null) {
            return EngineState(
                workoutSec = workoutSec,
                targetWatts = manualWatts,
                intensityPct = 100,
                paused = paused,
                autoPaused = started && !userPaused && autoPause && idleSec >= AUTO_PAUSE_SEC,
                finished = finished,
            )
        }
        val i = tl.segmentIndexAt(workoutSec)
        val seg = tl.segments.getOrNull(i)
        val next = tl.segments.getOrNull(i + 1)
        return EngineState(
            workoutSec = workoutSec,
            totalSec = tl.totalSec,
            segmentIndex = i,
            segmentRemainingSec = seg?.let { (it.endSec - workoutSec).toInt().coerceAtLeast(0) } ?: 0,
            targetWatts = if (finished) null else scaled(tl.targetWattsAt(workoutSec, ftp)),
            nextTargetWatts = scaled(next?.startPower?.watts(ftp)),
            intensityPct = intensity,
            paused = paused,
            autoPaused = started && !userPaused && autoPause && idleSec >= AUTO_PAUSE_SEC,
            finished = finished,
        )
    }

    companion object {
        const val AUTO_PAUSE_SEC = 3.0
    }
}
