package app.rollentrainer.ui.world

import app.rollentrainer.core.route.PathSample
import app.rollentrainer.core.route.World
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Triangle mesh with interleaved vertices: position (3), normal (3), colour (3). Built on the CPU,
 * uploaded once by the renderer.
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

    /** A flat ring (wheel) in the x/y plane with its axle along z. */
    fun wheel(radius: Float, width: Float, segments: Int): MeshData {
        val m = MeshData(segments * 4)
        val rimR = 0.11f; val rimG = 0.11f; val rimB = 0.12f
        val inner = radius * 0.86f
        val front = IntArray(segments); val back = IntArray(segments)
        val frontI = IntArray(segments); val backI = IntArray(segments)
        for (i in 0 until segments) {
            val a = (2 * PI * i / segments).toFloat()
            val x = cos(a); val y = sin(a)
            front[i] = m.vertex(x * radius, y * radius, width / 2, x, y, 0f, rimR, rimG, rimB)
            back[i] = m.vertex(x * radius, y * radius, -width / 2, x, y, 0f, rimR, rimG, rimB)
            frontI[i] = m.vertex(x * inner, y * inner, width / 2, 0f, 0f, 1f, 0.75f, 0.75f, 0.78f)
            backI[i] = m.vertex(x * inner, y * inner, -width / 2, 0f, 0f, -1f, 0.75f, 0.75f, 0.78f)
        }
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            m.quad(front[i], back[i], back[j], front[j])
            m.quad(front[i], front[j], frontI[j], frontI[i])
            m.quad(back[j], back[i], backI[i], backI[j])
        }
        // Spokes: a thin disc so the wheel is not see-through at a distance.
        val c1 = m.vertex(0f, 0f, 0.004f, 0f, 0f, 1f, 0.55f, 0.55f, 0.58f)
        val c2 = m.vertex(0f, 0f, -0.004f, 0f, 0f, -1f, 0.55f, 0.55f, 0.58f)
        for (i in 0 until segments) {
            val j = (i + 1) % segments
            m.triangle(c1, frontI[i], frontI[j])
            m.triangle(c2, backI[j], backI[i])
        }
        return m
    }
}

/** Converts the generated world into meshes. */
object WorldMeshes {
    /** Terrain grid as a mesh with colours by material and a little noise so it does not look flat. */
    fun terrain(t: World.Terrain, biome: app.rollentrainer.core.route.Biome): MeshData {
        val m = MeshData(t.cols * t.rows)
        val ids = IntArray(t.cols * t.rows)
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val i = t.index(c, r)
            val x = t.x(c).toFloat(); val z = t.z(r).toFloat(); val y = t.heights[i]
            // Normal from the neighbouring heights.
            val hl = t.heights[t.index((c - 1).coerceAtLeast(0), r)]
            val hr = t.heights[t.index((c + 1).coerceAtMost(t.cols - 1), r)]
            val hd = t.heights[t.index(c, (r - 1).coerceAtLeast(0))]
            val hu = t.heights[t.index(c, (r + 1).coerceAtMost(t.rows - 1))]
            var nx = (hl - hr); var ny = (2 * t.cell).toFloat(); var nz = (hd - hu)
            val len = sqrt(nx * nx + ny * ny + nz * nz)
            nx /= len; ny /= len; nz /= len
            val col = materialColor(t.material[i].toInt(), biome)
            val jitter = (World.fbm(x * 0.05, z * 0.05) * 0.06).toFloat()
            ids[i] = m.vertex(x, y, z, nx, ny, nz, (col[0] + jitter).coerceIn(0f, 1f), (col[1] + jitter).coerceIn(0f, 1f), (col[2] + jitter).coerceIn(0f, 1f))
        }
        for (r in 0 until t.rows - 1) for (c in 0 until t.cols - 1) {
            val a = ids[t.index(c, r)]; val b = ids[t.index(c + 1, r)]
            val d = ids[t.index(c, r + 1)]; val e = ids[t.index(c + 1, r + 1)]
            m.triangle(a, d, b)
            m.triangle(b, d, e)
        }
        return m
    }

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

    /** Models of the scattered props, by kind. */
    fun prop(kind: Int): MeshData {
        val m = MeshData(256)
        val id = FloatArray(16).also { android.opengl.Matrix.setIdentityM(it, 0) }
        when (kind) {
            app.rollentrainer.core.route.Prop.CONIFER -> {
                m.append(Shapes.cylinder(0.22f, 0.16f, 2.2f, 6, 0.36f, 0.24f, 0.13f), id)
                m.append(Shapes.cylinder(2.6f, 0.05f, 4.5f, 8, 0.12f, 0.33f, 0.15f, capped = false), translate(0f, 1.6f, 0f))
                m.append(Shapes.cylinder(2.0f, 0.02f, 4.0f, 8, 0.14f, 0.38f, 0.17f, capped = false), translate(0f, 4.2f, 0f))
                m.append(Shapes.cylinder(1.3f, 0f, 3.2f, 8, 0.16f, 0.42f, 0.19f, capped = false), translate(0f, 6.6f, 0f))
            }
            app.rollentrainer.core.route.Prop.BROADLEAF -> {
                m.append(Shapes.cylinder(0.28f, 0.2f, 3.0f, 6, 0.4f, 0.28f, 0.16f), id)
                m.append(Shapes.sphere(3.2f, 5, 8, 0.24f, 0.5f, 0.2f, squash = 0.85f), translate(0f, 5.2f, 0f))
            }
            app.rollentrainer.core.route.Prop.ROCK -> m.append(Shapes.sphere(1.2f, 3, 6, 0.48f, 0.46f, 0.43f, squash = 0.6f), id)
            app.rollentrainer.core.route.Prop.BUILDING -> {
                m.append(Shapes.box(22f, 19f, 16f, 0.86f, 0.8f, 0.68f, 0.35f, 0.33f, 0.35f), translate(0f, 9.5f, 0f))
                m.append(Shapes.box(22.6f, 0.6f, 16.6f, 0.4f, 0.38f, 0.36f), translate(0f, 4.6f, 0f))
            }
            app.rollentrainer.core.route.Prop.SNOW_POLE -> {
                m.append(Shapes.cylinder(0.06f, 0.06f, 2.5f, 5, 0.85f, 0.1f, 0.1f), id)
                m.append(Shapes.cylinder(0.065f, 0.065f, 0.4f, 5, 0.95f, 0.95f, 0.95f), translate(0f, 1.0f, 0f))
            }
            else -> {
                // Spectator: legs, bright jersey, head, one raised arm.
                m.append(Shapes.cylinder(0.16f, 0.14f, 0.8f, 6, 0.2f, 0.2f, 0.25f), id)
                m.append(Shapes.cylinder(0.22f, 0.2f, 0.65f, 6, 1f, 1f, 1f), translate(0f, 0.8f, 0f))
                m.append(Shapes.sphere(0.13f, 4, 6, 0.9f, 0.72f, 0.6f), translate(0f, 1.62f, 0f))
                m.append(Shapes.cylinder(0.05f, 0.05f, 0.6f, 5, 0.9f, 0.72f, 0.6f), translate(0.26f, 1.2f, 0f))
            }
        }
        return m
    }

    fun translate(x: Float, y: Float, z: Float): FloatArray = FloatArray(16).also {
        android.opengl.Matrix.setIdentityM(it, 0)
        android.opengl.Matrix.translateM(it, 0, x, y, z)
    }
}
