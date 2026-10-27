package app.rollentrainer.ui.world

import android.opengl.Matrix
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A low-poly cyclist seen from behind: bike frame, two wheels, body, arms and legs whose knees
 * follow the pedals. Local axes: x right, y up, z forward.
 */
class RiderModel(jersey: FloatArray, helmet: FloatArray) {
    private val id = FloatArray(16).also { Matrix.setIdentityM(it, 0) }
    val frame: MeshData = buildFrame()
    val wheel: MeshData = MeshData(512).also { m ->
        // The wheel mesh has its axle along z; turn it so the axle is along x.
        val rot = FloatArray(16).also { Matrix.setRotateM(it, 0, 90f, 0f, 1f, 0f) }
        m.append(Shapes.wheel(WHEEL_R, 0.025f, 20), rot)
    }
    val torso: MeshData = Shapes.box(0.34f, 0.56f, 0.2f, jersey[0], jersey[1], jersey[2])
    val head: MeshData = MeshData(256).also { m ->
        m.append(Shapes.sphere(0.12f, 5, 8, 0.9f, 0.72f, 0.6f), id)
        m.append(Shapes.sphere(0.135f, 4, 8, helmet[0], helmet[1], helmet[2], squash = 0.75f), WorldMeshes.translate(0f, 0.04f, 0f))
    }
    val upperArm: MeshData = Shapes.cylinder(0.045f, 0.04f, 0.3f, 6, jersey[0] * 0.95f, jersey[1] * 0.95f, jersey[2] * 0.95f)
    val forearm: MeshData = Shapes.cylinder(0.04f, 0.035f, 0.3f, 6, 0.9f, 0.72f, 0.6f)
    val thigh: MeshData = Shapes.cylinder(0.075f, 0.06f, THIGH, 7, 0.12f, 0.12f, 0.14f)
    val shin: MeshData = Shapes.cylinder(0.06f, 0.045f, SHIN, 7, 0.9f, 0.72f, 0.6f)
    val shoe: MeshData = Shapes.box(0.09f, 0.06f, 0.26f, 0.95f, 0.95f, 0.95f)

    /** Draws the rider with [draw] called per part with its model matrix. */
    fun render(base: FloatArray, crankAngle: Float, wheelAngle: Float, draw: (MeshData, FloatArray) -> Unit) {
        val m = FloatArray(16)
        draw(frame, base)
        for (z in floatArrayOf(-0.5f, 0.5f)) {
            Matrix.translateM(m, 0, base, 0, 0f, WHEEL_R, z)
            Matrix.rotateM(m, 0, -wheelAngle, 1f, 0f, 0f)
            draw(wheel, m)
        }
        // Body leans forward over the bars.
        Matrix.translateM(m, 0, base, 0, 0f, 1.17f, 0.08f)
        Matrix.rotateM(m, 0, 50f, 1f, 0f, 0f)
        draw(torso, m)
        Matrix.translateM(m, 0, base, 0, 0f, 1.4f, 0.4f)
        draw(head, m)
        for (side in floatArrayOf(-1f, 1f)) {
            limb(base, side * 0.19f, 1.33f, 0.28f, side * 0.2f, 1.1f, 0.42f, upperArm, 0.3f, draw)
            limb(base, side * 0.2f, 1.1f, 0.42f, side * 0.2f, 1.0f, 0.5f, forearm, 0.3f, draw)
            // Pedal position on the crank circle; the knee follows from the two leg lengths.
            val a = crankAngle + if (side < 0) Math.PI.toFloat() else 0f
            val py = BB_Y + CRANK * cos(a)
            val pz = BB_Z + CRANK * sin(a)
            val hy = HIP_Y; val hz = HIP_Z
            val dy = py - hy; val dz = pz - hz
            val dist = sqrt(dy * dy + dz * dz).coerceIn(0.05f, THIGH + SHIN - 0.01f)
            // Knee from the circle intersection, bent forward (+z).
            val cosA = ((THIGH * THIGH + dist * dist - SHIN * SHIN) / (2 * THIGH * dist)).coerceIn(-1f, 1f)
            val angToPedal = atan2(dz, dy)
            val kneeAng = angToPedal + acos(cosA)
            val ky = hy + THIGH * cos(kneeAng)
            val kz = hz + THIGH * sin(kneeAng)
            val x = side * 0.12f
            limb(base, x, hy, hz, x, ky, kz, thigh, THIGH, draw)
            limb(base, x, ky, kz, x, py, pz, shin, SHIN, draw)
            Matrix.translateM(m, 0, base, 0, x, py - 0.03f, pz + 0.04f)
            draw(shoe, m)
        }
    }

    /** Draws a cylinder mesh (built along +y from 0 to [length]) from point a to point b in the rider's plane. */
    private fun limb(base: FloatArray, ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, mesh: MeshData, length: Float, draw: (MeshData, FloatArray) -> Unit) {
        val m = FloatArray(16)
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
        val m = MeshData(1024)
        val c = floatArrayOf(0.1f, 0.12f, 0.16f)
        fun tube(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float, r: Float = 0.025f) {
            val dx = bx - ax; val dy = by - ay; val dz = bz - az
            val len = sqrt(dx * dx + dy * dy + dz * dz)
            val mat = FloatArray(16)
            Matrix.setIdentityM(mat, 0)
            Matrix.translateM(mat, 0, ax, ay, az)
            Matrix.rotateM(mat, 0, Math.toDegrees(atan2(dz, dy).toDouble()).toFloat(), 1f, 0f, 0f)
            Matrix.rotateM(mat, 0, Math.toDegrees(atan2(-dx, sqrt(dy * dy + dz * dz)).toDouble()).toFloat(), 0f, 0f, 1f)
            m.append(Shapes.cylinder(r, r, len, 6, c[0], c[1], c[2]), mat)
        }
        val bbY = BB_Y; val bbZ = BB_Z
        tube(0f, bbY, bbZ, 0f, 0.98f, 0.42f)          // down tube
        tube(0f, 0.95f, -0.12f, 0f, 0.98f, 0.42f)     // top tube
        tube(0f, bbY, bbZ, 0f, 1.0f, -0.14f)          // seat tube
        tube(0f, 1.0f, -0.14f, 0f, 1.02f, -0.2f, 0.02f) // seat post
        for (x in floatArrayOf(-0.05f, 0.05f)) {
            tube(x, bbY, bbZ, x, WHEEL_R, -0.5f, 0.015f)      // chain stays
            tube(x, 1.0f, -0.14f, x, WHEEL_R, -0.5f, 0.015f)  // seat stays
            tube(x, 0.98f, 0.42f, x, WHEEL_R, 0.5f, 0.018f)   // fork
        }
        tube(-0.21f, 1.0f, 0.48f, 0.21f, 1.0f, 0.48f, 0.018f) // handlebar
        m.append(Shapes.box(0.08f, 0.04f, 0.26f, 0.1f, 0.1f, 0.1f), WorldMeshes.translate(0f, 1.03f, -0.18f)) // saddle
        m.append(Shapes.cylinder(0.11f, 0.11f, 0.02f, 12, 0.3f, 0.3f, 0.32f), run {
            val mat = FloatArray(16); Matrix.setIdentityM(mat, 0)
            Matrix.translateM(mat, 0, 0.06f, bbY, bbZ); Matrix.rotateM(mat, 0, 90f, 0f, 0f, 1f); mat
        }) // chainring
        return m
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
    }
}
