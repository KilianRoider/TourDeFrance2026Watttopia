package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.CorridorSection
import app.rollentrainer.core.geo.HeightTile
import app.rollentrainer.core.geo.RoutePath
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Spatial queries for one tile: where the route is (exact, for keeping things off the road and
 * standing furniture on the verge), how far away it is (coarse, for density falloff), side roads,
 * water and building footprints. All coordinates here are pack coordinates (x east, y north).
 */

/** Result of a nearest-route query. */
internal class RouteHit {
    /** Distance from the centre line (m). */
    var dist = 0.0
    /** Signed lateral offset: positive left of the direction of travel. */
    var lateral = 0.0
    /** Segment start sample and fraction along it. */
    var i = 0
    var t = 0.0
    var halfWidth = 0.0
    var roadZ = 0.0
    var dirX = 0.0
    var dirY = 1.0
    /** Distance along the route. */
    var s = 0.0
    /** Distance beyond the road edge (negative = on the road). */
    val beyondEdge: Double get() = dist - halfWidth
    val left: Boolean get() = lateral > 0
}

/** The route's segments near a tile, bucketed in a grid for nearest-segment queries up to [reach] m. */
internal class RouteIndex(
    private val path: RoutePath,
    private val gx0: Double,
    private val gy0: Double,
    gx1: Double,
    gy1: Double,
    private val reach: Double,
    private val cell: Double = 20.0,
) {
    private val nx = max(1, ((gx1 - gx0) / cell).toInt() + 1)
    private val ny = max(1, ((gy1 - gy0) / cell).toInt() + 1)
    private val heads = IntArray(nx * ny) { -1 }
    private var next = IntArray(1024)
    private var seg = IntArray(1024)
    private var n = 0
    /** Sample index range touching the box (for furniture passes). */
    val firstSample: Int
    val lastSample: Int
    val isEmpty: Boolean get() = n == 0

    init {
        var first = Int.MAX_VALUE
        var last = -1
        for (i in 0 until path.size - 1) {
            val ax = path.x[i]; val ay = path.y[i]; val bx = path.x[i + 1]; val by = path.y[i + 1]
            val minX = min(ax, bx) - reach; val maxX = max(ax, bx) + reach
            val minY = min(ay, by) - reach; val maxY = max(ay, by) + reach
            if (maxX < gx0 || minX > gx0 + nx * cell || maxY < gy0 || minY > gy0 + ny * cell) continue
            first = min(first, i)
            last = max(last, i + 1)
            val c0 = ((minX - gx0) / cell).toInt().coerceIn(0, nx - 1)
            val c1 = ((maxX - gx0) / cell).toInt().coerceIn(0, nx - 1)
            val r0 = ((minY - gy0) / cell).toInt().coerceIn(0, ny - 1)
            val r1 = ((maxY - gy0) / cell).toInt().coerceIn(0, ny - 1)
            for (r in r0..r1) for (c in c0..c1) add(r * nx + c, i)
        }
        firstSample = if (last < 0) 0 else first
        lastSample = last
    }

    private fun add(cellIdx: Int, i: Int) {
        if (n == seg.size) {
            seg = seg.copyOf(n * 2)
            next = next.copyOf(n * 2)
        }
        seg[n] = i
        next[n] = heads[cellIdx]
        heads[cellIdx] = n
        n++
    }

    /** Nearest point of the route within [reach]; false when none. */
    fun nearest(x: Double, y: Double, hit: RouteHit): Boolean {
        val c = floor((x - gx0) / cell).toInt()
        val r = floor((y - gy0) / cell).toInt()
        if (c < 0 || r < 0 || c >= nx || r >= ny) return false
        var k = heads[r * nx + c]
        var best = Double.MAX_VALUE
        var bi = -1
        var bt = 0.0
        while (k >= 0) {
            val i = seg[k]
            val ax = path.x[i]; val ay = path.y[i]
            val vx = path.x[i + 1] - ax; val vy = path.y[i + 1] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            val ex = ax + vx * t - x
            val ey = ay + vy * t - y
            // Ranked by distance from the asphalt edge, not the centre line: where the width changes
            // (junctions, a wider town street) the nearest centre line is not always the nearest edge.
            val e = sqrt(ex * ex + ey * ey) - max(path.width[i], path.width[i + 1]) * 0.5
            if (e < best) { best = e; bi = i; bt = t }
            k = next[k]
        }
        if (bi < 0) return false
        val ax0 = path.x[bi]; val ay0 = path.y[bi]
        val d = hypot(ax0 + (path.x[bi + 1] - ax0) * bt - x, ay0 + (path.y[bi + 1] - ay0) * bt - y)
        if (d > reach) return false
        val ax = path.x[bi]; val ay = path.y[bi]
        val vx = path.x[bi + 1] - ax; val vy = path.y[bi + 1] - ay
        val l = hypot(vx, vy).coerceAtLeast(1e-9)
        hit.dist = d
        hit.dirX = vx / l
        hit.dirY = vy / l
        // left normal (-dy, dx)
        hit.lateral = ((x - ax) * (-hit.dirY) + (y - ay) * hit.dirX)
        hit.i = bi
        hit.t = bt
        hit.halfWidth = max(path.width[bi], path.width[bi + 1]) * 0.5
        hit.roadZ = path.z[bi] + (path.z[bi + 1] - path.z[bi]) * bt
        hit.s = path.distanceOf(bi) + (path.distanceOf(bi + 1) - path.distanceOf(bi)) * bt
        return true
    }
}

/** Polylines with a width (side roads, railways, water lines) bucketed for distance queries. */
internal class LineIndex(
    private val gx0: Double, private val gy0: Double, size: Double, private val reach: Double, private val cell: Double = 16.0,
) {
    private val nx = max(1, (size / cell).toInt() + 1)
    private val heads = IntArray(nx * nx) { -1 }
    private var next = IntArray(512)
    private var ref = IntArray(512)
    private var n = 0
    private val ax = ArrayList<Double>()
    private val ay = ArrayList<Double>()
    private val bx = ArrayList<Double>()
    private val by = ArrayList<Double>()
    private val hw = ArrayList<Double>()
    private val kind = ArrayList<Int>()

    fun add(x0: Double, y0: Double, x1: Double, y1: Double, halfWidth: Double, k: Int) {
        val id = ax.size
        ax += x0; ay += y0; bx += x1; by += y1; hw += halfWidth; kind += k
        val r = reach + halfWidth
        val c0 = ((min(x0, x1) - r - gx0) / cell).toInt().coerceIn(0, nx - 1)
        val c1 = ((max(x0, x1) + r - gx0) / cell).toInt().coerceIn(0, nx - 1)
        val r0 = ((min(y0, y1) - r - gy0) / cell).toInt().coerceIn(0, nx - 1)
        val r1 = ((max(y0, y1) + r - gy0) / cell).toInt().coerceIn(0, nx - 1)
        if (max(x0, x1) + r < gx0 || min(x0, x1) - r > gx0 + nx * cell || max(y0, y1) + r < gy0 || min(y0, y1) - r > gy0 + nx * cell) return
        for (rr in r0..r1) for (c in c0..c1) {
            if (n == ref.size) { ref = ref.copyOf(n * 2); next = next.copyOf(n * 2) }
            ref[n] = id
            next[n] = heads[rr * nx + c]
            heads[rr * nx + c] = n
            n++
        }
    }

    /** Distance from (x, y) to the nearest line's edge (negative inside), or [reach] when none is near. */
    fun edgeDistance(x: Double, y: Double, kindMask: Int = -1): Double {
        val c = floor((x - gx0) / cell).toInt()
        val r = floor((y - gy0) / cell).toInt()
        if (c < 0 || r < 0 || c >= nx || r >= nx) return reach
        var k = heads[r * nx + c]
        var best = reach
        while (k >= 0) {
            val id = ref[k]
            if ((1 shl kind[id]) and kindMask != 0) {
                val d = segDist(x, y, ax[id], ay[id], bx[id], by[id]) - hw[id]
                if (d < best) best = d
            }
            k = next[k]
        }
        return best
    }

    /** Direction (unit x, y) of the nearest line within [maxD] of its edge, or null. */
    fun nearestDir(x: Double, y: Double, maxD: Double, kindMask: Int = -1): DoubleArray? {
        val c = floor((x - gx0) / cell).toInt()
        val r = floor((y - gy0) / cell).toInt()
        if (c < 0 || r < 0 || c >= nx || r >= nx) return null
        var k = heads[r * nx + c]
        var best = maxD
        var out: DoubleArray? = null
        while (k >= 0) {
            val id = ref[k]
            if ((1 shl kind[id]) and kindMask != 0) {
                val d = segDist(x, y, ax[id], ay[id], bx[id], by[id]) - hw[id]
                if (d < best) {
                    best = d
                    val l = hypot(bx[id] - ax[id], by[id] - ay[id]).coerceAtLeast(1e-9)
                    out = doubleArrayOf((bx[id] - ax[id]) / l, (by[id] - ay[id]) / l, d)
                }
            }
            k = next[k]
        }
        return out
    }

    companion object {
        fun segDist(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
            val vx = bx - ax; val vy = by - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            return hypot(ax + vx * t - x, ay + vy * t - y)
        }
    }
}

/** A boolean raster over the tile (plus a margin) at [cellM] resolution. */
internal class Mask(val x0: Double, val y0: Double, val sizeM: Double, val cellM: Double) {
    val n = (sizeM / cellM).toInt()
    val bits = java.util.BitSet(n * n)

    fun get(x: Double, y: Double): Boolean {
        val c = floor((x - x0) / cellM).toInt()
        val r = floor((y - y0) / cellM).toInt()
        if (c < 0 || r < 0 || c >= n || r >= n) return false
        return bits[r * n + c]
    }

    /** Fills polygon rings (even-odd), coordinates absolute. [grow] dilates by whole cells. */
    fun fillRings(rings: List<DoubleArray>, grow: Int = 0) {
        var minY = Double.MAX_VALUE
        var maxY = -Double.MAX_VALUE
        for (r in rings) for (k in 1 until r.size step 2) { minY = min(minY, r[k]); maxY = max(maxY, r[k]) }
        val r0 = floor((minY - y0) / cellM).toInt().coerceAtLeast(0)
        val r1 = floor((maxY - y0) / cellM).toInt().coerceAtMost(n - 1)
        val xs = DoubleArray(64)
        for (row in r0..r1) {
            val yc = y0 + (row + 0.5) * cellM
            var cnt = 0
            var buf = xs
            for (ring in rings) {
                val m = ring.size / 2
                for (a in 0 until m) {
                    val b = (a + 1) % m
                    val ya = ring[a * 2 + 1]; val yb = ring[b * 2 + 1]
                    if ((ya <= yc && yb > yc) || (yb <= yc && ya > yc)) {
                        val xa = ring[a * 2]; val xb = ring[b * 2]
                        if (cnt == buf.size) buf = buf.copyOf(cnt * 2)
                        buf[cnt++] = xa + (yc - ya) / (yb - ya) * (xb - xa)
                    }
                }
            }
            if (cnt < 2) continue
            java.util.Arrays.sort(buf, 0, cnt)
            var k = 0
            while (k + 1 < cnt) {
                val c0 = floor((buf[k] - x0) / cellM + 0.5).toInt() - grow
                val c1 = floor((buf[k + 1] - x0) / cellM - 0.5).toInt() + grow
                for (rr in max(0, row - grow)..min(n - 1, row + grow)) {
                    val a = c0.coerceAtLeast(0)
                    val b = c1.coerceAtMost(n - 1)
                    if (b >= a) bits.set(rr * n + a, rr * n + b + 1)
                }
                k += 2
            }
        }
    }

    /** Marks a thick line. */
    fun line(xa: Double, ya: Double, xb: Double, yb: Double, halfWidth: Double) {
        val minX = min(xa, xb) - halfWidth; val maxX = max(xa, xb) + halfWidth
        val minY = min(ya, yb) - halfWidth; val maxY = max(ya, yb) + halfWidth
        val c0 = floor((minX - x0) / cellM).toInt().coerceAtLeast(0)
        val c1 = floor((maxX - x0) / cellM).toInt().coerceAtMost(n - 1)
        val r0 = floor((minY - y0) / cellM).toInt().coerceAtLeast(0)
        val r1 = floor((maxY - y0) / cellM).toInt().coerceAtMost(n - 1)
        for (r in r0..r1) for (c in c0..c1) {
            val x = x0 + (c + 0.5) * cellM
            val y = y0 + (r + 0.5) * cellM
            if (LineIndex.segDist(x, y, xa, ya, xb, yb) <= halfWidth) bits.set(r * n + c)
        }
    }
}

/**
 * Ground height: the carved road corridor where the renderer draws it (within the free
 * offsets of the cross-section), the tile's height grid elsewhere. Furniture on the verge and
 * trees on embankments must stand on what is drawn, not on the 10 m grid.
 */
internal class Ground(
    private val heights: HeightTile,
    private val path: RoutePath,
    private val sections: List<CorridorSection>,
) {
    private fun section(i: Int): CorridorSection? {
        for (s in sections) if (i >= s.firstSample && i < s.firstSample + s.count) return s
        return null
    }

    fun terrain(x: Double, y: Double): Double = heights.heightAt(x, y)

    /** Height at (x, y) given the nearest route point [hit] (or null when far from the road). */
    fun at(x: Double, y: Double, hit: RouteHit?): Double {
        if (hit == null) return terrain(x, y)
        val o = hit.beyondEdge
        if (o < 0) return hit.roadZ
        val i = if (hit.t < 0.5) hit.i else hit.i + 1
        val sec = section(i) ?: return terrain(x, y)
        val offs = sec.offsetsM
        val free = if (hit.left) sec.freeLeftAt(i) else sec.freeRightAt(i)
        if (free <= 0) return terrain(x, y)
        val last = min(free, offs.size) - 1
        if (o > offs[last]) {
            // Blend from the last free corridor offset into the terrain over 10 m.
            val zc = hit.roadZ + rel(sec, i, hit.left, last)
            val f = ((o - offs[last]) / 10.0).coerceIn(0.0, 1.0)
            return zc + (terrain(x, y) - zc) * f
        }
        var k = 0
        while (k < last && offs[k + 1] < o) k++
        val a = offs[k].toDouble()
        val b = offs[min(k + 1, last)].toDouble()
        val f = if (b > a) ((o - a) / (b - a)).coerceIn(0.0, 1.0) else 0.0
        val za = rel(sec, i, hit.left, k)
        val zb = rel(sec, i, hit.left, min(k + 1, last))
        return hit.roadZ + za + (zb - za) * f
    }

    /** Height of the cross-section at offset index [k] relative to the road, on one side. */
    fun relAt(i: Int, left: Boolean, k: Int): Double? {
        val sec = section(i) ?: return null
        val free = if (left) sec.freeLeftAt(i) else sec.freeRightAt(i)
        if (k >= free) return null
        return rel(sec, i, left, k)
    }

    fun freeAt(i: Int, left: Boolean): Int {
        val sec = section(i) ?: return 0
        return if (left) sec.freeLeftAt(i) else sec.freeRightAt(i)
    }

    fun offsets(i: Int): FloatArray? = section(i)?.offsetsM

    private fun rel(sec: CorridorSection, i: Int, left: Boolean, k: Int): Double =
        (if (left) sec.leftAt(i, k) else sec.rightAt(i, k)).toDouble()

    /** Slope magnitude (rise per metre) of the terrain grid at (x, y). */
    fun slope(x: Double, y: Double): Double {
        val d = heights.spacingM
        val gx = (terrain(x + d, y) - terrain(x - d, y)) / (2 * d)
        val gy = (terrain(x, y + d) - terrain(x, y - d)) / (2 * d)
        return hypot(gx, gy)
    }
}

/**
 * Approximate distance (m) from each land cover cell centre of a tile to the route, by a chamfer
 * transform on a grid that extends [margin] m around the tile: cheap and plenty for density falloff.
 */
internal class RouteDistanceField(path: RoutePath, val x0: Double, val y0: Double, val sizeM: Double, val cells: Int, margin: Double) {
    private val cell = sizeM / cells
    private val pad = (margin / cell).toInt()
    private val n = cells + 2 * pad
    private val d = FloatArray(n * n) { Float.MAX_VALUE }

    init {
        val gx0 = x0 - pad * cell
        val gy0 = y0 - pad * cell
        // Seed with the route, sampled at half a cell.
        for (i in 0 until path.size - 1) {
            val ax = path.x[i]; val ay = path.y[i]
            val bx = path.x[i + 1]; val by = path.y[i + 1]
            if (max(ax, bx) < gx0 - cell || min(ax, bx) > gx0 + n * cell + cell || max(ay, by) < gy0 - cell || min(ay, by) > gy0 + n * cell + cell) continue
            val steps = max(1, (hypot(bx - ax, by - ay) / (cell * 0.5)).toInt())
            for (k in 0..steps) {
                val x = ax + (bx - ax) * k / steps
                val y = ay + (by - ay) * k / steps
                val c = floor((x - gx0) / cell).toInt()
                val r = floor((y - gy0) / cell).toInt()
                if (c < 0 || r < 0 || c >= n || r >= n) continue
                val cx = gx0 + (c + 0.5) * cell
                val cy = gy0 + (r + 0.5) * cell
                val dd = hypot(x - cx, y - cy).toFloat()
                if (dd < d[r * n + c]) d[r * n + c] = dd
            }
        }
        val a = cell.toFloat()
        val b = (cell * 1.41421356).toFloat()
        for (r in 0 until n) for (c in 0 until n) {
            var v = d[r * n + c]
            if (c > 0) v = min(v, d[r * n + c - 1] + a)
            if (r > 0) {
                v = min(v, d[(r - 1) * n + c] + a)
                if (c > 0) v = min(v, d[(r - 1) * n + c - 1] + b)
                if (c < n - 1) v = min(v, d[(r - 1) * n + c + 1] + b)
            }
            d[r * n + c] = v
        }
        for (r in n - 1 downTo 0) for (c in n - 1 downTo 0) {
            var v = d[r * n + c]
            if (c < n - 1) v = min(v, d[r * n + c + 1] + a)
            if (r < n - 1) {
                v = min(v, d[(r + 1) * n + c] + a)
                if (c < n - 1) v = min(v, d[(r + 1) * n + c + 1] + b)
                if (c > 0) v = min(v, d[(r + 1) * n + c - 1] + b)
            }
            d[r * n + c] = v
        }
    }

    /** Distance at a point (pack coordinates), nearest cell. */
    fun at(x: Double, y: Double): Double {
        val c = floor((x - x0) / cell).toInt() + pad
        val r = floor((y - y0) / cell).toInt() + pad
        if (c < 0 || r < 0 || c >= n || r >= n) return 1e6
        val v = d[r * n + c]
        return if (v == Float.MAX_VALUE) 1e6 else v.toDouble()
    }
}

@Suppress("unused")
private fun absd(v: Double) = abs(v)
