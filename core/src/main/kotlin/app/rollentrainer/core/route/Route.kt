package app.rollentrainer.core.route

import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** Elevation at a distance from the start. */
@Serializable
data class RoutePoint(val distanceM: Double, val elevationM: Double)

/** A named spot on the route, shown as a sign when the rider passes it. */
@Serializable
data class Landmark(val distanceM: Double, val label: String)

@Serializable
enum class RouteKind(val title: String) { CLIMB("Anstieg"), FLAT("Flach"), ROLLING("Wellig") }

/**
 * A virtual road: elevation over distance. Built-in routes reproduce the profiles of famous
 * climbs from public elevation data; the road geometry (bends) is synthesised from the
 * hairpin count, since only the profile matters for the trainer.
 */
@Serializable
data class Route(
    val id: String,
    val name: String,
    val region: String,
    val description: String,
    val kind: RouteKind,
    val points: List<RoutePoint>,
    val landmarks: List<Landmark> = emptyList(),
    val hairpins: Int = 0,
    /** Landscape style for the 3D world. */
    val biome: Biome = Biome.ALPINE,
    /** Fraction of the original route this instance covers (1.0 = full). */
    val fraction: Double = 1.0,
    val builtIn: Boolean = true,
) {
    val lengthM: Double get() = points.lastOrNull()?.distanceM ?: 0.0
    val lengthKm: Double get() = lengthM / 1000
    val startElevationM: Double get() = points.firstOrNull()?.elevationM ?: 0.0
    val summitElevationM: Double get() = points.maxOfOrNull { it.elevationM } ?: 0.0

    /** Total climbing, counting only uphill parts. */
    val ascentM: Double
        get() = points.zipWithNext().sumOf { (a, b) -> (b.elevationM - a.elevationM).coerceAtLeast(0.0) }

    val avgGradientPct: Double
        get() = if (lengthM > 0) (points.last().elevationM - points.first().elevationM) / lengthM * 100 else 0.0

    val maxGradientPct: Double
        get() = points.zipWithNext().maxOfOrNull { (a, b) -> gradientBetween(a, b) } ?: 0.0

    private fun gradientBetween(a: RoutePoint, b: RoutePoint): Double {
        val d = b.distanceM - a.distanceM
        return if (d > 0) (b.elevationM - a.elevationM) / d * 100 else 0.0
    }

    /** Index of the segment that contains [distanceM] (clamped to the route). */
    private fun segmentAt(distanceM: Double): Int {
        if (points.size < 2) return 0
        var lo = 0
        var hi = points.size - 2
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (points[mid].distanceM <= distanceM) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Grade in percent at a distance, constant within each profile segment. */
    fun gradientAt(distanceM: Double): Double {
        if (points.size < 2) return 0.0
        val d = distanceM.coerceIn(0.0, lengthM)
        val i = segmentAt(d)
        return gradientBetween(points[i], points[i + 1])
    }

    /** Interpolated elevation at a distance. */
    fun elevationAt(distanceM: Double): Double {
        if (points.isEmpty()) return 0.0
        if (points.size == 1) return points[0].elevationM
        val d = distanceM.coerceIn(0.0, lengthM)
        val i = segmentAt(d)
        val a = points[i]
        val b = points[i + 1]
        val span = b.distanceM - a.distanceM
        if (span <= 0) return a.elevationM
        val f = (d - a.distanceM) / span
        return a.elevationM + (b.elevationM - a.elevationM) * f
    }

    /** Climbing done up to a distance. */
    fun ascentUntil(distanceM: Double): Double {
        var sum = 0.0
        var last = points.firstOrNull() ?: return 0.0
        for (p in points.drop(1)) {
            if (p.distanceM >= distanceM) {
                val e = elevationAt(distanceM)
                sum += (e - last.elevationM).coerceAtLeast(0.0)
                return sum
            }
            sum += (p.elevationM - last.elevationM).coerceAtLeast(0.0)
            last = p
        }
        return sum
    }

    /** The next landmark ahead of [distanceM], or null. */
    fun nextLandmark(distanceM: Double): Landmark? = landmarks.firstOrNull { it.distanceM > distanceM }

    /**
     * The first [fraction] of the route (0.25, 0.5, 0.75); the profile keeps its gradients, so
     * a shortened Alpe d'Huez still starts with its steepest kilometres.
     */
    fun shortened(fraction: Double): Route {
        val f = fraction.coerceIn(0.1, 1.0)
        if (f >= 1.0) return this
        val cut = lengthM * f
        val kept = points.filter { it.distanceM < cut } + RoutePoint(cut, elevationAt(cut))
        return copy(
            points = kept,
            landmarks = landmarks.filter { it.distanceM <= cut },
            hairpins = (hairpins * f).roundToInt(),
            fraction = f,
        )
    }

    companion object {
        /**
         * Builds a route from per-kilometre average gradients, the usual way climb profiles are published.
         * The last entry covers the remaining partial kilometre so that [lengthKm] is exact.
         */
        fun fromKmGradients(
            id: String,
            name: String,
            region: String,
            description: String,
            kind: RouteKind,
            startElevationM: Double,
            lengthKm: Double,
            kmGradients: List<Double>,
            landmarks: List<Landmark> = emptyList(),
            hairpins: Int = 0,
            biome: Biome = Biome.ALPINE,
        ): Route {
            val pts = mutableListOf(RoutePoint(0.0, startElevationM))
            var d = 0.0
            var e = startElevationM
            val totalM = lengthKm * 1000
            kmGradients.forEachIndexed { i, g ->
                val remaining = totalM - d
                val span = if (i == kmGradients.lastIndex) remaining else minOf(1000.0, remaining)
                if (span <= 0) return@forEachIndexed
                d += span
                e += span * g / 100
                pts += RoutePoint(d, e)
            }
            if (d < totalM - 1) pts += RoutePoint(totalM, e)
            return Route(id, name, region, description, kind, pts, landmarks, hairpins, biome)
        }
    }
}
