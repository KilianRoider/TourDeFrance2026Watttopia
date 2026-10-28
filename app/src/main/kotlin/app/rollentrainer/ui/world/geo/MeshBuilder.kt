package app.rollentrainer.ui.world.geo

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A growable [MeshBuffer] under construction (CPU only, so it runs on the streaming worker and in JVM
 * tests). Used by the road builder and the stub generators; W2/W3 may use it too.
 */
class MeshBuilder(initialVertices: Int = 256, initialIndices: Int = initialVertices * 2) {
    var vertices = FloatArray(initialVertices * Vtx.FLOATS)
        private set
    var indices = IntArray(initialIndices)
        private set
    var vertexCount = 0
        private set
    var indexCount = 0
        private set

    val isEmpty: Boolean get() = indexCount == 0

    fun vertex(
        x: Float, y: Float, z: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float = 0f, v: Float = 0f,
        r: Float = 1f, g: Float = 1f, b: Float = 1f,
        extra: Float = 0f,
    ): Int {
        if ((vertexCount + 1) * Vtx.FLOATS > vertices.size) vertices = vertices.copyOf(maxOf(vertices.size * 2, Vtx.FLOATS * 64))
        val o = vertexCount * Vtx.FLOATS
        val a = vertices
        a[o] = x; a[o + 1] = y; a[o + 2] = z
        a[o + 3] = nx; a[o + 4] = ny; a[o + 5] = nz
        a[o + 6] = u; a[o + 7] = v
        a[o + 8] = r; a[o + 9] = g; a[o + 10] = b
        a[o + 11] = extra
        return vertexCount++
    }

    fun triangle(a: Int, b: Int, c: Int) {
        if (indexCount + 3 > indices.size) indices = indices.copyOf(maxOf(indices.size * 2, 192))
        indices[indexCount] = a; indices[indexCount + 1] = b; indices[indexCount + 2] = c
        indexCount += 3
    }

    /** Two triangles a-b-c, a-c-d (counter-clockwise seen from the front). */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        triangle(a, b, c)
        triangle(a, c, d)
    }

    fun build(): MeshBuffer = MeshBuffer(vertices.copyOf(vertexCount * Vtx.FLOATS), vertexCount, indices.copyOf(indexCount), indexCount)

    /** Appends [m] moved by (dx, dy, dz), turned by [yaw] about y and scaled by [s]; colour multiplied by (r, g, b). */
    fun append(m: MeshBuffer, dx: Float = 0f, dy: Float = 0f, dz: Float = 0f, yaw: Float = 0f, s: Float = 1f, r: Float = 1f, g: Float = 1f, b: Float = 1f) {
        val c = cos(yaw)
        val sn = sin(yaw)
        val base = vertexCount
        val v = m.vertices
        for (i in 0 until m.vertexCount) {
            val o = i * Vtx.FLOATS
            val px = v[o] * s
            val pz = v[o + 2] * s
            val nx = v[o + 3]
            val nz = v[o + 5]
            vertex(
                px * c + pz * sn + dx, v[o + 1] * s + dy, -px * sn + pz * c + dz,
                nx * c + nz * sn, v[o + 4], -nx * sn + nz * c,
                v[o + 6], v[o + 7], v[o + 8] * r, v[o + 9] * g, v[o + 10] * b, v[o + 11],
            )
        }
        for (i in 0 until m.indexCount step 3) triangle(base + m.indices[i], base + m.indices[i + 1], base + m.indices[i + 2])
    }

    // --- simple solids (flat shaded, colour per solid) --------------------------------------------

    /** An axis-aligned box centred at (cx, cy, cz) with UVs in metres on every face. */
    fun box(cx: Float, cy: Float, cz: Float, sx: Float, sy: Float, sz: Float, r: Float, g: Float, b: Float, extra: Float = 0f) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        fun face(nx: Float, ny: Float, nz: Float, ux: Float, uy: Float, uz: Float, vx: Float, vy: Float, vz: Float, hu: Float, hv: Float, hn: Float) {
            val ox = cx + nx * hn; val oy = cy + ny * hn; val oz = cz + nz * hn
            val i0 = vertex(ox - ux * hu - vx * hv, oy - uy * hu - vy * hv, oz - uz * hu - vz * hv, nx, ny, nz, 0f, 0f, r, g, b, extra)
            val i1 = vertex(ox + ux * hu - vx * hv, oy + uy * hu - vy * hv, oz + uz * hu - vz * hv, nx, ny, nz, 2 * hu, 0f, r, g, b, extra)
            val i2 = vertex(ox + ux * hu + vx * hv, oy + uy * hu + vy * hv, oz + uz * hu + vz * hv, nx, ny, nz, 2 * hu, 2 * hv, r, g, b, extra)
            val i3 = vertex(ox - ux * hu + vx * hv, oy - uy * hu + vy * hv, oz - uz * hu + vz * hv, nx, ny, nz, 0f, 2 * hv, r, g, b, extra)
            quad(i0, i1, i2, i3)
        }
        face(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f, hx, hy, hz)
        face(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f, hx, hy, hz)
        face(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f, hz, hy, hx)
        face(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, hz, hy, hx)
        face(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f, hx, hz, hy)
        face(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, hx, hz, hy)
    }

    /**
     * A tapered cylinder along +y from (cx, y0, cz) with [segments] smooth sides; [extra0]/[extra1] at the
     * bottom/top ring (e.g. a sway weight that grows with height).
     */
    fun cylinder(
        cx: Float, y0: Float, cz: Float, h: Float, r0: Float, r1: Float, segments: Int,
        r: Float, g: Float, b: Float, extra0: Float = 0f, extra1: Float = 0f, capTop: Boolean = true,
    ) {
        val base = vertexCount
        val slope = (r0 - r1) / h
        val ny = slope / sqrt(1 + slope * slope)
        val nr = 1 / sqrt(1 + slope * slope)
        for (i in 0..segments) {
            val a = (i.toFloat() / segments * 2 * PI).toFloat()
            val c = cos(a); val s = sin(a)
            vertex(cx + c * r0, y0, cz + s * r0, c * nr, ny, s * nr, i.toFloat() / segments * 2 * PI.toFloat() * r0, 0f, r, g, b, extra0)
            vertex(cx + c * r1, y0 + h, cz + s * r1, c * nr, ny, s * nr, i.toFloat() / segments * 2 * PI.toFloat() * r0, h, r, g, b, extra1)
        }
        for (i in 0 until segments) {
            val a = base + i * 2
            quad(a, a + 1, a + 3, a + 2)
        }
        if (capTop && r1 > 0.001f) {
            val centre = vertex(cx, y0 + h, cz, 0f, 1f, 0f, 0f, 0f, r, g, b, extra1)
            val ring = vertexCount
            for (i in 0..segments) {
                val a = (i.toFloat() / segments * 2 * PI).toFloat()
                vertex(cx + cos(a) * r1, y0 + h, cz + sin(a) * r1, 0f, 1f, 0f, 0f, 0f, r, g, b, extra1)
            }
            for (i in 0 until segments) triangle(centre, ring + i + 1, ring + i)
        }
    }

    /** A cone or spheroid-ish blob: rings of radius rad(t) for t in 0..1 from y0 to y0 + h (smooth normals). */
    fun lathe(cx: Float, y0: Float, cz: Float, h: Float, rings: Int, segments: Int, r: Float, g: Float, b: Float, extraAt: (Float) -> Float = { 0f }, rad: (Float) -> Float) {
        val base = vertexCount
        for (j in 0..rings) {
            val t = j.toFloat() / rings
            val rr = rad(t)
            val dr = (rad((t + 0.01f).coerceAtMost(1f)) - rad((t - 0.01f).coerceAtLeast(0f))) / (0.02f * h)
            for (i in 0..segments) {
                val a = (i.toFloat() / segments * 2 * PI).toFloat()
                val c = cos(a); val s = sin(a)
                val ny = -dr
                val l = sqrt(1 + ny * ny)
                vertex(cx + c * rr, y0 + t * h, cz + s * rr, c / l, ny / l, s / l, i.toFloat() / segments, t, r, g, b, extraAt(t))
            }
        }
        val row = segments + 1
        for (j in 0 until rings) for (i in 0 until segments) {
            val a = base + j * row + i
            quad(a, a + row, a + row + 1, a + 1)
        }
    }
}
