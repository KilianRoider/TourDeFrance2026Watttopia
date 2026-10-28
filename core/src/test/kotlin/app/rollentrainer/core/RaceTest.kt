package app.rollentrainer.core

import app.rollentrainer.core.race.Drafting
import app.rollentrainer.core.race.FinaleKind
import app.rollentrainer.core.race.Mode
import app.rollentrainer.core.race.RaceEngine
import app.rollentrainer.core.race.RaceEventKind
import app.rollentrainer.core.race.RacePhase
import app.rollentrainer.core.race.RacePhysics
import app.rollentrainer.core.race.RacePlan
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.RiderFrame
import app.rollentrainer.core.race.RiderRole
import app.rollentrainer.core.race.StageResult
import app.rollentrainer.core.race.Terrain
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.Stages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * The pro race mode. Stages are picked from [Stages] by type and terrain, never by number or distance,
 * so the tests keep working when the profiles are replaced by real ones.
 */
class RaceTest {

    @Test fun rosterIsTheTourField() {
        val r = Tour2026.roster
        assertEquals(23, r.teams.size)
        assertEquals(184, r.size)
        r.teams.indices.forEach { t -> assertEquals("team $t", 8, r.ofTeam(t).size) }
        assertEquals(r.size, r.riders.map { it.bib }.toSet().size)
        for (p in r.riders) {
            assertTrue(p.name, p.weightKg in 50.0..95.0)
            assertTrue(p.name, p.ftpWkg in 4.0..7.2)
            // A power curve falls with duration.
            assertTrue(p.name, p.peak20sWkg > p.peak1mWkg && p.peak1mWkg > p.peak5mWkg && p.peak5mWkg > p.peak20mWkg)
            assertTrue(p.name, p.criticalPowerW < p.ftpW * 1.05 && p.wPrimeJ > 8_000)
        }
        val pog = r.byName("Tadej Pogačar")!!
        assertEquals("UAE Team Emirates XRG", pog.team.name)
        assertTrue(r.riders.count { it.role == RiderRole.SPRINTER } >= 10)
    }

    @Test fun userPowerIsScaledToThePro() {
        val e = RaceEngine(flatStage, roster, pogacar.id, userFtpW = 200, seed = 3)
        assertEquals(pogacar.ftpW / 200.0, e.powerScale, 1e-9)
        val s = e.tick(1.0, 200)
        assertEquals(pogacar.ftpW, s.proPowerW)
    }

    @Test fun draftingCutsPowerByTheExpectedShare() {
        val v = 45 / 3.6
        val m = 75.0
        val cda = 0.30
        fun saving(reduction: Double) = 1 - RacePhysics.powerFor(v, 0.0, m, cda * (1 - reduction)) / RacePhysics.powerFor(v, 0.0, m, cda)
        val oneWheel = saving(Drafting.reduction(Drafting.wake(0.5, 0.05), 0.0, false))
        assertTrue("one wheel $oneWheel", oneWheel in 0.22..0.30)
        // Deep in the bunch: a wheel straight ahead and riders ahead and beside.
        var crowd = 0.0
        for (row in 1..4) for (col in -1..1) crowd += Drafting.crowdWeight(row * 2.0 - Drafting.BIKE_LENGTH_M, col * 0.95)
        val bunch = saving(Drafting.reduction(Drafting.wake(0.4, 0.0), crowd, true))
        assertTrue("bunch $bunch", bunch in 0.36..0.50)
        assertEquals(0.0, saving(Drafting.reduction(Drafting.wake(4.0, 0.0), 0.0, false)), 1e-9)
        assertEquals(0.0, saving(Drafting.reduction(Drafting.wake(0.5, 1.2), 0.0, false)), 1e-9)

        // In the simulated race the riders in the bunch save what the formula says, the man on the front nothing.
        val e = RaceEngine(flatStage.finale(25.0), roster, pogacar.id, 250, seed = 5)
        repeat(240) { e.tick(1.0, 190) }
        val f = e.field
        val inBunch = (0 until roster.size).filter { f.active(it) && !f.pacer[it] && f.grp[it] == f.grp[f.ord[90]] }
        val deep = inBunch.filter { f.d[f.ord[0]] - f.d[it] > 15 }.map { f.draft[it] }
        assertTrue(deep.size > 50)
        // The edges of the bunch catch some wind; the sheltered core (upper quartile) is what the
        // 40-50 % figure describes, and nobody may save more than the bunch maximum.
        val sorted = deep.sorted()
        val q75 = sorted[sorted.size * 3 / 4]
        assertTrue("upper quartile of the draft deep in the bunch $q75", q75 in 0.38..0.50)
        assertTrue("average draft deep in the bunch ${deep.average()}", deep.average() in 0.25..0.50)
        assertTrue("max draft ${sorted.last()}", sorted.last() <= 0.51)
        assertTrue((0 until roster.size).filter { f.pacer[it] && f.grp[it] == f.grp[f.ord[90]] }.all { f.draft[it] < 0.1 })
    }

    @Test fun soloUserRidesLikeRouteEngine() {
        val route = Stages.all.first { it.stage?.type == "mountain" }
        val solo = roster.only { it.name == pogacar.name }
        val pro = solo.riders[0]
        val race = RaceEngine(route, solo, 0, userFtpW = 250, seed = 1)
        val ref = RouteEngine(route, RiderParams(totalMassKg = pro.weightKg + RiderParams.BIKE_KG, cda = pro.cda))
        var a = race.state
        var b = ref.state
        for (t in 0 until 1800) {
            val user = if (t % 300 < 150) 210 else 260
            a = race.tick(1.0, user)
            b = ref.tick(1.0, (user * race.powerScale).toInt(), false)
        }
        assertTrue(b.distanceM > 10_000)
        assertEquals(b.distanceM, a.route.distanceM, b.distanceM * 0.005)
        assertEquals(b.elevationM, a.route.elevationM, 3.0)
        assertEquals(b.gradePct, a.route.gradePct, 0.3)
        assertEquals(0.0, a.draftReduction, 1e-9)
        assertEquals(1, a.rank)
    }

    @Test fun breakIsCaughtTenToTwentyKilometresOutOnAFlatStage() {
        val run = passiveFlat
        assertTrue("break of ${run.breakSize}", run.breakSize in 1..6)
        assertTrue("max gap ${run.maxBreakGapSec}", run.maxBreakGapSec >= 90)
        val caught = run.engine.caughtToGoM
        assertTrue("caught at $caught m to go", caught in 6_000.0..25_000.0)
        assertEquals(run.engine.plan.catchToGoM, caught, 6_000.0)
        assertTrue(run.events.any { it.kind == RaceEventKind.BREAKAWAY })
        assertTrue(run.events.any { it.kind == RaceEventKind.CATCH })
    }

    @Test fun aSprinterWinsTheFlatStageWhenTheUserIsPassive() {
        val result = passiveFlat.result
        val winner = roster.riders[result.winner!!.riderId]
        assertTrue("${winner.name} (${winner.role}) won", winner.sprints)
        // A bunch sprint: most of the field on the winner's time.
        val onTime = result.rows.count { it.gapSec < 1.0 }
        assertTrue("$onTime riders on the winner's time", onTime > 60)
        assertTrue(passiveFlat.events.any { it.kind == RaceEventKind.SPRINT })
        // The passive user did not finish.
        assertTrue(result.user == null || result.rows.last().isUser)
    }

    @Test fun mountainStageSplitsTheFieldByWattsPerKilo() {
        val route = mountainStage.finale(60.0)
        val e = RaceEngine(route, roster, pogacar.id, 250, seed = 2)
        val result = e.result()
        val rows = result.rows.filter { !it.isUser }
        assertTrue(rows.size >= 170)
        val wkg = rows.map { roster.riders[it.riderId].let { r -> r.criticalPowerW / r.weightKg } }
        val time = rows.map { it.timeSec }
        val rho = spearman(wkg, time)
        assertTrue("rank correlation of W/kg and time $rho", rho < -0.55)
        val top10 = wkg.take(10).average()
        assertTrue("top 10 at $top10 W/kg vs field ${wkg.average()}", top10 > wkg.average() + 0.4)
        // Several groups, minutes apart.
        val groups = rows.zipWithNext().count { (a, b) -> b.timeSec - a.timeSec > 20 }
        assertTrue("$groups splits", groups >= 4)
        assertTrue(rows.last().timeSec - rows.first().timeSec > 300)
        val winner = roster.riders[rows.first().riderId]
        assertTrue(winner.name, winner.role == RiderRole.GC || winner.role == RiderRole.CLIMBER || winner.role == RiderRole.PUNCHEUR)
    }

    @Test fun ridersNeverOverlap() {
        // An active user who moves around in the bunch and sprints, and the whole run-in.
        val e = RaceEngine(flatStage.finale(20.0), roster, sprinter.id, 250, seed = 4)
        val frame = RiderFrame(e.riderCount)
        val idx = IntArray(e.riderCount)
        var worst = Double.MAX_VALUE
        var t = 0
        while (!e.state.finished && t < 3 * 3600 * 4) {
            val power = when {
                e.state.kmToGo < 0.3 -> 600
                t % 1200 < 300 -> 200
                else -> 150
            }
            if (t % 240 == 60) e.moveLeft()
            if (t % 240 == 180) e.moveRight()
            e.tick(0.25, power)
            t++
            e.copyFrame(frame)
            val live = (0 until frame.size).filter { frame.flags[it] and RiderFrame.VISIBLE != 0 && frame.flags[it] and RiderFrame.FINISHED == 0 }
            var n = 0
            for (i in live) idx[n++] = i
            val sorted = idx.copyOf(n).sortedByDescending { frame.distanceM[it] }
            for (a in sorted.indices) {
                var b = a + 1
                while (b < sorted.size && frame.distanceM[sorted[a]] - frame.distanceM[sorted[b]] < 1.5) {
                    val dx = abs(frame.lateralM[sorted[a]] - frame.lateralM[sorted[b]]).toDouble()
                    worst = minOf(worst, dx)
                    assertTrue("riders ${sorted[a]} and ${sorted[b]} overlap at t=${frame.timeSec}: dx=$dx", dx >= 0.4)
                    b++
                }
            }
        }
        assertTrue(e.state.finished)
        println("closest side-by-side spacing within a bike length: %.2f m".format(worst))
    }

    @Test fun simulationIsFastEnough() {
        val e = RaceEngine(flatStage, roster, pogacar.id, 250, seed = 6)
        repeat(300) { e.tick(1.0, 180) }
        // 184 riders, ten ticks a second, ten seconds of race.
        val t0 = System.nanoTime()
        repeat(100) { e.tick(0.1, 180) }
        val tenHzMs = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        repeat(600) { e.tick(1.0, 180) }
        val perSimSecMs = (System.nanoTime() - t1) / 1e6 / 600
        println("race: ${e.riderCount} riders, 10 s at 10 Hz in %.1f ms; %.2f ms per simulated second".format(tenHzMs, perSimSecMs))
        assertTrue("10 s at 10 Hz took $tenHzMs ms", tenHzMs < 400)
    }

    @Test fun theSameSeedRidesTheSameRace() {
        fun race(seed: Long): Pair<RiderFrame, List<String>> {
            val e = RaceEngine(flatStage, roster, pogacar.id, 250, seed)
            val texts = mutableListOf<String>()
            for (t in 0 until 1500) {
                val s = e.tick(1.0, 150 + (t / 60 % 3) * 40)
                s.newEvents.forEach { texts += it.text }
            }
            return RiderFrame(e.riderCount).also { e.copyFrame(it) } to texts
        }
        val (a, ea) = race(11)
        val (b, eb) = race(11)
        val (c, _) = race(12)
        assertArrayEquals(a.distanceM, b.distanceM, 0.0)
        assertArrayEquals(a.lateralM, b.lateralM, 0f)
        assertEquals(ea, eb)
        assertFalse(a.distanceM.contentEquals(c.distanceM))
    }

    @Test fun timeTrialStartsAtIntervalsAndRanksByTime() {
        val route = Stages.all.first { it.stage?.type == "itt" }
        val e = RaceEngine(route, roster, pogacar.id, 250, seed = 1)
        // Riders who started before the user are already on the road.
        assertTrue(e.state.groups.size > 5)
        var s = e.state
        while (!s.finished && s.raceTimeSec < 4 * 3600) s = e.tick(1.0, 250)
        assertTrue(s.finished)
        val result = e.result()
        assertEquals(roster.size, result.rows.size)
        assertTrue(result.rows.zipWithNext().all { (a, b) -> a.timeSec <= b.timeSec })
        val top = result.rows.take(10).map { roster.riders[it.riderId].role }
        assertTrue(top.toString(), top.all { it == RiderRole.GC || it == RiderRole.TT || it == RiderRole.CLIMBER || it == RiderRole.ROULEUR || it == RiderRole.PUNCHEUR })
        assertTrue(s.recentEvents.isNotEmpty())
        assertNotNull(result.user)
    }

    @Test fun teamTimeTrialTeamRidesWithTheUser() {
        val route = Stages.all.first { it.stage?.type == "ttt" }
        val e = RaceEngine(route, roster, pogacar.id, 250, seed = 1)
        var s = e.state
        var together = 0
        var onFront = 0
        var samples = 0
        val f = e.field
        val u = pogacar.id
        while (!s.finished && s.raceTimeSec < 3 * 3600) {
            s = e.tick(1.0, 250)
            if (s.raceTimeSec > 60 && !s.finished) {
                samples++
                val mates = (0 until roster.size).filter { it != u && f.team[it] == f.team[u] && f.active(it) }
                if (mates.count { abs(f.d[it] - f.d[u]) < 20 } >= 4) together++
                if (mates.all { f.d[it] < f.d[u] }) onFront++
            }
        }
        assertTrue(s.finished)
        // The user holds one power on every ramp, so the line stretches now and then; it must close up again.
        assertTrue("together $together of $samples", together > samples * 0.8)
        // He takes turns: sometimes on the front, mostly in the line.
        assertTrue("on the front $onFront of $samples", onFront > samples * 0.1 && onFront < samples * 0.7)
        val result = e.result()
        val mine = result.rows.filter { roster.riders[it.riderId].team.index == pogacar.team.index }
        val user = result.user!!
        // Stage 1 ends up Montjuïc: a user holding his pro's CP drops some helpers on the last ramps,
        // the strongest stay with him.
        assertTrue(mine.joinToString { "${it.name} ${it.timeSec}" }, mine.count { !it.isUser && abs(it.timeSec - user.timeSec) < 15 } >= 3)
        // Every team finishes as a team: most riders within a few seconds of their team's best.
        val teamsTogether = roster.teams.indices.count { t ->
            val times = result.rows.filter { roster.riders[it.riderId].team.index == t }.map { it.timeSec }.sorted()
            times.size >= 5 && times[4] - times[0] < 10
        }
        assertTrue("$teamsTogether teams together", teamsTogether >= 18)
    }

    @Test fun teamTimeTrialTeamMatesAreNotRivals() {
        val route = Stages.all.first { it.stage?.type == "ttt" }
        // The user's team against the weakest one, which starts first and sets the times to beat.
        val weakest = roster.teams.indices.minBy { t -> roster.ofTeam(t).map { it.criticalPowerW }.average() }
        val two = roster.only { it.team.index == pogacar.team.index || it.team.index == weakest }
        val user = two.byName(pogacar.name)!!.id
        val e = RaceEngine(route, two, user, 250, seed = 1)
        val texts = mutableListOf<String>()
        var s = e.state
        while (!s.finished && s.raceTimeSec < 3 * 3600) {
            s = e.tick(1.0, 250)
            s.newEvents.forEach { texts += it.text }
        }
        assertTrue(s.finished)
        // Faster than the other team everywhere: best time at every split, never "behind" his own team.
        val splits = texts.filter { it.startsWith("Zwischenzeit") }
        assertTrue(splits.toString(), splits.isNotEmpty() && splits.all { it == "Zwischenzeit: Bestzeit!" })
        assertTrue(texts.toString(), "Ziel! Platz 1" in texts)
        val result = e.result()
        val mine = result.user!!
        assertEquals(1, mine.rank)
        // The team-mates who crossed the line with him (he drops some on the last ramps) share the stage win and its time.
        val winners = result.rows.filter { it.rank == 1 }
        assertTrue(winners.joinToString { "${it.name} ${it.timeSec}" }, winners.size >= 2)
        assertTrue(winners.all { two.riders[it.riderId].team.index == pogacar.team.index && it.timeSec == mine.timeSec && it.gapSec == 0.0 })
        assertTrue(result.rows.zipWithNext().all { (a, b) -> a.rank <= b.rank && a.timeSec <= b.timeSec })
    }

    @Test fun aFinaleCutAfterThePlannedCatchStartsNoNewBreak() {
        // Flat finales of 15 km whose plan has the break caught before the cut starts (gone long enough for
        // its full lead, nearly nothing left of it): the run-in starts with the bunch together.
        var checked = 0
        for (stage in Stages.all.filter { it.stage?.type == "flat" }) for (seed in 1L..3L) {
            if (checked >= 4) break
            val route = stage.finale(15.0)
            val e = RaceEngine(route, roster, pogacar.id, 230, seed)
            val plan = e.plan
            if (!plan.hasBreak || plan.breakAtM > -RacePlan.RAMP_M || plan.gapTargetSec(route.lengthM) >= 15) continue
            checked++
            val events = mutableListOf<app.rollentrainer.core.race.RaceEvent>()
            var s = e.state
            while (!s.finished && s.kmToGo > 9) {
                s = e.tick(1.0, 230)
                events += s.newEvents
                assertTrue("stage ${stage.stage?.number} seed $seed: ${s.phase}", s.phase != RacePhase.BREAK_FORMING)
            }
            val texts = events.map { it.text }
            assertTrue(texts.toString(), events.none { it.kind == RaceEventKind.ATTACK || it.kind == RaceEventKind.BREAKAWAY })
            assertTrue(texts.toString(), texts.none { it.startsWith("Du attackierst –") })
        }
        assertTrue("$checked finales checked", checked >= 2)
    }

    @Test fun aLateAttackerRidesAtHisOwnThreshold() {
        // Flat finales with a late attack planned: the attacker goes at 1.2 times his own critical power.
        // The user stays behind (a user at his threshold is off the front of a 15 km finale, and nobody attacks then).
        var checked = 0
        for (stage in Stages.all.filter { it.stage?.type == "flat" }) for (seed in 1L..4L) {
            if (checked >= 3) break
            val route = stage.finale(15.0)
            val e = RaceEngine(route, roster, pogacar.id, 250, seed)
            val toGo = e.plan.lateAttackToGoM
            if (toGo < 0) continue
            var s = e.state
            var attacker = -1
            while (!s.finished && attacker < 0 && route.lengthM - e.field.d[e.field.ord[0]] > toGo - 2_000) {
                s = e.tick(1.0, 0)
                attacker = s.newEvents.firstOrNull { it.kind == RaceEventKind.ATTACK && route.lengthM - it.distanceM <= toGo + 100 }?.riderIds?.first() ?: -1
            }
            if (attacker < 0) continue
            checked++
            val f = e.field
            assertEquals(roster.riders[attacker].name, 1.2 * f.cpEff(attacker), f.modePower[attacker], 0.5)
        }
        assertTrue("$checked late attacks checked", checked >= 2)
    }

    @Test fun aRiderPushedOverTheLineByTheUserFinishes() {
        val e = RaceEngine(flatStage.finale(5.0), roster, pogacar.id, 250, seed = 1)
        val f = e.field
        val u = pogacar.id
        val a = (0 until roster.size).first { it != u }
        for (i in 0 until roster.size) if (i != u && i != a) f.mode[i] = Mode.DONE
        // The user just before the line steers to the right edge, where the rider squeezed beside him has no room.
        val line = f.length
        f.d[u] = line - 1.0; f.x[u] = 2.55; f.vx[u] = 1.3; f.v[u] = 14.0
        f.d[a] = line - 2.0; f.x[a] = 3.15; f.v[a] = 14.0
        e.moveRight()
        e.tick(0.1, 400)
        assertTrue("pushed ahead to ${f.d[a] - line} m past the line", f.d[a] > line && f.finishAt[a].isNaN())
        e.tick(0.1, 400)
        assertEquals(Mode.DONE, f.mode[a])
        assertFalse(f.finishAt[a].isNaN())
        assertTrue(e.result().rows.any { it.riderId == a })
    }

    @Test fun aFinaleCutKeepsTheNamesOfItsClimbs() {
        val stage = Stages.all.filter { it.stage?.type == "mountain" }
            .first { r -> Terrain.climbs(r).any { it.name.isNotBlank() && it.topM > r.lengthM - 50_000 } }
        val cut = stage.finale(60.0)
        val offset = stage.lengthM - cut.lengthM
        val inCut = Terrain.climbs(cut)
        val named = Terrain.climbs(stage).filter { it.name.isNotBlank() && it.topM > offset + 5_000 }
        assertTrue(named.isNotEmpty())
        for (c in named) {
            val same = inCut.minBy { abs(it.topM + offset - c.topM) }
            assertEquals(c.topM, same.topM + offset, 500.0)
            assertEquals(c.name, same.name)
        }
    }

    @Test fun flatStageEventsAreGerman() {
        val texts = passiveFlat.events.map { it.text }
        assertTrue(texts.any { it.startsWith("Noch ") && it.endsWith(" km") })
        assertTrue(texts.any { it.startsWith("Flamme rouge") })
        assertTrue(texts.any { it.contains("gewinnt die Etappe") })
    }

    private class Run(val engine: RaceEngine, val result: StageResult, val events: List<app.rollentrainer.core.race.RaceEvent>, val maxBreakGapSec: Double, val breakSize: Int)

    companion object {
        private val roster = Tour2026.roster
        private val pogacar = roster.byName("Tadej Pogačar")!!
        private val sprinter = roster.riders.filter { it.role == RiderRole.SPRINTER }.maxBy { it.peakPowerW }

        /** The longest flat stage that ends in a bunch sprint. */
        private val flatStage: Route by lazy {
            Stages.all.filter { it.stage?.type == "flat" && Terrain.finale(it, Terrain.climbs(it)).first == FinaleKind.SPRINT && it.lengthKm > 120 }
                .maxBy { it.lengthM }
        }

        /** The mountain stage with the most climbing in its last 60 km. */
        private val mountainStage: Route by lazy {
            Stages.all.filter { it.stage?.type == "mountain" }.maxBy { r ->
                val cut = r.finale(60.0)
                cut.ascentUntil(cut.lengthM)
            }
        }

        /** One whole flat stage with a user who never pedals, shared by the tests that look at it. */
        private val passiveFlat: Run by lazy {
            val e = RaceEngine(flatStage, roster, pogacar.id, 250, seed = 1)
            val events = mutableListOf<app.rollentrainer.core.race.RaceEvent>()
            var maxGap = 0.0
            var breakSize = 0
            var s: RaceState = e.state
            while (s.raceTimeSec < 9 * 3600 && events.none { it.kind == RaceEventKind.FINISH }) {
                s = e.tick(10.0, 0)
                events += s.newEvents
                val g = s.groups
                if (g.size >= 2 && g[0].size <= 8 && g[1].size > 60) {
                    breakSize = max(breakSize, g[0].size)
                    maxGap = max(maxGap, g[1].gapSec - g[0].gapSec)
                }
            }
            val result = e.result()
            Run(e, result, events, maxGap, breakSize)
        }

        private fun spearman(a: List<Double>, b: List<Double>): Double {
            fun ranks(x: List<Double>): DoubleArray {
                val order = x.indices.sortedBy { x[it] }
                val r = DoubleArray(x.size)
                var i = 0
                while (i < order.size) {
                    var j = i
                    while (j + 1 < order.size && x[order[j + 1]] == x[order[i]]) j++
                    for (k in i..j) r[order[k]] = (i + j) / 2.0
                    i = j + 1
                }
                return r
            }
            val ra = ranks(a)
            val rb = ranks(b)
            val ma = ra.average()
            val mb = rb.average()
            var num = 0.0
            var da = 0.0
            var db = 0.0
            for (i in ra.indices) {
                num += (ra[i] - ma) * (rb[i] - mb)
                da += (ra[i] - ma) * (ra[i] - ma)
                db += (rb[i] - mb) * (rb[i] - mb)
            }
            return num / sqrt(da * db)
        }
    }
}
