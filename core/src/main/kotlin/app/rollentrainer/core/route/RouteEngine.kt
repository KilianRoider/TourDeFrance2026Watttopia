package app.rollentrainer.core.route

import kotlin.math.roundToInt

/** A rider at constant power the user can race against. */
data class Pacer(val name: String, val powerW: Int)

/** Where a pacer is relative to the rider. */
data class PacerState(val name: String, val powerW: Int, val distanceM: Double, val speedMps: Double, val gapM: Double, val finished: Boolean)

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
) {
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

    /** One tick; while [paused] nobody moves. */
    fun tick(dt: Double, powerW: Int?, paused: Boolean): RouteState {
        if (paused || dt <= 0 || state.finished) {
            state = state.copy(passedLandmark = null)
            return state
        }
        val before = distance
        val grade = route.gradientAt(distance)
        speed = Physics.step(speed, (powerW ?: 0).toDouble(), grade, dt, params)
        distance = (distance + speed * dt).coerceAtMost(route.lengthM)
        elapsed += dt
        val finished = distance >= route.lengthM
        pacerList.forEachIndexed { i, p ->
            if (pacerDistance[i] < route.lengthM) {
                val g = route.gradientAt(pacerDistance[i])
                pacerSpeed[i] = Physics.step(pacerSpeed[i], p.powerW.toDouble(), g, dt, params)
                pacerDistance[i] = (pacerDistance[i] + pacerSpeed[i] * dt).coerceAtMost(route.lengthM)
            }
        }
        val passed = route.landmarks.firstOrNull { it.distanceM > before && it.distanceM <= distance }
        state = RouteState(
            route = route,
            distanceM = distance,
            speedMps = if (finished) 0.0 else speed,
            gradePct = route.gradientAt(distance),
            elevationM = route.elevationAt(distance),
            ascentM = route.ascentUntil(distance),
            elapsedSec = elapsed,
            finished = finished,
            pacers = pacerList.mapIndexed { i, p ->
                PacerState(p.name, p.powerW, pacerDistance[i], if (pacerDistance[i] >= route.lengthM) 0.0 else pacerSpeed[i], pacerDistance[i] - distance, pacerDistance[i] >= route.lengthM)
            },
            passedLandmark = passed,
        )
        return state
    }
}
