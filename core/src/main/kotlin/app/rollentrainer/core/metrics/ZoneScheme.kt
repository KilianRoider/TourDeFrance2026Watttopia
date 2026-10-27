package app.rollentrainer.core.metrics

import kotlin.math.roundToInt

/**
 * Power zone schemes. Coggan's seven zones are the head-unit default; Zwift riders know
 * the six colours from the workout graph. Zone indices are 0-based.
 */
@kotlinx.serialization.Serializable
enum class ZoneScheme(val title: String, val labels: List<String>, private val upperPct: IntArray) {
    COGGAN(
        "Coggan (7 Zonen)",
        listOf("Erholung", "Grundlage", "Tempo", "Schwelle", "VO2max", "Anaerob", "Neuromuskulär"),
        intArrayOf(55, 75, 90, 105, 120, 150, Int.MAX_VALUE),
    ),
    ZWIFT(
        "Zwift (6 Zonen)",
        listOf("Erholung", "Grundlage", "Tempo", "Schwelle", "VO2max", "Anaerob"),
        intArrayOf(59, 75, 89, 104, 118, Int.MAX_VALUE),
    );

    val count: Int get() = upperPct.size

    fun indexOf(percentFtp: Double): Int {
        val p = percentFtp.roundToInt()
        return upperPct.indexOfFirst { p <= it }.coerceAtLeast(0)
    }

    fun name(index: Int): String = "Z${index + 1}"

    /** Seconds per zone from 1 Hz power values. */
    fun timeInZones(powers: Iterable<Int?>, ftp: Int): IntArray {
        val out = IntArray(count)
        if (ftp <= 0) return out
        for (p in powers) out[indexOf((p ?: 0) * 100.0 / ftp)]++
        return out
    }
}
