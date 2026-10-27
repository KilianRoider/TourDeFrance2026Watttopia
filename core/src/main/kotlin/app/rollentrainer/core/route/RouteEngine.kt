package app.rollentrainer.core.route

import kotlin.math.roundToInt

/** A rider at constant power the user can race against. */
data class Pacer(val name: String, val powerW: Int)

/** Where a pacer is relative to the rider. */
data class PacerState(
    val name: String,
    val powerW: Int,
    val distanceM: Double,
    val speedMps: Double,
    val gapM: Double,
    val finished: Boolean,
    /** Exact time in seconds at which the pacer crossed the line, or null while still riding. */
    val finishTimeSec: Double? = null,
)

/** The rider's position on the virtual road. */
data class RouteState(
    val route: Route,
    val distanceM: Double = 0.0,
    val speedMps: Double = 0.0,
    val gradePct: Double = 0.0,
    val elevationM: Double = 0.0,
    val ascentM: Double = 0.0,
    val elapsedSec: Double = 0.0,
    val finished: Boolean = false,
    val pacers: List<PacerState> = emptyList(),
    /** Landmark passed in the last tick, shown as a sign. */
    val passedLandmark: Landmark? = null,
    /** Exact time in seconds at which the rider crossed the line (interpolated within the tick). */
    val finishTimeSec: Double? = null,
) {
    /** True when this pacer reached the top before the rider (ties go to the rider). */
    fun beatenBy(p: PacerState): Boolean {
        val mine = finishTimeSec
        val theirs = p.finishTimeSec ?: return false
        return mine == null || theirs < mine
    }

    val remainingM: Double get() = (route.lengthM - distanceM).coerceAtLeast(0.0)
    val progress: Double get() = if (route.lengthM > 0) (distanceM / route.lengthM).coerceIn(0.0, 1.0) else 0.0
    val speedKmh: Double get() = Physics.kmh(speedMps)
    val vamMph: Int get() = Physics.vam(speedMps, gradePct).roundToInt()
    /** Seconds to the finish at the current speed, or null when standing. */
    val etaSec: Int? get() = if (speedMps > 0.3) (remainingM / speedMps).roundToInt() else null
    val nextLandmark: Landmark? get() = route.nextLandmark(distanceM)
}

/**
 * Moves the rider (and the pacers) along a route from the measured power. The trainer is put into
 * simulation mode with the road's grade by the caller; this class only does the physics.
 */
class RouteEngine(
    val route: Route,
    private val params: RiderParams,
    pacers: List<Pacer> = emptyList(),
) {
    var state = RouteState(route, gradePct = route.gradientAt(0.0), elevationM = route.startElevationM)
        private set

    private var speed = 0.0
    private var distance = 0.0
    private var elapsed = 0.0
    private val pacerList = pacers
    private val pacerDistance = DoubleArray(pacers.size)
    private val pacerSpeed = DoubleArray(pacers.size)
    private val pacerFinish = arrayOfNulls<Double>(pacers.size)
    private var finishTime: Double? = null

    /**
     * One tick; while [paused] nobody moves. A pause stops the rider and the pacers alike: everybody
     * starts again from a standstill, so a break costs nobody ground in the race.
     */
    fun tick(dt: Double, powerW: Int?, paused: Boolean): RouteState {
        if (paused || state.finished) {
            speed = 0.0
            pacerSpeed.fill(0.0)
            state = state.copy(speedMps = 0.0, pacers = state.pacers.map { it.copy(speedMps = 0.0) }, passedLandmark = null)
            return state
        }
        if (dt <= 0) {
            state = state.copy(passedLandmark = null)
            return state
        }
        val before = distance
        val grade = route.gradientAt(distance)
        speed = Physics.step(speed, (powerW ?: 0).toDouble(), grade, dt, params)
        val start = elapsed
        // Time within this tick at which [from] + [v]·t reaches the line.
        fun crossing(from: Double, v: Double): Double = start + if (v > 0) ((route.lengthM - from) / v).coerceIn(0.0, dt) else dt
        val raw = distance + speed * dt
        if (raw >= route.lengthM && finishTime == null) finishTime = crossing(distance, speed)
        distance = raw.coerceAtMost(route.lengthM)
        elapsed += dt
        val finished = distance >= route.lengthM
        pacerList.forEachIndexed { i, p ->
            if (pacerDistance[i] < route.lengthM) {
                val g = route.gradientAt(pacerDistance[i])
                pacerSpeed[i] = Physics.step(pacerSpeed[i], p.powerW.toDouble(), g, dt, params)
                val pr = pacerDistance[i] + pacerSpeed[i] * dt
                if (pr >= route.lengthM) pacerFinish[i] = crossing(pacerDistance[i], pacerSpeed[i])
                pacerDistance[i] = pr.coerceAtMost(route.lengthM)
            }
        }
        val passed = route.landmarks.firstOrNull { it.distanceM > before && it.distanceM <= distance }
        state = RouteState(
            route = route,
            distanceM = distance,
            // On the tick that reaches the top: the average speed of the metres actually ridden, so the
            // recorded distance ends exactly at the summit.
            speedMps = if (finished) (distance - before) / dt else speed,
            gradePct = route.gradientAt(distance),
            elevationM = route.elevationAt(distance),
            ascentM = route.ascentUntil(distance),
            elapsedSec = elapsed,
            finished = finished,
            pacers = pacerList.mapIndexed { i, p ->
                PacerState(
                    p.name, p.powerW, pacerDistance[i], if (pacerDistance[i] >= route.lengthM) 0.0 else pacerSpeed[i],
                    pacerDistance[i] - distance, pacerDistance[i] >= route.lengthM, pacerFinish[i],
                )
            },
            passedLandmark = passed,
            finishTimeSec = finishTime,
        )
        return state
    }
}
