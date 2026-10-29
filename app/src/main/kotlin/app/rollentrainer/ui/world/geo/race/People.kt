package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.Prototype
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Spectators. Each pose is TWO prototypes placed with identical instance data:
 *  - "<pose>_body": head, hair, arms, legs, shoes. Its instance tint is a skin tone factor (it also
 *    darkens hair and trousers, which only adds variety);
 *  - "<pose>_top": shirt and sleeves in white vertex colours, tinted with the clothing colour.
 * The contract has one tint per instance, multiplying everything; splitting is how shirts get their
 * own colour without orange faces. Both stop at the mid LOD (120 m); beyond it every spectator is
 * drawn by one of two shared silhouettes ([People.FAR], [People.FAR_UP], placed with each person, in
 * the shirt colour: a person is a few pixels there), so the far crowd costs two draws.
 *
 * Sunglasses, caps and hats are separate prototypes ([Accessory]) placed on the head per spectator,
 * so they vary per person (and caps get their own colour) instead of every fan of a pose wearing them.
 *
 * Local axes: feet on the ground at y = 0, facing -z (towards the road when placed), x = right.
 * Vtx.EXTRA = arm weight for CHEER: 0 at the shoulder, rising to the pose's cheer amount at the hand.
 * InstanceSet variant for people = energy (0 calm, 1 cheering, 2 jumping party crowd).
 */
enum class Legwear { JEANS, SHORTS_KHAKI, SHORTS_DENIM, LEGGINGS, TROUSERS_DARK, SHORTS_WHITE }

class BodyType(val shoulder: Float, val hipW: Float, val chestRx: Float, val chestRz: Float, val belly: Float, val female: Boolean)

/** A spectator pose: targets for hands and feet (person-local), solved with two-bone IK. */
class PersonPose(
    val id: String,
    val body: BodyType,
    val handL: V3, val handR: V3,
    val elbowL: V3, val elbowR: V3,
    val footL: V3, val footR: V3,
    val kneeHint: V3 = v3(0, 0.1, -1),
    val hipY: Float = 0.93f,
    val hipZ: Float = 0f,
    /** Forward lean of the spine, radians. */
    val lean: Float = 0f,
    val cheer: Float = 0f,
    val legwear: Legwear,
    val hair: Int,
    val longHair: Boolean = false,
    val phone: Boolean = false,
    /** Thighs nearly horizontal (sitting): the shorts loft follows the thigh. */
    val seated: Boolean = false,
)

object People {
    private val MALE = BodyType(0.205f, 0.095f, 0.165f, 0.115f, 0.0f, false)
    private val MALE_HEAVY = BodyType(0.215f, 0.105f, 0.185f, 0.13f, 0.04f, false)
    private val FEMALE = BodyType(0.18f, 0.105f, 0.15f, 0.11f, 0.0f, true)

    const val SKIN = 0xE8B796
    val POSES: List<PersonPose> = listOf(
        PersonPose("fan_stand", MALE, v3(-0.26, 0.86, 0.03), v3(0.26, 0.86, 0.03), v3(-0.6, 0, 0.6), v3(0.6, 0, 0.6),
            v3(-0.11, 0, 0.0), v3(0.12, 0, 0.02), legwear = Legwear.JEANS, hair = 0x3B2A1E, cheer = 0.15f),
        PersonPose("fan_hips", FEMALE, v3(-0.17, 1.0, 0.04), v3(0.17, 1.0, 0.04), v3(-1, -0.2, 0.3), v3(1, -0.2, 0.3),
            v3(-0.09, 0, 0.0), v3(0.13, 0, -0.04), legwear = Legwear.SHORTS_DENIM, hair = 0x8A5A2B, longHair = true, cheer = 0.1f),
        PersonPose("fan_clap", FEMALE, v3(-0.035, 1.28, -0.3), v3(0.035, 1.3, -0.3), v3(-1, -1, 0.4), v3(1, -1, 0.4),
            v3(-0.1, 0, 0.0), v3(0.1, 0, 0.0), legwear = Legwear.LEGGINGS, hair = 0x1E1612, longHair = true, cheer = 1f),
        PersonPose("fan_armsup", MALE_HEAVY, v3(-0.36, 2.02, -0.08), v3(0.36, 2.02, -0.08), v3(-1, -0.3, 0.2), v3(1, -0.3, 0.2),
            v3(-0.13, 0, 0.0), v3(0.13, 0, 0.0), legwear = Legwear.SHORTS_KHAKI, hair = 0x6B5844, cheer = 1f),
        PersonPose("fan_fist", MALE, v3(-0.26, 0.87, 0.05), v3(0.22, 2.08, -0.12), v3(-0.6, 0, 0.6), v3(1, -0.3, 0.3),
            v3(-0.12, 0, 0.0), v3(0.11, 0, 0.03), legwear = Legwear.SHORTS_DENIM, hair = 0x2A1E16, cheer = 1f),
        PersonPose("fan_flag", MALE, v3(0.1, 1.38, -0.17), v3(0.12, 1.78, -0.17), v3(-0.5, -0.8, 0.4), v3(1, -0.6, 0.2),
            v3(-0.12, 0, 0.0), v3(0.12, 0, 0.0), legwear = Legwear.TROUSERS_DARK, hair = 0xC9A66B, cheer = 0f),
        PersonPose("fan_phone", FEMALE, v3(-0.24, 0.88, 0.02), v3(0.06, 1.5, -0.43), v3(-0.6, 0, 0.6), v3(1, -0.8, 0.2),
            v3(-0.1, 0, 0.0), v3(0.11, 0, 0.0), legwear = Legwear.SHORTS_WHITE, hair = 0xD8B27A, longHair = true, phone = true, cheer = 0.25f),
        PersonPose("fan_run", MALE, v3(-0.2, 1.05, 0.25), v3(0.22, 1.82, -0.25), v3(-0.5, -0.5, 0.6), v3(1, -0.4, 0.2),
            v3(-0.1, 0.02, -0.38), v3(0.1, 0.3, 0.32), lean = 0.22f, legwear = Legwear.SHORTS_KHAKI, hair = 0x2A1E16, cheer = 0.6f),
        PersonPose("fan_sit", FEMALE, v3(-0.14, 0.58, -0.3), v3(0.15, 0.6, -0.32), v3(-1, 0, 0.2), v3(1, 0, 0.2),
            v3(-0.12, 0, -0.45), v3(0.13, 0, -0.43), kneeHint = v3(0, 1, -0.3), hipY = 0.47f, hipZ = 0.05f, lean = -0.08f,
            legwear = Legwear.SHORTS_DENIM, hair = 0x4A3424, longHair = true, cheer = 0.3f, seated = true),
    )

    fun pose(id: String) = POSES.first { it.id == id }

    private val heads: Map<String, V3> = POSES.associate { it.id to PersonBuilder(it).headCentre }

    /** Centre of the head of [pose] in person-local coordinates: where the accessories go. */
    fun headOf(pose: String): V3 = heads.getValue(pose)

    /** Height of the seat the sitting pose expects (folding chair, camper roof edge). */
    const val SIT_HEIGHT = 0.44f

    const val LOD_NEAR = 35f
    const val LOD_MID = 120f
    const val LOD_FAR = 600f

    /** Shared far silhouettes (beyond [LOD_MID]): arms down, and arms up for the cheering poses. */
    const val FAR = "fan_far"
    const val FAR_UP = "fan_far_up"

    /**
     * The far prototype for a pose. Beyond 120 m the pose is a few pixels, so all poses share two
     * silhouettes: that keeps the far crowd at two draws instead of one per pose.
     */
    fun farFor(pose: String) = if (pose == "fan_armsup" || pose == "fan_fist") FAR_UP else FAR

    fun prototypes(): List<Prototype> = POSES.flatMap { p ->
        val b = PersonBuilder(p)
        listOf(
            Prototype(p.id + "_body", listOf(ProtoLod(listOf(RaceMaterials.PEOPLE to b.body(0)), LOD_NEAR), ProtoLod(listOf(RaceMaterials.PEOPLE to b.body(1)), LOD_MID)), 1.1f),
            Prototype(p.id + "_top", listOf(ProtoLod(listOf(RaceMaterials.PEOPLE to b.top(0)), LOD_NEAR), ProtoLod(listOf(RaceMaterials.PEOPLE to b.top(1)), LOD_MID)), 1.1f),
        )
    } + listOf(FAR to "fan_stand", FAR_UP to "fan_armsup").map { (id, poseId) ->
        // Empty up to LOD_MID: the pose's own body and top are drawn there.
        Prototype(id, listOf(ProtoLod(emptyList(), LOD_MID), ProtoLod(listOf(RaceMaterials.PEOPLE_FAR to PersonBuilder(pose(poseId)).far()), LOD_FAR)), 1.1f)
    } + Accessory.prototypes()

    /** Clothing tints (sRGB hex): what fans wear on a July afternoon. */
    val SHIRTS = intArrayOf(0xF4F4F4, 0xF4F4F4, 0x1D1D1F, 0x1C2C5A, 0xC81E2A, 0xF2C80F, 0x2E8B3E, 0xE8701A, 0x7FB6E6, 0x8A8A8A, 0xE86AA0, 0x5B3A8C, 0xF0E6D0, 0x0E6E6A)
    /** Skin tone factors for the body part (multiplies a light skin vertex colour). */
    val SKIN_TONES = intArrayOf(0xFFFFFF, 0xFFFFFF, 0xF4EEE8, 0xE6D8CC, 0xD6C0AE, 0xB89A86, 0x8C6E5E, 0x5E4A40)
}

/** Builds one pose's meshes. */
class PersonBuilder(private val p: PersonPose) {
    private val skin = Col.hex(People.SKIN)
    private val hair = Col.hex(p.hair)
    private val shoe = Col.hex(if (p.body.female) 0xF2F2F2 else 0x2B2B2E)
    private val legs = Col.hex(when (p.legwear) {
        Legwear.JEANS -> 0x3A5A86
        Legwear.SHORTS_KHAKI -> 0xB8A47A
        Legwear.SHORTS_DENIM -> 0x5D7FA8
        Legwear.LEGGINGS -> 0x1E1E22
        Legwear.TROUSERS_DARK -> 0x3A3B40
        Legwear.SHORTS_WHITE -> 0xEDEDED
    })
    private val shorts = p.legwear != Legwear.JEANS && p.legwear != Legwear.LEGGINGS && p.legwear != Legwear.TROUSERS_DARK
    /** Clothing of the top part: white so the tint is the colour; slightly darker trim reads as seams. */
    private val cloth = floatArrayOf(0.92f, 0.92f, 0.92f)
    private val trim = floatArrayOf(0.62f, 0.62f, 0.62f)

    private val hip = v3(0f, p.hipY, p.hipZ)
    /** Spine frame: local y up the spine, local +z the back. */
    private val spineDir = v3(0f, cos(p.lean), -sin(p.lean))
    private val spine = Affine.basis(hip, spineDir, V3.X)
    private fun sp(x: Float, y: Float, z: Float) = spine.apply(v3(x, y, z))
    private val neck = sp(0f, 0.53f, 0f)
    private val headC = sp(0f, 0.68f, -0.01f)
    /** Centre of the head (helmets for the moto crew are added on top). */
    val headCentre: V3 get() = headC
    private fun shoulder(right: Boolean) = sp(if (right) p.body.shoulder - 0.025f else -(p.body.shoulder - 0.025f), 0.47f, 0f)
    private fun hipJoint(right: Boolean) = hip + v3(if (right) p.body.hipW else -p.body.hipW, -0.02f, 0f)

    private class Arm(val sh: V3, val elbow: V3, val wrist: V3, val hand: V3)
    private class Leg(val hip: V3, val knee: V3, val ankle: V3, val foot: V3)

    private fun arm(right: Boolean): Arm {
        val sh = shoulder(right)
        val hand = if (right) p.handR else p.handL
        val hint = if (right) p.elbowR else p.elbowL
        // The wrist sits 8 cm before the hand target along the forearm direction.
        val elbow0 = RiderGeo.ik(sh, hand, 0.30f, 0.33f, hint)
        val wrist = elbow0 + (hand - elbow0).norm() * 0.255f
        return Arm(sh, elbow0, wrist, hand)
    }

    private fun leg(right: Boolean): Leg {
        val h = hipJoint(right)
        val foot = if (right) p.footR else p.footL
        val ankle = foot + v3(0f, 0.075f, 0.05f)
        val knee = RiderGeo.ik(h, ankle, 0.45f, 0.43f, p.kneeHint + v3(if (right) 0.15f else -0.15f, 0f, 0f))
        return Leg(h, knee, ankle, foot)
    }

    /** CHEER weight along the arm: t = 0 shoulder .. 1 hand. */
    private fun w(t: Float) = p.cheer * t * t

    // --- body part ----------------------------------------------------------------------------

    fun body(lod: Int): MeshBuffer {
        val m = MeshBuilder(1024)
        val sides = if (lod == 0) 10 else 6
        // Head with the hair on the same grid: hair on the crown and the back of the head.
        val rows = if (lod == 0) 9 else 5
        val cols = if (lod == 0) 14 else 8
        m.colorGrid(rows, cols, wrap = true,
            point = { r, c ->
                val lat = (-PI / 2 + PI * r / (rows - 1)).toFloat()
                val lon = (2 * PI * c / cols).toFloat()
                headC + v3(cos(lon) * cos(lat) * 0.082f, sin(lat) * 0.112f, sin(lon) * cos(lat) * 0.096f)
            },
            quadColor = { r, c ->
                val lat = -PI / 2 + PI * (r + 0.5) / (rows - 1)
                val back = sin(2 * PI * (c + 0.5) / cols) // +1 at the back (+z)
                val isHair = lat > 0.35 || (back > 0.2 && lat > (if (p.longHair) -1.2 else -0.35))
                if (isHair) hair else skin
            },
            outwardFrom = { _ -> headC })
        if (lod == 0) {
            if (p.longHair) m.withColor(hair) { m.ellipsoid(headC + v3(0f, -0.12f, 0.07f), 0.07f, 0.12f, 0.045f, 4, 7) }
            // Nose: a hint of a face direction for the people facing the camera.
            m.withColor(Col.scale(skin, 0.9f)) { m.box(headC + v3(0f, -0.01f, -0.098f), 0.025f, 0.04f, 0.02f) }
        }
        m.withColor(skin) { m.tube(neck - spineDir * 0.03f, headC + v3(0f, -0.07f, 0.01f), 0.05f, 0.047f, sides, capA = false, capB = false) }
        // Arms from the end of the short sleeve to the hand.
        for (right in listOf(false, true)) {
            val a = arm(right)
            val sleeveEnd = a.sh.lerp(a.elbow, 0.42f)
            m.withColor(skin) {
                m.tube(sleeveEnd, a.elbow, 0.043f, 0.038f, sides, capA = false, capB = lod == 0, ea = w(0.2f), eb = w(0.5f))
                m.tube(a.elbow, a.wrist, 0.038f, 0.03f, sides, capA = lod == 0, capB = false, ea = w(0.5f), eb = w(0.92f))
                if (lod == 0) m.withExtra(w(1f)) { m.with(Affine.basis(a.wrist, a.hand - a.wrist, V3.X)) { m.ellipsoid(v3(0f, 0.045f, 0f), 0.034f, 0.06f, 0.022f, 3, 6) } }
                else m.tube(a.wrist, a.hand, 0.03f, 0.02f, 3, capA = false, ea = w(0.92f), eb = w(1f))
            }
            if (p.phone && right) m.withColor(Col.hex(0x15171A)) { m.withExtra(w(1f)) { m.box(a.hand + v3(0f, 0.06f, 0.0f), 0.075f, 0.15f, 0.012f) } }
        }
        // Pelvis in the legwear colour, then legs.
        m.withColor(legs) {
            m.with(spine) { m.ellipsoid(v3(0f, 0.02f, 0f), p.body.hipW + 0.075f, 0.13f, 0.12f, if (lod == 0) 6 else 4, if (lod == 0) 12 else 7) }
        }
        for (right in listOf(false, true)) {
            val l = leg(right)
            val legEnd = if (shorts) l.hip.lerp(l.knee, 0.82f) else l.ankle
            m.withColor(legs) {
                if (shorts) m.tube(l.hip + (l.hip - l.knee).norm() * 0.03f, legEnd, 0.085f, 0.068f, sides, capA = false, capB = false)
                else {
                    m.tube(l.hip + (l.hip - l.knee).norm() * 0.03f, l.knee, 0.082f, 0.058f, sides, capA = false, capB = lod == 0)
                    m.tube(l.knee, l.ankle, 0.058f, 0.048f, sides, capA = lod == 0, capB = false)
                }
            }
            if (shorts) m.withColor(skin) {
                m.tube(legEnd, l.knee, 0.058f, 0.05f, sides, capA = false, capB = lod == 0)
                m.tube(l.knee, l.ankle, 0.05f, 0.036f, sides, capA = lod == 0, capB = false)
            }
            // Shoe along the foot direction (feet point forward, a little outward).
            m.withColor(shoe) {
                // Kept slim (8 x 7 cm, 25 cm long): a full-size box reads as clown shoes at this scale.
                val toe = l.foot + v3(if (right) 0.025f else -0.025f, 0f, -0.155f)
                val heel = l.foot + v3(0f, 0f, 0.07f)
                val f = Affine.basis(heel + v3(0f, 0.035f, 0f), toe - heel, V3.Y)
                // In this basis local x is up and local y runs heel to toe (so no skipBottom: it would drop the heel).
                m.with(f) { m.box(v3(0f, (toe - heel).length / 2, 0f), 0.07f, (toe - heel).length + 0.02f, 0.08f) }
                m.tube(l.ankle - v3(0f, 0.01f, 0f), heel + v3(0f, 0.06f, 0f), 0.045f, 0.045f, if (lod == 0) 5 else 3, capA = false, capB = false)
            }
        }
        return m.build()
    }

    // --- top part (tinted clothing) ------------------------------------------------------------

    fun top(lod: Int): MeshBuffer {
        val m = MeshBuilder(512)
        val b = p.body
        // Torso from the belt to the collar.
        data class R(val y: Float, val rx: Float, val rz: Float, val dz: Float)
        val rings = listOf(
            R(0.06f, b.hipW + 0.065f, 0.105f + b.belly * 0.5f, 0f),
            R(0.16f, b.chestRx - 0.012f, 0.1f + b.belly, -b.belly * 0.6f),
            R(0.28f, b.chestRx, b.chestRz + b.belly * 0.4f, -b.belly * 0.3f),
            R(0.38f, b.chestRx + 0.005f, b.chestRz, -0.005f),
            R(0.46f, b.shoulder - 0.01f, b.chestRz * 0.85f, 0f),
            R(0.515f, b.shoulder * 0.62f, 0.075f, 0f),
            R(0.55f, 0.065f, 0.058f, 0f),
        )
        val rr = if (lod == 0) rings else listOf(rings[0], rings[2], rings[4], rings[6])
        val cols = if (lod == 0) 14 else 8
        m.colorGrid(rr.size, cols, wrap = true,
            point = { r, c ->
                val ring = rr[r]
                val a = (2 * PI * c / cols).toFloat()
                // Female torso: a little narrower at the waist and fuller at the chest front.
                val front = max(0f, -sin(a))
                val bust = if (b.female && ring.y in 0.25f..0.42f) 0.02f * front else 0f
                spine.apply(v3(cos(a) * ring.rx, ring.y, sin(a) * (ring.rz + bust) + ring.dz))
            },
            quadColor = { r, _ -> if (lod == 0 && r == 0) trim else cloth },
            outwardFrom = { r -> spine.apply(v3(0f, rr[r].y, 0f)) })
        for (right in listOf(false, true)) {
            val a = arm(right)
            val end = a.sh.lerp(a.elbow, 0.45f)
            m.withColor(cloth) { m.tube(a.sh + (a.sh - a.elbow).norm() * 0.02f, end, 0.06f, 0.052f, if (lod == 0) 10 else 6, capA = true, capB = false, ea = 0f, eb = w(0.22f)) }
        }
        return m.build()
    }

    /** Whole person in one colour (the clothing tint), ~60 triangles, for the far LOD of the top. */
    fun far(): MeshBuffer {
        val m = MeshBuilder(128)
        m.withColor(cloth) {
            m.tube(spine.apply(v3(0f, -0.05f, 0f)), neck, p.body.hipW + 0.07f, p.body.shoulder * 0.9f, 4, capA = false, capB = true, side = V3.X, flat = 0.6f)
            m.box(headC, 0.16f, 0.22f, 0.18f, skipBottom = true)
            for (right in listOf(false, true)) {
                val a = arm(right)
                m.tube(a.sh, a.elbow, 0.05f, 0.045f, 3, capA = false, capB = false, ea = 0f, eb = w(0.5f))
                m.tube(a.elbow, a.hand, 0.045f, 0.035f, 3, capA = false, capB = false, ea = w(0.5f), eb = w(1f))
                val l = leg(right)
                m.tube(l.hip, l.knee, 0.08f, 0.06f, 3, capA = false, capB = false)
                m.tube(l.knee, l.foot, 0.06f, 0.05f, 3, capA = false, capB = false)
            }
        }
        return m.build()
    }
}

/*
 * Head accessories: sunglasses, caps and sun hats, each its own prototype placed per spectator at the
 * head centre of the pose (People.headOf), with the person's yaw, phase and energy so W1's CHEER bob
 * (the same for every vertex of an instance) moves them with the head; their Vtx.EXTRA is 0, so the
 * arm wave leaves them alone. Model origin = head centre, facing -z like the person.
 *
 * Kept small on purpose: the faces have no eyes, so a full-width dark band reads as a censor bar.
 * The glasses are two lenses with a thin bridge, dark grey, narrower than the face.
 */
object Accessory {
    const val GLASSES = "acc_glasses"
    const val CAP = "acc_cap"
    const val HAT = "acc_hat"

    /** Shares of spectators: sunglasses, a cap, a sun hat (exclusive; the rest go bare-headed). */
    const val P_GLASSES = 0.30
    const val P_CAP = 0.22
    const val P_HAT = 0.09

    /** Cap colours (the caravan's freebies: yellow, polka white, green; team-ish red, navy, black). */
    val CAP_COLOURS = intArrayOf(0xF4F4F4, 0xF4F4F4, 0xF2C80F, 0xF2C80F, 0xC81E2A, 0x1C2C5A, 0x2E8B3E, 0x1D1D1F, 0xE8701A, 0x7FB6E6)
    /** Sun hats: straw, white, khaki, faded blue. */
    val HAT_COLOURS = intArrayOf(0xE3CF98, 0xE3CF98, 0xF2F0EA, 0xC2B280, 0x9DB8D0)

    /** Which accessory a spectator wears for a uniform [u] in [0, 1): an id or null. */
    fun pick(u: Double): String? = when {
        u < P_GLASSES -> GLASSES
        u < P_GLASSES + P_CAP -> CAP
        u < P_GLASSES + P_CAP + P_HAT -> HAT
        else -> null
    }

    fun prototypes(): List<Prototype> = listOf(
        // Glasses are a few pixels beyond the near LOD: not worth an instance there.
        Prototype(GLASSES, listOf(ProtoLod(listOf(RaceMaterials.PEOPLE_GLASSES to glasses()), People.LOD_NEAR)), 0.12f),
        Prototype(CAP, listOf(ProtoLod(listOf(RaceMaterials.PEOPLE to cap(0)), People.LOD_NEAR), ProtoLod(listOf(RaceMaterials.PEOPLE to cap(1)), People.LOD_MID)), 0.2f),
        Prototype(HAT, listOf(ProtoLod(listOf(RaceMaterials.PEOPLE to hat(0)), People.LOD_NEAR), ProtoLod(listOf(RaceMaterials.PEOPLE to hat(1)), People.LOD_MID)), 0.2f),
    )

    // The head is an ellipsoid with semi-axes 0.082 (x), 0.112 (y), 0.096 (z) around the origin.
    private const val HX = 0.082f
    private const val HY = 0.112f
    private const val HZ = 0.096f
    private const val EYE_Y = 0.012f

    /** Depth of the face surface (negative z) at (x, y). */
    private fun faceZ(x: Float, y: Float): Float = -HZ * kotlin.math.sqrt(max(0f, 1f - (x / HX) * (x / HX) - (y / HY) * (y / HY)))

    private fun glasses(): MeshBuffer {
        val m = MeshBuilder(64)
        val lens = Col.hex(0x30353B)
        val frame = Col.hex(0x484D54)
        val lensW = 0.036f
        val lensH = 0.026f
        val cx = 0.03f
        for (sx in floatArrayOf(-1f, 1f)) {
            // Each lens follows the curve of the face: turned by the surface slope at its centre.
            val x = sx * cx
            val z = faceZ(x, EYE_Y)
            val slope = HZ * HZ / (HX * HX) * x / -z // dz/dx of the face surface (z grows towards the back)
            val ang = kotlin.math.atan(slope)
            val dir = v3(cos(ang), 0f, sin(ang))
            val c = v3(x, EYE_Y, z - 0.009f)
            m.withColor(lens) { m.with(Affine.basis(c, V3.Y, dir)) { m.box(V3.ZERO, lensW, lensH, 0.008f) } }
            // Temple arm from the outer lens edge back over the ear.
            val outer = c + dir * (lensW / 2 - 0.002f) + v3(0f, 0.006f, 0f)
            val ear = v3(sx * (HX + 0.003f), EYE_Y + 0.008f, -0.015f)
            m.withColor(frame) { m.tube(outer, ear, 0.003f, 0.003f, 3, capA = false, capB = false) }
        }
        // Thin bridge over the nose.
        m.withColor(frame) { m.box(v3(0f, EYE_Y + 0.007f, faceZ(0f, EYE_Y + 0.007f) - 0.008f), 0.026f, 0.005f, 0.005f) }
        return m.build()
    }

    /** Baseball cap, white so the tint is its colour; the peak a shade darker so it reads. */
    private fun cap(lod: Int): MeshBuffer {
        val m = MeshBuilder(128)
        m.withColor(floatArrayOf(0.92f, 0.92f, 0.92f)) {
            m.ellipsoid(v3(0f, 0.033f, 0.004f), 0.09f, 0.086f, 0.102f, if (lod == 0) 4 else 2, if (lod == 0) 10 else 6, yMin = 0f)
        }
        m.withColor(floatArrayOf(0.7f, 0.7f, 0.7f)) {
            // The peak tilts down a little towards its front edge.
            m.with(Affine.translate(0f, 0.04f, -0.125f) * Affine.rotX(-0.12f)) { m.box(V3.ZERO, 0.14f, 0.01f, 0.085f) }
        }
        return m.build()
    }

    /** Sun / bucket hat: a crown around the head and a brim sloping down, both faces of the brim built. */
    private fun hat(lod: Int): MeshBuffer {
        val m = MeshBuilder(128)
        val n = if (lod == 0) 10 else 6
        val white = floatArrayOf(0.92f, 0.92f, 0.92f)
        val under = floatArrayOf(0.72f, 0.72f, 0.72f)
        m.withColor(white) { m.tube(v3(0f, 0.025f, 0.004f), v3(0f, 0.125f, 0.004f), 0.097f, 0.086f, n, capA = false, capB = true, side = V3.Z, flat = 0.93f) }
        val r0 = 0.096f
        val r1 = 0.165f
        val y0 = 0.03f
        val y1 = 0.0f
        // Upward normal of the brim in the radial plane: (radial, up) = (y0 - y1, r1 - r0), normalised.
        val nr = (y0 - y1) / kotlin.math.hypot(y0 - y1, r1 - r0)
        val ny = (r1 - r0) / kotlin.math.hypot(y0 - y1, r1 - r0)
        val top = ArrayList<Int>()
        val bottom = ArrayList<Int>()
        for (k in 0..n) {
            val a = (2 * PI * k / n).toFloat()
            val ca = cos(a); val sa = sin(a)
            val nTop = v3(ca * nr, ny, sa * nr)
            val p0 = v3(ca * r0, y0, sa * r0 * 1.04f + 0.004f)
            val p1 = v3(ca * r1, y1, sa * r1 * 1.04f + 0.004f)
            top += m.vertex(p0, nTop, c = white); top += m.vertex(p1, nTop, c = white)
            val nBot = v3(-nTop.x, -nTop.y, -nTop.z)
            bottom += m.vertex(p0, nBot, c = under); bottom += m.vertex(p1, nBot, c = under)
        }
        for (k in 0 until n) {
            val a = k * 2
            // Upper face seen from above, lower face from below (single-sided material).
            m.quad(top[a], top[a + 2], top[a + 3], top[a + 1])
            m.quad(bottom[a], bottom[a + 1], bottom[a + 3], bottom[a + 2])
        }
        return m.build()
    }
}

/** Distance-independent helpers for checks. */
internal fun meshBounds(m: MeshBuffer): FloatArray {
    val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    for (i in 0 until m.vertexCount) {
        for (k in 0..2) {
            val v = m.vertices[i * 12 + k]
            b[k] = minOf(b[k], v); b[k + 3] = maxOf(b[k + 3], v)
        }
    }
    return b
}

