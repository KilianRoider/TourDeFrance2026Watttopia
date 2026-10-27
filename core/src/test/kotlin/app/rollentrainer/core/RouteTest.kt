package app.rollentrainer.core

import app.rollentrainer.core.route.Biome
import app.rollentrainer.core.route.Landmark
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.RouteKind
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
        }
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
}
