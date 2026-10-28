package app.rollentrainer.core.route

import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.tan

/** Chainrings and cassette of a bike; the gears are the usable combinations sorted from easy to hard. */
data class Drivetrain(val chainrings: List<Int>, val cassette: List<Int>) {
    data class Gear(val chainring: Int, val cog: Int) {
        val ratio: Double get() = chainring.toDouble() / cog
        val label: String get() = "$chainring × $cog"
    }

    /** Every combination, easiest first; near-duplicate ratios (small-small vs big-big) are kept, as on the road. */
    val gears: List<Gear> = chainrings.flatMap { r -> cassette.map { c -> Gear(r, c) } }.sortedBy { it.ratio }

    companion object {
        /** Common cassettes: smallest and largest cog name the steps in between. */
        val CASSETTES: Map<String, List<Int>> = mapOf(
            "11-25" to listOf(11, 12, 13, 14, 15, 16, 17, 19, 21, 23, 25),
            "11-28" to listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 28),
            "11-30" to listOf(11, 12, 13, 14, 15, 17, 19, 21, 24, 27, 30),
            "11-32" to listOf(11, 12, 13, 14, 16, 18, 20, 22, 25, 28, 32),
            "11-34" to listOf(11, 13, 15, 17, 19, 21, 23, 25, 27, 30, 34),
            "10-33" to listOf(10, 11, 12, 13, 14, 15, 17, 19, 21, 24, 28, 33),
            "10-36" to listOf(10, 11, 12, 13, 15, 17, 19, 21, 24, 27, 32, 36),
            "10-44" to listOf(10, 11, 13, 15, 17, 19, 21, 24, 28, 32, 38, 44),
            "10-51" to listOf(10, 12, 14, 16, 18, 21, 24, 28, 33, 39, 45, 51),
        )
        val CHAINRINGS: Map<String, List<Int>> = mapOf(
            "53/39" to listOf(53, 39), "52/36" to listOf(52, 36), "50/34" to listOf(50, 34), "48/32" to listOf(48, 32),
            "46/30" to listOf(46, 30), "1× 44" to listOf(44), "1× 42" to listOf(42), "1× 40" to listOf(40), "1× 38" to listOf(38),
        )

        fun of(chainrings: String, cassette: String): Drivetrain =
            Drivetrain(CHAINRINGS[chainrings] ?: listOf(50, 34), CASSETTES[cassette] ?: CASSETTES.getValue("11-28"))
    }
}

/**
 * Shifting in the app while the bike stays in one gear on the trainer. The virtual gear changes the
 * resistance the trainer applies: a harder gear feels like riding faster on the same road, so the
 * pedals get heavier at the same cadence, as on the road.
 */
class VirtualGears(val drivetrain: Drivetrain, startRatio: Double = NEUTRAL_RATIO) {
    private val gears = drivetrain.gears
    var index: Int = gears.indices.minByOrNull { kotlin.math.abs(gears[it].ratio - startRatio) } ?: 0
        private set

    val gear: Drivetrain.Gear get() = gears[index]
    val count: Int get() = gears.size
    /** "50 × 17 · 14/20" */
    val label: String get() = "${gear.label} · ${index + 1}/$count"

    fun up(): Boolean { if (index >= gears.size - 1) return false; index++; return true }
    fun down(): Boolean { if (index <= 0) return false; index--; return true }

    companion object {
        /** The physical gear the rider is asked to stay in on the trainer: a middle gear, 50 × 17. */
        const val NEUTRAL_RATIO = 50.0 / 17.0
        /** Metres per wheel revolution of a 700x25c tyre. */
        const val WHEEL_CIRCUMFERENCE_M = 2.1
    }
}

object Gearing {
    const val MIN_GRADE = -12.0
    /** The trainer engine clamps to ±25 % as well, so the hardest gear never asks for more than it sends. */
    const val MAX_GRADE = 25.0

    /**
     * The grade to send to the trainer so that the pedals feel like [virtualRatio] on a road of
     * [roadGradePct], while the bike really is in a gear of [physicalRatio] on the trainer. The
     * flywheel runs at [flywheelMps] (the trainer's speed, or cadence times the physical gear). The
     * pedal power must match: resistance(flywheel, sent grade) = k · resistance(k · flywheel, road
     * grade) with k the ratio of the two gears, which is solved for the sent grade.
     */
    fun feelGrade(roadGradePct: Double, virtualRatio: Double, physicalRatio: Double, flywheelMps: Double, p: RiderParams): Double {
        val k = (virtualRatio / physicalRatio).coerceIn(0.2, 5.0)
        val vFly = flywheelMps.coerceIn(0.5, Physics.MAX_SPEED_MPS)
        val vVirt = (vFly * k).coerceAtMost(Physics.MAX_SPEED_MPS)
        val wanted = k * Physics.resistance(vVirt, roadGradePct, p)
        val air = 0.5 * p.airDensity * p.cda * vFly * vFly
        // m·g·(sin θ + crr·cos θ) = wanted − air; crr is small, so the cosine term is taken at the road angle.
        val slopeTerm = (wanted - air) / (p.totalMassKg * Physics.G) - p.crr * cos(atan(roadGradePct / 100))
        val theta = asin(slopeTerm.coerceIn(-0.95, 0.95))
        return (tan(theta) * 100).coerceIn(MIN_GRADE, MAX_GRADE)
    }

    /** Flywheel speed from cadence when the trainer reports no speed. */
    fun flywheelFromCadence(cadenceRpm: Int, physicalRatio: Double): Double =
        cadenceRpm / 60.0 * physicalRatio * VirtualGears.WHEEL_CIRCUMFERENCE_M
}
