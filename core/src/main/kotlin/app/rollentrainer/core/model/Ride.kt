package app.rollentrainer.core.model

import app.rollentrainer.core.metrics.LapResult
import app.rollentrainer.core.metrics.Stats
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** One second of a ride. Missing sensor values are null. */
@Serializable
data class Sample(
    val t: Int,
    val power: Int? = null,
    val target: Int? = null,
    val hr: Int? = null,
    val cadence: Int? = null,
    val speedKmh: Double? = null,
    /** Height on a virtual route; null for indoor rides without a road. */
    val altitudeM: Double? = null,
)

@Serializable
data class RideSummary(
    val durationSec: Int,
    val distanceM: Double,
    val avgPower: Int,
    val maxPower: Int,
    val normalizedPower: Int,
    val intensityFactor: Double,
    val tss: Double,
    val workKj: Int,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgCadence: Int?,
    val maxCadence: Int?,
    val calories: Int,
) {
    companion object {
        fun of(samples: List<Sample>, ftp: Int): RideSummary {
            val duration = samples.size
            val watts = DoubleArray(duration) { (samples[it].power ?: 0).toDouble() }
            val np = Stats.normalizedPower(watts)
            val work = watts.sum()
            val hrs = samples.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
            // Average cadence excludes coasting, as head units do.
            val cads = samples.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }
            val distance = samples.sumOf { (it.speedKmh ?: 0.0) / 3.6 }
            return RideSummary(
                durationSec = duration,
                distanceM = distance,
                avgPower = if (duration > 0) watts.average().roundToInt() else 0,
                maxPower = watts.maxOrNull()?.roundToInt() ?: 0,
                normalizedPower = np.roundToInt(),
                intensityFactor = Stats.intensityFactor(np, ftp),
                tss = Stats.tss(duration, np, ftp),
                workKj = (work / 1000).roundToInt(),
                avgHr = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                maxHr = hrs.maxOrNull(),
                avgCadence = cads.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                maxCadence = cads.maxOrNull(),
                // Mechanical work in kJ is close to burned kcal at ~24 % efficiency.
                calories = (work / 1000).roundToInt(),
            )
        }
    }
}

@Serializable
data class Ride(
    val id: String,
    val startEpochSec: Long,
    val name: String,
    val workoutId: String? = null,
    val ftp: Int,
    val samples: List<Sample>,
    val summary: RideSummary,
    val stravaActivityId: Long? = null,
    /** One entry per workout segment actually ridden; empty for free rides. */
    val laps: List<LapResult> = emptyList(),
    /** Set when the rider accepted the FTP suggestion from this ride. */
    val ftpAccepted: Boolean = false,
    /** Best average power per window in seconds (5, 60, 300, 1200), stored so personal records are cheap to find. */
    val bests: Map<Int, Int> = emptyMap(),
    /** Virtual route that was ridden, with the fraction of it and the time to the finish (null if not finished). */
    val routeId: String? = null,
    val routeFraction: Double = 1.0,
    val routeTimeSec: Int? = null,
    /** Rate of perceived exertion 1–10, asked after the ride. */
    val rpe: Int? = null,
    val notes: String = "",
    /** Kind of FTP test this ride was, as the workout declared it. */
    val test: TestKind = TestKind.NONE,
)
