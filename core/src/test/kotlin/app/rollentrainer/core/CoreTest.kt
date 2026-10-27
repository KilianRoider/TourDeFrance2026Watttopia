package app.rollentrainer.core

import app.rollentrainer.core.ble.CrankCadence
import app.rollentrainer.core.ble.Csc
import app.rollentrainer.core.ble.CyclingPower
import app.rollentrainer.core.ble.Ftms
import app.rollentrainer.core.ble.HeartRate
import app.rollentrainer.core.engine.WorkoutEngine
import app.rollentrainer.core.metrics.PowerZone
import app.rollentrainer.core.metrics.Stats
import app.rollentrainer.core.model.AppJson
import app.rollentrainer.core.model.Block
import app.rollentrainer.core.model.Power
import app.rollentrainer.core.model.Presets
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.zwo.Zwo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class CoreTest {
    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    private val sample = Workout(
        id = "w", name = "Test",
        blocks = listOf(
            Block.Ramp("a", 100, Power.pct(50), Power.pct(100)),
            Block.Intervals("b", 2, 30, Power.watts(300), 10, Power.pct(50)),
            Block.Steady("c", 60, Power.pct(75)),
        ),
    )

    @Test fun timelineFlattensBlocks() {
        val tl = Timeline(sample)
        assertEquals(100 + 2 * 40 + 60, tl.totalSec)
        assertEquals(6, tl.segments.size)
        assertEquals(100, tl.targetWattsAt(0.0, 200))
        assertEquals(150, tl.targetWattsAt(50.0, 200))
        assertEquals(300, tl.targetWattsAt(100.0, 200))
        assertEquals(100, tl.targetWattsAt(135.0, 200))
        assertEquals(150, tl.targetWattsAt(239.0, 200))
        assertNull(tl.targetWattsAt(240.0, 200))
        assertEquals(-1, tl.segmentIndexAt(240.0))
    }

    @Test fun engineWaitsForPedalingAndAutoPauses() {
        val e = WorkoutEngine(Timeline(sample), ftp = 200)
        e.tick(1.0, pedaling = false)
        assertEquals(0.0, e.state.workoutSec, 0.0)
        assertTrue(e.state.paused)
        repeat(10) { e.tick(1.0, pedaling = true) }
        assertEquals(10.0, e.state.workoutSec, 0.0)
        repeat(5) { e.tick(1.0, pedaling = false) }
        // Keeps counting until the auto-pause threshold, then stops.
        assertEquals(12.0, e.state.workoutSec, 0.0)
        assertTrue(e.state.autoPaused)
        e.tick(1.0, pedaling = true)
        assertFalse(e.state.paused)
    }

    @Test fun engineSkipAndIntensity() {
        val e = WorkoutEngine(Timeline(sample), ftp = 200)
        e.tick(1.0, true)
        e.skip()
        assertEquals(100.0, e.state.workoutSec, 0.0)
        assertEquals(300, e.state.targetWatts)
        e.adjustIntensity(10)
        assertEquals(330, e.state.targetWatts)
        repeat(6) { e.skip() }
        assertTrue(e.state.finished)
        assertNull(e.state.targetWatts)
    }

    @Test fun ftmsCommands() {
        assertArrayEquals(bytes(0x05, 0xFA, 0x00), Ftms.setTargetPower(250))
        assertArrayEquals(bytes(0x05, 0x2C, 0x01), Ftms.setTargetPower(300))
        assertEquals(0x05 to 0x01, Ftms.parseControlResponse(bytes(0x80, 0x05, 0x01)))
    }

    @Test fun parsesIndoorBikeData() {
        // flags: speed present (bit0 = 0), cadence (bit2), power (bit6), heart rate (bit9)
        val flags = (1 shl 2) or (1 shl 6) or (1 shl 9)
        val d = Ftms.parseIndoorBikeData(
            bytes(flags and 0xFF, flags shr 8, 0xC4, 0x09, 0xB4, 0x00, 0xC8, 0x00, 0x8C)
        )!!
        assertEquals(25.0, d.speedKmh!!, 0.001)
        assertEquals(90.0, d.cadenceRpm!!, 0.001)
        assertEquals(200, d.powerW)
        assertEquals(140, d.heartRate)
    }

    @Test fun parsesIndoorBikeDataWithMoreDataFlag() {
        // "More data" set: no speed, only power.
        val flags = 0x01 or (1 shl 6)
        val d = Ftms.parseIndoorBikeData(bytes(flags, 0, 0x64, 0x00))!!
        assertNull(d.speedKmh)
        assertEquals(100, d.powerW)
    }

    @Test fun parsesHeartRate() {
        assertEquals(72, HeartRate.parse(bytes(0x00, 72)))
        assertEquals(300, HeartRate.parse(bytes(0x01, 0x2C, 0x01)))
    }

    @Test fun cadenceFromCrankEvents() {
        val c = CrankCadence()
        c.update(10, 0, 0)
        // 1 revolution in 0.667 s = 90 rpm
        val rpm = c.update(11, 683, 1000)
        assertEquals(90.0, rpm, 0.5)
        // Rollover of both counters still works.
        val c2 = CrankCadence()
        c2.update(0xFFFF, 0xFF00, 0)
        assertEquals(60.0, c2.update(0, 0xFF00 + 1024 - 0x10000, 1000), 0.5)
        // No new events for longer than the timeout → 0
        assertEquals(0.0, c.current(5000), 0.0)
    }

    @Test fun parsesCscAndPower() {
        assertEquals(5 to 1024, Csc.parseCrank(bytes(0x02, 5, 0, 0x00, 0x04)))
        assertEquals(3 to 2, Csc.parseCrank(bytes(0x03, 1, 0, 0, 0, 0, 0, 3, 0, 2, 0)))
        val p = CyclingPower.parse(bytes(0x20, 0x00, 0xF4, 0x01, 7, 0, 0, 8))!!
        assertEquals(500, p.powerW)
        assertEquals(7 to 2048, p.crank)
    }

    @Test fun normalizedPowerOfSteadyEqualsAverage() {
        assertEquals(200.0, Stats.normalizedPower(DoubleArray(600) { 200.0 }), 0.001)
        // One hour at FTP is 100 TSS.
        assertEquals(100.0, Stats.tss(3600, 250.0, 250), 0.001)
        assertEquals(PowerZone.Z4, PowerZone.of(100.0))
        assertEquals(PowerZone.Z1, PowerZone.of(40.0))
    }

    @Test fun workoutJsonRoundTrip() {
        val json = AppJson.encodeToString(Workout.serializer(), sample)
        assertTrue(json.contains("\"type\":\"intervals\""))
        assertEquals(sample, AppJson.decodeFromString(Workout.serializer(), json))
    }

    @Test fun zwoRoundTrip() {
        val xml = Zwo.write(sample, ftp = 200)
        val back = Zwo.parse(xml.byteInputStream())
        assertEquals(sample.durationSec(), back.durationSec())
        val tl = Timeline(back)
        assertEquals(300, tl.targetWattsAt(100.0, 200))
    }

    @Test fun presetsAreValid() {
        for (w in Presets.all) {
            assertTrue(w.name, w.durationSec() > 0)
            val ids = w.blocks.map { it.id }
            assertEquals(ids.size, ids.toSet().size)
        }
    }
}

class LiveStatsTest {
    @Test fun matchesBatchStatistics() {
        val stats = app.rollentrainer.core.metrics.LiveStats(ftp = 250, maxHr = 185)
        val powers = (0 until 900).map { 150 + (it % 60) * 3 }
        powers.forEachIndexed { i, p -> stats.add(p, 250, 120 + i % 40, 85 + i % 10, 30.0) }
        val s = stats.snapshot()
        val batch = app.rollentrainer.core.metrics.Stats.normalizedPower(powers.map { it.toDouble() }.toDoubleArray())
        // Incremental NP only counts full 30 s windows, so it stays within a watt of the batch value.
        assertEquals(batch.toDouble(), s.normalizedPower.toDouble(), 1.5)
        assertEquals(powers.average().roundToInt(), s.avgPower)
        assertEquals(327, s.maxPower)
        assertEquals(900, s.seconds)
        assertEquals(139, s.avgHr)
        assertEquals(7500.0, s.distanceM, 0.5)
        assertEquals(900, s.zoneSeconds.sum())
        assertEquals(900, s.hrZoneSeconds.sum())
        assertEquals(powers.takeLast(10).average().roundToInt(), s.power10s)
    }

    @Test fun lapResets() {
        val stats = app.rollentrainer.core.metrics.LiveStats(ftp = 250, maxHr = 185)
        repeat(60) { stats.add(100, 100, null, null, null) }
        stats.newLap()
        repeat(30) { stats.add(300, 300, 150, 90, null) }
        val s = stats.snapshot()
        assertEquals(30, s.lapSeconds)
        assertEquals(300, s.lapAvgPower)
        assertEquals(150, s.lapAvgHr)
        assertEquals(100, s.lapAccuracyPct)
        assertEquals(167, s.avgPower)
    }
}
