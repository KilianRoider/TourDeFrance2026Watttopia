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

    /**
     * ERG is switched off: the trainer simulates a flat road and the rider chooses the effort.
     * Used for sprints, 20-minute tests and "go as hard as you can" blocks. [guide] is only drawn.
     */
    @Serializable
    @SerialName("free")
    data class FreeRide(
        override val id: String,
        val durationSec: Int,
        val label: String = "Frei",
        val guide: Power = Power.pct(100),
        override val cadence: Int? = null,
        override val cues: List<Cue> = emptyList(),
    ) : Block() {
        override fun durationSec() = durationSec
    }
}

/** How the ride is evaluated afterwards: a ramp test takes 75 % of the best minute, the 20-minute test 95 %. */
@Serializable
enum class TestKind { NONE, RAMP, TWENTY_MIN }

@Serializable
data class Workout(
    val id: String,
    val name: String,
    val description: String = "",
    val blocks: List<Block> = emptyList(),
    val builtIn: Boolean = false,
    val test: TestKind = TestKind.NONE,
    val favorite: Boolean = false,
) {
    fun durationSec(): Int = blocks.sumOf { it.durationSec() }

    /** The zone most of the planned work happens in, for badges and filters. */
    fun dominantPercent(ftp: Int): Int {
        val tl = Timeline(this)
        var best = 0
        var bestSec = 0
        // Buckets of 5 % keep "sweet spot" and "threshold" apart.
        val sec = mutableMapOf<Int, Int>()
        for (s in tl.segments) {
            if (!s.erg) continue
            val p = ((s.startPower.percentOfFtp(ftp) + s.endPower.percentOfFtp(ftp)) / 2).roundToInt()
            if (p < 60) continue // warm-up and recovery don't define the workout
            val bucket = p / 5 * 5
            val v = (sec[bucket] ?: 0) + s.durationSec
            sec[bucket] = v
            if (v > bestSec) { bestSec = v; best = bucket }
        }
        return best
    }
}

/** A flattened piece of a workout with a linear target from [startPower] to [endPower]. */
data class Segment(
    val blockIndex: Int,
    val startSec: Int,
    val durationSec: Int,
    val startPower: Power,
    val endPower: Power,
    val label: String,
    val cadence: Int? = null,
    val cues: List<Cue> = emptyList(),
    /** False for free-ride segments: the trainer is put into simulation mode instead of ERG. */
    val erg: Boolean = true,
) {
    val endSec: Int get() = startSec + durationSec
    val isRamp: Boolean get() = startPower != endPower

    /** The cue that applies [offsetSec] into the segment, shown for [CUE_VISIBLE_SEC]. */
    fun cueAt(offsetSec: Double): Cue? = cues.lastOrNull { it.offsetSec <= offsetSec && offsetSec < it.offsetSec + CUE_VISIBLE_SEC }

    fun wattsAt(offsetSec: Double, ftp: Int): Int {
        val a = startPower.watts(ftp)
        val b = endPower.watts(ftp)
        if (durationSec <= 0 || a == b) return a
        val f = (offsetSec / durationSec).coerceIn(0.0, 1.0)
        return (a + (b - a) * f).roundToInt()
    }

    companion object {
        const val CUE_VISIBLE_SEC = 12
    }
}

/** Precomputed timeline of a workout, used by the ride engine and the profile chart. */
class Timeline private constructor(val workout: Workout, val segments: List<Segment>) {
    val totalSec: Int = segments.lastOrNull()?.endSec ?: 0

    constructor(workout: Workout) : this(workout, flatten(workout))

    /** A copy where segment [index] is [deltaSec] longer (or shorter, never below 5 s) and later segments move. */
    fun withSegmentExtended(index: Int, deltaSec: Int): Timeline {
        val seg = segments.getOrNull(index) ?: return this
        val d = deltaSec.coerceAtLeast(5 - seg.durationSec)
        if (d == 0) return this
        val out = segments.mapIndexed { i, s ->
            when {
                i < index -> s
                i == index -> s.copy(durationSec = s.durationSec + d)
                else -> s.copy(startSec = s.startSec + d)
            }
        }
        return Timeline(workout, out)
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

    companion object {
        private fun flatten(workout: Workout): List<Segment> {
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
                    is Block.FreeRide -> {
                        out += Segment(index, t, block.durationSec, block.guide, block.guide, block.label, block.cadence, block.cues, erg = false)
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
            return out.filter { it.durationSec > 0 }
        }
    }
}
