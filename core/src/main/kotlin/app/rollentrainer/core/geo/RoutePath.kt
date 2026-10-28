package app.rollentrainer.core.geo

import app.rollentrainer.core.route.RoutePoint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot

/**
 * The road's centre line, one sample every [stepM] metres (the last at [lengthM]): position,
 * road elevation (the corrected profile), width, flags and road class.
 */
class RoutePath(
    val stepM: Double,
    val lengthM: Double,
    val x: DoubleArray,
    val y: DoubleArray,
    val z: DoubleArray,
    val width: FloatArray,
    val flags: ByteArray,
    val roadClass: ByteArray,
) {
    val size: Int get() = x.size

    /** Distance of sample [i] from the start. */
    fun distanceOf(i: Int): Double = minOf(i * stepM, lengthM)

    private fun index(d: Double): Pair<Int, Double> {
        val dc = d.coerceIn(0.0, lengthM)
        val i = minOf((dc / stepM).toInt(), size - 2).coerceAtLeast(0)
        val span = distanceOf(i + 1) - distanceOf(i)
        val f = if (span > 0) ((dc - distanceOf(i)) / span).coerceIn(0.0, 1.0) else 0.0
        return i to f
    }

    fun elevationAt(d: Double): Double {
        val (i, f) = index(d)
        return z[i] + (z[i + 1] - z[i]) * f
    }

    /** Grade in percent over +-[halfWindowM]. */
    fun gradeAt(d: Double, halfWindowM: Double = 10.0): Double {
        val a = (d - halfWindowM).coerceIn(0.0, lengthM)
        val b = (d + halfWindowM).coerceIn(0.0, lengthM)
        return if (b > a) (elevationAt(b) - elevationAt(a)) / (b - a) * 100 else 0.0
    }

    fun poseAt(d: Double): PathPose {
        val (i, f) = index(d)
        val px = x[i] + (x[i + 1] - x[i]) * f
        val py = y[i] + (y[i + 1] - y[i]) * f
        // Direction over +-10 m, so a vertex kink does not flip the camera.
        val a = index((d - 10).coerceAtLeast(0.0))
        val b = index((d + 10).coerceAtMost(lengthM))
        val ax = x[a.first] + (x[a.first + 1] - x[a.first]) * a.second
        val ay = y[a.first] + (y[a.first + 1] - y[a.first]) * a.second
        val bx = x[b.first] + (x[b.first + 1] - x[b.first]) * b.second
        val by = y[b.first] + (y[b.first + 1] - y[b.first]) * b.second
        var dx = bx - ax
        var dy = by - ay
        val n = hypot(dx, dy)
        if (n > 1e-9) { dx /= n; dy /= n } else { dx = 0.0; dy = 1.0 }
        val k = if (f < 0.5) i else i + 1
        return PathPose(
            d.coerceIn(0.0, lengthM), px, py, elevationAt(d), dx, dy, gradeAt(d),
            width[k].toDouble(), flags[k].toInt() and 0xFF, roadClass[k].toInt() and 0xFF,
        )
    }

    /**
     * Distance along the route of the road point nearest to (px, py). With [hintM] only the road
     * within [windowM] of the hint is searched (cheap per-frame tracking, and a road that passes the
     * same place twice resolves to the right pass).
     */
    fun nearestDistance(px: Double, py: Double, hintM: Double? = null, windowM: Double = 300.0): Double {
        val from = if (hintM == null) 0 else (((hintM - windowM) / stepM).toInt()).coerceIn(0, size - 2)
        val to = if (hintM == null) size - 2 else (ceil((hintM + windowM) / stepM).toInt()).coerceIn(0, size - 2)
        var best = Double.MAX_VALUE
        var bestD = 0.0
        for (i in from..to) {
            val ax = x[i]
            val ay = y[i]
            val vx = x[i + 1] - ax
            val vy = y[i + 1] - ay
            val l2 = vx * vx + vy * vy
            val t = if (l2 > 0) (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            val ex = ax + vx * t - px
            val ey = ay + vy * t - py
            val dd = ex * ex + ey * ey
            if (dd < best) {
                best = dd
                bestD = distanceOf(i) + (distanceOf(i + 1) - distanceOf(i)) * t
            }
        }
        return bestD
    }

    /** The elevation profile every [stepM] metres (the last point at the exact length). */
    fun profile(stepM: Double = 20.0): List<RoutePoint> {
        val out = ArrayList<RoutePoint>((lengthM / stepM).toInt() + 2)
        var d = 0.0
        while (d < lengthM - 1e-6) {
            out += RoutePoint(d, elevationAt(d))
            d += stepM
        }
        out += RoutePoint(lengthM, elevationAt(lengthM))
        return out
    }

    /** Sample index range covering distances [fromM, toM]. */
    fun indexRange(fromM: Double, toM: Double): IntRange {
        val a = (fromM.coerceIn(0.0, lengthM) / stepM).toInt().coerceIn(0, size - 1)
        val b = ceil(toM.coerceIn(0.0, lengthM) / stepM).toInt().coerceIn(0, size - 1)
        return a..b
    }

    /** Total climbing, ignoring wiggles smaller than [thresholdM]. */
    fun ascentM(thresholdM: Double = 2.0): Double {
        var sum = 0.0
        var last = z.firstOrNull() ?: return 0.0
        for (v in z) {
            if (v > last + thresholdM) { sum += v - last; last = v } else if (v < last - thresholdM) last = v
        }
        return sum
    }

    internal fun maxAbsStep(): Double = (0 until size - 1).maxOfOrNull { abs(z[it + 1] - z[it]) } ?: 0.0
}
