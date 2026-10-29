package app.rollentrainer.ui.world.geo.scenery

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Planar polygon helpers for building footprints (x, y pairs in a DoubleArray). */
internal object Geom2 {
    fun signedArea(p: DoubleArray): Double {
        val n = p.size / 2
        var a = 0.0
        for (i in 0 until n) {
            val k = (i + 1) % n
            a += p[i * 2] * p[k * 2 + 1] - p[k * 2] * p[i * 2 + 1]
        }
        return a / 2
    }

    fun reversed(p: DoubleArray): DoubleArray {
        val n = p.size / 2
        return DoubleArray(p.size) { i -> val k = n - 1 - i / 2; p[k * 2 + i % 2] }
    }

    /**
     * Drops near-duplicate vertices (< [minEdge] m) and nearly collinear ones (turn < [minTurn]
     * rad): OSM footprints carry many of both, and each costs a wall quad.
     */
    fun simplify(p: DoubleArray, minEdge: Double = 0.35, minTurn: Double = 0.1): DoubleArray {
        var pts = p
        for (pass in 0 until 3) {
            val n = pts.size / 2
            if (n <= 3) return pts
            val keep = ArrayList<Int>(n)
            for (i in 0 until n) {
                val prev = if (keep.isEmpty()) (n - 1) else keep.last()
                val nx = (i + 1) % n
                val ax = pts[prev * 2]; val ay = pts[prev * 2 + 1]
                val bx = pts[i * 2]; val by = pts[i * 2 + 1]
                val cx = pts[nx * 2]; val cy = pts[nx * 2 + 1]
                val l1 = hypot(bx - ax, by - ay)
                val l2 = hypot(cx - bx, cy - by)
                if (l1 < minEdge) continue
                val cross = (bx - ax) * (cy - by) - (by - ay) * (cx - bx)
                val sin = if (l1 * l2 > 1e-9) cross / (l1 * l2) else 0.0
                val dot = (bx - ax) * (cx - bx) + (by - ay) * (cy - by)
                if (abs(sin) < minTurn && dot > 0) continue
                keep += i
            }
            if (keep.size < 3) return pts
            val out = DoubleArray(keep.size * 2)
            for ((k, i) in keep.withIndex()) { out[k * 2] = pts[i * 2]; out[k * 2 + 1] = pts[i * 2 + 1] }
            if (out.size == pts.size) return out
            pts = out
        }
        return pts
    }

    fun convexHull(p: DoubleArray): DoubleArray {
        val n = p.size / 2
        val idx = (0 until n).sortedWith(compareBy({ p[it * 2] }, { p[it * 2 + 1] }))
        val h = IntArray(2 * n + 1)
        var k = 0
        fun cross(o: Int, a: Int, b: Int) = (p[a * 2] - p[o * 2]) * (p[b * 2 + 1] - p[o * 2 + 1]) - (p[a * 2 + 1] - p[o * 2 + 1]) * (p[b * 2] - p[o * 2])
        for (i in idx) { while (k >= 2 && cross(h[k - 2], h[k - 1], i) <= 0) k--; h[k++] = i }
        val t = k + 1
        for (j in idx.indices.reversed()) {
            val i = idx[j]
            if (j == idx.size - 1) continue
            while (k >= t && cross(h[k - 2], h[k - 1], i) <= 0) k--
            h[k++] = i
        }
        val m = max(1, k - 1)
        return DoubleArray(m * 2) { i -> p[h[i / 2] * 2 + i % 2] }
    }

    /** Minimum-area bounding rectangle: centre, long axis direction, half lengths (a ≥ b). */
    class Obb(val cx: Double, val cy: Double, val ux: Double, val uy: Double, val a: Double, val b: Double) {
        val vx get() = -uy
        val vy get() = ux
        val area get() = 4 * a * b
    }

    fun obb(p: DoubleArray): Obb {
        val h = convexHull(p)
        val n = h.size / 2
        var best: Obb? = null
        for (i in 0 until n) {
            val k = (i + 1) % n
            var ux = h[k * 2] - h[i * 2]
            var uy = h[k * 2 + 1] - h[i * 2 + 1]
            val l = hypot(ux, uy)
            if (l < 1e-6) continue
            ux /= l; uy /= l
            var u0 = Double.MAX_VALUE; var u1 = -Double.MAX_VALUE; var v0 = Double.MAX_VALUE; var v1 = -Double.MAX_VALUE
            for (q in 0 until n) {
                val x = h[q * 2]; val y = h[q * 2 + 1]
                val u = x * ux + y * uy
                val v = -x * uy + y * ux
                u0 = min(u0, u); u1 = max(u1, u); v0 = min(v0, v); v1 = max(v1, v)
            }
            val area = (u1 - u0) * (v1 - v0)
            if (best == null || area < best.area - 1e-9) {
                val cu = (u0 + u1) / 2; val cv = (v0 + v1) / 2
                val cx = cu * ux - cv * uy
                val cy = cu * uy + cv * ux
                best = if (u1 - u0 >= v1 - v0) Obb(cx, cy, ux, uy, (u1 - u0) / 2, (v1 - v0) / 2)
                else Obb(cx, cy, -uy, ux, (v1 - v0) / 2, (u1 - u0) / 2)
            }
        }
        return best ?: Obb(p[0], p[1], 1.0, 0.0, 0.5, 0.5)
    }

    fun inside(x: Double, y: Double, p: DoubleArray): Boolean {
        val n = p.size / 2
        var c = false
        var j = n - 1
        for (i in 0 until n) {
            val xi = p[i * 2]; val yi = p[i * 2 + 1]
            val xj = p[j * 2]; val yj = p[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }

    /** Ear-clipping triangulation of a simple counter-clockwise polygon; indices into its vertices. */
    fun triangulate(p: DoubleArray): IntArray {
        val n = p.size / 2
        if (n < 3) return IntArray(0)
        val idx = ArrayList<Int>(n).apply { for (i in 0 until n) add(i) }
        val out = ArrayList<Int>((n - 2) * 3)
        fun x(i: Int) = p[i * 2]
        fun y(i: Int) = p[i * 2 + 1]
        fun convex(a: Int, b: Int, c: Int) = (x(b) - x(a)) * (y(c) - y(a)) - (y(b) - y(a)) * (x(c) - x(a)) > 1e-10
        fun inTri(px: Double, py: Double, a: Int, b: Int, c: Int): Boolean {
            val d1 = (px - x(b)) * (y(a) - y(b)) - (x(a) - x(b)) * (py - y(b))
            val d2 = (px - x(c)) * (y(b) - y(c)) - (x(b) - x(c)) * (py - y(c))
            val d3 = (px - x(a)) * (y(c) - y(a)) - (x(c) - x(a)) * (py - y(a))
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            return !(neg && pos)
        }
        var guard = 0
        var i = 0
        while (idx.size > 3 && guard < n * n) {
            guard++
            val m = idx.size
            val a = idx[(i + m - 1) % m]; val b = idx[i % m]; val c = idx[(i + 1) % m]
            var ear = convex(a, b, c)
            if (ear) {
                for (q in idx) {
                    if (q == a || q == b || q == c) continue
                    if (inTri(x(q), y(q), a, b, c)) { ear = false; break }
                }
            }
            if (ear) {
                out += a; out += b; out += c
                idx.removeAt(i % m)
                i = if (idx.isEmpty()) 0 else (i % m) % idx.size
            } else i = (i + 1) % m
        }
        if (idx.size == 3) { out += idx[0]; out += idx[1]; out += idx[2] }
        if (idx.size > 3) {
            // Degenerate input (self-touching ring): fan the rest; a slightly wrong flat roof beats a hole.
            for (k in 1 until idx.size - 1) { out += idx[0]; out += idx[k]; out += idx[k + 1] }
        }
        return out.toIntArray()
    }

    fun len(x: Double, y: Double) = sqrt(x * x + y * y)
}
