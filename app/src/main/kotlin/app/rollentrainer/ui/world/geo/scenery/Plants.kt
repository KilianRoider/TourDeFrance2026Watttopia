package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Low vegetation, crops and rocks: shrubs, hedges, grass tufts and flowers for the verges, vine
 * rows, maize/sunflower/lavender field blocks, hay bales and boulders. They share the scn_plants
 * atlas (4 × 4 cells of 256², see [PlantAtlas]) so a region's ground cover is one texture.
 */
internal object PlantAtlas {
    const val TEXTURE = "scn_plants"
    private fun cell(c: Int, r: Int): UvRect {
        val g = UvRect.grid(c, r, 4, 4)
        val e = 0.004f
        return UvRect(g.u0 + e, g.v0 + e, g.u1 - e, g.v1 - e)
    }
    val GRASS = cell(0, 0)
    val GRASS_DRY = cell(1, 0)
    val FLOWERS = cell(2, 0)
    val POPPIES = cell(3, 0)
    val SHRUB = cell(0, 1)
    val GARRIGUE = cell(1, 1)
    val GORSE = cell(2, 1)
    val RHODO = cell(3, 1)
    val FERN = cell(0, 2)
    val HEDGE = cell(1, 2)
    val VINE = cell(2, 2)
    val LAVENDER = cell(3, 2)
    val MAIZE = cell(0, 3)
    val SUNFLOWER = cell(1, 3)
    val ALPINE_FLOWERS = cell(2, 3)
    /** Wood / bark for stems and posts (seamless). */
    val WOOD = cell(3, 3)
}

/**
 * Ground cover rendered from Poly Haven's scanned plant models (CC0, tools/assets-cc0/tufts.py): 4 x 4
 * square cells, each a tuft standing on the cell's bottom edge with transparent space above it.
 */
internal object MeadowAtlas {
    const val TEXTURE = "scn_meadow"
    private fun cell(k: Int): UvRect {
        val g = UvRect.grid(k % 4, k / 4, 4, 4)
        val e = 0.002f
        return UvRect(g.u0 + e, g.v0 + e, g.u1 - e, g.v1 - e)
    }
    /** Green clumps, small to large (grass_medium_01 / 02, bermuda). */
    val GREEN = listOf(0, 1, 2, 4, 5, 6, 7).map(::cell)
    /** Tall grass with seed heads. */
    val TALL = listOf(3, 4).map(::cell)
    /** Dry and half dry clumps. */
    val DRY = listOf(8, 9, 10, 11).map(::cell)
    /** Dandelions and broad-leaved weeds. */
    val WEEDS = listOf(12, 13, 14, 15).map(::cell)
}

internal object PlantProtos {
    private val mat: SceneMaterial get() = Mats.foliage(PlantAtlas.TEXTURE)
    private val WHITE = floatArrayOf(1f, 1f, 1f)

    // ------------------------------------------------------------------ ground cover

    /**
     * A clump of [n] vertical cards around the centre (crossed at 180°/n), the classic grass /
     * flower tuft: reads as a volume from every side and costs 2n triangles.
     */
    private fun clump(rect: UvRect, w: Float, h: Float, n: Int, tilt: Float, sway: Float, seed: Long, darkBase: Float = 0.75f): MeshBuilder {
        val mb = MeshBuilder(n * 4, n * 6)
        val rnd = Rand(seed)
        val a0 = rnd.angle()
        for (k in 0 until n) {
            val a = a0 + k * PI.toFloat() / n
            val dir = Vec3(cos(a), 0f, sin(a))
            val face = Vec3(-sin(a), 0f, cos(a))
            // Lean the card a little so the clump opens up like real tufts.
            val up = (UP + face * (tilt * (if (k % 2 == 0) 1f else -1f))).norm()
            val c = up * (h * 0.5f)
            val first = mb.vertexCount
            val corners = arrayOf(c + dir * (-w / 2) + up * (-h / 2), c + dir * (w / 2) + up * (-h / 2), c + dir * (w / 2) + up * (h / 2), c + dir * (-w / 2) + up * (h / 2))
            val us = floatArrayOf(0f, 1f, 1f, 0f)
            val vs = floatArrayOf(1f, 1f, 0f, 0f)
            for (q in 0..3) {
                val p = corners[q]
                val top = q >= 2
                // Grass is lit from above: normals point up, so cards seen edge-on do not go black.
                val n0 = Vec3(dir.x * 0.15f * (if (q == 0 || q == 3) -1f else 1f), 1f, dir.z * 0.15f * (if (q == 0 || q == 3) -1f else 1f))
                val col = if (top) WHITE else floatArrayOf(darkBase, darkBase, darkBase)
                mb.vertex(p, n0, rect.u(us[q]), rect.v(vs[q]), col, if (top) sway else 0f)
            }
            mb.quad(first, first + 1, first + 2, first + 3)
        }
        return mb
    }

    fun grass(id: String, rect: UvRect, w: Float, h: Float, maxDist: Float = 45f, cards: Int = 3): Prototype {
        val mb = clump(rect, w, h, cards, 0.18f + 0.04f * (cards - 3), 0.6f, Hash.str(id))
        return Prototype(id, listOf(ProtoLod(listOf(mat to mb.build()), maxDist)), max(w, h))
    }

    /**
     * A tuft from [MeadowAtlas]: [n] crossed square cards of edge [size] (the cell keeps the model's
     * real proportions, so the card is square and the tuft fills it from the bottom).
     */
    fun meadow(id: String, rect: UvRect, size: Float, maxDist: Float, cards: Int = 3): Prototype {
        val mb = clump(rect, size, size, cards, 0.08f, 0.5f, Hash.str(id), darkBase = 0.85f)
        return Prototype(id, listOf(ProtoLod(listOf(Mats.groundCover(MeadowAtlas.TEXTURE) to mb.build()), maxDist)), size)
    }

    fun fern(): Prototype {
        val mb = MeshBuilder(64, 96)
        val rnd = Rand(91)
        // Fronds arch outwards from the centre: cards tilted 40-60° from vertical.
        for (k in 0 until 6) {
            val a = k * 2f * PI.toFloat() / 6 + rnd.sym(0.3f)
            val out = Vec3(cos(a), 0f, sin(a))
            val up = (UP * 0.7f + out * 0.75f).norm()
            val face = out.cross(UP).norm()
            val n2 = up.cross(face).norm()
            val c = up * 0.5f + Vec3(0f, 0.05f, 0f)
            Shapes.card(mb, c, face, up, 0.6f, 1.0f, PlantAtlas.FERN, WHITE, swayAt = { p -> (p.y * 0.8f).coerceIn(0f, 0.7f) }, normalAt = { (n2 * 0.3f + UP).norm() },
                bottomColour = floatArrayOf(0.6f, 0.6f, 0.6f))
        }
        return Prototype("scn_fern", listOf(ProtoLod(listOf(mat to mb.build()), 45f)), 1.0f)
    }

    // ------------------------------------------------------------------ shrubs

    /** A rounded shrub: a few stems from the base and foliage cards on a dome envelope. */
    private fun shrubModel(seed: Long, h: Float, r: Float, rect: UvRect, cards: Int, cardM: Float): TreeModel {
        val rnd = Rand(seed)
        val crown = Crown(0.1f, h, r, 0.42f, 1.5f, 1.9f)
        val m = TreeModel(h, crown, WHITE, PlantAtlas.WOOD)
        val stems = ArrayList<List<Vec3>>()
        for (s in 0 until 5) {
            val az = s * 2.4f + rnd.sym(0.4f)
            val d = TreeModel.dirFrom(az, rnd.range(25f, 50f) * (PI.toFloat() / 180f))
            val pts = m.grow(rnd, Vec3(rnd.sym(0.1f), 0f, rnd.sym(0.1f)), d, h * 0.25f, h * 0.9f, 0.2f, 0.3f, 0.95f, h * 0.4f)
            m.addStem(pts, 0.035f * h, 0.008f, 1, 0f, 0.25f) { q, _ -> TreeModel.barkShade(WHITE, q.y + 0.4f, 9f) }
            stems += pts
        }
        for (c in 0 until cards) {
            val az = rnd.angle()
            val y = crown.y0 + crown.height * (0.15f + 0.8f * sqrt(rnd.f()))
            val rr = crown.radiusAt(y) * rnd.range(0.55f, 1.0f)
            val p = Vec3(cos(az) * rr, y, sin(az) * rr)
            m.addCard(rnd, p, cardM, 1f, rect, WHITE, 0.9f, (0.15f + 0.4f * y / h).coerceAtMost(0.8f), outwardMix = 0.65f, faceUp = 0.2f,
                keep = if (rr > crown.radiusAt(y) * 0.8f) 0f else 0.7f)
        }
        return m
    }

    fun shrub(id: String, rect: UvRect, h: Float, r: Float, cards: Int, cardM: Float, seed: Long, nearM: Float = 50f, farM: Float = 160f): Prototype {
        val m = shrubModel(seed, h, r, rect, cards, cardM)
        val near = MeshBuilder(512, 1024)
        m.emitNear(near, trunkSides = 4, limbSides = 3)
        val mid = MeshBuilder(128, 256)
        m.emitMid(mid, max(6, cards / 4), maxLevel = -1)
        return Prototype(id, listOf(ProtoLod(listOf(mat to near.build()), nearM), ProtoLod(listOf(mat to mid.build()), farM)), max(h, r) + 0.3f)
    }

    /**
     * A hedge segment [len] m long (along x), centred at the origin, so consecutive instances
     * every [len] m along a line join into a continuous hedge. Cards overhang the ends slightly.
     */
    fun hedge(id: String = "scn_hedge", len: Float = 4f, h: Float = 2.3f, depth: Float = 1.3f, rect: UvRect = PlantAtlas.HEDGE): Prototype {
        val rnd = Rand(Hash.str(id))
        val crown = Crown(0f, h, depth * 0.5f, 0.45f, 3f, 2.2f)
        val m = TreeModel(h, crown, WHITE, PlantAtlas.WOOD)
        val cards = (len * 15).toInt()
        for (c in 0 until cards) {
            val x = rnd.range(-len * 0.55f, len * 0.55f)
            val y = rnd.range(0.35f, h - 0.25f)
            val side = if (rnd.chance(0.5f)) 1f else -1f
            val z = side * depth * 0.5f * rnd.range(0.35f, 1.0f)
            val p = Vec3(x, y, z)
            val face = (Vec3(0f, 0.25f, side) + rnd.onSphere(Vec3()) * 0.5f).norm()
            val ao = 0.65f + 0.35f * (y / h)
            val col = floatArrayOf(ao, ao, ao)
            val up = rotate(UP, face, rnd.sym(0.8f))
            val light = Vec3(0f, 0.6f, side * 0.8f).norm()
            m.cards += Card(p, face, up.norm(), 1.15f, 1.15f, rect, col, mul(col, 0.8f), 0.3f * y / h, light, rnd.chance(0.5f), 1f)
        }
        // Top cards, nearly horizontal: a hedge seen from a bike shows its top.
        for (c in 0 until (len * 3).toInt()) {
            val p = Vec3(rnd.range(-len * 0.5f, len * 0.5f), h - rnd.range(0.05f, 0.35f), rnd.sym(depth * 0.35f))
            val face = (UP + rnd.onSphere(Vec3()) * 0.35f).norm()
            m.cards += Card(p, face, Vec3(1f, 0f, 0f), 1.2f, 1.2f, rect, WHITE, floatArrayOf(0.85f, 0.85f, 0.85f), 0.3f, UP, rnd.chance(0.5f), 1f)
        }
        val near = MeshBuilder(512, 1024)
        m.emitNear(near)
        // Far: two long sides and the top as plain quads with the foliage texture.
        val far = MeshBuilder(16, 24)
        val hd = depth * 0.42f
        for (side in listOf(-1f, 1f)) {
            val f = far.vertexCount
            val n = Vec3(0f, 0.4f, side).norm()
            val us = floatArrayOf(0f, len / 1.2f, len / 1.2f, 0f)
            val pts = if (side > 0) arrayOf(Vec3(-len / 2, 0f, hd), Vec3(len / 2, 0f, hd), Vec3(len / 2, h, hd * 0.6f), Vec3(-len / 2, h, hd * 0.6f))
            else arrayOf(Vec3(len / 2, 0f, -hd), Vec3(-len / 2, 0f, -hd), Vec3(-len / 2, h, -hd * 0.6f), Vec3(len / 2, h, -hd * 0.6f))
            for (q in 0..3) {
                val c = if (q < 2) 0.7f else 1f
                // UVs stay inside the cell: repeat by splitting would cost more than this LOD is worth.
                far.vertex(pts[q], n, rect.u(us[q] / (len / 1.2f)), rect.v(if (q < 2) 1f else 0.05f), floatArrayOf(c, c, c), 0f)
            }
            far.quad(f, f + 1, f + 2, f + 3)
        }
        run {
            val f = far.vertexCount
            val y = h * 0.97f
            val pts = arrayOf(Vec3(-len / 2, y, hd * 0.6f), Vec3(len / 2, y, hd * 0.6f), Vec3(len / 2, y, -hd * 0.6f), Vec3(-len / 2, y, -hd * 0.6f))
            val uv = arrayOf(0f to 1f, 1f to 1f, 1f to 0.4f, 0f to 0.4f)
            for (q in 0..3) far.vertex(pts[q], UP, rect.u(uv[q].first), rect.v(uv[q].second), WHITE, 0f)
            far.quad(f, f + 1, f + 2, f + 3)
        }
        return Prototype(id, listOf(ProtoLod(listOf(mat to near.build()), 70f), ProtoLod(listOf(mat to far.build()), 700f)), len * 0.6f + 0.5f)
    }

    // ------------------------------------------------------------------ crops

    /**
     * A block of crop rows [len] m long along x, [rows] rows [spacing] m apart (along z), plants
     * [h] m tall. Each row is a wall of cards [cardW] m wide (the texture shows that much row).
     */
    fun rowBlock(id: String, rect: UvRect, len: Float, rows: Int, spacing: Float, h: Float, cardW: Float, maxNear: Float, far: Float, base: Float = 0f,
                 posts: Boolean = false, vShape: Float = 0f): Prototype {
        fun build(cardWidth: Float, rowStep: Int): MeshBuilder {
            val mb = MeshBuilder(256, 384)
            val segs = max(1, (len / cardWidth + 0.5f).toInt())
            val sw = len / segs
            var r = 0
            while (r < rows) {
                val z = (r - (rows - 1) / 2f) * spacing
                for (side in if (vShape > 0f) listOf(-1f, 1f) else listOf(0f)) {
                    for (k in 0 until segs) {
                        val x0 = -len / 2 + k * sw
                        val x1 = x0 + sw
                        val lean = side * vShape
                        val f = mb.vertexCount
                        val n = Vec3(0f, 0.65f, if (side == 0f) 0.75f else side).norm()
                        mb.vertex(x0, base, z, n.x, n.y, n.z, rect.u(0f), rect.v(1f), 0.7f, 0.7f, 0.7f, 0f)
                        mb.vertex(x1, base, z, n.x, n.y, n.z, rect.u(1f), rect.v(1f), 0.7f, 0.7f, 0.7f, 0f)
                        mb.vertex(x1, base + h, z + lean, n.x, n.y, n.z, rect.u(1f), rect.v(0f), 1f, 1f, 1f, 0.5f)
                        mb.vertex(x0, base + h, z + lean, n.x, n.y, n.z, rect.u(0f), rect.v(0f), 1f, 1f, 1f, 0.5f)
                        mb.quad(f, f + 1, f + 2, f + 3)
                    }
                }
                if (posts) {
                    // Vineyard posts at both ends of the row piece (one is shared with the next block).
                    Shapes.prism(mb, -len / 2, z, 0f, base + h + 0.25f, 0.04f, 0.035f, 4, floatArrayOf(0.55f, 0.48f, 0.4f), rect = PlantAtlas.WOOD)
                }
                r += rowStep
            }
            return mb
        }
        val near = build(cardW, 1)
        val mid = build(len, if (rows >= 4) 2 else 1)
        return Prototype(id, listOf(ProtoLod(listOf(mat to near.build()), maxNear), ProtoLod(listOf(mat to mid.build()), far)), max(len, rows * spacing) * 0.6f + h)
    }

    fun bale(): Prototype {
        val mb = MeshBuilder(128, 256)
        val straw = lin(0xC8B070)
        val r = 0.75f
        val w = 1.2f
        val sides = 14
        // A cylinder lying along x.
        val first = mb.vertexCount
        for (k in 0..sides) {
            val a = k * 2f * PI.toFloat() / sides
            val y = r + sin(a) * r
            val z = cos(a) * r
            val n = Vec3(0f, sin(a), cos(a))
            val shade = 0.85f + 0.15f * sin(a)
            mb.vertex(-w / 2, y, z, n.x, n.y, n.z, 0f, 0f, straw[0] * shade, straw[1] * shade, straw[2] * shade)
            mb.vertex(w / 2, y, z, n.x, n.y, n.z, 0f, 0f, straw[0] * shade, straw[1] * shade, straw[2] * shade)
        }
        for (k in 0 until sides) {
            val a = first + k * 2
            // Counter-clockwise seen from outside (the angle runs from +z towards +y).
            mb.quad(a, a + 1, a + 3, a + 2)
        }
        val endCol = mul(straw, 0.85f)
        for (side in listOf(-1f, 1f)) {
            val c = mb.vertex(side * w / 2, r, 0f, side, 0f, 0f, 0f, 0f, endCol[0], endCol[1], endCol[2])
            val f = mb.vertexCount
            for (k in 0 until sides) {
                val a = k * 2f * PI.toFloat() / sides
                mb.vertex(side * w / 2, r + sin(a) * r, cos(a) * r, side, 0f, 0f, 0f, 0f, endCol[0], endCol[1], endCol[2])
            }
            for (k in 0 until sides) if (side > 0) mb.tri(c, f + (k + 1) % sides, f + k) else mb.tri(c, f + k, f + (k + 1) % sides)
        }
        return Prototype("scn_bale", listOf(ProtoLod(listOf(Mats.plain to mb.build()), 400f)), 1.2f)
    }

    // ------------------------------------------------------------------ rocks

    /** A boulder: a noisy, flattened icosphere with box-projected UVs in metres. */
    fun boulder(id: String, texture: String, seed: Long, size: Float): Prototype {
        fun build(subdiv: Int): MeshBuilder {
            val rnd = Rand(seed)
            val (verts, faces) = icosphere(subdiv)
            // Low-frequency deformation from a few random planes: angular, rock-like.
            val planes = List(7) { rnd.onSphere(Vec3()) to rnd.range(0.62f, 0.95f) }
            val pos = verts.map { v ->
                var r = 1f
                for ((n, d) in planes) {
                    val dot = v.dot(n)
                    if (dot > d) r = minOf(r, d / dot)
                }
                Vec3(v.x * r * size, (v.y * r * 0.62f + 0.18f) * size, v.z * r * size * 0.85f)
            }
            val mb = MeshBuilder(faces.size * 3, faces.size * 3)
            val tile = 3f
            for (f in faces) {
                val a = pos[f[0]]; val b = pos[f[1]]; val c = pos[f[2]]
                val n = (b - a).cross(c - a).norm()
                // Faceted: one normal per face; UVs projected along the dominant axis.
                for (p in listOf(a, b, c)) {
                    val (u, v) = when {
                        kotlin.math.abs(n.y) > 0.6f -> p.x to p.z
                        kotlin.math.abs(n.x) > kotlin.math.abs(n.z) -> p.z to -p.y
                        else -> p.x to -p.y
                    }
                    val ao = (0.55f + 0.45f * (p.y / (size * 0.8f)).coerceIn(0f, 1f))
                    mb.vertex(p, n, u / tile, v / tile, floatArrayOf(ao, ao, ao))
                }
                val k = mb.vertexCount
                mb.tri(k - 3, k - 2, k - 1)
            }
            return mb
        }
        val m = Mats.rock(texture)
        return Prototype(id, listOf(ProtoLod(listOf(m to build(1).build()), 120f), ProtoLod(listOf(m to build(0).build()), 700f)), size * 1.1f)
    }

    /** A small stone for verges and meadows: the boulder shape, low-poly, only drawn close by. */
    fun pebble(id: String, texture: String, seed: Long): Prototype {
        val b = boulder(id, texture, seed, 1.0f)
        return Prototype(id, listOf(ProtoLod(b.lods.last().parts, 28f)), 1.1f)
    }

    private fun icosphere(subdiv: Int): Pair<List<Vec3>, List<IntArray>> {
        val t = ((1.0 + sqrt(5.0)) / 2.0).toFloat()
        val v = arrayListOf(
            Vec3(-1f, t, 0f), Vec3(1f, t, 0f), Vec3(-1f, -t, 0f), Vec3(1f, -t, 0f),
            Vec3(0f, -1f, t), Vec3(0f, 1f, t), Vec3(0f, -1f, -t), Vec3(0f, 1f, -t),
            Vec3(t, 0f, -1f), Vec3(t, 0f, 1f), Vec3(-t, 0f, -1f), Vec3(-t, 0f, 1f),
        ).map { it.norm() }.toMutableList()
        var f = listOf(
            intArrayOf(0, 11, 5), intArrayOf(0, 5, 1), intArrayOf(0, 1, 7), intArrayOf(0, 7, 10), intArrayOf(0, 10, 11),
            intArrayOf(1, 5, 9), intArrayOf(5, 11, 4), intArrayOf(11, 10, 2), intArrayOf(10, 7, 6), intArrayOf(7, 1, 8),
            intArrayOf(3, 9, 4), intArrayOf(3, 4, 2), intArrayOf(3, 2, 6), intArrayOf(3, 6, 8), intArrayOf(3, 8, 9),
            intArrayOf(4, 9, 5), intArrayOf(2, 4, 11), intArrayOf(6, 2, 10), intArrayOf(8, 6, 7), intArrayOf(9, 8, 1),
        )
        repeat(subdiv) {
            val cache = HashMap<Long, Int>()
            fun mid(a: Int, b: Int): Int {
                val key = if (a < b) a.toLong() * 100000 + b else b.toLong() * 100000 + a
                return cache.getOrPut(key) { v += v[a].lerp(v[b], 0.5f).norm(); v.size - 1 }
            }
            f = f.flatMap { tri ->
                val a = mid(tri[0], tri[1]); val b = mid(tri[1], tri[2]); val c = mid(tri[2], tri[0])
                listOf(intArrayOf(tri[0], a, c), intArrayOf(tri[1], b, a), intArrayOf(tri[2], c, b), intArrayOf(a, b, c))
            }
        }
        return v to f
    }
}
