package app.rollentrainer.ui.world

import app.rollentrainer.core.route.PathSample
import app.rollentrainer.core.route.World
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle mesh with interleaved vertices: position (3), normal (3), colour (3). Built on the CPU,
 * uploaded once by the renderer. The colour channel is free for the shader to interpret: the terrain
 * stores (material, variation, road distance) there, the road (along, across, 0).
 */
class MeshData(initialVertices: Int = 256) {
    var vertices = FloatArray(initialVertices * STRIDE)
        private set
    var vertexCount = 0
        private set
    var indices = IntArray(initialVertices * 3)
        private set
    var indexCount = 0
        private set

    fun vertex(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, r: Float, g: Float, b: Float): Int {
        if ((vertexCount + 1) * STRIDE > vertices.size) vertices = vertices.copyOf(vertices.size * 2)
        val o = vertexCount * STRIDE
        vertices[o] = x; vertices[o + 1] = y; vertices[o + 2] = z
        vertices[o + 3] = nx; vertices[o + 4] = ny; vertices[o + 5] = nz
        vertices[o + 6] = r; vertices[o + 7] = g; vertices[o + 8] = b
        return vertexCount++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(indices.size * 2)
        indices[indexCount++] = a; indices[indexCount++] = b; indices[indexCount++] = c
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    /** Appends another mesh, transformed by a 4x4 column-major matrix. */
    fun append(m: MeshData, matrix: FloatArray) {
        val base = vertexCount
        val v = m.vertices
        for (i in 0 until m.vertexCount) {
            val o = i * STRIDE
            val x = v[o]; val y = v[o + 1]; val z = v[o + 2]
            val px = matrix[0] * x + matrix[4] * y + matrix[8] * z + matrix[12]
            val py = matrix[1] * x + matrix[5] * y + matrix[9] * z + matrix[13]
            val pz = matrix[2] * x + matrix[6] * y + matrix[10] * z + matrix[14]
            val nx = v[o + 3]; val ny = v[o + 4]; val nz = v[o + 5]
            var qx = matrix[0] * nx + matrix[4] * ny + matrix[8] * nz
            var qy = matrix[1] * nx + matrix[5] * ny + matrix[9] * nz
            var qz = matrix[2] * nx + matrix[6] * ny + matrix[10] * nz
            val len = sqrt(qx * qx + qy * qy + qz * qz).takeIf { it > 1e-6f } ?: 1f
            qx /= len; qy /= len; qz /= len
            vertex(px, py, pz, qx, qy, qz, v[o + 6], v[o + 7], v[o + 8])
        }
        for (i in 0 until m.indexCount) {
            if (indexCount + 1 > indices.size) indices = indices.copyOf(indices.size * 2)
            indices[indexCount++] = m.indices[i] + base
        }
    }

    /**
     * Replaces every vertex normal with the area-weighted average of the normals of the triangles that
     * use it, so a surface shades smoothly instead of showing each facet.
     */
    fun smoothNormals() {
        val v = vertices
        for (i in 0 until vertexCount) { val o = i * STRIDE; v[o + 3] = 0f; v[o + 4] = 0f; v[o + 5] = 0f }
        var t = 0
        while (t + 2 < indexCount) {
            val a = indices[t] * STRIDE; val b = indices[t + 1] * STRIDE; val c = indices[t + 2] * STRIDE
            val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
            val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
            val nx = e1y * e2z - e1z * e2y
            val ny = e1z * e2x - e1x * e2z
            val nz = e1x * e2y - e1y * e2x
            for (o in intArrayOf(a, b, c)) { v[o + 3] += nx; v[o + 4] += ny; v[o + 5] += nz }
            t += 3
        }
        for (i in 0 until vertexCount) {
            val o = i * STRIDE
            val len = sqrt(v[o + 3] * v[o + 3] + v[o + 4] * v[o + 4] + v[o + 5] * v[o + 5])
            if (len > 1e-9f) { v[o + 3] /= len; v[o + 4] /= len; v[o + 5] /= len } else { v[o + 4] = 1f }
        }
    }

    /** A copy with every triangle on its own three vertices and a flat face normal (faceted look). */
    fun flatCopy(): MeshData {
        val out = MeshData(indexCount)
        val v = vertices
        var t = 0
        while (t + 2 < indexCount) {
            val a = indices[t] * STRIDE; val b = indices[t + 1] * STRIDE; val c = indices[t + 2] * STRIDE
            val e1x = v[b] - v[a]; val e1y = v[b + 1] - v[a + 1]; val e1z = v[b + 2] - v[a + 2]
            val e2x = v[c] - v[a]; val e2y = v[c + 1] - v[a + 1]; val e2z = v[c + 2] - v[a + 2]
            var nx = e1y * e2z - e1z * e2y
            var ny = e1z * e2x - e1x * e2z
            var nz = e1x * e2y - e1y * e2x
            val len = sqrt(nx * nx + ny * ny + nz * nz).takeIf { it > 1e-9f } ?: 1f
            nx /= len; ny /= len; nz /= len
            val ia = out.vertex(v[a], v[a + 1], v[a + 2], nx, ny, nz, v[a + 6], v[a + 7], v[a + 8])
            val ib = out.vertex(v[b], v[b + 1], v[b + 2], nx, ny, nz, v[b + 6], v[b + 7], v[b + 8])
            val ic = out.vertex(v[c], v[c + 1], v[c + 2], nx, ny, nz, v[c + 6], v[c + 7], v[c + 8])
            out.triangle(ia, ib, ic)
            t += 3
        }
        return out
    }

    companion object {
        const val STRIDE = 9
    }
}

/** Builders for the simple shapes the world is made of. */
object Shapes {
    /** Axis-aligned box centred at the origin with per-face flat normals. */
    fun box(sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, topR: Float = r, topG: Float = g, topB: Float = b): MeshData {
        val m = MeshData(24)
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        fun face(nx: Float, ny: Float, nz: Float, p: Array<FloatArray>, cr: Float, cg: Float, cb: Float) {
            val a = m.vertex(p[0][0], p[0][1], p[0][2], nx, ny, nz, cr, cg, cb)
            val b2 = m.vertex(p[1][0], p[1][1], p[1][2], nx, ny, nz, cr, cg, cb)
            val c = m.vertex(p[2][0], p[2][1], p[2][2], nx, ny, nz, cr, cg, cb)
            val d = m.vertex(p[3][0], p[3][1], p[3][2], nx, ny, nz, cr, cg, cb)
            m.quad(a, b2, c, d)
        }
        face(0f, 0f, 1f, arrayOf(floatArrayOf(-hx, -hy, hz), floatArrayOf(hx, -hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(-hx, hy, hz)), r, g, b)
        face(0f, 0f, -1f, arrayOf(floatArrayOf(hx, -hy, -hz), floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, hy, -hz), floatArrayOf(hx, hy, -hz)), r, g, b)
        face(1f, 0f, 0f, arrayOf(floatArrayOf(hx, -hy, hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, hy, -hz), floatArrayOf(hx, hy, hz)), r, g, b)
        face(-1f, 0f, 0f, arrayOf(floatArrayOf(-hx, -hy, -hz), floatArrayOf(-hx, -hy, hz), floatArrayOf(-hx, hy, hz), floatArrayOf(-hx, hy, -hz)), r, g, b)
        face(0f, 1f, 0f, arrayOf(floatArrayOf(-hx, hy, hz), floatArrayOf(hx, hy, hz), floatArrayOf(hx, hy, -hz), floatArrayOf(-hx, hy, -hz)), topR, topG, topB)
        face(0f, -1f, 0f, arrayOf(floatArrayOf(-hx, -hy, -hz), floatArrayOf(hx, -hy, -hz), floatArrayOf(hx, -hy, hz), floatArrayOf(-hx, -hy, hz)), r * 0.7f, g * 0.7f, b * 0.7f)
        return m
    }

    /** Cylinder along y from 0 to [h], radius [rBottom] at the base and [rTop] at the top (0 = cone). */
    fun cylinder(rBottom: Float, rTop: Float, h: Float, segments: Int, r: Float, g: Float, b: Float, capped: Boolean = true): MeshData {
        val m = MeshData(segments * 4)
        val ring0 = IntArray(segments)
        val ring1 = IntArray(segments)
        for (i in 0 until segments) {
            val a = (2 * PI * i / segments).toFloat()
            val ca = cos(a); val sa = sin(a)
            // Normal tilted by the taper.
            val slope = (rBottom - rTop) / h
            val len = sqrt(1 + slope * slope)
            val nx = ca / len; val ny = slope / len; val nz = sa / len
            ring0[i] = m.vertex(ca * rBottom, 0f, sa * rBottom, nx, ny, nz, r, g, b)
            ring1[i] = m.vertex(ca * rTop, h, sa * rTop, nx, ny, nz, r, g, b)
        }
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            m.quad(ring0[i], ring1[i], ring1[j], ring0[j])
        }
        if (capped) {
            val top = m.vertex(0f, h, 0f, 0f, 1f, 0f, r, g, b)
            val bottom = m.vertex(0f, 0f, 0f, 0f, -1f, 0f, r, g, b)
            for (i in 0 until segments) {
                val j = (i + 1) % segments
                val t0 = m.vertex(cos(2 * PI * i / segments).toFloat() * rTop, h, sin(2 * PI * i / segments).toFloat() * rTop, 0f, 1f, 0f, r, g, b)
                val t1 = m.vertex(cos(2 * PI * j / segments).toFloat() * rTop, h, sin(2 * PI * j / segments).toFloat() * rTop, 0f, 1f, 0f, r, g, b)
                m.triangle(top, t1, t0)
                val b0 = m.vertex(cos(2 * PI * i / segments).toFloat() * rBottom, 0f, sin(2 * PI * i / segments).toFloat() * rBottom, 0f, -1f, 0f, r, g, b)
                val b1 = m.vertex(cos(2 * PI * j / segments).toFloat() * rBottom, 0f, sin(2 * PI * j / segments).toFloat() * rBottom, 0f, -1f, 0f, r, g, b)
                m.triangle(bottom, b0, b1)
            }
        }
        return m
    }

    /**
     * A cone layer of a conifer: a cone whose rim dips a little between the "branches" so the silhouette
     * is not a clean circle. The tip is at [h], the rim hangs at [droop] below 0.
     */
    fun conifLayer(radius: Float, h: Float, segments: Int, droop: Float, r: Float, g: Float, b: Float, seed: Int): MeshData {
        val m = MeshData(segments * 3)
        val tip = m.vertex(0f, h, 0f, 0f, 1f, 0f, r, g, b)
        val rim = IntArray(segments)
        for (i in 0 until segments) {
            val a = (2 * PI * i / segments).toFloat()
            val jitter = 0.78f + 0.22f * hash01(i * 7 + seed * 131)
            val rad = radius * jitter
            val y = -droop * (0.5f + 0.5f * hash01(i * 13 + seed * 17))
            val slope = rad / (h - y)
            val len = sqrt(1 + slope * slope)
            rim[i] = m.vertex(cos(a) * rad, y, sin(a) * rad, cos(a) / len, slope / len, sin(a) / len, r, g, b)
        }
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            m.triangle(tip, rim[j], rim[i])
        }
        // Underside, so the layer reads as a solid skirt from below.
        val under = m.vertex(0f, -droop * 0.3f, 0f, 0f, -1f, 0f, r * 0.6f, g * 0.6f, b * 0.6f)
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            val v = m.vertices
            val oi = rim[i] * MeshData.STRIDE; val oj = rim[j] * MeshData.STRIDE
            val ui = m.vertex(v[oi], v[oi + 1], v[oi + 2], 0f, -1f, 0f, r * 0.6f, g * 0.6f, b * 0.6f)
            val uj = m.vertex(v[oj], v[oj + 1], v[oj + 2], 0f, -1f, 0f, r * 0.6f, g * 0.6f, b * 0.6f)
            m.triangle(under, ui, uj)
        }
        return m
    }

    /** Low-poly sphere centred at the origin. */
    fun sphere(radius: Float, rings: Int, segments: Int, r: Float, g: Float, b: Float, squash: Float = 1f): MeshData {
        val m = MeshData((rings + 1) * (segments + 1))
        val grid = Array(rings + 1) { IntArray(segments + 1) }
        for (i in 0..rings) {
            val phi = (PI * i / rings).toFloat()
            for (j in 0..segments) {
                val theta = (2 * PI * j / segments).toFloat()
                val nx = sin(phi) * cos(theta); val ny = cos(phi); val nz = sin(phi) * sin(theta)
                grid[i][j] = m.vertex(nx * radius, ny * radius * squash, nz * radius, nx, ny, nz, r, g, b)
            }
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            m.quad(grid[i][j], grid[i][j + 1], grid[i + 1][j + 1], grid[i + 1][j])
        }
        return m
    }

    /** A boulder: a squashed sphere with jittered radii, flat shaded so every facet catches the light. */
    fun rock(radius: Float, rings: Int, segments: Int, r: Float, g: Float, b: Float, seed: Int): MeshData {
        val m = MeshData((rings + 1) * (segments + 1))
        val grid = Array(rings + 1) { IntArray(segments + 1) }
        for (i in 0..rings) {
            val phi = (PI * i / rings).toFloat()
            for (j in 0..segments) {
                val jj = j % segments
                val theta = (2 * PI * jj / segments).toFloat()
                val nx = sin(phi) * cos(theta); val ny = cos(phi); val nz = sin(phi) * sin(theta)
                val jit = if (i == 0 || i == rings) 1f else 0.72f + 0.5f * hash01(i * 31 + jj * 7 + seed)
                val rad = radius * jit
                val shade = 0.9f + 0.2f * hash01(i * 11 + jj * 3 + seed * 5)
                grid[i][j] = m.vertex(nx * rad, ny * rad * 0.62f, nz * rad, nx, ny, nz, r * shade, g * shade, b * shade)
            }
        }
        for (i in 0 until rings) for (j in 0 until segments) {
            m.quad(grid[i][j], grid[i][j + 1], grid[i + 1][j + 1], grid[i + 1][j])
        }
        return m.flatCopy()
    }

    /**
     * A bicycle wheel in the x/y plane with its axle along z: black tyre, a metal rim, spokes as thin
     * two-sided strips and a hub. Winding faces outward on the tyre, the strips are drawn from both sides.
     */
    fun wheel(radius: Float, width: Float, segments: Int, spokes: Int = 16): MeshData {
        val m = MeshData(segments * 8 + spokes * 8)
        val tyreR = 0.08f; val tyreG = 0.08f; val tyreB = 0.09f
        val rimC = 0.7f
        val rimInner = radius * 0.9f
        val rimInner2 = radius * 0.84f
        val front = IntArray(segments); val back = IntArray(segments)
        val frontI = IntArray(segments); val backI = IntArray(segments)
        val frontR = IntArray(segments); val backR = IntArray(segments)
        for (i in 0 until segments) {
            val a = (2 * PI * i / segments).toFloat()
            val x = cos(a); val y = sin(a)
            front[i] = m.vertex(x * radius, y * radius, width / 2, x, y, 0f, tyreR, tyreG, tyreB)
            back[i] = m.vertex(x * radius, y * radius, -width / 2, x, y, 0f, tyreR, tyreG, tyreB)
            frontI[i] = m.vertex(x * rimInner, y * rimInner, width / 2 * 0.7f, 0f, 0f, 1f, tyreR, tyreG, tyreB)
            backI[i] = m.vertex(x * rimInner, y * rimInner, -width / 2 * 0.7f, 0f, 0f, -1f, tyreR, tyreG, tyreB)
            frontR[i] = m.vertex(x * rimInner2, y * rimInner2, width / 2 * 0.5f, 0f, 0f, 1f, rimC, rimC, rimC + 0.03f)
            backR[i] = m.vertex(x * rimInner2, y * rimInner2, -width / 2 * 0.5f, 0f, 0f, -1f, rimC, rimC, rimC + 0.03f)
        }
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            m.quad(front[i], back[i], back[j], front[j])          // tread
            m.quad(front[i], front[j], frontI[j], frontI[i])      // tyre side wall, front
            m.quad(back[j], back[i], backI[i], backI[j])          // tyre side wall, back
            m.quad(frontI[i], frontI[j], frontR[j], frontR[i])    // rim, front
            m.quad(backI[j], backI[i], backR[i], backR[j])        // rim, back
        }
        // Spokes: thin strips from the hub to the rim, alternating a little to the front and back
        // flange like a real wheel; both windings so they show from either side.
        val hubR = 0.03f
        for (s in 0 until spokes) {
            val a = (2 * PI * s / spokes).toFloat()
            val x = cos(a); val y = sin(a)
            val px = -y; val py = x   // perpendicular in the wheel plane
            val hw = 0.0025f
            val zOff = if (s % 2 == 0) 0.012f else -0.012f
            val c = 0.75f
            val h0 = m.vertex(x * hubR + px * hw, y * hubR + py * hw, zOff, 0f, 0f, 1f, c, c, c)
            val h1 = m.vertex(x * hubR - px * hw, y * hubR - py * hw, zOff, 0f, 0f, 1f, c, c, c)
            val r0 = m.vertex(x * rimInner2 + px * hw, y * rimInner2 + py * hw, 0f, 0f, 0f, 1f, c, c, c)
            val r1 = m.vertex(x * rimInner2 - px * hw, y * rimInner2 - py * hw, 0f, 0f, 0f, 1f, c, c, c)
            m.quad(h0, r0, r1, h1)
            m.quad(h1, r1, r0, h0)
        }
        // Hub: a short cylinder along z.
        val hub = cylinder(hubR, hubR, 0.08f, 8, 0.45f, 0.45f, 0.48f)
        val rot = FloatArray(16).also {
            android.opengl.Matrix.setIdentityM(it, 0)
            android.opengl.Matrix.translateM(it, 0, 0f, 0f, -0.04f)
            android.opengl.Matrix.rotateM(it, 0, 90f, 1f, 0f, 0f)
        }
        m.append(hub, rot)
        return m
    }

    /** Deterministic pseudo random number in 0..1 from an integer key. */
    fun hash01(k: Int): Float {
        var h = k * 374761393
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFF) / 65535f
    }
}

/**
 * Distance from a point to the road's centre line, from a bucket grid over the path segments, exact
 * up to [REACH] metres and reported as [REACH] beyond.
 */
class RoadDistance(private val path: List<PathSample>) {
    private val buckets = HashMap<Long, IntArray>()
    private val counts = HashMap<Long, Int>()

    init {
        val tmp = HashMap<Long, MutableList<Int>>()
        for (j in 0 until path.size - 1) {
            val a = path[j]; val b = path[j + 1]
            val ka = key(a.x, a.z); val kb = key(b.x, b.z)
            tmp.getOrPut(ka) { mutableListOf() }.add(j)
            if (kb != ka) tmp.getOrPut(kb) { mutableListOf() }.add(j)
        }
        for ((k, list) in tmp) buckets[k] = list.toIntArray()
    }

    private fun key(x: Double, z: Double): Long = floor(x / BUCKET).toLong() * 100_003L + floor(z / BUCKET).toLong()

    fun distance(x: Double, z: Double): Double {
        val bx = floor(x / BUCKET).toLong(); val bz = floor(z / BUCKET).toLong()
        var best = REACH * REACH
        for (dx in -1..1) for (dz in -1..1) {
            val list = buckets[(bx + dx) * 100_003L + (bz + dz)] ?: continue
            for (j in list) {
                val a = path[j]; val b = path[j + 1]
                val sx = b.x - a.x; val sz = b.z - a.z
                val len2 = sx * sx + sz * sz
                val t = if (len2 < 1e-9) 0.0 else (((x - a.x) * sx + (z - a.z) * sz) / len2).coerceIn(0.0, 1.0)
                val px = a.x + sx * t - x; val pz = a.z + sz * t - z
                val d2 = px * px + pz * pz
                if (d2 < best) best = d2
            }
        }
        return sqrt(best)
    }

    companion object {
        /** Bucket edge; a segment within [REACH] of a point has an endpoint in one of the 3x3 buckets around it. */
        const val BUCKET = 150.0
        const val REACH = 145.0
    }
}

/** Converts the generated world into meshes. */
object WorldMeshes {
    /**
     * Terrain grid as a mesh. Cells within [SUBDIVIDE_DIST] of the road are split into [SUB]x[SUB]
     * sub-cells whose heights are interpolated on the same two triangles per cell that
     * [World.Terrain.heightAt] uses, plus a smooth noise displacement that is zero within
     * [DISP_FADE_FROM] of the road edge and never positive within [DISP_BIAS_TO] of it, so the ground
     * next to the asphalt stays at or below the corner-interpolated height. Far cells stay coarse.
     * The colour channel carries (material, variation 0..1, road distance / [ROAD_DIST_SCALE]) for the
     * terrain shader; normals are smooth per vertex.
     */
    fun terrain(t: World.Terrain, biome: app.rollentrainer.core.route.Biome, path: List<PathSample> = emptyList()): MeshData {
        val m = MeshData(t.cols * t.rows * 2)
        val road = if (path.size >= 2) RoadDistance(path) else null
        val cols = t.cols; val rows = t.rows
        val dist = DoubleArray(cols * rows)
        val ids = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = t.index(c, r)
            val x = t.x(c); val z = t.z(r)
            dist[i] = road?.distance(x, z) ?: RoadDistance.REACH
            ids[i] = m.vertex(x.toFloat(), t.heights[i], z.toFloat(), 0f, 1f, 0f, t.material[i].toFloat(), variation(x, z), (dist[i] / ROAD_DIST_SCALE).toFloat())
        }
        val amp = if (biome == app.rollentrainer.core.route.Biome.CITY) 0.0 else DISP_AMP
        // Fine grid of SUB x SUB per cell; the coarse corners are its every SUB-th vertex.
        val fineCols = (cols - 1) * SUB + 1
        val fineRows = (rows - 1) * SUB + 1
        val fine = IntArray(fineCols * fineRows) { -1 }
        for (r in 0 until rows) for (c in 0 until cols) fine[(r * SUB) * fineCols + c * SUB] = ids[t.index(c, r)]
        val cellIds = IntArray((SUB + 1) * (SUB + 1))
        for (r in 0 until rows - 1) for (c in 0 until cols - 1) {
            val ia = t.index(c, r); val ib = t.index(c + 1, r); val id = t.index(c, r + 1); val ie = t.index(c + 1, r + 1)
            val near = road != null && minOf(minOf(dist[ia], dist[ib]), minOf(dist[id], dist[ie])) < SUBDIVIDE_DIST
            if (!near) {
                m.triangle(ids[ia], ids[id], ids[ib])
                m.triangle(ids[ib], ids[id], ids[ie])
                continue
            }
            val ha = t.heights[ia].toDouble(); val hb = t.heights[ib].toDouble()
            val hd = t.heights[id].toDouble(); val he = t.heights[ie].toDouble()
            val ma = t.material[ia].toInt(); val mb = t.material[ib].toInt()
            val md = t.material[id].toInt(); val me = t.material[ie].toInt()
            for (j in 0..SUB) for (i in 0..SUB) {
                val fc = c * SUB + i; val fr = r * SUB + j
                val fi = fr * fineCols + fc
                var vid = fine[fi]
                if (vid < 0) {
                    val fx = i.toDouble() / SUB; val fz = j.toDouble() / SUB
                    val x = t.x(c) + fx * t.cell; val z = t.z(r) + fz * t.cell
                    // Same split as Terrain.heightAt: (a, d, b) below the diagonal, (b, d, e) above it.
                    val planar = if (fx + fz <= 1.0) ha + (hb - ha) * fx + (hd - ha) * fz
                    else he + (hd - he) * (1 - fx) + (hb - he) * (1 - fz)
                    val d = road!!.distance(x, z)
                    val y = planar + displacement(x, z, d, amp)
                    // Material: the corner with the largest bilinear weight, nudged by noise so the borders wobble.
                    val n = World.fbm(x * 0.09 + 3.0, z * 0.09) * 0.35
                    val wa = (1 - fx) * (1 - fz) + n; val wb = fx * (1 - fz) - n
                    val wd = (1 - fx) * fz + n * 0.5; val we = fx * fz - n * 0.5
                    val mat = when {
                        wa >= wb && wa >= wd && wa >= we -> ma
                        wb >= wd && wb >= we -> mb
                        wd >= we -> md
                        else -> me
                    }
                    vid = m.vertex(x.toFloat(), y.toFloat(), z.toFloat(), 0f, 1f, 0f, mat.toFloat(), variation(x, z), (d / ROAD_DIST_SCALE).toFloat())
                    fine[fi] = vid
                }
                cellIds[j * (SUB + 1) + i] = vid
            }
            for (j in 0 until SUB) for (i in 0 until SUB) {
                val a = cellIds[j * (SUB + 1) + i]; val b = cellIds[j * (SUB + 1) + i + 1]
                val d = cellIds[(j + 1) * (SUB + 1) + i]; val e = cellIds[(j + 1) * (SUB + 1) + i + 1]
                m.triangle(a, d, b)
                m.triangle(b, d, e)
            }
        }
        m.smoothNormals()
        return m
    }

    /**
     * Noise displacement of a fine terrain vertex at road distance [d] (to the centre line): zero up to
     * [DISP_FADE_FROM] beyond the road edge, biased so it is never positive up to [DISP_BIAS_TO], and
     * fading out again towards the coarse cells so no crack opens at the subdivision border.
     */
    fun displacement(x: Double, z: Double, d: Double, amp: Double): Double {
        if (amp <= 0.0) return 0.0
        val de = d - World.ROAD_HALF
        val fadeIn = World.smooth(((de - DISP_FADE_FROM) / (DISP_FADE_TO - DISP_FADE_FROM)).coerceIn(0.0, 1.0))
        val fadeOut = 1 - World.smooth(((d - DISP_OUT_FROM) / (DISP_OUT_TO - DISP_OUT_FROM)).coerceIn(0.0, 1.0))
        val fade = fadeIn * fadeOut
        if (fade <= 0.0) return 0.0
        val noise = World.fbm(x * 0.07 + 11.0, z * 0.07 - 5.0) * amp + World.fbm(x * 0.21, z * 0.21 + 9.0) * amp * 0.25
        // Near the road the field is shifted down by its full range, so it can only ever dip.
        val bias = -amp * 1.25 * (1 - World.smooth(((de - DISP_BIAS_FROM) / (DISP_BIAS_TO - DISP_BIAS_FROM)).coerceIn(0.0, 1.0)))
        return fade * (noise + bias)
    }

    private fun variation(x: Double, z: Double): Float = (World.fbm(x * 0.05, z * 0.05) * 0.5 + 0.5).toFloat().coerceIn(0f, 1f)

    /** Sub-cells per terrain cell edge near the road. */
    const val SUB = 4
    /** Cells with a corner closer than this to the road's centre line are subdivided. */
    const val SUBDIVIDE_DIST = 140.0
    /** Amplitude of the fine terrain displacement in metres. */
    const val DISP_AMP = 0.45
    /** Distance from the road edge where the displacement starts (0 before) and where it is in full. */
    const val DISP_FADE_FROM = 6.0
    const val DISP_FADE_TO = 20.0
    /** Up to this distance from the road edge the displacement is non-positive; full range beyond. */
    const val DISP_BIAS_FROM = 30.0
    const val DISP_BIAS_TO = 60.0
    /** Centre line distance where the displacement fades out towards the coarse cells. */
    const val DISP_OUT_FROM = 95.0
    const val DISP_OUT_TO = 115.0
    /** The road distance in the terrain colour channel is divided by this. */
    const val ROAD_DIST_SCALE = 100.0

    fun materialColor(material: Int, biome: app.rollentrainer.core.route.Biome): FloatArray = when (material) {
        0 -> if (biome == app.rollentrainer.core.route.Biome.VOLCANIC) floatArrayOf(0.36f, 0.5f, 0.2f) else floatArrayOf(0.4f, 0.58f, 0.24f)
        1 -> floatArrayOf(0.25f, 0.42f, 0.17f)
        2 -> floatArrayOf(0.5f, 0.47f, 0.43f)
        3 -> floatArrayOf(0.93f, 0.95f, 0.98f)
        4 -> floatArrayOf(0.88f, 0.86f, 0.8f)
        5 -> floatArrayOf(0.5f, 0.49f, 0.47f)
        else -> floatArrayOf(0.55f, 0.52f, 0.44f)
    }

    /**
     * The road as a ribbon with a kerb; the colour channel carries (u along in metres / 10, v across -1..1, 0)
     * so the fragment shader can paint lines, hence the road uses its own shader. Both edges get a skirt
     * (v beyond ±1, painted as a stone wall) that reaches down to the terrain, so the small step to the
     * levelled ground and the retaining walls of stacked switchbacks are closed.
     */
    fun road(path: List<PathSample>, terrain: World.Terrain? = null): MeshData {
        val m = MeshData(path.size * 6)
        val half = World.ROAD_HALF.toFloat()
        var prevL = -1; var prevR = -1
        var prevLt = -1; var prevLb = -1; var prevRt = -1; var prevRb = -1
        for ((i, p) in path.withIndex()) {
            // Right-hand vector of the heading (the world is right-handed with y up).
            val rx = -cos(p.heading).toFloat(); val rz = sin(p.heading).toFloat()
            val y = p.y.toFloat() + ROAD_LIFT
            val u = (p.d / 10).toFloat()
            val px = p.x.toFloat(); val pz = p.z.toFloat()
            val lx = px - rx * half; val lz = pz - rz * half
            val rxe = px + rx * half; val rze = pz + rz * half
            val l = m.vertex(lx, y, lz, 0f, 1f, 0f, u, -1f, 0f)
            val r = m.vertex(rxe, y, rze, 0f, 1f, 0f, u, 1f, 0f)
            // Skirts down to the ground just outside each edge (at least a little below the asphalt).
            fun bottom(ex: Float, ez: Float, ox: Float, oz: Float): Float {
                val ground = terrain?.heightAt((ex + ox * 1.5f).toDouble(), (ez + oz * 1.5f).toDouble())?.toFloat() ?: (y - 1f)
                return minOf(ground, y) - 0.6f
            }
            val lt = m.vertex(lx, y, lz, -rx, 0f, -rz, u, -1f, 0f)
            val lb = m.vertex(lx, bottom(lx, lz, -rx, -rz), lz, -rx, 0f, -rz, u, -1.25f, 0f)
            val rt = m.vertex(rxe, y, rze, rx, 0f, rz, u, 1f, 0f)
            val rb = m.vertex(rxe, bottom(rxe, rze, rx, rz), rze, rx, 0f, rz, u, 1.25f, 0f)
            if (i > 0) {
                m.quad(prevR, r, l, prevL)          // surface, facing up
                m.quad(prevRt, prevRb, rb, rt)      // right skirt, facing right
                m.quad(prevLt, lt, lb, prevLb)      // left skirt, facing left
            }
            prevL = l; prevR = r; prevLt = lt; prevLb = lb; prevRt = rt; prevRb = rb
        }
        return m
    }

    /**
     * Far mountains: a ring of peaks around the origin, drawn centred on the camera like a backdrop so
     * the horizon is never empty. [relief] scales the peak heights (high Alps vs. the Breton hills).
     */
    fun skyline(radius: Float, relief: Float, seed: Int): MeshData {
        val m = MeshData(512)
        val n = 180
        val top = IntArray(n); val bottom = IntArray(n)
        for (i in 0 until n) {
            val a = (2 * PI * i / n).toFloat()
            val x = cos(a) * radius; val z = sin(a) * radius
            val h = ((World.fbm(i * 0.11 + seed, 0.3) * 0.5 + 0.5) * relief + 0.03) * radius
            val c = 0.62f + (World.fbm(i * 0.3 + seed, 7.0) * 0.05).toFloat()
            top[i] = m.vertex(x, h.toFloat(), z, -cos(a), 0.3f, -sin(a), c, c + 0.03f, c + 0.1f)
            bottom[i] = m.vertex(x, -radius * 0.12f, z, -cos(a), 0.3f, -sin(a), c, c + 0.03f, c + 0.1f)
        }
        for (i in 0 until n) {
            val j = (i + 1) % n
            // Wound to face the centre, where the camera is.
            m.quad(bottom[i], bottom[j], top[j], top[i])
        }
        return m
    }

    /** How high the far mountains rise, as a share of their distance. */
    fun skylineRelief(biome: app.rollentrainer.core.route.Biome): Float = when (biome) {
        app.rollentrainer.core.route.Biome.ALPINE -> 0.3f
        app.rollentrainer.core.route.Biome.PYRENEAN -> 0.24f
        app.rollentrainer.core.route.Biome.VENTOUX -> 0.1f
        app.rollentrainer.core.route.Biome.VOLCANIC -> 0.07f
        app.rollentrainer.core.route.Biome.FIELDS -> 0.025f
        app.rollentrainer.core.route.Biome.CITY -> 0.02f
    }

    /** The asphalt sits this far above the centre line samples. */
    const val ROAD_LIFT = 0.1f
    /** Far clipping plane of the camera. */
    const val FAR_PLANE = 4000f
    /** Distance of the far-mountain ring from the camera, inside the far plane. */
    const val SKYLINE_RADIUS = 3300f
    /** The ring sits this far below the camera, so its peaks stay near the horizon wherever you are. */
    const val SKYLINE_DROP = 350f
    /** Lateral place of the rider on the road in the bike's local x (negative = right). */
    const val RIDER_OFFSET = -1.1f
    /** The pacer rides on the rider's left. */
    const val PACER_OFFSET = 0.6f

    /**
     * Models of the scattered props, by kind. Pure white parts (the spectators' jerseys) take the
     * per-instance tint in the shader's "white only" tint mode; everything else keeps its colour.
     */
    fun prop(kind: Int): MeshData {
        val m = MeshData(512)
        val id = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        when (kind) {
            app.rollentrainer.core.route.Prop.CONIFER -> {
                m.append(Shapes.cylinder(0.24f, 0.14f, 3.0f, 7, 0.34f, 0.22f, 0.12f), id)
                // Four cone layers, darker and wider at the bottom, each with a drooping, uneven rim.
                m.append(Shapes.conifLayer(2.9f, 3.2f, 11, 0.5f, 0.10f, 0.28f, 0.13f, 1), translate(0f, 2.0f, 0f))
                m.append(Shapes.conifLayer(2.3f, 3.0f, 11, 0.45f, 0.12f, 0.33f, 0.15f, 2), translate(0f, 3.9f, 0f))
                m.append(Shapes.conifLayer(1.7f, 2.8f, 10, 0.4f, 0.14f, 0.38f, 0.17f, 3), translate(0f, 5.7f, 0f))
                m.append(Shapes.conifLayer(1.0f, 2.4f, 9, 0.3f, 0.17f, 0.43f, 0.19f, 4), translate(0f, 7.4f, 0f))
            }
            app.rollentrainer.core.route.Prop.BROADLEAF -> {
                m.append(Shapes.cylinder(0.3f, 0.2f, 3.2f, 7, 0.38f, 0.27f, 0.16f), id)
                // Two boughs leaving the trunk.
                m.append(Shapes.cylinder(0.12f, 0.06f, 1.6f, 5, 0.38f, 0.27f, 0.16f), tilt(2.6f, -35f, 0.6f, 0f, 0f))
                m.append(Shapes.cylinder(0.12f, 0.06f, 1.6f, 5, 0.38f, 0.27f, 0.16f), tilt(2.8f, 30f, -0.5f, 0f, 0.3f))
                // Three overlapping crowns in slightly different greens.
                m.append(Shapes.sphere(2.6f, 6, 10, 0.22f, 0.46f, 0.18f, squash = 0.8f), translate(0f, 5.0f, 0f))
                m.append(Shapes.sphere(2.0f, 5, 9, 0.27f, 0.52f, 0.2f, squash = 0.85f), translate(1.4f, 5.9f, 0.5f))
                m.append(Shapes.sphere(1.9f, 5, 9, 0.2f, 0.42f, 0.17f, squash = 0.9f), translate(-1.2f, 6.1f, -0.7f))
            }
            app.rollentrainer.core.route.Prop.ROCK -> {
                m.append(Shapes.rock(1.2f, 4, 8, 0.5f, 0.47f, 0.44f, 3), id)
                m.append(Shapes.rock(0.7f, 3, 7, 0.46f, 0.44f, 0.42f, 9), translate(0.9f, 0f, 0.5f))
            }
            app.rollentrainer.core.route.Prop.BUILDING -> {
                // Haussmann block: cream facade, zinc roof; the windows come from the shader.
                m.append(Shapes.box(22f, 19f, 16f, 0.86f, 0.8f, 0.68f, 0.35f, 0.33f, 0.35f), translate(0f, 9.5f, 0f))
                m.append(Shapes.box(22.6f, 0.5f, 16.6f, 0.4f, 0.38f, 0.36f), translate(0f, 4.6f, 0f))     // balcony band, 1st floor
                m.append(Shapes.box(22.6f, 0.4f, 16.6f, 0.4f, 0.38f, 0.36f), translate(0f, 14.0f, 0f))    // balcony band, top floor
                m.append(Shapes.box(22.8f, 0.6f, 16.8f, 0.3f, 0.29f, 0.31f), translate(0f, 19.0f, 0f))    // cornice
                m.append(Shapes.box(18f, 2.6f, 12f, 0.33f, 0.31f, 0.33f), translate(0f, 20.4f, 0f))        // mansard
                m.append(Shapes.box(1.2f, 2.0f, 1.2f, 0.6f, 0.55f, 0.5f), translate(5f, 22.2f, 2f))        // chimney
                m.append(Shapes.box(1.2f, 1.8f, 1.2f, 0.6f, 0.55f, 0.5f), translate(-6f, 22.1f, -3f))
            }
            app.rollentrainer.core.route.Prop.SNOW_POLE -> {
                m.append(Shapes.cylinder(0.06f, 0.06f, 2.5f, 5, 0.85f, 0.1f, 0.1f), id)
                m.append(Shapes.cylinder(0.065f, 0.065f, 0.4f, 5, 0.95f, 0.95f, 0.95f), translate(0f, 1.0f, 0f))
                m.append(Shapes.cylinder(0.065f, 0.065f, 0.3f, 5, 0.95f, 0.95f, 0.95f), translate(0f, 2.0f, 0f))
            }
            else -> {
                // Spectator: two legs, a pure white jersey (tinted per instance), head with cap, one arm
                // waving, one on the hip.
                for (x in floatArrayOf(-0.08f, 0.08f)) m.append(Shapes.cylinder(0.075f, 0.065f, 0.82f, 6, 0.2f, 0.2f, 0.25f), translate(x, 0f, 0f))
                m.append(Shapes.cylinder(0.21f, 0.19f, 0.62f, 7, 1f, 1f, 1f), translate(0f, 0.8f, 0f))
                m.append(Shapes.sphere(0.12f, 5, 7, 0.9f, 0.72f, 0.6f), translate(0f, 1.6f, 0f))
                m.append(Shapes.sphere(0.13f, 3, 7, 0.85f, 0.2f, 0.15f, squash = 0.5f), translate(0f, 1.66f, 0f))
                m.append(Shapes.cylinder(0.05f, 0.045f, 0.62f, 5, 0.9f, 0.72f, 0.6f), tilt(1.25f, -20f, 0.26f, 0f, 0f))
                m.append(Shapes.cylinder(0.05f, 0.045f, 0.5f, 5, 0.9f, 0.72f, 0.6f), tilt(1.3f, 160f, -0.24f, 0f, 0f))
            }
        }
        return m
    }

    fun translate(x: Float, y: Float, z: Float): FloatArray = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
        android.opengl.Matrix.translateM(it, 0, x, y, z)
    }

    /** Translation then a tilt of the local +y axis around z by [degrees], for boughs and arms. */
    private fun tilt(y: Float, degrees: Float, x: Float, z: Float, extraZ: Float): FloatArray = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
        android.opengl.Matrix.translateM(it, 0, x, y, z)
        android.opengl.Matrix.rotateM(it, 0, degrees, 0f, 0f, 1f)
        if (extraZ != 0f) android.opengl.Matrix.rotateM(it, 0, extraZ * 90f, 1f, 0f, 0f)
    }
}
