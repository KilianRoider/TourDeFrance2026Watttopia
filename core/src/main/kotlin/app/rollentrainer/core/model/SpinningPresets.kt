package app.rollentrainer.core.model

/**
 * Built-in spinning classes: structured like a studio hour (warm-up, seated and standing climbs,
 * jumps, sprints, recovery, cool-down) with cadence targets and the coach's calls as cues.
 * They are ordinary workouts and ride fine in the normal view too; the spinning view adds music,
 * the spoken coach and the light show.
 */
object SpinningPresets {
    private fun min(m: Int) = m * 60
    private var n = 0
    private fun id() = "spin${n++}"

    private fun warmup(minutes: Int) = Block.Ramp(
        id(), min(minutes), Power.pct(45), Power.pct(68), "Einrollen", cadence = 90,
        cues = listOf(
            Cue(5, "Willkommen! Locker einrollen, Schultern runter"),
            Cue(min(1), "Leichter Widerstand, runde Tritte"),
            Cue(min(2), "Trink einen Schluck, gleich wird es wärmer"),
            Cue(min(minutes) - 40, "Noch kurz, dann geht es los"),
        ),
    )

    private fun cooldown(minutes: Int) = Block.Ramp(
        id(), min(minutes), Power.pct(60), Power.pct(40), "Ausrollen", cadence = 85,
        cues = listOf(
            Cue(5, "Geschafft! Widerstand raus, locker rollen"),
            Cue(60, "Atmen, Puls runterfahren"),
            Cue(min(minutes) - 60, "Ausrollen, Schultern lockern, trinken"),
        ),
    )

    private fun recovery(minutes: Int, label: String = "Erholung") = Block.Steady(
        id(), min(minutes), Power.pct(50), label, cadence = 90,
        cues = listOf(Cue(3, "Atmen, locker rollen"), Cue(min(minutes) - 30, "Gleich geht es weiter – Widerstand bereit")),
    )

    /** Jumps: 8 s seated, 8 s standing, the whole block at one target. */
    private fun jumps(minutes: Int, pct: Int, label: String = "Jumps") = Block.Steady(
        id(), min(minutes), Power.pct(pct), label, cadence = 80,
        // The first jumps are called one by one, after that the rider keeps the rhythm with the music.
        cues = listOf(
            Cue(0, "Jumps: 8 Sekunden sitzen, 8 Sekunden stehen"),
            Cue(8, "Hoch!"), Cue(16, "Setzen"), Cue(24, "Hoch!"), Cue(32, "Setzen"),
            Cue(40, "Weiter im Takt: acht sitzen, acht stehen"),
        ) + (90 until min(minutes) - 30 step 60).map { t -> Cue(t, "Jumps weiter, Oberkörper ruhig") } +
            Cue(min(minutes) - 15, "Letzter Jump, dann setzen"),
    )

    val all: List<Workout> = listOf(
        Workout(
            id = "preset-spin-45",
            name = "Spinning Class 45",
            description = "Die klassische Studiostunde: Einrollen, Flachstück, sitzender und stehender Berg, Jumps, " +
                "vier Sprints und ein zweiter Berg zum Schluss. Gedacht für die Spinning-Ansicht mit Musik und Coach.",
            builtIn = true,
            blocks = listOf(
                warmup(5),
                Block.Steady(
                    id(), min(4), Power.pct(70), "Flachstück", cadence = 95,
                    cues = listOf(Cue(0, "Flaches Stück – schnelle, runde Beine"), Cue(120, "Oberkörper ruhig, nur die Beine arbeiten")),
                ),
                Block.Steady(
                    id(), min(5), Power.pct(85), "Sitzender Berg", cadence = 70,
                    cues = listOf(
                        Cue(0, "Widerstand rauf, bleib im Sattel"),
                        Cue(90, "Hände locker am Lenker, Druck aus der Hüfte"),
                        Cue(180, "Noch zwei Minuten, der Berg wird steiler"),
                        Cue(270, "Gleich raus aus dem Sattel"),
                    ),
                ),
                Block.Steady(
                    id(), min(3), Power.pct(95), "Stehender Berg", cadence = 60,
                    cues = listOf(Cue(0, "Aus dem Sattel!"), Cue(60, "Gewicht über die Pedale, Rücken gerade"), Cue(150, "Letzte Minute – bleib stehen!")),
                ),
                recovery(2),
                jumps(4, 78),
                recovery(2),
                Block.Intervals(
                    id(), 4, 20, Power.pct(150), 70, Power.pct(55), "Sprint", cadence = 110, offCadence = 85,
                    cues = listOf(Cue(0, "Sprint – alles raus!")),
                ),
                recovery(2),
                Block.Ramp(
                    id(), min(6), Power.pct(80), Power.pct(100), "Berg 2", cadence = 65,
                    cues = listOf(
                        Cue(0, "Letzter Berg – jede Minute ein bisschen mehr Widerstand"),
                        Cue(120, "Bleib sitzen, der Gipfel kommt"),
                        Cue(240, "Noch zwei Minuten, das ist dein Berg!"),
                        Cue(320, "Gleich der Gipfel – aus dem Sattel!"),
                    ),
                ),
                Block.Steady(id(), min(2), Power.pct(105), "Gipfel stehend", cadence = 60, cues = listOf(Cue(0, "Aus dem Sattel – zum Gipfel!"), Cue(90, "Letzte 30 Sekunden, alles geben!"))),
                cooldown(4),
            ),
        ),
        Workout(
            id = "preset-spin-hiit-30",
            name = "Spinning HIIT 30",
            description = "Kurz und hart: Aktivierung, acht Tabata-Belastungen, Bergsprints und ein Finale mit vier langen " +
                "Sprints. Gedacht für die Spinning-Ansicht mit Musik und Coach.",
            builtIn = true,
            blocks = listOf(
                warmup(4),
                Block.Intervals(
                    id(), 2, 30, Power.pct(100), 30, Power.pct(55), "Aktivierung", cadence = 100, offCadence = 85,
                    cues = listOf(Cue(0, "Kurz anziehen, die Beine wecken")),
                ),
                recovery(1),
                Block.Intervals(
                    id(), 8, 20, Power.pct(140), 10, Power.pct(50), "Tabata", cadence = 105, offCadence = 90,
                    cues = listOf(Cue(0, "Tabata: 20 Sekunden voll, 10 Sekunden locker – acht Mal!")),
                ),
                recovery(3),
                Block.Intervals(
                    id(), 4, 30, Power.pct(130), 60, Power.pct(55), "Bergsprint", cadence = 70, offCadence = 85,
                    cues = listOf(Cue(0, "Bergsprint – schwerer Gang, aus dem Sattel!")),
                ),
                recovery(2),
                Block.Intervals(
                    id(), 4, 15, Power.pct(165), 45, Power.pct(55), "Finale", cadence = 110, offCadence = 85,
                    cues = listOf(Cue(0, "Finale – Sprint, alles raus!")),
                ),
                cooldown(4),
            ),
        ),
        Workout(
            id = "preset-spin-berge-50",
            name = "Spinning Berge 50",
            description = "Eine Bergstunde: wellige Anfahrt, ein langer sitzender Anstieg, Abfahrt, Serpentinen im Wechsel " +
                "sitzend und stehend und der Angriff zum Gipfel. Gedacht für die Spinning-Ansicht mit Musik und Coach.",
            builtIn = true,
            blocks = listOf(
                warmup(6),
                Block.Intervals(
                    id(), 3, 60, Power.pct(85), 60, Power.pct(65), "Welle", cadence = 75, offCadence = 90, offLabel = "Abfahrt",
                    cues = listOf(Cue(0, "Wellige Anfahrt – Widerstand rauf, bleib im Sattel")),
                ),
                Block.Ramp(
                    id(), min(8), Power.pct(75), Power.pct(95), "Langer Anstieg", cadence = 65,
                    cues = listOf(
                        Cue(0, "Der lange Anstieg – setz dich, find deinen Rhythmus"),
                        Cue(120, "Widerstand rauf, bleib im Sattel"),
                        Cue(240, "Halbzeit am Berg, Atmung ruhig"),
                        Cue(360, "Es wird steiler – Druck auf die Pedale!"),
                        Cue(450, "Gleich die Kehre, dann aus dem Sattel"),
                    ),
                ),
                Block.Steady(id(), min(2), Power.pct(100), "Steilstück stehend", cadence = 55, cues = listOf(Cue(0, "Aus dem Sattel!"), Cue(60, "Schwer treten, Oberkörper ruhig"))),
                Block.Steady(id(), min(3), Power.pct(55), "Abfahrt", cadence = 100, cues = listOf(Cue(0, "Abfahrt – Widerstand raus, schnelle Beine"), Cue(120, "Trinken, gleich kommen die Serpentinen"))),
                Block.Intervals(
                    id(), 3, 120, Power.pct(90), 60, Power.pct(105), "Serpentine", cadence = 65, offCadence = 55, offLabel = "Kehre stehend",
                    cues = listOf(Cue(0, "Serpentinen: zwei Minuten sitzen, eine Minute stehen"), Cue(110, "Gleich die Kehre – aus dem Sattel!")),
                ),
                recovery(3),
                Block.Ramp(
                    id(), min(5), Power.pct(90), Power.pct(110), "Gipfelangriff", cadence = 70,
                    cues = listOf(Cue(0, "Angriff zum Gipfel – jetzt zählt es!"), Cue(150, "Bleib dran, das ist dein Berg!"), Cue(240, "Letzte Minute – aus dem Sattel, zum Gipfel!")),
                ),
                recovery(3, "Gipfel"),
                cooldown(5),
            ),
        ),
        Workout(
            id = "preset-spin-endurance-60",
            name = "Spinning Endurance 60",
            description = "Eine ruhige Stunde mit Rhythmus: langes Flachstück, sitzender Berg, Flachstück mit Antritten, " +
                "ein zweiter Berg mit Stehpassage. Gedacht für die Spinning-Ansicht mit Musik und Coach.",
            builtIn = true,
            blocks = listOf(
                warmup(8),
                Block.Steady(
                    id(), min(10), Power.pct(70), "Flachstück", cadence = 95,
                    cues = listOf(
                        Cue(0, "Flachstück – im Takt der Musik treten"),
                        Cue(180, "Runde Tritte, zieh die Pedale hoch"),
                        Cue(360, "Oberkörper ruhig, Atmung gleichmäßig"),
                        Cue(540, "Letzte Minute, dann wird es ein Berg"),
                    ),
                ),
                Block.Steady(
                    id(), min(8), Power.pct(80), "Sitzender Berg", cadence = 70,
                    cues = listOf(Cue(0, "Widerstand rauf, bleib im Sattel"), Cue(240, "Halbzeit am Berg, Hände locker"), Cue(420, "Letzte Minute, dann rollt es wieder")),
                ),
                Block.Intervals(
                    id(), 5, 30, Power.pct(100), 90, Power.pct(70), "Antritt", cadence = 100, offCadence = 90, offLabel = "Flach",
                    cues = listOf(Cue(0, "Antritt – 30 Sekunden zügig, dann wieder locker")),
                ),
                Block.Ramp(
                    id(), min(8), Power.pct(78), Power.pct(90), "Berg 2", cadence = 65,
                    cues = listOf(Cue(0, "Zweiter Berg, der wird langsam steiler"), Cue(240, "Bleib dran, das ist dein Berg!"), Cue(420, "Gleich aus dem Sattel")),
                ),
                Block.Steady(id(), min(2), Power.pct(95), "Berg 2 stehend", cadence = 60, cues = listOf(Cue(0, "Aus dem Sattel!"), Cue(90, "Letzte 30 Sekunden stehen"))),
                recovery(4),
                cooldown(10),
            ),
        ),
    )

    fun byId(id: String): Workout? = all.firstOrNull { it.id == id }
}
