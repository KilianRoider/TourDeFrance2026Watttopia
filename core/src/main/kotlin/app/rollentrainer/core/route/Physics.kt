package app.rollentrainer.core.route

import kotlin.math.cos
import kotlin.math.atan
import kotlin.math.sin

/** What the road and the air do to a rider; the usual values for a road bike on the hoods. */
data class RiderParams(
    /** Rider plus bike. */
    val totalMassKg: Double,
    val cda: Double = 0.34,
    val crr: Double = 0.004,
    val airDensity: Double = 1.20,
    val drivetrainEfficiency: Double = 0.975,
) {
    companion object {
        const val BIKE_KG = 8.0
        fun forRider(weightKg: Double) = RiderParams(totalMassKg = weightKg + BIKE_KG)
    }
}

/** Road-bike physics: power against gravity, rolling and air resistance, with the rider's inertia. */
object Physics {
    const val G = 9.81
    /** Nobody descends faster than this indoors. */
    const val MAX_SPEED_MPS = 25.0

    /** Largest pushing force at the wheel when starting, in newtons. */
    const val MAX_DRIVE_N = 600.0

    /** Resistive force in N at a speed on a grade (positive = slows the rider). */
    fun resistance(speedMps: Double, gradePct: Double, p: RiderParams): Double {
        val theta = atan(gradePct / 100)
        val gravity = p.totalMassKg * G * sin(theta)
        val rolling = p.totalMassKg * G * cos(theta) * p.crr
        val air = 0.5 * p.airDensity * p.cda * speedMps * speedMps
        return gravity + rolling + air
    }

    /**
     * Advances the speed by [dt] seconds with [powerW] at the pedals. Inertia makes accelerations
     * and the slowing on a steeper ramp gradual, like on the road.
     */
    fun step(speedMps: Double, powerW: Double, gradePct: Double, dt: Double, p: RiderParams): Double {
        var v = speedMps
        var remaining = dt
        while (remaining > 0) {
            val h = minOf(0.1, remaining)
            // Force at the wheel from the power; near standstill it is limited by what legs and tyre can
            // push, so starting is brisk but not instant and slow crawling on steep ramps still moves.
            val drive = minOf(powerW * p.drivetrainEfficiency / maxOf(v, 0.25), MAX_DRIVE_N)
            val a = (drive - resistance(v, gradePct, p)) / p.totalMassKg
            v = (v + a * h).coerceIn(0.0, MAX_SPEED_MPS)
            // Standing still on a climb with no power: the bike does not roll backwards.
            if (v <= 0.0 && powerW <= 0 && gradePct >= 0) v = 0.0
            remaining -= h
        }
        return v
    }

    /** The speed at which [powerW] exactly balances the resistance on a grade. */
    fun steadySpeed(powerW: Double, gradePct: Double, p: RiderParams): Double {
        if (powerW <= 0 && gradePct >= 0) return 0.0
        var lo = 0.0
        var hi = MAX_SPEED_MPS
        repeat(40) {
            val mid = (lo + hi) / 2
            val net = powerW * p.drivetrainEfficiency - resistance(mid, gradePct, p) * mid
            if (net > 0) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    /** Power needed to hold a speed on a grade. */
    fun powerFor(speedMps: Double, gradePct: Double, p: RiderParams): Double =
        resistance(speedMps, gradePct, p) * speedMps / p.drivetrainEfficiency

    /** Rough time in seconds to ride a route at a constant power, for the route list. */
    fun estimateSeconds(route: Route, powerW: Double, p: RiderParams): Int {
        var t = 0.0
        route.points.zipWithNext { a, b ->
            val d = b.distanceM - a.distanceM
            if (d > 0) {
                val g = (b.elevationM - a.elevationM) / d * 100
                val v = steadySpeed(powerW, g, p).coerceAtLeast(0.5)
                t += d / v
            }
        }
        return t.toInt()
    }

    /**
     * The power a rider can hold for a route of this length, as a share of FTP: a 40-minute climb at
     * 90 %, a five-hour stage at about 70 %.
     */
    fun paceShareOfFtp(route: Route, ftp: Int, p: RiderParams): Double {
        val roughSec = estimateSeconds(route, ftp * 0.8, p)
        return when {
            roughSec < 3600 -> 0.9
            roughSec < 3 * 3600 -> 0.8
            else -> 0.7
        }
    }

    /** Average speed in km/h over a route at constant power. */
    fun averageKmh(route: Route, powerW: Double, p: RiderParams): Double {
        val sec = estimateSeconds(route, powerW, p)
        return if (sec > 0) route.lengthM / sec * 3.6 else 0.0
    }

    fun kmh(mps: Double) = mps * 3.6

    /** Vertical speed in m/h, the climbers' number. */
    fun vam(speedMps: Double, gradePct: Double): Double = speedMps * sin(atan(gradePct / 100)) * 3600
}
