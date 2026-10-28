package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Instanced props of the race dressing. Local axes: y up, the side that faces the road is -z
 * (the instance yaw turns -z towards the road), x along the road for long things (barriers, campers).
 * Vehicles that drive face -z like the riders.
 */
object Props {
    private fun proto(id: String, radius: Float, vararg lods: Pair<Float, List<Pair<SceneMaterial, MeshBuffer>>>) =
        Prototype(id, lods.map { (d, parts) -> ProtoLod(parts.filter { it.second.indexCount > 0 }, d) }, radius)

    /**
     * Axis-aligned panel in the xy plane at [z], facing -z ([facePlusZ] false) or +z. The UV rectangle
     * is mapped so the print reads correctly from the side it faces.
     */
    internal fun panel(m: MeshBuilder, x0: Float, y0: Float, x1: Float, y1: Float, z: Float, facePlusZ: Boolean, r: Atlas.Rect? = null) {
        val u0 = r?.u0 ?: 0f; val v0 = r?.v0 ?: 0f; val u1 = r?.u1 ?: 1f; val v1 = r?.v1 ?: 1f
        if (facePlusZ) m.quadUv(v3(x0, y0, z), v3(x1, y0, z), v3(x1, y1, z), v3(x0, y1, z), u0, v0, u1, v1, V3.Z)
        else m.quadUv(v3(x1, y0, z), v3(x0, y0, z), v3(x0, y1, z), v3(x1, y1, z), u0, v0, u1, v1, -V3.Z)
    }

    /**
     * Glass on a sloped nose given by two points (x, y) of the profile edge: the quad sits [out] metres
     * outside the edge so it does not sink into the body, inset from the sides by [halfW].
     */
    private fun slopeGlass(m: MeshBuilder, ax: Float, ay: Float, bx: Float, by: Float, halfW: Float, out: Float = 0.02f) {
        val d = v3(bx - ax, by - ay, 0f).norm()
        // Outward normal of an edge running up and back from the nose (the body is behind and below).
        val n = v3(-d.y, d.x, 0f)
        val a = v3(ax, ay, 0f) + n * out
        val b = v3(bx, by, 0f) + n * out
        m.quadUv(a.copy(z = -halfW), a.copy(z = halfW), b.copy(z = halfW), b.copy(z = -halfW), 0f, 0f, 1f, 1f, n)
    }

    // --- flags ---------------------------------------------------------------------------------

    /*
     * Flags: one prototype per kind, its cloth UVs on atlas cell 0; the instance variant picks the flag
     * (Atlas.flag(code)) and W1's object shader shifts the UVs by that cell for FLAG materials. One
     * prototype for all flags keeps them at one draw per kind and LOD. The pole is a separate part in
     * the PROPS material, since a UV shift would recolour a pole that sampled the atlas.
     */
    /** Hand-held flag matching the "fan_flag" pose (pole through both hands). */
    const val FLAG_HELD = "flag_held"
    /** A flag on a 3.6 m pole stuck in the ground or tied to a camper. */
    const val FLAG_POLE = "flag_pole"

    /**
     * Cloth hanging from a pole at x0, flying towards -x: seen from -z (the road) the hoist is on the
     * viewer's left and the print reads the right way round.
     */
    private fun cloth(m: MeshBuilder, cell: Int, x0: Float, yTop: Float, w: Float, h: Float, z: Float, nu: Int, nv: Int) {
        val start = m.vertexCount
        val du = (cell % Atlas.FLAG_COLS).toFloat() / Atlas.FLAG_COLS
        val dv = (cell / Atlas.FLAG_COLS).toFloat() / Atlas.FLAG_ROWS
        for (j in 0..nv) for (i in 0..nu) {
            val u = i.toFloat() / nu
            val v = j.toFloat() / nv
            // Baked folds and a slight sag at the fly end, so a still frame already looks like cloth.
            val ph = u * 7.5f + v * 1.3f
            val fold = 0.045f * sin(ph) * u
            val dFold = 0.045f * (7.5f * cos(ph) * u + sin(ph)) / w
            val sag = 0.06f * u * u
            val p = v3(x0 - u * w, yTop - v * h - sag, z + fold)
            val uu = du + Atlas.FLAG_U0 + (Atlas.FLAG_U1 - Atlas.FLAG_U0) * u
            val vv = dv + Atlas.FLAG_V0 + (Atlas.FLAG_V1 - Atlas.FLAG_V0) * v
            // Normal of z = f(x) facing -z; x runs opposite to u, hence the sign.
            m.vertex(p, v3(-dFold, 0f, -1f).norm(), uu, vv, floatArrayOf(1f, 1f, 1f), u * w)
        }
        for (j in 0 until nv) for (i in 0 until nu) {
            val a = start + j * (nu + 1) + i
            m.quad(a, a + nu + 1, a + nu + 2, a + 1)
        }
    }

    private fun flagProto(id: String, poleX: Float, poleZ: Float, poleBottom: Float, poleTop: Float, w: Float, h: Float, poleCol: Int): Prototype {
        fun lod(nu: Int, nv: Int, sides: Int): List<Pair<SceneMaterial, MeshBuffer>> {
            val c = MeshBuilder(64)
            cloth(c, 0, poleX, poleTop, w, h, poleZ, nu, nv)
            val pole = MeshBuilder(32)
            pole.hex(poleCol)
            pole.tube(v3(poleX, poleBottom, poleZ), v3(poleX, poleTop + 0.03f, poleZ), 0.014f, 0.012f, sides, capA = false)
            return listOf(RaceMaterials.FLAG to c.build(), RaceMaterials.PROPS to pole.build())
        }
        return proto(id, maxOf(w, poleTop), 45f to lod(10, 4, 5), 140f to lod(3, 2, 3), 450f to lod(1, 1, 3))
    }

    // --- barriers ------------------------------------------------------------------------------

    /** Length of one crowd barrier: instances are placed every [BARRIER_LEN] metres along the road. */
    const val BARRIER_LEN = 2.05f
    const val BARRIER_PLAIN = "barrier"
    /** Barrier with a printed cover: ONE prototype, the instance variant is the cover index ([Atlas.cover]). */
    const val BARRIER_COVER = "barrier_cover"

    private fun barrierFrame(m: MeshBuilder, lod: Int) {
        val half = BARRIER_LEN / 2 - 0.03f
        val steel = Col.hex(0xA9AEB4)
        m.withColor(steel) {
            val s = if (lod == 0) 5 else 3
            m.tube(v3(-half, 1.08f, 0f), v3(half, 1.08f, 0f), 0.02f, 0.02f, s, capA = false, capB = false)
            m.tube(v3(-half, 0.22f, 0f), v3(half, 0.22f, 0f), 0.017f, 0.017f, s, capA = false, capB = false)
            for (x in floatArrayOf(-half, half)) {
                m.tube(v3(x, 0.02f, 0f), v3(x, 1.1f, 0f), 0.02f, 0.02f, s, capA = false)
                // Flat steel foot across the barrier line.
                m.box(v3(x, 0.0175f, 0f), 0.06f, 0.035f, 0.62f, skipBottom = true)
            }
            if (lod == 0) {
                var x = -half + 0.12f
                while (x < half - 0.06f) {
                    m.tube(v3(x, 0.22f, 0f), v3(x, 1.08f, 0f), 0.009f, 0.009f, 3, capA = false, capB = false)
                    x += 0.125f
                }
            } else {
                // Far away the bars merge into a grey screen.
                m.withColor(Col.scale(steel, 0.8f)) {
                    panel(m, -half, 0.22f, half, 1.08f, 0f, false)
                    panel(m, -half, 0.22f, half, 1.08f, 0f, true)
                }
            }
        }
    }

    private fun barrier(covered: Boolean): Prototype {
        fun lod(l: Int): List<Pair<SceneMaterial, MeshBuffer>> {
            val f = MeshBuilder(256)
            barrierFrame(f, l)
            if (!covered) return listOf(RaceMaterials.PROPS to f.build())
            val c = MeshBuilder(16)
            // Cover 0; the renderer moves the UVs to the instance's cover (RaceMaterials.SIGNS_COVERS).
            val r = Atlas.cover(0)
            val half = BARRIER_LEN / 2 - 0.02f
            // Printed on both sides, so it reads correctly from the road and from behind.
            panel(c, -half, 0.14f, half, 1.06f, -0.035f, false, r)
            panel(c, -half, 0.14f, half, 1.06f, 0.035f, true, r)
            return listOf(RaceMaterials.PROPS to f.build(), RaceMaterials.SIGNS_COVERS to c.build())
        }
        return proto(if (covered) BARRIER_COVER else BARRIER_PLAIN, 1.2f, 70f to lod(0), 380f to lod(1))
    }

    // --- campers, tents, chairs, parasols ----------------------------------------------------------

    const val CAMPER = "camper"
    const val CAMPER_VAN = "camper_van"
    const val TENT = "tent"
    const val CHAIR = "chair"
    const val TABLE = "table"
    const val PARASOL = "parasol"
    /** Roof heights of the campers, for spectators standing on top. */
    const val CAMPER_ROOF = 2.9f
    const val VAN_ROOF = 2.58f
    /** Half lengths along x, for placement. */
    const val CAMPER_HALF_LEN = 3.45f
    const val VAN_HALF_LEN = 2.7f

    private fun wheel(m: MeshBuilder, x: Float, z: Float, r: Float, w: Float, segs: Int) {
        m.withColor(Col.hex(0x1C1C1C)) { m.tube(v3(x, r, z - w / 2), v3(x, r, z + w / 2), r, r, segs) }
        m.withColor(Col.hex(0x9A9EA3)) {
            m.tube(v3(x, r, z - w / 2 - 0.005f), v3(x, r, z - w / 2 + 0.01f), r * 0.55f, r * 0.55f, segs, capB = false)
            m.tube(v3(x, r, z + w / 2 - 0.01f), v3(x, r, z + w / 2 + 0.005f), r * 0.55f, r * 0.55f, segs, capA = false)
        }
    }

    /** Coach-built camper ("profilé"): 6.9 m long, cab nose at -x, door and awning on the road side (-z). */
    private fun camper(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val body = MeshBuilder(512)
        val gloss = MeshBuilder(128)
        val cloth = MeshBuilder(128)
        val white = Col.hex(0xF1F1EE)
        val glass = Col.hex(0x1A2228)
        val L = CAMPER_HALF_LEN
        val W = 1.16f
        if (lod == 0) {
            // Living box with a sloped cab nose (profile in the xy plane extruded across z).
            val prof = listOf(
                -L to 0.42f, -L + 0.05f to 1.0f, -L + 0.45f to 1.18f, -L + 1.25f to 2.1f, -L + 1.9f to 2.86f, L - 0.25f to 2.9f,
                L to 2.7f, L + 0.02f to 0.62f, L - 0.2f to 0.42f,
            )
            body.withColor(white) { body.prism(prof, -W, 2 * W) }
            // Grey swoosh along both sides.
            body.withColor(Col.hex(0x5C6670)) {
                for (plus in listOf(false, true)) {
                    val z = if (plus) W + 0.004f else -W - 0.004f
                    val a = v3(-L + 1.3f, 0.95f, z); val b = v3(L, 1.2f, z); val c = v3(L, 1.38f, z); val d = v3(-L + 1.3f, 1.08f, z)
                    if (plus) body.quadUv(a, b, c, d, 0f, 0f, 1f, 1f, V3.Z) else body.quadUv(b, a, d, c, 0f, 0f, 1f, 1f, -V3.Z)
                }
            }
            body.withColor(Col.hex(0x3A3F45)) { body.boxMinMax(v3(-L - 0.02f, 0.38f, -W - 0.01f), v3(-L + 0.6f, 0.62f, W + 0.01f)) }
            for ((x, z) in listOf(-L + 0.75f to -W + 0.1f, -L + 0.75f to W - 0.1f, L - 1.9f to -W + 0.1f, L - 1.9f to W - 0.1f)) wheel(body, x, z, 0.36f, 0.24f, 12)
            gloss.withColor(glass) {
                for (plus in listOf(false, true)) {
                    val z = if (plus) W + 0.006f else -W - 0.006f
                    panel(gloss, -L + 0.95f, 1.3f, -L + 1.75f, 2.05f, z, plus)
                    panel(gloss, -0.7f, 1.55f, 0.5f, 2.15f, z, plus)
                    panel(gloss, 1.6f, 1.55f, 2.6f, 2.15f, z, plus)
                }
                slopeGlass(gloss, -L + 0.5f, 1.24f, -L + 1.2f, 2.05f, W - 0.1f)
            }
            // Entry door on the road side.
            body.withColor(Col.hex(0xD6D6D2)) { panel(body, 0.75f, 0.55f, 1.35f, 2.35f, -W - 0.008f, false) }
            // Roof hatch.
            body.withColor(Col.hex(0xD9D9D6)) { body.box(v3(0.4f, 2.96f, 0f), 0.7f, 0.12f, 0.6f, skipBottom = true) }
            // Awning: rolled-out striped cloth with two legs.
            val stripes = arrayOf(Col.hex(0x2F6FB5), Col.hex(0xEDEDED))
            val n = 8
            for (i in 0 until n) {
                val x0 = -0.6f + 3.4f * i / n; val x1 = -0.6f + 3.4f * (i + 1) / n
                cloth.withColor(stripes[i % 2]) {
                    cloth.quadUv(v3(x0, 2.5f, -W), v3(x1, 2.5f, -W), v3(x1, 2.2f, -W - 2.4f), v3(x0, 2.2f, -W - 2.4f), 0f, 0f, 1f, 1f, v3(0, 1, -0.12).norm())
                }
            }
            cloth.withColor(stripes[0]) { panel(cloth, -0.6f, 2.05f, 2.8f, 2.2f, -W - 2.4f, false) }
            body.withColor(Col.hex(0xB0B4B8)) {
                body.tube(v3(-0.55f, 0f, -W - 2.35f), v3(-0.55f, 2.2f, -W - 2.35f), 0.02f, 0.02f, 4)
                body.tube(v3(2.75f, 0f, -W - 2.35f), v3(2.75f, 2.2f, -W - 2.35f), 0.02f, 0.02f, 4)
            }
        } else {
            body.withColor(white) { body.boxMinMax(v3(-L, 0.4f, -W), v3(L, CAMPER_ROOF, W), skipBottom = true) }
            body.withColor(Col.hex(0x1C1C1C)) { body.boxMinMax(v3(-L + 0.4f, 0f, -W + 0.1f), v3(L - 1.6f, 0.42f, W - 0.1f), skipBottom = true) }
            gloss.withColor(glass) { panel(gloss, -0.7f, 1.55f, 2.6f, 2.15f, -W - 0.01f, false) }
        }
        return listOf(RaceMaterials.PROPS to body.build(), RaceMaterials.GLOSS to gloss.build(), RaceMaterials.CLOTH to cloth.build())
    }

    /** Panel-van conversion (the most common camper on the passes): 5.4 m, white so the tint is the paint. */
    private fun van(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val body = MeshBuilder(256)
        val gloss = MeshBuilder(64)
        val paint = Col.hex(0xEDEDED)
        val glass = Col.hex(0x1A2228)
        val L = VAN_HALF_LEN
        val W = 1.02f
        if (lod == 0) {
            val prof = listOf(-L to 0.45f, -L + 0.02f to 0.95f, -L + 0.55f to 1.12f, -L + 1.25f to 2.2f, -L + 1.6f to 2.55f, L - 0.08f to 2.58f, L to 2.4f, L to 0.5f, L - 0.15f to 0.42f)
            body.withColor(paint) { body.prism(prof, -W, 2 * W) }
            body.withColor(Col.hex(0x2E3236)) { body.boxMinMax(v3(-L - 0.02f, 0.4f, -W - 0.01f), v3(-L + 0.5f, 0.66f, W + 0.01f)) }
            for ((x, z) in listOf(-L + 0.75f to -W + 0.08f, -L + 0.75f to W - 0.08f, L - 0.9f to -W + 0.08f, L - 0.9f to W - 0.08f)) wheel(body, x, z, 0.34f, 0.22f, 12)
            gloss.withColor(glass) {
                slopeGlass(gloss, -L + 0.6f, 1.2f, -L + 1.2f, 2.12f, W - 0.1f)
                for (plus in listOf(false, true)) {
                    val z = if (plus) W + 0.006f else -W - 0.006f
                    panel(gloss, -L + 1.0f, 1.3f, -L + 1.6f, 2.0f, z, plus)
                    panel(gloss, 0.6f, 1.55f, 1.6f, 2.05f, z, plus)
                }
            }
            // Roof box: the vans carry their life on the roof.
            body.withColor(Col.hex(0x2B2B2B)) { body.box(v3(0.6f, 2.72f, 0f), 1.8f, 0.28f, 0.8f, skipBottom = true) }
        } else {
            body.withColor(paint) { body.boxMinMax(v3(-L, 0.4f, -W), v3(L, VAN_ROOF, W), skipBottom = true) }
            body.withColor(Col.hex(0x1C1C1C)) { body.boxMinMax(v3(-L + 0.4f, 0f, -W + 0.1f), v3(L - 0.6f, 0.42f, W - 0.1f), skipBottom = true) }
        }
        return listOf(RaceMaterials.PROPS to body.build(), RaceMaterials.GLOSS to gloss.build())
    }

    /** Dome tent, light grey so the tint is its colour; the door faces -z. */
    private fun tent(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(128)
        m.withColor(floatArrayOf(0.9f, 0.9f, 0.9f)) { m.ellipsoid(V3.ZERO, 1.1f, 1.05f, 0.75f, if (lod == 0) 4 else 2, if (lod == 0) 10 else 6, yMin = 0f, yMax = 1f) }
        // Door flap leaning out of the dome (a flat triangle cannot follow the curve, so it stands proud).
        if (lod == 0) m.withColor(Col.hex(0x2A2A2A)) {
            val n = v3(0, 0.3, -1).norm()
            val a = m.vertex(v3(0.35f, 0.02f, -0.83f), n)
            val b = m.vertex(v3(-0.35f, 0.02f, -0.83f), n)
            val c = m.vertex(v3(0f, 0.88f, -0.55f), n)
            m.tri(a, b, c)
        }
        return listOf(RaceMaterials.CLOTH to m.build())
    }

    /** Folding camping chair facing -z, seat at [People.SIT_HEIGHT]. */
    private fun chair(): List<Pair<SceneMaterial, MeshBuffer>> {
        val f = MeshBuilder(64)
        val c = MeshBuilder(32)
        f.withColor(Col.hex(0x2A2A2A)) {
            for (x in floatArrayOf(-0.26f, 0.26f)) {
                f.tube(v3(x, 0f, -0.25f), v3(x, 0.45f, 0.22f), 0.012f, 0.012f, 3, capA = false, capB = false)
                f.tube(v3(x, 0f, 0.22f), v3(x, 0.45f, -0.25f), 0.012f, 0.012f, 3, capA = false, capB = false)
                f.tube(v3(x, 0.45f, 0.22f), v3(x, 0.92f, 0.32f), 0.012f, 0.012f, 3, capA = false, capB = false)
            }
        }
        c.withColor(Col.hex(0x2B5DA8)) {
            // Seat facing up, backrest facing the sitter (-z).
            c.quadUv(v3(-0.26f, 0.42f, -0.22f), v3(-0.26f, 0.4f, 0.2f), v3(0.26f, 0.4f, 0.2f), v3(0.26f, 0.42f, -0.22f), 0f, 0f, 1f, 1f, V3.Y)
            c.quadUv(v3(0.26f, 0.48f, 0.22f), v3(-0.26f, 0.48f, 0.22f), v3(-0.26f, 0.9f, 0.31f), v3(0.26f, 0.9f, 0.31f), 0f, 0f, 1f, 1f, v3(0, 0.2, -1).norm())
        }
        return listOf(RaceMaterials.PROPS to f.build(), RaceMaterials.CLOTH to c.build())
    }

    private fun table(): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(64)
        m.withColor(Col.hex(0xE8E8E4)) { m.box(v3(0f, 0.72f, 0f), 0.9f, 0.03f, 0.6f) }
        m.withColor(Col.hex(0x8A8E92)) { for (x in floatArrayOf(-0.4f, 0.4f)) for (z in floatArrayOf(-0.26f, 0.26f)) m.tube(v3(x, 0f, z), v3(x, 0.71f, z), 0.012f, 0.012f, 3, capA = false, capB = false) }
        // A bottle and a cool box: the picnic of a day on the mountain.
        m.withColor(Col.hex(0x2E7D32)) { m.tube(v3(-0.2f, 0.735f, 0.05f), v3(-0.2f, 1.0f, 0.05f), 0.035f, 0.02f, 5) }
        m.withColor(Col.hex(0xC62828)) { m.box(v3(0.15f, 0.845f, 0f), 0.36f, 0.22f, 0.26f, skipBottom = true) }
        return listOf(RaceMaterials.PROPS to m.build())
    }

    /** Parasol in light grey, tinted per instance. */
    private fun parasol(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val p = MeshBuilder(16)
        val c = MeshBuilder(64)
        p.withColor(Col.hex(0xDADADA)) { p.tube(v3(0f, 0f, 0f), v3(0f, 2.15f, 0f), 0.02f, 0.02f, 4, capA = false) }
        val n = if (lod == 0) 8 else 5
        val light = floatArrayOf(0.9f, 0.9f, 0.9f)
        val darker = floatArrayOf(0.72f, 0.72f, 0.72f)
        // Alternating panels: each panel has its own vertices so it keeps its own colour.
        for (i in 0 until n) {
            val a0 = 2 * PI.toFloat() * i / n
            val a1 = 2 * PI.toFloat() * (i + 1) / n
            val col = if (i % 2 == 0) light else darker
            val t = c.vertex(v3(0f, 2.25f, 0f), V3.Y, c = col)
            val r0 = c.vertex(v3(cos(a0) * 1.1f, 1.85f, sin(a0) * 1.1f), v3(cos(a0) * 0.35f, 1f, sin(a0) * 0.35f).norm(), c = col)
            val r1 = c.vertex(v3(cos(a1) * 1.1f, 1.85f, sin(a1) * 1.1f), v3(cos(a1) * 0.35f, 1f, sin(a1) * 0.35f).norm(), c = col)
            c.tri(t, r1, r0)
        }
        return listOf(RaceMaterials.PROPS to p.build(), RaceMaterials.CLOTH to c.build())
    }

    // --- race vehicles -------------------------------------------------------------------------

    const val HELI = "race_heli"
    const val HELI_ROTOR = "race_heli_rotor"
    const val HELI_TAIL_ROTOR = "race_heli_tail_rotor"
    /** Main rotor hub in the helicopter's local frame; the rotor prototype spins about its local +y. */
    val HELI_ROTOR_HUB = v3(0, 2.92, -0.35)
    /** Tail rotor hub, left of the fin; the tail rotor prototype spins about its local x. */
    val HELI_TAIL_HUB = v3(-0.13, 2.75, 7.1)
    const val MOTO = "race_moto"
    const val TEAM_CAR = "race_team_car"

    private fun heli(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(512)
        val g = MeshBuilder(64)
        val white = Col.hex(0xF2F2F2)
        val blue = Col.hex(0x1C3F94)
        val segs = if (lod == 0) 12 else 6
        // Cabin: rings along z from the nose (-z) to the tail, a blue band low on the flanks.
        data class R(val z: Float, val y: Float, val rx: Float, val ry: Float)
        val rings = listOf(R(-2.0f, 1.25f, 0.15f, 0.2f), R(-1.75f, 1.3f, 0.6f, 0.62f), R(-1.0f, 1.45f, 0.82f, 0.85f), R(0.2f, 1.5f, 0.85f, 0.9f), R(1.2f, 1.6f, 0.7f, 0.85f), R(2.0f, 1.85f, 0.32f, 0.45f))
        val rr = if (lod == 0) rings else listOf(rings[0], rings[2], rings[4], rings[5])
        m.colorGrid(rr.size, segs, wrap = true,
            point = { r, c -> val a = (2 * PI * c / segs).toFloat(); val ring = rr[r]; v3(cos(a) * ring.rx, ring.y + sin(a) * ring.ry, ring.z) },
            quadColor = { _, c -> val s = sin(2 * PI * (c + 0.5) / segs); if (lod == 0 && s < -0.15 && s > -0.75) blue else white },
            outwardFrom = { r -> v3(0f, rr[r].y, rr[r].z) })
        // Glass canopy bulging out of the front of the cabin.
        g.withColor(Col.hex(0x1D2A33)) { g.ellipsoid(v3(0f, 1.55f, -1.3f), 0.86f, 0.8f, 1.0f, if (lod == 0) 5 else 3, segs, yMin = -0.1f, yMax = 0.9f) }
        m.withColor(white) {
            // Engine fairing, mast, tail boom, horizontal stabiliser.
            m.box(v3(0f, 2.45f, 0.3f), 1.1f, 0.55f, 1.9f)
            m.tube(v3(0f, 2.6f, -0.35f), HELI_ROTOR_HUB, 0.1f, 0.08f, 6)
            m.tube(v3(0f, 1.9f, 1.8f), v3(0f, 2.05f, 7.1f), 0.32f, 0.13f, if (lod == 0) 8 else 4)
            m.box(v3(0f, 2.0f, 6.3f), 2.1f, 0.06f, 0.42f)
        }
        // Fin: a profile in the xy plane turned into the yz plane (local x -> world -z), swept back.
        m.withColor(blue) { m.with(Affine.translate(0f, 2.05f, 6.95f) * Affine.rotY(PI.toFloat() / 2)) { m.prism(listOf(0f to 0f, 0.35f to 0f, -0.3f to 1.3f, -0.6f to 1.3f), -0.04f, 0.08f) } }
        // Skids and their struts.
        m.withColor(Col.hex(0x3A3A3A)) {
            for (x in floatArrayOf(-1.05f, 1.05f)) {
                m.tube(v3(x, 0.08f, -1.6f), v3(x, 0.08f, 1.4f), 0.045f, 0.045f, 5)
                for (z in floatArrayOf(-0.8f, 0.8f)) m.tube(v3(x, 0.08f, z), v3(x * 0.57f, 1.05f, z * 0.8f), 0.04f, 0.04f, 4, capA = false, capB = false)
            }
        }
        // TV camera ball under the nose.
        m.withColor(Col.hex(0x2A2A2A)) { m.sphere(v3(0f, 0.75f, -1.55f), 0.24f, 4, 8) }
        return listOf(RaceMaterials.PROPS to m.build(), RaceMaterials.GLOSS to g.build())
    }

    private fun heliRotor(): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(64)
        m.withColor(Col.hex(0x2B2B2B)) {
            m.tube(v3(0f, -0.05f, 0f), v3(0f, 0.12f, 0f), 0.16f, 0.12f, 6)
            for (k in 0 until 3) {
                // Three blades, 5.35 m from the hub, chord 0.3 m.
                m.with(Affine.rotY(2 * PI.toFloat() * k / 3)) { m.box(v3(2.75f, 0.05f, 0f), 5.2f, 0.04f, 0.3f) }
            }
        }
        return listOf(RaceMaterials.PROPS to m.build())
    }

    private fun tailRotor(): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(32)
        m.withColor(Col.hex(0x2B2B2B)) { m.box(V3.ZERO, 0.04f, 1.86f, 0.16f) }
        return listOf(RaceMaterials.PROPS to m.build())
    }

    /** TV motorbike: a big tourer with the pilot and a cameraman sitting backwards to film the riders. */
    private fun moto(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(1024)
        val dark = Col.hex(0x26292D)
        val white = Col.hex(0xEDEDED)
        val segs = if (lod == 0) 14 else 6
        for (z in floatArrayOf(-0.78f, 0.72f)) m.withColor(Col.hex(0x1A1A1A)) { m.torusX(v3(0f, 0.33f, z), 0.255f, 0.075f, segs, if (lod == 0) 6 else 3) }
        m.withColor(white) {
            // Fairing, tank and side cases.
            m.with(Affine.translate(0f, 0.95f, -0.55f) * Affine.rotX(-0.35f)) { m.ellipsoid(V3.ZERO, 0.42f, 0.38f, 0.42f, if (lod == 0) 5 else 3, segs) }
            m.box(v3(0f, 0.78f, 0.05f), 0.42f, 0.42f, 0.8f)
            for (x in floatArrayOf(-0.33f, 0.33f)) m.box(v3(x, 0.72f, 0.62f), 0.2f, 0.4f, 0.52f)
        }
        m.withColor(dark) {
            m.box(v3(0f, 0.98f, 0.3f), 0.36f, 0.1f, 0.85f)
            m.tube(v3(0f, 0.33f, -0.78f), v3(0f, 1.05f, -0.48f), 0.04f, 0.04f, 5)
            m.tube(v3(-0.38f, 1.12f, -0.45f), v3(0.38f, 1.12f, -0.45f), 0.016f, 0.016f, 4)
            m.box(v3(0f, 0.45f, 0.1f), 0.3f, 0.3f, 0.7f)
        }
        m.withColor(Col.hex(0x4A5560)) { m.quadUv(v3(-0.25f, 1.15f, -0.75f), v3(0.25f, 1.15f, -0.75f), v3(0.22f, 1.55f, -0.62f), v3(-0.22f, 1.55f, -0.62f), 0f, 0f, 1f, 1f) }
        val crew = BodyType(0.2f, 0.1f, 0.17f, 0.12f, 0.02f, false)
        val pilot = PersonPose("moto_pilot", crew, v3(-0.36, 1.12, -0.42), v3(0.36, 1.12, -0.42), v3(-1, -0.5, 0.3), v3(1, -0.5, 0.3),
            v3(-0.2, 0.45, -0.05), v3(0.2, 0.45, -0.05), kneeHint = v3(0, 1, -0.6), hipY = 1.02f, hipZ = 0.2f, lean = 0.3f, legwear = Legwear.TROUSERS_DARK, hair = 0x222222)
        // The cameraman faces backwards; his pose is written in his own frame (he faces his -z).
        val cam = PersonPose("moto_cam", crew, v3(0.02, 1.7, -0.18), v3(0.16, 1.6, -0.24), v3(-1, -0.6, 0.2), v3(1, -0.6, 0.2),
            v3(-0.22, 0.6, -0.12), v3(0.22, 0.6, -0.12), kneeHint = v3(0, 1, -0.6), hipY = 1.08f, hipZ = 0.1f, lean = 0.05f, legwear = Legwear.TROUSERS_DARK, hair = 0x5A4636)
        val pb = PersonBuilder(pilot)
        val cb = PersonBuilder(cam)
        val l = if (lod == 0) 0 else 1
        m.append(pb.body(l))
        m.append(pb.top(l), recolor = Col.hex(0x1F3C88))
        m.withColor(white) { m.sphere(pb.headCentre + v3(0f, 0.02f, 0f), 0.145f, if (lod == 0) 4 else 3, if (lod == 0) 8 else 5) }
        m.with(Affine.translate(0f, 0f, 0.62f) * Affine.rotY(PI.toFloat())) {
            m.append(cb.body(l))
            m.append(cb.top(l), recolor = Col.hex(0xE8E8E8))
            // Shoulder camera, lens towards the riders behind the bike.
            m.withColor(Col.hex(0x1E1E1E)) { m.box(v3(0.13f, 1.7f, -0.08f), 0.16f, 0.2f, 0.5f) }
            m.withColor(Col.hex(0x1F3C88)) { m.sphere(cb.headCentre + v3(0f, 0.02f, 0f), 0.14f, if (lod == 0) 4 else 3, if (lod == 0) 8 else 5) }
        }
        return listOf(RaceMaterials.PROPS to m.build())
    }

    /** Team car: estate with spare bikes on the roof; white paint so the tint is the team colour. */
    private fun teamCar(lod: Int): List<Pair<SceneMaterial, MeshBuffer>> {
        val m = MeshBuilder(1024)
        val g = MeshBuilder(64)
        val paint = Col.hex(0xF2F2F2)
        val L = 2.43f
        val W = 0.92f
        // Profiles are written with the nose at -x and turned so the nose faces -z (local x -> z).
        val turn = Affine.rotY(-PI.toFloat() / 2)
        val lower = listOf(-L to 0.35f, -L to 0.72f, -L + 0.9f to 0.95f, L - 0.1f to 1.0f, L to 0.75f, L to 0.35f)
        val house = listOf(-L + 0.98f to 0.96f, -L + 1.75f to 1.43f, L - 0.55f to 1.45f, L - 0.05f to 1.25f, L - 0.1f to 0.98f)
        m.withColor(paint) { m.with(turn) { m.prism(lower, -W, 2 * W) } }
        g.withColor(Col.hex(0x1A2228)) { g.with(turn) { g.prism(house, -W + 0.07f, 2 * W - 0.14f) } }
        m.withColor(paint) { m.with(turn) { m.box(v3(0.35f, 1.465f, 0f), 2.1f, 0.04f, 2 * W - 0.2f) } }
        for ((x, z) in listOf(-W + 0.08f to -L + 0.8f, W - 0.08f to -L + 0.8f, -W + 0.08f to L - 0.8f, W - 0.08f to L - 0.8f)) {
            m.withColor(Col.hex(0x1A1A1A)) { m.tube(v3(x - 0.11f, 0.33f, z), v3(x + 0.11f, 0.33f, z), 0.33f, 0.33f, if (lod == 0) 12 else 6) }
        }
        if (lod == 0) {
            m.withColor(Col.hex(0x2A2A2A)) {
                m.tube(v3(-0.8f, 1.5f, -0.9f), v3(-0.8f, 1.5f, 1.3f), 0.025f, 0.025f, 4)
                m.tube(v3(0.8f, 1.5f, -0.9f), v3(0.8f, 1.5f, 1.3f), 0.025f, 0.025f, 4)
            }
            // Spare bikes on the roof: two wheels and a frame triangle each.
            for (k in 0 until 4) {
                val x = -0.6f + 0.4f * k
                m.withColor(Col.hex(0x30343A)) {
                    for (z in floatArrayOf(-0.55f, 0.45f)) m.torusX(v3(x, 1.86f, z), 0.32f, 0.014f, 12, 3)
                    m.tube(v3(x, 1.9f, -0.1f), v3(x, 2.25f, -0.35f), 0.02f, 0.02f, 3)
                    m.tube(v3(x, 1.9f, -0.1f), v3(x, 2.28f, 0.15f), 0.02f, 0.02f, 3)
                    m.tube(v3(x, 2.28f, 0.15f), v3(x, 2.25f, -0.35f), 0.02f, 0.02f, 3)
                }
            }
        }
        return listOf(RaceMaterials.PROPS to m.build(), RaceMaterials.GLOSS to g.build())
    }

    fun prototypes(): List<Prototype> {
        val out = ArrayList<Prototype>()
        out += flagProto(FLAG_HELD, 0.11f, -0.17f, 0.95f, 2.6f, 0.9f, 0.6f, 0xE0E0E0)
        out += flagProto(FLAG_POLE, 0f, 0f, 0f, 3.6f, 1.2f, 0.8f, 0xBDBDBD)
        out += barrier(false)
        out += barrier(true)
        out += proto(CAMPER, 4f, 90f to camper(0), 900f to camper(1))
        out += proto(CAMPER_VAN, 3.2f, 90f to van(0), 900f to van(1))
        out += proto(TENT, 1.4f, 60f to tent(0), 400f to tent(1))
        out += proto(CHAIR, 0.6f, 60f to chair())
        out += proto(TABLE, 0.6f, 50f to table())
        out += proto(PARASOL, 1.2f, 70f to parasol(0), 300f to parasol(1))
        out += proto(HELI, 6f, 400f to heli(0), 4000f to heli(1))
        out += proto(HELI_ROTOR, 5.4f, 4000f to heliRotor())
        out += proto(HELI_TAIL_ROTOR, 1f, 400f to tailRotor())
        out += proto(MOTO, 1.6f, 60f to moto(0), 600f to moto(1))
        out += proto(TEAM_CAR, 2.8f, 80f to teamCar(0), 800f to teamCar(1))
        return out
    }
}
