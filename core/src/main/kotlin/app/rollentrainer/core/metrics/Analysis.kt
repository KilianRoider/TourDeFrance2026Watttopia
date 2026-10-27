package app.rollentrainer.core.metrics

import app.rollentrainer.core.model.Sample
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/** How strictly intervals are rated. The tolerance is the deviation that still earns all stars. */
@Serializable
enum class StarTolerance(val title: String, val pct: Int) {
    RELAXED("Locker", 8), NORMAL("Normal", 5), STRICT("Streng", 3);
}

/** Result of one workout segment (interval), like a lap on a head unit. */
@Serializable
data class LapResult(
    val label: String,
    val startSec: Int,
    val durationSec: Int,
    val targetWatts: Int?,
    val avgPower: Int,
    val avgHr: Int? = null,
    val avgCadence: Int? = null,
    /** 100 minus the mean relative deviation from the target, 0..100. Null when the lap is not rated. */
    val accuracyPct: Int? = null,
    /** Mean absolute deviation in watts, for low targets where percent is harsh. */
    val deviationW: Int? = null,
    /** False for free-ride segments, ramps and laps shorter than 30 s; these get no stars. */
    val rated: Boolean = accuracyPct != null,
) {
    /** Stars with the normal tolerance. */
    val stars: Int get() = stars(StarTolerance.NORMAL)

    /**
     * Three stars within the tolerance (or within 10 W), two within twice, one within three times it.
     * The first seconds of a lap are already excluded, because every trainer needs a moment to settle.
     */
    fun stars(tolerance: StarTolerance): Int {
        if (!rated || accuracyPct == null) return 0
        val dev = 100 - accuracyPct
        val tol = tolerance.pct
        return when {
            dev <= tol || (deviationW != null && deviationW <= 10) -> 3
            dev <= tol * 2 -> 2
            dev <= tol * 3 -> 1
            else -> 0
        }
    }
}

/** Where a lap boundary lies and how the lap should be judged. */
data class LapBoundary(val sampleIndex: Int, val label: String, val rated: Boolean = true)

/**
 * Skiba's W' balance (the "integral-free" form used by Golden Cheetah): above critical power the
 * battery drains, below it recovers towards full. CP is approximated by FTP, W' by 20 kJ unless set.
 */
class WPrimeBalance(private val cp: Int, private val wPrimeJ: Int = 20_000) {
    var balanceJ: Double = wPrimeJ.toDouble()
        private set

    fun update(power: Int?, dtSec: Double = 1.0): Double {
        val p = power ?: 0
        if (p > cp) {
            balanceJ -= (p - cp) * dtSec
        } else {
            val recovery = (cp - p).toDouble() / wPrimeJ
            balanceJ += (wPrimeJ - balanceJ) * (1 - exp(-recovery * dtSec))
        }
        balanceJ = balanceJ.coerceIn(-wPrimeJ.toDouble(), wPrimeJ.toDouble())
        return balanceJ
    }

    /** 0..100 % of W' left. */
    val percent: Int get() = (balanceJ / wPrimeJ * 100).roundToInt().coerceIn(-100, 100)
}

object Analysis {
    /** The windows head units show as "best efforts". */
    val BEST_WINDOWS = listOf(5, 60, 300, 1200)

    /** Highest mean power over any [windowSec] consecutive seconds, or null if the ride is shorter. */
    fun bestAverage(samples: List<Sample>, windowSec: Int): Int? {
        if (windowSec <= 0 || samples.size < windowSec) return null
        var sum = 0L
        for (i in 0 until windowSec) sum += samples[i].power ?: 0
        var best = sum
        for (i in windowSec until samples.size) {
            sum += (samples[i].power ?: 0) - (samples[i - windowSec].power ?: 0)
            if (sum > best) best = sum
        }
        return (best.toDouble() / windowSec).roundToInt()
    }

    /** Best averages for all [BEST_WINDOWS] the ride is long enough for, keyed by window seconds. */
    fun bests(samples: List<Sample>): Map<Int, Int> =
        BEST_WINDOWS.mapNotNull { w -> bestAverage(samples, w)?.let { w to it } }.toMap()

    /**
     * FTP estimate from a ride. A ramp test counts 75 % of the best minute; otherwise 95 % of the best
     * 20 minutes, which is the common field test. Null if the ride is too short to tell.
     */
    fun ftpEstimate(samples: List<Sample>, rampTest: Boolean): Int? = if (rampTest) {
        bestAverage(samples, 60)?.let { (it * 0.75).roundToInt() }
    } else {
        bestAverage(samples, 20 * 60)?.let { (it * 0.95).roundToInt() }
    }

    /** Seconds spent in each power zone. */
    fun timeInZones(samples: List<Sample>, ftp: Int): IntArray {
        val out = IntArray(PowerZone.entries.size)
        if (ftp <= 0) return out
        for (s in samples) out[PowerZone.of((s.power ?: 0) * 100.0 / ftp).ordinal]++
        return out
    }

    /** Seconds spent in each heart-rate zone; samples without heart rate are skipped. */
    fun timeInHrZones(samples: List<Sample>, maxHr: Int): IntArray {
        val out = IntArray(HeartRateZone.entries.size)
        if (maxHr <= 0) return out
        for (s in samples) s.hr?.takeIf { it > 0 }?.let { out[HeartRateZone.of(it, maxHr).ordinal]++ }
        return out
    }

    /** W' balance in joules for every sample, for the ride chart. */
    fun wBalSeries(samples: List<Sample>, cp: Int, wPrimeJ: Int = 20_000): DoubleArray {
        val b = WPrimeBalance(cp, wPrimeJ)
        return DoubleArray(samples.size) { b.update(samples[it].power) }
    }

    /** Backwards compatible form: every boundary is a rated lap. */
    fun laps(samples: List<Sample>, boundaries: List<Pair<Int, String>>): List<LapResult> =
        lapsOf(samples, boundaries.map { LapBoundary(it.first, it.second) })

    /**
     * Splits the samples at [boundaries] into lap results. The first boundary is expected at index 0.
     * The first [SETTLE_SEC] seconds of every lap do not count towards accuracy, and laps shorter than
     * [MIN_RATED_SEC], ramps (target changes) and unrated boundaries get no accuracy at all.
     */
    fun lapsOf(samples: List<Sample>, boundaries: List<LapBoundary>): List<LapResult> {
        if (samples.isEmpty() || boundaries.isEmpty()) return emptyList()
        val sorted = boundaries.sortedBy { it.sampleIndex }
        return sorted.mapIndexedNotNull { i, b ->
            val start = b.sampleIndex
            val end = sorted.getOrNull(i + 1)?.sampleIndex ?: samples.size
            if (start >= end || start >= samples.size) return@mapIndexedNotNull null
            val part = samples.subList(start, end.coerceAtMost(samples.size))
            val powers = part.map { it.power ?: 0 }
            val targets = part.mapNotNull { it.target }
            val hrs = part.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
            val cads = part.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }
            val isRamp = targets.size >= 2 && (targets.max() - targets.min()) > 5
            val rated = b.rated && part.size >= MIN_RATED_SEC && !isRamp && targets.isNotEmpty()
            val judged = if (rated) part.drop(SETTLE_SEC) else emptyList()
            val relDev = judged.mapNotNull { s ->
                val t = s.target ?: return@mapNotNull null
                if (t <= 0) null else abs((s.power ?: 0) - t).toDouble() / t
            }
            val absDev = judged.mapNotNull { s -> s.target?.let { t -> abs((s.power ?: 0) - t) } }
            LapResult(
                label = b.label,
                startSec = start,
                durationSec = part.size,
                targetWatts = targets.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgPower = powers.average().roundToInt(),
                avgHr = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgCadence = cads.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                accuracyPct = relDev.takeIf { it.isNotEmpty() }?.let { (100 - it.average() * 100).roundToInt().coerceIn(0, 100) },
                deviationW = absDev.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                rated = rated && relDev.isNotEmpty(),
            )
        }
    }

    const val SETTLE_SEC = 5
    const val MIN_RATED_SEC = 30
}
