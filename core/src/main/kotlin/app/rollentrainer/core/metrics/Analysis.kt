package app.rollentrainer.core.metrics

import app.rollentrainer.core.model.Sample
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.roundToInt

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
    /** 100 minus the mean relative deviation from the target, 0..100. */
    val accuracyPct: Int? = null,
) {
    /** Three stars within 3 %, two within 6 %, one within 12 % of the target. Zwift awards similarly. */
    val stars: Int get() = when {
        accuracyPct == null -> 0
        accuracyPct >= 97 -> 3
        accuracyPct >= 94 -> 2
        accuracyPct >= 88 -> 1
        else -> 0
    }
}

object Analysis {
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

    /**
     * Splits the samples at [boundaries] (sample index → label) into lap results.
     * The first boundary is expected at index 0.
     */
    fun laps(samples: List<Sample>, boundaries: List<Pair<Int, String>>): List<LapResult> {
        if (samples.isEmpty() || boundaries.isEmpty()) return emptyList()
        val sorted = boundaries.sortedBy { it.first }
        return sorted.mapIndexedNotNull { i, (start, label) ->
            val end = sorted.getOrNull(i + 1)?.first ?: samples.size
            if (start >= end || start >= samples.size) return@mapIndexedNotNull null
            val part = samples.subList(start, end.coerceAtMost(samples.size))
            val powers = part.map { it.power ?: 0 }
            val targets = part.mapNotNull { it.target }
            val hrs = part.mapNotNull { it.hr?.takeIf { h -> h > 0 } }
            val cads = part.mapNotNull { it.cadence?.takeIf { c -> c > 0 } }
            val accuracy = part.mapNotNull { s ->
                val t = s.target ?: return@mapNotNull null
                if (t <= 0) null else abs((s.power ?: 0) - t).toDouble() / t
            }
            LapResult(
                label = label,
                startSec = start,
                durationSec = part.size,
                targetWatts = targets.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgPower = powers.average().roundToInt(),
                avgHr = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                avgCadence = cads.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
                accuracyPct = accuracy.takeIf { it.isNotEmpty() }?.let { (100 - it.average() * 100).roundToInt().coerceIn(0, 100) },
            )
        }
    }
}
