package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.CorridorSection
import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.geo.HeightTile
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.geo.TileKey
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Path segments (i, i + 1) bucketed by the LOD-0 tile they come near, so per-tile work (lowering the
 * terrain under the road, the corridor strip, ground queries) looks only at the road nearby.
 */
internal class PathIndex(val path: RoutePath, val tileM: Double, val marginM: Double = 45.0) {
    private val buckets = HashMap<Long, IntArray>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (i in 0 until path.size - 1) {
            val x0 = min(path.x[i], path.x[i + 1]) - marginM
            val x1 = max(path.x[i], path.x[i + 1]) + marginM
            val y0 = min(path.y[i], path.y[i + 1]) - marginM
            val y1 = max(path.y[i], path.y[i + 1]) + marginM
            for (tx in floor(x0 / tileM).toInt()..floor(x1 / tileM).toInt()) for (ty in floor(y0 / tileM).toInt()..floor(y1 / tileM).toInt()) {
                tmp.getOrPut(key(tx, ty)) { ArrayList() }.add(i)
            }
        }
        for ((k, v) in tmp) buckets[k] = v.toIntArray()
    }

    private fun key(tx: Int, ty: Int): Long = (tx.toLong() shl 32) or (ty.toLong() and 0xFFFFFFFFL)

    /** Segments whose bounding box (plus margin) touches LOD-0 tile (tx, ty). */
    fun segments(tx: Int, ty: Int): IntArray = buckets[key(tx, ty)] ?: EMPTY

    /** Segments near a point (those of its tile). */
    fun segmentsNear(x: Double, y: Double): IntArray = segments(floor(x / tileM).toInt(), floor(y / tileM).toInt())

    /**
     * The nearest road point to (x, y) among the indexed segments: sample index, fraction, signed lateral
     * distance (positive = left of the direction of travel) and the distance; null when no segment is near.
     */
    fun nearest(x: Double, y: Double): Nearest? {
        val segs = segmentsNear(x, y)
        var best = Double.MAX_VALUE
        var bi = -1
        var bt = 0.0
        var side = 0.0
        for (i in segs) {
            val ax = path.x[i]; val ay = path.y[i]
            val vx = path.x[i + 1] - ax; val vy = path.y[i + 1] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) (((x - ax) * vx + (y - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            val ex = x - (ax + vx * t); val ey = y - (ay + vy * t)
            val d = ex * ex + ey * ey
            if (d < best) {
                best = d; bi = i; bt = t
                side = vx * ey - vy * ex
            }
        }
        if (bi < 0) return null
        val dist = sqrt(best)
        return Nearest(bi, bt, if (side >= 0) dist else -dist, dist)
    }

    class Nearest(val index: Int, val t: Double, val lateral: Double, val dist: Double)

    companion object {
        private val EMPTY = IntArray(0)
    }
}

/** Decoded height tiles, least recently used dropped first (streaming worker only). */
internal class HeightCache(private val pack: GeoPack, private val capacity: Int = 48) {
    private val map = object : LinkedHashMap<TileKey, HeightTile?>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, HeightTile?>?): Boolean = size > capacity
    }

    fun get(key: TileKey): HeightTile? {
        if (map.containsKey(key)) return map[key]
        val h = pack.heights(key)
        map[key] = h
        return h
    }

    /**
     * Height of the LOD-0 terrain at (x, y) on the exact triangles the renderer draws (diagonal from the
     * north-west to the south-east corner of each cell), null outside the pack's LOD-0 tiles.
     */
    fun exactHeight(x: Double, y: Double): Double? {
        val tileM = pack.lod(0).tileM
        val key = TileKey(0, floor(x / tileM).toInt(), floor(y / tileM).toInt())
        val h = get(key) ?: return null
        return exactHeight(h, x, y)
    }

    companion object {
        fun exactHeight(h: HeightTile, x: Double, y: Double): Double {
            val s = h.spacingM
            val fx = ((x - h.originX) / s).coerceIn(0.0, h.cells.toDouble())
            val fr = ((h.originY + h.sizeM - y) / s).coerceIn(0.0, h.cells.toDouble())
            val c = min(fx.toInt(), h.cells - 1)
            val r = min(fr.toInt(), h.cells - 1)
            val u = fx - c
            val v = fr - r
            val a = h.at(c, r).toDouble()
            val c1 = h.at(c + 1, r + 1).toDouble()
            // Triangles (r,c)-(r+1,c)-(r+1,c+1) and (r,c)-(r+1,c+1)-(r,c+1).
            return if (v >= u) {
                val b = h.at(c, r + 1).toDouble()
                a + (b - a) * (v - u) + (c1 - a) * u
            } else {
                val d = h.at(c + 1, r).toDouble()
                a + (d - a) * (u - v) + (c1 - a) * v
            }
        }
    }
}

/** Decoded corridor sections (streaming worker only). */
internal class CorridorCache(private val pack: GeoPack) {
    private val per = pack.meta.corridor?.sectionSamples ?: 2048
    private val map = object : LinkedHashMap<Int, CorridorSection?>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, CorridorSection?>?): Boolean = size > 6
    }
    val offsets: FloatArray? by lazy { section(0)?.offsetsM }

    fun section(index: Int): CorridorSection? {
        if (map.containsKey(index)) return map[index]
        val c = runCatching { pack.corridor(index) }.getOrNull()
        map[index] = c
        return c
    }

    /** The section holding path sample [i], or null when the pack has no corridor there. */
    fun at(i: Int): CorridorSection? = section(i / per)?.takeIf { i >= it.firstSample && i < it.firstSample + it.count }
}

/**
 * The visible ground: the road surface on the road, the corridor strip (verges, cuts, embankments)
 * beside it, the terrain grid elsewhere. What generators should stand spectators and barriers on.
 */
/**
 * Heights of the road's cross-section that more than the road builder needs: the surface lift and the
 * town kerb and pavement. RoadBuilder builds its mesh from these, [GroundSampler] hands the same heights
 * to W2 and W3, which stand things on them (spectators on the pavement, benches, lamp posts).
 */
internal object RoadProfile {
    /** The road surface lies this much above the path's elevation. */
    const val LIFT = 0.02f
    /** Kerb width and height above the road surface, the pavement behind it and its rise towards the houses. */
    const val KERB_W = 0.16f
    const val KERB_H = 0.14f
    const val PAVEMENT_W = 1.9f
    const val PAVEMENT_RISE = 0.04f

    /** Height above the road surface of the kerb or pavement [e] metres beyond the carriageway edge, NaN beyond it. */
    fun kerbHeight(e: Double): Double = when {
        e < 0 -> 0.0
        e <= KERB_W -> KERB_H.toDouble()
        e <= KERB_W + PAVEMENT_W -> KERB_H + PAVEMENT_RISE * (e - KERB_W) / PAVEMENT_W
        else -> Double.NaN
    }
}

internal class GroundSampler(
    private val path: RoutePath, private val index: PathIndex, private val heights: HeightCache, private val corridor: CorridorCache,
    /** Per path sample: kerbs and pavements there (RoadBuilder.kerbSamples); null = none anywhere. */
    private val kerbs: BooleanArray? = null,
) {
    fun height(x: Double, y: Double): Double? {
        val grid = heights.exactHeight(x, y)
        val n = index.nearest(x, y) ?: return grid
        val i = n.index
        val roadZ = path.z[i] + (path.z[i + 1] - path.z[i]) * n.t
        val hw = path.width[i] / 2.0
        val e = abs(n.lateral) - hw
        if (e <= 0) return roadZ
        val k = if (n.t < 0.5) i else i + 1
        // In towns the road builder's kerb and pavement are the ground next to the road.
        if (kerbs != null && kerbs[k]) {
            val kh = RoadProfile.kerbHeight(e)
            if (!kh.isNaN()) return roadZ + RoadProfile.LIFT + kh
        }
        val sec = corridor.at(k) ?: return grid
        val offs = sec.offsetsM
        val left = n.lateral > 0
        val free = if (left) sec.freeLeftAt(k) else sec.freeRightAt(k)
        if (free <= 0 || (path.flags[k].toInt() and (PathFlags.BRIDGE or PathFlags.TUNNEL)) != 0) return grid
        val last = min(free, offs.size) - 1
        if (e > offs[last]) return grid
        var j = 0
        while (j < last && offs[j + 1] < e) j++
        val j1 = min(j + 1, last)
        val f = if (j1 > j) ((e - offs[j]) / (offs[j1] - offs[j])).coerceIn(0.0, 1.0) else 0.0
        val h0 = if (left) sec.leftAt(k, j) else sec.rightAt(k, j)
        val h1 = if (left) sec.leftAt(k, j1) else sec.rightAt(k, j1)
        return path.z[k] + h0 + (h1 - h0) * f
    }

    @Suppress("unused")
    fun distanceToRoad(x: Double, y: Double): Double = index.nearest(x, y)?.let { abs(it.lateral) - path.width[it.index] / 2.0 } ?: Double.MAX_VALUE

    @Suppress("unused")
    private fun len(ax: Double, ay: Double) = hypot(ax, ay)
}
