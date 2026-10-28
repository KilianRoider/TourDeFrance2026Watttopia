package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.LandCover
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.geo.TownStretch
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.SceneContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Things along the whole route that need a view beyond one tile (warning signs before bends,
 * the kilometre count), computed once per scene from the path.
 */
internal class RouteFeatures(ctx: SceneContext) {
    val path: RoutePath = ctx.path
    val towns: List<TownStretch> = ctx.places.towns.sortedBy { it.sIn }

    /** Heading (rad, CCW from east) at each sample, from its neighbours. */
    val heading = DoubleArray(path.size) { i ->
        val a = max(0, i - 1)
        val b = min(path.size - 1, i + 1)
        atan2(path.y[b] - path.y[a], path.x[b] - path.x[a])
    }

    /** Warning signs: distance along the route and the sign prototype. */
    val warnings: List<Pair<Double, String>>

    /** Bends sharp enough for J4 chevron boards: (s at the apex, turn in rad, + = left). */
    val chevronBends: List<Pair<Double, Double>>

    init {
        val step = 10.0
        val n = (path.lengthM / step).toInt()
        val turn = DoubleArray(n + 1) { k -> turnAt(k * step, 25.0) }
        // Clusters of sharp bends.
        val bends = ArrayList<Triple<Double, Double, Double>>() // start, apex s, total turn
        var k = 0
        while (k <= n) {
            if (abs(turn[k]) > 50 * DEG) {
                val sign = if (turn[k] > 0) 1 else -1
                var e = k
                var best = k
                while (e + 1 <= n && turn[e + 1] * sign > 30 * DEG) { e++; if (abs(turn[e]) > abs(turn[best])) best = e }
                bends += Triple(k * step, best * step, turnAt(best * step, 40.0))
                k = e + 1
            } else k++
        }
        val warn = ArrayList<Pair<Double, String>>()
        var lastWarn = -1e9
        for ((idx, b) in bends.withIndex()) {
            val start = b.first
            if (start - lastWarn < 400 || start < 150) continue
            if (inTown(start - 110)) continue
            // A series: another sharp bend within 250 m after this one.
            val series = idx + 1 < bends.size && bends[idx + 1].first - start < 250
            val left = b.third > 0
            val id = when {
                series && left -> "scn_sign_bends_l"
                series -> "scn_sign_bends_r"
                left -> "scn_sign_bend_l"
                else -> "scn_sign_bend_r"
            }
            warn += (start - 110) to id
            lastWarn = start
        }
        warnings = warn
        val chev = ArrayList<Pair<Double, Double>>()
        for (b in bends) if (abs(b.third) > 75 * DEG) chev += b.second to b.third
        for (h in ctx.meta.hairpins) if (chev.none { abs(it.first - h.s) < 40 }) chev += h.s to turnAt(h.s, 40.0)
        chevronBends = chev.sortedBy { it.first }
    }

    fun turnAt(s: Double, half: Double): Double {
        val a = idx(s - half)
        val b = idx(s + half)
        return angleDiff(heading[a], heading[b])
    }

    fun idx(s: Double): Int = (s / path.stepM).toInt().coerceIn(0, path.size - 1)

    fun inTown(s: Double): Boolean = towns.any { s >= it.sIn && s <= it.sOut }

    fun townAt(s: Double): TownStretch? = towns.firstOrNull { s >= it.sIn && s <= it.sOut }

    /** Mean gradient over the next [len] metres. */
    fun gradeAhead(s: Double, len: Double): Double = (path.elevationAt(s + len) - path.elevationAt(s)) / len

    companion object {
        const val DEG = PI / 180.0
        fun angleDiff(a: Double, b: Double): Double {
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return d
        }
    }
}

/**
 * Street furniture and roadside structures of one tile: lamps, delineators, bornes, snow poles,
 * chevrons, warning and speed signs, town entry/exit signs (meshes with a per-town material),
 * guard rails and parapets on the valley side, walls / fences / hedges along fields.
 *
 * Side convention: +1 = left of the direction of travel, -1 = right. French furniture stands on
 * the right for the rider; the route is one-directional, so the left side gets only what both
 * directions share (delineators, snow poles, guard rails).
 */
internal class Roadside(private val j: TileJob) {
    private val f = j.reg.features(j.ctx)
    private val path = j.ctx.path
    private val p = j.profile
    private val tm = TileMesh(j.ox, j.oy, j.anchorY)

    private val sMin: Double
    private val sMax: Double

    init {
        val a = j.route.firstSample
        val b = j.route.lastSample
        sMin = if (b >= a) path.distanceOf(a) else 0.0
        sMax = if (b >= a) path.distanceOf(b) else -1.0
    }

    fun run() {
        if (sMax < sMin) return
        repeatEvery(50.0, 0.0) { s -> delineators(s) }
        repeatEvery(25.0, 0.0) { s -> snowPoles(s) }
        repeatEvery(1000.0, 0.0) { s -> borne(s) }
        townFurniture()
        warningSigns()
        chevrons()
        GuardRails(j, tm).run()
        fieldEdges()
    }

    private inline fun repeatEvery(step: Double, phase: Double, fn: (Double) -> Unit) {
        var s = floor((sMin - phase) / step) * step + phase
        if (s < sMin) s += step
        while (s <= sMax) { fn(s); s += step }
    }

    private class Spot(val x: Double, val y: Double, val dirX: Double, val dirY: Double, val nx: Double, val ny: Double, val hw: Double, val pose: app.rollentrainer.core.geo.PathPose)

    /** A point [off] metres beyond the road edge on [side], or null if outside the tile / in a tunnel. */
    private fun spot(s: Double, side: Int, off: Double, allowBridge: Boolean = false): Spot? {
        if (s < 0 || s > path.lengthM) return null
        val pose = path.poseAt(s)
        if (pose.inTunnel || (!allowBridge && pose.onBridge)) return null
        val nx = -pose.dirY * side
        val ny = pose.dirX * side
        val hw = pose.widthM / 2
        val x = pose.x + nx * (hw + off)
        val y = pose.y + ny * (hw + off)
        if (!j.inTile(x, y)) return null
        return Spot(x, y, pose.dirX, pose.dirY, nx, ny, hw, pose)
    }

    private fun clearAt(sp: Spot, r: Double = 0.3): Boolean {
        // Not on another leg of the route (hairpins), side roads, water, buildings.
        val h = j.routeHit(sp.x, sp.y) ?: return false
        if (h.beyondEdge < 0.25) return false
        if (j.roads.edgeDistance(sp.x, sp.y) < r) return false
        if (j.water.get(sp.x, sp.y)) return false
        return true
    }

    private fun z(sp: Spot): Double = j.groundAt(sp.x, sp.y)

    private fun rnd(s: Double, k: Long) = Rand(Hash.of(j.ctx.seed.toLong(), (s * 4).toLong(), k))

    private fun ruralRoad(pose: app.rollentrainer.core.geo.PathPose) = pose.roadClass in 2..7

    // ------------------------------------------------------------------ posts

    private fun delineators(s: Double) {
        if (f.inTown(s)) return
        val pose = path.poseAt(s)
        if (!ruralRoad(pose) || pose.z > 1500) return
        for (side in intArrayOf(-1, 1)) {
            val sp = spot(s + (if (side > 0) 6.0 else 0.0), side, 0.75) ?: continue
            if (!clearAt(sp)) continue
            if (j.blocked.get(sp.x, sp.y)) continue
            val r = rnd(s, side.toLong())
            j.out.put(if (side < 0) "scn_delineator_r" else "scn_delineator_l", sp.x, sp.y, z(sp) - 0.05,
                InstanceStore.yawFacing(-sp.dirX, -sp.dirY) + r.sym(0.06f), 1f, 0xFFFFFF, 0f)
        }
    }

    private fun snowPoles(s: Double) {
        if (f.inTown(s)) return
        val pose = path.poseAt(s)
        if (pose.z < 1500 || pose.roadClass > 7) return
        for (side in intArrayOf(-1, 1)) {
            val sp = spot(s + (if (side > 0) 12.5 else 0.0), side, 1.05) ?: continue
            if (!clearAt(sp) || j.blocked.get(sp.x, sp.y)) continue
            val r = rnd(s, 40L + side)
            j.out.put("scn_snowpole", sp.x, sp.y, z(sp) - 0.1, r.angle(), r.range(0.95f, 1.05f), 0xFFFFFF, r.f())
        }
    }

    /**
     * Kilometre markers (PR bornes): red cap on national roads, yellow on departmental roads.
     * [InstanceSet] variant = the kilometre, should the renderer print it (the atlas face says 12).
     */
    private fun borne(s: Double) {
        if (s < 500 || f.inTown(s)) return
        val pose = path.poseAt(s)
        if (!ruralRoad(pose)) return
        val sp = spot(s, -1, 0.6) ?: return
        if (!clearAt(sp) || j.blocked.get(sp.x, sp.y)) return
        val id = if (pose.roadClass <= 3) "scn_borne_red" else "scn_borne_yellow"
        // Faces the road, readable by the rider passing it.
        j.out.put(id, sp.x, sp.y, z(sp) - 0.05, InstanceStore.yawFacing(-sp.nx, -sp.ny), 1f, 0xFFFFFF, 0f, (s / 1000.0).toFloat())
    }

    // ------------------------------------------------------------------ towns

    private fun townFurniture() {
        val paris = j.style == RegionStyle.PARIS
        for (t in f.towns) {
            if (t.sOut < sMin - 50 || t.sIn > sMax + 50) continue
            // Lamps: right side every 32 m; both sides (staggered) in Paris and larger towns.
            val both = paris || (t.population ?: 0) > 3000
            val step = if (paris) 26.0 else 32.0
            var s = floor(t.sIn / step) * step + step
            while (s < t.sOut) {
                for (side in if (both) intArrayOf(-1, 1) else intArrayOf(-1)) {
                    val sp = spot(s + (if (side > 0) step / 2 else 0.0), side, 0.6) ?: continue
                    if (!clearAt(sp, 0.5) || j.blocked.get(sp.x, sp.y)) continue
                    if (!paris && !j.builtUp(sp.x, sp.y)) continue
                    j.out.put(if (paris) "scn_lamp_paris" else "scn_lamp_town", sp.x, sp.y, z(sp) - 0.1,
                        InstanceStore.yawFacing(-sp.nx, -sp.ny), 1f, 0xFFFFFF, 0f)
                }
                s += step
            }
            townSign(t, true)
            townSign(t, false)
            speedSign(t.sIn + 18, "scn_sign_50")
            // Leaving town: the national limit, 80 on two-lane roads since 2018.
            if (!paris) speedSign(t.sOut + 30, "scn_sign_80")
        }
    }

    private fun speedSign(s: Double, id: String) {
        if (s > path.lengthM) return
        val sp = spot(s, -1, 0.9) ?: return
        if (!clearAt(sp, 0.5) || j.blocked.get(sp.x, sp.y)) return
        j.out.put(id, sp.x, sp.y, z(sp) - 0.1, InstanceStore.yawFacing(-sp.dirX, -sp.dirY) - 0.15f, 1f, 0xFFFFFF, 0f)
    }

    /**
     * Town entry (EB10) and exit (EB20) signs: two posts and a panel whose face uses the per-town
     * material "sign_town:<style>:<in|out>:<name>" (the renderer draws the lettering), the back plain.
     */
    private fun townSign(t: TownStretch, entry: Boolean) {
        val s = if (entry) t.sIn else t.sOut
        if (s <= 0 || s >= path.lengthM) return
        var sp = spot(s, -1, 1.0) ?: return
        if (!clearAt(sp, 0.6)) sp = spot(s + 6, -1, 1.0)?.takeIf { clearAt(it, 0.6) } ?: return
        val mat = Mats.townSign(p.signs, entry, t.name)
        val face = j.meshes.builder(mat)
        val metal = j.meshes.builder(Mats.metal)
        val w = 1.3
        val h = w / Mats.TOWN_SIGN_ASPECT
        val z0 = z(sp)
        val y0 = z0 + 1.25
        // Panel faces the approaching rider (normal = -dir), turned 10° towards the road as signs are.
        val a = atan2(-sp.dirY, -sp.dirX) - 10 * RouteFeatures.DEG
        val fx = cos(a); val fy = sin(a)        // facing direction (normal of the face)
        // Right as seen by a viewer in front of the panel (looking along -f): (-fy, fx).
        val rx = -fy; val ry = fx
        val cx = sp.x; val cy = sp.y
        fun pt(u: Double, v: Double, d: Double) = doubleArrayOf(cx + rx * (u - 0.5) * w + fx * d, cy + ry * (u - 0.5) * w + fy * d, y0 + (1 - v) * h)
        val q = arrayOf(pt(0.0, 1.0, 0.0), pt(1.0, 1.0, 0.0), pt(1.0, 0.0, 0.0), pt(0.0, 0.0, 0.0))
        val nF = floatArrayOf(fx.toFloat(), fy.toFloat(), 0f)
        val white = floatArrayOf(1f, 1f, 1f)
        val uv = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
        val i0 = face.vertexCount
        for (k in 0 until 4) tm.vtx(face, q[k][0], q[k][1], q[k][2], nF[0], nF[1], nF[2], uv[k][0], uv[k][1], white)
        face.quad(i0, i0 + 1, i0 + 2, i0 + 3)
        // Back of the panel (galvanised) and the two posts.
        val grey = lin(0x9A9EA2)
        val back = arrayOf(pt(1.0, 1.0, -0.03), pt(0.0, 1.0, -0.03), pt(0.0, 0.0, -0.03), pt(1.0, 0.0, -0.03))
        val i1 = metal.vertexCount
        for (k in 0 until 4) tm.vtx(metal, back[k][0], back[k][1], back[k][2], -nF[0], -nF[1], 0f, 0f, 0f, grey)
        metal.quad(i1, i1 + 1, i1 + 2, i1 + 3)
        for (u in doubleArrayOf(0.2, 0.8)) {
            val b = pt(u, 0.0, -0.06)
            tm.post(metal, b[0], b[1], z0 - 0.2, y0 + h - 0.05, 0.035, grey)
        }
    }

    // ------------------------------------------------------------------ bends

    private fun warningSigns() {
        for ((s, id) in f.warnings) {
            if (s < sMin - 10 || s > sMax + 10) continue
            if (!j.reg.has(id)) continue
            val sp = spot(s, -1, 0.9) ?: continue
            if (!clearAt(sp, 0.5) || j.blocked.get(sp.x, sp.y)) continue
            j.out.put(id, sp.x, sp.y, z(sp) - 0.1, InstanceStore.yawFacing(-sp.dirX, -sp.dirY) - 0.12f, 1f, 0xFFFFFF, 0f)
        }
    }

    /** J4 chevron boards on the outside of hairpins, facing the approaching rider. */
    private fun chevrons() {
        for ((apex, turn) in f.chevronBends) {
            if (apex < sMin - 60 || apex > sMax + 60) continue
            val left = turn > 0
            val outer = if (left) -1 else 1
            val id = if (left) "scn_chevron_l" else "scn_chevron_r"
            val n = if (abs(turn) > 140 * RouteFeatures.DEG) 5 else 3
            for (k in 0 until n) {
                val s = apex + (k - (n - 1) / 2.0) * 7.0
                val sp = spot(s, outer, 1.4, allowBridge = true) ?: continue
                if (!clearAt(sp, 0.4) || j.blocked.get(sp.x, sp.y)) continue
                // Seen across the bend by the rider approaching it: face back along the approach heading.
                val ha = f.heading[f.idx(apex - 35)]
                j.out.put(id, sp.x, sp.y, z(sp) - 0.05, InstanceStore.yawFacing(-cos(ha), -sin(ha)), 1f, 0xFFFFFF, 0f)
            }
        }
    }

    // ------------------------------------------------------------------ fields

    /**
     * Dry-stone walls, wooden fences and hedgerows along the road where it runs past pasture or
     * fields, in stretches of 150 m chosen per region.
     */
    private fun fieldEdges() {
        repeatEvery(150.0, 0.0) { s0 ->
            if (f.inTown(s0) || f.inTown(s0 + 150)) return@repeatEvery
            for (side in intArrayOf(-1, 1)) {
                val h = Hash.of(j.ctx.seed.toLong(), (s0 / 150).toLong(), side.toLong(), 0xED6E)
                val u = Hash.unit(h)
                val pose = path.poseAt(s0 + 75)
                val alpine = pose.z > p.treeline - 200
                val kind = when {
                    u < p.stoneWalls * 0.35f -> 0
                    u < p.stoneWalls * 0.35f + p.bocage * 0.3f && !alpine -> 2
                    u < p.stoneWalls * 0.35f + p.bocage * 0.3f + fenceChance() -> 1
                    else -> -1
                }
                if (kind < 0) continue
                val id = when (kind) { 0 -> "scn_wall_stone"; 1 -> "scn_fence_wood"; else -> "scn_hedge" }
                val step = if (kind == 2) 3.8 else 3.0
                val off = when (kind) { 0 -> 2.6; 1 -> 3.0; else -> 3.6 } + Hash.unit(h ushr 9) * 1.5
                var s = s0
                while (s < s0 + 150) {
                    val sp = spot(s, side, off)
                    s += step
                    if (sp == null) continue
                    val cover = j.landCover(sp.x, sp.y)
                    if (cover != LandCover.GRASS && cover != LandCover.CROP) continue
                    if (!clearAt(sp, 1.6) || j.blocked.get(sp.x, sp.y)) continue
                    // Walls, fences and hedges keep out of the race crowd's band (W3).
                    if (j.inCrowdBand(sp.x, sp.y, 0.3)) continue
                    val hit = j.routeHit(sp.x, sp.y) ?: continue
                    if (hit.beyondEdge < off - 1.0) continue // the road bends towards it
                    if (j.ground.slope(sp.x, sp.y) > 0.6) continue
                    val r = Rand(Hash.of(h, (s * 3).toLong()))
                    j.out.put(id, sp.x, sp.y, j.ground.at(sp.x, sp.y, hit) - 0.08, InstanceStore.yawAlong(sp.dirX, sp.dirY) + r.sym(0.03f),
                        r.range(0.95f, 1.06f), InstanceStore.tint(r, 0.1f, 0.03f), r.f())
                }
            }
        }
    }

    private fun fenceChance(): Float = when (j.style) {
        RegionStyle.ALPS, RegionStyle.JURA_VOSGES -> 0.18f
        RegionStyle.MASSIF_CENTRAL, RegionStyle.PYRENEES -> 0.1f
        else -> 0.04f
    }

}

/** Vertex helpers for tile meshes: pack coordinates (x east, y north, z up) to the chunk's GL frame. */
internal class TileMesh(private val ox: Double, private val oy: Double, private val az: Double) {
    fun vtx(mb: MeshBuilder, x: Double, y: Double, z: Double, nx: Float, ny: Float, nz: Float, u: Float, v: Float, c: FloatArray, extra: Float = 0f): Int =
        mb.vertex((x - ox).toFloat(), (z - az).toFloat(), (-(y - oy)).toFloat(), nx, nz, -ny, u, v, c[0], c[1], c[2], extra)

    /** A quad given in counter-clockwise order seen from its front, flat normal (pack frame). */
    fun quad(mb: MeshBuilder, p: Array<DoubleArray>, n: FloatArray, uv: Array<FloatArray>, c: FloatArray) {
        val i = mb.vertexCount
        for (k in 0 until 4) vtx(mb, p[k][0], p[k][1], p[k][2], n[0], n[1], n[2], uv[k][0], uv[k][1], c)
        mb.quad(i, i + 1, i + 2, i + 3)
    }

    /** A square post (4 sides, no cap) from z0 to z1. */
    fun post(mb: MeshBuilder, x: Double, y: Double, z0: Double, z1: Double, r: Double, c: FloatArray) {
        val cs = doubleArrayOf(1.0, 1.0, -1.0, 1.0, -1.0, -1.0, 1.0, -1.0)
        for (k in 0 until 4) {
            val ax = x + cs[k * 2] * r; val ay = y + cs[k * 2 + 1] * r
            val k2 = (k + 1) % 4
            val bx = x + cs[k2 * 2] * r; val by = y + cs[k2 * 2 + 1] * r
            // CCW ring seen from above: the outward normal is to the right of a→b, (dy, -dx).
            val l = hypot(bx - ax, by - ay).coerceAtLeast(1e-9)
            val n = floatArrayOf(((by - ay) / l).toFloat(), ((ax - bx) / l).toFloat(), 0f)
            quad(mb, arrayOf(doubleArrayOf(ax, ay, z0), doubleArrayOf(bx, by, z0), doubleArrayOf(bx, by, z1), doubleArrayOf(ax, ay, z1)), n,
                arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f)), c)
        }
    }
}
