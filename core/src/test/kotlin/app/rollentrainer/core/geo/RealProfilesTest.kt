package app.rollentrainer.core.geo

import app.rollentrainer.core.route.Climb
import app.rollentrainer.core.route.Landmark
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteKind
import app.rollentrainer.core.route.RoutePoint
import app.rollentrainer.core.route.Routes
import app.rollentrainer.core.route.StageInfo
import app.rollentrainer.core.route.Stages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** How [RealProfiles] puts the pipeline's landmarks and climb summits on the route. */
class RealProfilesTest {
    private val handMade = Route(
        id = "t", name = "Test", region = "", description = "", kind = RouteKind.STAGE,
        points = listOf(RoutePoint(0.0, 100.0), RoutePoint(5000.0, 300.0), RoutePoint(7000.0, 150.0), RoutePoint(10000.0, 250.0)),
        landmarks = listOf(Landmark(4000.0, "Dorf"), Landmark(4500.0, "Kapelle"), Landmark(8000.0, "Brücke")),
        stage = StageInfo(
            1, "2026-07-04", "A", "B", "hilly",
            listOf(Climb("Col A", 300.0, 5.0, 5.0, 4.0, "3"), Climb("Côte B", 250.0, 10.0, 3.0, 3.3, "4")),
        ),
    )

    /** The real road: 2 % longer, the first top at 5.1 km, the second at the finish. */
    private val points = (0..510).map { i ->
        val d = i * 20.0
        RoutePoint(d, if (d <= 5100) 100 + d / 5100 * 210 else if (d <= 7100) 310 - (d - 5100) / 2000 * 160 else 150 + (d - 7100) / 3100 * 110)
    }

    @Test fun coincidentSignsAndSummitsAreSplit() {
        // Both labels and both climbs matched one waypoint, as Izoard's signs and stage 1's climbs did.
        val p = RealProfiles.Profile(
            "t", points, null, emptyList(),
            landmarks = listOf(Landmark(4590.0, "Dorf"), Landmark(4590.0, "Kapelle"), Landmark(8160.0, "Brücke")),
            climbs = listOf(RealProfiles.ClimbPosition("Col A", 5.1, 310.0), RealProfiles.ClimbPosition("Côte B", 5.1, 310.0)),
        )
        val r = RealProfiles.withProfile(handMade, p)
        // The sign whose own position is nearest keeps the spot; the other goes back to its stretched position.
        assertEquals(listOf("Dorf", "Kapelle", "Brücke"), r.landmarks.map { it.label })
        assertEquals(4080.0, r.landmarks[0].distanceM, 0.01)
        assertEquals(4590.0, r.landmarks[1].distanceM, 0.01)
        // The climb whose own summit is nearest keeps it; the other gets the high point next to its own summit.
        val (a, b) = r.stage!!.climbs
        assertEquals(5.1, a.summitKm, 1e-9)
        assertEquals(310.0, a.summitM, 1e-9)
        assertEquals(10.2, b.summitKm, 1e-9)
        assertEquals(260.0, b.summitM, 0.01)
    }

    @Test fun distinctPlacesStayWherePlaced() {
        val p = RealProfiles.Profile(
            "t", points, null, emptyList(),
            landmarks = listOf(Landmark(4100.0, "Dorf"), Landmark(4590.0, "Kapelle"), Landmark(8160.0, "Brücke")),
            climbs = listOf(RealProfiles.ClimbPosition("Col A", 5.1, 310.0), RealProfiles.ClimbPosition("Côte B", 10.2, 260.0)),
        )
        val r = RealProfiles.withProfile(handMade, p)
        assertEquals(listOf(4100.0, 4590.0, 8160.0), r.landmarks.map { it.distanceM })
        assertEquals(listOf(5.1, 10.2), r.stage!!.climbs.map { it.summitKm })
    }

    /** The shipped profiles: every sign is passed on its own and every climb has its own summit. */
    @Test fun shippedRoutesHaveOneSignPerSpotAndOneClimbPerSummit() {
        if (RealProfiles.load("tour26-01") == null) return // profile resources not generated yet
        for (r in Routes.all + Stages.all) {
            r.landmarks.zipWithNext().forEach { (x, y) -> assertTrue("${r.id}: ${x.label} / ${y.label}", y.distanceM - x.distanceM >= 25) }
            val tops = r.stage?.climbs.orEmpty().map { it.summitKm }.sorted()
            tops.zipWithNext().forEach { (x, y) -> assertTrue("${r.id}: summits at $x and $y km", (y - x) * 1000 >= 50) }
        }
        // Stage 1's final climb tops out at the stadium, the finish, not on the first climb at km 17.
        val s1 = Stages.byNumber(1)!!
        val stadium = s1.stage!!.climbs.first { it.name.contains("Stade") }
        assertEquals(s1.lengthKm, stadium.summitKm, 0.05)
        assertEquals(s1.points.last().elevationM, stadium.summitM, 2.0)
        val madrona = s1.stage!!.climbs.first { it.name.contains("Madrona") }
        assertEquals(17.0, madrona.summitKm, 0.1)
        // Izoard: the stele and the Casse Déserte before it; stage 3: Llívia after Puigcerdà.
        val iz = Routes.byId("izoard")!!.landmarks.associate { it.label to it.distanceM }
        assertTrue(iz.toString(), iz.getValue("Casse Déserte") < iz.getValue("Coppi-Bobet-Stele") - 100)
        val s3 = Stages.byNumber(3)!!.landmarks.associate { it.label to it.distanceM }
        assertTrue(s3.toString(), abs(s3.getValue("Llívia – Grenze Frankreich") - s3.getValue("Puigcerdà")) > 1000)
    }
}
