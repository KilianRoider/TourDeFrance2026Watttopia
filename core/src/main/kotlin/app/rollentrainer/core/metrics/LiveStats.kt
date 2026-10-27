package app.rollentrainer.core.metrics

import kotlin.math.pow
import kotlin.math.roundToInt

/** A frozen view of the running totals, cheap to copy into UI state once per second. */
data class StatsSnapshot(
    val seconds: Int = 0,
    val avgPower: Int = 0,
    val maxPower: Int = 0,
    val normalizedPower: Int = 0,
    val intensityFactor: Double = 0.0,
    val tss: Double = 0.0,
    val workKj: Double = 0.0,
    val calories: Int = 0,
    val avgHr: Int? = null,
    val maxHr: Int? = null,
    val avgCadence: Int? = null,
    val distanceM: Double = 0.0,
    /** Seconds spent in each of the seven power zones. */
    val zoneSeconds: IntArray = IntArray(PowerZone.entries.size),
    /** Seconds spent in each of the five heart-rate zones. */
    val hrZoneSeconds: IntArray = IntArray(HeartRateZone.entries.size),
    /** Running totals for the current segment (interval), reset by [LiveStats.newLap]. */
    val lapSeconds: Int = 0,
    val lapAvgPower: Int = 0,
    val lapAvgHr: Int? = null,
    val lapAvgCadence: Int? = null,
    /** Mean deviation from the target within the lap, as a share of the target. */
    val lapAccuracyPct: Int? = null,
    /** 10 s and 30 s rolling power, the figures head units show. */
    val power10s: Int? = null,
    val power30s: Int? = null,
)

/**
 * Incremental training statistics, fed one sample per second.
 * Keeps a 30 s window for normalized power so the per-tick cost stays constant.
 */
class LiveStats(private val ftp: Int, private val maxHr: Int) {
    private var n = 0
    private var sumPower = 0.0
    private var maxPower = 0
    private val window = IntArray(30)
    private var windowSum = 0
    private var windowCount = 0
    private var fourthSum = 0.0
    private var fourthCount = 0
    private var hrSum = 0L
    private var hrCount = 0
    private var hrMax = 0
    private var cadSum = 0L
    private var cadCount = 0
    private var distance = 0.0
    private val zone = IntArray(PowerZone.entries.size)
    private val hrZone = IntArray(HeartRateZone.entries.size)

    private var lapN = 0
    private var lapPower = 0.0
    private var lapHrSum = 0L
    private var lapHrCount = 0
    private var lapCadSum = 0L
    private var lapCadCount = 0
    private var lapError = 0.0
    private var lapTargetCount = 0

    private var last10 = IntArray(10)
    private var idx10 = 0
    private var count10 = 0

    fun newLap() {
        lapN = 0; lapPower = 0.0; lapHrSum = 0; lapHrCount = 0
        lapCadSum = 0; lapCadCount = 0; lapError = 0.0; lapTargetCount = 0
    }

    fun add(power: Int?, target: Int?, hr: Int?, cadence: Int?, speedKmh: Double?) {
        val p = (power ?: 0).coerceAtLeast(0)
        n++
        sumPower += p
        if (p > maxPower) maxPower = p
        if (ftp > 0) zone[PowerZone.of(p * 100.0 / ftp).ordinal]++

        // 30 s rolling window for NP
        val slot = (n - 1) % 30
        if (windowCount < 30) {
            windowCount++
        } else {
            windowSum -= window[slot]
        }
        window[slot] = p
        windowSum += p
        if (windowCount == 30) {
            fourthSum += (windowSum / 30.0).pow(4)
            fourthCount++
        }

        last10[idx10] = p
        idx10 = (idx10 + 1) % 10
        if (count10 < 10) count10++

        if (hr != null && hr > 0) {
            hrSum += hr; hrCount++
            if (hr > hrMax) hrMax = hr
            hrZone[HeartRateZone.of(hr, maxHr).ordinal]++
            lapHrSum += hr; lapHrCount++
        }
        if (cadence != null && cadence > 0) {
            cadSum += cadence; cadCount++
            lapCadSum += cadence; lapCadCount++
        }
        if (speedKmh != null) distance += speedKmh / 3.6

        lapN++
        lapPower += p
        if (target != null && target > 0) {
            lapError += kotlin.math.abs(p - target).toDouble() / target
            lapTargetCount++
        }
    }

    fun snapshot(): StatsSnapshot {
        val np = when {
            fourthCount > 0 -> (fourthSum / fourthCount).pow(0.25)
            n > 0 -> sumPower / n
            else -> 0.0
        }
        val intensity = Stats.intensityFactor(np, ftp)
        val power30 = if (windowCount > 0) (windowSum.toDouble() / windowCount).roundToInt() else null
        val power10 = if (count10 > 0) (last10.take(count10).sum().toDouble() / count10).roundToInt() else null
        return StatsSnapshot(
            seconds = n,
            avgPower = if (n > 0) (sumPower / n).roundToInt() else 0,
            maxPower = maxPower,
            normalizedPower = np.roundToInt(),
            intensityFactor = intensity,
            tss = Stats.tss(n, np, ftp),
            workKj = sumPower / 1000.0,
            calories = (sumPower / 1000.0).roundToInt(),
            avgHr = if (hrCount > 0) (hrSum.toDouble() / hrCount).roundToInt() else null,
            maxHr = if (hrCount > 0) hrMax else null,
            avgCadence = if (cadCount > 0) (cadSum.toDouble() / cadCount).roundToInt() else null,
            distanceM = distance,
            zoneSeconds = zone.copyOf(),
            hrZoneSeconds = hrZone.copyOf(),
            lapSeconds = lapN,
            lapAvgPower = if (lapN > 0) (lapPower / lapN).roundToInt() else 0,
            lapAvgHr = if (lapHrCount > 0) (lapHrSum.toDouble() / lapHrCount).roundToInt() else null,
            lapAvgCadence = if (lapCadCount > 0) (lapCadSum.toDouble() / lapCadCount).roundToInt() else null,
            lapAccuracyPct = if (lapTargetCount > 0) (100 - lapError / lapTargetCount * 100).roundToInt().coerceIn(0, 100) else null,
            power10s = power10,
            power30s = power30,
        )
    }
}
