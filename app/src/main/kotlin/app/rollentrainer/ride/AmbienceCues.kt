package app.rollentrainer.ride

import app.rollentrainer.core.route.Biome
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteKind
import app.rollentrainer.core.route.RouteState
import app.rollentrainer.core.route.Stages
import kotlin.math.abs
import kotlin.math.max

/**
 * What the ambience needs to know about the road that the route itself does not say directly: how many
 * spectators stand at a distance, cobbles, cowbells, and whether the end of the route is a real finish.
 */
internal object AmbienceCues {
    /** Climbing as the ambience understands it: the crowd turns into the dense roar of a pass. */
    const val CLIMB_PCT = 4.0

    /** A finale is the end of the stage; a shortened climb stops somewhere on the mountain, without a finish line. */
    fun finishesAtEnd(route: Route): Boolean = route.variant.isNotBlank() || route.fraction >= 0.999

    /**
     * The surface when no geodata pack says it (RideSession.surfaceAt reads the pack's cobble flags). The
     * Champs-Élysées lap in race direction is paved with setts from the line up round the Étoile and down
     * the avenue to the Concorde, and from the Concorde back up to the line (where the pack's flags have
     * them: 0-3980 m and from 5880 m); everything else the app rides is asphalt.
     */
    fun surface(route: Route, distanceM: Double): RoadSurface =
        if (route.id == "champs-elysees" && (distanceM < 3980 || distanceM > 5880)) RoadSurface.COBBLES else RoadSurface.ASPHALT

    /** Cowbells on Alpine and Pyrenean climbs; a stage's own biome is its start's, so high ground counts too. */
    fun cowbells(route: Route, rs: RouteState): Boolean =
        rs.gradePct > CLIMB_PCT && (route.biome == Biome.ALPINE || route.biome == Biome.PYRENEAN || (route.isStage && rs.elevationM > 700))

    /**
     * Spectators near the rider, 0..1, estimated from the profile as the old 3D world placed them: they
     * thicken over the last two kilometres to a stage finish or a summit (the more so the harder the
     * climb), villages and sprints along the way get a bunch, a few stand everywhere. The geodata world
     * knows the real crowd (RaceDressing.crowdDensityAt) and replaces this through RideSession.crowdDensity.
     */
    fun crowdDensity(route: Route, distanceM: Double): Double = model(route).at(distanceM)

    private class Model(val route: Route) {
        /** Summits in this route's distances with the weight of their category. */
        val summits: List<Pair<Double, Double>>
        val finish: Boolean = finishesAtEnd(route) && (route.kind == RouteKind.CLIMB || route.isStage)
        val base: Double = when {
            route.id == "champs-elysees" -> 0.45
            route.biome == Biome.CITY -> 0.15
            else -> 0.05
        }

        init {
            val info = route.stage
            summits = if (info == null) emptyList() else {
                // A finale starts again at 0: shift the stage's kilometres by what was cut off.
                val stageLength = Stages.all.firstOrNull { it.id == route.id }?.lengthM ?: route.lengthM
                val shift = if (route.variant.startsWith("Finale")) stageLength - route.lengthM else route.offsetM
                info.climbs.map { c ->
                    val w = when (c.category) {
                        "HC" -> 1.0
                        "1" -> 0.9
                        "2" -> 0.7
                        "3" -> 0.5
                        "4" -> 0.4
                        else -> 0.3
                    }
                    (c.summitKm * 1000 - shift) to w
                }.filter { it.first > 0 && it.first <= route.lengthM + 1 }
            }
        }

        fun at(s: Double): Double {
            var c = base
            fun near(toGo: Double, weight: Double) {
                if (toGo in 0.0..2000.0) c = max(c, weight * (1.4 / (1 + toGo / 400)).coerceIn(0.0, 1.0))
            }
            if (finish) near(route.lengthM - s, 1.0)
            for ((summit, w) in summits) near(summit - s, w)
            if (route.landmarks.any { abs(it.distanceM - s) < 150 }) c += 0.3
            return c.coerceIn(0.0, 1.0)
        }
    }

    // Asked once a second for the same route: the model (summits from the stage list) is kept for it.
    @Volatile private var cached: Model? = null

    private fun model(route: Route): Model {
        val m = cached
        if (m != null && m.route === route) return m
        return Model(route).also { cached = it }
    }
}
