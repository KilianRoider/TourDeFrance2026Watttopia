package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Street furniture of French (and Catalan) roads, modelled facing -z (the instance yaw turns that
 * towards whoever should see it): lamps, warning signs, speed limits, J4 chevron boards at
 * hairpins, J6 delineator posts every 50 m, kilometre bornes, snow poles, dry-stone walls and
 * wooden pasture fences. Plates use the scn_signs atlas ([SignAtlas]).
 */
internal object SignAtlas {
    const val TEXTURE = "scn_signs"
    private fun cell(c: Int, r: Int): UvRect {
        val g = UvRect.grid(c, r, 4, 4)
        val e = 0.003f
        return UvRect(g.u0 + e, g.v0 + e, g.u1 - e, g.v1 - e)
    }
    val BEND_RIGHT = cell(0, 0)
    val BEND_LEFT = cell(1, 0)
    val BENDS_RIGHT = cell(2, 0)
    val BENDS_LEFT = cell(3, 0)
    val SPEED_50 = cell(0, 1)
    val SPEED_70 = cell(1, 1)
    val SPEED_80 = cell(2, 1)
    val SPEED_30 = cell(3, 1)
    val CHEVRON_RIGHT = cell(0, 2)
    val CHEVRON_LEFT = cell(1, 2)
    val GIVE_WAY = cell(2, 2)
    val BACK = cell(3, 2)
    val BORNE_FACE = cell(0, 3)
    val BORNE_FACE_Y = cell(1, 3)
    val DIRECTION = cell(2, 3)
    val WALL = cell(3, 3)
}

internal object FurnitureProtos {
    private val grey = lin(0x8A8E90)
    private val darkGrey = lin(0x3C4044)
    private val white = lin(0xEDEDE8)
    private val black = lin(0x1E1E1E)
    private val red = lin(0xC4202A)
    private val yellow = lin(0xE8B620)
    private val orange = lin(0xF07818)
    private val parisGreen = lin(0x26302A)

    private fun proto(id: String, radius: Float, vararg lods: Pair<List<Pair<SceneMaterial, MeshBuilder>>, Float>): Prototype =
        Prototype(id, lods.map { (parts, d) -> ProtoLod(parts.filter { !it.second.isEmpty }.map { it.first to it.second.build() }, d) }, radius)

    /** A sign plate on a post: plate centre at [plateY], [w] × [h], front face towards -z. */
    fun signPlate(id: String, rect: UvRect, w: Float, h: Float, plateY: Float, postR: Float = 0.035f): Prototype {
        val metal = MeshBuilder(64, 128)
        // The post stands wholly behind the plate (its back face is at z = 0.012).
        Shapes.prism(metal, 0f, postR + 0.02f, 0f, plateY + h * 0.3f, postR, postR, 6, grey)
        val plate = MeshBuilder(8, 12)
        val z = -0.0f
        // front (normal -z): counter-clockwise seen from -z
        val f = plate.vertexCount
        plate.vertex(w / 2, plateY - h / 2, z, 0f, 0f, -1f, rect.u(0f), rect.v(1f), 1f, 1f, 1f)
        plate.vertex(-w / 2, plateY - h / 2, z, 0f, 0f, -1f, rect.u(1f), rect.v(1f), 1f, 1f, 1f)
        plate.vertex(-w / 2, plateY + h / 2, z, 0f, 0f, -1f, rect.u(1f), rect.v(0f), 1f, 1f, 1f)
        plate.vertex(w / 2, plateY + h / 2, z, 0f, 0f, -1f, rect.u(0f), rect.v(0f), 1f, 1f, 1f)
        plate.quad(f, f + 1, f + 2, f + 3)
        // back (normal +z): the same outline (alpha from the same cell keeps the plate shape), grey tint
        val b = plate.vertexCount
        val bz = 0.012f
        val g = 0.55f
        plate.vertex(-w / 2, plateY - h / 2, bz, 0f, 0f, 1f, rect.u(1f), rect.v(1f), g, g, g)
        plate.vertex(w / 2, plateY - h / 2, bz, 0f, 0f, 1f, rect.u(0f), rect.v(1f), g, g, g)
        plate.vertex(w / 2, plateY + h / 2, bz, 0f, 0f, 1f, rect.u(0f), rect.v(0f), g, g, g)
        plate.vertex(-w / 2, plateY + h / 2, bz, 0f, 0f, 1f, rect.u(1f), rect.v(0f), g, g, g)
        plate.quad(b, b + 1, b + 2, b + 3)
        return proto(id, plateY + h, listOf(Mats.metal to metal, Mats.signs to plate) to 160f)
    }

    /** Modern town street lamp: tapered column, arm over the road (-z), flat luminaire. */
    fun lampTown(): Prototype {
        val mb = MeshBuilder(256, 512)
        Shapes.prism(mb, 0f, 0f, 0f, 7.2f, 0.09f, 0.05f, 8, grey)
        Shapes.prism(mb, 0f, 0f, 0f, 0.6f, 0.13f, 0.11f, 8, darkGrey)
        // arm: a thin box sloping up towards the road
        val arm = MeshBuilder(32, 64)
        Shapes.box(arm, -0.03f, 6.9f, -1.6f, 0.03f, 6.98f, 0f, grey)
        mb.append(arm)
        // luminaire
        Shapes.box(mb, -0.17f, 6.82f, -1.95f, 0.17f, 6.98f, -1.3f, darkGrey, topColour = grey)
        val glow = floatArrayOf(1.6f, 1.45f, 1.15f)
        val f = mb.vertexCount
        mb.vertex(-0.14f, 6.815f, -1.9f, 0f, -1f, 0f, 0f, 0f, glow[0], glow[1], glow[2])
        mb.vertex(0.14f, 6.815f, -1.9f, 0f, -1f, 0f, 0f, 0f, glow[0], glow[1], glow[2])
        mb.vertex(0.14f, 6.815f, -1.35f, 0f, -1f, 0f, 0f, 0f, glow[0], glow[1], glow[2])
        mb.vertex(-0.14f, 6.815f, -1.35f, 0f, -1f, 0f, 0f, 0f, glow[0], glow[1], glow[2])
        mb.quad(f, f + 3, f + 2, f + 1)
        val far = MeshBuilder(64, 128)
        Shapes.prism(far, 0f, 0f, 0f, 7.2f, 0.09f, 0.05f, 4, grey)
        Shapes.box(far, -0.17f, 6.82f, -1.95f, 0.17f, 6.98f, 0f, darkGrey)
        return proto("scn_lamp_town", 7.5f, listOf(Mats.metal to mb) to 120f, listOf(Mats.metal to far) to 400f)
    }

    /** Paris / old-town lantern on a cast-iron column. */
    fun lampParis(): Prototype {
        val mb = MeshBuilder(256, 512)
        Shapes.prism(mb, 0f, 0f, 0f, 0.5f, 0.16f, 0.12f, 8, parisGreen)
        Shapes.prism(mb, 0f, 0f, 0.5f, 3.6f, 0.075f, 0.055f, 8, parisGreen)
        Shapes.prism(mb, 0f, 0f, 3.6f, 3.75f, 0.12f, 0.08f, 8, parisGreen)
        // lantern: glass body (glowing) and a cap
        Shapes.prism(mb, 0f, 0f, 3.75f, 4.3f, 0.14f, 0.2f, 6, floatArrayOf(1.3f, 1.2f, 0.95f), top = false)
        Shapes.prism(mb, 0f, 0f, 4.3f, 4.5f, 0.24f, 0.04f, 6, parisGreen)
        val far = MeshBuilder(64, 96)
        Shapes.prism(far, 0f, 0f, 0f, 4.4f, 0.1f, 0.06f, 4, parisGreen)
        Shapes.prism(far, 0f, 0f, 3.75f, 4.3f, 0.14f, 0.2f, 4, floatArrayOf(1.3f, 1.2f, 0.95f), top = false)
        return proto("scn_lamp_paris", 4.6f, listOf(Mats.metal to mb) to 100f, listOf(Mats.metal to far) to 300f)
    }

    /**
     * Kilometre borne: white body with a rounded top and a coloured cap (red on national roads,
     * yellow on departmental roads). The face (towards -z) carries the borne texture.
     */
    fun borne(id: String, cap: FloatArray, face: UvRect): Prototype {
        val mb = MeshBuilder(128, 256)
        val w = 0.25f
        val d = 0.11f
        val h = 0.55f
        Shapes.box(mb, -w, 0f, -d, w, h, d, white)
        // rounded cap: half cylinder along z
        val sides = 8
        val first = mb.vertexCount
        for (k in 0..sides) {
            val a = (k.toFloat() / sides) * PI.toFloat()
            val x = cos(a) * w
            val y = h + sin(a) * w * 0.9f
            val n = Vec3(cos(a), sin(a), 0f)
            mb.vertex(x, y, -d, n.x, n.y, 0f, 0f, 0f, cap[0], cap[1], cap[2])
            mb.vertex(x, y, d, n.x, n.y, 0f, 0f, 0f, cap[0], cap[1], cap[2])
        }
        for (k in 0 until sides) {
            val a = first + k * 2
            // Counter-clockwise from outside: the angle runs from +x over the top to -x.
            mb.quad(a, a + 2, a + 3, a + 1)
        }
        for (z in listOf(-d, d)) {
            val c = mb.vertex(0f, h, z, 0f, 0f, if (z < 0) -1f else 1f, 0f, 0f, cap[0], cap[1], cap[2])
            val f = mb.vertexCount
            for (k in 0..sides) {
                val a = (k.toFloat() / sides) * PI.toFloat()
                mb.vertex(cos(a) * w, h + sin(a) * w * 0.9f, z, 0f, 0f, if (z < 0) -1f else 1f, 0f, 0f, cap[0], cap[1], cap[2])
            }
            for (k in 0 until sides) if (z < 0) mb.tri(c, f + k + 1, f + k) else mb.tri(c, f + k, f + k + 1)
        }
        // printed face
        val p = MeshBuilder(4, 6)
        val fz = -d - 0.004f
        val f = p.vertexCount
        p.vertex(w * 0.9f, 0.08f, fz, 0f, 0f, -1f, face.u(0f), face.v(1f), 1f, 1f, 1f)
        p.vertex(-w * 0.9f, 0.08f, fz, 0f, 0f, -1f, face.u(1f), face.v(1f), 1f, 1f, 1f)
        p.vertex(-w * 0.9f, h - 0.02f, fz, 0f, 0f, -1f, face.u(1f), face.v(0f), 1f, 1f, 1f)
        p.vertex(w * 0.9f, h - 0.02f, fz, 0f, 0f, -1f, face.u(0f), face.v(0f), 1f, 1f, 1f)
        p.quad(f, f + 1, f + 2, f + 3)
        return proto(id, 0.9f, listOf(Mats.plain to mb, Mats.signs to p) to 140f)
    }

    /**
     * J6 delineator: white post with a black band and a reflector, orange on the right side of the
     * road, white on the left ([rightSide]).
     */
    fun delineator(id: String, rightSide: Boolean): Prototype {
        val mb = MeshBuilder(64, 128)
        val h = 1.05f
        Shapes.prism(mb, 0f, 0f, 0f, h * 0.72f, 0.06f, 0.05f, 5, white)
        Shapes.prism(mb, 0f, 0f, h * 0.72f, h * 0.9f, 0.05f, 0.048f, 5, black, top = false)
        Shapes.prism(mb, 0f, 0f, h * 0.9f, h, 0.048f, 0.04f, 5, white)
        val refl = if (rightSide) floatArrayOf(orange[0] * 1.3f, orange[1] * 1.3f, orange[2] * 1.3f) else floatArrayOf(1.2f, 1.2f, 1.2f)
        Shapes.box(mb, -0.025f, h * 0.75f, -0.062f, 0.025f, h * 0.87f, -0.045f, refl)
        val far = MeshBuilder(16, 24)
        Shapes.box(far, -0.05f, 0f, -0.05f, 0.05f, h, 0.05f, white)
        return proto(id, 1.1f, listOf(Mats.plain to mb) to 70f, listOf(Mats.plain to far) to 220f)
    }

    /** Snow pole: red and white bands every 25 cm. */
    fun snowPole(): Prototype {
        val mb = MeshBuilder(128, 256)
        val h = 2.6f
        val n = (h / 0.25f).toInt()
        for (k in 0 until n) {
            val c = if (k % 2 == 0 && k >= n - 4) red else if (k % 2 == 0) red else white
            Shapes.prism(mb, 0f, 0f, k * 0.25f, (k + 1) * 0.25f, 0.025f, 0.025f, 4, if (k < 2) white else c, top = k == n - 1)
        }
        val far = MeshBuilder(16, 24)
        Shapes.prism(far, 0f, 0f, 0f, h, 0.035f, 0.035f, 3, mix(red, white, 0.5f))
        return proto("scn_snowpole", 2.7f, listOf(Mats.plain to mb) to 90f, listOf(Mats.plain to far) to 350f)
    }

    /** A 3 m dry-stone wall segment along x (field boundaries, pastures). */
    fun stoneWall(): Prototype {
        val mb = MeshBuilder(64, 96)
        val l = 1.55f
        val h = 0.95f
        val b = 0.3f
        val t = 0.22f
        val rect = SignAtlas.WALL
        val tile = 1.5f
        // two battered faces and a rounded top
        fun quad(a: Vec3, bb: Vec3, c: Vec3, d: Vec3, n: Vec3, ua: Float, ub: Float, va: Float, vb: Float, shadeBottom: Float) {
            val f = mb.vertexCount
            mb.vertex(a, n, rect.u(ua), rect.v(vb), floatArrayOf(shadeBottom, shadeBottom, shadeBottom))
            mb.vertex(bb, n, rect.u(ub), rect.v(vb), floatArrayOf(shadeBottom, shadeBottom, shadeBottom))
            mb.vertex(c, n, rect.u(ub), rect.v(va), floatArrayOf(1f, 1f, 1f))
            mb.vertex(d, n, rect.u(ua), rect.v(va), floatArrayOf(1f, 1f, 1f))
            mb.quad(f, f + 1, f + 2, f + 3)
        }
        // UVs: the wall cell holds 1.5 m × 1.5 m of stones; a 3.1 m segment uses it twice side by side.
        for (half in listOf(-1f, 1f)) {
            val x0 = if (half < 0) -l else 0f
            val x1 = if (half < 0) 0f else l
            quad(Vec3(x1, 0f, b), Vec3(x0, 0f, b), Vec3(x0, h, t), Vec3(x1, h, t), Vec3(0f, 0.2f, 1f).norm(), 0f, 1f, 1f - h / tile, 1f, 0.6f)
            quad(Vec3(x0, 0f, -b), Vec3(x1, 0f, -b), Vec3(x1, h, -t), Vec3(x0, h, -t), Vec3(0f, 0.2f, -1f).norm(), 0f, 1f, 1f - h / tile, 1f, 0.6f)
            quad(Vec3(x0, h, t), Vec3(x0, h, -t), Vec3(x1, h, -t), Vec3(x1, h, t), UP, 0f, 1f, 0.6f, 0.9f, 0.95f)
        }
        // end caps
        for (x in listOf(-l, l)) {
            val n = Vec3(if (x < 0) -1f else 1f, 0f, 0f)
            val f = mb.vertexCount
            val zs = if (x < 0) floatArrayOf(-b, b, t, -t) else floatArrayOf(b, -b, -t, t)
            mb.vertex(x, 0f, zs[0], n.x, n.y, n.z, rect.u(0f), rect.v(1f), 0.6f, 0.6f, 0.6f)
            mb.vertex(x, 0f, zs[1], n.x, n.y, n.z, rect.u(0.4f), rect.v(1f), 0.6f, 0.6f, 0.6f)
            mb.vertex(x, h, zs[2], n.x, n.y, n.z, rect.u(0.3f), rect.v(0.4f), 1f, 1f, 1f)
            mb.vertex(x, h, zs[3], n.x, n.y, n.z, rect.u(0.1f), rect.v(0.4f), 1f, 1f, 1f)
            mb.quad(f, f + 1, f + 2, f + 3)
        }
        return proto("scn_wall_stone", 1.8f, listOf(Mats.signs to mb) to 500f)
    }

    /** A 3 m wooden pasture fence (two rails) along x, post at -x. */
    fun fence(): Prototype {
        val mb = MeshBuilder(64, 128)
        val wood = lin(0x7A6248)
        Shapes.box(mb, -1.55f, 0f, -0.06f, -1.43f, 1.15f, 0.06f, mul(wood, 0.9f))
        Shapes.box(mb, -1.5f, 0.45f, -0.03f, 1.5f, 0.57f, 0.03f, wood)
        Shapes.box(mb, -1.5f, 0.9f, -0.03f, 1.5f, 1.02f, 0.03f, wood)
        return proto("scn_fence_wood", 1.8f, listOf(Mats.plain to mb) to 250f)
    }

    fun all(): List<Prototype> = listOf(
        lampTown(), lampParis(),
        // Kilometre bornes: red cap on national roads, yellow on departmental roads; both carry the
        // PR (kilometre) face. The cyclists' col borne face (SOMMET x km, pente y %) stays in the atlas
        // unused: its numbers are printed, and a wrong distance to the summit is worse than none.
        borne("scn_borne_red", red, SignAtlas.BORNE_FACE), borne("scn_borne_yellow", yellow, SignAtlas.BORNE_FACE),
        delineator("scn_delineator_r", true), delineator("scn_delineator_l", false),
        snowPole(), stoneWall(), fence(),
        signPlate("scn_sign_bend_r", SignAtlas.BEND_RIGHT, 0.9f, 0.9f, 1.75f),
        signPlate("scn_sign_bend_l", SignAtlas.BEND_LEFT, 0.9f, 0.9f, 1.75f),
        signPlate("scn_sign_bends_r", SignAtlas.BENDS_RIGHT, 0.9f, 0.9f, 1.75f),
        signPlate("scn_sign_bends_l", SignAtlas.BENDS_LEFT, 0.9f, 0.9f, 1.75f),
        signPlate("scn_sign_50", SignAtlas.SPEED_50, 0.65f, 0.65f, 1.85f),
        signPlate("scn_sign_70", SignAtlas.SPEED_70, 0.65f, 0.65f, 1.85f),
        signPlate("scn_sign_80", SignAtlas.SPEED_80, 0.65f, 0.65f, 1.85f),
        signPlate("scn_sign_30", SignAtlas.SPEED_30, 0.65f, 0.65f, 1.85f),
        signPlate("scn_chevron_r", SignAtlas.CHEVRON_RIGHT, 0.5f, 0.5f, 0.95f, 0.03f),
        signPlate("scn_chevron_l", SignAtlas.CHEVRON_LEFT, 0.5f, 0.5f, 0.95f, 0.03f),
        signPlate("scn_sign_giveway", SignAtlas.GIVE_WAY, 0.9f, 0.9f, 1.6f),
        signPlate("scn_sign_direction", SignAtlas.DIRECTION, 1.3f, 0.65f, 2.0f),
    )
}
