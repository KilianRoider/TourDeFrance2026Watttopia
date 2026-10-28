package app.rollentrainer.ui.world.geo

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Sun direction (GL axes: x east, y up, z south) for a place and local summer time. */
internal object SunPosition {
    /**
     * Direction towards the sun on [dayOfYear] at [clockH] local summer time (CEST, UTC+2) for
     * latitude/longitude in degrees. Accurate to well under a degree, which is all a renderer needs.
     */
    fun direction(latDeg: Double, lonDeg: Double, clockH: Double, dayOfYear: Int = 196): DoubleArray {
        val decl = Math.toRadians(23.44) * sin(2 * PI * (284 + dayOfYear) / 365.0)
        val b = 2 * PI * (dayOfYear - 81) / 364.0
        val eotMin = 9.87 * sin(2 * b) - 7.53 * cos(b) - 1.5 * sin(b)
        val solarH = clockH - 2.0 + lonDeg / 15.0 + eotMin / 60.0
        val hour = Math.toRadians(15.0 * (solarH - 12.0))
        val lat = Math.toRadians(latDeg)
        val elev = asin(sin(lat) * sin(decl) + cos(lat) * cos(decl) * cos(hour))
        val az = atan2(-sin(hour), tan(decl) * cos(lat) - sin(lat) * cos(hour))
        return doubleArrayOf(cos(elev) * sin(az), sin(elev), -cos(elev) * cos(az))
    }
}

/**
 * Chase and first-person camera along the pack path. Works in Double world coordinates (GL axes);
 * the renderer subtracts [eye] from everything it draws.
 */
internal class GeoCamera {
    val eye = DoubleArray(3)
    val look = DoubleArray(3)
    /** Smoothed roll (radians) into bends. */
    var roll = 0.0
    private var valid = false
    private val tEye = DoubleArray(3)
    private val tLook = DoubleArray(3)

    fun invalidate() { valid = false }

    /**
     * [rider]: GL position of the rider's contact point; [fwd]: GL unit forward (horizontal);
     * [behind]/[ahead]: GL points on the road (rider's lane) behind and ahead of the rider for the chase eye
     * and the look-at; [lean] the rider's lean (radians, + left).
     */
    fun update(
        firstPerson: Boolean, rider: DoubleArray, fwd: DoubleArray, behind: DoubleArray, ahead: DoubleArray,
        aheadFar: DoubleArray, lean: Double, aspect: Float, dt: Double, bob: Double,
    ) {
        val rate: Double
        if (firstPerson) {
            // Eye over the bars: 1.55 m above the road, a little ahead of the bottom bracket; look 30 m up the road.
            tEye[0] = rider[0] + fwd[0] * 0.15; tEye[1] = rider[1] + 1.55 + bob; tEye[2] = rider[2] + fwd[2] * 0.15
            tLook[0] = aheadFar[0]; tLook[1] = aheadFar[1] + 1.15; tLook[2] = aheadFar[2]
            rate = 12.0
        } else {
            // Moto camera: low and close behind (on a portrait screen closer still, see GeoWorldRenderer.updateCamera).
            tEye[0] = behind[0]; tEye[1] = behind[1] + 1.85; tEye[2] = behind[2]
            tLook[0] = ahead[0]; tLook[1] = ahead[1] + 0.95; tLook[2] = ahead[2]
            // The eye trails the rider by speed / rate: on a portrait screen it follows more tightly, or the
            // rider, already small in the very tall view, shrinks further at speed.
            rate = if (aspect < 1f) 8.0 else 5.0
        }
        if (!valid) {
            System.arraycopy(tEye, 0, eye, 0, 3)
            System.arraycopy(tLook, 0, look, 0, 3)
            roll = 0.0
            valid = true
            return
        }
        val k = 1 - exp(-dt * rate)
        val kl = 1 - exp(-dt * rate * 1.6)
        for (i in 0 until 3) {
            eye[i] += (tEye[i] - eye[i]) * k
            look[i] += (tLook[i] - look[i]) * kl
        }
        // Never fall more than 1 m below the target height (steep downhills with the lag).
        if (eye[1] < tEye[1] - 1.0) eye[1] = tEye[1] - 1.0
        val targetRoll = if (firstPerson) lean * 0.5 else lean * 0.3
        roll += (targetRoll - roll) * (1 - exp(-dt * 3.0))
    }

    /** Vertical field of view (degrees) keeping a horizontal one of [hfovDeg] at [aspect], within limits. */
    fun verticalFov(hfovDeg: Double, aspect: Float): Float {
        val v = 2 * atan(tan(Math.toRadians(hfovDeg / 2)) / aspect)
        return Math.toDegrees(v).coerceIn(38.0, 106.0).toFloat()
    }

    @Suppress("unused")
    fun distanceTo(x: Double, y: Double, z: Double): Double {
        val dx = x - eye[0]; val dy = y - eye[1]; val dz = z - eye[2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }

    @Suppress("unused")
    private fun angle(a: DoubleArray, b: DoubleArray) = acos((a[0] * b[0] + a[2] * b[2]).coerceIn(-1.0, 1.0))
}
