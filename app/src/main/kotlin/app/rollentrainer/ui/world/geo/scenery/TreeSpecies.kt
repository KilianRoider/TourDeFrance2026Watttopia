package app.rollentrainer.ui.world.geo.scenery

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The tree species of the French (and Catalan) landscape the tour rides through, each grown by a
 * builder that knows the species' architecture: the silhouette is what makes a beech read as a
 * beech at 200 m (ovoid dome, smooth grey bole), a Lombardy poplar as a poplar (a green column),
 * a spruce as a spruce (a dark cone of drooping sprays), a stone pine as an umbrella.
 * Heights are the reference size of the model; instances scale them (see [Species.heightM]).
 */

private const val DEG = (PI / 180.0).toFloat()
private const val GOLDEN = 2.3999632f

internal enum class Species(
    val id: String,
    /** Texture atlas in assets/tex. */
    val atlas: String,
    /** Height of the model (m); instances scale it by 0.6–1.25. */
    val heightM: Float,
    /** Mid LOD foliage cards. */
    val midCards: Int,
    /*
     * LOD switch distances (m, at instance scale 1). Near models cost ~1–5k triangles, so they are
     * kept to the first ~40 m (≈50 trees in a forest); mid models (~250–1100) to ~190 m; beyond that
     * impostors (4 triangles) carry the forest out to the horizon tiles.
     */
    val nearM: Float,
    val midM: Float,
    val farM: Float,
) {
    BEECH("scn_tree_beech", "scn_tree_beech", 28f, 90, 38f, 190f, 1500f),
    OAK("scn_tree_oak", "scn_tree_oak", 24f, 90, 38f, 190f, 1500f),
    CHESTNUT("scn_tree_chestnut", "scn_tree_chestnut", 22f, 80, 38f, 190f, 1500f),
    HOLM_OAK("scn_tree_holmoak", "scn_tree_holmoak", 12f, 60, 32f, 160f, 1300f),
    PLANE("scn_tree_plane", "scn_tree_plane", 24f, 80, 40f, 200f, 1500f),
    POPLAR("scn_tree_poplar", "scn_tree_poplar", 27f, 80, 38f, 200f, 1600f),
    BIRCH("scn_tree_birch", "scn_tree_birch", 18f, 64, 34f, 170f, 1300f),
    SPRUCE("scn_tree_spruce", "scn_tree_spruce", 30f, 100, 38f, 190f, 1600f),
    LARCH("scn_tree_larch", "scn_tree_larch", 28f, 90, 38f, 190f, 1500f),
    PINE("scn_tree_pine", "scn_tree_pine", 22f, 60, 36f, 180f, 1500f),
    ALEPPO_PINE("scn_tree_aleppo", "scn_tree_aleppo", 15f, 56, 32f, 170f, 1400f),
    MARITIME_PINE("scn_tree_mpine", "scn_tree_mpine", 24f, 60, 36f, 190f, 1600f),
    STONE_PINE("scn_tree_spine", "scn_tree_spine", 15f, 44, 36f, 190f, 1500f),
    CYPRESS("scn_tree_cypress", "scn_tree_cypress", 17f, 34, 32f, 170f, 1400f),
    OLIVE("scn_tree_olive", "scn_tree_olive", 6f, 44, 26f, 130f, 900f),
    APPLE("scn_tree_apple", "scn_tree_apple", 5.5f, 40, 26f, 130f, 900f),
    PALM("scn_tree_palm", "scn_tree_palm", 11f, 30, 34f, 170f, 1200f),
    MUGO("scn_tree_mugo", "scn_tree_pine", 2.2f, 18, 22f, 110f, 600f),
    ;

    val isConifer: Boolean get() = this in setOf(SPRUCE, LARCH, PINE, ALEPPO_PINE, MARITIME_PINE, STONE_PINE, CYPRESS, MUGO)
}

internal object TreeBuilders {
    private val WHITE = floatArrayOf(1f, 1f, 1f)

    fun build(sp: Species, seed: Long = 7L): TreeModel = when (sp) {
        Species.BEECH -> broadleaf(seed, Broadleaf(
            h = 28f, trunkR = 0.36f, crownBase = 0.32f, crownR = 7.3f, peak = 0.46f, lowPow = 1.9f, topPow = 2.3f,
            trunkTop = 0.84f, limbs = 14, limbFrom = 0.34f, limbTo = 0.82f, angleLow = 58f, angleHigh = 28f, upBias = 0.22f,
            gnarl = 0.12f, subs = 4, subAngle = 42f, cards = 420, cardM = 2.35f, faceUp = 0.45f, outwardMix = 0.55f,
            aoStrength = 0.95f, barkUpper = floatArrayOf(1f, 1f, 1f),
        ))
        Species.OAK -> broadleaf(seed, Broadleaf(
            h = 24f, trunkR = 0.5f, crownBase = 0.28f, crownR = 8.2f, peak = 0.42f, lowPow = 2.2f, topPow = 1.8f,
            trunkTop = 0.56f, limbs = 9, limbFrom = 0.27f, limbTo = 0.58f, angleLow = 66f, angleHigh = 36f, upBias = 0.1f,
            gnarl = 0.5f, subs = 5, subAngle = 50f, cards = 390, cardM = 2.2f, faceUp = 0.25f, outwardMix = 0.5f,
            clump = 0.6f, aoStrength = 1f,
        ))
        Species.CHESTNUT -> broadleaf(seed, Broadleaf(
            h = 22f, trunkR = 0.52f, crownBase = 0.27f, crownR = 7.6f, peak = 0.44f, lowPow = 2.1f, topPow = 2.1f,
            trunkTop = 0.62f, limbs = 10, limbFrom = 0.26f, limbTo = 0.62f, angleLow = 60f, angleHigh = 32f, upBias = 0.16f,
            gnarl = 0.32f, subs = 4, subAngle = 45f, cards = 370, cardM = 2.25f, faceUp = 0.2f, outwardMix = 0.55f,
            clump = 0.35f, aoStrength = 1f,
        ))
        Species.HOLM_OAK -> broadleaf(seed, Broadleaf(
            h = 12f, trunkR = 0.32f, crownBase = 0.22f, crownR = 5.2f, peak = 0.45f, lowPow = 2.0f, topPow = 2.0f,
            trunkTop = 0.45f, limbs = 8, limbFrom = 0.2f, limbTo = 0.5f, angleLow = 60f, angleHigh = 35f, upBias = 0.15f,
            gnarl = 0.45f, subs = 3, subAngle = 45f, cards = 260, cardM = 1.6f, faceUp = 0.15f, outwardMix = 0.65f,
            clump = 0.3f, aoStrength = 1f, stems = 2,
        ))
        Species.PLANE -> broadleaf(seed, Broadleaf(
            h = 24f, trunkR = 0.46f, crownBase = 0.24f, crownR = 7.4f, peak = 0.55f, lowPow = 2.6f, topPow = 2.0f,
            trunkTop = 0.42f, limbs = 7, limbFrom = 0.2f, limbTo = 0.42f, angleLow = 42f, angleHigh = 26f, upBias = 0.12f,
            gnarl = 0.22f, subs = 6, subAngle = 42f, cards = 350, cardM = 2.5f, faceUp = 0.2f, outwardMix = 0.6f,
            aoStrength = 1f,
        ))
        Species.APPLE -> broadleaf(seed, Broadleaf(
            h = 5.5f, trunkR = 0.14f, crownBase = 0.28f, crownR = 2.7f, peak = 0.5f, lowPow = 2.0f, topPow = 1.8f,
            trunkTop = 0.32f, limbs = 5, limbFrom = 0.26f, limbTo = 0.36f, angleLow = 55f, angleHigh = 45f, upBias = 0.1f,
            gnarl = 0.4f, subs = 3, subAngle = 45f, cards = 95, cardM = 1.05f, faceUp = 0.2f, outwardMix = 0.6f,
            aoStrength = 0.9f, spareShare = 0.25f,
        ))
        Species.BIRCH -> broadleaf(seed, Broadleaf(
            h = 18f, trunkR = 0.19f, crownBase = 0.3f, crownR = 3.6f, peak = 0.42f, lowPow = 1.8f, topPow = 1.6f,
            trunkTop = 0.92f, limbs = 13, limbFrom = 0.3f, limbTo = 0.88f, angleLow = 62f, angleHigh = 36f, upBias = 0.05f,
            gnarl = 0.2f, subs = 3, subAngle = 40f, cards = 240, cardM = 1.6f, faceUp = 0f, outwardMix = 0.45f,
            aoStrength = 0.8f, hang = 0.5f, stems = 1,
        ))
        Species.POPLAR -> poplar(seed)
        Species.SPRUCE -> whorl(seed, Whorl(
            h = 30f, trunkR = 0.34f, crownBase = 0.12f, crownR = 3.7f, spacing = 0.78f, perWhorl = 5,
            droopLow = -24f, riseTop = 32f, tipCurl = 0.45f, cardLen = 1.45f, cardW = 1.05f, density = 1.15f, aoStrength = 1f,
        ))
        Species.LARCH -> whorl(seed, Whorl(
            h = 28f, trunkR = 0.33f, crownBase = 0.22f, crownR = 4.1f, spacing = 0.9f, perWhorl = 5,
            droopLow = -12f, riseTop = 26f, tipCurl = 0.7f, cardLen = 1.5f, cardW = 1.3f, density = 1.45f, aoStrength = 0.8f,
            gnarl = 0.25f,
        ))
        Species.PINE -> pine(seed, Pine(h = 22f, trunkR = 0.3f, crownBase = 0.6f, crownR = 4.1f, limbs = 9, cardM = 1.9f,
            barkLow = floatArrayOf(0.62f, 0.58f, 0.56f), barkHigh = floatArrayOf(1.05f, 0.95f, 0.85f), lean = 0.04f, gnarl = 0.55f))
        Species.ALEPPO_PINE -> pine(seed, Pine(h = 15f, trunkR = 0.28f, crownBase = 0.42f, crownR = 4.8f, limbs = 9, cardM = 1.7f,
            barkLow = floatArrayOf(0.55f, 0.55f, 0.55f), barkHigh = floatArrayOf(0.7f, 0.68f, 0.66f), lean = 0.12f, gnarl = 0.75f,
            foliage = floatArrayOf(1.12f, 1.12f, 0.9f), open = 0.35f))
        Species.MARITIME_PINE -> pine(seed, Pine(h = 24f, trunkR = 0.32f, crownBase = 0.66f, crownR = 4.6f, limbs = 8, cardM = 2.1f,
            barkLow = floatArrayOf(0.9f, 0.85f, 0.85f), barkHigh = floatArrayOf(1f, 0.95f, 0.92f), lean = 0.05f, gnarl = 0.4f, open = 0.25f))
        Species.STONE_PINE -> umbrella(seed)
        Species.CYPRESS -> cypress(seed)
        Species.OLIVE -> olive(seed)
        Species.PALM -> palm(seed)
        Species.MUGO -> mugo(seed)
    }

    // ------------------------------------------------------------------ broadleaf

    class Broadleaf(
        val h: Float, val trunkR: Float, val crownBase: Float, val crownR: Float, val peak: Float,
        val lowPow: Float, val topPow: Float, val trunkTop: Float, val limbs: Int, val limbFrom: Float, val limbTo: Float,
        val angleLow: Float, val angleHigh: Float, val upBias: Float, val gnarl: Float, val subs: Int, val subAngle: Float,
        val cards: Int, val cardM: Float, val faceUp: Float, val outwardMix: Float, val clump: Float = 0f,
        val aoStrength: Float = 1f, val hang: Float = 0f, val stems: Int = 1, val spareShare: Float = 0f,
        val barkUpper: FloatArray = floatArrayOf(1f, 1f, 1f),
    )

    private fun broadleaf(seed: Long, p: Broadleaf): TreeModel {
        val rnd = Rand(seed)
        val crown = Crown(p.h * p.crownBase, p.h, p.crownR, p.peak, p.lowPow, p.topPow)
        val m = TreeModel(p.h, crown, WHITE)
        val scale = p.h / 25f
        val trunks = ArrayList<List<Vec3>>()
        for (st in 0 until p.stems) {
            val az = rnd.angle()
            val lean = if (p.stems > 1) 0.12f + rnd.f() * 0.08f else rnd.f() * 0.03f
            val top = p.h * p.trunkTop * (if (st == 0) 1f else 0.85f)
            val n = max(3, (top / (1.5f * scale)).toInt() + 1)
            val ph1 = rnd.angle()
            val ph2 = rnd.angle()
            val off = if (p.stems > 1) p.trunkR * 0.6f else 0f
            val pts = List(n + 1) { i ->
                val y = top * i / n
                val wob = p.gnarl * 0.35f * scale
                Vec3(
                    cos(az) * (off + lean * y) + wob * sin(y / p.h * 7f + ph1),
                    y,
                    sin(az) * (off + lean * y) + wob * sin(y / p.h * 6f + ph2),
                )
            }
            trunks += pts
            val r0 = TreeModel.flare(0f, p.trunkR) * (if (p.stems > 1) 0.75f else 1f)
            m.addStem(pts, r0, p.trunkR * 0.32f, 0, 0f, 0f) { q, t ->
                mix(TreeModel.barkShade(WHITE, q.y, crown.y0), p.barkUpper, t * 0.6f)
            }
            // The flare: a wide short ring at the foot is part of the radius taper above.
        }
        val trunk = trunks[0]
        val trunkStem = m.stems[0]
        fun trunkAt(y: Float): Pair<Vec3, Float> {
            val pts = trunks[rnd.int(trunks.size)]
            val topY = pts.last().y
            val t = (y / topY).coerceIn(0f, 1f)
            val (pt, _) = m.along(pts, t)
            return pt to (trunkStem.radii[0] + (trunkStem.radii.last() - trunkStem.radii[0]) * t)
        }

        val limbTips = ArrayList<List<Vec3>>()
        for (i in 0 until p.limbs) {
            val f = ((i + rnd.f() * 0.8f) / p.limbs).coerceIn(0f, 1f)
            val y = p.h * (p.limbFrom + (p.limbTo - p.limbFrom) * f)
            val (start, tr) = trunkAt(y)
            val az = i * GOLDEN + rnd.sym(0.4f)
            val ang = (p.angleLow + (p.angleHigh - p.angleLow) * f + rnd.sym(8f)) * DEG
            val dir = TreeModel.dirFrom(az, ang)
            val pts = m.grow(rnd, start, dir, 1.2f * scale, p.crownR * 1.7f, p.upBias, p.gnarl, 0.86f, p.crownR * 0.35f)
            val r0 = tr * (0.62f - 0.25f * f)
            m.addStem(pts, r0, 0.03f * scale, 1, m.trunkSway(y), 0.05f) { q, t -> TreeModel.barkShade(WHITE, q.y, crown.y0).let { mul(it, 0.9f + 0.1f * t) } }
            limbTips += pts
            for (j in 0 until p.subs) {
                val t = 0.28f + 0.68f * (j + rnd.f() * 0.6f) / p.subs
                val (sp, sd) = m.along(pts, t)
                val axis = sd.cross(rnd.onSphere(Vec3())).norm()
                val d = rotate(sd, axis, (p.subAngle + rnd.sym(12f)) * DEG)
                d.y += 0.12f - p.hang * 0.5f
                val len = p.crownR * rnd.range(0.45f, 0.85f)
                val sub = m.grow(rnd, sp, d.norm(), 0.8f * scale, len, p.upBias * 0.7f - p.hang * 0.6f, p.gnarl * 1.2f, 0.98f, 0.6f * scale)
                if (sub.size >= 2) {
                    m.addStem(sub, r0 * 0.38f * (1f - t * 0.5f), 0.012f * scale, 2, m.trunkSway(sp.y) + 0.05f * t * p.crownR, 0.07f) { q, _ ->
                        TreeModel.barkShade(WHITE, q.y, crown.y0)
                    }
                    limbTips += sub
                }
            }
        }

        // Foliage, distributed over the outer parts of limbs and branches in proportion to length.
        val outer = limbTips.map { pts ->
            var l = 0f
            for (i in 1 until pts.size) l += (pts[i] - pts[i - 1]).len()
            l
        }
        val total = outer.sum().coerceAtLeast(1e-3f)
        val filler = (p.cards * 0.12f).toInt()
        val main = p.cards - filler
        for ((k, pts) in limbTips.withIndex()) {
            val nCards = max(1, (main * outer[k] / total + rnd.f()).toInt())
            for (c in 0 until nCards) {
                val t = 0.38f + 0.62f * rnd.f().pow(0.55f)
                val (bp, _) = m.along(pts, t)
                val spread = p.cardM * (0.45f - 0.25f * p.clump)
                val pos = bp + rnd.onSphere(Vec3()) * (spread * rnd.f())
                pos.y -= p.hang * p.cardM * 0.4f
                pullInside(crown, pos, 1.06f)
                val tip = t > 0.85f
                val region = pickRegion(rnd, p.spareShare)
                m.addCard(rnd, pos, p.cardM * (if (tip) 0.95f else 1.05f), if (region === TreeAtlas.SPARE) 1.6f else 1f, region, WHITE,
                    p.aoStrength, (m.trunkSway(pos.y) + 0.35f + 0.25f * t).coerceAtMost(1f), p.outwardMix, faceUp = p.faceUp,
                    keep = if (tip) 0f else 0.6f)
            }
        }
        // A few cards deep inside so the crown is not hollow when the outer cards part.
        for (i in 0 until filler) {
            val az = rnd.angle()
            val y = crown.y0 + crown.height * rnd.range(0.2f, 0.8f)
            val r = crown.radiusAt(y) * rnd.range(0.3f, 0.65f)
            val pos = Vec3(cos(az) * r, y, sin(az) * r)
            m.addCard(rnd, pos, p.cardM * 1.15f, 1f, TreeAtlas.FOLIAGE_A, WHITE, p.aoStrength, 0.35f, 0.3f, keep = 1f)
        }
        return m
    }

    private fun pickRegion(rnd: Rand, spare: Float): UvRect = when {
        spare > 0f && rnd.chance(spare) -> TreeAtlas.SPARE
        rnd.chance(0.5f) -> TreeAtlas.FOLIAGE_A
        else -> TreeAtlas.FOLIAGE_B
    }

    private fun pullInside(crown: Crown, p: Vec3, maxFrac: Float) {
        val f = crown.frac(p)
        if (f > maxFrac) {
            val k = maxFrac / f
            p.x = crown.cx + (p.x - crown.cx) * k
            p.z = crown.cz + (p.z - crown.cz) * k
        }
        p.y = p.y.coerceIn(crown.y0 + 0.3f, crown.y1 - 0.2f)
    }

    // ------------------------------------------------------------------ poplar

    private fun poplar(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 27f
        val crown = Crown(1.0f, h, 2.6f, 0.36f, 1.5f, 1.25f)
        val m = TreeModel(h, crown, WHITE)
        val trunk = List(10) { i -> Vec3(rnd.sym(0.05f), h * 0.97f * i / 9f, rnd.sym(0.05f)) }
        m.addStem(trunk, TreeModel.flare(0f, 0.36f), 0.05f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 2f) }
        val branches = ArrayList<List<Vec3>>()
        var y = 1.4f
        var i = 0
        while (y < h - 1f) {
            val az = i * GOLDEN + rnd.sym(0.3f)
            val ang = rnd.range(13f, 24f) * DEG
            val dir = TreeModel.dirFrom(az, ang)
            val start = Vec3(trunk[0].x, y, trunk[0].z)
            val maxLen = (h - y) * 0.5f + 1.5f
            val pts = m.grow(rnd, start, dir, 1.0f, maxLen.coerceAtMost(8f), 0.3f, 0.12f, 0.95f, 1.5f)
            m.addStem(pts, 0.07f, 0.012f, 1, m.trunkSway(y), 0.06f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 2f) }
            branches += pts
            y += rnd.range(0.55f, 0.85f)
            i++
        }
        val cards = 380
        for (c in 0 until cards) {
            val pts = branches[rnd.int(branches.size)]
            val t = 0.3f + 0.7f * rnd.f().pow(0.6f)
            val (bp, _) = m.along(pts, t)
            val pos = bp + rnd.onSphere(Vec3()) * 0.45f
            pullInside(crown, pos, 1.05f)
            m.addCard(rnd, pos, 1.6f, 1.15f, if (rnd.chance(0.5f)) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B, WHITE, 0.9f,
                (m.trunkSway(pos.y) + 0.45f).coerceAtMost(1f), 0.72f, roll = 0.35f, keep = if (t > 0.8f) 0f else 0.7f)
        }
        return m
    }

    // ------------------------------------------------------------------ whorled conifers

    class Whorl(
        val h: Float, val trunkR: Float, val crownBase: Float, val crownR: Float, val spacing: Float, val perWhorl: Int,
        val droopLow: Float, val riseTop: Float, val tipCurl: Float, val cardLen: Float, val cardW: Float, val density: Float,
        val aoStrength: Float, val gnarl: Float = 0.08f,
    )

    private fun whorl(seed: Long, p: Whorl): TreeModel {
        val rnd = Rand(seed)
        val y0 = p.h * p.crownBase
        val crown = Crown(y0 - 0.5f, p.h + 0.3f, p.crownR, 0.06f, 1.4f, 1.05f)
        val m = TreeModel(p.h, crown, WHITE)
        val n = 14
        val trunk = List(n + 1) { i ->
            val y = p.h * i / n
            Vec3(rnd.sym(p.gnarl * 0.3f), y, rnd.sym(p.gnarl * 0.3f))
        }
        fun rAt(y: Float) = TreeModel.flare(y, p.trunkR) * (1f - y / p.h).coerceAtLeast(0f).pow(0.85f) + 0.012f
        m.addStem(trunk, rAt(0f), 0.02f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, y0) }
        var y = y0
        var wi = 0
        while (y < p.h - 0.6f) {
            val t = ((y - y0) / (p.h - y0)).coerceIn(0f, 1f)
            val bottomRound = if (t < 0.12f) 0.72f + 2.3f * t else 1f
            val per = max(3, p.perWhorl - (if (t > 0.75f) 1 else 0) + (if (rnd.chance(0.3f)) 1 else 0) - (if (rnd.chance(0.2f)) 1 else 0))
            val rot = wi * 0.63f + rnd.f()
            val (tp, _) = m.along(trunk, y / p.h)
            for (b in 0 until per) {
                val len = p.crownR * (1f - t).pow(0.92f) * bottomRound * rnd.range(0.82f, 1.1f) + 0.25f
                val az = rot + b * 2f * PI.toFloat() / per + rnd.sym(0.28f)
                val elev = (p.droopLow + (p.riseTop - p.droopLow) * t.pow(1.15f) + rnd.sym(7f)) * DEG
                val dir = Vec3(cos(elev) * cos(az), sin(elev), cos(elev) * sin(az))
                val start = tp + Vec3(cos(az), 0f, sin(az)) * rAt(y)
                val mid = start + dir * (len * 0.55f) + Vec3(0f, -len * 0.08f * (1f - t), 0f)
                val tip = start + dir * len + Vec3(0f, len * (p.tipCurl * 0.18f) - len * 0.1f * (1f - t), 0f)
                val pts = listOf(start, mid, tip)
                val sw0 = m.trunkSway(y)
                m.addStem(pts, (rAt(y) * 0.32f).coerceIn(0.015f, 0.09f), 0.008f, 2, sw0, 0.12f) { q, _ -> TreeModel.barkShade(WHITE, q.y, y0) }
                val nc = max(1, (len / p.cardLen * p.density + rnd.f() * 0.5f).toInt())
                for (c in 0 until nc) {
                    val f = ((c + 0.62f) / nc).coerceAtMost(0.92f)
                    val (cp, cd) = m.along(pts, f)
                    val side = cd.cross(UP).let { if (it.len() < 1e-3f) Vec3(1f, 0f, 0f) else it.norm() }
                    val flatFace = side.cross(cd).norm().let { if (it.y < 0) it * -1f else it }
                    for (v in 0..1) {
                        val tilt = (if (v == 0) 1f else -1f) * rnd.range(22f, 42f) * DEG
                        val face = rotate(flatFace, cd, tilt).norm()
                        val sw = (sw0 + 0.12f * len * f + 0.2f).coerceAtMost(1f)
                        val size = p.cardLen * rnd.range(0.9f, 1.12f) * (if (t > 0.85f) 0.75f else 1f)
                        val tipDir = (cd + Vec3(0f, -0.15f, 0f)).norm()
                        m.addCardExplicit(rnd, cp + cd * (size * 0.12f), face, tipDir, p.cardW * size / p.cardLen, size, TreeAtlas.FOLIAGE_A,
                            WHITE, p.aoStrength, sw, lightMix = 0.7f, keep = if (f > 0.6f) 0.2f else 0.8f)
                    }
                }
            }
            y += p.spacing * rnd.range(0.85f, 1.15f) * (1f - 0.3f * t)
            wi++
        }
        // Leader: a few upright sprays at the top.
        for (k in 0 until 4) {
            val az = k * PI.toFloat() / 2f + rnd.sym(0.3f)
            val c = Vec3(cos(az) * 0.15f, p.h - 0.7f - k * 0.2f, sin(az) * 0.15f)
            val face = Vec3(cos(az + PI.toFloat() / 2f), 0f, sin(az + PI.toFloat() / 2f))
            m.addCardExplicit(rnd, c, face, UP, p.cardW * 0.7f, p.cardLen * 0.8f, TreeAtlas.FOLIAGE_A, WHITE, p.aoStrength, 0.3f, keep = 0f)
        }
        return m
    }

    // ------------------------------------------------------------------ pines

    class Pine(
        val h: Float, val trunkR: Float, val crownBase: Float, val crownR: Float, val limbs: Int, val cardM: Float,
        val barkLow: FloatArray, val barkHigh: FloatArray, val lean: Float, val gnarl: Float,
        val foliage: FloatArray = floatArrayOf(1f, 1f, 1f), val open: Float = 0f,
    )

    private fun pine(seed: Long, p: Pine): TreeModel {
        val rnd = Rand(seed)
        val y0 = p.h * p.crownBase
        val crown = Crown(y0 - 0.8f, p.h, p.crownR, 0.45f, 1.6f, 2.6f)
        val m = TreeModel(p.h, crown, WHITE)
        val az = rnd.angle()
        val ph = rnd.angle()
        val n = 12
        val top = p.h * 0.9f
        val trunk = List(n + 1) { i ->
            val y = top * i / n
            val s = p.gnarl * 0.6f * sin(y / p.h * 4.2f + ph)
            Vec3(cos(az) * (p.lean * y + s), y, sin(az) * (p.lean * y + s) + p.gnarl * 0.3f * sin(y / p.h * 3.1f + ph * 2f))
        }
        m.addStem(trunk, TreeModel.flare(0f, p.trunkR), p.trunkR * 0.25f, 0, 0f, 0f) { q, t ->
            val c = mix(p.barkLow, p.barkHigh, smooth(0.25f, 0.6f, t))
            TreeModel.barkShade(c, q.y, y0)
        }
        val tips = ArrayList<Pair<List<Vec3>, Float>>()
        for (i in 0 until p.limbs) {
            val f = (i + rnd.f() * 0.7f) / p.limbs
            val y = y0 - 0.6f + (p.h * 0.93f - y0) * f
            val (start, _) = m.along(trunk, (y / top).coerceAtMost(1f))
            val azl = i * GOLDEN + rnd.sym(0.5f)
            val ang = (72f - 40f * f + rnd.sym(10f)) * DEG
            val dir = TreeModel.dirFrom(azl, ang)
            val pts = m.grow(rnd, start, dir, 1.1f, p.crownR * 1.5f, 0.32f, p.gnarl, 0.9f, p.crownR * 0.4f)
            val r0 = p.trunkR * (0.42f - 0.2f * f)
            m.addStem(pts, r0, 0.025f, 1, m.trunkSway(y), 0.06f) { q, _ -> TreeModel.barkShade(p.barkHigh, q.y, 0f) }
            tips += pts to 1f
            val subs = 2 + rnd.int(2)
            for (j in 0 until subs) {
                val t = 0.35f + 0.55f * (j + rnd.f() * 0.5f) / subs
                val (sp, sd) = m.along(pts, t)
                val d = rotate(sd, sd.cross(rnd.onSphere(Vec3())).norm(), rnd.range(30f, 55f) * DEG)
                d.y += 0.25f
                val sub = m.grow(rnd, sp, d.norm(), 0.7f, p.crownR * rnd.range(0.35f, 0.6f), 0.4f, p.gnarl, 0.98f, 0.5f)
                if (sub.size >= 2) {
                    m.addStem(sub, r0 * 0.4f, 0.015f, 2, m.trunkSway(sp.y) + 0.1f, 0.08f) { q, _ -> TreeModel.barkShade(p.barkHigh, q.y, 0f) }
                    tips += sub to 0.7f
                }
            }
        }
        // Needle tufts: clumps at the tips and part-way along, the clumpy look of a pine crown.
        for ((pts, w) in tips) {
            val clumps = if (w >= 1f) 2 else 1
            for (c in 0 until clumps) {
                val t = if (c == 0) 1f else rnd.range(0.5f, 0.7f)
                val (cp, cd) = m.along(pts, t)
                if (rnd.chance(p.open * 0.5f) && c > 0) continue
                val count = 4 + rnd.int(3)
                for (k in 0 until count) {
                    val pos = cp + rnd.onSphere(Vec3()) * (p.cardM * 0.32f)
                    pos.y += p.cardM * 0.12f
                    val out = Vec3(cd.x, 0f, cd.z).let { if (it.len() < 1e-3f) Vec3(1f, 0f, 0f) else it.norm() }
                    val face = (UP * 0.75f + out * 0.35f + rnd.onSphere(Vec3()) * 0.45f).norm()
                    m.addCardExplicit(rnd, pos, face, out, p.cardM, p.cardM, if (k % 2 == 0) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B,
                        p.foliage, 0.85f, (m.trunkSway(pos.y) + 0.35f).coerceAtMost(1f), lightMix = 0.72f, keep = if (c == 0) 0f else 0.6f)
                }
            }
        }
        return m
    }

    private fun umbrella(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 15f
        val r = 7.2f
        val y0 = h * 0.6f
        val crown = Crown(y0, h, r, 0.42f, 1.4f, 2.8f)
        val m = TreeModel(h, crown, WHITE)
        val fork = h * 0.42f
        val lean = 0.08f
        val trunk = List(6) { i ->
            val y = fork * i / 5
            Vec3(lean * y + 0.25f * sin(y * 0.6f), y, 0.15f * sin(y * 0.4f))
        }
        m.addStem(trunk, TreeModel.flare(0f, 0.42f), 0.3f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, y0) }
        val forkP = trunk.last()
        val limbs = 4 + rnd.int(2)
        val ends = ArrayList<List<Vec3>>()
        for (i in 0 until limbs) {
            val az = i * 2f * PI.toFloat() / limbs + rnd.sym(0.4f)
            val reach = r * rnd.range(0.45f, 0.65f)
            val endY = h * rnd.range(0.78f, 0.86f)
            val end = Vec3(cos(az) * reach, endY, sin(az) * reach)
            val mid = forkP.lerp(end, 0.5f) + Vec3(rnd.sym(0.4f), rnd.range(0f, 0.6f), rnd.sym(0.4f))
            val pts = listOf(forkP.copy(), forkP.lerp(mid, 0.5f), mid, mid.lerp(end, 0.5f), end)
            m.addStem(pts, 0.24f, 0.09f, 1, m.trunkSway(fork), 0.03f) { q, _ -> TreeModel.barkShade(WHITE, q.y, y0) }
            ends += pts
            for (j in 0 until 4) {
                val a2 = az + rnd.sym(1.1f)
                val len = rnd.range(1.8f, 3.2f)
                val d = Vec3(cos(a2), rnd.range(0.05f, 0.35f), sin(a2)).norm()
                val sub = m.grow(rnd, end, d, 0.8f, len, 0.15f, 0.3f, 0.95f, 0.8f)
                if (sub.size >= 2) {
                    m.addStem(sub, 0.08f, 0.02f, 2, m.trunkSway(end.y) + 0.05f, 0.08f) { q, _ -> TreeModel.barkShade(WHITE, q.y, y0) }
                    ends += sub
                }
            }
        }
        // The canopy: a dense flattened dome, cards on the top facing up, a thinner dark layer below.
        val count = 270
        for (c in 0 until count) {
            val az = rnd.angle()
            val rr = r * sqrt(rnd.f()) * 0.97f
            val q = rr / r
            val topY = h - (h - (y0 + 1.8f)) * q.pow(2.3f) - 0.3f
            val under = c % 4 == 3
            val y = if (under) topY - rnd.range(1.0f, 1.8f) * (1f - q * 0.5f) else topY - rnd.range(0f, 0.6f)
            val pos = Vec3(cos(az) * rr, y, sin(az) * rr)
            val out = Vec3(cos(az), 0f, sin(az))
            val face = if (under) (UP * -0.2f + out * 0.6f + rnd.onSphere(Vec3()) * 0.5f).norm()
            else (UP * (1.1f - q * 0.6f) + out * (0.2f + q * 0.6f) + rnd.onSphere(Vec3()) * 0.35f).norm()
            m.addCardExplicit(rnd, pos, face, out, 2.1f, 2.1f, if (c % 2 == 0) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B, WHITE, 0.95f,
                (m.trunkSway(y) + 0.3f + 0.1f * q).coerceAtMost(1f), lightMix = 0.75f, keep = if (under) 0.9f else (1f - q) * 0.5f)
        }
        return m
    }

    private fun cypress(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 17f
        val crown = Crown(0.3f, h, 1.55f, 0.3f, 1.3f, 1.35f)
        val m = TreeModel(h, crown, WHITE)
        val trunk = List(8) { i -> Vec3(rnd.sym(0.04f), h * 0.94f * i / 7f, rnd.sym(0.04f)) }
        m.addStem(trunk, TreeModel.flare(0f, 0.26f), 0.03f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 1f) }
        val count = 230
        for (c in 0 until count) {
            val t = (c + rnd.f()) / count
            val y = crown.y0 + 0.3f + (crown.y1 - crown.y0 - 0.5f) * t.pow(0.92f)
            val az = c * GOLDEN + rnd.sym(0.2f)
            val inner = c % 5 == 4
            val rr = crown.radiusAt(y) * (if (inner) rnd.range(0.25f, 0.5f) else rnd.range(0.72f, 0.98f))
            val pos = Vec3(cos(az) * rr, y, sin(az) * rr)
            val out = Vec3(cos(az), 0.12f, sin(az)).norm()
            val face = (out * 0.8f + rnd.onSphere(Vec3()) * 0.3f).norm()
            val upv = (UP + out * 0.15f).norm()
            val size = 1.25f * (if (t > 0.85f) 0.75f else 1f)
            m.addCardExplicit(rnd, pos, face, upv, size * 0.85f, size * 1.25f, if (c % 2 == 0) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B,
                WHITE, 0.9f, (m.trunkSway(y) + 0.2f).coerceAtMost(1f), lightMix = 0.8f, keep = if (inner) 1f else 0.2f)
        }
        return m
    }

    private fun olive(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 6f
        val crown = Crown(1.4f, h, 3.3f, 0.45f, 1.7f, 2.0f)
        val m = TreeModel(h, crown, WHITE)
        val stems = 2 + rnd.int(2)
        val tops = ArrayList<Vec3>()
        for (s in 0 until stems) {
            val az = s * 2f * PI.toFloat() / stems + rnd.sym(0.5f)
            val pts = ArrayList<Vec3>()
            for (i in 0..5) {
                val y = 2.0f * i / 5
                val twist = az + y * 0.9f
                pts += Vec3(cos(twist) * (0.12f + 0.18f * y), y, sin(twist) * (0.12f + 0.18f * y))
            }
            m.addStem(pts, 0.3f, 0.16f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 1.5f) }
            tops += pts.last()
        }
        val clumps = ArrayList<Vec3>()
        for (i in 0 until 7) {
            val base = tops[i % tops.size]
            val az = i * GOLDEN + rnd.sym(0.4f)
            val d = TreeModel.dirFrom(az, rnd.range(40f, 62f) * DEG)
            val pts = m.grow(rnd, base, d, 0.6f, rnd.range(2.0f, 2.8f), 0.15f, 0.6f, 0.8f, 1.2f)
            m.addStem(pts, 0.12f, 0.03f, 1, 0.05f, 0.08f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 1.5f) }
            clumps += pts.last()
        }
        clumps += Vec3(0f, h - 1.3f, 0f)
        for ((k, cc) in clumps.withIndex()) {
            val rad = if (k == clumps.size - 1) 1.3f else rnd.range(1.0f, 1.35f)
            val n = 20
            for (i in 0 until n) {
                val d = rnd.onSphere(Vec3())
                if (d.y < -0.5f) d.y = -d.y * 0.3f
                val pos = cc + d * (rad * rnd.range(0.6f, 1f))
                pullInside(crown, pos, 1.05f)
                val face = (d * 0.7f + rnd.onSphere(Vec3()) * 0.4f).norm()
                m.addCardExplicit(rnd, pos, face, UP, 1.05f, 1.05f, if (i % 2 == 0) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B, WHITE, 0.85f,
                    0.4f + 0.1f * d.y, lightMix = 0.7f, keep = if (d.y > 0.3f) 0.1f else 0.7f)
            }
        }
        return m
    }

    private fun palm(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 11f
        val trunkTop = h - 1.6f
        val crown = Crown(trunkTop - 3f, h + 0.5f, 4.8f, 0.55f, 1.5f, 2f)
        val m = TreeModel(h, crown, WHITE)
        val trunk = List(8) { i ->
            val y = trunkTop * i / 7f
            Vec3(0.35f * (y / trunkTop).pow(2f), y, 0f)
        }
        m.addStem(trunk, 0.5f, 0.42f, 0, 0f, 0f) { q, _ -> TreeModel.barkShade(WHITE, q.y, 99f) }
        val topP = trunk.last() + Vec3(0f, 0.3f, 0f)
        val fronds = 28
        for (k in 0 until fronds) {
            val f = k / (fronds - 1f)
            val az = k * GOLDEN
            val elev = (62f - 95f * f + rnd.sym(8f)) * DEG
            val len = rnd.range(4.4f, 5.4f)
            val d1 = Vec3(cos(elev) * cos(az), sin(elev), cos(elev) * sin(az))
            val p1 = topP + d1 * (len * 0.5f)
            val d2 = (d1 + Vec3(0f, -0.55f - 0.3f * f, 0f)).norm()
            val p2 = p1 + d2 * (len * 0.5f)
            val sw = 0.35f + 0.25f * f
            for ((a, b) in listOf(topP to p1, p1 to p2)) {
                val dir = (b - a).norm()
                val side = dir.cross(UP).let { if (it.len() < 1e-3f) Vec3(1f, 0f, 0f) else it.norm() }
                val flat = side.cross(dir).norm().let { if (it.y < 0) it * -1f else it }
                for (v in 0..1) {
                    val face = rotate(flat, dir, (if (v == 0) 1f else -1f) * 32f * DEG)
                    val c = a.lerp(b, 0.5f)
                    m.addCardExplicit(rnd, c, face, dir, 1.7f, (b - a).len() * 1.05f, TreeAtlas.FOLIAGE_A, WHITE, 0.8f,
                        if (a === topP) sw * 0.6f else sw, lightMix = 0.6f, keep = if (v == 0) 0f else 0.5f)
                }
            }
        }
        return m
    }

    private fun mugo(seed: Long): TreeModel {
        val rnd = Rand(seed)
        val h = 2.2f
        val crown = Crown(0.2f, h, 2.0f, 0.4f, 1.3f, 2.2f)
        val m = TreeModel(h, crown, WHITE)
        for (s in 0 until 6) {
            val az = s * GOLDEN + rnd.sym(0.4f)
            val d = TreeModel.dirFrom(az, rnd.range(60f, 78f) * DEG)
            val pts = m.grow(rnd, Vec3(0f, 0f, 0f), d, 0.45f, rnd.range(1.6f, 2.4f), 0.55f, 0.5f, 1.5f, 1.2f)
            m.addStem(pts, 0.08f, 0.02f, if (s == 0) 0 else 1, 0f, 0.12f) { q, _ -> TreeModel.barkShade(floatArrayOf(0.6f, 0.58f, 0.55f), q.y + 0.6f, 9f) }
            for (t in listOf(1f, 0.6f)) {
                val (cp, cd) = m.along(pts, t)
                for (k in 0 until 3) {
                    val pos = cp + rnd.onSphere(Vec3()) * 0.3f
                    pos.y = pos.y.coerceAtLeast(0.35f)
                    val out = Vec3(cd.x, 0f, cd.z).let { if (it.len() < 1e-3f) Vec3(1f, 0f, 0f) else it.norm() }
                    val face = (UP * 0.8f + out * 0.3f + rnd.onSphere(Vec3()) * 0.4f).norm()
                    m.addCardExplicit(rnd, pos, face, out, 1.15f, 1.15f, if (k % 2 == 0) TreeAtlas.FOLIAGE_A else TreeAtlas.FOLIAGE_B,
                        floatArrayOf(0.78f, 0.86f, 0.8f), 0.8f, 0.35f, lightMix = 0.7f, keep = if (t == 1f) 0f else 0.6f)
                }
            }
        }
        return m
    }
}

/** A card with an explicit face normal and up direction (sprays, tufts, fronds). */
internal fun TreeModel.addCardExplicit(
    rnd: Rand, p: Vec3, face: Vec3, up0: Vec3, w: Float, h: Float, rect: UvRect, base: FloatArray, aoStrength: Float,
    sway: Float, lightMix: Float = 0.75f, keep: Float = 1f, variation: Float = 0.09f,
) {
    var up = up0 - face * up0.dot(face)
    if (up.len() < 0.05f) up = face.anyPerp()
    up.norm()
    val out = crown.normal(p)
    val light = (out * lightMix + face * (1f - lightMix) + UP * 0.4f).norm()  // sky bias as in TreeModel.addCard
    val (top, bottom) = cardColours(p, max(w, h), base, aoStrength, rnd, variation)
    cards += Card(p.copy(), face.copy().norm(), up, w, h, rect, top, bottom, sway, light, rnd.chance(0.5f), keep)
}

@Suppress("unused")
private fun clampf(x: Float, a: Float, b: Float) = min(b, max(a, x))
