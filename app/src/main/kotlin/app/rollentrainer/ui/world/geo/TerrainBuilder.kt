package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.geo.HeightTile
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.RoadClass
import app.rollentrainer.core.geo.RoadTile
import app.rollentrainer.core.geo.TileKey
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The fixed topology of every terrain tile (all LODs have 129 x 129 height samples): the grid, skirts
 * along the lines between 4 x 4 sub-blocks, and index ranges per sub-block at three resolutions. One
 * index buffer serves all tiles; a tile draws only the sub-blocks that no finer tile covers, and the
 * skirts hide the cracks where resolutions meet.
 */
internal object TerrainGrid {
    const val CELLS = 128
    const val N = CELLS + 1
    const val BLOCKS = 4
    const val BLOCK = CELLS / BLOCKS
    /** Grid vertices, then skirt copies of the 5 vertical lines (c = 0, 32, ..) and 5 horizontal lines. */
    const val SKIRT_V = N * N
    const val SKIRT_H = SKIRT_V + (BLOCKS + 1) * N
    const val VERTICES = SKIRT_H + (BLOCKS + 1) * N
    const val STRIDE = 16
    val STEPS = intArrayOf(1, 2, 4)

    /** Index offset and count (in indices) per [resolution][block], blocks row-major from the north-west. */
    val rangeStart = Array(3) { IntArray(BLOCKS * BLOCKS) }
    val rangeCount = Array(3) { IntArray(BLOCKS * BLOCKS) }
    val indices: ShortArray

    init {
        val out = ArrayList<Int>(200_000)
        fun v(c: Int, r: Int) = r * N + c
        for ((res, step) in STEPS.withIndex()) {
            for (br in 0 until BLOCKS) for (bc in 0 until BLOCKS) {
                val start = out.size
                val r0 = br * BLOCK
                val c0 = bc * BLOCK
                var r = r0
                while (r < r0 + BLOCK) {
                    var c = c0
                    while (c < c0 + BLOCK) {
                        val a = v(c, r); val b = v(c, r + step); val cc = v(c + step, r + step); val d = v(c + step, r)
                        out += a; out += b; out += cc
                        out += a; out += cc; out += d
                        c += step
                    }
                    r += step
                }
                // Skirts on the four edges of the block, facing outwards.
                var c = c0
                while (c < c0 + BLOCK) {
                    // North edge (row r0, horizontal line br).
                    val tn0 = v(c, r0); val tn1 = v(c + step, r0)
                    val sn0 = SKIRT_H + br * N + c; val sn1 = SKIRT_H + br * N + c + step
                    out += tn0; out += tn1; out += sn1; out += tn0; out += sn1; out += sn0
                    // South edge (row r0 + 32, horizontal line br + 1).
                    val ts0 = v(c, r0 + BLOCK); val ts1 = v(c + step, r0 + BLOCK)
                    val ss0 = SKIRT_H + (br + 1) * N + c; val ss1 = SKIRT_H + (br + 1) * N + c + step
                    out += ts1; out += ts0; out += ss0; out += ts1; out += ss0; out += ss1
                    c += step
                }
                r = r0
                while (r < r0 + BLOCK) {
                    // West edge (column c0, vertical line bc).
                    val tw0 = v(c0, r); val tw1 = v(c0, r + step)
                    val sw0 = SKIRT_V + bc * N + r; val sw1 = SKIRT_V + bc * N + r + step
                    out += tw1; out += tw0; out += sw0; out += tw1; out += sw0; out += sw1
                    // East edge (column c0 + 32, vertical line bc + 1).
                    val te0 = v(c0 + BLOCK, r); val te1 = v(c0 + BLOCK, r + step)
                    val se0 = SKIRT_V + (bc + 1) * N + r; val se1 = SKIRT_V + (bc + 1) * N + r + step
                    out += te0; out += te1; out += se1; out += te0; out += se1; out += se0
                    r += step
                }
                rangeStart[res][br * BLOCKS + bc] = start
                rangeCount[res][br * BLOCKS + bc] = out.size - start
            }
        }
        indices = ShortArray(out.size) { out[it].toShort() }
    }
}

/** A terrain tile built on the worker, ready for upload: vertices (16 bytes each) and optional layers. */
internal class TerrainCpu(
    val key: TileKey,
    /** GL world position of the tile's south-west corner (y = 0: heights are absolute). */
    val anchorX: Double,
    val anchorZ: Double,
    val sizeM: Float,
    val vertices: ByteBuffer,
    val vertexCount: Int,
    /** Corridor strip triangles (LOD 0), 32-bit indices into [vertices]; empty elsewhere. */
    val corridorIndices: IntArray,
    val blockMinY: FloatArray,
    val blockMaxY: FloatArray,
    val minY: Float,
    val maxY: Float,
    /** Satellite colour, RGBA rows from the north (decoded), with its size. */
    val image: ByteBuffer?,
    val imageSize: Int,
    val landCover: ByteArray?,
    val landCoverCells: Int,
    /** Side roads, tracks and railways (LOD 0) in the road layout, anchored like the tile. */
    val sideRoads: MeshBuffer?,
    val bytes: Int,
)

/** Builds terrain tiles on the streaming worker. */
internal class TerrainBuilder(
    private val pack: GeoPack,
    private val heights: HeightCache,
    private val corridor: CorridorCache,
    private val index: PathIndex,
) {
    private val path = pack.path

    fun build(key: TileKey, image: ByteBuffer?, imageSize: Int): TerrainCpu? {
        val h = heights.get(key) ?: return null
        require(h.cells == TerrainGrid.CELLS) { "unexpected grid ${h.cells}" }
        val n = TerrainGrid.N
        val s = h.spacingM
        val tile = h.sizeM
        val hs = FloatArray(n * n) { h.heights[it] }
        val roadDist = FloatArray(n * n) { 99f }
        if (key.lod == 0) lowerUnderRoad(h, hs, roadDist)

        // Neighbour samples so normals are continuous across tile borders.
        val west = heights.get(TileKey(key.lod, key.tx - 1, key.ty))
        val east = heights.get(TileKey(key.lod, key.tx + 1, key.ty))
        val north = heights.get(TileKey(key.lod, key.tx, key.ty + 1))
        val south = heights.get(TileKey(key.lod, key.tx, key.ty - 1))
        fun hAt(c: Int, r: Int): Float = when {
            c < 0 -> west?.at(n - 2, r) ?: hs[r * n]
            c >= n -> east?.at(1, r) ?: hs[r * n + n - 1]
            r < 0 -> north?.at(c, n - 2) ?: hs[c]
            r >= n -> south?.at(c, 1) ?: hs[(n - 1) * n + c]
            else -> hs[r * n + c]
        }

        val strip = if (key.lod == 0) CorridorStrip(h) else null
        val stripVerts = strip?.vertexCount ?: 0
        val total = TerrainGrid.VERTICES + stripVerts
        val buf = ByteBuffer.allocateDirect(total * TerrainGrid.STRIDE).order(ByteOrder.nativeOrder())
        val skirt = when (key.lod) { 0 -> 25f; 1 -> 90f; else -> 260f }
        val blockMin = FloatArray(16) { Float.MAX_VALUE }
        val blockMax = FloatArray(16) { -Float.MAX_VALUE }
        val nrm = ByteArray(n * n * 3)
        for (r in 0 until n) for (c in 0 until n) {
            val dx = ((hAt(c + 1, r) - hAt(c - 1, r)) / (2 * s)).toFloat()
            val dz = ((hAt(c, r + 1) - hAt(c, r - 1)) / (2 * s)).toFloat()
            val l = sqrt(dx * dx + 1 + dz * dz)
            val o = (r * n + c) * 3
            nrm[o] = packNormal(-dx / l); nrm[o + 1] = packNormal(1 / l); nrm[o + 2] = packNormal(-dz / l)
        }
        fun put(x: Float, y: Float, z: Float, gi: Int, dist: Float) {
            buf.putFloat(x); buf.putFloat(y); buf.putFloat(z)
            buf.put(nrm[gi * 3]); buf.put(nrm[gi * 3 + 1]); buf.put(nrm[gi * 3 + 2])
            buf.put((dist / 12.7f * 127f).coerceIn(0f, 127f).toInt().toByte())
        }
        for (r in 0 until n) for (c in 0 until n) {
            val y = hs[r * n + c]
            put((c * s).toFloat(), y, (r * s - tile).toFloat(), r * n + c, roadDist[r * n + c])
            val b = min(r / TerrainGrid.BLOCK, 3) * 4 + min(c / TerrainGrid.BLOCK, 3)
            blockMin[b] = min(blockMin[b], y); blockMax[b] = max(blockMax[b], y)
            // Vertices on block borders also belong to the neighbouring block.
            if (r % TerrainGrid.BLOCK == 0 && r > 0) { val b2 = (r / TerrainGrid.BLOCK - 1) * 4 + min(c / TerrainGrid.BLOCK, 3); blockMin[b2] = min(blockMin[b2], y); blockMax[b2] = max(blockMax[b2], y) }
            if (c % TerrainGrid.BLOCK == 0 && c > 0) { val b2 = min(r / TerrainGrid.BLOCK, 3) * 4 + c / TerrainGrid.BLOCK - 1; blockMin[b2] = min(blockMin[b2], y); blockMax[b2] = max(blockMax[b2], y) }
        }
        for (line in 0..TerrainGrid.BLOCKS) for (r in 0 until n) {
            val c = line * TerrainGrid.BLOCK
            put((c * s).toFloat(), hs[r * n + c] - skirt, (r * s - tile).toFloat(), r * n + c, roadDist[r * n + c])
        }
        for (line in 0..TerrainGrid.BLOCKS) for (c in 0 until n) {
            val r = line * TerrainGrid.BLOCK
            put((c * s).toFloat(), hs[r * n + c] - skirt, (r * s - tile).toFloat(), r * n + c, roadDist[r * n + c])
        }
        for (b in 0 until 16) blockMin[b] -= skirt
        var corridorIdx = IntArray(0)
        if (strip != null && stripVerts > 0) {
            strip.write(buf)
            corridorIdx = strip.indices(TerrainGrid.VERTICES)
            for (b in 0 until 16) { blockMin[b] = min(blockMin[b], strip.minY); blockMax[b] = max(blockMax[b], strip.maxY) }
        }
        buf.position(0)
        val lc = if (key.lod <= 1) pack.landCover(key) else null
        val side = if (key.lod == 0) pack.roads(key)?.let { sideRoads(it, h) } else null
        val (ax, az) = h.originX to -h.originY
        val bytes = total * TerrainGrid.STRIDE + corridorIdx.size * 4 + (image?.capacity() ?: 0) + (lc?.classes?.size ?: 0)
        return TerrainCpu(
            key, ax, az, tile.toFloat(), buf, total, corridorIdx, blockMin, blockMax, blockMin.min(), blockMax.max(),
            image, imageSize, lc?.classes, lc?.cells ?: 0, side, bytes,
        )
    }

    private fun packNormal(v: Float): Byte = (v * 127f).coerceIn(-127f, 127f).toInt().toByte()

    /**
     * Pushes the 10 m grid under the road and its corridor strip, which carry the real shape at 5 m:
     * within 12 m beyond the road edge the grid stays below the road, beyond it a steep cone lets the
     * terrain rise again; a smaller drop fades out by 30 m. Tunnels keep the terrain, bridges keep it
     * below their deck.
     */
    private fun lowerUnderRoad(h: HeightTile, hs: FloatArray, roadDist: FloatArray) {
        val n = TerrainGrid.N
        val s = h.spacingM
        val segs = index.segments(h.key.tx, h.key.ty)
        if (segs.isEmpty()) return
        val reach = 30.0
        for (i in segs) {
            val f0 = path.flags[i].toInt() and 0xFF
            val f1 = path.flags[i + 1].toInt() and 0xFF
            val skip = ((f0 or f1) and (PathFlags.BRIDGE or PathFlags.TUNNEL)) != 0
            val ax = path.x[i]; val ay = path.y[i]
            val vx = path.x[i + 1] - ax; val vy = path.y[i + 1] - ay
            val l2 = vx * vx + vy * vy
            val hw = path.width[i] / 2.0
            val r = hw + reach
            val c0 = floor((min(ax, ax + vx) - r - h.originX) / s).toInt().coerceIn(0, n - 1)
            val c1 = ceil((max(ax, ax + vx) + r - h.originX) / s).toInt().coerceIn(0, n - 1)
            val r0 = floor((h.originY + h.sizeM - (max(ay, ay + vy) + r)) / s).toInt().coerceIn(0, n - 1)
            val r1 = ceil((h.originY + h.sizeM - (min(ay, ay + vy) - r)) / s).toInt().coerceIn(0, n - 1)
            for (row in r0..r1) for (col in c0..c1) {
                val px = h.originX + col * s
                val py = h.originY + h.sizeM - row * s
                val t = if (l2 > 0) (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
                val ex = px - (ax + vx * t); val ey = py - (ay + vy * t)
                val e = sqrt(ex * ex + ey * ey) - hw
                if (e > reach) continue
                val k = row * n + col
                if (e < roadDist[k]) roadDist[k] = e.toFloat()
                val roadZ = path.z[i] + (path.z[i + 1] - path.z[i]) * t
                if (skip) {
                    // A bridge keeps the valley under it, but the grid must stay below the deck and its
                    // parapets: near the abutments the coarse river bank would rise through the walkway.
                    if (((f0 or f1) and PathFlags.TUNNEL) == 0) {
                        val under = roadZ - 1.6 + max(0.0, e - 4.0) * 0.6
                        if (under < hs[k]) hs[k] = under.toFloat()
                    }
                    continue
                }
                val cone = roadZ - 0.8 + max(0.0, e - 12.0) * 1.5
                val base = 1.0 * (1.0 - smooth(15.0, 30.0, e))
                val target = min(h.heights[k] - base, cone)
                if (target < hs[k]) hs[k] = target.toFloat()
            }
        }
    }

    private fun smooth(a: Double, b: Double, x: Double): Double {
        val t = ((x - a) / (b - a)).coerceIn(0.0, 1.0)
        return t * t * (3 - 2 * t)
    }

    /**
     * The carved ground beside the road at 5 m resolution (verges, cuts, embankments) for the path
     * samples inside this tile, drawn with the terrain shader so it blends with the grid around it.
     */
    private inner class CorridorStrip(private val h: HeightTile) {
        private val xs = ArrayList<Float>()
        private val ys = ArrayList<Float>()
        private val zs = ArrayList<Float>()
        private val ds = ArrayList<Float>()
        private val tris = ArrayList<Int>()
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        val vertexCount: Int get() = xs.size

        init {
            build()
        }

        private fun inside(i: Int): Boolean {
            val x = path.x[i]; val y = path.y[i]
            return x >= h.originX && x < h.originX + h.sizeM && y >= h.originY && y < h.originY + h.sizeM
        }

        private fun build() {
            val offs = corridor.offsets ?: return
            val k = offs.size
            var i = 0
            val last = path.size - 1
            while (i < last) {
                if (!inside(i)) { i++; continue }
                var j = i
                while (j < last && inside(j)) j++
                // Run i..j (j is the first sample outside, or the last sample): sections i..j inclusive.
                buildRun(i, min(j, last), offs, k)
                i = j + 1
            }
        }

        private fun buildRun(a: Int, b: Int, offs: FloatArray, k: Int) {
            val count = b - a + 1
            if (count < 2) return
            // Per sample and side: first vertex index and how many offsets are usable.
            val firstL = IntArray(count); val nL = IntArray(count)
            val firstR = IntArray(count); val nR = IntArray(count)
            for (q in 0 until count) {
                val i = a + q
                val sec = corridor.at(i)
                val fl = path.flags[i].toInt() and 0xFF
                if (sec == null || (fl and (PathFlags.BRIDGE or PathFlags.TUNNEL)) != 0) { nL[q] = 0; nR[q] = 0; continue }
                val i0 = max(0, i - 1); val i1 = min(path.size - 1, i + 1)
                var dx = path.x[i1] - path.x[i0]; var dy = path.y[i1] - path.y[i0]
                val dl = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
                dx /= dl; dy /= dl
                // Left normal in map coordinates.
                val lx = -dy; val ly = dx
                // Inner side of a bend: offsets beyond the centre of curvature would fold over.
                val turn = turnAngle(i)
                val radius = if (abs(turn) > 1e-4) path.stepM / abs(turn) else 1e9
                val hw = path.width[i] / 2.0
                for (side in 0..1) {
                    val left = side == 0
                    val free = min(if (left) sec.freeLeftAt(i) else sec.freeRightAt(i), k)
                    val inner = (turn > 0) == left
                    var m = 0
                    while (m < free) {
                        val lat = hw + offs[m]
                        if (inner && lat > radius * 0.85) break
                        m++
                    }
                    if (left) { firstL[q] = xs.size; nL[q] = m } else { firstR[q] = xs.size; nR[q] = m }
                    for (o in 0 until m) {
                        val lat = (hw + offs[o]) * (if (left) 1 else -1)
                        val px = path.x[i] + lx * lat
                        val py = path.y[i] + ly * lat
                        val z = path.z[i] + (if (left) sec.leftAt(i, o) else sec.rightAt(i, o))
                        xs += (px - h.originX).toFloat()
                        ys += z.toFloat()
                        zs += (-(py - h.originY)).toFloat()
                        ds += offs[o]
                        minY = min(minY, z.toFloat()); maxY = max(maxY, z.toFloat())
                    }
                }
            }
            for (q in 0 until count - 1) {
                for (side in 0..1) {
                    val left = side == 0
                    val m = min(if (left) nL[q] else nR[q], if (left) nL[q + 1] else nR[q + 1])
                    val f0 = if (left) firstL[q] else firstR[q]
                    val f1 = if (left) firstL[q + 1] else firstR[q + 1]
                    for (o in 0 until m - 1) {
                        val p00 = f0 + o; val p01 = f0 + o + 1
                        val p10 = f1 + o; val p11 = f1 + o + 1
                        // Counter-clockwise seen from above on both sides.
                        if (left) { tris += p00; tris += p10; tris += p11; tris += p00; tris += p11; tris += p01 }
                        else { tris += p00; tris += p11; tris += p10; tris += p00; tris += p01; tris += p11 }
                    }
                }
            }
        }

        private fun turnAngle(i: Int): Double {
            if (i <= 0 || i >= path.size - 1) return 0.0
            val a1 = atan2(path.y[i] - path.y[i - 1], path.x[i] - path.x[i - 1])
            val a2 = atan2(path.y[i + 1] - path.y[i], path.x[i + 1] - path.x[i])
            var d = a2 - a1
            while (d > Math.PI) d -= 2 * Math.PI
            while (d < -Math.PI) d += 2 * Math.PI
            return d
        }

        /** Writes the strip vertices with normals from their triangles. */
        fun write(buf: ByteBuffer) {
            val nv = xs.size
            val nx = FloatArray(nv); val ny = FloatArray(nv); val nz = FloatArray(nv)
            var t = 0
            while (t < tris.size) {
                val a = tris[t]; val b = tris[t + 1]; val c = tris[t + 2]
                val ux = xs[b] - xs[a]; val uy = ys[b] - ys[a]; val uz = zs[b] - zs[a]
                val vx = xs[c] - xs[a]; val vy = ys[c] - ys[a]; val vz = zs[c] - zs[a]
                val cx = uy * vz - uz * vy; val cy = uz * vx - ux * vz; val cz = ux * vy - uy * vx
                for (p in intArrayOf(a, b, c)) { nx[p] += cx; ny[p] += cy; nz[p] += cz }
                t += 3
            }
            for (p in 0 until nv) {
                var x = nx[p]; var y = ny[p]; var z = nz[p]
                if (y < 0) { x = -x; y = -y; z = -z }
                val l = sqrt(x * x + y * y + z * z)
                if (l < 1e-6) { x = 0f; y = 1f; z = 0f } else { x /= l; y /= l; z /= l }
                buf.putFloat(xs[p]); buf.putFloat(ys[p]); buf.putFloat(zs[p])
                buf.put(packNormal(x)); buf.put(packNormal(y)); buf.put(packNormal(z))
                buf.put((ds[p] / 12.7f * 127f).coerceIn(0f, 127f).toInt().toByte())
            }
        }

        fun indices(base: Int): IntArray = IntArray(tris.size) { tris[it] + base }
    }

    // --- side roads ------------------------------------------------------------------------------

    /**
     * Other roads, tracks and railways as textured strips on the terrain (road layout, tile anchor).
     * They follow the original terrain and meet the route's surface where they join it.
     */
    private fun sideRoads(rt: RoadTile, h: HeightTile): MeshBuffer? {
        val mb = MeshBuilder(1024)
        for (road in rt.roads) {
            val cls = road.roadClass
            if (cls == RoadClass.RAIL) continue
            if (cls == 1 || cls == 15 || cls == 13 || cls == 14 || cls == 16) continue
            if (road.flags and PathFlags.TUNNEL != 0) continue
            val unpaved = road.flags and PathFlags.UNPAVED != 0 || cls == 12
            val w = when {
                road.widthM >= 2f -> road.widthM
                cls in 2..5 -> 6f
                cls == 6 || cls == 7 -> 4.5f
                cls == 12 -> 2.6f
                cls == 11 -> 2.2f
                else -> 3.2f
            }.coerceIn(1.8f, 12f)
            strip(mb, road.points, w, if (unpaved) 5f else 0f, cls, h, road.flags and PathFlags.BRIDGE != 0)
        }
        return if (mb.isEmpty) null else mb.build()
    }

    private fun strip(mb: MeshBuilder, pts: FloatArray, w: Float, kind: Float, cls: Int, h: HeightTile, bridge: Boolean) {
        val n = pts.size / 2
        if (n < 2) return
        // Resample to <= 4 m steps so the strip follows the ground.
        val xs = ArrayList<Double>(); val ys = ArrayList<Double>()
        for (i in 0 until n - 1) {
            val ax = pts[2 * i].toDouble(); val ay = pts[2 * i + 1].toDouble()
            val bx = pts[2 * i + 2].toDouble(); val by = pts[2 * i + 3].toDouble()
            val len = sqrt((bx - ax) * (bx - ax) + (by - ay) * (by - ay))
            val steps = max(1, ceil(len / 4.0).toInt())
            for (k in 0 until steps) { xs += ax + (bx - ax) * k / steps; ys += ay + (by - ay) * k / steps }
        }
        xs += pts[2 * n - 2].toDouble(); ys += pts[2 * n - 1].toDouble()
        val m = xs.size
        val hw = w / 2
        var u = 0.0
        var prevL = -1; var prevR = -1; var prevLs = -1; var prevRs = -1
        for (i in 0 until m) {
            val i0 = max(0, i - 1); val i1 = min(m - 1, i + 1)
            var dx = xs[i1] - xs[i0]; var dy = ys[i1] - ys[i0]
            val dl = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-6)
            dx /= dl; dy /= dl
            if (i > 0) u += sqrt((xs[i] - xs[i - 1]) * (xs[i] - xs[i - 1]) + (ys[i] - ys[i - 1]) * (ys[i] - ys[i - 1]))
            val wx = h.originX + xs[i]; val wy = h.originY + ys[i]
            val lx = -dy; val ly = dx
            fun groundAt(px: Double, py: Double): Double {
                val g = HeightCache.exactHeight(h, px, py)
                // Near the route the side road meets the route's surface instead of the lowered grid.
                val near = index.nearest(px, py) ?: return g
                val e = abs(near.lateral) - path.width[near.index] / 2.0
                if (e > 14) return g
                val roadZ = path.z[near.index] + (path.z[near.index + 1] - path.z[near.index]) * near.t
                val f = (e / 14.0).coerceIn(0.0, 1.0)
                return roadZ - 0.03 + (g - roadZ) * f * f
            }
            val yl = groundAt(wx + lx * hw, wy + ly * hw) + 0.1
            val yr = groundAt(wx - lx * hw, wy - ly * hw) + 0.1
            val yc = if (bridge) max(yl, yr) else null
            val pxL = (xs[i] + lx * hw).toFloat(); val pzL = (-(ys[i] + ly * hw)).toFloat()
            val pxR = (xs[i] - lx * hw).toFloat(); val pzR = (-(ys[i] - ly * hw)).toFloat()
            val l = mb.vertex(pxL, (yc ?: yl).toFloat(), pzL, 0f, 1f, 0f, u.toFloat(), hw, kind, cls.toFloat(), hw, 0f)
            val r = mb.vertex(pxR, (yc ?: yr).toFloat(), pzR, 0f, 1f, 0f, u.toFloat(), -hw, kind, cls.toFloat(), hw, 0f)
            // Small skirts so a strip that floats a little above a bump shows no gap.
            val ls = mb.vertex(pxL, (yl - 0.5).toFloat(), pzL, lx.toFloat(), 0f, (-ly).toFloat(), u.toFloat(), hw + 0.5f, kind, cls.toFloat(), hw, 0f)
            val rs = mb.vertex(pxR, (yr - 0.5).toFloat(), pzR, (-lx).toFloat(), 0f, ly.toFloat(), u.toFloat(), -hw - 0.5f, kind, cls.toFloat(), hw, 0f)
            if (prevL >= 0) {
                mb.quad(prevR, r, l, prevL)
                mb.quad(prevL, l, ls, prevLs)
                mb.quad(prevR, prevRs, rs, r)
            }
            prevL = l; prevR = r; prevLs = ls; prevRs = rs
        }
    }
}
