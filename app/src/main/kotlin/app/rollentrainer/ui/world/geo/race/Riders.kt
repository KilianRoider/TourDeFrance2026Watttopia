package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.KitChannel
import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.RiderLod
import app.rollentrainer.ui.world.geo.RiderModelSet
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The rider and bike as rigid parts for instanced drawing: a 1.80 m rider on a 56 cm road frame
 * (700c wheels, 0.68 m over the tyres), hands on the hoods, flat back. Bike-local axes as in the
 * contract: origin on the ground under the bottom bracket, x right (drivetrain side), y up, -z forward.
 *
 * Crank convention (W1 poses the legs with it): crank angle θ is the angle of the RIGHT crank arm from
 * straight up, growing as the rider pedals (the right pedal moves forward first): the right pedal is at
 * BB + 0.1725 · (0, cos θ, −sin θ), the left one at θ + π. legPoses[i] is θ = i · 2π / legPoses.size.
 * Wheels: a positive wheel angle rolls forward, i.e. the wheel mesh is rotated by −wheelRad about +x.
 */
object RiderGeo {
    const val WHEEL_R = 0.34f
    val BB = v3(0, 0.27, 0)
    val REAR_HUB = v3(0, WHEEL_R, 0.405)
    val FRONT_HUB = v3(0, WHEEL_R, -0.585)
    const val CRANK = 0.1725f
    const val THIGH = 0.45f
    const val SHIN = 0.44f
    const val UPPER_ARM = 0.31f
    const val FOREARM = 0.27f

    // Frame (56 cm): seat tube 73.5°, head tube 73°, stack 570, reach 390.
    val SEAT_DIR = v3(0, sin(Math.toRadians(73.5)), cos(Math.toRadians(73.5)))
    val HEAD_DIR = v3(0, sin(Math.toRadians(73.0)), cos(Math.toRadians(73.0)))
    val SEAT_CLUSTER = BB + SEAT_DIR * 0.53f
    val HT_TOP = BB + v3(0, 0.57, -0.39)
    val HT_BOTTOM = HT_TOP - HEAD_DIR * 0.165f
    val STEERER_TOP = HT_TOP + HEAD_DIR * 0.03f
    val BAR_CLAMP = STEERER_TOP + v3(0, 0, -0.11)
    val SADDLE_TOP = BB + SEAT_DIR * 0.745f
    const val BAR_HALF = 0.205f
    /** Where the palms rest on the hoods. */
    val HOOD_GRIP = v3(BAR_HALF, 0.905, -0.575)

    fun pedal(theta: Float, right: Boolean): V3 {
        val a = if (right) theta else theta + PI.toFloat()
        return BB + v3(if (right) 0.112f else -0.112f, CRANK * cos(a), -CRANK * sin(a))
    }

    /**
     * Two-bone inverse kinematics: the joint between [root] and [target] for bone lengths [l1], [l2],
     * bent towards [pole]. The reach is clamped just short of straight so knees never snap.
     */
    fun ik(root: V3, target: V3, l1: Float, l2: Float, pole: V3): V3 {
        val dv = target - root
        val d = dv.length.coerceIn(abs(l1 - l2) + 1e-3f, (l1 + l2) * 0.999f)
        val axis = dv.norm()
        val a = (l1 * l1 - l2 * l2 + d * d) / (2 * d)
        val h = sqrt(max(0f, l1 * l1 - a * a))
        val perp = (pole - axis * (pole dot axis)).norm()
        return root + axis * a + perp * h
    }
}

/** Seated or standing posture of the rider's upper body. */
class Posture(val standing: Boolean) {
    /**
     * Centre between the hip joints, ~9 cm above the saddle seated. With the ankling of the foot this
     * leaves ~33° of knee flexion at the bottom of the stroke and ~107° at the top, like a real fit.
     */
    val hip: V3 = if (standing) v3(0, 1.09, 0.045) else v3(0, 1.07, 0.19)
    /** Torso angle above horizontal: a flat back seated, more upright out of the saddle. */
    private val torsoDeg = if (standing) 47.0 else 38.0
    val spineDir: V3 = v3(0, sin(Math.toRadians(torsoDeg)), -cos(Math.toRadians(torsoDeg)))
    val spineLen = 0.53f
    val waist: V3 = hip + spineDir * 0.02f
    val neck: V3 = waist + spineDir * spineLen
    /** Spine frame: local y along the spine, local +z towards the rider's back. */
    val spine: Affine = Affine.basis(waist, spineDir, V3.X)
    fun shoulder(right: Boolean): V3 = spine.apply(v3(if (right) 0.168 else -0.168, 0.445, 0.0))
    val headCentre: V3 = neck + v3(0, 0.07, -0.1)
    fun hipJoint(right: Boolean): V3 = hip + v3(if (right) 0.088 else -0.088, 0, 0)
}

/** Builds the rider meshes at one level of detail (0 near, 1 mid, 2 far). */
class RiderBuilder(private val lod: Int) {
    private val skin = Col.hex(0xC98E6A)
    private val sock = Col.hex(0xF2F2F2)
    private val shoeWhite = Col.hex(0xEDEDED)
    private val sole = Col.hex(0x1A1A1A)
    private val black = Col.hex(0x141414)
    private val carbon = Col.hex(0x1C1D20)
    private val tyre = Col.hex(0x222222)
    private val silver = Col.hex(0x9DA1A6)
    private val glove = Col.hex(0x1E1E1E)
    private val hair = Col.hex(0x4A3424)
    private val lens = Col.hex(0x0E1418)
    private val vent = Col.hex(0x101010)
    private val numberWhite = Col.hex(0xF4F4F4)

    private val segs = when (lod) { 0 -> 14; 1 -> 7; else -> 4 }
    private val limbSides = when (lod) { 0 -> 9; 1 -> 4; else -> 3 }

    /** Frame, rider torso, arms and head. */
    fun body(p: Posture): MeshBuffer {
        if (lod == 2) return bodyFar(p)
        val m = MeshBuilder(4096)
        frame(m)
        bars(m)
        saddle(m)
        if (lod < 2) drivetrainStatic(m)
        torso(m, p)
        arms(m, p)
        head(m, p)
        return m.build()
    }

    // --- bike --------------------------------------------------------------------------------

    private fun frame(m: MeshBuilder) {
        val g = RiderGeo
        val bike = KitChannel.BIKE
        val s = if (lod == 0) 10 else 5
        // Tube ends meet other tubes; their caps are only worth it up close.
        val caps = lod == 0
        m.withColor(bike) {
            // Aero tubes: flattened sideways (x), deep in the frame plane.
            m.tube(g.BB, g.HT_BOTTOM, 0.026f, 0.028f, s, capA = caps, capB = caps, side = V3.X, flat = 1.5f)
            m.tube(g.BB, g.SEAT_CLUSTER + g.SEAT_DIR * 0.02f, 0.024f, 0.02f, s, capA = caps, capB = caps, side = V3.X, flat = 1.4f)
            m.tube(g.SEAT_CLUSTER, g.HT_TOP - g.HEAD_DIR * 0.02f, 0.02f, 0.022f, s, capA = caps, capB = caps, side = V3.X, flat = 1.2f)
            m.tube(g.HT_BOTTOM - g.HEAD_DIR * 0.01f, g.HT_TOP, 0.027f, 0.024f, s)
            val ss = if (lod == 0) 6 else 3
            for (sx in floatArrayOf(-1f, 1f)) {
                // Chain stays, dropped seat stays, fork blades.
                m.tube(g.BB + v3(sx * 0.035f, 0.0, 0.03), g.REAR_HUB + v3(sx * 0.062f, 0.0, 0.0), 0.014f, 0.01f, ss, capA = caps, capB = caps)
                m.tube(g.SEAT_CLUSTER + v3(sx * 0.018f, -0.06, 0.02), g.REAR_HUB + v3(sx * 0.062f, 0.012, -0.005), 0.011f, 0.009f, ss, capA = caps, capB = caps)
                m.tube(g.HT_BOTTOM + v3(sx * 0.03f, -0.02, -0.005), g.FRONT_HUB + v3(sx * 0.052f, 0.0, 0.0), 0.015f, 0.01f, ss, capA = caps, capB = caps, side = V3.X, flat = 1.6f)
            }
            // Fork crown.
            m.box(g.HT_BOTTOM + v3(0, -0.02, -0.005), 0.08f, 0.035f, 0.05f, skipBottom = lod > 0)
        }
        // Seat post, steerer spacers and stem in black carbon.
        m.withColor(carbon) {
            m.tube(g.SEAT_CLUSTER, g.SADDLE_TOP - g.SEAT_DIR * 0.045f, 0.0145f, 0.0145f, if (lod == 0) 8 else 4, capA = false, capB = caps)
            m.tube(g.HT_TOP, g.STEERER_TOP + g.HEAD_DIR * 0.012f, 0.019f, 0.019f, if (lod == 0) 8 else 4, capA = false)
            m.tube(g.STEERER_TOP, g.BAR_CLAMP, 0.018f, 0.016f, if (lod == 0) 8 else 4, capA = false, capB = false, side = V3.Y, flat = 0.8f)
        }
        if (lod == 0) {
            // Bottles in their cages: what makes a bike read as a road bike from the chase camera.
            val bottle = Col.hex(0xE8E8E8)
            val dtDir = (g.HT_BOTTOM - g.BB).norm()
            val dtUp = (V3.X cross dtDir).norm().let { if (it.y < 0) -it else it }
            m.withColor(bottle) { m.tube(g.BB + dtDir * 0.17f + dtUp * 0.045f, g.BB + dtDir * 0.38f + dtUp * 0.045f, 0.035f, 0.035f, 8) }
            val stUp = (V3.X cross g.SEAT_DIR).norm().let { if (it.z > 0) it else -it }
            m.withColor(bottle) { m.tube(g.BB + g.SEAT_DIR * 0.16f - stUp * 0.045f, g.BB + g.SEAT_DIR * 0.37f - stUp * 0.045f, 0.035f, 0.035f, 8) }
            // Disc brake calipers on the left.
            m.withColor(black) {
                m.box(g.REAR_HUB + v3(-0.07, 0.055, -0.045), 0.025f, 0.035f, 0.06f)
                m.box(g.FRONT_HUB + v3(-0.065, 0.06, 0.035), 0.025f, 0.035f, 0.06f)
            }
        }
    }

    private fun bars(m: MeshBuilder) {
        val g = RiderGeo
        val tape = black
        val sides = if (lod == 0) 7 else 4
        m.withColor(carbon) { m.tube(g.BAR_CLAMP + v3(-g.BAR_HALF, 0, 0), g.BAR_CLAMP + v3(g.BAR_HALF, 0, 0), 0.013f, 0.013f, sides, capA = false, capB = false) }
        for (sx in floatArrayOf(-1f, 1f)) {
            // Forward bend to the hoods, then the drop curving back down.
            val pts = listOf(
                v3(g.BAR_HALF, 0.0, 0.0), v3(g.BAR_HALF + 0.004, 0.004, -0.04), v3(g.BAR_HALF + 0.005, 0.0, -0.075),
                v3(g.BAR_HALF + 0.005, -0.03, -0.1), v3(g.BAR_HALF + 0.005, -0.08, -0.098), v3(g.BAR_HALF + 0.005, -0.112, -0.07),
                v3(g.BAR_HALF + 0.005, -0.125, -0.02), v3(g.BAR_HALF + 0.005, -0.122, 0.035),
            ).map { g.BAR_CLAMP + v3(it.x * sx, it.y, it.z) }
            val step = if (lod == 0) 1 else 4
            var i = 0
            m.withColor(tape) {
                while (i < pts.size - 1) {
                    val j = min(i + step, pts.size - 1)
                    m.tube(pts[i], pts[j], 0.0135f, 0.0135f, sides, capA = false, capB = j == pts.size - 1)
                    i = j
                }
            }
            if (lod < 2) {
                // Hood body (brake/shift lever) pointing forward and up, with the lever blade below.
                val hb = g.BAR_CLAMP + v3(sx * (g.BAR_HALF + 0.005f), 0.0, -0.075)
                m.withColor(black) {
                    m.tube(hb + v3(0, -0.005, 0.01), hb + v3(0, 0.03, -0.06), 0.017f, 0.014f, sides, capA = lod == 0, side = V3.X, flat = 1.3f)
                    if (lod == 0) m.tube(hb + v3(0, 0.025, -0.062), hb + v3(0, -0.07, -0.05), 0.006f, 0.005f, 4)
                }
            }
        }
    }

    private fun saddle(m: MeshBuilder) {
        val g = RiderGeo
        val top = g.SADDLE_TOP
        val n = if (lod == 0) 9 else 3
        val rings = ArrayList<LoftRing>()
        // From the tail (behind, +z) to the nose (-z); the loft runs along +y of a frame turned onto -z.
        for (i in 0..n) {
            val t = i.toFloat() / n
            val w = 0.072f * (1 - t * 0.78f) * (if (t < 0.12f) 0.75f + t * 2f else 1f)
            rings += LoftRing(v3(0, t * 0.27f, 0), w, 0.022f)
        }
        val f = Affine.basis(top + v3(0, -0.02, 0.16), v3(0, -0.06, -1), V3.X)
        m.withColor(black) { m.loft(rings, if (lod == 0) 10 else 4, frame = { f }) }
    }

    /** Chainring, cassette, derailleur and chain: static, the cranks belong to the leg poses. */
    private fun drivetrainStatic(m: MeshBuilder) {
        val g = RiderGeo
        if (lod > 0) {
            // A disc for the chainring and one for the cassette.
            m.withColor(Col.hex(0x2A2B2E)) { m.tube(g.BB + v3(0.054, 0, 0), g.BB + v3(0.062, 0, 0), 0.105f, 0.105f, 7) }
            m.withColor(silver) { m.tube(g.REAR_HUB + v3(0.025, 0, 0), g.REAR_HUB + v3(0.055, 0, 0), 0.055f, 0.03f, 5, capA = false) }
            return
        }
        m.withColor(Col.hex(0x2A2B2E)) {
            m.with(Affine.translate(g.BB + v3(0.058, 0, 0))) { m.torusX(V3.ZERO, 0.098f, 0.008f, 24, 4) }
            m.with(Affine.translate(g.BB + v3(0.058, 0, 0))) { m.tube(v3(-0.004, 0, 0), v3(0.004, 0, 0), 0.06f, 0.06f, 10) }
        }
        m.withColor(silver) {
            m.tube(g.REAR_HUB + v3(0.025, 0, 0), g.REAR_HUB + v3(0.055, 0, 0), 0.055f, 0.03f, 12)
            // Chain runs (top and bottom).
            val a1 = g.BB + v3(0.058, 0.104, 0.0); val b1 = g.REAR_HUB + v3(0.045, 0.05, 0.0)
            val a2 = g.BB + v3(0.058, -0.104, 0.0); val b2 = g.REAR_HUB + v3(0.045, -0.08, 0.02)
            m.tube(a1, b1, 0.004f, 0.004f, 3, capA = false, capB = false)
            m.tube(a2, b2, 0.004f, 0.004f, 3, capA = false, capB = false)
        }
        if (lod == 0) m.withColor(black) { m.box(g.REAR_HUB + v3(0.055, -0.075, 0.025), 0.02f, 0.07f, 0.04f) }
    }

    // --- rider -------------------------------------------------------------------------------

    private fun torso(m: MeshBuilder, p: Posture) {
        val J = KitChannel.JERSEY
        val A = KitChannel.ACCENT
        val S = KitChannel.SHORTS
        // Rings along the spine (local y), elliptical sections; +z is the back, flatter than the chest.
        data class R(val y: Float, val rx: Float, val rz: Float, val dz: Float)
        val rings = listOf(
            R(-0.10f, 0.150f, 0.120f, 0.02f), R(-0.02f, 0.165f, 0.125f, 0.0f), R(0.06f, 0.152f, 0.108f, 0f), R(0.16f, 0.160f, 0.112f, 0f),
            R(0.26f, 0.172f, 0.118f, -0.005f), R(0.34f, 0.180f, 0.118f, -0.01f), R(0.41f, 0.180f, 0.108f, -0.012f),
            R(0.465f, 0.150f, 0.088f, -0.01f), R(0.505f, 0.085f, 0.066f, -0.01f), R(0.525f, 0.06f, 0.055f, -0.012f),
        )
        val pick = when (lod) { 0 -> rings.indices.toList(); 1 -> listOf(0, 1, 3, 5, 7, 9); else -> listOf(0, 3, 6, 9) }
        val rr = pick.map { rings[it] }
        val cols = segs + (if (lod == 0) 4 else 0)
        // Bands: shorts (bib) at the bottom, accent hem, jersey, accent shoulder yoke, jersey collar.
        fun bandColor(r: Int, c: Int): FloatArray {
            val y = (rr[r].y + rr[r + 1].y) / 2
            val ang = 2 * PI * (c + 0.5) / cols
            val sideness = abs(cos(ang))
            return when {
                y < -0.02f -> S
                y < 0.03f -> A
                y > 0.43f && y < 0.49f -> A
                // Side panels in the accent colour: visible from behind as the jersey's silhouette edge.
                sideness > 0.93 && y < 0.40f -> A
                else -> J
            }
        }
        m.colorGrid(rr.size, cols, wrap = true,
            point = { r, c ->
                val ring = rr[r]
                val a = (2 * PI * c / cols).toFloat()
                val sz = sin(a)
                val z = if (sz > 0) sz * ring.rz * 0.82f else sz * ring.rz
                p.spine.apply(v3(cos(a) * ring.rx, ring.y, z + ring.dz))
            },
            quadColor = ::bandColor,
            outwardFrom = { r -> p.spine.apply(v3(0f, rr[r].y, 0f)) },
        )
        // Pelvis in the shorts colour, wrapping the hip joints so the thighs can turn inside it.
        m.withColor(S) {
            m.with(p.spine * Affine.translate(0f, -0.06f, 0.01f)) {
                m.ellipsoid(V3.ZERO, 0.165f, 0.09f, 0.11f, if (lod == 0) 6 else 3, if (lod == 0) 12 else 6)
            }
        }
        if (lod == 0) {
            // Race number on the lower back, the first thing the chase camera reads.
            val back = { y: Float, x: Float -> p.spine.apply(v3(x, y, 0.096f + 0.004f)) }
            m.withColor(numberWhite) {
                m.quadUv(back(0.04f, -0.07f), back(0.04f, 0.07f), back(0.16f, 0.07f), back(0.16f, -0.07f), 0f, 0f, 1f, 1f,
                    n = (p.spine.applyDir(V3.Z)).norm())
            }
            m.withColor(black) {
                for ((x0, x1) in listOf(-0.045f to -0.02f, -0.008f to 0.017f, 0.029f to 0.054f)) {
                    m.quadUv(p.spine.apply(v3(x0, 0.07f, 0.1015f)), p.spine.apply(v3(x1, 0.07f, 0.1015f)), p.spine.apply(v3(x1, 0.13f, 0.1015f)), p.spine.apply(v3(x0, 0.13f, 0.1015f)), 0f, 0f, 1f, 1f, n = p.spine.applyDir(V3.Z).norm())
                }
            }
        }
    }

    private fun arms(m: MeshBuilder, p: Posture) {
        val g = RiderGeo
        for (right in listOf(false, true)) {
            val sx = if (right) 1f else -1f
            val sh = p.shoulder(right)
            val grip = v3(sx * g.HOOD_GRIP.x, g.HOOD_GRIP.y, g.HOOD_GRIP.z)
            val wrist = grip + v3(sx * -0.005f, 0.02, 0.05)
            // Elbows out and down, as riders hold them on the hoods.
            val elbow = RiderGeo.ik(sh, wrist, g.UPPER_ARM, g.FOREARM, v3(sx * 0.7f, -0.6f, 0.35f))
            val sleeveEnd = sh.lerp(elbow, 0.55f)
            val cuffEnd = sh.lerp(elbow, 0.66f)
            // The accent cuff is a pixel wide beyond the near LOD: there the sleeve runs to the cuff's end.
            m.withColor(KitChannel.JERSEY) { m.tube(sh + (sh - elbow).norm() * 0.015f, if (lod == 0) sleeveEnd else cuffEnd, 0.053f, if (lod == 0) 0.048f else 0.046f, limbSides, capA = true, capB = false) }
            if (lod == 0) m.withColor(KitChannel.ACCENT) { m.tube(sleeveEnd, cuffEnd, 0.048f, 0.046f, limbSides, capA = false, capB = true) }
            m.withColor(skin) {
                m.tube(cuffEnd, elbow, 0.042f, 0.038f, limbSides, capA = false, capB = false)
                if (lod == 0) m.sphere(elbow, 0.038f, 4, 7)
                m.tube(elbow, wrist, 0.038f, 0.028f, limbSides, capA = false, capB = lod == 0)
            }
            // Hand (gloved) wrapped over the hood.
            m.withColor(glove) {
                if (lod == 0) {
                    m.with(Affine.basis(wrist, grip - wrist, V3.X)) { m.ellipsoid(v3(0, 0.045, 0.0), 0.03f, 0.055f, 0.022f, 4, 7) }
                    m.tube(grip + v3(sx * 0.02f, 0.01, -0.01), grip + v3(sx * -0.022f, -0.025, -0.03), 0.012f, 0.011f, 5)
                } else {
                    m.tube(wrist, grip + v3(0, -0.01, -0.02), 0.03f, 0.025f, limbSides)
                }
            }
        }
    }

    private fun head(m: MeshBuilder, p: Posture) {
        val hc = p.headCentre
        val pitch = Affine.translate(hc) * Affine.rotX(0.28f)
        m.withColor(skin) {
            m.tube(p.neck - p.spineDir * 0.02f, hc + v3(0, -0.05, 0.03), 0.052f, 0.048f, limbSides, capA = false, capB = false)
            m.with(pitch) {
                m.ellipsoid(V3.ZERO, 0.074f, 0.1f, 0.094f, if (lod == 0) 7 else 3, if (lod == 0) 12 else 6)
                if (lod == 0) {
                    m.ellipsoid(v3(0.074, -0.005, 0.01), 0.012f, 0.028f, 0.02f, 3, 5)
                    m.ellipsoid(v3(-0.074, -0.005, 0.01), 0.012f, 0.028f, 0.02f, 3, 5)
                }
            }
        }
        if (lod == 0) m.withColor(hair) { m.with(pitch) { m.ellipsoid(v3(0, -0.035, 0.05), 0.066f, 0.05f, 0.06f, 4, 8, yMin = -0.6f, yMax = 0.6f) } }
        // Aero road helmet: a long shell with ribs; vents are dark grooves between the ribs.
        m.with(pitch * Affine.translate(0f, 0.035f, 0.018f)) {
            val rows = if (lod == 0) 9 else 4
            val cols = if (lod == 0) 22 else 6
            m.colorGrid(rows, cols, wrap = true,
                point = { r, c ->
                    // Latitude from the rim (r = 0) to the crown; longitude around y.
                    val lat = -0.32f + (PI.toFloat() / 2 + 0.32f) * r / (rows - 1)
                    val lon = 2 * PI.toFloat() * c / cols
                    val ventCol = lod < 2 && r in 2 until rows - 1 && c % 2 == 1 && sin(lon) < 0.7f
                    val k = if (ventCol) 0.94f else 1f
                    val tail = 1f + 0.18f * max(0f, sin(lon)) * (1 - sin(lat))
                    v3(cos(lon) * cos(lat) * 0.103f * k, sin(lat) * 0.088f * k - 0.002f, sin(lon) * cos(lat) * 0.138f * k * tail)
                },
                quadColor = { r, c -> if (lod < 2 && r in 2 until rows - 1 && c % 2 == 1 && sin(2 * PI * (c + 0.5) / cols) < 0.7) vent else KitChannel.HELMET },
                outwardFrom = { _ -> V3.ZERO },
            )
            if (lod == 0) {
                // Chin straps.
                m.withColor(black) {
                    m.tube(v3(0.095, -0.03, -0.01), v3(0.045, -0.13, -0.06), 0.004f, 0.004f, 3)
                    m.tube(v3(-0.095, -0.03, -0.01), v3(-0.045, -0.13, -0.06), 0.004f, 0.004f, 3)
                }
            }
        }
        // Wrap-around sunglasses in front of the eyes.
        if (lod < 2) {
            m.with(pitch) {
                val n = if (lod == 0) 9 else 4
                val start = m.vertexCount
                for (i in 0..n) {
                    val a = (-PI / 2 - 1.15 + 2.3 * i / n).toFloat()
                    val px = cos(a) * 0.086f; val pz = sin(a) * 0.1f
                    val nn = v3(cos(a), 0, sin(a))
                    m.vertex(v3(px, 0.012, pz), nn, c = lens)
                    m.vertex(v3(px, 0.05, pz * 0.98f), nn, c = lens)
                }
                for (i in 0 until n) {
                    val a = start + i * 2
                    m.quad(a, a + 1, a + 3, a + 2)
                }
                if (lod == 0) m.withColor(black) {
                    m.tube(v3(0.084, 0.04, -0.03), v3(0.08, 0.035, 0.05), 0.004f, 0.004f, 3)
                    m.tube(v3(-0.084, 0.04, -0.03), v3(-0.08, 0.035, 0.05), 0.004f, 0.004f, 3)
                }
            }
        }
    }

    // --- legs per crank angle ----------------------------------------------------------------

    fun legs(p: Posture, theta: Float): MeshBuffer {
        if (lod == 2) return legsFar(p, theta)
        val m = MeshBuilder(1024)
        val g = RiderGeo
        for (right in listOf(false, true)) {
            val sx = if (right) 1f else -1f
            val ped = g.pedal(theta, right)
            val a = if (right) theta else theta + PI.toFloat()
            // Ankling: heel drops over the top, toes point down through the bottom of the stroke.
            val toeDown = Math.toRadians(14.0 - 9.0 * cos(a.toDouble())).toFloat() + if (p.standing) 0.08f else 0f
            val fwd = v3(0, -sin(toeDown), -cos(toeDown))
            val up = v3(0, cos(toeDown), -sin(toeDown))
            val ankle = ped + up * 0.088f - fwd * 0.125f + v3(sx * -0.012f, 0, 0)
            val hipJ = p.hipJoint(right)
            val knee = g.ik(hipJ, ankle, g.THIGH, g.SHIN, v3(sx * 0.12f, 0.25f, -1f))
            val shortsEnd = hipJ.lerp(knee, 0.8f)
            val bandEnd = hipJ.lerp(knee, 0.86f)
            m.withColor(KitChannel.SHORTS) { m.tube(hipJ + (hipJ - knee).norm() * 0.05f, shortsEnd, 0.088f, 0.064f, limbSides + 1, capA = true, capB = false, rings = if (lod == 0) 2 else 1) }
            m.withColor(KitChannel.ACCENT) { m.tube(shortsEnd, bandEnd, 0.064f, 0.06f, limbSides + 1, capA = false, capB = false) }
            m.withColor(skin) {
                m.tube(bandEnd, knee, 0.058f, 0.05f, limbSides, capA = false, capB = false)
                if (lod == 0) m.sphere(knee, 0.05f, 3, limbSides)
                // Calf bulge: wider a third of the way down the shin.
                val calf = knee.lerp(ankle, 0.33f) + (knee - ankle).cross(V3.X).norm().let { if (it.z > 0) it else -it } * 0.012f
                val sockTop = knee.lerp(ankle, 0.66f)
                val c0 = lod == 0
                m.tube(knee, calf, 0.048f, 0.056f, limbSides, capA = false, capB = c0)
                m.tube(calf, sockTop, 0.056f, 0.04f, limbSides, capA = c0, capB = false)
                m.withColor(sock) { m.tube(sockTop, ankle, 0.04f, 0.034f, limbSides, capA = c0, capB = c0) }
            }
            shoe(m, ped, fwd, up, sx)
            // Crank arm from the bottom bracket to the pedal, outside the chainstays, and the pedal body.
            m.withColor(if (right) Col.hex(0x2A2B2E) else Col.hex(0x2A2B2E)) {
                m.tube(g.BB + v3(sx * 0.072f, 0, 0), ped + v3(sx * -0.03f, 0, 0), 0.016f, 0.012f, if (lod == 0) 6 else 4, side = V3.X, flat = 0.6f)
                if (lod == 0) m.box(ped + v3(sx * 0.0f, -0.004, 0), 0.07f, 0.014f, 0.075f)
            }
        }
        if (lod < 2) m.withColor(Col.hex(0x2A2B2E)) { m.tube(g.BB + v3(-0.075, 0, 0), g.BB + v3(0.075, 0, 0), 0.014f, 0.014f, 6) }
        return m.build()
    }

    private fun shoe(m: MeshBuilder, ped: V3, fwd: V3, up: V3, sx: Float) {
        // Shoe frame: origin at the cleat on the pedal, +y = up of the foot, -z = toe direction.
        val f = Affine(floatArrayOf(1f, up.x, -fwd.x, ped.x, 0f, up.y, -fwd.y, ped.y + 0.008f, 0f, up.z, -fwd.z, ped.z))
        m.with(f) {
            if (lod == 0) {
                m.withColor(sole) { m.boxMinMax(v3(-0.042, 0.0, -0.1), v3(0.042, 0.016, 0.175)) }
                val rings = listOf(
                    LoftRing(v3(0, 0.0, 0), 0.038f, 0.05f), LoftRing(v3(0, 0.05, 0), 0.043f, 0.06f), LoftRing(v3(0, 0.14, 0), 0.04f, 0.055f),
                    LoftRing(v3(0, 0.235, 0), 0.03f, 0.033f), LoftRing(v3(0, 0.27, 0), 0.01f, 0.01f),
                )
                // Loft from heel (+z) to toe (-z): the frame turns local +y onto -z and lifts it to the upper.
                val lf = Affine.translate(0f, 0.045f, 0.165f) * Affine.rotX(-PI.toFloat() / 2)
                m.with(lf) { m.withColor(shoeWhite) { m.loft(rings, 8) } }
                // Ankle collar.
                m.withColor(shoeWhite) { m.tube(v3(0, 0.05, 0.13), v3(0, 0.1, 0.125), 0.04f, 0.036f, 6, capB = false) }
            } else {
                m.withColor(shoeWhite) { m.boxMinMax(v3(-0.04, 0.01, -0.1), v3(0.04, 0.08, 0.17)) }
            }
        }
    }

    // --- far LOD: a few hundred triangles that keep the silhouette and the team colours -----------

    private fun bodyFar(p: Posture): MeshBuffer {
        val m = MeshBuilder(256)
        val g = RiderGeo
        m.withColor(KitChannel.BIKE) {
            for ((a, b) in listOf(g.BB to g.HT_BOTTOM, g.BB to g.SEAT_CLUSTER, g.SEAT_CLUSTER to g.HT_TOP, g.BB to g.REAR_HUB, g.SEAT_CLUSTER to g.REAR_HUB, g.HT_BOTTOM to g.FRONT_HUB)) {
                m.tube(a, b, 0.022f, 0.02f, 3, capA = false, capB = false)
            }
        }
        m.withColor(black) {
            m.tube(g.BAR_CLAMP + v3(-g.BAR_HALF, 0, 0), g.BAR_CLAMP + v3(g.BAR_HALF, 0, 0), 0.016f, 0.016f, 3, capA = false, capB = false)
            m.tube(g.HT_TOP, g.BAR_CLAMP, 0.018f, 0.018f, 3, capA = false, capB = false)
            m.box(g.SADDLE_TOP + v3(0, -0.015, 0.06), 0.1f, 0.03f, 0.25f, skipBottom = true)
        }
        // Torso + pelvis in one coarse lofted grid with the shorts, jersey and accent bands.
        val ys = floatArrayOf(-0.12f, 0.02f, 0.24f, 0.44f, 0.53f)
        val rx = floatArrayOf(0.16f, 0.16f, 0.175f, 0.17f, 0.06f)
        val rz = floatArrayOf(0.12f, 0.11f, 0.12f, 0.1f, 0.05f)
        m.colorGrid(ys.size, 4, wrap = true,
            point = { r, c -> val a = (2 * PI * c / 4 + PI / 4).toFloat(); p.spine.apply(v3(cos(a) * rx[r] * 1.25f, ys[r], sin(a) * rz[r] * 1.25f)) },
            quadColor = { r, _ -> when (r) { 0 -> KitChannel.SHORTS; 2 -> KitChannel.ACCENT; else -> KitChannel.JERSEY } },
            outwardFrom = { r -> p.spine.apply(v3(0f, ys[r], 0f)) })
        for (right in listOf(false, true)) {
            val sx = if (right) 1f else -1f
            val grip = v3(sx * g.HOOD_GRIP.x, g.HOOD_GRIP.y, g.HOOD_GRIP.z)
            m.withColor(skin) { m.tube(p.shoulder(right), grip, 0.045f, 0.03f, 3, capA = false, capB = false) }
        }
        // Head and helmet: one ellipsoid, helmet colour on the upper rows.
        val hc = p.headCentre
        m.colorGrid(4, 5, wrap = true,
            point = { r, c ->
                val lat = (-PI / 2 + PI * r / 3).toFloat(); val lon = (2 * PI * c / 5).toFloat()
                hc + v3(cos(lon) * cos(lat) * 0.1f, sin(lat) * 0.12f, sin(lon) * cos(lat) * 0.13f)
            },
            quadColor = { r, _ -> if (r >= 2) KitChannel.HELMET else skin },
            outwardFrom = { _ -> hc })
        return m.build()
    }

    private fun legsFar(p: Posture, theta: Float): MeshBuffer {
        val m = MeshBuilder(128)
        val g = RiderGeo
        for (right in listOf(false, true)) {
            val sx = if (right) 1f else -1f
            val ped = g.pedal(theta, right)
            val ankle = ped + v3(sx * -0.012f, 0.12, 0.07)
            val hipJ = p.hipJoint(right)
            val knee = g.ik(hipJ, ankle, g.THIGH, g.SHIN, v3(sx * 0.12f, 0.25f, -1f))
            m.withColor(KitChannel.SHORTS) { m.tube(hipJ, knee, 0.08f, 0.06f, 3, capA = false, capB = false) }
            m.withColor(skin) { m.tube(knee, ankle, 0.055f, 0.04f, 3, capA = false, capB = false) }
            m.withColor(shoeWhite) { m.tube(ankle, ped + v3(0, 0.01, -0.08), 0.045f, 0.035f, 3, capA = false, capB = false) }
        }
        return m.build()
    }

    // --- wheel --------------------------------------------------------------------------------

    /** One wheel centred on its hub, axle along x: tyre, deep carbon rim, spokes, hub, disc rotor. */
    fun wheel(): MeshBuffer {
        val m = MeshBuilder(1024)
        if (lod == 2) {
            // A dark ring (tyre + deep rim) seen from both sides.
            lathe(m, 0f, 0.34f, 0f, 0.262f, 8, tyre, outward = 1f)
            lathe(m, 0f, 0.262f, 0f, 0.34f, 8, tyre, outward = -1f)
            return m.build()
        }
        // LOD 1 is seen from 28 m on: 10 segments keep the tyre round to a few pixels (budget < 1000 tris).
        val tSegs = if (lod == 0) 32 else 10
        m.withColor(tyre) { m.torusX(V3.ZERO, 0.3255f, 0.0135f, tSegs, if (lod == 0) 6 else 3) }
        // Rim as a lathe: bead (r 0.312) down the flanks to the spoke bed (r 0.262).
        val prof = if (lod == 0) listOf(0.0f to 0.312f, 0.0125f to 0.311f, 0.0115f to 0.29f, 0.0085f to 0.268f, 0.0f to 0.261f) else listOf(0.012f to 0.311f, 0.0f to 0.262f)
        for (sx in floatArrayOf(-1f, 1f)) {
            for (k in 0 until prof.size - 1) {
                val (x0, r0) = prof[k]
                val (x1, r1) = prof[k + 1]
                // A light band on the flank stands in for rim decals and shows the wheel turning.
                val c = if (lod == 0 && k == 1) Col.hex(0x5A5D63) else carbon
                lathe(m, sx * x0, r0, sx * x1, r1, tSegs, c, outward = sx)
            }
        }
        m.withColor(Col.hex(0x303236)) { m.tube(v3(-0.05, 0, 0), v3(0.05, 0, 0), 0.018f, 0.018f, if (lod == 0) 8 else 4, capA = lod == 0, capB = lod == 0) }
        if (lod == 0) {
            m.withColor(Col.hex(0x2A2A2A)) {
                for (i in 0 until 24) {
                    val a = 2 * PI.toFloat() * i / 24
                    val sxx = if (i % 2 == 0) 1f else -1f
                    val hub = v3(sxx * 0.03f, cos(a) * 0.026f, sin(a) * 0.026f)
                    // Tangential lacing: offset the rim end so spokes cross like real two-cross wheels.
                    val ra = a + sxx * 0.18f
                    val rim = v3(sxx * 0.004f, cos(ra) * 0.262f, sin(ra) * 0.262f)
                    m.tube(hub, rim, 0.0022f, 0.0022f, 3, capA = false, capB = false)
                }
            }
            m.withColor(silver) {
                // Disc rotor on the left.
                m.with(Affine.translate(-0.056f, 0f, 0f)) { lathe(m, 0f, 0.08f, 0f, 0.058f, 20, silver, outward = -1f); lathe(m, 0f, 0.058f, 0f, 0.08f, 20, silver, outward = 1f) }
            }
        } else if (lod == 1) {
            // Spokes blur at this distance: a faint dark disc reads better than aliasing lines.
            m.withColor(Col.hex(0x3A3A3A)) { lathe(m, 0.0f, 0.26f, 0.0f, 0.03f, 6, Col.hex(0x3A3A3A), outward = 1f); lathe(m, 0.0f, 0.03f, 0.0f, 0.26f, 6, Col.hex(0x3A3A3A), outward = -1f) }
        }
        return m.build()
    }

    /** Surface of revolution about x between (x0, r0) and (x1, r1); [outward] picks the facing side. */
    private fun lathe(m: MeshBuilder, x0: Float, r0: Float, x1: Float, r1: Float, segs: Int, c: FloatArray, outward: Float) {
        val start = m.vertexCount
        val dx = x1 - x0; val dr = r1 - r0
        for (i in 0..segs) {
            val a = 2 * PI.toFloat() * i / segs
            val ca = cos(a); val sa = sin(a)
            // Profile normal (perpendicular to the profile segment), oriented by [outward] on x.
            var nx = dr; var nr = -dx
            if (nx * outward < 0 || (abs(nx) < 1e-6f && nr < 0)) { nx = -nx; nr = -nr }
            val n = v3(nx, nr * ca, nr * sa).norm()
            m.vertex(v3(x0, r0 * ca, r0 * sa), n, c = c)
            m.vertex(v3(x1, r1 * ca, r1 * sa), n, c = c)
        }
        for (i in 0 until segs) {
            val a = start + i * 2
            // Orient each quad by its normal: compute and flip if needed.
            val p0 = v3(x0, r0, 0f); val p1 = v3(x1, r1, 0f)
            val q0 = v3(x0, r0 * cos(2 * PI.toFloat() / segs), r0 * sin(2 * PI.toFloat() / segs))
            val fn = (p1 - p0) cross (q0 - p0)
            var nx = dr; var nr = -dx
            if (nx * outward < 0 || (abs(nx) < 1e-6f && nr < 0)) { nx = -nx; nr = -nr }
            val want = v3(nx, nr, 0f)
            if ((fn dot want) > 0) m.quad(a, a + 1, a + 3, a + 2) else m.quad(a, a + 2, a + 3, a + 1)
        }
    }
}

/** The rider model sets the renderer instances (see [RiderGeo] for the conventions). */
object Riders {
    /** One material for the whole rider so a rider part is one draw call; kit colours come from [KitChannel]. */
    val MATERIAL = SceneMaterial("race_rider", roughness = 0.55f)
    val WHEEL_MATERIAL = SceneMaterial("race_rider_wheel", roughness = 0.45f)

    /** Leg poses per LOD: fewer crank steps where a leg is a few pixels tall. */
    val LEG_POSES = intArrayOf(12, 8, 4)
    val LOD_DISTANCE = floatArrayOf(28f, 110f, 900f)

    private fun set(standing: Boolean): RiderModelSet {
        val p = Posture(standing)
        val lods = (0..2).map { l ->
            val b = RiderBuilder(l)
            val n = LEG_POSES[l]
            RiderLod(
                maxDistanceM = LOD_DISTANCE[l],
                body = listOf(MATERIAL to b.body(p)),
                wheel = listOf(WHEEL_MATERIAL to b.wheel()),
                frontHub = floatArrayOf(RiderGeo.FRONT_HUB.x, RiderGeo.FRONT_HUB.y, RiderGeo.FRONT_HUB.z),
                rearHub = floatArrayOf(RiderGeo.REAR_HUB.x, RiderGeo.REAR_HUB.y, RiderGeo.REAR_HUB.z),
                legPoses = (0 until n).map { i -> listOf(MATERIAL to b.legs(p, (2 * PI * i / n).toFloat())) },
                bodyStanding = null,
            )
        }
        return RiderModelSet(lods)
    }

    /** Seated riders (the contract's model set). bodyStanding stays null: standing needs its own legs. */
    val seated: RiderModelSet by lazy { set(false) }

    /**
     * Out of the saddle: hips forward over the bottom bracket and higher, so the legs differ from the
     * seated ones too. Until the contract has standing leg poses, the renderer swaps the whole set for
     * riders with standing > 0.5.
     */
    val standing: RiderModelSet by lazy { set(true) }
}
