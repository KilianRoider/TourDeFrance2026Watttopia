package app.rollentrainer.core

import app.rollentrainer.core.ble.Ftms
import app.rollentrainer.core.engine.EngineConfig
import app.rollentrainer.core.engine.ErgState
import app.rollentrainer.core.engine.WorkoutEngine
import app.rollentrainer.core.import.Mrc
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.metrics.LapBoundary
import app.rollentrainer.core.metrics.StarTolerance
import app.rollentrainer.core.metrics.WPrimeBalance
import app.rollentrainer.core.metrics.ZoneScheme
import app.rollentrainer.core.model.AppJson
import app.rollentrainer.core.model.Block
import app.rollentrainer.core.model.Power
import app.rollentrainer.core.model.Presets
import app.rollentrainer.core.model.Sample
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.zwo.Zwo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeaturesTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val w = Workout(
        "w", "Frei",
        blocks = listOf(
            Block.Steady("a", 60, Power.pct(50), "Einfahren"),
            Block.FreeRide("b", 30, "Sprint", Power.pct(200)),
            Block.Steady("c", 60, Power.pct(100), "Schwelle"),
        ),
    )

    @Test fun freeRideSegmentsHaveErgOff() {
        val tl = Timeline(w)
        assertTrue(tl.segments[0].erg)
        assertFalse(tl.segments[1].erg)
        val back = Zwo.parse(Zwo.write(w, 200).byteInputStream())
        assertTrue(back.blocks[1] is Block.FreeRide)
        assertEquals(30, back.blocks[1].durationSec())
        val json = AppJson.encodeToString(Workout.serializer(), w)
        assertTrue(json.contains("\"type\":\"free\""))
        assertEquals(w, AppJson.decodeFromString(Workout.serializer(), json))
    }

    @Test fun engineSendsNoCommandInFreeRideAndSwitchesEarly() {
        val e = WorkoutEngine(Timeline(w), EngineConfig(ftp = 200, spiralRpm = 0))
        repeat(57) { e.tick(1.0, 100, 90) }
        assertEquals(100, e.state.targetWatts)
        assertEquals(100, e.state.commandWatts)
        e.tick(1.0, 100, 90) // 58 s: 2 s before the free segment → no early target because next is not ERG
        assertEquals(100, e.state.commandWatts)
        repeat(3) { e.tick(1.0, 100, 90) }
        assertEquals(1, e.state.segmentIndex)
        assertFalse(e.state.erg)
        assertNull(e.state.commandWatts)
        assertEquals("Schwelle", e.state.nextSegment?.label)
        repeat(28) { e.tick(1.0, 300, 100) }
        // 2 s before the threshold segment the next target (200 W) is already commanded? Not in free ride: still null.
        assertNull(e.state.commandWatts)
        repeat(2) { e.tick(1.0, 300, 100) }
        assertEquals(2, e.state.segmentIndex)
        assertEquals(200, e.state.commandWatts)
    }

    @Test fun earlyTargetBeforeErgSegment() {
        val tl = Timeline(Workout("x", "x", blocks = listOf(Block.Steady("a", 60, Power.pct(50)), Block.Steady("b", 60, Power.pct(100)))))
        val e = WorkoutEngine(tl, EngineConfig(ftp = 200, spiralRpm = 0))
        repeat(57) { e.tick(1.0, 100, 90) }
        assertEquals(100, e.state.commandWatts)
        e.tick(1.0, 100, 90)
        assertEquals(200, e.state.commandWatts)
        assertEquals(100, e.state.targetWatts)
        assertEquals(200, e.state.nextTargetWatts)
    }

    @Test fun ergReleaseAndReengage() {
        val tl = Timeline(Workout("x", "x", blocks = listOf(Block.Steady("a", 600, Power.pct(100)))))
        val e = WorkoutEngine(tl, EngineConfig(ftp = 200, spiralRpm = 50))
        repeat(10) { e.tick(1.0, 200, 90) }
        assertEquals(ErgState.NORMAL, e.state.ergState)
        repeat(3) { e.tick(1.0, 150, 40) }
        assertEquals(ErgState.RELEASED, e.state.ergState)
        assertEquals(80, e.state.commandWatts)
        repeat(2) { e.tick(1.0, 100, 80) }
        assertEquals(ErgState.RE_ENGAGING, e.state.ergState)
        assertTrue(e.state.commandWatts!! in 80..200)
        repeat(3) { e.tick(1.0, 150, 85) }
        assertEquals(ErgState.NORMAL, e.state.ergState)
        assertEquals(200, e.state.commandWatts)
        // Manual re-engage jumps straight back.
        repeat(3) { e.tick(1.0, 150, 40) }
        assertEquals(ErgState.RELEASED, e.state.ergState)
        e.reengage()
        assertEquals(200, e.state.commandWatts)
    }

    @Test fun extendBackAndContinue() {
        val tl = Timeline(Workout("x", "x", blocks = listOf(Block.Steady("a", 60, Power.pct(50)), Block.Steady("b", 60, Power.pct(100)))))
        val e = WorkoutEngine(tl, EngineConfig(ftp = 200, spiralRpm = 0))
        repeat(10) { e.tick(1.0, 100, 90) }
        e.extendCurrent(60)
        assertEquals(180, e.state.totalSec)
        assertEquals(110, e.state.segmentRemainingSec)
        assertEquals(120, e.timeline!!.segments[1].startSec)
        e.extendCurrent(-200) // never below 5 s
        assertEquals(5, e.timeline!!.segments[0].durationSec)
        assertEquals(1, e.state.segmentIndex) // 10 s in, the first segment is already over
        repeat(10) { e.tick(1.0, 100, 90) }
        e.back() // 15 s into segment 1 → restart it
        assertEquals(1, e.state.segmentIndex)
        assertEquals(5.0, e.state.workoutSec, 0.0)
        repeat(2) { e.tick(1.0, 100, 90) }
        e.back() // less than 5 s in → previous segment
        assertEquals(0, e.state.segmentIndex)
        assertEquals(0.0, e.state.workoutSec, 0.0)
        repeat(65) { e.tick(1.0, 100, 90) }
        assertTrue(e.state.finished)
        e.continueRiding(120)
        assertFalse(e.state.finished)
        assertTrue(e.state.continuing)
        assertEquals(120, e.state.targetWatts)
        e.tick(1.0, 100, 90)
        assertEquals(66.0, e.state.workoutSec, 0.0)
        e.adjustIntensity(1)
        assertEquals(1, 1)
    }

    @Test fun bailJumpsToCoolDown() {
        val ramp = Presets.byId("preset-ramp-test")!!
        val e = WorkoutEngine(Timeline(ramp), EngineConfig(ftp = 200, spiralRpm = 0))
        repeat(600) { e.tick(1.0, 150, 90) }
        e.bail()
        assertEquals("Ausfahren", e.state.segmentLabel)
        assertFalse(e.state.finished)
    }

    @Test fun ftmsSimulationAndResistance() {
        assertArrayEquals(bytes(0x04, 0x32), Ftms.setTargetResistance(5.0))
        // grade 2.5 % = 250, crr 0.004 = 40, cw 0.51 = 51
        assertArrayEquals(bytes(0x11, 0x00, 0x00, 0xFA, 0x00, 40, 51), Ftms.setSimulation(2.5))
        assertArrayEquals(bytes(0x11, 0x00, 0x00, 0x9C, 0xFF, 40, 51), Ftms.setSimulation(-1.0))
    }

    @Test fun mrcAndErgImport() {
        val mrc = """
            [COURSE HEADER]
            VERSION = 2
            UNITS = ENGLISH
            DESCRIPTION = Test workout
            FILE NAME = Test.mrc
            MINUTES PERCENT
            [END COURSE HEADER]
            [COURSE DATA]
            0.00	50
            5.00	75
            5.00	100
            8.00	100
            8.00	50
            10.00	50
            10.00	100
            13.00	100
            13.00	50
            15.00	50
            15.00	100
            18.00	100
            18.00	50
            20.00	50
            20.00	60
            25.00	40
            [END COURSE DATA]
            [COURSE TEXT]
            0	Los geht's	10
            [END COURSE TEXT]
        """.trimIndent()
        val w = Mrc.parse(mrc, "Test.mrc")
        assertEquals("Test", w.name)
        assertEquals("Test workout", w.description)
        assertEquals(25 * 60, w.durationSec())
        assertTrue(w.blocks[0] is Block.Ramp)
        assertEquals("Los geht's", w.blocks[0].cues.single().text)
        val iv = w.blocks[1] as Block.Intervals
        assertEquals(3, iv.repeat)
        assertEquals(180, iv.onSec)
        assertEquals(Power.pct(100), iv.onPower)
        assertEquals(120, iv.offSec)
        assertTrue(w.blocks[2] is Block.Ramp)
        assertEquals(3, w.blocks.size)

        val erg = """
            [COURSE HEADER]
            FTP = 250
            MINUTES WATTS
            [END COURSE HEADER]
            [COURSE DATA]
            0 100
            2 100
            2 250
            4 250
            [END COURSE DATA]
        """.trimIndent()
        val w2 = Mrc.parse(erg, "ride.erg")
        assertEquals(Power.watts(100), (w2.blocks[0] as Block.Steady).power)
        assertEquals(250, Timeline(w2).targetWattsAt(150.0, 200))
    }

    @Test fun zoneSchemes() {
        assertEquals(6, ZoneScheme.ZWIFT.count)
        assertEquals(7, ZoneScheme.COGGAN.count)
        assertEquals(3, ZoneScheme.ZWIFT.indexOf(100.0))
        assertEquals(5, ZoneScheme.ZWIFT.indexOf(150.0))
        assertEquals(6, ZoneScheme.COGGAN.indexOf(151.0))
        val t = ZoneScheme.COGGAN.timeInZones(listOf(100, 200, 300), 200)
        assertEquals(3, t.sum())
    }

    @Test fun wPrimeBalanceDrainsAndRecovers() {
        val b = WPrimeBalance(cp = 250, wPrimeJ = 20_000)
        repeat(60) { b.update(350) }
        assertEquals(14_000.0, b.balanceJ, 1.0)
        assertEquals(70, b.percent)
        repeat(600) { b.update(100) }
        assertTrue(b.balanceJ > 19_000)
        val series = Analysis.wBalSeries(List(10) { Sample(it, 350) }, 250)
        assertEquals(10, series.size)
        assertEquals(19_000.0, series[9], 1.0)
    }

    @Test fun fairerStarsAndBests() {
        fun s(n: Int, p: Int, t: Int?, off: Int) = List(n) { Sample(off + it, p, t, 140, 90) }
        // Lap 1: trainer needs 5 s to settle (0 W), then exact. Lap 2: 20 s only → unrated. Lap 3: free ride.
        val samples = s(5, 0, 200, 0) + s(55, 200, 200, 5) + s(20, 300, 300, 60) + s(60, 400, 200, 80)
        val laps = Analysis.lapsOf(
            samples,
            listOf(LapBoundary(0, "A"), LapBoundary(60, "B"), LapBoundary(80, "Frei", rated = false)),
        )
        assertEquals(3, laps.size)
        assertEquals(3, laps[0].stars)
        assertEquals(100, laps[0].accuracyPct)
        assertFalse(laps[1].rated)
        assertEquals(0, laps[1].stars)
        assertFalse(laps[2].rated)
        // Tolerance: 8 % off is 2 stars normally, 3 stars relaxed, 1 star strict.
        val off = Analysis.lapsOf(s(60, 184, 200, 0), listOf(LapBoundary(0, "C"))).single()
        assertEquals(92, off.accuracyPct)
        assertEquals(2, off.stars(StarTolerance.NORMAL))
        assertEquals(3, off.stars(StarTolerance.RELAXED))
        assertEquals(1, off.stars(StarTolerance.STRICT))
        // Low target: 8 W off a 60 W target is 13 % but still three stars.
        val low = Analysis.lapsOf(s(60, 68, 60, 0), listOf(LapBoundary(0, "D"))).single()
        assertEquals(3, low.stars)
        val bests = Analysis.bests(samples)
        assertEquals(400, bests[5])
        assertEquals(400, bests[60])
        assertNull(bests[300])
    }

    @Test fun presetsIncludeTestsAndFreeRides() {
        assertTrue(Presets.all.size >= 15)
        assertEquals(TestKind.RAMP, Presets.byId("preset-ramp-test")!!.test)
        assertEquals(TestKind.TWENTY_MIN, Presets.byId("preset-20min-test")!!.test)
        assertTrue(Presets.byId("preset-sprints")!!.blocks.any { it is Block.FreeRide })
        assertEquals(Presets.all.size, Presets.all.map { it.id }.toSet().size)
        assertEquals(90, Presets.byId("preset-sweetspot")!!.dominantPercent(200))
    }
}
