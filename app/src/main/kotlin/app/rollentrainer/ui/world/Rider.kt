package app.rollentrainer.ui.world

import android.opengl.Matrix
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A cyclist seen from behind: bike frame with fork, stem and bars, spoked wheels, cranks with pedals,
 * body, arms and legs whose knees follow the pedals. Local axes: z forward, y up and, as the world is
 * right-handed, x to the rider's left; the drivetrain is on the right (-x).
 */
class RiderModel(jersey: FloatArray, helmet: FloatArray) {
    private val id = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    /** Model matrices reused every frame; [draw] uploads them before the next part is placed. */
    private val scratch = FloatArray(16)
    private val limbScratch = FloatArray(16)
    val frame: MeshData = buildFrame()
    val wheel: MeshData = MeshData(1024).also { m ->
        // The wheel mesh has its axle along z; turn it so the axle is along x.
        val rot = FloatArray(16).also { Matrix.setRotateM(it, 0, 90f, 0f, 1f, 0f) }
        m.append(Shapes.wheel(WHEEL_R, 0.028f, 24, 16), rot)
    }
    val torso: MeshData = MeshData(128).also { m ->
        m.append(Shapes.box(0.34f, 0.56f, 0.2f, jersey[0], jersey[1], jersey[2]), id)
        // Back pockets and a darker bib stripe.
        m.append(Shapes.box(0.3f, 0.12f, 0.03f, jersey[0] * 0.75f, jersey[1] * 0.75f, jersey[2] * 0.75f), WorldMeshes.translate(0f, -0.2f, -0.1f))
        m.append(Shapes.box(0.36f, 0.14f, 0.22f, 0.12f, 0.12f, 0.14f), WorldMeshes.translate(0f, -0.3f, 0f))
    }
    val head: MeshData = MeshData(256).also { m ->
        m.append(Shapes.sphere(0.12f, 6, 10, 0.9f, 0.72f, 0.6f), id)
        m.append(Shapes.sphere(0.135f, 5, 10, helmet[0], helmet[1], helmet[2], squash = 0.75f), WorldMeshes.translate(0f, 0.04f, 0f))
        // Helmet vents as a darker band and sunglasses.
        m.append(Shapes.box(0.2f, 0.03f, 0.1f, helmet[0] * 0.5f, helmet[1] * 0.5f, helmet[2] * 0.5f), WorldMeshes.translate(0f, 0.12f, -0.02f))
        m.append(Shapes.box(0.2f, 0.035f, 0.04f, 0.08f, 0.08f, 0.1f), WorldMeshes.translate(0f, 0.02f, 0.11f))
    }
    val upperArm: MeshData = Shapes.cylinder(0.045f, 0.04f, 0.3f, 7, jersey[0] * 0.95f, jersey[1] * 0.95f, jersey[2] * 0.95f)
    val forearm: MeshData = Shapes.cylinder(0.04f, 0.035f, 0.3f, 7, 0.9f, 0.72f, 0.6f)
    val thigh: MeshData = Shapes.cylinder(0.075f, 0.06f, THIGH, 8, 0.12f, 0.12f, 0.14f)
    val shin: MeshData = Shapes.cylinder(0.06f, 0.045f, SHIN, 8, 0.9f, 0.72f, 0.6f)
    val shoe: MeshData = MeshData(64).also { m ->
        m.append(Shapes.box(0.09f, 0.06f, 0.26f, 0.95f, 0.95f, 0.95f), id)
        m.append(Shapes.box(0.095f, 0.015f, 0.27f, 0.1f, 0.1f, 0.1f), WorldMeshes.translate(0f, -0.035f, 0f)) // sole
    }
    /** Crank arm along +y from the bottom bracket to the pedal axle, with the pedal at its end. */
    val crankArm: MeshData = MeshData(64).also { m ->
        m.append(Shapes.box(0.02f, CRANK, 0.03f, 0.15f, 0.15f, 0.17f), WorldMeshes.translate(0f, CRANK / 2, 0f))
    }
    val pedal: MeshData = Shapes.box(0.1f, 0.02f, 0.07f, 0.1f, 0.1f, 0.11f)

    /**
     * Draws the rider with [draw] called per part with its model matrix. Angles in degrees for the wheel
     * (positive rolls forward) and radians for the crank.
     */
    fun render(base: FloatArray, crankAngle: Float, wheelAngle: Float, draw: (MeshData, FloatArray) -> Unit) {
        val m = scratch
        draw(frame, base)
        for (i in 0..1) {
            val z = if (i == 0) -0.5f else 0.5f
            Matrix.translateM(m, 0, base, 0, 0f, WHEEL_R, z)
            // Positive rotation about x turns the top of the wheel forward (+z): rolling ahead.
            Matrix.rotateM(m, 0, wheelAngle, 1f, 0f, 0f)
            draw(wheel, m)
        }
        // Body leans forward over the bars.
        Matrix.translateM(m, 0, base, 0, 0f, 1.17f, 0.08f)
        Matrix.rotateM(m, 0, 50f, 1f, 0f, 0f)
        draw(torso, m)
        Matrix.translateM(m, 0, base, 0, 0f, 1.4f, 0.4f)
        draw(head, m)
        for (i in 0..1) {
            val side = if (i == 0) -1f else 1f
            limb(base, side * 0.19f, 1.33f, 0.28f, side * 0.2f, 1.1f, 0.42f, upperArm, 0.3f, draw)
            limb(base, side * 0.2f, 1.1f, 0.42f, side * 0.2f, 1.0f, 0.5f, forearm, 0.3f, draw)
            // Pedal position on the crank circle; the knee follows from the two leg lengths.
            val a = crankAngle + if (side < 0) Math.PI.toFloat() else 0f
            val py = BB_Y + CRANK * cos(a)
            val pz = BB_Z + CRANK * sin(a)
            val hy = HIP_Y; val hz = HIP_Z
            val dy = py - hy; val dz = pz - hz
            val dist = sqrt(dy * dy + dz * dz).coerceIn(0.05f, THIGH + SHIN - 0.01f)
            // Knee from the circle intersection. Angles run from +y towards +z, the pedal is below the
            // hip, so turning back from the pedal direction puts the knee in front (+z).
            val cosA = ((THIGH * THIGH + dist * dist - SHIN * SHIN) / (2 * THIGH * dist)).coerceIn(-1f, 1f)
            val angToPedal = atan2(dz, dy)
            val kneeAng = angToPedal - acos(cosA)
            val ky = hy + THIGH * cos(kneeAng)
            val kz = hz + THIGH * sin(kneeAng)
            val x = side * 0.12f
            limb(base, x, hy, hz, x, ky, kz, thigh, THIGH, draw)
            limb(base, x, ky, kz, x, py, pz, shin, SHIN, draw)
            // Crank arm from the bottom bracket to the pedal, just outside the frame; the pedal stays level.
            val cx = side * 0.085f
            Matrix.translateM(m, 0, base, 0, cx, BB_Y, BB_Z)
            // The arm's +y must point at (py - BB_Y, pz - BB_Z): rotate +y towards +z by the crank angle.
            Matrix.rotateM(m, 0, Math.toDegrees(a.toDouble()).toFloat(), 1f, 0f, 0f)
            draw(crankArm, m)
            Matrix.translateM(m, 0, base, 0, side * 0.13f, py, pz)
            draw(pedal, m)
            Matrix.translateM(m, 0, base, 0, x, py + 0.01f, pz + 0.04f)
            draw(shoe, m)
        }
    }

    /** Draws a cylinder mesh (built along +y from 0 to [length]) from point a to point b in the rider's plane. */
    private fun limb(base: FloatArray, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, mesh: MeshData, length: Float, draw: (MeshData, FloatArray) -> Unit) {
        val m = limbScratch
        val dy = by - ay; val dz = bz - az; val dx = bx - ax
        val len = sqrt(dx * dx + dy * dy + dz * dz)
        Matrix.translateM(m, 0, base, 0, ax, ay, az)
        // Rotate +y onto the direction: first around x (pitch), then a small roll for the x offset.
        val pitch = Math.toDegrees(atan2(dz, dy).toDouble()).toFloat()
        Matrix.rotateM(m, 0, pitch, 1f, 0f, 0f)
        val roll = Math.toDegrees(atan2(-dx, sqrt(dy * dy + dz * dz)).toDouble()).toFloat()
        Matrix.rotateM(m, 0, roll, 0f, 0f, 1f)
        Matrix.scaleM(m, 0, 1f, len / length, 1f)
        draw(mesh, m)
    }

    private fun buildFrame(): MeshData {
        val m = MeshData(2048)
        val c = floatArrayOf(0.1f, 0.12f, 0.16f)
        val alu = floatArrayOf(0.55f, 0.56f, 0.6f)
        fun tube(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float = 0.025f, col: FloatArray = c) {
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val mat = FloatArray(16)
            Matrix.setIdentityM(mat, 0)
            Matrix.translateM(mat, 0, ax, ay, az)
            Matrix.rotateM(mat, 0, Math.toDegrees(atan2(dz, dy).toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.rotateM(mat, 0, Math.toDegrees(atan2(-dx, sqrt(dy * dy + dz * dz)).toDouble()).toFloat(), 0f, 0f, 1f)
            m.append(Shapes.cylinder(r, r, len, 8, col[0], col[1], col[2]), mat)
        }
        val bbY = BB_Y; val bbZ = BB_Z
        // Head tube top / bottom and the seat cluster.
        val htTopZ = 0.42f; val htTopY = 0.98f
        val htBotZ = 0.45f; val htBotY = 0.86f
        tube(0f, bbY, bbZ, 0f, htBotY, htBotZ, 0.03f)         // down tube (fat, to the head tube bottom)
        tube(0f, 0.95f, -0.12f, 0f, htTopY, htTopZ, 0.024f)   // top tube
        tube(0f, bbY, bbZ, 0f, 1.0f, -0.14f, 0.026f)          // seat tube
        tube(0f, htBotY, htBotZ, 0f, htTopY + 0.03f, htTopZ - 0.008f, 0.03f) // head tube
        tube(0f, 1.0f, -0.14f, 0f, 1.03f, -0.2f, 0.016f, alu)  // seat post
        for (x in floatArrayOf(-0.05f, 0.05f)) {
            tube(x, bbY, bbZ, x, WHEEL_R, -0.5f, 0.012f)      // chain stays
            tube(x, 1.0f, -0.14f, x, WHEEL_R, -0.5f, 0.012f)  // seat stays
            tube(x * 0.9f, htBotY, htBotZ, x, WHEEL_R, 0.5f, 0.016f)   // fork blades
        }
        tube(0f, htTopY + 0.03f, htTopZ - 0.008f, 0f, 1.03f, 0.48f, 0.016f, alu) // stem
        tube(-0.21f, 1.03f, 0.48f, 0.21f, 1.03f, 0.48f, 0.016f)              // handlebar
        for (x in floatArrayOf(-0.21f, 0.21f)) {
            tube(x, 1.03f, 0.48f, x, 1.03f, 0.58f, 0.016f)       // bar reach
            tube(x, 1.03f, 0.58f, x, 0.91f, 0.56f, 0.014f)       // drops
            m.append(Shapes.box(0.03f, 0.03f, 0.1f, 0.1f, 0.1f, 0.1f), WorldMeshes.translate(x, 1.05f, 0.6f)) // brake hood
        }
        m.append(Shapes.box(0.11f, 0.035f, 0.27f, 0.1f, 0.1f, 0.1f), WorldMeshes.translate(0f, 1.05f, -0.19f)) // saddle
        // Chainring and cassette on the right (-x), chain as a thin stay between them.
        m.append(Shapes.cylinder(0.11f, 0.11f, 0.012f, 16, 0.3f, 0.3f, 0.32f), spin(-0.055f, bbY, bbZ))
        m.append(Shapes.cylinder(0.045f, 0.045f, 0.03f, 10, 0.5f, 0.5f, 0.52f), spin(-0.03f, WHEEL_R, -0.5f))
        tube(-0.065f, bbY + 0.1f, bbZ, -0.065f, WHEEL_R + 0.04f, -0.5f, 0.005f, alu)
        tube(-0.065f, bbY - 0.1f, bbZ, -0.065f, WHEEL_R - 0.04f, -0.5f, 0.005f, alu)
        // Bottle on the down tube.
        m.append(Shapes.cylinder(0.035f, 0.035f, 0.2f, 8, 0.9f, 0.9f, 0.92f), run {
            val mat = FloatArray(16); Matrix.setIdentityM(mat, 0)
            Matrix.translateM(mat, 0, 0f, 0.5f, 0.2f); Matrix.rotateM(mat, 0, 36f, 1f, 0f, 0f); mat
        })
        return m
    }

    /** A disc built along +y, turned so its axle is along x at the given point. */
    private fun spin(x: Float, y: Float, z: Float): FloatArray {
        val mat = FloatArray(16); Matrix.setIdentityM(mat, 0)
        Matrix.translateM(mat, 0, x, y, z); Matrix.rotateM(mat, 0, 90f, 0f, 0f, 1f)
        return mat
    }

    companion object {
        const val WHEEL_R = 0.34f
        const val BB_Y = 0.29f
        const val BB_Z = 0.0f
        const val HIP_Y = 1.0f
        const val HIP_Z = -0.12f
        const val CRANK = 0.17f
        const val THIGH = 0.44f
        const val SHIN = 0.44f
        /** Eye height of the first-person camera above the road surface. */
        const val EYE_HEIGHT = 1.5f
        /** The first-person eye sits this far ahead of the saddle. */
        const val EYE_FORWARD = 0.25f
    }
}
