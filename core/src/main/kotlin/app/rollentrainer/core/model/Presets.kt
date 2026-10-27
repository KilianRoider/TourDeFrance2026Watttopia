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

    private fun warmup(minutes: Int = 10) = Block.Ramp(id(), min(minutes), Power.pct(45), Power.pct(70), "Einfahren")
    private fun cooldown(minutes: Int = 8) = Block.Ramp(id(), min(minutes), Power.pct(65), Power.pct(40), "Ausfahren")
    private fun easy(minutes: Int) = Block.Steady(id(), min(minutes), Power.pct(55), "Locker")

    /** Ids of workouts that older app versions shipped, so they are recognised in the history. */
    val all: List<Workout> = listOf(
        Workout(
            id = "preset-ramp-test",
            name = "FTP-Rampentest",
            description = "Jede Minute +6 % bis zur Erschöpfung. FTP ≈ 75 % der besten Minute. " +
                "Tippe auf „Ich bin platt“, wenn du nicht mehr kannst – dann folgt direkt das Ausfahren.",
            builtIn = true,
            test = TestKind.RAMP,
            blocks = listOf(Block.Steady(id(), min(5), Power.pct(40), "Einfahren")) +
                (0 until 25).map { Block.Steady(id(), 60, Power.pct(50 + it * 6), "Stufe ${it + 1}") } +
                Block.Steady(id(), min(5), Power.pct(35), "Ausfahren"),
        ),
        Workout(
            id = "preset-ramp-test-lite",
            name = "FTP-Rampentest Lite",
            description = "Sanfter Rampentest für Einsteiger: startet niedriger und steigt jede Minute nur um 4 %. " +
                "FTP ≈ 75 % der besten Minute.",
            builtIn = true,
            test = TestKind.RAMP,
            blocks = listOf(Block.Steady(id(), min(5), Power.pct(35), "Einfahren")) +
                (0 until 30).map { Block.Steady(id(), 60, Power.pct(40 + it * 4), "Stufe ${it + 1}") } +
                Block.Steady(id(), min(5), Power.pct(35), "Ausfahren"),
        ),
        Workout(
            id = "preset-20min-test",
            name = "20-Minuten-FTP-Test",
            description = "Der klassische Feldtest: nach Einfahren und drei kurzen Aktivierungen 5 Minuten voll, dann 20 Minuten ohne ERG " +
                "so hart wie möglich, aber gleichmäßig. FTP ≈ 95 % der 20 Minuten.",
            builtIn = true,
            test = TestKind.TWENTY_MIN,
            blocks = listOf(
                warmup(15),
                Block.Intervals(id(), 3, 60, Power.pct(100), 60, Power.pct(55), "Aktivierung", cadence = 100),
                easy(4),
                Block.FreeRide(id(), min(5), "5 Minuten voll", Power.pct(120)),
                easy(10),
                Block.FreeRide(id(), min(20), "20 Minuten Test", Power.pct(100), cues = listOf(Cue(0, "Gleichmäßig einteilen – nicht zu hart starten"))),
                cooldown(10),
            ),
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
            id = "preset-sweetspot-2x20",
            name = "Sweet Spot 2 × 20",
            description = "Viel Zeit knapp unter der Schwelle bei moderater Ermüdung – das Brot-und-Butter-Training.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 2, min(20), Power.pct(88), min(5), Power.pct(55)),
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
                easy(3),
                Block.Intervals(id(), 5, min(4), Power.pct(115), min(4), Power.pct(50)),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-30-30",
            name = "30/30 × 2 Sätze",
            description = "Zwei Sätze mit je zehn 30-Sekunden-Belastungen bei 120 % und 30 Sekunden locker – kurze Intervalle mit viel Zeit bei VO2max.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 10, 30, Power.pct(120), 30, Power.pct(50), "Satz 1", cadence = 100),
                easy(5),
                Block.Intervals(id(), 10, 30, Power.pct(120), 30, Power.pct(50), "Satz 2", cadence = 100),
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
            id = "preset-threshold-3x12",
            name = "Schwelle 3 × 12",
            description = "Drei Schwellenblöcke, etwas kürzer, dafür einer mehr.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 3, min(12), Power.pct(98), min(5), Power.pct(50)),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-over-under",
            name = "Over-Under 3 × 9",
            description = "Drei Blöcke im Wechsel 2 Minuten unter (95 %) und 1 Minute über (105 %) der Schwelle. Trainiert, Laktat unter Last abzubauen.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                easy(2),
            ) + (1..3).flatMap { set ->
                listOf(
                    Block.Intervals(id(), 3, min(2), Power.pct(95), 60, Power.pct(105), "Under $set", offLabel = "Over $set"),
                    Block.Steady(id(), min(5), Power.pct(50), "Erholung"),
                )
            } + cooldown(),
        ),
        Workout(
            id = "preset-anaerobic",
            name = "Anaerob 8 × 1",
            description = "Acht einminütige Intervalle bei 130 % mit jeweils zwei Minuten Erholung.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                easy(3),
                Block.Intervals(id(), 8, 60, Power.pct(130), 120, Power.pct(50), cadence = 100),
                cooldown(),
            ),
        ),
        Workout(
            id = "preset-sprints",
            name = "Sprints 6 × 15 s",
            description = "Sechs freie Sprints über 15 Sekunden ohne ERG – so hart wie möglich. Dazwischen 4 Minuten locker.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                easy(5),
            ) + (1..6).flatMap { n ->
                listOf(
                    Block.FreeRide(id(), 15, "Sprint $n", Power.pct(200), cues = if (n == 1) listOf(Cue(0, "Alles raus!")) else emptyList()),
                    Block.Steady(id(), min(4), Power.pct(50), "Erholung $n"),
                )
            } + cooldown(),
        ),
        Workout(
            id = "preset-pyramid",
            name = "Pyramide",
            description = "1-2-3-4-3-2-1 Minuten bei 110 % mit gleich langer Erholung.",
            builtIn = true,
            blocks = listOf(warmup()) + listOf(1, 2, 3, 4, 3, 2, 1).flatMap { m ->
                listOf(
                    Block.Steady(id(), min(m), Power.pct(110), "$m min"),
                    Block.Steady(id(), min(m), Power.pct(50), "Erholung"),
                )
            } + cooldown(),
        ),
        Workout(
            id = "preset-tempo",
            name = "Tempo 45",
            description = "45 Minuten zügig im Tempobereich, als Block zwischen Grundlage und Sweet Spot.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Steady(id(), min(45), Power.pct(80), "Tempo"),
                cooldown(5),
            ),
        ),
        Workout(
            id = "preset-cadence",
            name = "Trittfrequenz-Drills",
            description = "Grundlage mit Wechseln zwischen hoher (105 rpm) und niedriger (65 rpm) Trittfrequenz.",
            builtIn = true,
            blocks = listOf(
                warmup(),
                Block.Intervals(id(), 6, min(3), Power.pct(70), min(3), Power.pct(70), "105 rpm", cadence = 105, offCadence = 65, offLabel = "65 rpm"),
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
            id = "preset-endurance-90",
            name = "Grundlage 90",
            description = "Neunzig Minuten Zone 2 mit drei kurzen Beschleunigungen gegen die Langeweile.",
            builtIn = true,
            blocks = listOf(
                Block.Ramp(id(), min(5), Power.pct(45), Power.pct(65), "Einfahren"),
                Block.Steady(id(), min(25), Power.pct(66), "Grundlage"),
                Block.Intervals(id(), 3, 60, Power.pct(90), min(9), Power.pct(66), "Beschleunigung"),
                Block.Steady(id(), min(25), Power.pct(66), "Grundlage"),
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

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }
}
