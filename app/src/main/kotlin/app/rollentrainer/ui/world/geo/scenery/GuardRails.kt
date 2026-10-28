package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.ui.world.geo.RegionStyle
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Guard rails on the valley side and parapets on bridges, as tile meshes following the route
 * polyline (instances would kink at every joint).
 *
 * Where the corridor cross-section falls away steeply beside the carriageway (from the CORRIDOR
 * heights: −1.3 m at 3 m, −2.5 m at 6 m or −4.5 m at 10 m beyond the edge) the road gets a
 * W-beam rail, or in the Alps and Pyrenees sometimes the low stone parapet of old mountain
 * roads. Bridges get rails on both sides. Gaps shorter than 25 m are closed and pieces shorter
 * than 20 m dropped, as real rails run continuously. Where the race crowd stands (W3's
 * RacePlan.crowdBand) there is no rail off the bridges: the spectators line the edge there.
 */
internal class GuardRails(private val j: TileJob, private val tm: TileMesh) {
    private val path = j.ctx.path
    private val f = j.reg.features(j.ctx)
    private val steel = lin(0xA9ADAF)
    private val steelDark = lin(0x7C8084)
    private val postCol = lin(0x6E7276)

    var segments = 0
        private set
    /** Placed rail segments as (sample, side), for the checks. */
    internal val placed = ArrayList<Pair<Int, Int>>()

    fun run() {
        val a = j.route.firstSample
        val b = j.route.lastSample
        if (b < a) return
        val lo = max(0, a - 10)
        val hi = min(path.size - 1, b + 10)
        for (side in intArrayOf(-1, 1)) {
            val need = BooleanArray(hi - lo + 1) { needsRail(lo + it, side > 0) }
            closeGaps(need, 5)
            // After closing gaps (they must not bridge a crowd stretch), before dropping short pieces.
            for (k in need.indices) if (need[k] && inCrowd(lo + k)) need[k] = false
            dropShort(need, 4)
            for (i in a..min(b, path.size - 2)) {
                if (!need[i - lo] || !need[i + 1 - lo]) continue
                if (!j.inTile(path.x[i], path.y[i])) continue
                val stone = stoneAt(i)
                if (stone) parapet(i, side) else wBeam(i, side)
                segments++
                placed += i to side
            }
        }
    }

    /** A crowd stands beside sample [i] (not on bridges: they keep their rails and have no crowd). */
    private fun inCrowd(i: Int): Boolean {
        if (path.flags[i].toInt() and PathFlags.BRIDGE != 0) return false
        return j.race.crowdBand(path.distanceOf(i)) > 0 || j.race.crowdBand(path.distanceOf(min(path.size - 1, i + 1))) > 0
    }

    private fun needsRail(i: Int, left: Boolean): Boolean {
        val fl = path.flags[i].toInt()
        if (fl and PathFlags.TUNNEL != 0) return false
        if (fl and PathFlags.BRIDGE != 0) return true
        val g = j.ground
        val r2 = g.relAt(i, left, 2) ?: 0.0
        val r3 = g.relAt(i, left, 3) ?: 0.0
        val r4 = g.relAt(i, left, 4) ?: 0.0
        return r2 < -1.3 || r3 < -2.5 || r4 < -4.5
    }

    /** Stone parapets on some stretches of old mountain roads (per 200 m, so tiles agree). */
    private fun stoneAt(i: Int): Boolean {
        val mountain = j.style == RegionStyle.ALPS || j.style == RegionStyle.PYRENEES || j.style == RegionStyle.MASSIF_CENTRAL
        val inTown = f.inTown(path.distanceOf(i))
        val u = Hash.unit(Hash.of(j.ctx.seed.toLong(), (i / 40).toLong(), 0x9A2A))
        return (mountain && u < 0.3f) || (inTown && u < 0.7f)
    }

    private fun closeGaps(b: BooleanArray, maxGap: Int) {
        var last = -1
        for (i in b.indices) {
            if (b[i]) {
                if (last >= 0 && i - last - 1 in 1..maxGap) for (k in last + 1 until i) b[k] = true
                last = i
            }
        }
    }

    private fun dropShort(b: BooleanArray, minLen: Int) {
        var i = 0
        while (i < b.size) {
            if (!b[i]) { i++; continue }
            var e = i
            while (e + 1 < b.size && b[e + 1]) e++
            // Runs touching the window edge may continue outside: keep them.
            if (e - i + 1 < minLen && i > 0 && e < b.size - 1) for (k in i..e) b[k] = false
            i = e + 1
        }
    }

    /** Outward unit normal (away from the road) at sample [i] on [side]. */
    private fun normal(i: Int, side: Int): DoubleArray {
        val h = f.heading[i]
        val dx = cos(h); val dy = sin(h)
        return doubleArrayOf(-dy * side, dx * side)
    }

    private fun wBeam(i: Int, side: Int) {
        val mb = j.meshes.builder(Mats.metal)
        val off = 0.45
        val pts = Array(2) { k ->
            val s = i + k
            val n = normal(s, side)
            val o = path.width[s] / 2.0 + off
            doubleArrayOf(path.x[s] + n[0] * o, path.y[s] + n[1] * o, path.z[s], n[0], n[1])
        }
        // Front (towards the road) in two strips tilted down and up: the W profile catches light.
        val strips = arrayOf(doubleArrayOf(0.44, 0.60, -0.45), doubleArrayOf(0.60, 0.76, 0.45))
        for (st in strips) {
            val (z0, z1, tilt) = Triple(st[0], st[1], st[2])
            val nx0 = (-pts[0][3]).toFloat(); val ny0 = (-pts[0][4]).toFloat()
            val nz = tilt.toFloat()
            val c = if (tilt < 0) steelDark else steel
            val a0 = pts[0]; val a1 = pts[1]
            // A quad's corners must run counter-clockwise seen from the viewer. The road lies left of
            // the travel direction for a right-side rail (side < 0), so there b comes first.
            val q = if (side > 0) arrayOf(
                doubleArrayOf(a0[0], a0[1], a0[2] + z0), doubleArrayOf(a1[0], a1[1], a1[2] + z0),
                doubleArrayOf(a1[0], a1[1], a1[2] + z1), doubleArrayOf(a0[0], a0[1], a0[2] + z1),
            ) else arrayOf(
                doubleArrayOf(a1[0], a1[1], a1[2] + z0), doubleArrayOf(a0[0], a0[1], a0[2] + z0),
                doubleArrayOf(a0[0], a0[1], a0[2] + z1), doubleArrayOf(a1[0], a1[1], a1[2] + z1),
            )
            tm.quad(mb, q, normalised(nx0, ny0, nz), UV, c)
        }
        // Back face.
        val a0 = pts[0]; val a1 = pts[1]
        val bo = 0.06
        val q = if (side > 0) arrayOf(
            doubleArrayOf(a1[0] + a1[3] * bo, a1[1] + a1[4] * bo, a1[2] + 0.44), doubleArrayOf(a0[0] + a0[3] * bo, a0[1] + a0[4] * bo, a0[2] + 0.44),
            doubleArrayOf(a0[0] + a0[3] * bo, a0[1] + a0[4] * bo, a0[2] + 0.76), doubleArrayOf(a1[0] + a1[3] * bo, a1[1] + a1[4] * bo, a1[2] + 0.76),
        ) else arrayOf(
            doubleArrayOf(a0[0] + a0[3] * bo, a0[1] + a0[4] * bo, a0[2] + 0.44), doubleArrayOf(a1[0] + a1[3] * bo, a1[1] + a1[4] * bo, a1[2] + 0.44),
            doubleArrayOf(a1[0] + a1[3] * bo, a1[1] + a1[4] * bo, a1[2] + 0.76), doubleArrayOf(a0[0] + a0[3] * bo, a0[1] + a0[4] * bo, a0[2] + 0.76),
        )
        tm.quad(mb, q, floatArrayOf(a0[3].toFloat(), a0[4].toFloat(), 0f), UV, steelDark)
        // A post behind the beam at every sample (5 m: the usual 4 m spacing, near enough).
        val px = a0[0] + a0[3] * 0.16
        val py = a0[1] + a0[4] * 0.16
        tm.post(mb, px, py, a0[2] - 0.4, a0[2] + 0.7, 0.06, postCol)
    }

    private fun parapet(i: Int, side: Int) {
        val mat = Mats.rock(j.profile.rock)
        val mb = j.meshes.builder(mat)
        val tile = 3.0
        val pts = Array(2) { k ->
            val s = i + k
            val n = normal(s, side)
            val o = path.width[s] / 2.0 + 0.3
            doubleArrayOf(path.x[s] + n[0] * o, path.y[s] + n[1] * o, path.z[s], n[0], n[1])
        }
        val s0 = path.distanceOf(i)
        val s1 = path.distanceOf(i + 1)
        val u0 = (s0 / tile % 1000).toFloat()
        val u1 = u0 + ((s1 - s0) / tile).toFloat()
        val h = 0.6
        val w = 0.45
        val drop = 1.6
        val col = floatArrayOf(0.92f, 0.9f, 0.86f)
        val a = pts[0]; val b = pts[1]
        fun out(p: DoubleArray, d: Double) = doubleArrayOf(p[0] + p[3] * d, p[1] + p[4] * d)
        val (ao, bo) = out(a, w) to out(b, w)
        val vTop = 0f
        val vH = (h / tile).toFloat()
        val vDrop = ((h + drop) / tile).toFloat()
        val first = side < 0
        // Inner face (towards the road), top, outer face down into the slope.
        val inner = arrayOf(doubleArrayOf(a[0], a[1], a[2] - 0.1), doubleArrayOf(b[0], b[1], b[2] - 0.1), doubleArrayOf(b[0], b[1], b[2] + h), doubleArrayOf(a[0], a[1], a[2] + h))
        val top = arrayOf(doubleArrayOf(a[0], a[1], a[2] + h), doubleArrayOf(b[0], b[1], b[2] + h), doubleArrayOf(bo[0], bo[1], b[2] + h), doubleArrayOf(ao[0], ao[1], a[2] + h))
        val outer = arrayOf(doubleArrayOf(bo[0], bo[1], b[2] - drop), doubleArrayOf(ao[0], ao[1], a[2] - drop), doubleArrayOf(ao[0], ao[1], a[2] + h), doubleArrayOf(bo[0], bo[1], b[2] + h))
        val nIn = floatArrayOf((-a[3]).toFloat(), (-a[4]).toFloat(), 0f)
        val nOut = floatArrayOf(a[3].toFloat(), a[4].toFloat(), 0f)
        val uvIn = arrayOf(floatArrayOf(u0, vH + 0.03f), floatArrayOf(u1, vH + 0.03f), floatArrayOf(u1, vTop), floatArrayOf(u0, vTop))
        val uvOut = arrayOf(floatArrayOf(u1, vDrop), floatArrayOf(u0, vDrop), floatArrayOf(u0, vTop), floatArrayOf(u1, vTop))
        val uvTop = arrayOf(floatArrayOf(u0, 0f), floatArrayOf(u1, 0f), floatArrayOf(u1, 0.15f), floatArrayOf(u0, 0.15f))
        // Counter-clockwise seen from the viewer: the road is left of a→b for a right-side wall.
        if (first) {
            tm.quad(mb, arrayOf(inner[1], inner[0], inner[3], inner[2]), nIn, arrayOf(uvIn[1], uvIn[0], uvIn[3], uvIn[2]), col)
            tm.quad(mb, arrayOf(top[3], top[2], top[1], top[0]), floatArrayOf(0f, 0f, 1f), arrayOf(uvTop[3], uvTop[2], uvTop[1], uvTop[0]), col)
            tm.quad(mb, arrayOf(outer[1], outer[0], outer[3], outer[2]), nOut, arrayOf(uvOut[1], uvOut[0], uvOut[3], uvOut[2]), mul(col, 0.9f))
        } else {
            tm.quad(mb, inner, nIn, uvIn, col)
            tm.quad(mb, top, floatArrayOf(0f, 0f, 1f), uvTop, col)
            tm.quad(mb, outer, nOut, uvOut, mul(col, 0.9f))
        }
    }

    private fun normalised(x: Float, y: Float, z: Float): FloatArray {
        val l = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(1e-6f)
        return floatArrayOf(x / l, y / l, z / l)
    }

    companion object {
        private val UV = arrayOf(floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f), floatArrayOf(0f, 0f))
    }
}
