package app.rollentrainer.core.model

import app.rollentrainer.core.metrics.Stats
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.roundToInt

/** A power target, either relative to the rider's FTP or as absolute watts. */
@Serializable
data class Power(val value: Int, val unit: PowerUnit = PowerUnit.PERCENT_FTP) {
    fun watts(ftp: Int): Int = when (unit) {
        PowerUnit.PERCENT_FTP -> (ftp * value / 100.0).roundToInt()
        PowerUnit.WATTS -> value
    }

    fun percentOfFtp(ftp: Int): Double = when (unit) {
        PowerUnit.PERCENT_FTP -> value.toDouble()
        PowerUnit.WATTS -> if (ftp > 0) value * 100.0 / ftp else 0.0
    }

    fun label(): String = when (unit) {
        PowerUnit.PERCENT_FTP -> "$value %"
        PowerUnit.WATTS -> "$value W"
    }

    companion object {
        fun pct(value: Int) = Power(value, PowerUnit.PERCENT_FTP)
        fun watts(value: Int) = Power(value, PowerUnit.WATTS)
    }
}

@Serializable
enum class PowerUnit { PERCENT_FTP, WATTS }

/** A coaching message shown [offsetSec] seconds into a block, like Zwift's text events. */
@Serializable
data class Cue(val offsetSec: Int, val text: String)

/** One building block of a workout. */
@Serializable
sealed class Block {
    abstract val id: String
    abstract fun durationSec(): Int
    /** Optional cadence target in rpm. */
    abstract val cadence: Int?
    abstract val cues: List<Cue>

    /** Constant power for a given time. */
    @Serializable
    @SerialName("steady")
    data class Steady(
        override val id: String,
        val durationSec: Int,
        val power: Power,
        val label: String = "",
        override val cadence: Int? = null,
        override val cues: List<Cue> = emptyList(),
    ) : Block() {
        override fun durationSec() = durationSec
    }

    /** Linear change of power, used for warm-up and cool-down. */
    @Serializable
    @SerialName("ramp")
    data class Ramp(
        override val id: String,
        val durationSec: Int,
        val from: Power,
        val to: Power,
        val label: String = "",
        override val cadence: Int? = null,
        override val cues: List<Cue> = emptyList(),
    ) : Block() {
        override fun durationSec() = durationSec
    }

    /** Repeated on/off intervals, e.g. 5 x (4 min @ 110 %, 3 min @ 55 %). */
    @Serializable
    @SerialName("intervals")
    data class Intervals(
        override val id: String,
        val repeat: Int,
        val onSec: Int,
        val onPower: Power,
        val offSec: Int,
        val offPower: Power,
        val label: String = "",
        override val cadence: Int? = null,
        /** Cadence target during the recovery part. */
        val offCadence: Int? = null,
        override val cues: List<Cue> = emptyList(),
    ) : Block() {
        override fun durationSec() = repeat * (onSec + offSec)
    }
}

@Serializable
data class Workout(
    val id: String,
    val name: String,
    val description: String = "",
    val blocks: List<Block> = emptyList(),
    val builtIn: Boolean = false,
) {
    fun durationSec(): Int = blocks.sumOf { it.durationSec() }
}

/** A flattened piece of a workout with a linear target from [startPct] to [endPct] of FTP. */
data class Segment(
    val blockIndex: Int,
    val startSec: Int,
    val durationSec: Int,
    val startPower: Power,
    val endPower: Power,
    val label: String,
    val cadence: Int? = null,
    val cues: List<Cue> = emptyList(),
) {
    val endSec: Int get() = startSec + durationSec

    /** The cue that applies [offsetSec] into the segment, shown for [CUE_VISIBLE_SEC]. */
    fun cueAt(offsetSec: Double): Cue? = cues.lastOrNull { it.offsetSec <= offsetSec && offsetSec < it.offsetSec + CUE_VISIBLE_SEC }

    companion object {
        const val CUE_VISIBLE_SEC = 12
    }
    val isRamp: Boolean get() = startPower != endPower

    fun wattsAt(offsetSec: Double, ftp: Int): Int {
        val a = startPower.watts(ftp)
        val b = endPower.watts(ftp)
        if (durationSec <= 0 || a == b) return a
        val f = (offsetSec / durationSec).coerceIn(0.0, 1.0)
        return (a + (b - a) * f).roundToInt()
    }
}

/** Precomputed timeline of a workout, used by the ride engine and the profile chart. */
class Timeline(val workout: Workout) {
    val segments: List<Segment>
    val totalSec: Int

    init {
        val out = mutableListOf<Segment>()
        var t = 0
        workout.blocks.forEachIndexed { index, block ->
            when (block) {
                is Block.Steady -> {
                    out += Segment(index, t, block.durationSec, block.power, block.power, block.label, block.cadence, block.cues)
                    t += block.durationSec
                }
                is Block.Ramp -> {
                    out += Segment(index, t, block.durationSec, block.from, block.to, block.label, block.cadence, block.cues)
                    t += block.durationSec
                }
                is Block.Intervals -> repeat(block.repeat) { r ->
                    val n = "${r + 1}/${block.repeat}"
                    val name = block.label.ifBlank { "Intervall" }
                    if (block.onSec > 0) {
                        // Cues belong to the first repetition only, so they are not repeated five times.
                        out += Segment(index, t, block.onSec, block.onPower, block.onPower, "$name $n", block.cadence, if (r == 0) block.cues else emptyList())
                        t += block.onSec
                    }
                    if (block.offSec > 0) {
                        out += Segment(index, t, block.offSec, block.offPower, block.offPower, "Erholung $n", block.offCadence)
                        t += block.offSec
                    }
                }
            }
        }
        segments = out.filter { it.durationSec > 0 }
        totalSec = t
    }

    /** Index of the segment active at [sec], or -1 once the workout is finished. */
    fun segmentIndexAt(sec: Double): Int {
        if (sec < 0) return if (segments.isEmpty()) -1 else 0
        // Segments are sorted, so a binary search keeps this cheap for long workouts.
        var lo = 0
        var hi = segments.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = segments[mid]
            when {
                sec < s.startSec -> hi = mid - 1
                sec >= s.endSec -> lo = mid + 1
                else -> return mid
            }
        }
        return -1
    }

    fun targetWattsAt(sec: Double, ftp: Int): Int? {
        val i = segmentIndexAt(sec)
        if (i < 0) return null
        val s = segments[i]
        return s.wattsAt(sec - s.startSec, ftp)
    }

    /** Intensity factor and TSS the workout would produce if ridden exactly as planned. */
    fun plannedStress(ftp: Int): Pair<Double, Double> {
        if (totalSec == 0 || ftp <= 0) return 0.0 to 0.0
        val watts = DoubleArray(totalSec) { targetWattsAt(it.toDouble(), ftp)?.toDouble() ?: 0.0 }
        val np = Stats.normalizedPower(watts)
        val intensity = np / ftp
        val tss = totalSec * np * intensity / (ftp * 3600.0) * 100.0
        return intensity to tss
    }
}
