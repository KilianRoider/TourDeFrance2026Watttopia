package app.rollentrainer.core.coach

import kotlin.random.Random

/** What the coach needs to know about one moment of the ride; filled from the ride state once per second. */
data class CoachInput(
    val workoutSec: Double,
    val segmentIndex: Int,
    val segmentLabel: String,
    val segmentRemainingSec: Int,
    val segmentDurationSec: Int,
    val targetWatts: Int?,
    val targetCadence: Int?,
    val power: Int?,
    /** The workout's own cue for this moment, if one is visible. */
    val cue: String?,
    val finished: Boolean,
    val paused: Boolean,
    val ftp: Int,
    /** Label of the following segment, for the countdown. */
    val nextLabel: String? = null,
    /** False in free-ride segments, where the trainer does not enforce the target. */
    val erg: Boolean = true,
)

/** How important a line is: the speaker drops low lines when it is busy and interrupts for high ones. */
enum class CoachPriority { LOW, NORMAL, HIGH }

/** [text] is shown on screen, [speech] is what the voice says (e.g. "Sprint 1 von 4" for "Sprint 1/4"). */
data class CoachLine(val text: String, val priority: CoachPriority, val speech: String = text)

/** Lines to say and show right now, plus the mood of the moment for lights and beat. */
data class CoachOutput(val lines: List<CoachLine>, val mood: Int) {
    companion object {
        const val MOOD_RECOVERY = 0
        const val MOOD_STEADY = 1
        const val MOOD_HARD = 2
        const val MOOD_SPRINT = 3
    }
}

/**
 * The spinning instructor's script: announces segments, repeats the workout's cues, counts down,
 * motivates during hard efforts and comments on the rider's power. Pure Kotlin so it can be tested;
 * feed it one [CoachInput] per second and it remembers what it has already said.
 */
class CoachScript(private val random: Random = Random.Default) {
    private var lastSegment = Int.MIN_VALUE
    private var lastCue: String? = null
    private var lastSec = Double.NaN
    private var said30 = false
    private var said10 = false
    private var saidHalf = false
    private var saidFinish = false
    private var praised = false
    private var nextMotivationSec = 0.0
    private var lastMotivation = -1
    private var onTargetSec = 0.0
    private var underSec = 0.0
    private var lastWarningSec = -1000.0

    fun update(input: CoachInput): CoachOutput {
        val lines = mutableListOf<CoachLine>()
        val mood = moodOf(input)
        if (input.finished) {
            if (!saidFinish) {
                saidFinish = true
                lines += CoachLine("Geschafft! Ausrollen und trinken.", CoachPriority.HIGH)
            }
            return CoachOutput(lines, CoachOutput.MOOD_RECOVERY)
        }
        if (input.paused || input.segmentIndex < 0) return CoachOutput(lines, mood)

        // A second of ride time passes between calls; jumps (skip, back) count as nothing.
        val dt = if (lastSec.isNaN()) 0.0 else (input.workoutSec - lastSec).coerceIn(0.0, 5.0)
        lastSec = input.workoutSec

        if (input.segmentIndex != lastSegment) {
            lastSegment = input.segmentIndex
            resetSegment(input)
            val label = input.segmentLabel.ifBlank { "Abschnitt ${input.segmentIndex + 1}" }
            lines += CoachLine(startLine(input, label), CoachPriority.HIGH, startLine(input, spoken(label)))
        }

        val cue = input.cue
        if (cue != null && cue != lastCue) {
            lines += CoachLine(cue, CoachPriority.NORMAL)
        }
        lastCue = cue

        val remaining = input.segmentRemainingSec
        val duration = input.segmentDurationSec
        val hasNext = input.nextLabel != null
        if (!saidHalf && duration >= 180 && remaining <= duration / 2 && remaining > 35) {
            saidHalf = true
            lines += CoachLine("Halbzeit", CoachPriority.NORMAL)
        }
        if (!said30 && duration >= 60 && remaining in 25..30) {
            said30 = true
            val next = input.nextLabel
            lines += if (next != null && next.isNotBlank()) {
                CoachLine("Noch 30 Sekunden, dann $next", CoachPriority.NORMAL, "Noch 30 Sekunden, dann ${spoken(next)}")
            } else {
                CoachLine("Noch 30 Sekunden", CoachPriority.NORMAL)
            }
        }
        if (!said10 && duration >= 20 && remaining in 8..10 && hasNext) {
            said10 = true
            lines += CoachLine("Noch 10 Sekunden", CoachPriority.NORMAL)
        }

        // Motivation only during hard work, never on top of the start line or into the countdown.
        val hard = mood >= CoachOutput.MOOD_HARD
        val elapsedInSegment = duration - remaining
        if (hard && lines.isEmpty() && elapsedInSegment >= 8 && remaining > 12 && input.workoutSec >= nextMotivationSec) {
            nextMotivationSec = input.workoutSec + 40 + random.nextInt(31)
            lines += CoachLine(motivation(), CoachPriority.LOW)
        }

        lines += powerFeedback(input, dt)
        return CoachOutput(lines, mood)
    }

    private fun resetSegment(input: CoachInput) {
        said30 = false
        said10 = false
        saidHalf = false
        praised = false
        onTargetSec = 0.0
        underSec = 0.0
        lastCue = null
        // The first motivational line waits a while so the start line can sink in.
        nextMotivationSec = input.workoutSec + 25 + random.nextInt(21)
    }

    /** "Jetzt: Stehender Berg – aus dem Sattel! 250 Watt, 60 Umdrehungen" or "Jetzt: Flachstück, 180 Watt, 95 Umdrehungen". */
    private fun startLine(input: CoachInput, label: String): String {
        val standing = isStanding(input.segmentLabel, input.cue)
        val details = buildList {
            val w = input.targetWatts
            if (w != null) add(if (input.erg) "$w Watt" else "frei, so hart du kannst")
            input.targetCadence?.let { add("$it Umdrehungen") }
        }.joinToString(", ")
        return buildString {
            append("Jetzt: ").append(label)
            if (standing && !label.contains("Sattel", ignoreCase = true)) {
                // A new sentence after the call, so the voice does not run "Sattel!" into the watts.
                append(" – aus dem Sattel!")
                if (details.isNotEmpty()) append(' ').append(details.replaceFirstChar { it.uppercase() })
            } else if (details.isNotEmpty()) {
                append(", ").append(details)
            }
        }
    }

    private fun powerFeedback(input: CoachInput, dt: Double): List<CoachLine> {
        val target = input.targetWatts ?: return emptyList()
        val power = input.power ?: return emptyList()
        if (!input.erg || target <= 0 || input.ftp <= 0) return emptyList()
        val out = mutableListOf<CoachLine>()
        val ratio = power.toDouble() / target
        if (ratio in 0.95..1.05) onTargetSec += dt else onTargetSec = 0.0
        if (ratio < 0.85) underSec += dt else underSec = 0.0

        if (!praised && onTargetSec >= 60 && target >= input.ftp * 0.6) {
            praised = true
            out += CoachLine(PRAISE[random.nextInt(PRAISE.size)], CoachPriority.NORMAL)
        }
        // Recovery targets are not worth a warning; ERG keeps the rider close anyway unless the cadence drops.
        if (underSec >= 20 && target >= input.ftp * 0.6 && input.workoutSec - lastWarningSec >= 60) {
            lastWarningSec = input.workoutSec
            underSec = 0.0
            out += CoachLine("Mehr Druck, du liegst unter dem Ziel", CoachPriority.NORMAL)
        }
        return out
    }

    private fun motivation(): String {
        var i = random.nextInt(MOTIVATION.size)
        if (i == lastMotivation) i = (i + 1) % MOTIVATION.size
        lastMotivation = i
        return MOTIVATION[i]
    }

    private fun moodOf(input: CoachInput): Int {
        val target = input.targetWatts ?: return CoachOutput.MOOD_STEADY
        val label = input.segmentLabel
        if (!input.erg || label.contains("Sprint", ignoreCase = true) || label.contains("Finale", ignoreCase = true)) return CoachOutput.MOOD_SPRINT
        val pct = if (input.ftp > 0) target * 100.0 / input.ftp else 0.0
        return when {
            pct < 60 -> CoachOutput.MOOD_RECOVERY
            pct < 90 -> CoachOutput.MOOD_STEADY
            pct < 120 -> CoachOutput.MOOD_HARD
            else -> CoachOutput.MOOD_SPRINT
        }
    }

    companion object {
        /** Beat for the music and lights: the target cadence as BPM, doubled for slow climbs so the beat still drives. */
        fun bpmFor(cadence: Int?): Int {
            val rpm = cadence ?: 85
            return (if (rpm < 60) rpm * 2 else rpm).coerceIn(60, 180)
        }

        /** True when [text] tells the rider to stand now; announcements and calls to sit are not. */
        fun isStanding(text: String): Boolean = posture(text) == true

        /**
         * Whether the rider should be out of the saddle: the visible [cue] decides when it calls for
         * sitting or standing, otherwise the segment [label] ("Stehender Berg"). Shared by the coach's
         * start line and the animated riders, so both always agree.
         */
        fun isStanding(label: String, cue: String?): Boolean =
            cue?.let { posture(it) } ?: posture(label) ?: false

        /**
         * Reads a label or cue: true for a call to stand now, false for a call to sit now, null when it
         * says neither. Announcements ("Gleich aus dem Sattel", "…, dann aus dem Sattel") are about
         * later and count as nothing. When both appear, the first call is the one that applies now
         * ("8 Sekunden sitzen, 8 Sekunden stehen" starts seated).
         */
        private fun posture(text: String): Boolean? {
            val t = text.lowercase()
            if (t.trimStart().startsWith("gleich")) return null
            val now = t.substringBefore(" dann ")
            val sit = SIT_WORDS.mapNotNull { w -> now.indexOf(w).takeIf { it >= 0 } }.minOrNull()
            val stand = STAND_WORDS.mapNotNull { w -> now.indexOf(w).takeIf { it >= 0 } }.minOrNull()
            return when {
                sit == null && stand == null -> null
                stand == null -> false
                sit == null -> true
                else -> stand < sit
            }
        }

        private val SIT_WORDS = listOf("sitz", "setz", "im sattel")
        private val STAND_WORDS = listOf("steh", "aus dem sattel", "wiegetritt", "hoch!")

        private val FRACTION = Regex("""\b(\d+)/(\d+)\b""")

        /** Interval labels count repetitions as "1/4", which a German voice reads as "ein Viertel"; this says "1 von 4". */
        fun spoken(text: String): String = FRACTION.replace(text, "\$1 von \$2")

        val MOTIVATION = listOf(
            "Bleib dran, das ist dein Berg!",
            "Druck auf die Pedale!",
            "Atmen, du schaffst das!",
            "Komm, jetzt zählt es!",
            "Rund treten, nicht nachlassen!",
            "Du bist stärker als dieser Widerstand!",
            "Schultern locker, Beine arbeiten!",
            "Weiter so, das sieht gut aus!",
            "Bleib im Rhythmus, bleib im Takt!",
            "Jede Umdrehung bringt dich näher an den Gipfel!",
            "Ruhig atmen, kräftig treten!",
            "Zeig mir, was in dir steckt!",
            "Nicht denken, treten!",
            "Fokus auf die nächsten Sekunden!",
            "Das ist der Moment, der dich besser macht!",
            "Halte den Druck, du hast das!",
            "Zieh die Pedale hoch, drück sie runter!",
            "Dein Puls arbeitet für dich, bleib dran!",
            "Noch ein Stück, der Berg gibt nach!",
            "Kopf hoch, Blick nach vorn!",
            "Gib nicht nach, gleich ist es geschafft!",
            "Spür die Kraft in den Beinen!",
            "Stark! Genau so weiter!",
            "Lass die Musik dich tragen!",
            "Du hast noch Reserven, hol sie raus!",
        )

        val PRAISE = listOf(
            "Sauber auf dem Ziel, genau so!",
            "Perfekt im Ziel, weiter so!",
            "Das ist dein Tempo, halt es!",
        )
    }
}
