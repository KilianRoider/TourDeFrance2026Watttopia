package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.PlacesData
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.geo.TownStretch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** One stretch of the route's road surface, built on the worker. */
internal class RoadCpu(
    val chunk: Int,
    val firstSample: Int,
    val lastSample: Int,
    val anchorX: Double,
    val anchorZ: Double,
    val mesh: MeshBuffer,
    val minX: Float, val maxX: Float, val minY: Float, val maxY: Float, val minZ: Float, val maxZ: Float,
    /** Index range of the tunnel tubes (lining and outer shell), which the shadow pass draws. */
    val tubeFirst: Int = 0,
    val tubeCount: Int = 0,
)

/**
 * Builds the route's road from the 5 m PATH: a cambered carriageway (asphalt, cobbles or gravel by the
 * path flags), a short edge face down to the corridor strip, kerbs and pavements through towns, bridge
 * decks with parapets and piers, and tunnel tubes with portals. Vertex layout: [Vtx] with
 * uv = (metres along, metres across or up), colour = (surface kind, road class, half width), extra = tunnel
 * darkness (0 in the open, 1 from ~30 m inside a tunnel; the shadow map has no sky and ends at ~100 m).
 */
internal class RoadBuilder(private val path: RoutePath, private val towns: List<TownStretch>, private val ground: (Double, Double) -> Double?) {
    companion object {
        const val CHUNK_SAMPLES = 200
        const val ASPHALT = 0f
        const val COBBLES = 1f
        const val KERB = 2f
        const val PAVEMENT = 3f
        const val CONCRETE = 4f
        const val GRAVEL = 5f
        const val TUNNEL = 6f
        /** Tunnel tube: lining this far beyond the carriageway edge and this high, the outer shell this much further out. */
        const val TUBE_GAP = 0.8f
        const val TUBE_H = 5.6f
        const val TUBE_WALL = 0.4f
        /** The road surface lies this much above the path's elevation, the carved bed 0.15 m below it. */
        const val LIFT = RoadProfile.LIFT
        /**
         * The along-road texture coordinate restarts every U_PERIOD metres: a float keeps centimetre
         * precision only up to a few km, and every periodic pattern of the road shader (2 m textures,
         * 13 m dashes, 6.5 m edge dashes, 30 m repair cells) repeats an exact number of times in it.
         */
        const val U_PERIOD = 2340.0

        /**
         * The town stretches that get kerbs and pavements: real towns, not every hamlet the road passes
         * (French villages of a hundred people have no kerbs, the road just runs between the houses).
         */
        /** Kerb and pavement: one profile for the mesh below and for GroundSampler ([RoadProfile]). */
        const val KERB_W = RoadProfile.KERB_W
        const val KERB_H = RoadProfile.KERB_H
        const val PAVEMENT_W = RoadProfile.PAVEMENT_W
        const val PAVEMENT_RISE = RoadProfile.PAVEMENT_RISE

        /** Per path sample: true where the road has kerbs and pavements (a kerb town, not on a bridge or in a tunnel). */
        fun kerbSamples(path: RoutePath, towns: List<TownStretch>): BooleanArray = BooleanArray(path.size) { i ->
            val s = path.distanceOf(i)
            (path.flags[i].toInt() and (PathFlags.BRIDGE or PathFlags.TUNNEL)) == 0 && towns.any { s >= it.sIn && s <= it.sOut }
        }

        fun kerbTowns(places: PlacesData): List<TownStretch> = places.towns.filter { t ->
            val place = places.places.firstOrNull { it.name == t.name }
            val pop = t.population ?: place?.population
            val cls = place?.placeClass ?: ""
            (pop != null && pop >= 900) || cls == "town" || cls == "city" ||
                (pop == null && cls == "village" && t.sOut - t.sIn >= 1500)
        }
    }

    val chunkCount: Int get() = (path.size - 2) / CHUNK_SAMPLES + 1

    /** Per path sample: metres to the nearest sample outside a tunnel (0 outside). Built once, on the worker. */
    private val tunnelDepth: FloatArray by lazy {
        val n = path.size
        val d = FloatArray(n)
        val step = path.stepM.toFloat()
        var run = 0f
        for (i in 0 until n) { run = if ((path.flags[i].toInt() and PathFlags.TUNNEL) != 0) run + step else 0f; d[i] = run }
        run = 0f
        for (i in n - 1 downTo 0) { run = if ((path.flags[i].toInt() and PathFlags.TUNNEL) != 0) run + step else 0f; d[i] = min(d[i], run) }
        d
    }

    /** How dark the road is at path sample [i]: daylight reaches some 5-30 m into a tunnel. */
    private fun tunnelDark(i: Int): Float {
        val t = ((tunnelDepth[i] - 5f) / 25f).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    fun build(chunk: Int): RoadCpu {
        val a = chunk * CHUNK_SAMPLES
        val b = min(path.size - 1, a + CHUNK_SAMPLES)
        val ax = path.x[a]
        val ay = path.y[a]
        val mb = MeshBuilder((b - a + 1) * 24, (b - a + 1) * 80)
        val count = b - a + 1
        // Cross-section frame per sample: position (GL, relative to the anchor), left normal, half width.
        val px = FloatArray(count); val py = FloatArray(count); val pz = FloatArray(count)
        val lx = FloatArray(count); val lz = FloatArray(count)
        val hw = FloatArray(count); val innerL = FloatArray(count); val innerR = FloatArray(count)
        val u = FloatArray(count)
        val uBase = floor(path.distanceOf(a) / U_PERIOD) * U_PERIOD
        val sAt = DoubleArray(count) { path.distanceOf(a + it) }
        for (q in 0 until count) {
            val i = a + q
            px[q] = (path.x[i] - ax).toFloat()
            py[q] = path.z[i].toFloat() + LIFT
            pz[q] = (-(path.y[i] - ay)).toFloat()
            val i0 = max(0, i - 1); val i1 = min(path.size - 1, i + 1)
            var dx = path.x[i1] - path.x[i0]; var dy = path.y[i1] - path.y[i0]
            val dl = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            dx /= dl; dy /= dl
            // Left of the direction of travel in map coordinates (-dy, dx), in GL (x, -y).
            lx[q] = (-dy).toFloat(); lz[q] = (-dx).toFloat()
            hw[q] = path.width[i] / 2f
            u[q] = (path.distanceOf(i) - uBase).toFloat()
            val turn = turn(i)
            val radius = if (abs(turn) > 1e-4) (path.stepM / abs(turn)).toFloat() else 1e9f
            // On the inside of a tight bend the cross-section is limited to the centre of curvature.
            innerL[q] = if (turn > 0) radius * 0.95f else 1e9f
            innerR[q] = if (turn < 0) radius * 0.95f else 1e9f
        }
        fun flags(q: Int) = path.flags[a + q].toInt() and 0xFF
        fun cls(q: Int) = (path.roadClass[a + q].toInt() and 0xFF).toFloat()
        fun town(q: Int): Boolean {
            val s = sAt[q]
            return towns.any { s >= it.sIn && s <= it.sOut }
        }
        fun surface(q: Int): Float {
            val f = flags(q)
            return when {
                f and PathFlags.COBBLES != 0 -> COBBLES
                f and PathFlags.UNPAVED != 0 -> GRAVEL
                else -> ASPHALT
            }
        }
        // Lateral clamp on the inner side of a bend.
        fun lat(q: Int, l: Float): Float = if (l >= 0) min(l, innerL[q]) else -min(-l, innerR[q])
        fun pos(q: Int, l: Float, dy: Float): FloatArray {
            val ll = lat(q, l)
            return floatArrayOf(px[q] + lx[q] * ll, py[q] + dy, pz[q] + lz[q] * ll)
        }
        fun dirAt(q: Int): FloatArray {
            val q0 = max(0, q - 1); val q1 = min(count - 1, q + 1)
            if (q1 == q0) return floatArrayOf(-lz[q], 0f, lx[q])
            val dx = px[q1] - px[q0]; val dy = py[q1] - py[q0]; val dz = pz[q1] - pz[q0]
            val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
            return floatArrayOf(dx / l, dy / l, dz / l)
        }

        /** A strip along the run [q0, q1] through cross-section points (lateral, height) with one kind. */
        fun strip(q0: Int, q1: Int, profile: (Int) -> List<FloatArray>, kind: (Int) -> Float, vertical: Boolean = false) {
            var prev: IntArray? = null
            for (q in q0..q1) {
                val prof = profile(q)
                val d = dirAt(q)
                val ids = IntArray(prof.size)
                for ((j, pt) in prof.withIndex()) {
                    val p = pos(q, pt[0], pt[1])
                    // Normal from the neighbouring points across and the direction along.
                    val o = prof[min(j + 1, prof.size - 1)]
                    val w = prof[max(j - 1, 0)]
                    val ax2 = (o[0] - w[0]); val ay2 = (o[1] - w[1])
                    // Across vector in GL: left normal * dLat + up * dHeight.
                    val cx = lx[q] * ax2; val cy = ay2; val cz = lz[q] * ax2
                    // n = across x along (points up for a flat road with across to the left).
                    var nx = cy * d[2] - cz * d[1]
                    var ny = cz * d[0] - cx * d[2]
                    var nz = cx * d[1] - cy * d[0]
                    val nl = sqrt(nx * nx + ny * ny + nz * nz).coerceAtLeast(1e-6f)
                    nx /= nl; ny /= nl; nz /= nl
                    val v = if (vertical) pt[1] + pt[2] else pt[0]
                    ids[j] = mb.vertex(p[0], p[1], p[2], nx, ny, nz, u[q], v, kind(q), cls(q), hw[q], tunnelDark(a + q))
                }
                val pv = prev
                if (pv != null && pv.size == ids.size) {
                    for (j in 0 until ids.size - 1) {
                        // Points run from left to right: counter-clockwise seen from their front side.
                        mb.quad(pv[j], pv[j + 1], ids[j + 1], ids[j])
                    }
                }
                prev = ids
            }
        }

        // Runs of equal cross-section type: 0 open road, 1 town (kerbs), 2 bridge, 3 tunnel.
        fun typeOf(q: Int): Int {
            val f = flags(q)
            return when {
                f and PathFlags.TUNNEL != 0 -> 3
                f and PathFlags.BRIDGE != 0 -> 2
                town(q) -> 1
                else -> 0
            }
        }
        var q = 0
        while (q < count) {
            val t = typeOf(q)
            var e = q
            while (e + 1 < count && typeOf(e + 1) == t) e++
            // Include the next sample so consecutive runs join without a gap.
            val end = min(count - 1, e + 1)
            if (end > q) buildRun(q, end, t, ::strip, { hw[it] }, { surface(it) })
            q = e + 1
        }
        // Bridge piers and tunnel portals at the ends of their runs.
        q = 0
        while (q < count) {
            val t = typeOf(q)
            var e = q
            while (e + 1 < count && typeOf(e + 1) == t) e++
            if (t == 2) piers(mb, q, e, a, px, py, pz, lx, lz, hw, ax, ay)
            if (t == 3) {
                if (a + q > 0 && (path.flags[a + q - 1].toInt() and PathFlags.TUNNEL) == 0) portal(mb, q, px, py, pz, lx, lz, hw, u, false)
                if (a + e < path.size - 1 && (path.flags[a + e + 1].toInt() and PathFlags.TUNNEL) == 0) portal(mb, e, px, py, pz, lx, lz, hw, u, true)
            }
            q = e + 1
        }
        // Tunnel tubes last, in one index range the shadow pass can draw on its own: without them in the
        // shadow map the sun would shine on the road and the riders inside.
        val tubeFirst = mb.indexCount
        q = 0
        while (q < count) {
            val t = typeOf(q)
            var e = q
            while (e + 1 < count && typeOf(e + 1) == t) e++
            val end = min(count - 1, e + 1)
            if (t == 3 && end > q) {
                // The lining: a vaulted tube seen from inside (points run right to left so the faces look inwards).
                strip(q, end, { s ->
                    val h = hw[s] + TUBE_GAP
                    List(11) { j -> val ang = (PI * j / 10).toFloat(); floatArrayOf(-cos(ang) * h, sin(ang) * TUBE_H, 0f) }
                }, { TUNNEL }, false)
                // The outer shell, facing out: a gallery on a slope or a cut-and-cover tunnel shows it, and
                // it closes the hole the terrain shader cuts around the tube (GeoShaders, TUNNEL_CUT).
                strip(q, end, { s ->
                    val h = hw[s] + TUBE_GAP + TUBE_WALL
                    val pts = ArrayList<FloatArray>(13)
                    pts += floatArrayOf(h, -0.6f, 0f)
                    for (j in 10 downTo 0) { val ang = (PI * j / 10).toFloat(); pts += floatArrayOf(-cos(ang) * h, sin(ang) * (TUBE_H + TUBE_WALL), 0f) }
                    pts += floatArrayOf(-h, -0.6f, 0f)
                    pts
                }, { CONCRETE }, false)
            }
            q = e + 1
        }
        val tubeCount = mb.indexCount - tubeFirst
        val m = mb.build()
        var x0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        for (i in 0 until m.vertexCount) {
            val o = i * Vtx.FLOATS
            x0 = min(x0, m.vertices[o]); x1 = max(x1, m.vertices[o])
            y0 = min(y0, m.vertices[o + 1]); y1 = max(y1, m.vertices[o + 1])
            z0 = min(z0, m.vertices[o + 2]); z1 = max(z1, m.vertices[o + 2])
        }
        return RoadCpu(chunk, a, b, ax, -ay, m, x0, x1, y0, y1, z0, z1, tubeFirst, tubeCount)
    }

    private fun buildRun(
        q0: Int, q1: Int, type: Int,
        strip: (Int, Int, (Int) -> List<FloatArray>, (Int) -> Float, Boolean) -> Unit,
        hw: (Int) -> Float, surface: (Int) -> Float,
    ) {
        // Carriageway with a 2.5 % crown; the outer points drop to the carved bed under the verge.
        val crown = { q: Int -> 0.025f * hw(q) }
        val carriage = { q: Int ->
            val h = hw(q); val c = crown(q)
            listOf(
                floatArrayOf(h, 0f, 0f), floatArrayOf(h * 0.5f, c * 0.5f, 0f), floatArrayOf(0f, c, 0f),
                floatArrayOf(-h * 0.5f, c * 0.5f, 0f), floatArrayOf(-h, 0f, 0f),
            )
        }
        strip(q0, q1, carriage, surface, false)
        when (type) {
            0 -> {
                // Edge faces down to the bed (the corridor strip starts 0.15 m below the road).
                strip(q0, q1, { q -> listOf(floatArrayOf(hw(q), -0.3f, 0f), floatArrayOf(hw(q), 0f, 0f)) }, surface, true)
                strip(q0, q1, { q -> listOf(floatArrayOf(-hw(q), 0f, 0f), floatArrayOf(-hw(q), -0.3f, 0f)) }, surface, true)
            }
            1 -> {
                // Kerb face, kerb top, pavement sloping 2 % to the road, outer face down. Cross-section points
                // run so that the front of every face is to the left of the walking direction seen from behind
                // (decreasing lateral = towards the right on a horizontal surface means facing up).
                // The profile is RoadProfile.kerbHeight's (the heights W2 and W3 stand things on).
                val k = KERB_H
                val kw = KERB_W
                val pw = PAVEMENT_W
                val top = KERB_H + PAVEMENT_RISE
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(h + kw, k, 0f), floatArrayOf(h, k, 0f), floatArrayOf(h, -0.02f, 0f)) }, { KERB }, true)
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(-h, -0.02f, 0f), floatArrayOf(-h, k, 0f), floatArrayOf(-h - kw, k, 0f)) }, { KERB }, true)
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(h + kw + pw, top, 0f), floatArrayOf(h + kw, k, 0f)) }, { PAVEMENT }, false)
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(-h - kw, k, 0f), floatArrayOf(-h - kw - pw, top, 0f)) }, { PAVEMENT }, false)
                strip(q0, q1, { q -> val h = hw(q) + kw + pw; listOf(floatArrayOf(h, -0.6f, 0f), floatArrayOf(h, top, 0f)) }, { KERB }, true)
                strip(q0, q1, { q -> val h = hw(q) + kw + pw; listOf(floatArrayOf(-h, top, 0f), floatArrayOf(-h, -0.6f, 0f)) }, { KERB }, true)
            }
            2 -> {
                // Bridge: a narrow walkway, parapet walls 1 m high, the deck's sides and underside.
                strip(q0, q1, { q ->
                    val h = hw(q) + 0.35f
                    listOf(
                        floatArrayOf(h + 0.3f, -1.3f, 0f), floatArrayOf(h + 0.3f, 1.05f, 0f), floatArrayOf(h, 1.05f, 0f),
                        floatArrayOf(h, 0.12f, 0f), floatArrayOf(hw(q), 0f, 0f),
                    )
                }, { CONCRETE }, true)
                strip(q0, q1, { q ->
                    val h = hw(q) + 0.35f
                    listOf(
                        floatArrayOf(-hw(q), 0f, 0f), floatArrayOf(-h, 0.12f, 0f), floatArrayOf(-h, 1.05f, 0f),
                        floatArrayOf(-h - 0.3f, 1.05f, 0f), floatArrayOf(-h - 0.3f, -1.3f, 0f),
                    )
                }, { CONCRETE }, true)
                val under = { q: Int -> val h = hw(q) + 0.65f; listOf(floatArrayOf(-h, -1.3f, 0f), floatArrayOf(h, -1.3f, 0f)) }
                strip(q0, q1, under, { CONCRETE }, false)
            }
            3 -> {
                // Tunnel: the floor between the carriageway and the lining, facing up (the terrain under it
                // is cut away); the tube itself is built after everything else (see build).
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(h + TUBE_GAP, 0f, 0f), floatArrayOf(h, 0f, 0f)) }, { TUNNEL }, false)
                strip(q0, q1, { q -> val h = hw(q); listOf(floatArrayOf(-h, 0f, 0f), floatArrayOf(-h - TUBE_GAP, 0f, 0f)) }, { TUNNEL }, false)
            }
        }
    }

    private fun turn(i: Int): Double {
        if (i <= 0 || i >= path.size - 1) return 0.0
        val a1 = atan2(path.y[i] - path.y[i - 1], path.x[i] - path.x[i - 1])
        val a2 = atan2(path.y[i + 1] - path.y[i], path.x[i + 1] - path.x[i])
        var d = a2 - a1
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return d
    }

    /** Piers every ~30 m under a bridge, from the deck down to the ground. */
    private fun piers(
        mb: MeshBuilder, q0: Int, q1: Int, a: Int, px: FloatArray, py: FloatArray, pz: FloatArray,
        lx: FloatArray, lz: FloatArray, hw: FloatArray, ax: Double, ay: Double,
    ) {
        val n = q1 - q0 + 1
        if (n < 4) return
        val step = 6
        var q = q0 + step / 2
        while (q < q1 - 1) {
            val i = a + q
            val g = ground(path.x[i], path.y[i]) ?: (py[q] - 20.0)
            val top = py[q] - 1.3f
            val bottom = (g - 1.0).toFloat()
            val h = top - bottom
            if (h > 2.5f) {
                for (side in intArrayOf(-1, 1)) {
                    val off = side * hw[q] * 0.55f
                    mb.cylinder(px[q] + lx[q] * off, bottom, pz[q] + lz[q] * off, h, 0.75f, 0.65f, 10, CONCRETE, 0f, hw[q], 0f, 0f, false)
                }
            }
            q += step
        }
        @Suppress("UNUSED_EXPRESSION") ax; @Suppress("UNUSED_EXPRESSION") ay
    }

    /** A concrete frame around a tunnel mouth, facing out of the tunnel. */
    private fun portal(mb: MeshBuilder, q: Int, px: FloatArray, py: FloatArray, pz: FloatArray, lx: FloatArray, lz: FloatArray, hw: FloatArray, u: FloatArray, exit: Boolean) {
        val h = hw[q] + TUBE_GAP
        // Forward direction (GL) from the left normal: left = (-dy, -dx) in GL x/z, forward = (dx, -dy).
        val fx = -lz[q]; val fz = lx[q]
        val dir = if (exit) 1f else -1f
        val nx = fx * dir; val nz = fz * dir
        val outer = h + 1.4f
        val top = 7.4f
        val ring = 12
        val ids = ArrayList<Pair<Int, Int>>()
        for (j in 0..ring) {
            val ang = (PI * j / ring).toFloat()
            val ix = -cos(ang) * h; val iy = sin(ang) * TUBE_H
            val ox = -cos(ang) * outer; val oy = min(top, sin(ang) * top + 0.4f).coerceAtLeast(iy + 1.0f)
            val i1 = mb.vertex(px[q] + lx[q] * ix, py[q] + iy, pz[q] + lz[q] * ix, nx, 0f, nz, u[q], iy, CONCRETE, 0f, hw[q], 0f)
            val o1 = mb.vertex(px[q] + lx[q] * ox, py[q] + oy, pz[q] + lz[q] * ox, nx, 0f, nz, u[q] + 1.5f, oy, CONCRETE, 0f, hw[q], 0f)
            ids += i1 to o1
        }
        for (j in 0 until ring) {
            val (i0, o0) = ids[j]
            val (i1, o1) = ids[j + 1]
            // Both windings: the frame is seen from the road outside and from inside the tube.
            mb.quad(i0, o0, o1, i1)
            mb.quad(i0, i1, o1, o0)
        }
    }
}
