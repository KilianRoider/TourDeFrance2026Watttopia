package app.rollentrainer.core.race

import app.rollentrainer.core.route.Climb
import app.rollentrainer.core.route.Route
import kotlin.math.abs

/** A climb found in the profile; distances in metres from the start of the route. */
data class ProfileClimb(
    val startM: Double,
    val topM: Double,
    val gainM: Double,
    /** Name from the stage's categorised climbs when one matches, else empty. */
    val name: String = "",
    val category: String = "",
) {
    val lengthM: Double get() = topM - startM
    val avgPct: Double get() = if (lengthM > 0) gainM / lengthM * 100 else 0.0
    /** Length times grade, the usual measure of how hard a climb is (Alpe d'Huez ≈ 110 000, a 4th category ≈ 8 000). */
    val score: Double get() = lengthM * avgPct
    val isMajor: Boolean get() = score >= 35_000
}

/** How the stage is decided at the end, from the profile of its last kilometres. */
enum class FinaleKind {
    /** Flat run-in: lead-out trains and a bunch sprint. */
    SPRINT,
    /** Short steep finish: puncheurs attack in the last few hundred metres. */
    PUNCH,
    /** Summit finish on a real mountain: the favourites attack on the climb. */
    SUMMIT,
    /** A last hard climb not far from the line: attacks over the top, then a reduced sprint or a solo. */
    HILL,
}

/**
 * Reads the climbs out of an elevation profile, so tactics follow the terrain of whatever profile the
 * route carries (the stage profiles will become more exact; nothing here depends on their distances).
 */
object Terrain {
    private const val STEP = 100.0

    fun climbs(route: Route, named: List<Climb> = route.stage?.climbs.orEmpty()): List<ProfileClimb> {
        val len = route.lengthM
        if (len < 2 * STEP) return emptyList()
        val n = (len / STEP).toInt() + 1
        val e = DoubleArray(n) { route.elevationAt(minOf(it * STEP, len)) }
        // Uphill where the grade over ±300 m is above 2 %; a profile with sparse points is linear between them.
        val up = BooleanArray(n) { i ->
            val a = e[(i - 3).coerceAtLeast(0)]
            val b = e[(i + 3).coerceAtMost(n - 1)]
            val span = ((i + 3).coerceAtMost(n - 1) - (i - 3).coerceAtLeast(0)) * STEP
            span > 0 && (b - a) / span > 0.02
        }
        val runs = mutableListOf<IntArray>()
        var i = 0
        while (i < n) {
            if (!up[i]) { i++; continue }
            var j = i
            while (j + 1 < n && up[j + 1]) j++
            runs += intArrayOf(i, j)
            i = j + 1
        }
        // Join runs separated by a short false flat or a small dip.
        val merged = mutableListOf<IntArray>()
        for (r in runs) {
            val last = merged.lastOrNull()
            if (last != null && (r[0] - last[1]) * STEP <= 1500 && lowest(e, last[1], r[0]) > e[last[1]] - 25) last[1] = r[1]
            else merged += r.copyOf()
        }
        // The named climbs are at stage kilometres; a finale cut starts again at 0, so match where it lies in the stage.
        val offset = RacePlan.startOffset(route)
        val out = mutableListOf<ProfileClimb>()
        for (r in merged) {
            // From the lowest point at the foot to the highest point at the top.
            var s = r[0]
            for (k in r[0]..r[1]) if (e[k] < e[s]) s = k
            var t = r[1].coerceAtMost(n - 1)
            for (k in s..minOf(n - 1, r[1] + 3)) if (e[k] > e[t]) t = k
            val gain = e[t] - e[s]
            val lenM = (t - s) * STEP
            if (lenM <= 0) continue
            val steep = gain / lenM
            if (gain >= 40 || (gain >= 20 && steep >= 0.05)) {
                val top = minOf(t * STEP, len)
                val match = named.minByOrNull { abs(it.summitKm * 1000 - (top + offset)) }
                    ?.takeIf { abs(it.summitKm * 1000 - (top + offset)) < 2500 }
                out += ProfileClimb(s * STEP, top, gain, match?.name.orEmpty(), match?.category.orEmpty())
            }
        }
        return out
    }

    private fun lowest(e: DoubleArray, from: Int, to: Int): Double {
        var m = Double.MAX_VALUE
        for (k in from..to) if (e[k] < m) m = e[k]
        return m
    }

    /** How the finish is decided and which climb decides it. */
    fun finale(route: Route, climbs: List<ProfileClimb>): Pair<FinaleKind, ProfileClimb?> {
        val len = route.lengthM
        val last = climbs.lastOrNull { it.score >= 6_000 } ?: return FinaleKind.SPRINT to null
        val toGoAtTop = len - last.topM
        return when {
            toGoAtTop < 1500 && last.isMajor -> FinaleKind.SUMMIT to last
            toGoAtTop < 1500 && last.avgPct >= 3.5 -> FinaleKind.PUNCH to last
            last.isMajor && toGoAtTop < 30_000 -> FinaleKind.HILL to last
            last.score >= 15_000 && last.avgPct >= 5 && toGoAtTop < 15_000 -> FinaleKind.HILL to last
            else -> FinaleKind.SPRINT to null
        }
    }
}
