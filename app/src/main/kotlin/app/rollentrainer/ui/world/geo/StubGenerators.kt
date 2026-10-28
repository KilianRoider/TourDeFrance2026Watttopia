package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.LandCover
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Placeholder generators so the renderer's whole path (prototypes, LODs, instancing, animations, kit
 * colours, one-off meshes) works before work packages W2 (scenery) and W3 (race dressing) deliver.
 * GeoWorldRenderer uses their implementations as soon as they exist; these stay as the fallback.
 */

private fun hash(a: Int, b: Int, c: Int = 0): Float {
    var h = a * 374761393 + b * 668265263 + c * 1274126177
    h = (h xor (h ushr 13)) * 1274126177
    h = h xor (h ushr 16)
    return (h and 0xFFFFFF) / 16777216f
}

/** Trees on forest land cover and extruded building footprints. */
internal class StubScenery : SceneryGenerator {
    private val bark = SceneMaterial("stub_bark", roughness = 0.9f)
    private val leaves = SceneMaterial("stub_leaves", roughness = 0.85f, anim = Anim.WIND)
    private val walls = SceneMaterial("stub_walls", roughness = 0.9f)

    override fun prototypes(ctx: SceneContext): List<Prototype> {
        fun broadleaf(detail: Boolean): ProtoLod {
            val t = MeshBuilder(); val c = MeshBuilder()
            t.cylinder(0f, 0f, 0f, 4.5f, 0.28f, 0.18f, if (detail) 7 else 4, 0.32f, 0.25f, 0.19f, 0f, 0.3f)
            val seg = if (detail) 10 else 6
            c.lathe(0f, 3.0f, 0f, 8.5f, if (detail) 6 else 3, seg, 0.17f, 0.30f, 0.09f, { 0.4f + 0.6f * it }) { tt -> (sin(tt * PI.toFloat()) * 3.6f + 0.2f) * (1f - 0.25f * tt) }
            return ProtoLod(listOf(bark to t.build(), leaves to c.build()), if (detail) 160f else 2500f)
        }
        fun conifer(detail: Boolean): ProtoLod {
            val t = MeshBuilder(); val c = MeshBuilder()
            t.cylinder(0f, 0f, 0f, 3f, 0.25f, 0.15f, if (detail) 6 else 4, 0.30f, 0.22f, 0.16f)
            val seg = if (detail) 9 else 5
            c.lathe(0f, 1.6f, 0f, 15f, if (detail) 5 else 2, seg, 0.07f, 0.17f, 0.07f, { 0.3f + 0.7f * it }) { tt -> (1f - tt) * 3.1f + 0.05f }
            return ProtoLod(listOf(bark to t.build(), leaves to c.build()), if (detail) 160f else 2500f)
        }
        return listOf(
            Prototype("stub_broadleaf", listOf(broadleaf(true), broadleaf(false)), 5f),
            Prototype("stub_conifer", listOf(conifer(true), conifer(false)), 4f),
        )
    }

    override fun tile(input: SceneryTileInput): SceneryChunk {
        val lc = input.landCover
        val h = input.heights
        val alpine = input.ctx.region == RegionStyle.ALPS || input.ctx.region == RegionStyle.PYRENEES
        val broad = ArrayList<Float>(); val con = ArrayList<Float>()
        if (lc != null) {
            val cells = lc.cells
            val cell = input.sizeM / cells
            for (r in 0 until cells) for (c in 0 until cells) {
                val cls = lc.classes[r * cells + c].toInt() and 0xFF
                if (cls != LandCover.TREE) continue
                // Two trees per 10 m cell, jittered.
                for (k in 0 until 2) {
                    if (hash(input.key.tx * 977 + c, input.key.ty * 613 + r, k) > 0.85f) continue
                    val fx = (c + hash(c, r, k + 11)) * cell
                    val fy = (r + hash(c, r, k + 23)) * cell
                    val x = input.originX + fx
                    val y = input.originY + input.sizeM - fy
                    if (nearRoad(input, x, y, 7.0)) continue
                    val z = h.heightAt(x, y)
                    val list = if (alpine && (z > 1100 || hash(c, r, 5) < 0.6f) || z > 1400) con else broad
                    list += (x - input.originX).toFloat(); list += z.toFloat(); list += (-(y - input.originY)).toFloat()
                    list += hash(c, r, k + 31) * 6.28f
                    list += 0.7f + 0.6f * hash(c, r, k + 41)
                    val g = (0xC0 + (hash(c, r, k + 51) * 63).toInt())
                    list += ((g shl 16) or (0xFF shl 8) or (0xC0 + (hash(c, r, k + 61) * 63).toInt())).toFloat()
                    list += hash(c, r, k + 71)
                    list += 0f
                }
            }
        }
        val sets = ArrayList<InstanceSet>()
        if (broad.isNotEmpty()) sets += InstanceSet("stub_broadleaf", broad.toFloatArray(), broad.size / 8)
        if (con.isNotEmpty()) sets += InstanceSet("stub_conifer", con.toFloatArray(), con.size / 8)
        val meshes = ArrayList<Pair<SceneMaterial, MeshBuffer>>()
        input.buildings?.let { bt ->
            val mb = MeshBuilder(1024)
            for (b in bt.buildings) {
                val ring = b.ring
                val n = ring.size / 2
                if (n < 3) continue
                val y0 = b.baseZ + b.minHeightM
                val y1 = b.baseZ + max(3f, b.heightM - b.roofHeightM.coerceAtMost(b.heightM * 0.4f))
                val shade = 0.36f + 0.14f * hash(n, (ring[0] * 10).toInt())
                val cr = shade; val cg = shade * 0.93f; val cb = shade * 0.82f
                // Orientation of the ring (counter-clockwise rings have positive area in map coordinates).
                var area = 0f
                for (i in 0 until n) { val j = (i + 1) % n; area += ring[2 * i] * ring[2 * j + 1] - ring[2 * j] * ring[2 * i + 1] }
                val ccw = area > 0
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val a = if (ccw) i else j; val bb = if (ccw) j else i
                    val ax = ring[2 * a]; val ay = ring[2 * a + 1]; val bx = ring[2 * bb]; val by = ring[2 * bb + 1]
                    val dx = bx - ax; val dy = by - ay
                    val l = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-3f)
                    // Outward normal of a counter-clockwise ring edge is (dy, -dx) in map coordinates.
                    val nx = dy / l; val nz = dx / l
                    val v0 = mb.vertex(ax, y0, -ay, nx, 0f, nz, 0f, 0f, cr, cg, cb)
                    val v1 = mb.vertex(bx, y0, -by, nx, 0f, nz, l, 0f, cr, cg, cb)
                    val v2 = mb.vertex(bx, y1, -by, nx, 0f, nz, l, y1 - y0, cr, cg, cb)
                    val v3 = mb.vertex(ax, y1, -ay, nx, 0f, nz, 0f, y1 - y0, cr, cg, cb)
                    mb.quad(v0, v1, v2, v3)
                }
                // Hipped-ish roof: a fan to the centroid, raised.
                var cx = 0f; var cy = 0f
                for (i in 0 until n) { cx += ring[2 * i]; cy += ring[2 * i + 1] }
                cx /= n; cy /= n
                val roofH = if (b.roofShape == 1) 0.2f else min(4f, 0.35f * sqrt(abs(area) / 2f))
                val apex = mb.vertex(cx, y1 + roofH, -cy, 0f, 1f, 0f, 0f, 0f, 0.42f, 0.22f, 0.16f)
                for (i in 0 until n) {
                    val j = (i + 1) % n
                    val a = if (ccw) i else j; val bb = if (ccw) j else i
                    val pa = mb.vertex(ring[2 * a], y1, -ring[2 * a + 1], 0f, 1f, 0f, 0f, 0f, 0.42f, 0.22f, 0.16f)
                    val pb = mb.vertex(ring[2 * bb], y1, -ring[2 * bb + 1], 0f, 1f, 0f, 0f, 0f, 0.42f, 0.22f, 0.16f)
                    mb.triangle(pa, pb, apex)
                }
            }
            if (!mb.isEmpty) meshes += walls to mb.build()
        }
        return SceneryChunk(input.originX, 0.0, -input.originY, sets, meshes)
    }

    private fun nearRoad(input: SceneryTileInput, x: Double, y: Double, clear: Double): Boolean {
        val p = input.ctx.path
        // Corridor sections tell which path samples lie in this tile; check those (cheap enough for a stub).
        for (sec in input.corridor) {
            var i = sec.firstSample
            val end = min(sec.firstSample + sec.count, p.size)
            while (i < end) {
                val dx = p.x[i] - x; val dy = p.y[i] - y
                if (abs(dx) < 60 && abs(dy) < 60 && dx * dx + dy * dy < (p.width[i] / 2 + clear) * (p.width[i] / 2 + clear)) return true
                i++
            }
        }
        return false
    }
}

/**
 * Spectators, barriers, flags and a simple rider model. [riders] and [kits] can come from the real
 * race package while its dressing generator is not there yet.
 */
internal class StubDressing(
    private val riders: () -> RiderModelSet = { StubRider.build() },
    kits: List<Kit>? = null,
) : RaceDressing {
    private val body = SceneMaterial("stub_body", roughness = 0.8f, anim = Anim.CHEER)
    private val metal = SceneMaterial("stub_metal", roughness = 0.4f)
    private val cloth = SceneMaterial("stub_flag", roughness = 0.9f, doubleSided = true, anim = Anim.FLAG, castsShadow = false)

    override fun prototypes(ctx: SceneContext): List<Prototype> {
        val fan = MeshBuilder()
        fan.box(0f, 0.45f, 0f, 0.32f, 0.9f, 0.22f, 0.2f, 0.22f, 0.3f)
        fan.box(0f, 1.25f, 0f, 0.42f, 0.7f, 0.26f, 1f, 1f, 1f)
        fan.box(0f, 1.72f, 0f, 0.2f, 0.24f, 0.22f, 0.85f, 0.62f, 0.48f)
        fan.box(0.27f, 1.45f, 0f, 0.1f, 0.6f, 0.1f, 1f, 1f, 1f, extra = 1f)
        fan.box(-0.27f, 1.45f, 0f, 0.1f, 0.6f, 0.1f, 1f, 1f, 1f, extra = 1f)
        val barrier = MeshBuilder()
        barrier.box(0f, 0.55f, 0f, 2.4f, 0.9f, 0.05f, 0.75f, 0.76f, 0.78f)
        barrier.box(-1.1f, 0.05f, 0f, 0.08f, 0.1f, 0.6f, 0.4f, 0.4f, 0.42f)
        barrier.box(1.1f, 0.05f, 0f, 0.08f, 0.1f, 0.6f, 0.4f, 0.4f, 0.42f)
        val pole = MeshBuilder()
        pole.cylinder(0f, 0f, 0f, 2.6f, 0.025f, 0.02f, 5, 0.3f, 0.3f, 0.3f)
        val flag = MeshBuilder()
        for (j in 0..6) {
            val x = j * 0.2f
            flag.vertex(x, 1.7f, 0f, 0f, 0f, 1f, x, 0f, 0.9f, 0.1f, 0.1f, x)
            flag.vertex(x, 2.5f, 0f, 0f, 0f, 1f, x, 1f, 0.95f, 0.95f, 0.2f, x)
        }
        for (j in 0 until 6) flag.quad(j * 2, j * 2 + 2, j * 2 + 3, j * 2 + 1)
        return listOf(
            Prototype("stub_fan", listOf(ProtoLod(listOf(body to fan.build()), 400f)), 1f),
            Prototype("stub_barrier", listOf(ProtoLod(listOf(metal to barrier.build()), 500f)), 1.3f),
            Prototype("stub_flag", listOf(ProtoLod(listOf(metal to pole.build(), cloth to flag.build()), 350f)), 1.5f),
        )
    }

    override fun range(ctx: SceneContext, fromM: Double, toM: Double, heights: (x: Double, y: Double) -> Double?): SceneryChunk {
        val p = ctx.path
        val i0 = (fromM / p.stepM).toInt().coerceIn(0, p.size - 1)
        val ax = p.x[i0]; val ay = p.y[i0]
        val fans = ArrayList<Float>(); val bars = ArrayList<Float>(); val flags = ArrayList<Float>()
        fun put(list: ArrayList<Float>, x: Double, y: Double, yaw: Float, scale: Float, tint: Int, phase: Float) {
            val z = heights(x, y) ?: return
            list += (x - ax).toFloat(); list += z.toFloat(); list += (-(y - ay)).toFloat()
            list += yaw; list += scale; list += tint.toFloat(); list += phase; list += 0f
        }
        var s = fromM
        var k = 0
        while (s < toM) {
            val pose = p.poseAt(s)
            val dens = crowdDensityAt(ctx, s)
            val yawRoad = kotlin.math.atan2(-pose.dirX, pose.dirY).toFloat()
            for (side in intArrayOf(1, -1)) {
                val lx = -pose.dirY * side; val ly = pose.dirX * side
                if (s > p.lengthM - 1000) {
                    val off = pose.widthM / 2 + 0.6
                    put(bars, pose.x + lx * off, pose.y + ly * off, yawRoad, 1f, 0xFFFFFF, 0f)
                }
                if (hash(k, side, 3) < dens) {
                    val off = pose.widthM / 2 + 1.2 + 2.0 * hash(k, side, 4)
                    val tint = listOf(0xFFE000, 0xFF2020, 0x2060FF, 0xFFFFFF, 0x20C040, 0xFF8000)[(hash(k, side, 5) * 6).toInt().coerceIn(0, 5)]
                    // Fans face the road.
                    val yaw = yawRoad + (if (side > 0) -PI / 2 else PI / 2).toFloat()
                    put(fans, pose.x + lx * off, pose.y + ly * off, yaw, 0.95f + 0.1f * hash(k, side, 6), tint, hash(k, side, 7))
                    if (hash(k, side, 8) < 0.12f) put(flags, pose.x + lx * (off + 0.6), pose.y + ly * (off + 0.6), yaw, 1f, 0xFFFFFF, hash(k, side, 9))
                }
            }
            s += 2.4
            k++
        }
        val sets = ArrayList<InstanceSet>()
        if (fans.isNotEmpty()) sets += InstanceSet("stub_fan", fans.toFloatArray(), fans.size / 8)
        if (bars.isNotEmpty()) sets += InstanceSet("stub_barrier", bars.toFloatArray(), bars.size / 8)
        if (flags.isNotEmpty()) sets += InstanceSet("stub_flag", flags.toFloatArray(), flags.size / 8)
        return SceneryChunk(ax, 0.0, -ay, sets)
    }

    override fun crowdDensityAt(ctx: SceneContext, s: Double): Double {
        val p = ctx.path
        val toGo = p.lengthM - s
        val grade = p.gradeAt(s, 50.0)
        val town = ctx.places.towns.any { s in it.sIn..it.sOut }
        return maxOf(
            if (toGo < 3000) 1.0 - toGo / 6000 else 0.0,
            if (town) 0.35 else 0.0,
            ((grade - 5) / 6).coerceIn(0.0, 0.7),
        ).coerceIn(0.0, 1.0)
    }

    override val kits: List<Kit> = kits ?: listOf(
        Kit("player", floatArrayOf(0.85f, 0.08f, 0.06f), floatArrayOf(0.95f, 0.95f, 0.95f), floatArrayOf(0.03f, 0.03f, 0.035f), floatArrayOf(0.95f, 0.95f, 0.95f), floatArrayOf(0.05f, 0.05f, 0.06f)),
        Kit("pacer", floatArrayOf(0.06f, 0.25f, 0.85f), floatArrayOf(0.95f, 0.85f, 0.1f), floatArrayOf(0.03f, 0.03f, 0.04f), floatArrayOf(0.06f, 0.25f, 0.85f), floatArrayOf(0.85f, 0.85f, 0.88f)),
        Kit("yellow", floatArrayOf(0.95f, 0.78f, 0.05f), floatArrayOf(0.05f, 0.05f, 0.05f), floatArrayOf(0.03f, 0.03f, 0.03f), floatArrayOf(0.95f, 0.78f, 0.05f), floatArrayOf(0.9f, 0.9f, 0.9f)),
        Kit("green", floatArrayOf(0.05f, 0.55f, 0.2f), floatArrayOf(0.95f, 0.95f, 0.95f), floatArrayOf(0.03f, 0.08f, 0.05f), floatArrayOf(0.95f, 0.95f, 0.95f), floatArrayOf(0.1f, 0.1f, 0.1f)),
    )

    override fun riderPrototypes(): RiderModelSet = riders()
}

/** A simple rider + bike in the rigid-part layout of [RiderModelSet] (kit colours through [KitChannel]). */
internal object StubRider {
    const val WHEEL_R = 0.34f
    private val mat = SceneMaterial("stub_rider", roughness = 0.6f)

    fun build(): RiderModelSet {
        val wheelbase = 1.0f
        val front = floatArrayOf(0f, WHEEL_R, -0.6f)
        val rear = floatArrayOf(0f, WHEEL_R, wheelbase - 0.6f)
        val body = MeshBuilder(1024)
        val bike = KitChannel.BIKE
        val jersey = KitChannel.JERSEY
        val shorts = KitChannel.SHORTS
        val helmet = KitChannel.HELMET
        // Frame: down tube, top tube, seat tube, stays, fork, bars.
        fun tube(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, c: FloatArray) {
            val dx = x1 - x0; val dy = y1 - y0; val dz = z1 - z0
            val l = sqrt(dx * dx + dy * dy + dz * dz)
            val steps = max(1, (l / 0.1f).toInt())
            for (i in 0 until steps) {
                val t = (i + 0.5f) / steps
                body.box(x0 + dx * t, y0 + dy * t, z0 + dz * t, r, r, r, c[0], c[1], c[2])
            }
        }
        val bb = floatArrayOf(0f, 0.28f, 0f)
        val seatTop = floatArrayOf(0f, 0.86f, 0.17f)
        val head = floatArrayOf(0f, 0.82f, -0.48f)
        tube(bb[0], bb[1], bb[2], head[0], head[1] - 0.12f, head[2], 0.045f, bike)
        tube(seatTop[0], seatTop[1] - 0.05f, seatTop[2], head[0], head[1], head[2], 0.04f, bike)
        tube(bb[0], bb[1], bb[2], seatTop[0], seatTop[1], seatTop[2], 0.04f, bike)
        tube(bb[0], bb[1], bb[2], rear[0], rear[1], rear[2], 0.03f, bike)
        tube(seatTop[0], seatTop[1] - 0.1f, seatTop[2], rear[0], rear[1], rear[2], 0.025f, bike)
        tube(head[0], head[1], head[2], front[0], front[1], front[2], 0.035f, bike)
        body.box(0f, 0.92f, -0.55f, 0.42f, 0.03f, 0.03f, 0.1f, 0.1f, 0.1f)
        body.box(0f, 0.88f, 0.2f, 0.12f, 0.04f, 0.26f, 0.05f, 0.05f, 0.05f)
        // Rider: hips over the saddle, torso leaning to the bars, head, arms.
        body.box(0f, 1.0f, 0.15f, 0.34f, 0.2f, 0.3f, shorts[0], shorts[1], shorts[2])
        for (i in 0 until 6) {
            val t = i / 5f
            body.box(0f, 1.06f + 0.18f * t, 0.12f - 0.55f * t, 0.36f - 0.04f * t, 0.24f, 0.16f, jersey[0], jersey[1], jersey[2])
        }
        body.box(0f, 1.28f, -0.25f, 0.30f, 0.06f, 0.2f, KitChannel.ACCENT[0], KitChannel.ACCENT[1], KitChannel.ACCENT[2])
        body.box(0f, 1.38f, -0.55f, 0.18f, 0.2f, 0.2f, 0.86f, 0.66f, 0.52f)
        body.box(0f, 1.47f, -0.55f, 0.24f, 0.13f, 0.32f, helmet[0], helmet[1], helmet[2])
        for (side in intArrayOf(-1, 1)) {
            tube(side * 0.19f, 1.3f, -0.4f, side * 0.2f, 0.95f, -0.55f, 0.08f, jersey)
        }
        val wheel = MeshBuilder(512)
        val seg = 20
        for (i in 0 until seg) {
            val a0 = (i.toFloat() / seg * 2 * PI).toFloat()
            val a1 = ((i + 1).toFloat() / seg * 2 * PI).toFloat()
            for (side in intArrayOf(-1, 1)) {
                val x = side * 0.012f
                val p0 = wheel.vertex(x, cos(a0) * WHEEL_R, sin(a0) * WHEEL_R, side.toFloat(), 0f, 0f, 0f, 0f, 0.05f, 0.05f, 0.05f)
                val p1 = wheel.vertex(x, cos(a1) * WHEEL_R, sin(a1) * WHEEL_R, side.toFloat(), 0f, 0f, 0f, 0f, 0.05f, 0.05f, 0.05f)
                val p2 = wheel.vertex(x, cos(a1) * (WHEEL_R - 0.05f), sin(a1) * (WHEEL_R - 0.05f), side.toFloat(), 0f, 0f, 0f, 0f, 0.15f, 0.15f, 0.17f)
                val p3 = wheel.vertex(x, cos(a0) * (WHEEL_R - 0.05f), sin(a0) * (WHEEL_R - 0.05f), side.toFloat(), 0f, 0f, 0f, 0f, 0.15f, 0.15f, 0.17f)
                if (side > 0) wheel.quad(p0, p1, p2, p3) else wheel.quad(p0, p3, p2, p1)
            }
        }
        // Spokes as thin boxes so the spin is visible.
        for (k in 0 until 6) {
            val a = (k / 6f * PI).toFloat()
            val m = MeshBuilder(); m.box(0f, 0f, 0f, 0.008f, (WHEEL_R - 0.05f) * 2, 0.008f, 0.6f, 0.6f, 0.62f)
            val mm = m.build()
            // Rotate the spoke about x by a (y,z plane).
            for (v in 0 until mm.vertexCount) {
                val o = v * Vtx.FLOATS
                val y = mm.vertices[o + 1]; val z = mm.vertices[o + 2]
                mm.vertices[o + 1] = y * cos(a) - z * sin(a); mm.vertices[o + 2] = y * sin(a) + z * cos(a)
            }
            wheel.append(mm)
        }
        val poses = ArrayList<List<Pair<SceneMaterial, MeshBuffer>>>()
        val n = 8
        for (k in 0 until n) {
            val legs = MeshBuilder(256)
            val crank = (k.toFloat() / n * 2 * PI).toFloat()
            for (side in intArrayOf(1, -1)) {
                val a = crank + if (side < 0) PI.toFloat() else 0f
                // Pedal on the crank circle (forward = -z, angle 0 = crank pointing forward).
                val px = side * 0.1f
                val py = bb[1] - sin(a) * 0.17f
                val pz = bb[2] - cos(a) * 0.17f
                val hip = floatArrayOf(side * 0.1f, 1.0f, 0.13f)
                // Knee from two segment lengths (thigh 0.45, shin 0.45), bent forward.
                val dx = pz - hip[2]; val dy = py - hip[1]
                val d = sqrt(dx * dx + dy * dy).coerceAtMost(0.89f)
                val mid = d / 2
                val up = sqrt((0.45f * 0.45f - mid * mid).coerceAtLeast(0f))
                val ux = dx / d; val uy = dy / d
                val kz = hip[2] + ux * mid + uy * up * -1f
                val ky = hip[1] + uy * mid - ux * up * -1f
                fun seg(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float, r: Float, c: FloatArray) {
                    val steps = 5
                    for (i in 0 until steps) {
                        val t = (i + 0.5f) / steps
                        legs.box(x0 + (x1 - x0) * t, y0 + (y1 - y0) * t, z0 + (z1 - z0) * t, r, r, r, c[0], c[1], c[2])
                    }
                }
                seg(hip[0], hip[1], hip[2], px, ky, kz, 0.13f, shorts)
                seg(px, ky, kz, px, py + 0.05f, pz, 0.1f, floatArrayOf(0.86f, 0.66f, 0.52f))
                legs.box(px, py, pz - 0.04f, 0.09f, 0.06f, 0.24f, 0.95f, 0.95f, 0.95f)
                legs.box(px * 0.6f, bb[1] - sin(a) * 0.085f, bb[2] - cos(a) * 0.085f, 0.02f, 0.03f, 0.03f, 0.2f, 0.2f, 0.22f)
            }
            poses += listOf(mat to legs.build())
        }
        val lod0 = RiderLod(1e9f, listOf(mat to body.build()), listOf(mat to wheel.build()), front, rear, poses)
        return RiderModelSet(listOf(lod0))
    }
}
