package app.rollentrainer.ui.world.geo.scenery

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Procedural trees. A tree is first grown as a skeleton (stems with radii and wind weights) plus a
 * list of foliage cards; the three LODs are then emitted from the same skeleton, so the near model,
 * the simplified mid model and the impostor (rendered offline from the near model) agree in shape
 * and colour and LOD switches do not pop.
 *
 * Conventions: metres, origin at the ground under the trunk, +y up, the "front" is -z. Every tree
 * species has one texture atlas (see [TreeAtlas]) so all LODs of a species share one material.
 */

/** Regions of a tree atlas (1024², v = 0 at the image's top row). */
internal object TreeAtlas {
    val FOLIAGE_A = UvRect(0.003f, 0.003f, 0.497f, 0.497f)
    val FOLIAGE_B = UvRect(0.503f, 0.003f, 0.997f, 0.497f)
    /** Seamless bark strip (both directions), [BARK_W_M] × [BARK_H_M] of real bark. */
    val BARK = UvRect(0.006f, 0.506f, 0.244f, 0.994f)
    /** Second foliage motif (fruit, flowers, a drooping spray), 256 × 512 px. */
    val SPARE = UvRect(0.256f, 0.503f, 0.497f, 0.997f)
    /** Side view of the near model, rendered offline (tools: scratchpad/w2). */
    val IMPOSTOR = UvRect(0.5f, 0.5f, 1f, 1f)
    const val BARK_W_M = 0.5f
    const val BARK_H_M = 1.0f
}

internal class Stem(
    val pts: List<Vec3>,
    val radii: FloatArray,
    /** 0 trunk, 1 limb, 2 branch, 3 twig. */
    val level: Int,
    val sway: FloatArray,
    val colours: List<FloatArray>,
)

internal class Card(
    val c: Vec3,
    /** Face normal (the card plane is perpendicular to it). */
    val face: Vec3,
    /** In-plane up direction (towards the top of the texture). */
    val up: Vec3,
    val w: Float,
    val h: Float,
    val rect: UvRect,
    /** Linear colour multiplier (ambient occlusion and per-cluster variation). */
    val colour: FloatArray,
    /** Darker colour for the lower edge (self-shadowing inside the crown). */
    val bottomColour: FloatArray,
    val sway: Float,
    /** Lighting normal: mostly the crown's outward normal, so the canopy shades like a volume. */
    val light: Vec3,
    val flip: Boolean,
    /** Mid LOD keeps cards with priority 0 first. */
    val keep: Float,
)

/** A crown envelope: radius as a function of height between [y0] and [y1]. */
internal class Crown(
    val y0: Float,
    val y1: Float,
    val rMax: Float,
    /** Relative height (0..1) of the widest point. */
    val peak: Float,
    /** Exponents of the lower and upper part: 2 = ellipse, 1 = cone, > 2 boxy. */
    val lowPow: Float = 2f,
    val topPow: Float = 2f,
    /** Horizontal offset of the crown centre (leaning trees). */
    val cx: Float = 0f,
    val cz: Float = 0f,
) {
    val height: Float get() = y1 - y0
    val peakY: Float get() = y0 + height * peak

    fun radiusAt(y: Float): Float {
        val t = (y - y0) / height
        if (t <= 0f || t >= 1f) return 0f
        return if (t < peak) {
            val s = (peak - t) / peak
            rMax * (1f - s.pow(lowPow)).coerceAtLeast(0f).pow(1f / lowPow)
        } else {
            val s = (t - peak) / (1f - peak)
            rMax * (1f - s.pow(topPow)).coerceAtLeast(0f).pow(1f / topPow)
        }
    }

    /** How far out [p] is: 0 at the axis, 1 on the envelope, > 1 outside. */
    fun frac(p: Vec3): Float {
        val r = radiusAt(p.y)
        val dx = p.x - cx
        val dz = p.z - cz
        val h = sqrt(dx * dx + dz * dz)
        if (r <= 1e-3f) return 2f + h
        return h / r
    }

    /** Outward normal of the envelope near [p] (an ellipsoid approximation is enough for shading). */
    fun normal(p: Vec3): Vec3 {
        val hy = max(height * 0.5f, 0.5f)
        val ry = max(rMax, 0.3f)
        return Vec3((p.x - cx) / (ry * ry), (p.y - peakY) / (hy * hy), (p.z - cz) / (ry * ry)).norm()
    }

    /** Ambient occlusion of foliage at [p]: dark deep inside and at the bottom of the crown. */
    fun ao(p: Vec3, strength: Float): Float {
        val f = frac(p).coerceIn(0f, 1.2f)
        val vt = ((p.y - y0) / height).coerceIn(0f, 1f)
        val radial = smooth(0.15f, 1.05f, f)
        val a = 0.42f + 0.58f * radial
        val v = 0.72f + 0.28f * sqrt(vt)
        return 1f - (1f - a * v) * strength
    }
}

internal fun smooth(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Rotates [v] about the unit [axis] by [a] radians (Rodrigues). */
internal fun rotate(v: Vec3, axis: Vec3, a: Float): Vec3 {
    val c = cos(a)
    val s = sin(a)
    val k = axis
    val cross = k.cross(v)
    val dot = k.dot(v)
    return Vec3(
        v.x * c + cross.x * s + k.x * dot * (1 - c),
        v.y * c + cross.y * s + k.y * dot * (1 - c),
        v.z * c + cross.z * s + k.z * dot * (1 - c),
    )
}

internal val UP = Vec3(0f, 1f, 0f)

/**
 * The skeleton and foliage of one tree plus emitters for its LODs. Species builders (see
 * [TreeSpecies]) fill [stems] and [cards]; [crown] is used for shading and the mid LOD.
 */
internal class TreeModel(val heightM: Float, val crown: Crown, val barkColour: FloatArray, val barkRect: UvRect = TreeAtlas.BARK) {
    val stems = ArrayList<Stem>()
    val cards = ArrayList<Card>()

    /** Wind weight of a trunk point at height y: the trunk barely moves, the top a little. */
    fun trunkSway(y: Float): Float = 0.22f * (y / heightM).coerceIn(0f, 1f).pow(2f)

    // ---------------------------------------------------------------- emitters

    fun emitNear(mb: MeshBuilder, trunkSides: Int = 9, limbSides: Int = 4, branchSides: Int = 3, twigSides: Int = 3) {
        for (s in stems) {
            val sides = when (s.level) { 0 -> trunkSides; 1 -> limbSides; 2 -> branchSides; else -> twigSides }
            // Only limbs get a tip cone; branch tips vanish in the foliage.
            emitStem(mb, s, sides, s.level == 1)
        }
        for (c in cards) emitCard(mb, c, 1f)
    }

    /**
     * Mid LOD: trunk and limbs with few sides and every other path point, no thin branches, and a
     * subset of the foliage cards scaled up so the crown keeps its coverage and silhouette.
     */
    fun emitMid(mb: MeshBuilder, cardCount: Int, maxLevel: Int = 1, trunkSides: Int = 5, limbSides: Int = 3) {
        // The dozen thickest limbs carry the silhouette at this distance; the rest hide in the crown.
        val limbs = stems.filter { it.level == 1 && it.radii[0] >= 0.06f * heightM / 25f }.sortedByDescending { it.radii[0] }.take(12).toHashSet()
        for (s in stems) {
            if (s.level > maxLevel) continue
            if (s.level == 1 && s !in limbs) continue
            emitStem(mb, decimate(s), if (s.level == 0) trunkSides else limbSides, false, coarse = true)
        }
        val chosen = pickSpread(cardCount)
        val k = sqrt(cards.size.toFloat() / max(1, chosen.size)).coerceIn(1f, 2.6f) * 0.92f
        for (c in chosen) emitCard(mb, c, k)
    }

    /** Every other point of a stem (keeping both ends), for the mid LOD. */
    private fun decimate(s: Stem): Stem {
        if (s.pts.size <= 3) return s
        val keep = (s.pts.indices).filter { it % 2 == 0 || it == s.pts.size - 1 }
        return Stem(keep.map { s.pts[it] }, FloatArray(keep.size) { s.radii[keep[it]] }, s.level,
            FloatArray(keep.size) { s.sway[keep[it]] }, keep.map { s.colours[it] })
    }

    /**
     * Far LOD: two crossed vertical quads showing the offline-rendered side view in
     * [TreeAtlas.IMPOSTOR]. [frame] = (halfWidth, height, square side S) as used for the render.
     */
    fun emitImpostor(mb: MeshBuilder, frame: FloatArray, sway: Float = 0.25f) {
        val hw = frame[0]
        val h = frame[1]
        val s = frame[2]
        val r = TreeAtlas.IMPOSTOR
        val uL = 0.5f - hw / s
        val uR = 0.5f + hw / s
        val vT = 1f - h / s
        // The impostor texture is unlit albedo (with the crown's baked AO), but its two quads face the
        // light far more squarely than the near crown's cards, half of which turn away from the sun:
        // measured in the three.js check (scratchpad/w2/viewer), the plain impostor came out ~1.3x
        // (sRGB) brighter than the mid LOD. These vertex colours bring it back to the mid LOD's
        // brightness so the switch at ~190 m does not pop; the base is darker like the lower crown.
        val col = floatArrayOf(0.52f, 0.52f, 0.52f)
        val colBase = floatArrayOf(0.28f, 0.28f, 0.28f)
        for (q in 0..1) {
            // Quad 0 in the x-y plane, quad 1 in the z-y plane.
            val ax = if (q == 0) Vec3(1f, 0f, 0f) else Vec3(0f, 0f, -1f)
            val first = mb.vertexCount
            val bottomY = 0f
            val corners = arrayOf(
                Triple(-hw, bottomY, 0f), Triple(hw, bottomY, 0f), Triple(hw, h, 1f), Triple(-hw, h, 1f),
            )
            val us = floatArrayOf(uL, uR, uR, uL)
            val vs = floatArrayOf(1f, 1f, vT, vT)
            for (k in 0..3) {
                val (a, y, top) = corners[k]
                val px = ax.x * a
                val pz = ax.z * a
                // Normals bend outwards and upwards, like the crown they stand for.
                val side = if (a < 0) -0.55f else 0.55f
                val n = Vec3(ax.x * side, 0.35f + 0.45f * top, ax.z * side).norm()
                val c = if (top > 0f) col else colBase
                mb.vertex(px, y, pz, n.x, n.y, n.z, r.u(us[k]), r.v(vs[k]), c[0], c[1], c[2], sway * top)
            }
            mb.quad(first, first + 1, first + 2, first + 3)
        }
    }

    /** (half width, height, square side) of the impostor render. */
    fun impostorFrame(): FloatArray {
        var hw = 0.3f
        var h = 1f
        for (s in stems) for ((i, p) in s.pts.withIndex()) {
            hw = max(hw, max(abs(p.x), abs(p.z)) + s.radii[i])
            h = max(h, p.y)
        }
        for (c in cards) {
            val e = max(c.w, c.h) * 0.5f
            hw = max(hw, max(abs(c.c.x), abs(c.c.z)) + e * 0.85f)
            h = max(h, c.c.y + e * 0.85f)
        }
        hw *= 1.02f
        h *= 1.02f
        val side = max(2f * hw, h) * 1.02f
        return floatArrayOf(hw, h, side)
    }

    private fun emitStem(mb: MeshBuilder, s: Stem, sides: Int, cap: Boolean, coarse: Boolean = false) {
        val girth = 2f * PI.toFloat() * s.radii[0]
        val rep = max(1, (girth / TreeAtlas.BARK_W_M + 0.5f).toInt())
        // The bark keeps its aspect on the trunk only. An atlas region cannot repeat, so every bark
        // tile along a stem costs a duplicated ring: on thin limbs and branches (seen as a colour
        // from below) the bark is stretched instead, which saves thousands of triangles per tree.
        val aspect = TreeAtlas.BARK_H_M * (girth / rep / TreeAtlas.BARK_W_M).coerceIn(0.3f, 1.5f)
        val vTile = when {
            coarse -> max(aspect, 3f)
            s.level == 0 -> aspect
            s.level == 1 -> max(aspect, 1.5f)
            else -> 4f
        }
        Shapes.tube(mb, s.pts, s.radii, sides, barkRect, vTile, s.colours, s.sway, uRepeat = if (s.level == 0) rep else 1, capEnd = cap)
    }

    private fun emitCard(mb: MeshBuilder, c: Card, scale: Float) {
        val right = c.up.cross(c.face).norm()
        val sway = c.sway
        val light = c.light
        // Enlarged mid-LOD cards move inwards by part of their growth, so the crown keeps the
        // near model's (and the impostor's) outline instead of swelling at the LOD switch.
        val centre = if (scale > 1.01f) {
            val toAxis = Vec3(crown.cx - c.c.x, (crown.peakY - c.c.y) * 0.5f, crown.cz - c.c.z)
            val l = toAxis.len()
            val shift = min((scale - 1f) * max(c.w, c.h) * 0.35f, l * 0.5f)
            if (l > 1e-3f) c.c + toAxis * (shift / l) else c.c
        } else c.c
        Shapes.card(mb, centre, right, c.up, c.w * scale, c.h * scale, c.rect, c.colour,
            swayAt = { p -> (sway + 0.08f * (p.y - c.c.y).coerceAtLeast(0f)).coerceAtMost(1f) },
            normalAt = { p ->
                // Corner normals fan out a little around the crown normal: smoother shading.
                val o = (p - centre) * (0.25f / max(c.w * scale, 0.1f))
                (light + o).norm()
            },
            flipU = c.flip, bottomColour = c.bottomColour)
    }

    /** Farthest-point selection of [n] cards (deterministic), preferring cards with low [Card.keep]. */
    private fun pickSpread(n: Int): List<Card> {
        if (cards.size <= n) return cards
        val chosen = ArrayList<Card>(n)
        val dist = FloatArray(cards.size) { Float.MAX_VALUE }
        var next = cards.indices.minByOrNull { cards[it].keep } ?: 0
        repeat(n) {
            val c = cards[next]
            chosen += c
            var best = -1f
            var bi = 0
            for (i in cards.indices) {
                val q = cards[i]
                val dx = q.c.x - c.c.x
                val dy = q.c.y - c.c.y
                val dz = q.c.z - c.c.z
                val d = dx * dx + dy * dy + dz * dz
                if (d < dist[i]) dist[i] = d
                val score = dist[i] * (1.2f - q.keep * 0.4f)
                if (score > best) { best = score; bi = i }
            }
            next = bi
        }
        return chosen
    }

    // ---------------------------------------------------------------- growth helpers

    /** Adds a stem along [pts] whose radius tapers from [r0] to [r1]. */
    fun addStem(pts: List<Vec3>, r0: Float, r1: Float, level: Int, swayStart: Float, swayPerM: Float, colourAt: (Vec3, Float) -> FloatArray) {
        val n = pts.size
        val len = FloatArray(n)
        for (i in 1 until n) len[i] = len[i - 1] + (pts[i] - pts[i - 1]).len()
        val total = max(len[n - 1], 1e-3f)
        val radii = FloatArray(n) { i -> r0 + (r1 - r0) * (len[i] / total).pow(0.85f) }
        val sway = FloatArray(n) { i -> (swayStart + swayPerM * len[i]).coerceAtMost(0.9f) }
        val cols = List(n) { i -> colourAt(pts[i], len[i] / total) }
        stems += Stem(pts, radii, level, sway, cols)
    }

    /** Point and direction at fraction [t] of the polyline [pts]. */
    fun along(pts: List<Vec3>, t: Float): Pair<Vec3, Vec3> {
        val n = pts.size
        var total = 0f
        for (i in 1 until n) total += (pts[i] - pts[i - 1]).len()
        var target = t.coerceIn(0f, 1f) * total
        for (i in 1 until n) {
            val seg = (pts[i] - pts[i - 1]).len()
            if (target <= seg || i == n - 1) {
                val f = if (seg > 0) (target / seg).coerceIn(0f, 1f) else 0f
                return pts[i - 1].lerp(pts[i], f) to (pts[i] - pts[i - 1]).norm()
            }
            target -= seg
        }
        return pts.last() to (pts.last() - pts[n - 2]).norm()
    }

    /**
     * Grows a limb from [start] in direction [dir0]: steps of [stepM] bending towards [upBias]
     * (gravitropism: positive bends up, negative droops) with random wander [gnarl], until it
     * reaches [envelopeFrac] of the crown or [maxLen].
     */
    fun grow(rnd: Rand, start: Vec3, dir0: Vec3, stepM: Float, maxLen: Float, upBias: Float, gnarl: Float, envelopeFrac: Float, minLen: Float = 0f): List<Vec3> {
        val pts = arrayListOf(start.copy())
        val dir = dir0.copy().norm()
        var p = start.copy()
        var len = 0f
        while (len < maxLen) {
            val step = min(stepM, maxLen - len)
            dir.addScaled(UP, upBias * step * 0.25f)
            dir.x += rnd.sym(gnarl) * step * 0.35f
            dir.y += rnd.sym(gnarl) * step * 0.2f
            dir.z += rnd.sym(gnarl) * step * 0.35f
            dir.norm()
            p = p + dir * step
            len += step
            pts += p.copy()
            if (len >= minLen && crown.frac(p) >= envelopeFrac) break
            if (p.y > crown.y1 * 1.02f) break
        }
        return pts
    }

    /** Foliage colour helpers: AO from the crown times a per-cluster variation. */
    fun cardColours(p: Vec3, size: Float, base: FloatArray, aoStrength: Float, rnd: Rand, variation: Float = 0.1f): Pair<FloatArray, FloatArray> {
        val ao = crown.ao(p, aoStrength)
        val lum = 1f + rnd.sym(variation)
        val warm = rnd.sym(variation * 0.6f)
        val top = floatArrayOf(base[0] * ao * lum * (1f + warm), base[1] * ao * lum, base[2] * ao * lum * (1f - warm))
        val below = Vec3(p.x, p.y - size * 0.5f, p.z)
        val aoB = crown.ao(below, aoStrength) * 0.86f
        val bottom = floatArrayOf(base[0] * aoB * lum * (1f + warm), base[1] * aoB * lum, base[2] * aoB * lum * (1f - warm))
        return top to bottom
    }

    /**
     * A foliage card at [p]: its face turns towards [outward] mixed with randomness, the texture's
     * up points roughly up (or along [upHint]) with a random roll of ±[roll] radians.
     */
    fun addCard(
        rnd: Rand, p: Vec3, size: Float, aspect: Float, rect: UvRect, base: FloatArray, aoStrength: Float,
        sway: Float, outwardMix: Float = 0.55f, upHint: Vec3 = UP, roll: Float = 0.7f, faceUp: Float = 0f,
        lightMix: Float = 0.78f, keep: Float = 1f, variation: Float = 0.1f,
    ) {
        val out = crown.normal(p)
        val rndDir = rnd.onSphere(Vec3())
        val face = (out * outwardMix + rndDir * (1f - outwardMix) + UP * faceUp).norm()
        var up = (upHint - face * upHint.dot(face))
        if (up.len() < 0.1f) up = face.anyPerp()
        up.norm()
        up = rotate(up, face, rnd.sym(roll)).norm()
        // Lit with the crown normal (so the crown shades as one volume), tipped towards the sky:
        // leaves are translucent and the lower crown sees the bright sky between the cards, so a
        // crown's underside is never as dark as a solid surface facing the ground.
        val light = (out * lightMix + face * (1f - lightMix) + UP * 0.4f).norm()
        val (top, bottom) = cardColours(p, size, base, aoStrength, rnd, variation)
        val w = size * rnd.range(0.85f, 1.15f)
        cards += Card(p.copy(), face, up, w, w * aspect, rect, top, bottom, sway, light, rnd.chance(0.5f), keep)
    }

    companion object {
        /** Bark colour with AO towards the ground and inside the crown. */
        fun barkShade(base: FloatArray, y: Float, crownBase: Float): FloatArray {
            val ground = 0.62f + 0.38f * smooth(0f, 1.2f, y)
            val inCrown = if (y > crownBase) 0.8f else 1f
            val s = ground * inCrown
            return floatArrayOf(base[0] * s, base[1] * s, base[2] * s)
        }

        fun dirFrom(azimuth: Float, fromVertical: Float): Vec3 =
            Vec3(sin(fromVertical) * cos(azimuth), cos(fromVertical), sin(fromVertical) * sin(azimuth))

        fun flare(y: Float, r: Float): Float = r * (1f + 0.45f * exp(-y / 0.45f))
    }
}
