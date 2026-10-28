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
enum class RouteKind(val title: String) { CLIMB("Anstieg"), FLAT("Flach"), ROLLING("Wellig"), STAGE("Etappe") }

/** A categorised climb of a stage. */
@Serializable
data class Climb(
    val name: String,
    val summitM: Double,
    val summitKm: Double,
    val lengthKm: Double,
    val avgPct: Double,
    /** "HC", "1", "2", "3", "4" */
    val category: String,
)

/** What makes a route a stage of a multi-day race. */
@Serializable
data class StageInfo(
    val number: Int,
    val date: String,
    val startTown: String,
    val finishTown: String,
    /** flat, hilly, mountain, itt, ttt */
    val type: String,
    val climbs: List<Climb> = emptyList(),
    val winner: String = "",
) {
    val typeTitle: String
        get() = when (type) {
            "flat" -> "Flachetappe"
            "hilly" -> "Hügeletappe"
            "mountain" -> "Bergetappe"
            "itt" -> "Einzelzeitfahren"
            "ttt" -> "Mannschaftszeitfahren"
            else -> "Etappe"
        }
}

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
    /** Where the hairpins really are (apex, metres from the start); empty = spread evenly. */
    val hairpinsAtM: List<Double> = emptyList(),
    /** Number of the first bend; a shortened route keeps the full climb's numbering (0 = [hairpins]). */
    val firstHairpinNumber: Int = 0,
    /** Set for a stage of the tour. */
    val stage: StageInfo? = null,
    /** Name of the cut this instance is ("Finale 30 km"); empty for the whole route or a plain fraction. */
    val variant: String = "",
    /** For a part of a longer route: where it starts and how long the whole route is (0 = this is the whole route). */
    val offsetM: Double = 0.0,
    val fullLengthM: Double = 0.0,
) {
    val lengthM: Double get() = points.lastOrNull()?.distanceM ?: 0.0
    /** Length of the whole route this is part of; the own length when it is the whole route. */
    val wholeLengthM: Double get() = if (fullLengthM > 0) fullLengthM else lengthM
    /** Short name with the cut: "½ Alpe d'Huez", "Etappe 5 · Finale 30 km". */
    val displayName: String
        get() = when {
            variant.isNotBlank() -> "$name · $variant"
            fraction < 0.999 -> "${fractionText(fraction)} $name"
            else -> name
        }
    val isStage: Boolean get() = stage != null
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

    /** Average elevation over the route, weighted by distance. */
    val meanElevationM: Double
        get() {
            if (points.size < 2) return startElevationM
            var sum = 0.0
            points.zipWithNext { a, b -> sum += (a.elevationM + b.elevationM) / 2 * (b.distanceM - a.distanceM) }
            return sum / lengthM
        }

    /**
     * The last [km] kilometres: the finale of a stage, with the finish and the final climbs. The
     * distances start at 0 again; [offsetM] keeps where in the whole stage this part begins.
     */
    fun finale(km: Double): Route {
        val cutM = km * 1000
        if (cutM >= lengthM - 1) return this
        val from = lengthM - cutM
        // The ride stores the stage's id, so the finale is found again in the history and the progress.
        return section(from, lengthM).copy(
            id = id,
            fraction = cutM / lengthM,
            variant = "Finale %.0f km".format(km),
            offsetM = 0.0,
            fullLengthM = 0.0,
        )
    }

    /** The instance a ride was done on, from what the ride stored. */
    fun cut(fraction: Double, variant: String): Route = when {
        variant.startsWith("Finale") -> finale(variant.filter { it.isDigit() || it == '.' }.toDoubleOrNull() ?: lengthKm)
        else -> shortened(fraction)
    }

    /**
     * The part between two distances as a route of its own (for building the 3D world of a long
     * route piece by piece). Kind and biome follow the terrain of that part.
     */
    fun section(fromM: Double, toM: Double): Route {
        val a = fromM.coerceIn(0.0, lengthM)
        val b = toM.coerceIn(a, lengthM)
        val inner = points.filter { it.distanceM > a && it.distanceM < b }
        val pts = (listOf(RoutePoint(a, elevationAt(a))) + inner + RoutePoint(b, elevationAt(b)))
            .map { RoutePoint(it.distanceM - a, it.elevationM) }
        val part = copy(points = pts)
        val climbGrade = part.avgGradientPct
        // Any part of a stage (also a part of a finale, which already carries a derived kind) gets the kind
        // and biome of its own terrain.
        val sectionKind = when {
            !isStage -> kind
            climbGrade > 3.0 -> RouteKind.CLIMB
            part.ascentM / part.lengthM.coerceAtLeast(1.0) > 0.012 -> RouteKind.ROLLING
            else -> RouteKind.FLAT
        }
        val bends = hairpinsAtM.filter { it > a + 100 && it < b - 100 }.map { it - a }
        val sectionBiome = when {
            !isStage || biome == Biome.CITY -> biome
            part.meanElevationM > 1100 -> if (biome == Biome.FIELDS || biome == Biome.VOLCANIC) Biome.ALPINE else biome
            part.meanElevationM > 600 -> if (biome == Biome.FIELDS) Biome.PYRENEAN else biome
            else -> Biome.FIELDS
        }
        return part.copy(
            id = if (a == 0.0 && b >= lengthM) id else "$id@${a.toInt()}",
            kind = sectionKind,
            biome = sectionBiome,
            landmarks = landmarks.filter { it.distanceM >= a && it.distanceM <= b }.map { Landmark(it.distanceM - a, it.label) },
            hairpins = bends.size,
            hairpinsAtM = bends,
            firstHairpinNumber = 0,
            offsetM = offsetM + a,
            fullLengthM = wholeLengthM,
        )
    }

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
            hairpins = if (hairpinsAtM.isNotEmpty()) hairpinsAtM.count { it < cut - 60 } else (hairpins * f).roundToInt(),
            hairpinsAtM = hairpinsAtM.filter { it < cut - 60 },
            firstHairpinNumber = if (firstHairpinNumber > 0) firstHairpinNumber else hairpins,
            fraction = f,
        )
    }

    companion object {
        /** "½", "¾", "¼" for a shortened route, otherwise the percentage. */
        fun fractionText(fraction: Double): String = when {
            kotlin.math.abs(fraction - 0.75) < 0.01 -> "¾"
            kotlin.math.abs(fraction - 0.5) < 0.01 -> "½"
            kotlin.math.abs(fraction - 0.25) < 0.01 -> "¼"
            else -> "${(fraction * 100).roundToInt()} %"
        }

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
            hairpinsAtKm: List<Double> = emptyList(),
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
            return Route(
                id, name, region, description, kind, pts, landmarks,
                hairpins = if (hairpinsAtKm.isNotEmpty()) hairpinsAtKm.size else hairpins,
                biome = biome,
                hairpinsAtM = hairpinsAtKm.map { it * 1000 }.sorted(),
            )
        }
    }
}
