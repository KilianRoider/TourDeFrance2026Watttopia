package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.geo.GeoLayer
import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.geo.TileKey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The pack's building footprints, as W2's scenery builds them (same skip rules as
 * scenery/Buildings.run: no roof-only shelters, rings of at least three points, at least 5 m²), so
 * barriers, spectators and camps stay out of the walls standing beside the road. Read per finest
 * BUILDINGS tile, indexed in 8 m cells, kept in a small LRU cache; deterministic, pure data.
 */
internal class BuildingLookup(private val pack: GeoPack) {
    private val lod = pack.meta.lods.minOf { it.lod }
    private val tileM = pack.lod(lod).tileM

    /** One footprint in pack coordinates with its bounding box. */
    private class Fp(val xs: DoubleArray, val ys: DoubleArray, val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    /**
     * Footprints stored in one tile (by the pipeline; a ring may reach a little beyond the tile),
     * bucketed in cells over the tile plus [MARGIN] so a query never needs the ring's own tile.
     */
    private class TileIndex(val fps: List<Fp>, val gx0: Double, val gy0: Double, val nx: Int, val ny: Int, val cells: Array<IntArray?>)

    private val cache = object : LinkedHashMap<TileKey, TileIndex?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, TileIndex?>?) = size > 24
    }

    /** Whether (x, y) lies inside a footprint or within [r] metres of one. */
    fun near(x: Double, y: Double, r: Double): Boolean {
        // A footprint stored in a neighbouring tile can reach across the border: look there too
        // when the point is within the margin of it.
        val tx = floor(x / tileM).toInt()
        val ty = floor(y / tileM).toInt()
        val fx = x / tileM - tx
        val fy = y / tileM - ty
        val m = (MARGIN + r) / tileM
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == -1 && fx > m) continue
            if (dx == 1 && fx < 1 - m) continue
            if (dy == -1 && fy > m) continue
            if (dy == 1 && fy < 1 - m) continue
            val t = tile(TileKey(lod, tx + dx, ty + dy)) ?: continue
            if (nearIn(t, x, y, r)) return true
        }
        return false
    }

    private fun nearIn(t: TileIndex, x: Double, y: Double, r: Double): Boolean {
        val c0 = floor((x - r - t.gx0) / CELL).toInt().coerceAtLeast(0)
        val c1 = floor((x + r - t.gx0) / CELL).toInt().coerceAtMost(t.nx - 1)
        val r0 = floor((y - r - t.gy0) / CELL).toInt().coerceAtLeast(0)
        val r1 = floor((y + r - t.gy0) / CELL).toInt().coerceAtMost(t.ny - 1)
        for (cy in r0..r1) for (cx in c0..c1) {
            val list = t.cells[cy * t.nx + cx] ?: continue
            for (k in list) {
                val f = t.fps[k]
                if (x < f.x0 - r || x > f.x1 + r || y < f.y0 - r || y > f.y1 + r) continue
                if (inside(f, x, y) || edgeDistance(f, x, y) < r) return true
            }
        }
        return false
    }

    private fun tile(key: TileKey): TileIndex? = synchronized(cache) {
        if (cache.containsKey(key)) return cache[key]
        val idx = if (pack.has(GeoLayer.BUILDINGS, key)) pack.buildings(key)?.let { index(it.originX, it.originY, it.buildings) } else null
        cache[key] = idx
        idx
    }

    private fun index(ox: Double, oy: Double, list: List<app.rollentrainer.core.geo.Building>): TileIndex {
        val fps = ArrayList<Fp>(list.size)
        for (b in list) {
            if (b.buildingClass == ROOF_ONLY || b.ring.size < 6) continue
            val n = b.ring.size / 2
            val xs = DoubleArray(n) { ox + b.ring[it * 2] }
            val ys = DoubleArray(n) { oy + b.ring[it * 2 + 1] }
            var area = 0.0
            for (i in 0 until n) { val j = (i + 1) % n; area += xs[i] * ys[j] - xs[j] * ys[i] }
            if (abs(area) / 2 < 5.0) continue
            fps += Fp(xs, ys, xs.min(), ys.min(), xs.max(), ys.max())
        }
        val gx0 = ox - MARGIN
        val gy0 = oy - MARGIN
        val nx = ((tileM + 2 * MARGIN) / CELL).toInt() + 1
        val ny = nx
        val buckets = arrayOfNulls<ArrayList<Int>>(nx * ny)
        for ((k, f) in fps.withIndex()) {
            val c0 = floor((f.x0 - gx0) / CELL).toInt().coerceIn(0, nx - 1)
            val c1 = floor((f.x1 - gx0) / CELL).toInt().coerceIn(0, nx - 1)
            val r0 = floor((f.y0 - gy0) / CELL).toInt().coerceIn(0, ny - 1)
            val r1 = floor((f.y1 - gy0) / CELL).toInt().coerceIn(0, ny - 1)
            for (r in r0..r1) for (c in c0..c1) (buckets[r * nx + c] ?: ArrayList<Int>(4).also { buckets[r * nx + c] = it }).add(k)
        }
        return TileIndex(fps, gx0, gy0, nx, ny, Array(nx * ny) { buckets[it]?.toIntArray() })
    }

    companion object {
        /** scenery/Buildings.ROOF_ONLY: shelters without walls (W2 builds nothing for them). */
        const val ROOF_ONLY = 26
        private const val CELL = 8.0
        /** How far a footprint may reach beyond its tile, plus the largest query radius. */
        private const val MARGIN = 60.0

        private fun inside(f: Fp, x: Double, y: Double): Boolean {
            var c = false
            val n = f.xs.size
            var j = n - 1
            for (i in 0 until n) {
                if ((f.ys[i] > y) != (f.ys[j] > y) && x < (f.xs[j] - f.xs[i]) * (y - f.ys[i]) / (f.ys[j] - f.ys[i]) + f.xs[i]) c = !c
                j = i
            }
            return c
        }

        private fun edgeDistance(f: Fp, x: Double, y: Double): Double {
            var best = Double.MAX_VALUE
            val n = f.xs.size
            for (i in 0 until n) {
                val j = (i + 1) % n
                val ax = f.xs[i]; val ay = f.ys[i]
                val vx = f.xs[j] - ax; val vy = f.ys[j] - ay
                val l2 = vx * vx + vy * vy
                val t = if (l2 > 1e-12) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
                val dx = ax + vx * t - x; val dy = ay + vy * t - y
                best = min(best, sqrt(dx * dx + dy * dy))
            }
            return max(0.0, best)
        }
    }
}
