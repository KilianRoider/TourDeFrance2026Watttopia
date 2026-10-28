package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.Vtx
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Small CPU mesh toolkit for the race dressing: everything here runs once per scene (prototypes) or on
 * the streaming worker (one-off banner meshes), never on the GL thread, so it favours clarity over
 * allocation-free code. Coordinates follow the scene contract: x right, y up, -z forward.
 */

/** A 3D vector; immutable so poses can be built from expressions without aliasing bugs. */
data class V3(val x: Float, val y: Float, val z: Float) {
    operator fun plus(o: V3) = V3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: V3) = V3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Float) = V3(x * k, y * k, z * k)
    operator fun unaryMinus() = V3(-x, -y, -z)
    infix fun dot(o: V3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: V3) = V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    val length: Float get() = sqrt(x * x + y * y + z * z)
    fun norm(): V3 {
        val l = length
        return if (l > 1e-9f) V3(x / l, y / l, z / l) else V3(0f, 1f, 0f)
    }
    fun lerp(o: V3, t: Float) = V3(x + (o.x - x) * t, y + (o.y - y) * t, z + (o.z - z) * t)
    fun withX(v: Float) = V3(v, y, z)
    fun mirrorX() = V3(-x, y, z)

    companion object {
        val ZERO = V3(0f, 0f, 0f)
        val X = V3(1f, 0f, 0f)
        val Y = V3(0f, 1f, 0f)
        val Z = V3(0f, 0f, 1f)
    }
}

fun v3(x: Number, y: Number, z: Number) = V3(x.toFloat(), y.toFloat(), z.toFloat())

/** Row-major 3x4 affine transform: p' = R p + t. */
class Affine(val m: FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f)) {
    fun apply(p: V3) = V3(
        m[0] * p.x + m[1] * p.y + m[2] * p.z + m[3],
        m[4] * p.x + m[5] * p.y + m[6] * p.z + m[7],
        m[8] * p.x + m[9] * p.y + m[10] * p.z + m[11],
    )

    fun applyDir(d: V3) = V3(m[0] * d.x + m[1] * d.y + m[2] * d.z, m[4] * d.x + m[5] * d.y + m[6] * d.z, m[8] * d.x + m[9] * d.y + m[10] * d.z)

    /** Normals need the inverse transpose so non-uniform scales (squashed ellipsoids) keep them perpendicular. */
    fun applyNormal(n: V3): V3 {
        val a = m[0]; val b = m[1]; val c = m[2]
        val d = m[4]; val e = m[5]; val f = m[6]
        val g = m[8]; val h = m[9]; val i = m[10]
        // Cofactor matrix C = det * (M^-1)^T; multiplying by sign(det) keeps the normal's side.
        val c00 = e * i - f * h; val c01 = -(d * i - f * g); val c02 = d * h - e * g
        val c10 = -(b * i - c * h); val c11 = a * i - c * g; val c12 = -(a * h - b * g)
        val c20 = b * f - c * e; val c21 = -(a * f - c * d); val c22 = a * e - b * d
        val det = a * c00 + b * c01 + c * c02
        val k = if (det < 0) -1f else 1f
        return V3(
            k * (c00 * n.x + c01 * n.y + c02 * n.z),
            k * (c10 * n.x + c11 * n.y + c12 * n.z),
            k * (c20 * n.x + c21 * n.y + c22 * n.z),
        ).norm()
    }

    /** this ∘ o: first [o], then this. */
    operator fun times(o: Affine): Affine {
        val a = m
        val b = o.m
        val r = FloatArray(12)
        for (row in 0..2) {
            for (col in 0..3) {
                var v = a[row * 4] * b[col] + a[row * 4 + 1] * b[4 + col] + a[row * 4 + 2] * b[8 + col]
                if (col == 3) v += a[row * 4 + 3]
                r[row * 4 + col] = v
            }
        }
        return Affine(r)
    }

    val determinant: Float get() = m[0] * (m[5] * m[10] - m[6] * m[9]) - m[1] * (m[4] * m[10] - m[6] * m[8]) + m[2] * (m[4] * m[9] - m[5] * m[8])

    companion object {
        val IDENTITY = Affine()
        fun translate(x: Float, y: Float, z: Float) = Affine(floatArrayOf(1f, 0f, 0f, x, 0f, 1f, 0f, y, 0f, 0f, 1f, z))
        fun translate(p: V3) = translate(p.x, p.y, p.z)
        fun scale(x: Float, y: Float = x, z: Float = x) = Affine(floatArrayOf(x, 0f, 0f, 0f, 0f, y, 0f, 0f, 0f, 0f, z, 0f))
        fun rotX(a: Float): Affine { val c = cos(a); val s = sin(a); return Affine(floatArrayOf(1f, 0f, 0f, 0f, 0f, c, -s, 0f, 0f, s, c, 0f)) }
        /** Counter-clockwise seen from above (+y), the contract's yaw convention. */
        fun rotY(a: Float): Affine { val c = cos(a); val s = sin(a); return Affine(floatArrayOf(c, 0f, s, 0f, 0f, 1f, 0f, 0f, -s, 0f, c, 0f)) }
        fun rotZ(a: Float): Affine { val c = cos(a); val s = sin(a); return Affine(floatArrayOf(c, -s, 0f, 0f, s, c, 0f, 0f, 0f, 0f, 1f, 0f)) }
        /** Maps local +y onto [dir] (unit) with local +x as close to [side] as possible. */
        fun basis(origin: V3, dir: V3, side: V3 = V3.X): Affine {
            val y = dir.norm()
            var z = (side cross y)
            if (z.length < 1e-4f) z = (V3.Z cross y)
            if (z.length < 1e-4f) z = (V3.X cross y)
            z = z.norm()
            val x = (y cross z).norm()
            return Affine(floatArrayOf(x.x, y.x, z.x, origin.x, x.y, y.y, z.y, origin.y, x.z, y.z, z.z, origin.z))
        }
        fun mirrorX() = scale(-1f, 1f, 1f)
    }
}

/** Colour helpers: the contract's vertex colours and kit colours are linear RGB. */
object Col {
    fun lin(c: Float): Float = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).toDouble().pow(2.4).toFloat()
    fun toSrgb(c: Float): Float = if (c <= 0.0031308f) c * 12.92f else (1.055 * c.toDouble().pow(1 / 2.4) - 0.055).toFloat()
    /** sRGB hex (as designers pick colours) to linear floats. */
    fun hex(rgb: Int): FloatArray = floatArrayOf(lin(((rgb shr 16) and 255) / 255f), lin(((rgb shr 8) and 255) / 255f), lin((rgb and 255) / 255f))
    fun scale(c: FloatArray, k: Float) = floatArrayOf(c[0] * k, c[1] * k, c[2] * k)
    fun mix(a: FloatArray, b: FloatArray, t: Float) = floatArrayOf(a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t)

    /**
     * Instance tint packed as the contract wants it (0xRRGGBB, a float-exact int). The bytes are LINEAR
     * values, because the renderer multiplies the linear vertex colour by them directly.
     */
    fun packTint(lin: FloatArray): Float {
        val r = (lin[0].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val g = (lin[1].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val b = (lin[2].coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return ((r shl 16) or (g shl 8) or b).toFloat()
    }

    fun packTintHex(srgb: Int): Float = packTint(hex(srgb))

    const val WHITE_TINT = 0xFFFFFF.toFloat()
}

/**
 * Accumulates interleaved vertices in the [Vtx] layout. State (colour, extra, transform) applies to
 * the primitives added after it is set, which keeps model code short.
 */
class MeshBuilder(initialVertices: Int = 256) {
    private var v = FloatArray(initialVertices * Vtx.FLOATS)
    private var idx = IntArray(initialVertices * 2)
    var vertexCount = 0; private set
    var indexCount = 0; private set
    val triangles: Int get() = indexCount / 3

    var color: FloatArray = floatArrayOf(1f, 1f, 1f)
    var extra: Float = 0f
    var xf: Affine = Affine.IDENTITY

    fun color(c: FloatArray): MeshBuilder { color = c; return this }
    fun hex(rgb: Int): MeshBuilder { color = Col.hex(rgb); return this }

    /** Runs [block] with [t] applied after the current transform, then restores it. */
    inline fun <T> with(t: Affine, block: () -> T): T {
        val saved = xf
        xf = saved * t
        try { return block() } finally { xf = saved }
    }

    inline fun <T> withColor(c: FloatArray, block: () -> T): T {
        val saved = color
        color = c
        try { return block() } finally { color = saved }
    }

    inline fun <T> withExtra(e: Float, block: () -> T): T {
        val saved = extra
        extra = e
        try { return block() } finally { extra = saved }
    }

    /** Adds a vertex given in local coordinates (transformed by [xf]); returns its index. */
    fun vertex(p: V3, n: V3, u: Float = 0f, vv: Float = 0f, c: FloatArray = color, e: Float = extra): Int {
        val wp = xf.apply(p)
        val wn = xf.applyNormal(n)
        return rawVertex(wp, wn, u, vv, c, e)
    }

    fun rawVertex(p: V3, n: V3, u: Float, vv: Float, c: FloatArray, e: Float): Int {
        ensureV(vertexCount + 1)
        val o = vertexCount * Vtx.FLOATS
        v[o] = p.x; v[o + 1] = p.y; v[o + 2] = p.z
        v[o + 3] = n.x; v[o + 4] = n.y; v[o + 5] = n.z
        v[o + 6] = u; v[o + 7] = vv
        v[o + 8] = c[0]; v[o + 9] = c[1]; v[o + 10] = c[2]
        v[o + 11] = e
        return vertexCount++
    }

    fun tri(a: Int, b: Int, c: Int) {
        ensureI(indexCount + 3)
        // A mirroring transform flips the winding; keep triangles front-facing outward (CCW).
        if (xf.determinant < 0) { idx[indexCount++] = a; idx[indexCount++] = c; idx[indexCount++] = b } else { idx[indexCount++] = a; idx[indexCount++] = b; idx[indexCount++] = c }
    }

    fun quad(a: Int, b: Int, c: Int, d: Int) { tri(a, b, c); tri(a, c, d) }

    private fun ensureV(n: Int) { if (n * Vtx.FLOATS > v.size) v = v.copyOf(maxOf(v.size * 2, n * Vtx.FLOATS)) }
    private fun ensureI(n: Int) { if (n > idx.size) idx = idx.copyOf(maxOf(idx.size * 2, n)) }

    fun isEmpty() = indexCount == 0

    fun build(): MeshBuffer = MeshBuffer(v.copyOf(vertexCount * Vtx.FLOATS), vertexCount, idx.copyOf(indexCount), indexCount)

    /** Appends another mesh (already in its final coordinates) through the current transform. */
    fun append(m: MeshBuffer, recolor: FloatArray? = null) {
        val base = vertexCount
        for (i in 0 until m.vertexCount) {
            val o = i * Vtx.FLOATS
            val a = m.vertices
            vertex(V3(a[o], a[o + 1], a[o + 2]), V3(a[o + 3], a[o + 4], a[o + 5]), a[o + 6], a[o + 7], recolor ?: floatArrayOf(a[o + 8], a[o + 9], a[o + 10]), a[o + 11])
        }
        for (i in 0 until m.indexCount step 3) tri(base + m.indices[i], base + m.indices[i + 1], base + m.indices[i + 2])
    }

    // --- primitives ---------------------------------------------------------------------------

    /** Axis-aligned box centred at [c], flat shaded; UVs per face 0..1. */
    fun box(c: V3, sx: Float, sy: Float, sz: Float, skipBottom: Boolean = false) {
        val hx = sx / 2; val hy = sy / 2; val hz = sz / 2
        val faces = arrayOf(
            V3.X to arrayOf(v3(hx, -hy, hz), v3(hx, -hy, -hz), v3(hx, hy, -hz), v3(hx, hy, hz)),
            -V3.X to arrayOf(v3(-hx, -hy, -hz), v3(-hx, -hy, hz), v3(-hx, hy, hz), v3(-hx, hy, -hz)),
            V3.Y to arrayOf(v3(-hx, hy, hz), v3(hx, hy, hz), v3(hx, hy, -hz), v3(-hx, hy, -hz)),
            -V3.Y to arrayOf(v3(-hx, -hy, -hz), v3(hx, -hy, -hz), v3(hx, -hy, hz), v3(-hx, -hy, hz)),
            V3.Z to arrayOf(v3(-hx, -hy, hz), v3(hx, -hy, hz), v3(hx, hy, hz), v3(-hx, hy, hz)),
            -V3.Z to arrayOf(v3(hx, -hy, -hz), v3(-hx, -hy, -hz), v3(-hx, hy, -hz), v3(hx, hy, -hz)),
        )
        for ((n, q) in faces) {
            if (skipBottom && n.y < -0.5f) continue
            val i0 = vertex(c + q[0], n, 0f, 0f)
            val i1 = vertex(c + q[1], n, 1f, 0f)
            val i2 = vertex(c + q[2], n, 1f, 1f)
            val i3 = vertex(c + q[3], n, 0f, 1f)
            quad(i0, i1, i2, i3)
        }
    }

    /** Box between two corner points. */
    fun boxMinMax(a: V3, b: V3, skipBottom: Boolean = false) =
        box((a + b) * 0.5f, abs(b.x - a.x), abs(b.y - a.y), abs(b.z - a.z), skipBottom)

    /**
     * A tube from [a] to [b] with elliptical cross-sections ([ra] at a, [rb] at b; [flat] < 1 squashes
     * the section along [side]); smooth normals. Extra values ramp from [ea] to [eb] (arm weights).
     */
    fun tube(a: V3, b: V3, ra: Float, rb: Float, sides: Int, capA: Boolean = true, capB: Boolean = true, side: V3 = V3.X, flat: Float = 1f, ea: Float = extra, eb: Float = extra, rings: Int = 1) {
        val dir = b - a
        val len = dir.length
        if (len < 1e-5f) return
        val f = Affine.basis(a, dir, side)
        val ringStart = IntArray(rings + 1)
        for (r in 0..rings) {
            val t = r.toFloat() / rings
            val rad = ra + (rb - ra) * t
            val e = ea + (eb - ea) * t
            ringStart[r] = vertexCount
            for (s in 0 until sides) {
                val ang = 2 * PI.toFloat() * s / sides
                val lx = cos(ang) * rad
                val lz = sin(ang) * rad * flat
                val p = f.apply(v3(lx, len * t, lz))
                // Normal of a cone: include the radius slope along the axis.
                val slope = (ra - rb) / len
                val nl = f.applyDir(v3(cos(ang) * flat, slope, sin(ang))).norm()
                vertex(p, nl, s.toFloat() / sides, t, color, e)
            }
        }
        for (r in 0 until rings) {
            for (s in 0 until sides) {
                val s1 = (s + 1) % sides
                val i0 = ringStart[r] + s; val i1 = ringStart[r] + s1
                val i2 = ringStart[r + 1] + s1; val i3 = ringStart[r + 1] + s
                quad(i0, i3, i2, i1)
            }
        }
        // The ring runs counter-clockwise seen from +axis: the cap at a faces -axis, the one at b +axis.
        if (capA) fan(ringStart[0], sides, a, -dir.norm(), facesMinusAxis = true, e = ea)
        if (capB) fan(ringStart[rings], sides, b, dir.norm(), facesMinusAxis = false, e = eb)
    }

    private fun fan(ring: Int, sides: Int, centre: V3, n: V3, facesMinusAxis: Boolean, e: Float) {
        // Cap vertices get their own flat normal; positions are re-read from the ring (already transformed).
        val c = rawVertex(xf.apply(centre), xf.applyNormal(n), 0.5f, 0.5f, color, e)
        val first = vertexCount
        for (s in 0 until sides) {
            val o = (ring + s) * Vtx.FLOATS
            rawVertex(V3(v[o], v[o + 1], v[o + 2]), xf.applyNormal(n), 0f, 0f, color, e)
        }
        for (s in 0 until sides) {
            val s1 = (s + 1) % sides
            if (facesMinusAxis) triRaw(c, first + s, first + s1) else triRaw(c, first + s1, first + s)
        }
    }

    /** Triangle with already-transformed vertices: winding decided by the caller. */
    private fun triRaw(a: Int, b: Int, c: Int) {
        ensureI(indexCount + 3)
        if (xf.determinant < 0) { idx[indexCount++] = a; idx[indexCount++] = c; idx[indexCount++] = b } else { idx[indexCount++] = a; idx[indexCount++] = b; idx[indexCount++] = c }
    }

    /** Ellipsoid centred at [c] with semi-axes rx, ry, rz; [rings] latitude bands, [segs] longitudes. */
    fun ellipsoid(c: V3, rx: Float, ry: Float, rz: Float, rings: Int, segs: Int, yMin: Float = -1f, yMax: Float = 1f) {
        val start = vertexCount
        val lat0 = kotlin.math.asin(yMin.coerceIn(-1f, 1f))
        val lat1 = kotlin.math.asin(yMax.coerceIn(-1f, 1f))
        for (r in 0..rings) {
            val lat = lat0 + (lat1 - lat0) * r / rings
            val cy = sin(lat); val cr = cos(lat)
            for (s in 0..segs) {
                val lon = 2 * PI.toFloat() * s / segs
                val ux = cos(lon) * cr; val uz = sin(lon) * cr
                val p = c + v3(ux * rx, cy * ry, uz * rz)
                val n = v3(ux / rx, cy / ry, uz / rz).norm()
                vertex(p, n, s.toFloat() / segs, r.toFloat() / rings)
            }
        }
        val w = segs + 1
        for (r in 0 until rings) for (s in 0 until segs) {
            val i0 = start + r * w + s
            val i1 = i0 + 1
            val i2 = i0 + w + 1
            val i3 = i0 + w
            // Outward winding (i0, i3, i2, i1); skip the degenerate triangle at a pole.
            if (r == 0 && yMin <= -0.999f) tri(i0, i3, i2)
            else if (r == rings - 1 && yMax >= 0.999f) tri(i0, i2, i1)
            else quad(i0, i3, i2, i1)
        }
        if (yMin > -0.999f) {
            // Close the cut-off bottom with a flat cap.
            val cy = c.y + yMin * ry
            val centre = vertex(v3(c.x, cy, c.z), -V3.Y)
            for (s in 0 until segs) tri(centre, start + s, start + s + 1)
        }
    }

    fun sphere(c: V3, r: Float, rings: Int, segs: Int) = ellipsoid(c, r, r, r, rings, segs)

    /**
     * A smooth surface through elliptical rings: each ring is (centre, half-width x, half-depth z) in a
     * plane perpendicular to y (for torsos, saddles, helmets lofted along an axis). Rings are given in
     * order; [closeEnds] fans the first and last ring.
     */
    fun loft(rings: List<LoftRing>, segs: Int, closeEnds: Boolean = true, frame: (Int) -> Affine = { Affine.IDENTITY }) {
        val start = vertexCount
        val pos = ArrayList<V3>()
        for ((ri, r) in rings.withIndex()) {
            for (s in 0 until segs) {
                val a = 2 * PI.toFloat() * s / segs
                pos += frame(ri).apply(r.point(a))
            }
        }
        // Normals by central differences over the grid, so the surface is smooth however rings vary.
        for (ri in rings.indices) {
            for (s in 0 until segs) {
                val p = pos[ri * segs + s]
                val pn = pos[ri * segs + (s + 1) % segs]
                val pp = pos[ri * segs + (s + segs - 1) % segs]
                val up = pos[minOf(ri + 1, rings.size - 1) * segs + s]
                val dn = pos[maxOf(ri - 1, 0) * segs + s]
                var n = ((pn - pp) cross (up - dn)).norm()
                // Keep normals outward: compare with the direction from the ring centre.
                val centre = frame(ri).apply(rings[ri].c)
                if ((n dot (p - centre)) < 0) n = -n
                vertex(p, n, s.toFloat() / segs, ri.toFloat() / (rings.size - 1), rings[ri].color ?: color, rings[ri].extra ?: extra)
            }
        }
        for (ri in 0 until rings.size - 1) for (s in 0 until segs) {
            val s1 = (s + 1) % segs
            val i0 = start + ri * segs + s; val i1 = start + ri * segs + s1
            val i2 = start + (ri + 1) * segs + s1; val i3 = start + (ri + 1) * segs + s
            // Winding chosen so that the outward normal is front-facing for rings ordered along +axis.
            quad(i0, i3, i2, i1)
        }
        if (closeEnds) {
            val p0 = frame(0).apply(rings.first().c)
            val p1 = frame(1).apply(rings[1].c)
            val c0 = vertex(p0, (p0 - p1).norm(), 0.5f, 0f, rings.first().color ?: color, rings.first().extra ?: extra)
            for (s in 0 until segs) tri(c0, start + s, start + (s + 1) % segs)
            val lastR = rings.size - 1
            val q0 = frame(lastR).apply(rings.last().c)
            val q1 = frame(lastR - 1).apply(rings[lastR - 1].c)
            val c1 = vertex(q0, (q0 - q1).norm(), 0.5f, 1f, rings.last().color ?: color, rings.last().extra ?: extra)
            val b = start + lastR * segs
            for (s in 0 until segs) tri(c1, b + (s + 1) % segs, b + s)
        }
    }

    /**
     * A smooth surface over a grid of points ([rows] × [cols], row-major, closed around the columns when
     * [wrap]) whose quads each have one colour from [quadColor]. Vertices are shared only between
     * quads of the same colour, so colour borders stay hard: the kit channels of the rider must reach
     * the shader exactly, an interpolated mix of two channels would match neither.
     */
    fun colorGrid(rows: Int, cols: Int, wrap: Boolean, point: (Int, Int) -> V3, quadColor: (Int, Int) -> FloatArray, extraAt: (Int, Int) -> Float = { _, _ -> extra }, outwardFrom: ((Int) -> V3)? = null) {
        val pts = Array(rows) { r -> Array(cols) { c -> point(r, c) } }
        val ncols = if (wrap) cols else cols - 1
        fun p(r: Int, c: Int) = pts[r.coerceIn(0, rows - 1)][if (wrap) ((c % cols) + cols) % cols else c.coerceIn(0, cols - 1)]
        val normals = Array(rows) { r ->
            Array(cols) { c ->
                val du = p(r, c + 1) - p(r, c - 1)
                val dv = p(r + 1, c) - p(r - 1, c)
                var n = (du cross dv).norm()
                if (outwardFrom != null && (n dot (p(r, c) - outwardFrom(r))) < 0) n = -n
                n
            }
        }
        // One vertex per (grid point, colour); colours compared by identity of their values.
        val cache = HashMap<Long, Int>()
        fun vid(r: Int, c: Int, col: FloatArray): Int {
            val cc = if (wrap) ((c % cols) + cols) % cols else c
            val key = (r.toLong() shl 40) or (cc.toLong() shl 20) or (java.lang.Float.floatToIntBits(col[0] * 3.1f + col[1] * 7.3f + col[2] * 11.7f).toLong() and 0xFFFFF)
            return cache.getOrPut(key) { vertex(pts[r][cc], normals[r][cc], cc.toFloat() / cols, r.toFloat() / (rows - 1), col, extraAt(r, cc)) }
        }
        for (r in 0 until rows - 1) for (c in 0 until ncols) {
            val col = quadColor(r, c)
            val a = vid(r, c, col); val b = vid(r, c + 1, col); val cc = vid(r + 1, c + 1, col); val d = vid(r + 1, c, col)
            // Same orientation as the loft (outward for rings stacked along +axis, angle increasing x→z);
            // with an outward hint every quad is oriented by its own face normal instead.
            var flip = false
            if (outwardFrom != null) {
                val pa = pts[r][c]; val pd = pts[r + 1][c]; val pc = p(r + 1, c + 1)
                val fn = (pd - pa) cross (pc - pa)
                flip = (fn dot (pa - outwardFrom(r))) < 0
            }
            if (flip) quad(a, b, cc, d) else quad(a, d, cc, b)
        }
    }

    /** A flat quad given by four corners (CCW seen from the front), with explicit UVs. */
    fun quadUv(p0: V3, p1: V3, p2: V3, p3: V3, u0: Float, v0: Float, u1: Float, v1: Float, n: V3? = null) {
        val nn = n ?: ((p1 - p0) cross (p3 - p0)).norm()
        val a = vertex(p0, nn, u0, v1)
        val b = vertex(p1, nn, u1, v1)
        val c = vertex(p2, nn, u1, v0)
        val d = vertex(p3, nn, u0, v0)
        quad(a, b, c, d)
    }

    /** Flat polygon (convex or star-shaped around its first vertex) extruded by [depth] along +z. */
    fun prism(polygon: List<Pair<Float, Float>>, z0: Float, depth: Float) {
        // Accept either orientation: profiles are easier to write tracing the outline from the nose,
        // which is clockwise. Reverse around the first vertex so the fan origin stays the same.
        var area = 0f
        for (i in polygon.indices) {
            val (ax, ay) = polygon[i]
            val (bx, by) = polygon[(i + 1) % polygon.size]
            area += ax * by - bx * ay
        }
        val poly = if (area >= 0f) polygon else listOf(polygon[0]) + polygon.drop(1).reversed()
        val n = poly.size
        // Front (-z) and back (+z) faces.
        val f0 = vertexCount
        for ((x, y) in poly) vertex(v3(x, y, z0), -V3.Z)
        for (i in 1 until n - 1) tri(f0, f0 + i + 1, f0 + i)
        val b0 = vertexCount
        for ((x, y) in poly) vertex(v3(x, y, z0 + depth), V3.Z)
        for (i in 1 until n - 1) tri(b0, b0 + i, b0 + i + 1)
        for (i in 0 until n) {
            val (ax, ay) = poly[i]
            val (bx, by) = poly[(i + 1) % n]
            val nrm = v3(by - ay, -(bx - ax), 0f).norm()
            val q0 = vertex(v3(ax, ay, z0), nrm); val q1 = vertex(v3(bx, by, z0), nrm)
            val q2 = vertex(v3(bx, by, z0 + depth), nrm); val q3 = vertex(v3(ax, ay, z0 + depth), nrm)
            quad(q0, q1, q2, q3)
        }
    }

    /** Torus whose axis is x (a wheel seen from the side): used for tyres, rims and inflatable arches. */
    fun torusX(c: V3, major: Float, minor: Float, segs: Int, sides: Int, arcFrom: Float = 0f, arcTo: Float = 2 * PI.toFloat()) {
        val start = vertexCount
        val full = abs(arcTo - arcFrom - 2 * PI.toFloat()) < 1e-4f
        val segCount = if (full) segs else segs + 1
        for (i in 0 until segCount) {
            val a = arcFrom + (arcTo - arcFrom) * i / segs
            val ca = cos(a); val sa = sin(a)
            for (j in 0 until sides) {
                val b = 2 * PI.toFloat() * j / sides
                val cb = cos(b); val sb = sin(b)
                // Ring centre on the circle in the yz plane; tube section around it.
                val n = v3(sb, ca * cb, sa * cb)
                val p = c + v3(minor * sb, (major + minor * cb) * ca, (major + minor * cb) * sa)
                vertex(p, n, i.toFloat() / segs, j.toFloat() / sides)
            }
        }
        val rows = if (full) segs else segs
        for (i in 0 until rows) for (j in 0 until sides) {
            val i1 = if (full) (i + 1) % segs else i + 1
            val j1 = (j + 1) % sides
            quad(start + i * sides + j, start + i1 * sides + j, start + i1 * sides + j1, start + i * sides + j1)
        }
    }
}

/** One cross-section of a [MeshBuilder.loft]: an ellipse around [c] in the xz plane, optionally offset. */
class LoftRing(val c: V3, val rx: Float, val rz: Float, val color: FloatArray? = null, val extra: Float? = null, val shape: ((Float) -> Float)? = null, val zShift: Float = 0f) {
    fun point(a: Float): V3 {
        val k = shape?.invoke(a) ?: 1f
        val sz = sin(a)
        // zShift > 0 flattens one side (a jersey's back is flatter than its chest).
        val zz = if (sz > 0) sz * rz * (1f - zShift) else sz * rz
        return V3(c.x + cos(a) * rx * k, c.y, c.z + zz * k)
    }
}

fun triangleCount(parts: List<Pair<*, MeshBuffer>>): Int = parts.sumOf { it.second.indexCount / 3 }
