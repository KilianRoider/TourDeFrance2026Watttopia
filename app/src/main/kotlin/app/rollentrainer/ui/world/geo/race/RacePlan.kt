package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.PlacesData
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteKind
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.RoadBuilder
import app.rollentrainer.ui.world.geo.RoadProfile
import app.rollentrainer.ui.world.geo.SceneContext
import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Where the race happens on the pack's road, and how busy each 5 m sample is.
 *
 * Everything is derived once per scene from the real data: the stage's categorised climbs (summit km
 * from core/route/Stages.kt, snapped to the road's local summit), the intermediate sprint landmarks,
 * the TOWN stretches of the pack, its hairpins, the finish (the end of the whole route) and named fan
 * spots such as the Dutch corner of Alpe d'Huez. The density array is the single source for both the
 * placement and crowdDensityAt(), so the crowd sound and the picture agree.
 */
internal class RacePlan(ctx: SceneContext) {
    val path: RoutePath = ctx.path
    val n = path.size
    val step = path.stepM
    val lengthM = path.lengthM
    /** Per sample: kerbs and pavements there, exactly as W1's road builder and ground sampler see them. */
    private val kerbSamples: BooleanArray = RoadBuilder.kerbSamples(path, RoadBuilder.kerbTowns(ctx.places))

    val seed = ctx.seed xor ctx.meta.id.hashCode()
    val region: RegionStyle = ctx.region
    private val route: Route = ctx.route

    /** Route distances are pack distances when the route has the pipeline's real profile; otherwise scale. */
    private val k: Double = run {
        val rl = route.wholeLengthM
        if (rl > 1 && abs(lengthM - rl) / rl > 0.002) lengthM / rl else 1.0
    }

    class Climb(val name: String, val category: String, val factor: Double, val footS: Double, val summitS: Double)
    class Sprint(val name: String, val s: Double)
    class Town(val name: String, val sIn: Double, val sOut: Double, val population: Int?, val kerbs: Boolean)
    class Hairpin(val s: Double, val turnDeg: Double)
    enum class FanKind { DUTCH }
    class FanZone(val kind: FanKind, val s: Double, val radiusM: Double)

    val isStage: Boolean = route.isStage
    val climbs: List<Climb>
    val sprints: List<Sprint>
    val towns: List<Town>
    val hairpins: List<Hairpin> = ctx.meta.hairpins.map { Hairpin(it.s, it.turnDeg) }.sortedBy { it.s }
    val fanZones: List<FanZone>
    /** The finish line: the end of the whole route. */
    val finishS: Double = max(0.0, lengthM - 3.0)
    val mountainFinish: Boolean
    /** Start arch of a full stage (km 0), null for climbs. */
    val startS: Double? = if (isStage) min(8.0, lengthM * 0.01) else null

    /** Index of the other road legs (hairpin stacks, loops) to keep the crowd off their asphalt. */
    val grid = PathGrid(path)

    /** Crowd density per sample (at the sample's centre), 0..1, already masked (tunnels, bridges). */
    val density: FloatArray
    /** 0..1 how much a sample is a mountain-climb crowd (poses, road writing, runners). */
    val climbZone: FloatArray
    /** Camper / tent probability per 30 m slot centred on the sample. */
    val camp: FloatArray
    /** Barrier cover style per sample, -1 = no barriers (see [Barriers]). */
    val barrier: ByteArray
    /** Share of orange-dressed fans (Dutch corner, Basque country). */
    val orange: FloatArray
    /** Share of fans in polka-dot colours (KOM summits). */
    val polka: FloatArray

    init {
        towns = buildTowns(ctx.places)
        climbs = buildClimbs()
        sprints = buildSprints()
        fanZones = buildFanZones(ctx)
        mountainFinish = route.kind == RouteKind.CLIMB || climbs.any { abs(it.summitS - finishS) < 1500 } ||
            (path.elevationAt(finishS) - path.elevationAt(max(0.0, finishS - 5000)) > 150)
        density = FloatArray(n)
        climbZone = FloatArray(n)
        camp = FloatArray(n)
        barrier = ByteArray(n) { -1 }
        orange = FloatArray(n)
        polka = FloatArray(n)
        for (i in 0 until n) fillSample(i)
    }

    // --- features ------------------------------------------------------------------------------

    private fun buildTowns(places: PlacesData): List<Town> {
        // The road builder's own rule decides which towns have kerbs and pavements.
        val kerbTowns = RoadBuilder.kerbTowns(places).toSet()
        return places.towns.map { t ->
            val place = places.places.firstOrNull { it.name == t.name }
            Town(t.name, t.sIn, t.sOut, t.population ?: place?.population, t in kerbTowns)
        }
    }

    private fun catFactor(c: String) = when (c.uppercase()) { "HC" -> 1.0; "1" -> 0.95; "2" -> 0.8; "3" -> 0.6; else -> 0.45 }

    /** Highest road sample within [w] metres of [s]: summits in the data are given to the 100 m. */
    private fun snapSummit(s: Double, w: Double): Double {
        val r = path.indexRange(s - w, s + w)
        var best = r.first
        for (i in r) if (path.z[i] > path.z[best]) best = i
        return path.distanceOf(best)
    }

    private fun buildClimbs(): List<Climb> {
        val stage = route.stage
        if (stage != null && stage.climbs.isNotEmpty()) {
            return stage.climbs.map { c ->
                val s0 = (c.summitKm * 1000 * k).coerceIn(0.0, lengthM)
                val summit = snapSummit(s0, max(300.0, min(2500.0, 0.02 * s0)))
                val foot = (summit - c.lengthKm * 1000 * k).coerceAtLeast(0.0)
                Climb(c.name, c.category, catFactor(c.category), foot, summit)
            }.sortedBy { it.summitS }
        }
        if (route.kind == RouteKind.CLIMB || (route.kind != RouteKind.FLAT && path.ascentM() > 250)) {
            // A climb route: the whole road is the climb, its category from the height gained.
            var top = 0
            for (i in 0 until n) if (path.z[i] > path.z[top]) top = i
            val gain = path.z[top] - path.z[0]
            val cat = when { gain > 900 -> "HC"; gain > 550 -> "1"; gain > 300 -> "2"; gain > 150 -> "3"; else -> "4" }
            return listOf(Climb(route.name, cat, catFactor(cat), 0.0, path.distanceOf(top)))
        }
        return emptyList()
    }

    private fun fold(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().replace(Regex("[^a-z ]"), " ").trim()

    private fun buildSprints(): List<Sprint> = route.landmarks.filter { "sprint" in it.label.lowercase() }.map { lm ->
        var s = (lm.distanceM * k).coerceIn(0.0, lengthM)
        val name = fold(lm.label.replace(Regex("(?i)zwischensprint|sprint"), "").trim(' ', '-', '–', ','))
        // The sprint line is in the town: move it there when the pack knows the town nearby.
        val town = towns.filter { name.isNotEmpty() && (fold(it.name).contains(name) || name.contains(fold(it.name))) }
            .minByOrNull { abs((it.sIn + it.sOut) / 2 - s) }
        if (town != null && abs((town.sIn + town.sOut) / 2 - s) < 6000) s = (town.sIn + town.sOut) / 2
        Sprint(lm.label, offBridges(s, 160.0, 1500.0) ?: offBridges(s, 40.0, 1500.0) ?: s)
    }.sortedBy { it.s }

    /**
     * The distance nearest to [s] (within [searchM]) whose +-[halfM] is free of bridges and tunnels:
     * a sprint zone needs ground for its barriers, crowd and banner (Decize's centre is a Loire bridge).
     */
    private fun offBridges(s: Double, halfM: Double, searchM: Double): Double? {
        fun free(c: Double): Boolean {
            val a = max(0, ((c - halfM) / step).toInt())
            val b = min(n - 1, ((c + halfM) / step).toInt() + 1)
            if (c - halfM < 0 || c + halfM > lengthM) return false
            for (i in a..b) if ((path.flags[i].toInt() and (PathFlags.TUNNEL or PathFlags.BRIDGE)) != 0) return false
            return true
        }
        var d = 0.0
        while (d <= searchM) {
            if (free(s + d)) return s + d
            if (d > 0 && free(s - d)) return s - d
            d += step
        }
        return null
    }

    private fun buildFanZones(ctx: SceneContext): List<FanZone> {
        val out = ArrayList<FanZone>()
        val tm = Tmerc(ctx.meta.projection)
        for ((kind, lat, lon, radius) in KNOWN_FAN_SPOTS) {
            val p = tm.forward(lat, lon)
            val near = grid.nearest(p[0], p[1], 200.0) ?: continue
            out += FanZone(kind, near.first, radius)
        }
        return out
    }

    // --- density -------------------------------------------------------------------------------

    private fun ramp(x: Double, a: Double, b: Double) = ((x - a) / (b - a)).coerceIn(0.0, 1.0)

    /** Crowd of a categorised climb at [s]: thickening over the last 5 km, thickest in the last 2. */
    private fun climbDensity(c: Climb, s: Double): Double {
        if (s < c.footS - 200) return 0.0
        val r = c.summitS - s
        val reach = when (c.category.uppercase()) { "HC", "1", "2" -> 1e9; "3" -> 3000.0; else -> 2000.0 }
        val d = when {
            r < 0 -> 0.95 * max(0.0, 1 + r / 350)
            r <= 1000 -> 0.85 + 0.15 * (1 - r / 1000)
            r <= 2000 -> 0.6 + 0.25 * (2000 - r) / 1000
            r <= 5000 -> 0.25 + 0.35 * (5000 - r) / 3000
            else -> when (c.category.uppercase()) { "HC", "1" -> 0.17; "2" -> 0.09; else -> 0.0 }
        }
        val fade = if (r > reach) 0.0 else if (r > reach - 500) (reach - r) / 500 else 1.0
        return d * c.factor * fade
    }

    /** Rural background: mostly empty with clusters (farms, picnic spots, a bend with a view). */
    private fun baseDensity(s: Double): Double {
        val a = Noise.value(s / 230.0, seed + 11)
        val b = Noise.value(s / 55.0, seed + 12)
        return 0.006 + 0.13 * smooth(ramp(a, 0.58, 0.93)) * (0.55 + 0.45 * b)
    }

    private fun smooth(t: Double) = t * t * (3 - 2 * t)

    private fun townDensity(t: Town): Double {
        val pop = t.population ?: (if (t.kerbs) 2000 else 300)
        return (0.3 + 0.11 * kotlin.math.log10(max(100.0, pop.toDouble()) / 100.0)).coerceAtMost(0.68)
    }

    /** Raw density at [s] (no mask) and the side channels, for sample [i] when i >= 0. */
    private fun fillSample(i: Int) {
        val s = min(lengthM, (i + 0.5) * step)
        var d = baseDensity(s)
        var cz = 0.0
        var cp = 0.0
        var bar = -1
        var or = 0.0
        var pk = 0.0
        for (t in towns) {
            if (s < t.sIn - 120 || s > t.sOut + 120) continue
            val f = min(ramp(s, t.sIn - 120, t.sIn + 30), 1 - ramp(s, t.sOut - 30, t.sOut + 120))
            d = max(d, townDensity(t) * f)
        }
        for (c in climbs) {
            val cd = climbDensity(c, s)
            if (cd > d) d = cd
            if (s >= c.footS && s <= c.summitS + 300) cz = max(cz, min(1.0, cd * 1.6))
            // Campers on the high meadows of the big climbs: the last 8 km up to a little past the summit.
            val r = c.summitS - s
            if (r in -600.0..8000.0 && c.factor >= 0.8) cp = max(cp, (if (c.factor >= 0.95) 0.55 else 0.35) * (1 - ramp(-r, 300.0, 600.0)))
            // King of the mountains: polka barriers +-80 m, polka fans around the summit.
            if (abs(r) <= 80) { bar = Barriers.POLKA; d = max(d, 0.6 + 0.3 * c.factor) }
            if (abs(r) <= 400) pk = max(pk, 0.22 * (1 - abs(r) / 400))
        }
        for (h in hairpins) {
            val dd = abs(s - h.s)
            if (dd < 45) d = max(d, min(1.0, d + (if (cz > 0.2) 0.25 else 0.12) * (1 - dd / 45)))
        }
        for (z in fanZones) {
            val dd = abs(s - z.s)
            if (dd < z.radiusM + 100) {
                val f = 1 - ramp(dd, z.radiusM, z.radiusM + 100)
                d = max(d, f)
                if (z.kind == FanKind.DUTCH) or = max(or, 0.85 * f)
            }
        }
        // Finish: the last 10 km fill up, barriers over the last 1 km (summit) or 3 km (flat).
        val r = finishS - s
        if (r >= -5) {
            val fd = when {
                r <= 1000 -> 0.9 + 0.1 * (1 - r / 1000)
                r <= 3000 -> 0.62 + 0.28 * (3000 - r) / 2000
                r <= 10000 -> 0.12 + 0.5 * (10000 - r) / 7000
                else -> 0.0
            }
            d = max(d, fd)
            val barrierLen = if (mountainFinish) 1000.0 else 3000.0
            if (r <= barrierLen) bar = if (r <= 300) Barriers.FINISH_LINE else Barriers.FINISH
        }
        for (sp in sprints) {
            val dd = abs(s - sp.s)
            if (dd < 260) d = max(d, if (dd < 130) 0.78 else 0.78 - 0.58 * (dd - 130) / 130)
            if (dd <= 150 && bar < 0) bar = Barriers.SPRINT
        }
        if (startS != null && s < 650) {
            d = max(d, if (s < 300) 0.72 else 0.72 * (1 - (s - 300) / 350))
            if (s < 300 && bar < 0) bar = Barriers.START
        }
        // Basque country: the orange of its fans.
        if (region == RegionStyle.PYRENEES || region == RegionStyle.SOUTH_WEST) or = max(or, 0.12 * cz)
        val f = path.flags[min(i, n - 1)].toInt()
        val masked = (f and (PathFlags.TUNNEL or PathFlags.BRIDGE)) != 0
        density[i] = if (masked) 0f else d.coerceIn(0.0, 1.0).toFloat()
        climbZone[i] = cz.toFloat()
        camp[i] = if (masked) 0f else cp.toFloat()
        barrier[i] = if (masked) -1 else bar.toByte()
        orange[i] = or.toFloat()
        polka[i] = pk.toFloat()
    }

    /** Density at route distance [s], linear between sample centres. */
    fun densityAt(s: Double): Double {
        val f = (s / step - 0.5).coerceIn(0.0, (n - 1).toDouble())
        val i = min(f.toInt(), n - 2).coerceAtLeast(0)
        val t = (f - i).coerceIn(0.0, 1.0)
        return density[i] + (density[i + 1] - density[i]) * t
    }

    fun townAt(s: Double): Town? = towns.firstOrNull { s >= it.sIn && s <= it.sOut }

    /** True where W1's road builder draws kerbs and pavements at [s] (nearest sample, as GroundSampler). */
    fun kerbAt(s: Double): Boolean = kerbSamples[kotlin.math.round(s / step).toInt().coerceIn(0, n - 1)]

    /**
     * How deep the crowd may stand beyond the road edge at [s]: on the pavement in kerb towns, a
     * narrow strip along the houses elsewhere in towns, a few rows in the open.
     */
    fun crowdMaxDepth(s: Double): Double = when {
        kerbAt(s) -> RoadProfile.KERB_W + RoadProfile.PAVEMENT_W - 0.1
        townAt(s) == null -> 2.75
        else -> 1.15
    }

    /**
     * Outer edge of the spectators' band at route distance [s], in metres from the centre line (the
     * wider of both sides), or 0 where the crowd is sparse (density <= [BAND_MIN_DENSITY]: a single
     * row of a few people, which stands in the first metre of the verge). It is the room the rows
     * RangeBuilder.crowd fills there can take (the same front offsets, row spacing, depth limit and
     * clear lane) plus a person's reach and a margin; W2's scenery keeps trees, bushes, walls and
     * guard rails out of it. Deterministic: depends only on the plan.
     */
    fun crowdBand(s: Double): Double {
        val i = (s / step).toInt().coerceIn(0, n - 1)
        var band = 0.0
        // Neighbouring samples too: a sample's spectators stand all along it, and bends shift them.
        for (j in max(0, i - 1)..min(n - 1, i + 1)) {
            val d = density[j].toDouble()
            if (d <= BAND_MIN_DENSITY) continue
            val hw = max(path.width[j], path.width[min(n - 1, j + 1)]) / 2.0
            val front = if (barrier[j] >= 0) RangeBuilder.FRONT_E_BARRIER else RangeBuilder.FRONT_E
            // The right side starts behind the clear lane: take it for both sides (the band is the wider one).
            val e0 = max(front, TourDressing.laneMinE(hw))
            val rows = min(RangeBuilder.ROWS, kotlin.math.ceil(RangeBuilder.PEOPLE_ROWS_AT_FULL * d).toInt())
            val outer = min(crowdMaxDepth(j * step), e0 + (rows - 1) * RangeBuilder.ROW_GAP + RangeBuilder.ROW_JITTER / 2)
            band = max(band, hw + outer + TourDressing.PERSON_RADIUS_M + BAND_MARGIN_M)
        }
        return band
    }

    /**
     * Outer edge of where campers, tents and their chairs may stand at [s] (metres from the centre
     * line), or 0 outside the camp stretches of the big climbs and in towns. Camps only go on open
     * ground (never on TREE land cover), so W2 needs to keep this clear only off the forest.
     */
    fun campBand(s: Double): Double {
        val i = (s / step).toInt().coerceIn(0, n - 1)
        if (townAt(s) != null) return 0.0
        // A slot's camp may sit up to CAMP_EVERY - 1 samples later and reaches ~6 m along the road.
        for (j in max(0, i - RangeBuilder.CAMP_EVERY - 2)..min(n - 1, i + 2)) {
            if (camp[j] > 0f) return max(path.width[i], path.width[min(n - 1, i + 1)]) / 2.0 + RangeBuilder.CAMP_REACH_M
        }
        return 0.0
    }

    companion object {
        /** Named fan spots: the church of Huez at hairpin 7 of Alpe d'Huez ("Dutch corner"). */
        val KNOWN_FAN_SPOTS = listOf(Spot(FanKind.DUTCH, 45.077443, 6.053177, 130.0))

        /** Below this density the crowd is a few people in the first row: no band is reserved. */
        const val BAND_MIN_DENSITY = 0.05
        /** Extra room around the band: placement along averaged normals in bends, trunks' radius. */
        const val BAND_MARGIN_M = 0.15

        private val plans = ArrayList<Pair<java.lang.ref.WeakReference<SceneContext>, RacePlan>>()

        /**
         * The plan of a scene, built once and shared by TourDressing and W2's scenery generator (so
         * the crowd band they agree on comes from one computation). Keyed by identity; a few scenes.
         */
        fun of(ctx: SceneContext): RacePlan = synchronized(plans) {
            plans.removeAll { it.first.get() == null }
            plans.firstOrNull { it.first.get() === ctx }?.second
                ?: RacePlan(ctx).also {
                    plans += java.lang.ref.WeakReference(ctx) to it
                    if (plans.size > 4) plans.removeAt(0)
                }
        }

        /** [crowdBand] of the scene's plan: the spectators' band's outer edge from the centre line, 0 = none. */
        fun crowdBandM(ctx: SceneContext, s: Double): Double = of(ctx).crowdBand(s)

        /** [campBand] of the scene's plan. */
        fun campBandM(ctx: SceneContext, s: Double): Double = of(ctx).campBand(s)
    }

    data class Spot(val kind: FanKind, val lat: Double, val lon: Double, val radiusM: Double)
}

/** Barrier zones and their cover styles ([Atlas.cover] indices are picked per zone in the placement). */
internal object Barriers {
    const val FINISH = 0
    const val FINISH_LINE = 1
    const val SPRINT = 2
    const val POLKA = 3
    const val START = 4
}

/**
 * Spatial index of the road's centre line in 25 m cells: nearest road point, and the clearance of a
 * point from every road leg (a spectator must not stand on the asphalt of the leg above a hairpin).
 */
internal class PathGrid(private val path: RoutePath) {
    private val cell = 25.0
    private val map = HashMap<Long, IntArray>()
    private val maxHalfWidth: Double

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        var mh = 0.0
        for (i in 0 until path.size - 1) {
            val hw = path.width[i] / 2.0
            mh = max(mh, hw)
            val x0 = min(path.x[i], path.x[i + 1]) - hw; val x1 = max(path.x[i], path.x[i + 1]) + hw
            val y0 = min(path.y[i], path.y[i + 1]) - hw; val y1 = max(path.y[i], path.y[i + 1]) + hw
            for (cx in floor(x0 / cell).toInt()..floor(x1 / cell).toInt()) for (cy in floor(y0 / cell).toInt()..floor(y1 / cell).toInt()) {
                tmp.getOrPut(key(cx, cy)) { ArrayList(4) }.add(i)
            }
        }
        maxHalfWidth = mh
        for ((kk, v) in tmp) map[kk] = v.toIntArray()
    }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    private inline fun segmentsNear(x: Double, y: Double, r: Double, f: (Int) -> Unit) {
        // A segment spanning several cells is visited more than once: harmless for minima, and cheaper
        // than a set per query (this runs for every placement slot).
        for (cx in floor((x - r) / cell).toInt()..floor((x + r) / cell).toInt()) for (cy in floor((y - r) / cell).toInt()..floor((y + r) / cell).toInt()) {
            val a = map[key(cx, cy)] ?: continue
            for (i in a) f(i)
        }
    }

    /** Distance from (x, y) to segment i and the parameter along it. */
    fun segDist(i: Int, x: Double, y: Double): Pair<Double, Double> {
        val ax = path.x[i]; val ay = path.y[i]
        val vx = path.x[i + 1] - ax; val vy = path.y[i + 1] - ay
        val l2 = vx * vx + vy * vy
        val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
        return hypot(ax + vx * t - x, ay + vy * t - y) to t
    }

    /** Smallest distance from (x, y) to the edge of any road leg (negative = on the asphalt). */
    fun clearance(x: Double, y: Double, searchM: Double = 12.0): Double {
        var best = searchM
        segmentsNear(x, y, searchM) { i ->
            val (d, _) = segDist(i, x, y)
            val hw = path.width[i] / 2.0
            if (d - hw < best) best = d - hw
        }
        return best
    }

    /** Route distance and lateral distance of the road point nearest to (x, y) within [r], or null. */
    fun nearest(x: Double, y: Double, r: Double): Pair<Double, Double>? {
        var best = Double.MAX_VALUE
        var bs = 0.0
        segmentsNear(x, y, r) { i ->
            val (d, t) = segDist(i, x, y)
            if (d < best) { best = d; bs = path.distanceOf(i) + (path.distanceOf(i + 1) - path.distanceOf(i)) * t }
        }
        return if (best <= r) bs to best else null
    }

    /** Signed lateral offset of (x, y) from the centre line near sample [hint] (positive = left of travel). */
    fun lateral(x: Double, y: Double, hint: Int, window: Int = 6): Pair<Double, Double> {
        var best = Double.MAX_VALUE
        var bs = 0.0
        var sign = 1.0
        for (i in max(0, hint - window)..min(path.size - 2, hint + window)) {
            val (d, t) = segDist(i, x, y)
            if (d < best) {
                best = d
                bs = path.distanceOf(i) + (path.distanceOf(i + 1) - path.distanceOf(i)) * t
                val vx = path.x[i + 1] - path.x[i]; val vy = path.y[i + 1] - path.y[i]
                sign = if (vx * (y - path.y[i]) - vy * (x - path.x[i]) >= 0) 1.0 else -1.0
            }
        }
        return bs to best * sign
    }

    @Suppress("unused")
    private fun len(x: Double, y: Double) = sqrt(x * x + y * y)
}

/** Deterministic hashing and 1D value noise: results depend only on their inputs, never on call order. */
internal object Noise {
    fun hash(a: Int, b: Int, c: Int = 0, d: Int = 0, e: Int = 0): Int {
        var h = a * -0x61c88647 + b
        h = (h xor (h ushr 15)) * -0x7a143595
        h += c * 0x27d4eb2d
        h = (h xor (h ushr 13)) * -0x3d4d51cb
        h += d * 0x165667b1
        h = (h xor (h ushr 16)) * -0x61c88647
        h += e * 0x2545F491
        h = (h xor (h ushr 15)) * -0x7a143595
        return h xor (h ushr 16)
    }

    /** Uniform in [0, 1). */
    fun unit(a: Int, b: Int, c: Int = 0, d: Int = 0, e: Int = 0): Double = (hash(a, b, c, d, e) ushr 8) / 16777216.0

    /** Smooth value noise in [0, 1] over x (lattice spacing 1). */
    fun value(x: Double, seed: Int): Double {
        val i = floor(x).toInt()
        val f = x - i
        val a = unit(seed, i)
        val b = unit(seed, i + 1)
        val t = f * f * (3 - 2 * f)
        return a + (b - a) * t
    }
}
