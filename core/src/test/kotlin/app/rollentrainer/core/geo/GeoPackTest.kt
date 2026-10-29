package app.rollentrainer.core.geo

import app.rollentrainer.core.route.Routes
import app.rollentrainer.core.route.Stages
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Reads the fixture pack written by tools/geodata/make_test_pack.py, whose every value is known. */
class GeoPackTest {
    private fun fixtureBytes(): ByteArray =
        GeoPackTest::class.java.getResourceAsStream("/geo/fixture.wwgeo")!!.use { it.readBytes() }

    private val pack = GeoPack.open(ByteArraySource(fixtureBytes()))

    @Test fun metaAndPath() {
        assertEquals("fixture", pack.id)
        assertEquals(1000.0, pack.lengthM, 0.0)
        assertEquals(45.0, pack.meta.projection.lat0, 0.0)
        assertEquals(2, pack.meta.lods.size)
        assertEquals(1280.0, pack.lod(0).tileM, 0.0)
        assertEquals(500.0, pack.meta.hairpins.single().s, 0.0)
        val p = pack.path
        assertEquals(201, p.size)
        assertEquals(100.0, p.x[0], 1e-9)
        assertEquals(200.0, p.y[0], 1e-9)
        assertEquals(1200.0, p.y[200], 1e-9)
        assertEquals(550.0, p.z[200], 1e-9)
        val pose = p.poseAt(350.0)
        assertEquals(100.0, pose.x, 1e-9)
        assertEquals(550.0, pose.y, 1e-9)
        assertEquals(517.5, pose.z, 1e-9)
        assertEquals(5.0, pose.gradePct, 1e-6)
        assertEquals(0.0, pose.bearingRad, 1e-9) // due north
        assertEquals(6.5, pose.widthM, 1e-6)
        assertTrue(pose.onBridge)
        assertFalse(p.poseAt(600.0).onBridge)
        assertEquals("secondary", RoadClass.name(pose.roadClass))
        // Nearest road point, with and without a hint.
        assertEquals(400.0, p.nearestDistance(130.0, 600.0), 1e-6)
        assertEquals(400.0, p.nearestDistance(130.0, 600.0, hintM = 450.0, windowM = 100.0), 1e-6)
        val prof = pack.profile(20.0)
        assertEquals(51, prof.size)
        assertEquals(1000.0, prof.last().distanceM, 0.0)
        assertEquals(510.0, prof[10].elevationM, 1e-9)
        assertEquals(50.0, p.ascentM(), 2.5) // the 2 m hysteresis drops the last metres
    }

    @Test fun heightTiles() {
        val k = TileKey(0, 0, 0)
        val h = pack.heights(k)!!
        assertEquals(128, h.cells)
        // h = 400 + 0.5 * col + 0.25 * row, row 0 = north; stored in 0.1 m steps.
        assertEquals(400.0, h.at(0, 0).toDouble(), 0.01)
        assertEquals(464.0, h.at(128, 0).toDouble(), 0.01)
        assertEquals(432.0, h.at(0, 128).toDouble(), 0.01)
        // (x, y) = (100 m, 1280 - 50 m): col 10, row 5.
        assertEquals(400 + 5.0 + 1.25, h.heightAt(100.0, 1230.0), 0.06)
        // Between samples: bilinear.
        assertEquals(400 + 0.5 * 10.5 + 0.25 * 5, h.heightAt(105.0, 1230.0), 0.06)
        assertEquals(500.0, pack.heights(TileKey(0, 0, 1))!!.at(0, 0).toDouble(), 0.01)
        assertEquals(300.0 + 2.0 * 128, pack.heights(TileKey(1, 0, 0))!!.at(128, 7).toDouble(), 0.3)
        assertNull(pack.heights(TileKey(0, 5, 5)))
    }

    @Test fun tileQueries() {
        assertEquals(listOf(TileKey(0, 0, 0), TileKey(0, 0, 1)), pack.tiles(GeoLayer.HEIGHT, 0))
        assertEquals(setOf(TileKey(0, 0, 0)), pack.tilesNear(100.0, 200.0, 50.0, 0).toSet())
        assertEquals(setOf(TileKey(0, 0, 0), TileKey(0, 0, 1)), pack.tilesNear(100.0, 1250.0, 100.0, 0).toSet())
        assertEquals(setOf(TileKey(0, 0, 0), TileKey(0, 0, 1)), pack.tilesAlong(0.0, 1000.0, 0, 100.0).toSet())
        assertEquals(setOf(TileKey(0, 0, 0)), pack.tilesAlong(0.0, 900.0, 0, 50.0).toSet())
        assertTrue(pack.has(GeoLayer.IMAGE, TileKey(0, 0, 0)))
        assertFalse(pack.has(GeoLayer.IMAGE, TileKey(0, 0, 1)))
    }

    @Test fun rastersAndVectors() {
        val k = TileKey(0, 0, 0)
        val lc = pack.landCover(k)!!
        assertEquals(LandCover.TREE, lc.classAt(10.0, 500.0))
        assertEquals(LandCover.CROP, lc.classAt(1000.0, 500.0))
        val img = pack.image(k)!!
        assertEquals("jpeg", img.format)
        val decoded = javax.imageio.ImageIO.read(img.bytes.inputStream())
        assertEquals(16, decoded.width)
        assertTrue((decoded.getRGB(8, 8) shr 16 and 0xFF) > 180)

        val b = pack.buildings(k)!!.buildings
        assertEquals(2, b.size)
        assertEquals(9.5f, b[0].heightM, 1e-4f)
        assertEquals(2.5f, b[0].roofHeightM, 1e-4f)
        assertEquals(2, b[0].roofShape)
        assertEquals(3, b[0].floors)
        assertTrue(b[0].heightEstimated)
        assertEquals(512.5f, b[0].baseZ, 0f)
        assertEquals(0xFFA03020.toInt(), b[0].roofColour)
        assertArrayEquals(floatArrayOf(50f, 50f, 60f, 50f, 60f, 58f, 50f, 58f), b[0].ring, 1e-3f)
        assertArrayEquals(floatArrayOf(100f, 200f, 115f, 200f, 115f, 210f), b[1].ring, 1e-3f)

        val r = pack.roads(k)!!.roads.single()
        assertEquals("residential", RoadClass.name(r.roadClass))
        assertEquals(PathFlags.UNPAVED, r.flags)
        assertEquals(5.5f, r.widthM, 1e-4f)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f), r.points, 1e-3f)

        val w = pack.water(k)!!.features
        assertEquals(2, w.size)
        assertTrue(w[0].isArea)
        assertEquals(480.25f, w[0].levelM, 0f)
        assertEquals(2, w[0].rings.size)
        assertEquals(8, w[0].rings[0].size)
        assertFalse(w[1].isArea)
        assertTrue(w[1].levelM.isNaN())

        val pl = pack.places
        assertEquals("Testdorf", pl.places.single().name)
        assertEquals("village", pl.places.single().placeClass)
        assertEquals(350.0, pl.towns.single().sIn, 0.0)
    }

    @Test fun corridorCrossSections() {
        assertEquals(1, pack.corridorSections)
        val c = pack.corridorAt(600.0)!!
        assertEquals(0, c.firstSample)
        assertEquals(201, c.count)
        assertArrayEquals(floatArrayOf(0f, 5f), c.offsetsM, 0f)
        assertEquals(-0.2f, c.leftAt(120, 0), 1e-6f) // stored in dm: -0.15 m becomes -0.2
        assertEquals(1.0f, c.leftAt(120, 1), 1e-6f)
        assertEquals(-2.0f, c.rightAt(200, 1), 1e-6f)
        assertEquals(2, c.freeLeftAt(5))
        assertEquals(1, c.freeRightAt(5))
    }

    @Test fun fileSourceReadsTheSameAsBytes() {
        val f = File.createTempFile("fixture", ".wwgeo")
        try {
            val bytes = fixtureBytes()
            // An uncompressed asset is a region inside the APK: put junk around the pack.
            f.writeBytes(ByteArray(100) { 7 } + bytes + ByteArray(50))
            FileByteSource(f, 100, bytes.size.toLong()).use { src ->
                val p = GeoPack.open(src)
                assertEquals(pack.path.z.toList(), p.path.z.toList())
                assertEquals(pack.heights(TileKey(0, 0, 0))!!.heights.toList(), p.heights(TileKey(0, 0, 0))!!.heights.toList())
            }
        } finally {
            f.delete()
        }
    }

    @Test fun corruptChunkIsDetected() {
        val bytes = fixtureBytes()
        bytes[80] = (bytes[80] + 1).toByte() // inside the first chunk (the path)
        val p = GeoPack.open(ByteArraySource(bytes))
        assertTrue(runCatching { p.path }.isFailure)
    }

    @Test fun realProfilesAreWiredAndPlausible() {
        val alpe = Routes.byId("alpe-dhuez")!!
        val real = RealProfiles.load("alpe-dhuez")
        if (real == null) return // profile resources not generated yet
        assertNotNull(real)
        assertEquals(real.points, alpe.points)
        // Le Bourg-d'Oisans ~720 m to the resort ~1850 m, 13.8 km at about 8 %.
        assertEquals(13.8, alpe.lengthKm, 0.4)
        assertEquals(720.0, alpe.startElevationM, 25.0)
        assertEquals(1845.0, alpe.points.last().elevationM, 30.0)
        assertEquals(8.1, alpe.avgGradientPct, 0.5)
        assertTrue(alpe.maxGradientPct < 22)
        // Nearly all of it climbs: what goes down is noise of the elevation model, a few metres at most.
        val down = alpe.points.zipWithNext().sumOf { (a, b) -> (a.elevationM - b.elevationM).coerceAtLeast(0.0) }
        assertTrue("descent $down", down < 40)
        assertEquals(21, alpe.hairpins)
        assertTrue(alpe.hairpinsAtM.all { it < alpe.lengthM })
        assertTrue(alpe.landmarks.all { it.distanceM > 0 && it.distanceM < alpe.lengthM })
        // Routes without a resource keep their profile.
        assertNull(RealProfiles.load("no-such-route"))
        for (st in Stages.all) assertTrue(st.points.zipWithNext().all { (a, b) -> b.distanceM > a.distanceM && abs(b.elevationM) < 5000 })
    }

    /**
     * Real packs, when present (WWGEO_PACK_DIR or app/src/main/assets/geo): every chunk decodes, and
     * the carved terrain meets the road profile, so the road never floats or sinks.
     */
    @Test fun realPacksAreConsistent() {
        val dir = System.getenv("WWGEO_PACK_DIR")?.let { File(it) } ?: File("../app/src/main/assets/geo")
        val packs = dir.listFiles { f -> f.name.endsWith(".wwgeo") }?.sortedBy { it.name } ?: return
        for (f in packs) {
            FileByteSource(f).use { src ->
                val p = GeoPack.open(src)
                val path = p.path
                assertEquals(f.name, p.meta.lengthM, path.lengthM, 0.01)
                assertTrue(f.name, path.z.all { it.isFinite() && it > -50 && it < 5000 })
                for (i in 0 until path.size - 1) assertTrue("${f.name} step $i", abs(path.z[i + 1] - path.z[i]) < 0.3 * path.stepM + 0.01)
                for (lod in p.meta.lods.map { it.lod }) {
                    for (k in p.tiles(GeoLayer.HEIGHT, lod)) assertTrue(p.heights(k)!!.heights.all { it.isFinite() })
                    for (k in p.tiles(GeoLayer.IMAGE, lod)) assertTrue(p.image(k)!!.bytes.size > 100)
                    for (k in p.tiles(GeoLayer.WATER, lod)) p.water(k)
                }
                for (k in p.tiles(GeoLayer.BUILDINGS, 0)) assertTrue(p.buildings(k)!!.buildings.all { it.ring.size >= 6 && it.baseZ.isFinite() })
                for (k in p.tiles(GeoLayer.ROADS, 0)) p.roads(k)
                // The carved terrain meets the road (bridges and tunnels excepted): at the road edge in the 5 m
                // corridor cross-sections (a tight bend on a grade puts the inner edge near road further on, hence
                // the tolerance), and within the resolution of the 10 m grid under the road.
                val errs = ArrayList<Double>()
                var i = 0
                while (i < path.size) {
                    val clear = (-6..6).all { j -> path.flags[(i + j).coerceIn(0, path.size - 1)].toInt() and (PathFlags.BRIDGE or PathFlags.TUNNEL) == 0 }
                    if (clear) {
                        val pose = path.poseAt(path.distanceOf(i))
                        val key = p.tilesNear(pose.x, pose.y, 0.0, 0).firstOrNull()
                        if (key != null) errs += abs(p.heights(key)!!.heightAt(pose.x, pose.y) - (pose.z - 0.15))
                        val c = p.corridorAt(path.distanceOf(i))!!
                        assertEquals("${f.name} edge at sample $i", -0.15, c.leftAt(i, 0).toDouble(), 0.6)
                        assertEquals("${f.name} edge at sample $i", -0.15, c.rightAt(i, 0).toDouble(), 0.6)
                    }
                    i += 20
                }
                errs.sort()
                val p90 = errs[(errs.size * 0.9).toInt()]
                assertTrue("${f.name} terrain vs road p90 $p90 m", p90 < 1.0)
                RealProfiles.load(p.id)?.let { prof ->
                    assertEquals(path.lengthM, prof.lengthM, 0.01)
                    for (pt in prof.points) assertEquals(path.elevationAt(pt.distanceM), pt.elevationM, 0.02)
                }
            }
        }
    }
}
