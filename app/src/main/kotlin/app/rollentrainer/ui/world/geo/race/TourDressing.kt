package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.geo.GeoLayer
import app.rollentrainer.core.geo.LandCover
import app.rollentrainer.core.geo.LandCoverTile
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.TileKey
import app.rollentrainer.ui.world.geo.InstanceSet
import app.rollentrainer.ui.world.geo.Kit
import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.RaceDressing
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.RiderModelSet
import app.rollentrainer.ui.world.geo.SceneContext
import app.rollentrainer.ui.world.geo.SceneryChunk
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Tour de France atmosphere of the geodata world (work package W3): crowds that follow the real
 * race (climbs, towns, sprints, finish, hairpins, the Dutch corner), barriers, banners, arches, campers,
 * flags, road writing, and the rider models and kits.
 *
 * [pack] is optional: with it the campers avoid forest, water and built-up land by the land cover,
 * and spectators, barriers, camps and far cards keep out of the building footprints W2 builds;
 * without it they rely on the terrain alone. Thread-safe: the plan is built once per scene context
 * (RacePlan.of, shared with W2's scenery, which keeps the crowd band clear), ranges only read it.
 */
class TourDressing(pack: GeoPack? = null) : RaceDressing {
    private val land = pack?.let { LandLookup(it) }
    private val buildings = pack?.let { BuildingLookup(it) }
    private val protos: List<Prototype> by lazy { People.prototypes() + Props.prototypes() + FarCrowd.prototypes() }

    /** The scene's plan, shared with W2's scenery (RacePlan.of), which keeps the crowd band clear. */
    internal fun plan(ctx: SceneContext): RacePlan = RacePlan.of(ctx)

    override fun prototypes(ctx: SceneContext): List<Prototype> = protos

    override fun range(ctx: SceneContext, fromM: Double, toM: Double, heights: (x: Double, y: Double) -> Double?): SceneryChunk =
        RangeBuilder(plan(ctx), fromM, toM, heights, land, buildings).build()

    override fun crowdDensityAt(ctx: SceneContext, s: Double): Double = plan(ctx).densityAt(s)

    override val kits: List<Kit> get() = Kits.all

    override fun riderPrototypes(): RiderModelSet = RiderModelSet(Riders.seated.lods, Riders.standing)

    companion object {
        /** Half width of the lane kept free of spectators beside the rider's line, metres (brief: >= 1.5). */
        const val CLEAR_LANE_M = 1.5
        /** A spectator's reach from the instance position (shoulders, arms). */
        const val PERSON_RADIUS_M = 0.35

        /** The player's default line, metres left of the centre (GeoWorldRenderer.defaultLane): right of centre. */
        fun riderLine(roadWidthM: Double): Double = -min(roadWidthM * 0.22, 1.4)

        /**
         * Least distance from the right road edge (half width [hw]) for a spectator's centre so that the
         * lane beside the player's line stays free: CLEAR_LANE_M of space plus the person's own radius.
         */
        fun laneMinE(hw: Double) = -riderLine(hw * 2) + CLEAR_LANE_M + PERSON_RADIUS_M - hw + RangeBuilder.LANE_MARGIN
    }
}

/** Land cover class at a point from the pack's finest LANDCOVER tiles (small LRU cache). */
internal class LandLookup(private val pack: GeoPack) {
    private val lod = pack.meta.lods.minOf { it.lod }
    private val tileM = pack.lod(lod).tileM
    private val cache = object : LinkedHashMap<TileKey, LandCoverTile?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, LandCoverTile?>?) = size > 24
    }

    fun classAt(x: Double, y: Double): Int {
        val key = TileKey(lod, floor(x / tileM).toInt(), floor(y / tileM).toInt())
        val tile = synchronized(cache) {
            if (cache.containsKey(key)) cache[key] else (if (pack.has(GeoLayer.LANDCOVER, key)) pack.landCover(key) else null).also { cache[key] = it }
        }
        return tile?.classAt(x, y) ?: LandCover.NONE
    }
}

/** A growable float list for instance data. */
internal class FloatList(initial: Int = 64) {
    var data = FloatArray(initial)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = v
    }
}

/**
 * Builds the dressing of one path range. Every random choice hashes the sample index (and slot), never
 * a running generator, so a range gives the same result however the route is split into ranges.
 */
internal class RangeBuilder(
    private val plan: RacePlan,
    private val fromM: Double,
    private val toM: Double,
    private val heights: (Double, Double) -> Double?,
    private val land: LandLookup?,
    private val buildings: BuildingLookup?,
) {
    private val path = plan.path
    private val step = plan.step
    private val n = plan.n
    private val seed = plan.seed
    private val a0 = (fromM / step).toInt().coerceIn(0, n - 1)
    private val ax = path.x[a0]
    private val ay = path.z[a0]
    private val az = -path.y[a0]
    private val sets = LinkedHashMap<String, FloatList>()
    private val meshes = ChunkMeshes()
    /** Route distances of structure legs: no spectators or barriers right there. */
    private val legs = ArrayList<Double>()

    fun build(): SceneryChunk {
        structures()
        var i = max(0, ceil(fromM / step - 1e-9).toInt())
        while (i < n - 1 && i * step < toM) {
            if (!masked(i)) {
                crowd(i, 0)
                crowd(i, 1)
                paint(i)
            }
            i++
        }
        // Camps by slot, also over masked samples: a slot may find its site on a later sample.
        var slot = ceil(fromM / step - 1e-9).toInt().coerceAtLeast(0)
        slot = ((slot + CAMP_EVERY - 1) / CAMP_EVERY) * CAMP_EVERY
        while (slot < n - 1 && slot * step < toM) {
            campAt(slot, 0)?.let { emitCamp(it) }
            campAt(slot, 1)?.let { emitCamp(it) }
            slot += CAMP_EVERY
        }
        barriers()
        farCrowd()
        val inst = sets.map { (id, l) -> InstanceSet(id, l.data.copyOf(l.size), l.size / InstanceSet.FLOATS) }
        return SceneryChunk(ax, ay, az, inst, meshes.build())
    }

    // --- geometry helpers ------------------------------------------------------------------------

    private fun masked(i: Int) = (path.flags[i].toInt() and (PathFlags.TUNNEL or PathFlags.BRIDGE)) != 0

    /** Unit direction of travel at sample i (central difference, so normals turn smoothly in bends). */
    private fun dir(i: Int): DoubleArray {
        val p = max(0, i - 1)
        val q = min(n - 1, i + 1)
        val dx = path.x[q] - path.x[p]
        val dy = path.y[q] - path.y[p]
        val l = hypot(dx, dy).coerceAtLeast(1e-9)
        return doubleArrayOf(dx / l, dy / l)
    }

    /** Point at fraction [t] of segment i, [u] metres left of the centre line: (x, y, road z, dx, dy). */
    private fun at(i: Int, t: Double, u: Double): DoubleArray {
        val d0 = dir(i)
        val d1 = dir(min(n - 1, i + 1))
        var dx = d0[0] + (d1[0] - d0[0]) * t
        var dy = d0[1] + (d1[1] - d0[1]) * t
        val l = hypot(dx, dy).coerceAtLeast(1e-9)
        dx /= l; dy /= l
        val j = min(n - 1, i + 1)
        val cx = path.x[i] + (path.x[j] - path.x[i]) * t
        val cy = path.y[i] + (path.y[j] - path.y[i]) * t
        val cz = path.z[i] + (path.z[j] - path.z[i]) * t
        // Left of travel is (-dy, dx).
        return doubleArrayOf(cx - dy * u, cy + dx * u, cz, dx, dy)
    }

    private fun halfWidth(i: Int, t: Double): Double {
        val j = min(n - 1, i + 1)
        return (path.width[i] + (path.width[j] - path.width[i]) * t) / 2.0
    }

    private fun sampleOf(s: Double): Pair<Int, Double> {
        val f = (s / step).coerceIn(0.0, (n - 1).toDouble() - 1e-9)
        val i = min(f.toInt(), n - 2)
        return i to (f - i)
    }

    /**
     * The surface a spectator stands on: the road builder's pavement in kerb towns (0.14-0.18 m above
     * the road, 1.9 m wide), else the rendered ground from [heights] (verge, terrain).
     */

    /** Yaw (contract convention) of an object facing pack direction (fx, fy). */
    private fun yawOf(fx: Double, fy: Double) = atan2(-fx, fy)

    private fun put(id: String, x: Double, y: Double, z: Double, yaw: Double, tint: Float = WHITE, phase: Float = 0f, variant: Float = 0f, scale: Float = 1f) {
        val l = sets.getOrPut(id) { FloatList(256) }
        l.add((x - ax).toFloat()); l.add((z - ay).toFloat()); l.add((-y - az).toFloat())
        l.add(yaw.toFloat()); l.add(scale); l.add(tint); l.add(phase); l.add(variant)
    }

    /**
     * One spectator: the skin-tinted body and the shirt-tinted top of its pose (near and mid LODs),
     * plus the shared far silhouette beyond People.LOD_MID, so all poses cost one far draw.
     */
    private fun putPerson(pose: String, x: Double, y: Double, z: Double, yaw: Double, skin: Int, shirt: Int, phase: Float, energy: Float) {
        val shirtTint = Col.packTintHex(shirt)
        put(pose + "_body", x, y, z, yaw, Col.packTintHex(skin), phase, energy)
        put(pose + "_top", x, y, z, yaw, shirtTint, phase, energy)
        put(People.farFor(pose), x, y, z, yaw, shirtTint, phase, energy)
        // Head accessory: hashed from what makes this person (phase, tints), so it is the same in
        // every range and independent of the order of placement.
        val h = Noise.hash(seed + 91, phase.toRawBits(), skin, shirt)
        val acc = Accessory.pick((h ushr 8) / 16777216.0) ?: return
        val head = People.headOf(pose)
        // The instance origin is the head centre: the person's local offset turned by the yaw
        // (contract: GL x = pack x, GL z = -pack y, rotation as in the object shader).
        val c = cos(yaw); val sn = sin(yaw)
        val hx = x + head.x * c + head.z * sn
        val hy = y + head.x * sn - head.z * c
        val tint = when (acc) {
            Accessory.CAP -> Col.packTintHex(Accessory.CAP_COLOURS[(h and 0xFF) % Accessory.CAP_COLOURS.size])
            Accessory.HAT -> Col.packTintHex(Accessory.HAT_COLOURS[(h and 0xFF) % Accessory.HAT_COLOURS.size])
            else -> WHITE
        }
        put(acc, hx, hy, z + head.y, yaw, tint, phase, energy)
    }

    private fun nearLeg(s: Double, m: Double) = legs.any { abs(it - s) < m }

    // --- spectators ------------------------------------------------------------------------------

    private fun crowd(i: Int, side: Int) {
        val d = plan.density[i].toDouble()
        if (d < 0.002) return
        val sigma = if (side == 0) 1.0 else -1.0
        val s = i * step
        // Segment i has kerbs when sample i is in a kerb town, as in W1's road builder.
        val town = plan.townAt(s)
        val maxDepth = plan.crowdMaxDepth(s)
        val barrier = plan.barrier[i] >= 0
        val hw = path.width[i] / 2.0
        var e0 = if (barrier) FRONT_E_BARRIER else FRONT_E
        // The lane beside the player's line stays free (right of centre, so it matters on the right).
        if (sigma < 0) e0 = max(e0, laneMinE(hw))
        val cz = plan.climbZone[i].toDouble()
        val party = plan.orange[i] > 0.4f || (cz > 0.5 && d > 0.85)
        val zone = when {
            party -> Zone.PARTY
            cz >= 0.35 -> Zone.CLIMB
            town != null || barrier || d >= 0.45 -> Zone.TOWN
            else -> Zone.RURAL
        }
        for (r in 0 until ROWS) {
            val fill = (PEOPLE_ROWS_AT_FULL * d - r).coerceIn(0.0, 1.0)
            if (fill <= 0) break
            for (k in 0 until SLOTS) {
                if (Noise.unit(seed, i, side, r, k) >= fill) continue
                val j1 = Noise.unit(seed + 1, i, side, r, k)
                val j2 = Noise.unit(seed + 2, i, side, r, k)
                val t = (k + 0.5 + (j1 - 0.5) * 0.55) / SLOTS
                val hwt = halfWidth(i, t)
                var e = e0 + r * ROW_GAP + (j2 - 0.5) * (if (r == 0) 0.1 else ROW_JITTER)
                // The jitter and the width varying along the sample must not eat into the clear lane.
                if (sigma < 0) e = max(e, laneMinE(hwt))
                if (e > maxDepth) continue
                val ss = s + t * step
                if (nearLeg(ss, 1.4) && e < 2.9) continue
                if (inCamp(i, side, ss, e)) continue
                var p = at(i, t, sigma * (hwt + e))
                if (sigma < 0 && e < laneMinE(hwt) + 0.8) {
                    // Inside a bend the offsets converge: measure the real distance to the player's line
                    // and step back by what is missing.
                    val miss = LANE_NEED - lineDistance(p[0], p[1], ss)
                    if (miss > 0) {
                        e += miss + 0.02
                        if (e > maxDepth) continue
                        p = at(i, t, sigma * (hwt + e))
                        if (lineDistance(p[0], p[1], ss) < LANE_NEED) continue
                    }
                }
                if (plan.grid.clearance(p[0], p[1]) < 0.12) continue
                // Not in a wall: W2 builds the footprints standing beside the road.
                if (buildings != null && buildings.near(p[0], p[1], 0.25)) continue
                // The renderer's ground: includes kerbs and pavements in towns (RoadProfile).
                val g = heights(p[0], p[1]) ?: continue
                if (e > 1.3) {
                    val qa = at(i, t, sigma * (hwt + e + 0.45))
                    val qb = at(i, t, sigma * (hwt + e - 0.45))
                    val ha = heights(qa[0], qa[1]) ?: continue
                    val hb = heights(qb[0], qb[1]) ?: continue
                    if (abs(ha - hb) > 0.68) continue
                }
                if (land != null) {
                    val c = land.classAt(p[0], p[1])
                    if (c == LandCover.WATER || c == LandCover.WETLAND) continue
                    // In the band W2 keeps free of trees (RacePlan.crowdBand) the rows go into the wood
                    // as on the real climbs; a sparse crowd stays at its edge.
                    if (c == LandCover.TREE && e > 1.5 && d <= RacePlan.BAND_MIN_DENSITY) continue
                    if (c == LandCover.BUILT && town == null && e > 1.0) continue
                }
                person(i, side, r, k, p, g, sigma, zone, d, cz, town != null)
            }
        }
    }

    private fun laneMinE(hw: Double) = TourDressing.laneMinE(hw)

    /**
     * Distance from (x, y) to the player's default line (TourDressing.riderLine, measured along the
     * path pose's normal as the renderer places the rider) near route distance [s].
     */
    private fun lineDistance(x: Double, y: Double, s: Double): Double {
        fun d(sv: Double): Double {
            val q = path.poseAt(sv.coerceIn(0.0, plan.lengthM))
            val l = TourDressing.riderLine(q.widthM)
            return hypot(q.x - q.dirY * l - x, q.y + q.dirX * l - y)
        }
        var best = Double.MAX_VALUE
        var bs = s
        var sv = s - 8.0
        while (sv <= s + 8.0) { val v = d(sv); if (v < best) { best = v; bs = sv }; sv += 1.0 }
        sv = bs - 1.0
        while (sv <= bs + 1.0) { best = min(best, d(sv)); sv += 0.1 }
        return best
    }

    private enum class Zone { PARTY, CLIMB, TOWN, RURAL }

    private fun person(i: Int, side: Int, r: Int, k: Int, p: DoubleArray, g: Double, sigma: Double, zone: Zone, d: Double, cz: Double, inTown: Boolean) {
        val h = { salt: Int -> Noise.unit(seed + salt, i, side, r, k) }
        val dx = p[3]; val dy = p[4]
        // Facing the road, turned a little towards the riders coming up (-travel), with some scatter.
        var fx = sigma * dy - 0.32 * dx
        var fy = -sigma * dx - 0.32 * dy
        var pose = pickPose(zone, h(3), r)
        // A few fans run alongside the riders near the top of the climbs.
        if (zone != Zone.TOWN && r == 0 && cz > 0.5 && d > 0.6 && h(4) < 0.03) {
            pose = "fan_run"; fx = dx; fy = dy
        }
        if (pose == "fan_sit" && (r > 0 || d > 0.6 || inTown)) pose = "fan_stand"
        val yaw = yawOf(fx, fy) + (h(5) - 0.5) * 0.6
        val energy = when (zone) { Zone.PARTY -> 2; Zone.CLIMB -> if (d > 0.7) 2 else 1; Zone.TOWN -> 1; Zone.RURAL -> 0 }.let { if (pose == "fan_sit") 0 else it }
        val u = h(6)
        // W1's CHEER makes a fan jump when fract(phase * 7) >= 0.6: only the party crowd jumps.
        val phase = if (energy >= 2) u else (floor(u * 7) + 0.58 * h(7)) / 7.0
        val skin = People.SKIN_TONES[(h(8) * People.SKIN_TONES.size).toInt()]
        val shirt = shirtColour(i, h(9), h(10))
        putPerson(pose, p[0], p[1], g, yaw, skin, shirt, phase.toFloat(), energy.toFloat())
        if (pose == "fan_sit") put(Props.CHAIR, p[0], p[1], g, yaw)
        if (pose == "fan_flag") put(Props.FLAG_HELD, p[0], p[1], g, yaw, phase = h(12).toFloat(), variant = flagFor(i, h(11)).toFloat())
    }

    private fun pickPose(zone: Zone, u: Double, row: Int): String {
        val w = when (zone) {
            Zone.PARTY -> W_PARTY
            Zone.CLIMB -> W_CLIMB
            Zone.TOWN -> W_TOWN
            Zone.RURAL -> if (row == 0) W_RURAL else W_RURAL_BACK
        }
        var x = u * w.sumOf { it.second }
        for ((id, wt) in w) { x -= wt; if (x <= 0) return id }
        return w.last().first
    }

    private fun shirtColour(i: Int, u: Double, v: Double): Int {
        val or = plan.orange[i]
        val pk = plan.polka[i]
        return when {
            u < or -> ORANGES[(v * ORANGES.size).toInt()]
            u < or + pk -> if (v < 0.55) 0xF4F4F4 else 0xD7141A
            u < or + pk + 0.035 -> 0xF5D000 // the yellow jersey's fans
            else -> People.SHIRTS[(v * People.SHIRTS.size).toInt()]
        }
    }

    private fun flagFor(i: Int, u: Double): Int {
        val w = HashMap(FLAG_BASE)
        when (plan.region) {
            RegionStyle.BRITTANY -> w["BZH"] = 28.0
            RegionStyle.PYRENEES, RegionStyle.SOUTH_WEST -> { w["EUS"] = 16.0; w["OCC"] = 6.0; w["ES"] = 9.0; w["CAT"] = 3.0 }
            RegionStyle.CATALONIA -> { w["CAT"] = 26.0; w["ES"] = 9.0 }
            RegionStyle.ALPS -> { w["SAV"] = 10.0; w["IT"] = 5.0; w["CH"] = 3.0 }
            RegionStyle.MEDITERRANEAN -> w["OCC"] = 8.0
            RegionStyle.NORTH -> { w["VLA"] = 10.0; w["BE"] = 10.0 }
            RegionStyle.JURA_VOSGES -> { w["CH"] = 4.0; w["DE"] = 6.0 }
            else -> {}
        }
        if (plan.orange[i] > 0.4f) { w["NL"] = 140.0; w["ORA"] = 40.0 }
        if (plan.polka[i] > 0.05f) w["KOM"] = 14.0
        var x = u * w.values.sum()
        for (code in Atlas.FLAGS) { val wt = w[code] ?: 0.0; x -= wt; if (x <= 0 && wt > 0) return Atlas.flag(code) }
        return 0
    }

    // --- campers, tents ------------------------------------------------------------------------

    /**
     * A placed camp: centre (x, y), facing (fx, fy, towards the road), base height of the body, and
     * whether it stands on a gravel pad (a lay-by levelling a sloping verge). [s0]..[s1] and [eFront]
     * are the route extent and the lateral distance from the road edge that the crowd keeps clear of.
     */
    private class Camp(
        val slot: Int, val side: Int, val tent: Boolean, val van: Boolean, val halfL: Double, val halfW: Double,
        val x: Double, val y: Double, val fx: Double, val fy: Double, val base: Double, val pad: DoubleArray?,
        val s0: Double, val s1: Double, val eFront: Double,
    ) {
        fun local(lx: Double, lz: Double) = doubleArrayOf(x + fy * lx - fx * lz, y - fx * lx - fy * lz)
    }

    private val campMemo = HashMap<Int, Camp?>()

    /**
     * The camp of slot [slot] (a multiple of [CAMP_EVERY]) on [side], or null. A pure function of the
     * slot, memoised: the crowd asks for the slots around it, including ones in the neighbouring range,
     * so both ranges agree on where a camp stands. Mountain verges are mostly steep, so the slot tries
     * each of its samples in turn and takes the first site that is level enough or can be levelled.
     */
    private fun campAt(slot: Int, side: Int): Camp? {
        val key = slot * 2 + side
        // Not getOrPut: it would recompute the (frequent, costly) null results on every call.
        if (campMemo.containsKey(key)) return campMemo[key]
        val c = findCamp(slot, side)
        campMemo[key] = c
        return c
    }

    private fun findCamp(slot: Int, side: Int): Camp? {
        if (slot < 0 || slot >= n - 1) return null
        val p = plan.camp[slot]
        if (p <= 0f || Noise.unit(seed + 20, slot, side) >= p) return null
        val tent = Noise.unit(seed + 21, slot, side) < 0.18
        val van = Noise.unit(seed + 22, slot, side) < 0.65
        val halfL = if (tent) 1.1 else if (van) Props.VAN_HALF_LEN.toDouble() else Props.CAMPER_HALF_LEN.toDouble()
        val halfW = if (tent) 0.75 else if (van) 1.02 else 1.16
        // Close to the asphalt like on the real climbs, the front row of fans in front of it.
        val e = CAMP_FRONT_E + halfW + (if (tent) 1.5 else 1.4) * Noise.unit(seed + 23, slot, side)
        for (k in 0 until CAMP_EVERY) {
            val j = slot + k
            if (j >= n - 1 || masked(j) || plan.townAt(j * step) != null || nearLeg(j * step, 10.0)) continue
            campSite(slot, j, side, tent, van, halfL, halfW, e)?.let { return it }
        }
        return null
    }

    private fun campSite(slot: Int, j: Int, side: Int, tent: Boolean, van: Boolean, halfL: Double, halfW: Double, e: Double): Camp? {
        val sigma = if (side == 0) 1.0 else -1.0
        val c = at(j, 0.5, sigma * (halfWidth(j, 0.5) + e))
        // Camp frame: facing the road (-z local) = f, local +x = right of facing.
        val fx = sigma * c[4]; val fy = -sigma * c[3]
        fun local(lx: Double, lz: Double) = doubleArrayOf(c[0] + fy * lx - fx * lz, c[1] - fx * lx - fy * lz)
        // Footprint corners, centre, and two points between camp and road (clearance and embankment
        // only: land cover right at the road edge is mostly the road itself).
        val g = DoubleArray(CAMP_PROBES.size)
        for ((k, pr) in CAMP_PROBES.withIndex()) {
            val q = local(pr[0] * halfL, pr[1] * halfW + pr[2])
            if (plan.grid.clearance(q[0], q[1], 15.0) < (if (k < 5) 0.8 else 0.2)) return null
            g[k] = heights(q[0], q[1]) ?: return null
            if (land != null && k < 5) {
                val cl = land.classAt(q[0], q[1])
                if (cl == LandCover.TREE || cl == LandCover.BUILT || cl == LandCover.WATER || cl == LandCover.WETLAND || cl == LandCover.SNOW) return null
            }
        }
        if (buildings != null) {
            // Corners, centre and the long sides' midpoints, with the reach of the awning and chairs.
            for (lx in doubleArrayOf(-1.0, -0.5, 0.0, 0.5, 1.0)) for (lz in doubleArrayOf(-1.0, 1.0)) {
                val q = local(lx * halfL, lz * halfW)
                if (buildings.near(q[0], q[1], 0.8)) return null
            }
        }
        val y0 = min(g[0], g[1])
        val alongFront = abs(g[0] - g[1])
        val along = max(alongFront, abs(g[2] - g[3]))
        val backLo = min(g[2], min(g[3], g[4]))
        val backHi = max(g[2], g[3])
        val frontDrop = y0 - min(g[5], g[6])
        // A tent needs level grass. A camper parks along the road at the road's grade: on a level
        // verge its wheels just sink 10 cm into the grass; on a sloping one it stands on a gravel pad
        // (the lay-bys the real climbs are lined with) whose top is level with the front edge, a
        // little above its low end. A verge rising behind may swallow the back of the body.
        val base: Double
        var pad = false
        if (tent) {
            if (along > 0.3 || backLo < y0 - 0.08 || backHi > y0 + 0.3 || frontDrop > 0.5) return null
            base = min(y0, backLo)
        } else {
            pad = backLo < y0 - 0.12 || alongFront > 0.3
            base = if (pad) y0 + 0.4 * alongFront else y0 - CAMP_SINK
            if (along > 1.0 || backLo < base - 1.3 || backHi > base + 1.4 || frontDrop > 0.8) return null
        }
        // Pad outline (local x from, x to, z front, z back): wider on the +x end, where the chairs are.
        val padBox = if (pad) doubleArrayOf(-halfL - 0.5, halfL + 2.6, -halfW - 0.25, halfW + 0.4) else null
        val xA = padBox?.get(0)?.minus(PAD_RUN) ?: (-halfL - 0.5)
        val xB = padBox?.get(1)?.plus(PAD_RUN) ?: (halfL + 2.4)
        // Local +x runs against travel on the left side (side 0), with it on the right.
        val sx = if (side == 0) -1.0 else 1.0
        val sc = (j + 0.5) * step
        val sA = sc + sx * xA; val sB = sc + sx * xB
        val front = e - halfW - (if (pad) 0.25 + PAD_FRONT_RUN else 0.0)
        return Camp(slot, side, tent, van, halfL, halfW, c[0], c[1], fx, fy, base, padBox, min(sA, sB) - 0.3, max(sA, sB) + 0.3, front)
    }

    /** True if a spectator at route distance [s], [e] metres from the edge, would stand in a camp. */
    private fun inCamp(i: Int, side: Int, s: Double, e: Double): Boolean {
        var slot = ((i - CAMP_EVERY - 3).coerceAtLeast(0) / CAMP_EVERY) * CAMP_EVERY
        while (slot <= i + 3) {
            val c = campAt(slot, side)
            if (c != null && s >= c.s0 && s <= c.s1 && e > c.eFront - 0.75) return true
            slot += CAMP_EVERY
        }
        return false
    }

    private fun emitCamp(c: Camp) {
        val i = c.slot
        val side = c.side
        val tent = c.tent
        val van = c.van
        val halfL = c.halfL
        val halfW = c.halfW
        val lo = c.base
        val yaw = yawOf(c.fx, c.fy)
        val h = { salt: Int -> Noise.unit(seed + salt, i, side) }
        // Accessories stand on the pad where there is one, else on the ground.
        val groundAt = { q: DoubleArray -> if (c.pad != null) lo else heights(q[0], q[1]) }
        c.pad?.let { padMesh(c, it) }
        if (tent) {
            put(Props.TENT, c.x, c.y, lo, yaw, Col.packTintHex(TENTS[(h(24) * TENTS.size).toInt()]))
        } else {
            put(if (van) Props.CAMPER_VAN else Props.CAMPER, c.x, c.y, lo, yaw, if (van) Col.packTintHex(VANS[(h(24) * VANS.size).toInt()]) else WHITE)
            // Somebody on the roof: the best seat on the mountain.
            if (h(25) < 0.3) {
                val q = c.local(if (van) -1.3 else 0.9, 0.0)
                val roof = if (van) Props.VAN_ROOF else Props.CAMPER_ROOF
                val pose = if (h(26) < 0.5) "fan_armsup" else "fan_fist"
                putPerson(pose, q[0], q[1], lo + roof, yaw, People.SKIN_TONES[(h(27) * People.SKIN_TONES.size).toInt()], shirtColour(i, h(29), h(30)), h(28).toFloat() * 0.08f, 1f)
            }
            if (h(31) < 0.65) {
                val q = c.local(-halfL - 0.3, -halfW + 0.1)
                groundAt(q)?.let { put(Props.FLAG_POLE, q[0], q[1], it, yaw, phase = h(33).toFloat(), variant = flagFor(i, h(32)).toFloat()) }
            }
            if (h(34) < 0.45) {
                val q = c.local(halfL + 1.3, -halfW + 1.2)
                groundAt(q)?.let { put(Props.TABLE, q[0], q[1], it, yaw + 0.2) }
            }
        }
        // Their owners in folding chairs beside the camp, facing the road.
        val seats = if (tent) 1 else 1 + (h(35) * 2.2).toInt()
        for (m in 0 until seats) {
            val q = c.local(halfL + 0.6 + m * 0.7, -halfW + 0.15 + 0.2 * h(36 + m))
            val g = groundAt(q) ?: continue
            val yy = yaw + (h(40 + m) - 0.5) * 0.5
            val skin = People.SKIN_TONES[(h(44 + m) * People.SKIN_TONES.size).toInt()]
            val ph = ((floor(h(48 + m) * 7)) + 0.5 * h(52 + m)) / 7.0
            putPerson("fan_sit", q[0], q[1], g, yy, skin, shirtColour(i, h(56 + m), h(60 + m)), ph.toFloat(), 0f)
            put(Props.CHAIR, q[0], q[1], g, yy)
        }
        if (!tent && h(64) < 0.25) {
            val q = c.local(halfL + 1.4, -halfW + 0.7)
            groundAt(q)?.let { put(Props.PARASOL, q[0], q[1], it, yaw, Col.packTintHex(PARASOLS[(h(65) * PARASOLS.size).toInt()])) }
        }
    }

    /**
     * A gravel lay-by under a camp: a level top at the camp's base and four sloping sides running
     * [PAD_RUN] m out (less towards the road) to 25 cm below the ground, so it never leaves a gap.
     */
    private fun padMesh(c: Camp, box: DoubleArray) {
        val m = meshes.props
        val top = Array(4) { DoubleArray(0) }
        val bot = Array(4) { DoubleArray(0) }
        // Corners counter-clockwise seen from above in local (x, z): (x0,z0) (x0,z1) (x1,z1) (x1,z0).
        val xs = doubleArrayOf(box[0], box[0], box[1], box[1])
        val zs = doubleArrayOf(box[2], box[3], box[3], box[2])
        for (k in 0 until 4) {
            val q = c.local(xs[k], zs[k])
            top[k] = doubleArrayOf(q[0], q[1], c.base)
            val ox = if (k < 2) -PAD_RUN else PAD_RUN
            val oz = if (k == 1 || k == 2) PAD_RUN else -PAD_FRONT_RUN
            val b = c.local(xs[k] + ox, zs[k] + oz)
            val gz = heights(b[0], b[1]) ?: (c.base - 1.5)
            bot[k] = doubleArrayOf(b[0], b[1], min(gz, c.base) - 0.25)
        }
        fun gl(p: DoubleArray) = v3(p[0] - ax, p[2] - ay, -p[1] - az)
        val cx = (top[0][0] + top[2][0]) / 2; val cy = (top[0][1] + top[2][1]) / 2
        fun face(p: List<DoubleArray>, color: FloatArray) {
            val a = gl(p[0]); val b = gl(p[1]); val cc = gl(p[2]); val d = gl(p[3])
            var nrm = ((b - a) cross (cc - a)).norm()
            // Outward: away from the pad centre (or up for the top).
            val mid = (a + b + cc + d) * 0.25f
            val out = mid - v3(cx - ax, c.base - ay - 2.0, -cy - az)
            val flip = (nrm dot out) < 0
            if (flip) nrm = -nrm
            m.withColor(color) {
                val i0 = m.vertex(a, nrm); val i1 = m.vertex(b, nrm); val i2 = m.vertex(cc, nrm); val i3 = m.vertex(d, nrm)
                if (flip) m.quad(i0, i3, i2, i1) else m.quad(i0, i1, i2, i3)
            }
        }
        face(listOf(top[0], top[1], top[2], top[3]), PAD_TOP)
        for (k in 0 until 4) {
            val l = (k + 1) % 4
            face(listOf(top[k], bot[k], bot[l], top[l]), PAD_SIDE)
        }
    }

    // --- far crowd cards ----------------------------------------------------------------------------

    /**
     * T's crowd strips every [FarCrowd.WIDTH] m of route (their tiling period) on both sides, where the
     * crowd is at least a thin row; drawn only beyond the 3D spectators' last LOD. Slots are indexed by
     * route distance, so ranges split anywhere agree.
     */
    private fun farCrowd() {
        val w = FarCrowd.WIDTH
        var k = ceil(fromM / w - 0.5 - 1e-9).toLong()
        while (true) {
            val sc = (k + 0.5) * w
            if (sc >= toM || sc >= plan.lengthM - w / 2) break
            if (sc >= fromM && sc >= w / 2) for (side in 0..1) farCard(k, sc, side)
            k++
        }
    }

    private fun farCard(k: Long, sc: Double, side: Int) {
        val d = plan.densityAt(sc)
        if (d < 0.12) return
        val h = { salt: Int -> Noise.unit(seed + salt, k.toInt(), side) }
        // A card is two rows deep: thinner crowds get a card on part of the slots.
        if (h(90) >= min(1.0, d / 0.5)) return
        val (i, t) = sampleOf(sc)
        if (masked(i) || masked(min(n - 1, i + 1)) || nearLeg(sc, 4.0)) return
        val sigma = if (side == 0) 1.0 else -1.0
        val e = if (plan.barrier[i] >= 0) 1.3 else 0.8
        val p = at(i, t, sigma * (halfWidth(i, t) + e))
        if (plan.grid.clearance(p[0], p[1]) < 0.3) return
        if (buildings != null && (buildings.near(p[0], p[1], 0.3) || buildings.near(p[0] + p[3] * w2, p[1] + p[4] * w2, 0.3) || buildings.near(p[0] - p[3] * w2, p[1] - p[4] * w2, 0.3))) return
        // Bottom edge on the lowest of centre and ends: at this distance a sunk end is invisible, a
        // floating one would show sky under the fans.
        var g = heights(p[0], p[1]) ?: return
        for (off in doubleArrayOf(-w2, w2)) {
            val q = doubleArrayOf(p[0] + p[3] * off, p[1] + p[4] * off)
            g = min(g, heights(q[0], q[1]) ?: return)
        }
        val cz = plan.climbZone[i]
        val id = when {
            plan.orange[i] > 0.4f || d > 0.85 -> FarCrowd.FLAGS
            cz >= 0.35f -> FarCrowd.MIXED
            else -> FarCrowd.CALM
        }
        put(id, p[0], p[1], g, yawOf(sigma * p[4], -sigma * p[3]))
    }

    // --- barriers --------------------------------------------------------------------------------

    private fun barriers() {
        val len = Props.BARRIER_LEN.toDouble()
        var b = ceil(fromM / len - 0.5 - 1e-9).toLong()
        while (true) {
            val sb = (b + 0.5) * len
            if (sb >= toM || sb >= plan.lengthM) break
            if (sb >= fromM) barrier(b, sb)
            b++
        }
    }

    private fun barrier(b: Long, sb: Double) {
        val (i, t) = sampleOf(sb)
        val zone = plan.barrier[i].toInt()
        if (zone < 0 || masked(i) || nearLeg(sb, 1.8)) return
        for (side in 0..1) {
            val sigma = if (side == 0) 1.0 else -1.0
            val hw = halfWidth(i, t)
            val p = at(i, t, sigma * (hw + BARRIER_E))
            // Not across a junction or onto another leg: both ends must be off the asphalt.
            val (i0, t0) = sampleOf(sb - len2)
            val (i1, t1) = sampleOf(sb + len2)
            val e0 = at(i0, t0, sigma * (halfWidth(i0, t0) + BARRIER_E))
            val e1 = at(i1, t1, sigma * (halfWidth(i1, t1) + BARRIER_E))
            if (plan.grid.clearance(e0[0], e0[1]) < 0.1 || plan.grid.clearance(e1[0], e1[1]) < 0.1) continue
            // Not where a building stands on or in front of the barrier line (W2's footprints): centre
            // and both ends, with the feet's reach.
            if (buildings != null && (buildings.near(p[0], p[1], 0.45) || buildings.near(e0[0], e0[1], 0.35) || buildings.near(e1[0], e1[1], 0.35))) continue
            val g = heights(p[0], p[1]) ?: continue
            val block = (b / 3).toInt()
            val u = Noise.unit(seed + 70, block, side, (b shr 32).toInt())
            val cover = when (zone) {
                Barriers.FINISH -> when { u < 0.35 -> 0; u < 0.6 -> 6; u < 0.75 -> 7; else -> -1 }
                Barriers.FINISH_LINE -> when { u < 0.4 -> 6; u < 0.75 -> 0; else -> 7 }
                Barriers.SPRINT -> if (u < 0.7) 1 else -1
                Barriers.POLKA -> if (u < 0.85) 2 else -1
                else -> when { u < 0.4 -> 0; u < 0.7 -> 4; else -> 3 }
            }
            if (cover < 0) put(Props.BARRIER_PLAIN, p[0], p[1], g, yawOf(sigma * p[4], -sigma * p[3]))
            else put(Props.BARRIER_COVER, p[0], p[1], g, yawOf(sigma * p[4], -sigma * p[3]), variant = cover.toFloat())
        }
    }

    // --- structures --------------------------------------------------------------------------------

    private fun inRange(s: Double) = s >= fromM && s < toM && s >= 0 && s <= plan.lengthM

    /** Runs [block] in the structure frame at [s]; gives the ground under lateral offsets (left +). */
    private fun structure(s: Double, block: (hw: Float, groundAt: (Double) -> Float) -> Unit) {
        val (i, t) = sampleOf(s)
        if (masked(i)) return
        val c = at(i, t, 0.0)
        val hw = halfWidth(i, t)
        val f = Affine.translate((c[0] - ax).toFloat(), (c[2] - ay).toFloat(), (-c[1] - az).toFloat()) * Affine.rotY(yawOf(c[3], c[4]).toFloat())
        val groundAt = { u: Double ->
            val q = at(i, t, u)
            ((heights(q[0], q[1]) ?: c[2]) - c[2]).toFloat()
        }
        meshes.inFrame(f) { block(hw.toFloat(), groundAt) }
    }

    /**
     * Every structure of the route as (s, builder). Listed for the whole route, not just this range,
     * so spectators and barriers keep clear of a leg standing just across a range boundary and the
     * result does not depend on how the route is split.
     */
    private fun structureList(): List<Pair<Double, () -> Unit>> {
        val out = ArrayList<Pair<Double, () -> Unit>>()
        val fin = plan.finishS
        out += fin to {
            structure(fin) { hw, g -> Structures.gantry(meshes, hw, g(hw + 1.3), g(-(hw + 1.3))) }
            line(fin, 0.72, true)
        }
        for ((m, _) in Atlas.BOARDS) {
            val s = fin - m
            out += s to {
                structure(s) { hw, g ->
                    // Local -x is the left of travel, where the lateral offset u is positive.
                    Structures.board(meshes, m, -(hw + 0.55f), g(hw + 0.55))
                    Structures.board(meshes, m, hw + 0.55f, g(-(hw + 0.55)))
                }
            }
        }
        val fr = fin - 1000
        out += fr to { structure(fr) { hw, g -> Structures.inflatable(meshes, Col.hex(0xD7141A), Atlas.DERNIER_KM, hw, g(hw + 1.0), g(-(hw + 1.0)), true) } }
        for ((km, cell) in Atlas.KM_BANNERS) {
            val s = fin - km * 1000.0
            out += s to { structure(s) { hw, g -> Structures.banner(meshes, cell, hw, g(hw + 0.7), g(-(hw + 0.7))) } }
        }
        for (sp in plan.sprints) out += sp.s to {
            structure(sp.s) { hw, g -> Structures.banner(meshes, Atlas.SPRINT, hw, g(hw + 0.7), g(-(hw + 0.7))) }
            line(sp.s, 0.35, false)
        }
        for (c in plan.climbs) {
            // The KOM banner at the summit, unless the finish gantry stands there.
            if (abs(c.summitS - fin) > 150) out += c.summitS to { structure(c.summitS) { hw, g -> Structures.banner(meshes, Atlas.GPM, hw, g(hw + 0.7), g(-(hw + 0.7))) } }
        }
        plan.startS?.let { s -> out += s to { structure(s) { hw, g -> Structures.inflatable(meshes, Col.hex(0x1B3A8C), Atlas.DEPART, hw, g(hw + 1.0), g(-(hw + 1.0)), false) } } }
        for (t in plan.towns) {
            val s = t.sIn + 40
            if (!t.kerbs || (t.population ?: 0) < 2000) continue
            if (abs(s - fin) < 1200 || plan.sprints.any { abs(it.s - s) < 300 }) continue
            out += s to { structure(s) { hw, g -> Structures.banner(meshes, Atlas.BIENVENUE, hw, g(hw + 0.7), g(-(hw + 0.7)), 4.6f) } }
        }
        return out.filter { it.first >= 0 && it.first <= plan.lengthM }
    }

    private fun structures() {
        for ((s, build) in structureList()) {
            legs += s
            if (inRange(s)) build()
        }
    }

    /** A painted line across the road: white, with the black photo-finish line in the middle. */
    private fun line(s: Double, width: Double, photo: Boolean) {
        val (i, _) = sampleOf(s)
        if (masked(i)) return
        roadStrip(meshes.line, s - width / 2, s + width / 2, null, null, Col.hex(0xF4F4F4), 0.0)
        if (photo) roadStrip(meshes.line, s - 0.04, s + 0.04, null, null, Col.hex(0x0A0A0A), 0.004)
    }

    /**
     * A strip lying on the crowned carriageway between route distances [s0] and [s1], across the whole
     * road or between lateral offsets [u] (left, right); [uv] maps the strip to an atlas cell reading
     * for a rider travelling forward.
     */
    private fun roadStrip(b: MeshBuilder, s0: Double, s1: Double, u: DoubleArray?, uv: Atlas.Rect?, color: FloatArray, lift: Double) {
        val na = 3
        val nc = 6
        val base = b.vertexCount
        for (ja in 0..na) {
            val s = s0 + (s1 - s0) * ja / na
            val (i, t) = sampleOf(s)
            val hw = halfWidth(i, t)
            val uL = u?.get(0) ?: hw
            val uR = u?.get(1) ?: -hw
            for (jc in 0..nc) {
                val uu = uL + (uR - uL) * jc / nc
                val p = at(i, t, uu)
                val z = p[2] + LIFT + 0.025 * max(0.0, hw - abs(uu)) + DECAL_LIFT + lift
                val tu = uv?.let { it.u0 + (it.u1 - it.u0) * jc / nc } ?: 0f
                val tv = uv?.let { it.v1 + (it.v0 - it.v1) * ja / na } ?: 0f
                b.rawVertex(V3((p[0] - ax).toFloat(), (z - ay).toFloat(), (-p[1] - az).toFloat()), V3.Y, tu, tv, color, 0f)
            }
        }
        for (ja in 0 until na) for (jc in 0 until nc) {
            val v = base + ja * (nc + 1) + jc
            b.quad(v, v + 1, v + nc + 2, v + nc + 1)
        }
    }

    // --- road writing ---------------------------------------------------------------------------

    private fun paint(i: Int) {
        val cz = plan.climbZone[i]
        if (cz < 0.35f || plan.barrier[i] >= 0) return
        if (Noise.unit(seed + 80, i) >= 0.11 * cz) return
        val s = (i + 0.5) * step
        if (nearLeg(s, 15.0)) return
        val hw = halfWidth(i, 0.5)
        val room = hw - 1.4
        val centre = if (room > 0) (Noise.unit(seed + 81, i) * 2 - 1) * room else 0.0
        val wHalf = min(1.5, hw - 0.3)
        val word = pickWord(i, Noise.unit(seed + 82, i))
        val c = Noise.unit(seed + 83, i)
        val col = when {
            plan.orange[i] > 0.4f && c < 0.6 -> Col.hex(0xFF7A1A)
            c < 0.6 -> Col.hex(0xF2F2F2)
            c < 0.78 -> Col.hex(0xF5D000)
            c < 0.9 -> Col.hex(0x8FC8F0)
            else -> Col.hex(0xE8463C)
        }
        // Letters stretched along the road (3.2 m), as painted for riders who see them foreshortened.
        roadStrip(meshes.paint, s - 1.6, s + 1.6, doubleArrayOf(centre + wHalf, centre - wHalf), Atlas.paint(Atlas.word(word)), col, 0.0)
    }

    private fun pickWord(i: Int, u: Double): String {
        val w = LinkedHashMap(WORDS)
        if (plan.region == RegionStyle.PYRENEES || plan.region == RegionStyle.SOUTH_WEST) w["AUPA"] = 3.0
        if (plan.orange[i] > 0.4f) { w["HUP"] = 6.0; w["<hearts>"] = 2.0 }
        var x = u * w.values.sum()
        for ((k, wt) in w) { x -= wt; if (x <= 0) return k }
        return "ALLEZ"
    }

    companion object {
        const val SLOTS = 8
        const val ROWS = 5
        const val ROW_GAP = 0.55
        /** Spread of the back rows' offsets (the front row's is smaller). */
        const val ROW_JITTER = 0.22
        /** Front row's offset beyond the road edge, without and with barriers. */
        const val FRONT_E = 0.22
        const val FRONT_E_BARRIER = 0.85
        /** Rows filled at density 1: SLOTS / 5 m * 3.75 rows = 6 spectators per metre and side. */
        const val PEOPLE_ROWS_AT_FULL = 3.75
        const val PEOPLE_PER_M_AT_FULL = SLOTS / 5.0 * PEOPLE_ROWS_AT_FULL
        const val CAMP_EVERY = 6
        /** Half a far crowd card, for its end probes. */
        private const val w2 = FarCrowd.WIDTH / 2
        /** Extra room on the clear lane for bends, where offsets along averaged normals cut inwards. */
        const val LANE_MARGIN = 0.08
        /** Least distance from a spectator's centre to the player's line. */
        const val LANE_NEED = TourDressing.CLEAR_LANE_M + TourDressing.PERSON_RADIUS_M + 0.02
        /** Camp probes as (x / halfL, z / halfW, extra z): four corners, centre, two in front. */
        private val CAMP_PROBES = arrayOf(
            doubleArrayOf(-1.0, -1.0, 0.0), doubleArrayOf(1.0, -1.0, 0.0), doubleArrayOf(-1.0, 1.0, 0.0), doubleArrayOf(1.0, 1.0, 0.0),
            doubleArrayOf(0.0, 0.0, 0.0), doubleArrayOf(-0.3, -1.0, -0.6), doubleArrayOf(0.3, -1.0, -0.6),
        )
        /** Least lateral distance (from the road edge) of a camp's front: room for one row of fans. */
        const val CAMP_FRONT_E = 1.1
        /** How far a camp pad's sides run out to meet the ground (towards the road: less). */
        const val PAD_RUN = 0.9
        const val PAD_FRONT_RUN = 0.35
        val PAD_TOP = Col.hex(0x9C9585)
        val PAD_SIDE = Col.hex(0x857B66)
        /** How far a camper's base sits below its lower front corner (wheels in the grass). */
        const val CAMP_SINK = 0.1
        /**
         * Farthest reach of a camp beyond the road edge: front offset, the widest camper (2 x 1.16 m),
         * its random set-back (up to 1.5 m), the pad's back margin and side run, and a margin.
         */
        const val CAMP_REACH_M = CAMP_FRONT_E + 2 * 1.16 + 1.5 + 0.4 + PAD_RUN + 0.3
        const val BARRIER_E = 0.3
        private const val len2 = 0.95
        const val LIFT = 0.02
        const val DECAL_LIFT = 0.012
        val WHITE = 0xFFFFFF.toFloat()

        val W_PARTY = listOf("fan_armsup" to 4.0, "fan_fist" to 3.0, "fan_clap" to 3.0, "fan_flag" to 1.6, "fan_stand" to 0.6, "fan_phone" to 0.8)
        val W_CLIMB = listOf("fan_armsup" to 2.6, "fan_fist" to 2.6, "fan_clap" to 3.0, "fan_flag" to 1.2, "fan_phone" to 1.5, "fan_stand" to 1.2, "fan_hips" to 0.6)
        val W_TOWN = listOf("fan_clap" to 3.0, "fan_stand" to 2.4, "fan_hips" to 1.4, "fan_phone" to 2.2, "fan_armsup" to 1.0, "fan_fist" to 0.8, "fan_flag" to 0.6)
        val W_RURAL = listOf("fan_stand" to 3.0, "fan_hips" to 2.0, "fan_clap" to 1.5, "fan_phone" to 1.2, "fan_sit" to 1.4, "fan_flag" to 0.5, "fan_armsup" to 0.5)
        val W_RURAL_BACK = listOf("fan_stand" to 3.0, "fan_hips" to 2.0, "fan_clap" to 1.0, "fan_phone" to 1.5, "fan_flag" to 0.4)

        val ORANGES = intArrayOf(0xF26A10, 0xFF7F00, 0xE85D04, 0xFF9A1F)
        // Light body colours only: the tint multiplies the windows too, and a dark van reads as a black box.
        val VANS = intArrayOf(0xEDEDED, 0xEDEDED, 0xF3E9C8, 0xC9CED3, 0x9DB8D6, 0x7FA7D8, 0xE07B70, 0xA9C48F, 0xF0D35A, 0xD9D2C3)
        val TENTS = intArrayOf(0xE07A2E, 0x2E7D32, 0x1F5AA6, 0xC62828, 0xF2C80F, 0x6D6D6D)
        val PARASOLS = intArrayOf(0xD8322E, 0x2F6FB5, 0xF2C80F, 0x2E8B3E, 0xF4F4F4)

        val FLAG_BASE = mapOf(
            "FR" to 30.0, "NL" to 5.0, "BE" to 5.0, "VLA" to 2.0, "DK" to 4.0, "NO" to 3.0, "SI" to 3.0, "CO" to 3.0,
            "ES" to 3.0, "IT" to 2.0, "DE" to 3.0, "GB" to 2.0, "CH" to 1.5, "LU" to 0.6, "AT" to 0.8, "IE" to 0.8,
            "PT" to 0.8, "US" to 1.0, "AU" to 1.0, "ER" to 1.0, "CZ" to 0.8, "PL" to 0.8, "CA" to 0.6, "KOM" to 1.5,
            "YEL" to 1.5, "ALLEZ" to 2.0, "ORA" to 0.4, "BZH" to 1.0, "EUS" to 1.0, "SAV" to 0.6, "OCC" to 0.6, "CAT" to 0.6,
        )
        val WORDS = linkedMapOf(
            "ALLEZ" to 3.0, "VAS-Y" to 1.5, "ALLEZ KILI" to 1.0, "AUPA" to 0.4, "FORZA" to 1.0, "HOP HOP" to 0.8,
            "COURAGE" to 1.0, "BRAVO" to 1.0, "GO GO GO" to 1.0, "ALLEZ ALLEZ" to 1.5, "<hearts>" to 1.0, "<stars>" to 0.7,
            "<chevrons>" to 1.0, "KILI" to 0.8, "HUP" to 0.8, "VAMOS" to 0.8,
        )
    }
}
