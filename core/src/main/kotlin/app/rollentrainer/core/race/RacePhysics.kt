package app.rollentrainer.core.race

import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/**
 * The road physics of [Physics], written out for the race so that every rider can have his own drag area
 * each sub step (drafting) without allocating parameter objects, and so the bunch can brake. With no
 * braking and the full drag area one step is exactly [Physics.step] for a step of at most 0.1 s.
 */
internal object RacePhysics {
    private val ROAD = RiderParams(totalMassKg = 1.0)
    val CRR = ROAD.crr
    val RHO = ROAD.airDensity
    val ETA = ROAD.drivetrainEfficiency

    fun resistance(v: Double, gradePct: Double, massKg: Double, cda: Double): Double {
        val theta = atan(gradePct / 100)
        return massKg * Physics.G * sin(theta) + massKg * Physics.G * cos(theta) * CRR + 0.5 * RHO * cda * v * v
    }

    /** One step of [h] ≤ 0.1 s: drive force from the power (limited near standstill), less resistance and brakes. */
    fun step(v: Double, powerW: Double, gradePct: Double, h: Double, massKg: Double, cda: Double, brakeMps2: Double = 0.0): Double {
        val drive = minOf(powerW * ETA / maxOf(v, 0.25), Physics.MAX_DRIVE_N)
        val a = (drive - resistance(v, gradePct, massKg, cda)) / massKg - brakeMps2
        return (v + a * h).coerceIn(0.0, Physics.MAX_SPEED_MPS)
    }

    fun powerFor(v: Double, gradePct: Double, massKg: Double, cda: Double): Double =
        resistance(v, gradePct, massKg, cda) * v / ETA

    /** Pedal power that brings the speed from [v] towards [target] within about [tau] seconds. */
    fun powerToReach(v: Double, target: Double, gradePct: Double, massKg: Double, cda: Double, tau: Double): Double {
        val force = resistance(v, gradePct, massKg, cda) + massKg * (target - v) / tau
        return force * maxOf(v, 1.0) / ETA
    }

    /**
     * W' balance (Skiba's differential model): spent one-to-one above critical power, refilled below it
     * the faster the emptier it is and the further below CP the rider rides.
     */
    fun wBalance(balance: Double, powerW: Double, cp: Double, wPrime: Double, h: Double): Double =
        if (powerW > cp) (balance - (powerW - cp) * h).coerceAtLeast(0.0)
        else (balance + (cp - powerW) * (wPrime - balance) / wPrime * h).coerceAtMost(wPrime)

    /**
     * Highest power available right now: the sprint peak when W' is full, falling to critical power as it
     * empties. A rider who has burnt his matches can still ride at CP, not above.
     */
    fun maxPower(cp: Double, peak: Double, balance: Double, wPrime: Double): Double {
        val f = (balance / wPrime).coerceIn(0.0, 1.0)
        return cp + (peak - cp) * Math.pow(f, 0.6)
    }

    /**
     * Power that can be held for [seconds]: the two-parameter model for short efforts, a slow decline
     * beyond 20 minutes (an hour is about FTP, a three-hour day about 80 % of it).
     */
    fun powerForDuration(cp: Double, wPrime: Double, seconds: Double): Double {
        val t = seconds.coerceAtLeast(20.0)
        return if (t <= 1200) cp + wPrime / t
        else (cp + wPrime / 1200) * (1 - 0.075 * Math.log(t / 1200) / Math.log(2.0)).coerceAtLeast(0.6)
    }

    /** Pedalling cadence for the animation; pros coast on fast descents and spin up in the sprint. */
    fun cadence(powerW: Double, cp: Double, gradePct: Double, sprinting: Boolean): Double = when {
        powerW < 25 -> 0.0
        sprinting -> 112.0
        else -> (90 + 10 * (powerW / cp - 0.75) - 1.2 * (gradePct - 3).coerceAtLeast(0.0)).coerceIn(62.0, 108.0)
    }

    /** A smooth 0..1 for the crowd shelter: many riders ahead help more, with diminishing returns. */
    fun saturate(x: Double, scale: Double): Double = 1 - exp(-x / scale)
}

/**
 * How the air behind and around other riders lowers a rider's drag area. Wind tunnel, track and CFD
 * studies agree on the order of magnitude: about 25–30 % less drag directly on one wheel at a short gap,
 * 40–50 % deep inside a bunch, almost nothing beyond a 3–4 m gap. The rider in front gains a little
 * from the rider behind him (about 3 %).
 */
object Drafting {
    const val BIKE_LENGTH_M = 1.75
    /** Drag reduction directly behind one wheel at a tight gap. */
    const val ONE_WHEEL = 0.30
    /** What the riders around and further ahead add inside a bunch. */
    const val BUNCH_EXTRA = 0.20
    const val MAX_REDUCTION = 0.50
    /** Wheel gap (rear wheel to front wheel) up to which the wake helps fully, and where it ends. */
    const val FULL_GAP_M = 0.3
    const val END_GAP_M = 3.7
    /** Lateral offset up to which the wake is fully used, and where it is lost. */
    const val FULL_LAT_M = 0.2
    const val END_LAT_M = 0.85
    /** Window ahead (wheel gap) and to the side in which other riders count as bunch shelter. */
    const val CROWD_AHEAD_M = 8.0
    const val CROWD_SIDE_M = 2.4
    const val LEADER_GAIN = 0.03

    /** Share of the full one-wheel effect at this wheel gap and lateral offset (0..1). */
    fun wake(wheelGapM: Double, lateralM: Double): Double {
        if (wheelGapM < -0.2 || wheelGapM >= END_GAP_M) return 0.0
        val lat = kotlin.math.abs(lateralM)
        if (lat >= END_LAT_M) return 0.0
        val l = if (wheelGapM <= FULL_GAP_M) 1.0 else 1 - (wheelGapM - FULL_GAP_M) / (END_GAP_M - FULL_GAP_M)
        val s = if (lat <= FULL_LAT_M) 1.0 else 1 - (lat - FULL_LAT_M) / (END_LAT_M - FULL_LAT_M)
        return l * s
    }

    /** Weight of one rider ahead as part of the bunch around (0..1). */
    fun crowdWeight(wheelGapM: Double, lateralM: Double): Double {
        if (wheelGapM < -BIKE_LENGTH_M || wheelGapM > CROWD_AHEAD_M) return 0.0
        val lat = kotlin.math.abs(lateralM)
        if (lat >= CROWD_SIDE_M) return 0.0
        val l = 1 - (wheelGapM.coerceAtLeast(0.0) / CROWD_AHEAD_M)
        return l * (1 - lat / CROWD_SIDE_M)
    }

    /**
     * The total drag reduction from the best wheel ([bestWake], 0..1), the summed crowd weight of the
     * other riders ahead and whether a rider sits right on this rider's wheel.
     */
    fun reduction(bestWake: Double, crowd: Double, followed: Boolean): Double {
        val r = ONE_WHEEL * bestWake + BUNCH_EXTRA * RacePhysics.saturate(crowd, 2.5) + if (followed) LEADER_GAIN else 0.0
        return r.coerceIn(0.0, MAX_REDUCTION)
    }
}
