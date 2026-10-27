package app.rollentrainer.core.metrics

import kotlin.math.pow
import kotlin.math.roundToInt

/** Training metrics after Coggan: normalized power, intensity factor, TSS. */
object Stats {
    /** Normalized power from 1 Hz samples: 30 s rolling mean, 4th power, mean, 4th root. */
    fun normalizedPower(watts: DoubleArray): Double {
        if (watts.isEmpty()) return 0.0
        if (watts.size < 30) return watts.average()
        var sum = 0.0
        for (i in 0 until 30) sum += watts[i]
        var acc = (sum / 30).pow(4)
        var n = 1
        for (i in 30 until watts.size) {
            sum += watts[i] - watts[i - 30]
            acc += (sum / 30).pow(4)
            n++
        }
        return (acc / n).pow(0.25)
    }

    fun intensityFactor(np: Double, ftp: Int) = if (ftp > 0) np / ftp else 0.0

    fun tss(durationSec: Int, np: Double, ftp: Int): Double {
        if (ftp <= 0) return 0.0
        val intensity = np / ftp
        return durationSec * np * intensity / (ftp * 3600.0) * 100.0
    }
}

/** Seven-zone power model (Coggan), used for colours and labels. */
enum class PowerZone(val label: String, val upperPct: Int) {
    Z1("Erholung", 55),
    Z2("Grundlage", 75),
    Z3("Tempo", 90),
    Z4("Schwelle", 105),
    Z5("VO2max", 120),
    Z6("Anaerob", 150),
    Z7("Neuromuskulär", Int.MAX_VALUE);

    companion object {
        fun of(percentFtp: Double): PowerZone {
            val p = percentFtp.roundToInt()
            return entries.first { p <= it.upperPct }
        }
    }
}

/** Five heart-rate zones as a share of maximum heart rate. */
enum class HeartRateZone(val label: String, val upperPct: Int) {
    Z1("Sehr leicht", 60),
    Z2("Leicht", 70),
    Z3("Moderat", 80),
    Z4("Hart", 90),
    Z5("Maximal", Int.MAX_VALUE);

    companion object {
        fun of(hr: Int, maxHr: Int): HeartRateZone {
            if (maxHr <= 0) return Z1
            val p = (hr * 100.0 / maxHr).roundToInt()
            return entries.first { p <= it.upperPct }
        }
    }
}
