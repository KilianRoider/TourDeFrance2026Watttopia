package app.rollentrainer.core

import app.rollentrainer.core.coach.CoachInput
import app.rollentrainer.core.coach.CoachOutput
import app.rollentrainer.core.coach.CoachPriority
import app.rollentrainer.core.coach.CoachScript
import app.rollentrainer.core.model.SpinningPresets
import app.rollentrainer.core.model.Timeline
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class CoachScriptTest {
    private fun input(
        sec: Int,
        segment: Int = 0,
        label: String = "Sitzender Berg",
        duration: Int = 300,
        target: Int? = 200,
        cadence: Int? = 70,
        power: Int? = target,
        cue: String? = null,
        finished: Boolean = false,
        paused: Boolean = false,
        next: String? = "Erholung",
        erg: Boolean = true,
    ) = CoachInput(
        workoutSec = sec.toDouble(), segmentIndex = segment, segmentLabel = label,
        segmentRemainingSec = duration - sec, segmentDurationSec = duration,
        targetWatts = target, targetCadence = cadence, power = power, cue = cue,
        finished = finished, paused = paused, ftp = 200, nextLabel = next, erg = erg,
    )

    /** Runs the segment second by second and collects every line. */
    private fun run(script: CoachScript, seconds: IntRange, make: (Int) -> CoachInput): List<String> =
        seconds.flatMap { s -> script.update(make(s)).lines.map { it.text } }

    @Test fun announcesSegmentStartWithWattsAndCadence() {
        val script = CoachScript(Random(1))
        val out = script.update(input(0))
        assertEquals(listOf("Jetzt: Sitzender Berg, 200 Watt, 70 Umdrehungen"), out.lines.map { it.text })
        assertEquals(CoachPriority.HIGH, out.lines.single().priority)
        // Standing segments get the call to get out of the saddle.
        val standing = CoachScript(Random(1)).update(input(0, segment = 1, label = "Stehender Berg", cadence = 60))
        assertEquals("Jetzt: Stehender Berg – aus dem Sattel! 200 Watt, 60 Umdrehungen", standing.lines.single().text)
        // Free segments start a new sentence after the call too.
        val free = CoachScript(Random(1)).update(input(0, label = "Bergsprint", cue = "Bergsprint – schwerer Gang, aus dem Sattel!", erg = false))
        assertEquals("Jetzt: Bergsprint – aus dem Sattel! Frei, so hart du kannst, 70 Umdrehungen", free.lines.first().text)
    }

    @Test fun seatedStartsAndAnnouncementsAreNotStanding() {
        // The cue at the start of a jumps or serpentine block begins seated.
        val jumps = CoachScript(Random(1)).update(input(0, label = "Jumps", cue = "Jumps: 8 Sekunden sitzen, 8 Sekunden stehen"))
        assertEquals("Jetzt: Jumps, 200 Watt, 70 Umdrehungen", jumps.lines.first().text)
        val serp = CoachScript(Random(1)).update(input(0, label = "Serpentine 1/3", cue = "Serpentinen: zwei Minuten sitzen, eine Minute stehen"))
        assertEquals("Jetzt: Serpentine 1/3, 200 Watt, 70 Umdrehungen", serp.lines.first().text)

        for (announcement in listOf("Gleich raus aus dem Sattel", "Gleich aus dem Sattel", "Gleich der Gipfel – aus dem Sattel!", "Gleich die Kehre, dann aus dem Sattel")) {
            assertFalse(announcement, CoachScript.isStanding(announcement))
            assertFalse(announcement, CoachScript.isStanding("Berg 2", announcement))
        }
        for (sit in listOf("Setzen", "Bleib sitzen, der Gipfel kommt", "Widerstand rauf, bleib im Sattel", "Letzter Jump, dann setzen", "Der lange Anstieg – setz dich, find deinen Rhythmus")) {
            assertFalse(sit, CoachScript.isStanding(sit))
        }
        for (stand in listOf("Aus dem Sattel!", "Hoch!", "Letzte Minute – bleib stehen!", "Bergsprint – schwerer Gang, aus dem Sattel!", "Stehender Berg", "Kehre stehend 2/3")) {
            assertTrue(stand, CoachScript.isStanding(stand))
        }
        // The visible cue wins over the label when it calls for one or the other, otherwise the label decides.
        assertTrue(CoachScript.isStanding("Stehender Berg", null))
        assertTrue(CoachScript.isStanding("Stehender Berg", "Gewicht über die Pedale, Rücken gerade"))
        assertTrue(CoachScript.isStanding("Stehender Berg", "Gleich setzen"))
        assertFalse(CoachScript.isStanding("Stehender Berg", "Setzen, locker rollen"))
        assertTrue(CoachScript.isStanding("Gipfelangriff", "Letzte Minute – aus dem Sattel, zum Gipfel!"))
        assertFalse(CoachScript.isStanding("Sitzender Berg", null))
        assertFalse(CoachScript.isStanding("Flachstück", null))
    }

    @Test fun noPresetAnnouncementOrSeatedCueCountsAsStanding() {
        for (w in SpinningPresets.all) for (b in w.blocks) for (c in b.cues) {
            val t = c.text
            if (t.startsWith("Gleich") || t.contains("sitzen") || t.contains("im Sattel") || t.contains("Setzen")) {
                assertFalse("${w.name}: $t", CoachScript.isStanding(t))
            }
        }
    }

    @Test fun speaksIntervalCountsAsWordsButShowsThemAsWritten() {
        assertEquals("Sprint 1 von 4", CoachScript.spoken("Sprint 1/4"))
        assertEquals("Kehre stehend 3 von 8", CoachScript.spoken("Kehre stehend 3/8"))
        assertEquals("Flachstück", CoachScript.spoken("Flachstück"))
        val start = CoachScript(Random(1)).update(input(0, label = "Sprint 1/4", target = 300, cadence = 110)).lines.first()
        assertEquals("Jetzt: Sprint 1/4, 300 Watt, 110 Umdrehungen", start.text)
        assertEquals("Jetzt: Sprint 1 von 4, 300 Watt, 110 Umdrehungen", start.speech)
        // The countdown names the next interval the same way.
        val script = CoachScript(Random(1))
        val countdown = (0..35).flatMap { s -> script.update(input(s, duration = 60, target = 120, power = 120, next = "Sprint 2/4")).lines }
            .single { it.text.startsWith("Noch 30") }
        assertEquals("Noch 30 Sekunden, dann Sprint 2/4", countdown.text)
        assertEquals("Noch 30 Sekunden, dann Sprint 2 von 4", countdown.speech)
        // Lines without a label speak exactly what they show.
        val plain = CoachScript(Random(1)).update(input(0, cue = "Tabata: 20 Sekunden voll, 10 Sekunden locker")).lines
        assertTrue(plain.all { it.text == it.speech || it.priority == CoachPriority.HIGH })
    }

    @Test fun countsDownOnceAndSaysHalfTime() {
        val script = CoachScript(Random(1))
        val lines = run(script, 0..299) { s -> input(s, target = 120, power = 120) }
        assertEquals(1, lines.count { it == "Halbzeit" })
        assertEquals(1, lines.count { it.startsWith("Noch 30 Sekunden") })
        assertEquals(1, lines.count { it == "Noch 10 Sekunden" })
        assertTrue(lines.single { it.startsWith("Noch 30") }.endsWith("dann Erholung"))
        // Halftime comes before the countdown, which comes before the ten-second call.
        val half = lines.indexOf("Halbzeit")
        val thirty = lines.indexOfFirst { it.startsWith("Noch 30") }
        assertTrue(half in 1 until thirty && thirty < lines.indexOf("Noch 10 Sekunden"))
    }

    @Test fun motivatesEvery40To70SecondsOnlyWhenHard() {
        val script = CoachScript(Random(7))
        var lastAt = -1
        val gaps = mutableListOf<Int>()
        for (s in 0..599) {
            val out = script.update(input(s, duration = 600, target = 210, power = 210, next = null))
            if (out.lines.any { it.priority == CoachPriority.LOW && it.text in CoachScript.MOTIVATION }) {
                if (lastAt >= 0) gaps += s - lastAt
                lastAt = s
            }
        }
        assertTrue("expected several motivational lines, got ${gaps.size + 1}", gaps.size >= 6)
        assertTrue("gaps out of range: $gaps", gaps.all { it in 40..70 })

        // An easy segment never motivates.
        val easy = run(CoachScript(Random(7)), 0..599) { s -> input(s, duration = 600, target = 100, power = 100, next = null) }
        assertTrue(easy.none { it in CoachScript.MOTIVATION })
    }

    @Test fun warnsAfter20SecondsUnderTargetAndPraisesAfter60OnTarget() {
        val script = CoachScript(Random(1))
        val under = run(script, 0..30) { s -> input(s, duration = 600, target = 200, power = 150, next = null) }
        assertEquals(1, under.count { it == "Mehr Druck, du liegst unter dem Ziel" })
        // The line comes after 20 s under target, not before.
        val early = run(CoachScript(Random(1)), 0..18) { s -> input(s, duration = 600, target = 200, power = 150, next = null) }
        assertTrue(early.none { it.startsWith("Mehr Druck") })

        val praise = run(CoachScript(Random(1)), 0..65) { s -> input(s, duration = 600, target = 200, power = 203, next = null) }
        assertEquals(1, praise.count { it in CoachScript.PRAISE })
        val short = run(CoachScript(Random(1)), 0..50) { s -> input(s, duration = 600, target = 200, power = 203, next = null) }
        assertTrue(short.none { it in CoachScript.PRAISE })
    }

    @Test fun moodFollowsTargetAndSprints() {
        val script = CoachScript(Random(1))
        assertEquals(CoachOutput.MOOD_RECOVERY, script.update(input(0, target = 100)).mood)
        assertEquals(CoachOutput.MOOD_STEADY, script.update(input(1, target = 150)).mood)
        assertEquals(CoachOutput.MOOD_HARD, script.update(input(2, target = 200)).mood)
        assertEquals(CoachOutput.MOOD_SPRINT, script.update(input(3, target = 260)).mood)
        assertEquals(CoachOutput.MOOD_SPRINT, script.update(input(4, label = "Sprint 1/4", target = 180)).mood)
        assertEquals(CoachOutput.MOOD_SPRINT, script.update(input(5, label = "Frei", target = 150, erg = false)).mood)
        assertEquals(CoachOutput.MOOD_RECOVERY, script.update(input(6, finished = true)).mood)
        assertEquals(128, CoachScript.bpmFor(128))
        assertEquals(110, CoachScript.bpmFor(55))
        assertEquals(85, CoachScript.bpmFor(null))
    }

    @Test fun repeatsCuesOnceAndEndsTheClass() {
        val script = CoachScript(Random(1))
        val lines = run(script, 0..20) { s -> input(s, cue = if (s in 5..16) "Widerstand rauf, bleib im Sattel" else null) }
        assertEquals(1, lines.count { it == "Widerstand rauf, bleib im Sattel" })
        val end = script.update(input(21, finished = true))
        assertEquals("Geschafft! Ausrollen und trinken.", end.lines.single().text)
        assertTrue(script.update(input(22, finished = true)).lines.isEmpty())
        // Nothing is said while paused.
        assertTrue(CoachScript(Random(1)).update(input(0, paused = true)).lines.isEmpty())
    }

    @Test fun spinningPresetsAreWellFormed() {
        val ids = SpinningPresets.all.map { it.id }
        assertEquals(ids.toSet().size, ids.size)
        assertTrue(ids.all { it.startsWith("preset-spin-") })
        val expected = mapOf("preset-spin-45" to 45, "preset-spin-hiit-30" to 30, "preset-spin-berge-50" to 50, "preset-spin-endurance-60" to 60)
        for (w in SpinningPresets.all) {
            assertEquals(w.name, expected.getValue(w.id) * 60, w.durationSec())
            assertTrue(w.builtIn)
            assertTrue(w.description.contains("Spinning-Ansicht"))
            val blockIds = w.blocks.map { it.id }
            assertEquals(blockIds.toSet().size, blockIds.size)
            // Every cue lies inside its block and every block has a cadence target for the beat.
            for (b in w.blocks) {
                assertTrue("${w.name}: ${b.id}", b.cues.all { it.offsetSec in 0 until b.durationSec() })
                assertTrue("${w.name}: ${b.id} without cadence", b.cadence != null)
            }
            assertTrue(Timeline(w).segments.isNotEmpty())
        }
        // Block ids are unique across all spinning classes too, so copies into the library never clash.
        val allBlockIds = SpinningPresets.all.flatMap { w -> w.blocks.map { it.id } }
        assertEquals(allBlockIds.toSet().size, allBlockIds.size)
    }

    @Test fun postureCallsStayInForceAndOnlyTheWordGleichAnnounces() {
        // "Gleichmäßig" and "Gleichzeitig" are not announcements, "Gleich" is.
        assertTrue(CoachScript.isStanding("Berg", "Gleichmäßig im Stehen weitertreten"))
        assertTrue(CoachScript.isStanding("Berg", "Gleichzeitig aus dem Sattel und schwerer schalten"))
        assertFalse(CoachScript.isStanding("Berg", "Gleich aus dem Sattel!"))
        assertFalse(CoachScript.isStanding("Berg", "  Gleich, aus dem Sattel"))
        // The last call so far decides, also after its cue has left the screen.
        assertTrue(CoachScript.isStanding("Gipfelangriff", listOf("Schwerer schalten", "Letzte Minute – aus dem Sattel, zum Gipfel!")))
        assertFalse(CoachScript.isStanding("Stehender Berg", listOf("Setzen, Kraft sparen")))
        assertTrue(CoachScript.isStanding("Stehender Berg", emptyList()))
        assertFalse(CoachScript.isStanding("Gipfelangriff", emptyList()))
    }
}
