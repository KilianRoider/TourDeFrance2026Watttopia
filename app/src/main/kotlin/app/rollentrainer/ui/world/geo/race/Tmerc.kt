package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.geo.GeoProjection
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * Forward transverse Mercator on WGS84 (Snyder's series, good to centimetres within the ~100 km a pack
 * spans), the projection of the pack coordinates: x east, y north, metres from (lat0, lon0). Used to
 * put named places (the Dutch corner's church, waypoints) onto the pack's road.
 */
class Tmerc(p: GeoProjection) {
    private val lat0 = Math.toRadians(p.lat0)
    private val lon0 = Math.toRadians(p.lon0)
    private val k0 = p.k0
    private val a = 6378137.0
    private val f = 1 / 298.257223563
    private val e2 = f * (2 - f)
    private val ep2 = e2 / (1 - e2)
    private val m0 = meridian(lat0)

    private fun meridian(phi: Double): Double {
        val e4 = e2 * e2
        val e6 = e4 * e2
        return a * (
            (1 - e2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * e2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    /** (x, y) in pack metres of a WGS84 position. */
    fun forward(latDeg: Double, lonDeg: Double): DoubleArray {
        val phi = Math.toRadians(latDeg)
        val lam = Math.toRadians(lonDeg) - lon0
        val s = sin(phi)
        val c = cos(phi)
        val t = tan(phi)
        val n = a / sqrt(1 - e2 * s * s)
        val tt = t * t
        val cc = ep2 * c * c
        val aa = lam * c
        val m = meridian(phi)
        val x = k0 * n * (aa + (1 - tt + cc) * aa * aa * aa / 6 + (5 - 18 * tt + tt * tt + 72 * cc - 58 * ep2) * aa * aa * aa * aa * aa / 120)
        val y = k0 * (m - m0 + n * t * (aa * aa / 2 + (5 - tt + 9 * cc + 4 * cc * cc) * aa * aa * aa * aa / 24 +
            (61 - 58 * tt + tt * tt + 600 * cc - 330 * ep2) * aa * aa * aa * aa * aa * aa / 720))
        return doubleArrayOf(x, y)
    }
}
