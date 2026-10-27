package app.rollentrainer.core.model

import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    classDiscriminator = "type"
}

/** Built-in workouts so the app is useful on first launch. */
object Presets {
    private fun min(m: Int) = m * 60
    private var n = 0
    private fun id() = "b${n++}"

    private fun warmup() = Block.Ramp(id(), min(10), Power.pct(45), Power.pct(70), "Einfahren")
    private fun cooldown() = Block.Ramp(id(), min(8), Power.pct(65), Power.pct(40), "Ausfahren")

    val all: List<Workout> = listOf(
        Workout(
            id = "preset-ramp-test",
            name = "FTP-Rampentest",
            description = "Jede Minute +6 % bis zur Erschöpfung. FTP ≈ 75 % der besten Minute. " +
                "Beende das Training mit „Überspringen“, wenn du nicht mehr kannst.",
            builtIn = true,
            blocks = listOf(Block.Steady(id(), min(5), Power.pct(40), "Einfahren")) +
                (0 until 25).map { Block.Steady(id(), 60, Power.pct(50 + it * 6), "Stufe ${it + 1}") } +
                Block.Steady(id(), min(5), Power.pct(35), "Ausfahren"),
        ),
        Workout(
            id = "preset-sweetspot",
            name = "Sweet Spot 3 × 10",
            description = "Drei Blöcke knapp unter der Schwelle. Baut Ausdauer an der Schwelle auf.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 3, min(10), Power.pct(90), min(5), Power.pct(55)),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-vo2",
            name = "VO2max 5 × 4",
            description = "Fünf harte Intervalle mit gleich langer Erholung.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Steady(id(), min(3), Power.pct(55), "Locker"),
                Block.Intervals(id(), 5, min(4), Power.pct(115), min(4), Power.pct(50)),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-threshold",
            name = "Schwelle 2 × 20",
            description = "Der Klassiker für die FTP: zwei Mal zwanzig Minuten an der Schwelle.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 2, min(20), Power.pct(97), min(5), Power.pct(50)),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-endurance",
            name = "Grundlage 60",
            description = "Eine Stunde ruhige Grundlage in Zone 2.",
            builtIn = true,
            blocks = listOf(
                Block.Ramp(id(), min(5), Power.pct(45), Power.pct(65), "Einfahren"),
                Block.Steady(id(), min(50), Power.pct(68), "Grundlage"),
                Block.Ramp(id(), min(5), Power.pct(60), Power.pct(45), "Ausfahren"),
            ),
        ),
        Workout(
            id = "preset-recovery",
            name = "Regeneration 30",
            description = "Lockeres Kurbeln für die Erholung.",
            builtIn = true,
            blocks = listOf(Block.Steady(id(), min(30), Power.pct(50), "Locker")),
        ),
    )
}
