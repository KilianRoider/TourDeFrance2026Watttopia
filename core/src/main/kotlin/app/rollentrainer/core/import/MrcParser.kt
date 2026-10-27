package app.rollentrainer.core.import

import app.rollentrainer.core.model.Block
import app.rollentrainer.core.model.Cue
import app.rollentrainer.core.model.Power
import app.rollentrainer.core.model.Workout
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Reads TrainerRoad/ErgVideo style .mrc (percent FTP) and .erg (watts) files:
 * a [COURSE HEADER] with the description, then [COURSE DATA] rows of "minutes  value".
 * Pairs of rows with the same value form a steady block, differing values a ramp.
 */
object Mrc {
    fun parse(text: String, fileName: String = ""): Workout {
        val lines = text.lines().map { it.trim() }
        var section = ""
        var name = ""
        var description = ""
        var unit = if (fileName.lowercase().endsWith(".erg")) "WATTS" else "PERCENT"
        val points = mutableListOf<Pair<Double, Double>>()
        val texts = mutableListOf<Pair<Int, String>>()
        for (raw in lines) {
            if (raw.isEmpty()) continue
            if (raw.startsWith("[")) { section = raw.uppercase(); continue }
            when (section) {
                "[COURSE HEADER]" -> {
                    val (k, v) = raw.split("=", limit = 2).let { if (it.size == 2) it[0].trim().uppercase() to it[1].trim() else "" to "" }
                    when (k) {
                        "FILE NAME" -> if (name.isBlank()) name = v.substringBeforeLast('.')
                        "DESCRIPTION" -> description = v
                    }
                    if (raw.uppercase().startsWith("MINUTES")) {
                        unit = if (raw.uppercase().contains("WATT")) "WATTS" else "PERCENT"
                    }
                }
                "[COURSE DATA]" -> {
                    val parts = raw.split(Regex("[\\s,;]+")).filter { it.isNotEmpty() }
                    if (parts.size >= 2) {
                        val t = parts[0].toDoubleOrNull()
                        val v = parts[1].toDoubleOrNull()
                        if (t != null && v != null) points += t to v
                    }
                }
                "[COURSE TEXT]" -> {
                    val parts = raw.split(Regex("\\s{2,}|\\t")).filter { it.isNotEmpty() }
                    val sec = parts.getOrNull(0)?.toDoubleOrNull()?.roundToInt()
                    val msg = parts.getOrNull(1)
                    if (sec != null && msg != null) texts += sec to msg
                }
            }
        }
        require(points.size >= 2) { "Keine Kursdaten gefunden" }

        fun power(v: Double) = if (unit == "WATTS") Power.watts(v.roundToInt()) else Power.pct(v.roundToInt())
        val blocks = mutableListOf<Block>()
        var blockStart = 0
        for (i in 0 until points.size - 1) {
            val (t0, v0) = points[i]
            val (t1, v1) = points[i + 1]
            val dur = ((t1 - t0) * 60).roundToInt()
            if (dur <= 0) continue
            val cues = texts.filter { it.first >= blockStart && it.first < blockStart + dur }.map { Cue(it.first - blockStart, it.second) }
            val id = UUID.randomUUID().toString()
            blocks += if (v0 == v1) Block.Steady(id, dur, power(v0), cues = cues)
            else Block.Ramp(id, dur, power(v0), power(v1), cues = cues)
            blockStart += dur
        }
        return Workout(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { fileName.substringBeforeLast('.').ifBlank { "Importiertes Training" } },
            description = description,
            blocks = mergeRepeats(blocks),
        )
    }

    /** Collapses runs of alternating steady blocks (on/off/on/off…) into one interval block. */
    private fun mergeRepeats(blocks: List<Block>): List<Block> {
        val out = mutableListOf<Block>()
        var i = 0
        while (i < blocks.size) {
            val a = blocks[i] as? Block.Steady
            val b = blocks.getOrNull(i + 1) as? Block.Steady
            if (a != null && b != null && a.power != b.power && a.cues.isEmpty() && b.cues.isEmpty()) {
                var n = 0
                var j = i
                while (j + 1 < blocks.size) {
                    val x = blocks[j] as? Block.Steady
                    val y = blocks[j + 1] as? Block.Steady
                    if (x == null || y == null || x.durationSec != a.durationSec || y.durationSec != b.durationSec ||
                        x.power != a.power || y.power != b.power || x.cues.isNotEmpty() || y.cues.isNotEmpty()) break
                    n++
                    j += 2
                }
                if (n >= 2) {
                    out += Block.Intervals(a.id, n, a.durationSec, a.power, b.durationSec, b.power)
                    i = j
                    continue
                }
            }
            out += blocks[i]
            i++
        }
        return out
    }
}
