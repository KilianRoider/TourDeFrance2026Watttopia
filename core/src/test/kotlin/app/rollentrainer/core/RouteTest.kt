package app.rollentrainer.core

import app.rollentrainer.core.route.Biome
import app.rollentrainer.core.route.Drivetrain
import app.rollentrainer.core.route.Gearing
import app.rollentrainer.core.route.VirtualGears
import app.rollentrainer.core.route.Landmark
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.RouteKind
import app.rollentrainer.core.route.RoutePoint
import app.rollentrainer.core.route.StageInfo
import app.rollentrainer.core.route.Stages
import app.rollentrainer.core.route.World
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RouteTest {
    private val climb = Route.fromKmGradients(
        "t-climb", "Testberg", "Alpen", "", RouteKind.CLIMB, 700.0, 5.5,
        listOf(8.0, 9.0, 7.0, 10.0, 6.0, 8.0), listOf(Landmark(2000.0, "Dorf")), hairpins = 5,
    )

    @Test fun profileFromKmGradients() {
        assertEquals(5500.0, climb.lengthM, 0.001)
        // 5 full km plus a 500 m rest at 8 %.
        assertEquals(700 + 80 + 90 + 70 + 100 + 60 + 40.0, climb.summitElevationM, 0.01)
        assertEquals(9.0, climb.gradientAt(1500.0), 0.001)
        assertEquals(700 + 80 + 45.0, climb.elevationAt(1500.0), 0.01)
        assertEquals(440.0, climb.ascentM, 0.01)
        assertEquals(10.0, climb.maxGradientPct, 0.001)
        assertEquals("Dorf", climb.nextLandmark(1000.0)?.label)
        val half = climb.shortened(0.5)
        assertEquals(2750.0, half.lengthM, 0.001)
        assertEquals(8.0, half.gradientAt(100.0), 0.001)
        assertEquals(0.5, half.fraction, 0.001)
        assertEquals(1, half.landmarks.size)
        assertTrue(climb.shortened(0.25).landmarks.isEmpty())
    }

    @Test fun physicsIsPlausible() {
        val p = RiderParams.forRider(75.0)
        // 250 W on the flat is a bit over 40 km/h; on 8 % it is about 11 km/h.
        assertEquals(37.0, Physics.kmh(Physics.steadySpeed(250.0, 0.0, p)), 3.0)
        assertEquals(11.0, Physics.kmh(Physics.steadySpeed(250.0, 8.0, p)), 1.5)
        // Inertia: after one second from standstill the rider has not reached the steady speed.
        val v1 = Physics.step(0.0, 250.0, 0.0, 1.0, p)
        assertTrue(v1 > 1.0 && v1 < Physics.steadySpeed(250.0, 0.0, p))
        // Coasting downhill accelerates, uphill stops.
        assertTrue(Physics.step(5.0, 0.0, -6.0, 2.0, p) > 5.0)
        assertEquals(0.0, Physics.step(0.5, 0.0, 8.0, 3.0, p), 0.001)
        assertTrue(Physics.estimateSeconds(climb, 250.0, p) in 1500..2400)
        assertTrue(Physics.vam(3.0, 8.0) in 850.0..880.0)
    }

    @Test fun engineMovesRiderAndPacer() {
        val e = RouteEngine(climb, RiderParams.forRider(75.0), listOf(Pacer("Pacer", 300)))
        repeat(60) { e.tick(1.0, 250, false) }
        val s = e.state
        assertTrue(s.distanceM > 100 && s.distanceM < 300)
        assertEquals(8.0, s.gradePct, 0.001)
        assertTrue(s.pacers[0].gapM > 0)
        assertFalse(s.finished)
        // Paused: nobody moves.
        val before = e.tick(1.0, 250, true).distanceM
        assertEquals(before, e.state.distanceM, 0.0)
        // Ride to the end.
        repeat(5000) { e.tick(1.0, 300, false) }
        assertTrue(e.state.finished)
        assertEquals(climb.lengthM, e.state.distanceM, 0.001)
        assertTrue(e.state.pacers[0].finished)
    }

    @Test fun worldFollowsTheProfile() {
        val w = World(climb, Biome.ALPINE)
        assertEquals((climb.lengthM / World.STEP).toInt() + 1, w.path.size)
        // The road keeps the real elevation and its length.
        val pose = w.poseAt(1500.0)
        assertEquals(climb.elevationAt(1500.0), pose.y, 0.01)
        var length = 0.0
        w.path.zipWithNext { a, b -> length += Math.hypot(b.x - a.x, b.z - a.z) }
        assertEquals(climb.lengthM, length, climb.lengthM * 0.01)
        // Five hairpins fold the road: the horizontal footprint is much shorter than the road.
        val spanX = w.path.maxOf { it.x } - w.path.minOf { it.x }
        val spanZ = w.path.maxOf { it.z } - w.path.minOf { it.z }
        assertTrue(spanX < climb.lengthM / 3)
        assertTrue(spanZ < climb.lengthM)
        // Terrain is finite and the road shoulder sits on the road.
        val t = w.terrain
        assertTrue(t.heights.all { it.isFinite() })
        val c = ((pose.x - t.originX) / t.cell).toInt()
        val r = ((pose.z - t.originZ) / t.cell).toInt()
        assertTrue(abs(t.heights[t.index(c, r)] - pose.y) < 12.0)
        assertTrue(w.props.count { it.kind == app.rollentrainer.core.route.Prop.CONIFER || it.kind == app.rollentrainer.core.route.Prop.BROADLEAF } > 50)
        assertTrue(w.signs.any { it.text == "Dorf" })
        assertTrue(w.signs.count { it.text.startsWith("Kehre") } == 5)
        assertTrue(w.props.any { it.kind == app.rollentrainer.core.route.Prop.SPECTATOR })
    }

    @Test fun builtInRoutesBuildSaneWorlds() {
        assertTrue(app.rollentrainer.core.route.Routes.all.size >= 10)
        for (r in app.rollentrainer.core.route.Routes.all) {
            assertTrue(r.name, r.lengthM > 1500)
            assertTrue(r.name, r.points.all { it.elevationM.isFinite() })
            assertTrue(r.name, r.maxGradientPct < 25)
            val w = World(r, r.biome)
            assertTrue(r.name, w.terrain.heights.all { it.isFinite() })
            // The footprint stays within a few kilometres so the terrain grid stays small.
            val spanX = w.path.maxOf { it.x } - w.path.minOf { it.x }
            val spanZ = w.path.maxOf { it.z } - w.path.minOf { it.z }
            assertTrue("${r.name} $spanX x $spanZ", spanX < 9000 && spanZ < 14000)
            assertTrue(r.name, w.terrain.cols * w.terrain.rows < 150_000)
            assertTrue(r.name, w.props.size < 60_000)
            assertTrue(r.name, w.signs.isNotEmpty())
            // Every hairpin sign stands where the road really turns, and flat routes have none.
            val kehren = w.signs.filter { Regex("Kehre \\d+").matches(it.text) }
            assertEquals(r.name, if (w.hillside > 0.0001) r.hairpins else 0, kehren.size)
            for (k in kehren) {
                val near = w.path.minByOrNull { (it.x - k.x) * (it.x - k.x) + (it.z - k.z) * (it.z - k.z) }!!
                val before = w.poseAt(near.d - 60).heading
                val after = w.poseAt(near.d + 80).heading
                var turn = abs(after - before) % (2 * Math.PI)
                if (turn > Math.PI) turn = 2 * Math.PI - turn
                assertTrue("${r.name} ${k.text} turn=$turn", turn > 0.8)
            }
        }
    }

    @Test fun slowClimbingStillMovesAndTiesGoToTheRider() {
        // 100 kg rider at 90 W on 12.5 %: slow, but moving, and it matches the steady-state estimate.
        val p = RiderParams.forRider(100.0)
        var v = 0.0
        repeat(600) { v = Physics.step(v, 90.0, 12.5, 1.0, p) }
        val steady = Physics.steadySpeed(90.0, 12.5, p)
        assertTrue("v=$v steady=$steady", v > 0.5 && abs(v - steady) < 0.05)
        // Starting from standstill is brisk but not instant.
        assertTrue(Physics.step(0.0, 400.0, 0.0, 0.1, p) < 1.0)
        // Rider and pacer at the same power reach the top in the same tick: the rider is not beaten.
        val short = Route.fromKmGradients("t-tie", "Tie", "", "", RouteKind.CLIMB, 100.0, 1.0, listOf(8.0))
        val e = RouteEngine(short, p, listOf(Pacer("Pacer", 250)))
        var st = e.tick(1.0, 250, false)
        while (!st.finished) st = e.tick(1.0, 250, false)
        val pacer = st.pacers.first()
        assertTrue(pacer.finished)
        assertFalse(st.beatenBy(pacer))
        assertTrue(st.finishTimeSec!! <= st.elapsedSec && st.finishTimeSec!! > st.elapsedSec - 1.0)
        // A stronger pacer wins, with its own exact time.
        val e2 = RouteEngine(short, p, listOf(Pacer("Stark", 400)))
        var s2 = e2.tick(1.0, 250, false)
        while (!s2.finished) s2 = e2.tick(1.0, 250, false)
        assertTrue(s2.beatenBy(s2.pacers.first()))
    }

    @Test fun pauseStopsTheRiderInsteadOfFreezingTheSpeed() {
        val flat = Route.fromKmGradients("t-p", "Flach", "", "", RouteKind.FLAT, 100.0, 3.0, listOf(0.0, 0.0, 0.0))
        val e = RouteEngine(flat, RiderParams.forRider(80.0))
        repeat(60) { e.tick(1.0, 250, false) }
        assertTrue(e.state.speedMps > 7.0)
        val at = e.state.distanceM
        repeat(30) { e.tick(1.0, null, true) }
        assertEquals(0.0, e.state.speedMps, 0.0)
        assertEquals(at, e.state.distanceM, 0.0)
        // After the break the rider starts from a standstill.
        e.tick(1.0, 250, false)
        assertTrue(e.state.speedMps < 3.0)

        // Pacers stop as well, so a break costs no ground: rider and an equal pacer stay level.
        val race = RouteEngine(flat, RiderParams.forRider(80.0), listOf(Pacer("Gleich", 250)))
        repeat(60) { race.tick(1.0, 250, false) }
        repeat(20) { race.tick(1.0, null, true) }
        assertEquals(0.0, race.state.pacers.single().speedMps, 0.0)
        repeat(60) { race.tick(1.0, 250, false) }
        assertEquals(0.0, race.state.pacers.single().gapM, 0.01)

        // The tick that reaches the top records the metres it rode, so the speeds add up to the route.
        val short = Route.fromKmGradients("t-f", "Kurz", "", "", RouteKind.FLAT, 100.0, 0.5, listOf(0.0))
        val fin = RouteEngine(short, RiderParams.forRider(80.0))
        var ridden = 0.0
        while (!fin.state.finished) ridden += fin.tick(1.0, 250, false).speedMps
        assertEquals(short.lengthM, ridden, 0.01)
    }

    @Test fun shortenedClimbKeepsTheRealHairpinNumbers() {
        val alpe = app.rollentrainer.core.route.Routes.byId("alpe-dhuez")!!
        val full = World(alpe, alpe.biome).signs.filter { Regex("Kehre \\d+").matches(it.text) }.map { it.text }
        assertEquals("Kehre 21", full.first())
        assertEquals("Kehre 1", full.last())
        val half = alpe.shortened(0.5)
        val signs = World(half, half.biome).signs.filter { Regex("Kehre \\d+").matches(it.text) }.map { it.text }
        // The bottom half rides the high numbers: 21 down to 9.
        assertEquals("Kehre 21", signs.first())
        assertEquals("Kehre ${21 - signs.size + 1}", signs.last())
        assertTrue(signs.size in 10..14)
    }

    @Test fun straightClimbAndCityStayCompact() {
        val straight = Route.fromKmGradients("t-s", "Gerade", "", "", RouteKind.CLIMB, 300.0, 4.0, listOf(7.0, 7.0, 7.0, 7.0), hairpins = 0)
        val w = World(straight, Biome.VENTOUX)
        val spanX = w.path.maxOf { it.x } - w.path.minOf { it.x }
        assertTrue(spanX < 1200)
        val city = Route.fromKmGradients("t-c", "Stadt", "", "", RouteKind.FLAT, 30.0, 2.0, listOf(0.5, -0.5), hairpins = 0)
        val cw = World(city, Biome.CITY)
        assertTrue(cw.props.count { it.kind == app.rollentrainer.core.route.Prop.BUILDING } > 100)
        assertTrue(cw.terrain.heights.all { abs(it - 32.5) < 4.0 })
    }

    @Test fun stageSectionsAndFinaleKeepTheProfile() {
        // A 60 km stage: flat, a 10 km climb at 7 %, a descent, flat to the finish.
        val pts = listOf(
            RoutePoint(0.0, 200.0), RoutePoint(20000.0, 220.0), RoutePoint(30000.0, 920.0),
            RoutePoint(40000.0, 300.0), RoutePoint(60000.0, 180.0),
        )
        val stage = Route(
            "t-stage", "Etappe 1: A – B", "Test", "", RouteKind.STAGE, pts,
            landmarks = listOf(Landmark(25000.0, "Halber Berg"), Landmark(30000.0, "Gipfel"), Landmark(55000.0, "Letzte Stadt")),
            biome = Biome.FIELDS, hairpinsAtM = listOf(24000.0, 27000.0),
            stage = StageInfo(1, "4. Juli", "A", "B", "mountain"),
        )
        assertTrue(stage.isStage)
        val climb = stage.section(20000.0, 30000.0)
        assertEquals(RouteKind.CLIMB, climb.kind)
        assertEquals(10000.0, climb.lengthM, 0.001)
        assertEquals(7.0, climb.avgGradientPct, 0.01)
        assertEquals(2, climb.hairpins)
        assertEquals(listOf(4000.0, 7000.0), climb.hairpinsAtM)
        assertEquals(listOf("Halber Berg", "Gipfel"), climb.landmarks.map { it.label })
        assertEquals(5000.0, climb.landmarks[0].distanceM, 0.001)
        assertEquals(20000.0, climb.offsetM, 0.001)
        assertEquals(60000.0, climb.wholeLengthM, 0.001)
        assertEquals(Biome.FIELDS, climb.biome)
        val flat = stage.section(0.0, 20000.0)
        assertEquals(RouteKind.FLAT, flat.kind)
        val down = stage.section(30000.0, 40000.0)
        assertEquals(RouteKind.FLAT, down.kind)
        assertEquals(-6.2, down.avgGradientPct, 0.01)
        // Every section builds a world, and the boards count down to the stage finish.
        val w = World(climb, climb.biome)
        assertTrue(w.signs.any { it.text.startsWith("noch 3") && it.text.contains("km") })
        assertTrue(w.signs.count { Regex("Kehre \\d+").matches(it.text) } == 2)
        // The finale is the last 30 km with the finish; its name says so and the ride stores it.
        val finale = stage.finale(30.0)
        assertEquals(30000.0, finale.lengthM, 0.001)
        assertEquals(180.0, finale.points.last().elevationM, 0.001)
        assertEquals(920.0, finale.startElevationM, 0.001)
        assertEquals("Etappe 1: A – B · Finale 30 km", finale.displayName)
        assertEquals(0.5, finale.fraction, 0.001)
        assertEquals(finale.lengthM, stage.cut(finale.fraction, finale.variant).lengthM, 0.001)
        // The ride stores the finale under the stage's id, so history and progress find it again.
        assertEquals(stage.id, finale.id)
        assertEquals("½ Etappe 1: A – B", stage.shortened(0.5).displayName)
        assertEquals(stage.lengthM, stage.finale(90.0).lengthM, 0.001)
    }

    @Test fun builtInStagesAreSane() {
        for (st in Stages.all) {
            val info = st.stage!!
            assertTrue(st.name, st.lengthM in 10_000.0..260_000.0)
            assertTrue(st.name, st.points.zipWithNext().all { (a, b) -> b.distanceM > a.distanceM })
            assertTrue("${st.name} max grade ${st.maxGradientPct}", st.maxGradientPct < 17.0)
            assertTrue(st.name, info.climbs.all { it.summitKm * 1000 <= st.lengthM + 1 })
            assertTrue(st.name, st.landmarks.all { it.distanceM > 0 && it.distanceM < st.lengthM })
            // Every section of the stage builds a world within the limits of the renderer.
            val n = kotlin.math.ceil(st.lengthM / 8000.0).toInt()
            val len = st.lengthM / n
            for (i in 0 until n) {
                val part = st.section(i * len, if (i == n - 1) st.lengthM else (i + 1) * len)
                val w = World(part, part.biome)
                assertTrue("${st.name} section $i cells ${w.terrain.cols * w.terrain.rows}", w.terrain.cols * w.terrain.rows < 150_000)
            }
        }
        assertEquals(Stages.all.size, Stages.all.map { it.id }.toSet().size)
    }
}

class GearingTest {
    private val p = RiderParams.forRider(78.0)
    private val neutral = VirtualGears.NEUTRAL_RATIO

    @Test fun neutralGearSendsTheRoadGrade() {
        for (g in listOf(-5.0, 0.0, 4.0, 9.0)) for (v in listOf(3.0, 6.0, 12.0)) {
            assertEquals(g, Gearing.feelGrade(g, neutral, neutral, v, p), 0.05)
        }
    }

    @Test fun harderGearsFeelSteeperAndEasierGearsFlatter() {
        val dt = Drivetrain.of("50/34", "11-28")
        assertEquals(20, dt.gears.size)
        val gears = VirtualGears(dt)
        assertEquals("50 × 17", gears.gear.label)
        val road = 6.0
        val v = 5.0
        val neutralGrade = Gearing.feelGrade(road, gears.gear.ratio, neutral, v, p)
        gears.up(); gears.up()
        val harder = Gearing.feelGrade(road, gears.gear.ratio, neutral, v, p)
        repeat(6) { gears.down() }
        val easier = Gearing.feelGrade(road, gears.gear.ratio, neutral, v, p)
        assertTrue("$easier < $neutralGrade < $harder", easier < neutralGrade - 1 && harder > neutralGrade + 1)
        // The hardest gear on a steep climb stays within what trainers accept.
        while (gears.up()) Unit
        assertTrue(Gearing.feelGrade(12.0, gears.gear.ratio, neutral, 2.0, p) <= Gearing.MAX_GRADE)
        assertEquals("50 × 11 · 20/20", gears.label)
        while (gears.down()) Unit
        assertTrue(Gearing.feelGrade(-6.0, gears.gear.ratio, neutral, 12.0, p) >= Gearing.MIN_GRADE)
    }

    @Test fun pedalPowerMatchesBetweenVirtualAndSentGrade() {
        // Riding 34 × 28 virtually on 8 % at 90 rpm must take the same pedal power as the trainer at the sent grade.
        val gear = Drivetrain.Gear(34, 28)
        val vFly = Gearing.flywheelFromCadence(90, neutral)
        val sent = Gearing.feelGrade(8.0, gear.ratio, neutral, vFly, p)
        val k = gear.ratio / neutral
        val wantedPower = Physics.resistance(vFly * k, 8.0, p) * vFly * k
        val trainerPower = Physics.resistance(vFly, sent, p) * vFly
        assertEquals(wantedPower, trainerPower, wantedPower * 0.03)
    }
}
