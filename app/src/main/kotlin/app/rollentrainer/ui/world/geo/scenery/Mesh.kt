package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.Vtx
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Small mutable vector; the generators build a few hundred thousand of them per tile at most. */
internal class Vec3(var x: Float = 0f, var y: Float = 0f, var z: Float = 0f) {
    fun set(x: Float, y: Float, z: Float): Vec3 { this.x = x; this.y = y; this.z = z; return this }
    fun set(o: Vec3): Vec3 = set(o.x, o.y, o.z)
    fun copy() = Vec3(x, y, z)
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vec3(x * s, y * s, z * s)
    fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun len() = sqrt(x * x + y * y + z * z)
    fun norm(): Vec3 {
        val l = len()
        if (l > 1e-9f) { x /= l; y /= l; z /= l } else { x = 0f; y = 1f; z = 0f }
        return this
    }
    fun normalized() = copy().norm()
    fun addScaled(o: Vec3, s: Float): Vec3 { x += o.x * s; y += o.y * s; z += o.z * s; return this }
    fun lerp(o: Vec3, t: Float) = Vec3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    /** Any unit vector perpendicular to this (unit) vector. */
    fun anyPerp(): Vec3 = (if (abs(y) < 0.9f) cross(Vec3(0f, 1f, 0f)) else cross(Vec3(1f, 0f, 0f))).norm()
    override fun toString() = "(%.2f, %.2f, %.2f)".format(x, y, z)
}

/** A sub-rectangle of a texture atlas, in UV units (v = 0 is the top row of the image). */
internal class UvRect(val u0: Float, val v0: Float, val u1: Float, val v1: Float) {
    fun u(t: Float) = u0 + (u1 - u0) * t
    fun v(t: Float) = v0 + (v1 - v0) * t
    companion object {
        val FULL = UvRect(0f, 0f, 1f, 1f)
        /** Cell (col, row) of an n × m grid. */
        fun grid(col: Int, row: Int, cols: Int, rows: Int) =
            UvRect(col / cols.toFloat(), row / rows.toFloat(), (col + 1) / cols.toFloat(), (row + 1) / rows.toFloat())
    }
}

/**
 * Growable interleaved vertex + index buffer in the [Vtx] layout. Colours are linear RGB.
 */
internal class MeshBuilder(vertexCapacity: Int = 256, indexCapacity: Int = 768) {
    var v = FloatArray(vertexCapacity * Vtx.FLOATS); private set
    var ix = IntArray(indexCapacity); private set
    var vertexCount = 0; private set
    var indexCount = 0; private set
    val triangleCount: Int get() = indexCount / 3
    val isEmpty: Boolean get() = indexCount == 0

    fun vertex(
        px: Float, py: Float, pz: Float,
        nx: Float, ny: Float, nz: Float,
        u: Float, vv: Float,
        r: Float, g: Float, b: Float,
        extra: Float = 0f,
    ): Int {
        ensureVertices(1)
        val o = vertexCount * Vtx.FLOATS
        val nl = sqrt(nx * nx + ny * ny + nz * nz).let { if (it > 1e-9f) it else 1f }
        v[o] = px; v[o + 1] = py; v[o + 2] = pz
        v[o + 3] = nx / nl; v[o + 4] = ny / nl; v[o + 5] = nz / nl
        v[o + 6] = u; v[o + 7] = vv
        v[o + 8] = r; v[o + 9] = g; v[o + 10] = b
        v[o + 11] = extra
        return vertexCount++
    }

    fun vertex(p: Vec3, n: Vec3, u: Float, vv: Float, c: FloatArray, extra: Float = 0f): Int =
        vertex(p.x, p.y, p.z, n.x, n.y, n.z, u, vv, c[0], c[1], c[2], extra)

    fun tri(a: Int, b: Int, c: Int) {
        ensureIndices(3)
        ix[indexCount++] = a; ix[indexCount++] = b; ix[indexCount++] = c
    }

    /** Quad a-b-c-d, counter-clockwise seen from the front. */
    fun quad(a: Int, b: Int, c: Int, d: Int) {
        tri(a, b, c)
        tri(a, c, d)
    }

    fun ensureVertices(n: Int) {
        val need = (vertexCount + n) * Vtx.FLOATS
        if (need > v.size) v = v.copyOf(max(need, v.size * 2))
    }

    fun ensureIndices(n: Int) {
        val need = indexCount + n
        if (need > ix.size) ix = ix.copyOf(max(need, ix.size * 2))
    }

    fun posX(i: Int) = v[i * Vtx.FLOATS]
    fun posY(i: Int) = v[i * Vtx.FLOATS + 1]
    fun posZ(i: Int) = v[i * Vtx.FLOATS + 2]

    /** Multiplies the colour of vertices [from] until the end. */
    fun tintFrom(from: Int, r: Float, g: Float, b: Float) {
        for (i in from until vertexCount) {
            val o = i * Vtx.FLOATS + Vtx.COLOR
            v[o] *= r; v[o + 1] *= g; v[o + 2] *= b
        }
    }

    /**
     * Appends [o] rotated by [yaw] about +y (counter-clockwise from above, the [InstanceSet]
     * convention), scaled by [scale] and moved by (tx, ty, tz).
     */
    fun append(o: MeshBuilder, tx: Float = 0f, ty: Float = 0f, tz: Float = 0f, yaw: Float = 0f, scale: Float = 1f) {
        ensureVertices(o.vertexCount)
        ensureIndices(o.indexCount)
        val c = cos(yaw)
        val s = sin(yaw)
        val base = vertexCount
        for (i in 0 until o.vertexCount) {
            val a = i * Vtx.FLOATS
            val d = (base + i) * Vtx.FLOATS
            val x = o.v[a]; val y = o.v[a + 1]; val z = o.v[a + 2]
            v[d] = (x * c + z * s) * scale + tx
            v[d + 1] = y * scale + ty
            v[d + 2] = (-x * s + z * c) * scale + tz
            val nx = o.v[a + 3]; val nz = o.v[a + 5]
            v[d + 3] = nx * c + nz * s
            v[d + 4] = o.v[a + 4]
            v[d + 5] = -nx * s + nz * c
            for (k in 6 until Vtx.FLOATS) v[d + k] = o.v[a + k]
        }
        for (i in 0 until o.indexCount) ix[indexCount + i] = o.ix[i] + base
        vertexCount += o.vertexCount
        indexCount += o.indexCount
    }

    fun build(): MeshBuffer = MeshBuffer(v.copyOf(vertexCount * Vtx.FLOATS), vertexCount, ix.copyOf(indexCount), indexCount)

    fun clear() { vertexCount = 0; indexCount = 0 }

    /** Axis-aligned bounds: minX, minY, minZ, maxX, maxY, maxZ. */
    fun bounds(): FloatArray {
        val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (i in 0 until vertexCount) {
            val o = i * Vtx.FLOATS
            for (k in 0..2) {
                b[k] = minOf(b[k], v[o + k])
                b[k + 3] = maxOf(b[k + 3], v[o + k])
            }
        }
        return b
    }
}

/** Common solids and strips. Colours are linear RGB triples. */
internal object Shapes {
    val WHITE = floatArrayOf(1f, 1f, 1f)

    /**
     * A generalised cylinder along [pts] with [radii], about [sides0] sides. UVs: u goes [uRepeat]
     * times around (each repeat spans the whole width of [rect]), v advances by length / [vTileM]
     * and restarts inside [rect] at every whole tile: an atlas region cannot use GL_REPEAT, so rings
     * on tile borders are emitted twice (v = 1 closing one tile, v = 0 opening the next) and the
     * seams of the u repeats get their own vertices. [colours] and [sway] are per point.
     * [capEnd] closes the far end with a short cone (branch tips).
     */
    fun tube(
        mb: MeshBuilder, pts: List<Vec3>, radii: FloatArray, sides0: Int, rect: UvRect, vTileM: Float,
        colours: List<FloatArray>, sway: FloatArray, uRepeat: Int = 1, capEnd: Boolean = false,
    ) {
        val n = pts.size
        if (n < 2) return
        val rep = maxOf(1, uRepeat)
        val per = maxOf(2, (sides0 + rep - 1) / rep)
        // Parallel-transport frames so the tube does not twist at bends.
        val tangents = Array(n) { i -> (pts[minOf(n - 1, i + 1)] - pts[maxOf(0, i - 1)]).norm() }
        val normals = arrayOfNulls<Vec3>(n)
        normals[0] = tangents[0].anyPerp()
        for (i in 1 until n) {
            val prev = normals[i - 1]!!
            val t = tangents[i]
            val proj = prev - t * prev.dot(t)
            normals[i] = if (proj.len() < 1e-4f) t.anyPerp() else proj.norm()
        }
        val len = FloatArray(n)
        for (i in 1 until n) len[i] = len[i - 1] + (pts[i] - pts[i - 1]).len()

        fun ringAt(i: Int, f: Float, v: Float): Int {
            val j = minOf(n - 1, i + 1)
            val c = pts[i].lerp(pts[j], f)
            val t = tangents[i].lerp(tangents[j], f).norm()
            val n0 = normals[i]!!.lerp(normals[j]!!, f)
            val n1 = (n0 - t * n0.dot(t)).let { if (it.len() < 1e-4f) t.anyPerp() else it.norm() }
            val b = t.cross(n1).norm()
            val r = radii[i] + (radii[j] - radii[i]) * f
            val col = mix(colours[i], colours[j], f)
            val sw = sway[i] + (sway[j] - sway[i]) * f
            val first = mb.vertexCount
            val vv = rect.v(1f - v)
            for (q in 0 until rep) {
                for (k in 0..per) {
                    val a = ((q * per + k) % (per * rep)) * (2.0 * Math.PI / (per * rep)).toFloat()
                    val ca = cos(a)
                    val sa = sin(a)
                    val nx = n1.x * ca + b.x * sa
                    val ny = n1.y * ca + b.y * sa
                    val nz = n1.z * ca + b.z * sa
                    mb.vertex(c.x + nx * r, c.y + ny * r, c.z + nz * r, nx, ny, nz, rect.u(k.toFloat() / per), vv, col[0], col[1], col[2], sw)
                }
            }
            return first
        }

        fun connect(a: Int, b: Int) {
            for (q in 0 until rep) {
                val o = q * (per + 1)
                for (k in 0 until per) mb.quad(a + o + k, a + o + k + 1, b + o + k + 1, b + o + k)
            }
        }

        var tileStart = 0f
        var prev = ringAt(0, 0f, 0f)
        var lastV = 0f
        for (i in 0 until n - 1) {
            val l0 = len[i] / vTileM
            val l1 = len[i + 1] / vTileM
            // Tile borders inside this segment.
            var k = kotlin.math.floor(l0).toInt() + 1
            while (k < l1 - 1e-4f) {
                val f = if (l1 > l0) (k - l0) / (l1 - l0) else 0f
                val end = ringAt(i, f, 1f)
                connect(prev, end)
                tileStart = k.toFloat()
                prev = ringAt(i, f, 0f)
                k++
            }
            lastV = (l1 - tileStart).coerceIn(0f, 1f)
            val r = ringAt(i + 1, 0f, lastV)
            connect(prev, r)
            prev = r
        }
        if (capEnd) {
            val tip = pts[n - 1] + tangents[n - 1] * (radii[n - 1] * 2.5f)
            val c = colours[n - 1]
            val t = mb.vertex(tip, tangents[n - 1], rect.u(0.5f), rect.v(1f - minOf(1f, lastV + 0.02f)), c, sway[n - 1])
            for (q in 0 until rep) {
                val o = q * (per + 1)
                for (kk in 0 until per) mb.tri(prev + o + kk, prev + o + kk + 1, t)
            }
        }
    }

    /**
     * A flat card centred at [c] spanning [right] × [up] (half extents given by the vectors' lengths
     * times w/2, h/2). Normals per corner are supplied by [normalAt] so foliage can use
     * crown-shaped normals (light falls on a canopy, not on a few hundred flat cards).
     */
    fun card(
        mb: MeshBuilder, c: Vec3, right: Vec3, up: Vec3, w: Float, h: Float, rect: UvRect,
        colour: FloatArray, swayAt: (Vec3) -> Float, normalAt: (Vec3) -> Vec3, flipU: Boolean = false,
        bottomColour: FloatArray = colour,
    ) {
        val hw = w * 0.5f
        val hh = h * 0.5f
        val corners = arrayOf(
            c + right * (-hw) + up * (-hh),
            c + right * hw + up * (-hh),
            c + right * hw + up * hh,
            c + right * (-hw) + up * hh,
        )
        val us = if (flipU) floatArrayOf(1f, 0f, 0f, 1f) else floatArrayOf(0f, 1f, 1f, 0f)
        val vs = floatArrayOf(1f, 1f, 0f, 0f)
        val first = mb.vertexCount
        for (k in 0..3) {
            val p = corners[k]
            val n = normalAt(p)
            val col = if (k < 2) bottomColour else colour
            mb.vertex(p, n, rect.u(us[k]), rect.v(vs[k]), col, swayAt(p))
        }
        mb.quad(first, first + 1, first + 2, first + 3)
    }

    /** Axis-aligned box with per-face normals, uv in metres / [uvTileM] (0 = no texture coords). */
    fun box(
        mb: MeshBuilder, x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float,
        colour: FloatArray, uvTileM: Float = 0f, rect: UvRect = UvRect.FULL, bottom: Boolean = false, sway: Float = 0f,
        topColour: FloatArray = colour,
    ) {
        fun face(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, cx: Float, cy: Float, cz: Float, dx: Float, dy: Float, dz: Float,
                 nx: Float, ny: Float, nz: Float, wu: Float, hv: Float, col: FloatArray) {
            val su = if (uvTileM > 0f) wu / uvTileM else 1f
            val sv = if (uvTileM > 0f) hv / uvTileM else 1f
            val f = mb.vertexCount
            mb.vertex(ax, ay, az, nx, ny, nz, rect.u(0f), rect.v(sv), col[0], col[1], col[2], sway)
            mb.vertex(bx, by, bz, nx, ny, nz, rect.u(su), rect.v(sv), col[0], col[1], col[2], sway)
            mb.vertex(cx, cy, cz, nx, ny, nz, rect.u(su), rect.v(0f), col[0], col[1], col[2], sway)
            mb.vertex(dx, dy, dz, nx, ny, nz, rect.u(0f), rect.v(0f), col[0], col[1], col[2], sway)
            mb.quad(f, f + 1, f + 2, f + 3)
        }
        val w = x1 - x0; val h = y1 - y0; val d = z1 - z0
        // front (+z), back (-z), right (+x), left (-x), top, bottom
        face(x0, y0, z1, x1, y0, z1, x1, y1, z1, x0, y1, z1, 0f, 0f, 1f, w, h, colour)
        face(x1, y0, z0, x0, y0, z0, x0, y1, z0, x1, y1, z0, 0f, 0f, -1f, w, h, colour)
        face(x1, y0, z1, x1, y0, z0, x1, y1, z0, x1, y1, z1, 1f, 0f, 0f, d, h, colour)
        face(x0, y0, z0, x0, y0, z1, x0, y1, z1, x0, y1, z0, -1f, 0f, 0f, d, h, colour)
        face(x0, y1, z1, x1, y1, z1, x1, y1, z0, x0, y1, z0, 0f, 1f, 0f, w, d, topColour)
        if (bottom) face(x0, y0, z0, x1, y0, z0, x1, y0, z1, x0, y0, z1, 0f, -1f, 0f, w, d, colour)
    }

    /**
     * A vertical prism (n-gon) from y0 to y1, radius r, optional top cap; used for posts, poles
     * and lamp columns where a box looks too square up close.
     */
    fun prism(mb: MeshBuilder, cx: Float, cz: Float, y0: Float, y1: Float, r0: Float, r1: Float, sides: Int, colour: FloatArray,
              top: Boolean = true, rect: UvRect? = null, topColour: FloatArray = colour) {
        val first = mb.vertexCount
        for (k in 0..sides) {
            val a = (k % sides) * (2.0 * Math.PI / sides).toFloat()
            val ca = cos(a); val sa = sin(a)
            val u = rect?.u(k.toFloat() / sides) ?: 0f
            mb.vertex(cx + ca * r0, y0, cz + sa * r0, ca, 0f, sa, u, rect?.v(1f) ?: 0f, colour[0], colour[1], colour[2])
            mb.vertex(cx + ca * r1, y1, cz + sa * r1, ca, 0f, sa, u, rect?.v(0f) ?: 0f, colour[0], colour[1], colour[2])
        }
        for (k in 0 until sides) {
            val a = first + k * 2
            // bottom k, top k, top k+1, bottom k+1: counter-clockwise seen from outside
            mb.quad(a, a + 1, a + 3, a + 2)
        }
        if (top && r1 > 0f) {
            val c = mb.vertex(cx, y1, cz, 0f, 1f, 0f, 0f, 0f, topColour[0], topColour[1], topColour[2])
            val f2 = mb.vertexCount
            for (k in 0 until sides) {
                val a = (k % sides) * (2.0 * Math.PI / sides).toFloat()
                mb.vertex(cx + cos(a) * r1, y1, cz + sin(a) * r1, 0f, 1f, 0f, 0f, 0f, topColour[0], topColour[1], topColour[2])
            }
            for (k in 0 until sides) mb.tri(c, f2 + (k + 1) % sides, f2 + k)
        }
    }
}

/** sRGB hex colour to linear RGB, so colours can be written as they look. */
internal fun lin(hex: Int): FloatArray {
    fun ch(c: Int): Float {
        val s = c / 255f
        return if (s <= 0.04045f) s / 12.92f else Math.pow(((s + 0.055f) / 1.055f).toDouble(), 2.4).toFloat()
    }
    return floatArrayOf(ch((hex shr 16) and 0xFF), ch((hex shr 8) and 0xFF), ch(hex and 0xFF))
}

internal fun mul(c: FloatArray, s: Float) = floatArrayOf(c[0] * s, c[1] * s, c[2] * s)
internal fun mix(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)
