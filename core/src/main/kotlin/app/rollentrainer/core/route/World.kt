package app.rollentrainer.core.route

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** One sample of the road's centre line: position in metres (y up), heading in radians around y. */
class PathSample(val d: Double, val x: Double, val y: Double, val z: Double, val heading: Double)

/** What the landscape around a route looks like. */
@kotlinx.serialization.Serializable
enum class Biome {
    /** Alpine: pastures, forest up to the tree line, rock and snow above. */
    ALPINE,
    /** Pyrenean: greener, softer, forest higher. */
    PYRENEAN,
    /** Mont Ventoux: forest, then the white limestone desert. */
    VENTOUX,
    /** Volcanic Auvergne: grass and dark rock. */
    VOLCANIC,
    /** Breton hills: hedges, fields. */
    FIELDS,
    /** Paris: boulevard with buildings. */
    CITY,
}

/** A roadside sign: a landmark or a hairpin number. */
class Sign(val x: Double, val y: Double, val z: Double, val heading: Double, val text: String, val big: Boolean)

/** A tree, rock or building instance. */
class Prop(val kind: Int, val x: Double, val y: Double, val z: Double, val scale: Double, val rotation: Double) {
    companion object {
        const val CONIFER = 0
        const val BROADLEAF = 1
        const val ROCK = 2
        const val BUILDING = 3
        const val SNOW_POLE = 4
        const val SPECTATOR = 5
    }
}

/**
 * Builds the 3D world for a route: the road follows the real elevation profile and zig-zags up the
 * mountainside with the climb's hairpins, the terrain is a height field hung on the road, and props
 * are scattered by biome. Pure geometry; the renderer only uploads it.
 */
class World(val route: Route, val biome: Biome, seed: Long = route.id.hashCode().toLong()) {
    private val rnd = Rng(seed)

    /** Road centre line every [STEP] metres. */
    val path: List<PathSample>
    /** Mountainside slope along the progression axis (rise per metre). */
    val hillside: Double
    val signs: List<Sign>
    val props: List<Prop>
    val terrain: Terrain
    val treeLineM: Double = when (biome) {
        Biome.ALPINE -> 2100.0
        Biome.PYRENEAN -> 2000.0
        Biome.VENTOUX -> 1500.0
        else -> 9000.0
    }

    init {
        // A climb without hairpins goes straight up a hillside as steep as the road; with hairpins the
        // hillside is steeper and the road crosses it at an angle.
        hillside = when (route.kind) {
            RouteKind.CLIMB -> if (route.hairpins == 0) (route.avgGradientPct / 100).coerceAtLeast(0.02)
            else (route.avgGradientPct / 100 * 2.6).coerceIn(0.12, 0.35)
            RouteKind.ROLLING -> 0.06
            RouteKind.FLAT -> 0.0
        }
        path = buildPath()
        terrain = buildTerrain()
        signs = buildSigns()
        props = buildProps()
    }

    /** Interpolated pose at a distance along the road. */
    fun poseAt(distanceM: Double): PathSample {
        val d = distanceM.coerceIn(0.0, route.lengthM)
        val f = d / STEP
        val i = floor(f).toInt().coerceIn(0, path.size - 2)
        val t = (f - i).coerceIn(0.0, 1.0)
        val a = path[i]
        val b = path[i + 1]
        val h = lerpAngle(a.heading, b.heading, t)
        return PathSample(d, a.x + (b.x - a.x) * t, a.y + (b.y - a.y) * t, a.z + (b.z - a.z) * t, h)
    }

    // --- road -------------------------------------------------------------------------------

    private fun buildPath(): List<PathSample> {
        val length = route.lengthM
        val n = (length / STEP).toInt() + 1
        val out = ArrayList<PathSample>(n)
        // Traverses between hairpins: the road crosses the hillside at an angle that gives the real grade.
        val hairpins = route.hairpins.coerceAtLeast(0)
        val traverses = hairpins + 1
        val boundaries = DoubleArray(hairpins) { i -> length * (i + 1) / traverses * (1 + rnd.range(-0.12, 0.12)) }.sortedArray()
        val acrossAngle = if (hairpins > 0 && hillside > 0.0001) asin((route.avgGradientPct.coerceAtLeast(0.5) / 100 / hillside).coerceIn(0.05, 1.0)) else PI / 2
        // Heading 0 = along +z (up the hill); traverses alternate left and right across the slope.
        fun traverseHeading(i: Int): Double {
            val sign = if (i % 2 == 0) 1.0 else -1.0
            return if (hairpins == 0) 0.0 else sign * (PI / 2 - acrossAngle)
        }
        var x = 0.0
        var z = 0.0
        var heading = traverseHeading(0)
        var traverse = 0
        var d = 0.0
        // A hairpin is an arc of [HAIRPIN_LEN] metres between two traverse headings.
        var turnFrom = heading
        var turnTo = heading
        var turnStart = -1.0
        for (i in 0 until n) {
            val y = route.elevationAt(d)
            out += PathSample(d, x, y, z, heading)
            d += STEP
            if (traverse < hairpins && d >= boundaries[traverse] && turnStart < 0) {
                turnStart = d
                turnFrom = heading
                turnTo = traverseHeading(traverse + 1)
                traverse++
            }
            val meander = if (route.kind == RouteKind.CLIMB) 0.12 else 0.22
            val wobble = meander * sin(d / 170.0 + seedPhase(1)) + meander * 0.5 * sin(d / 61.0 + seedPhase(2))
            heading = if (turnStart >= 0) {
                val t = ((d - turnStart) / HAIRPIN_LEN).coerceIn(0.0, 1.0)
                val h = lerpAngle(turnFrom, turnTo, smooth(t))
                if (t >= 1.0) turnStart = -1.0
                h
            } else {
                traverseHeading(traverse) + wobble
            }
            x += sin(heading) * STEP
            z += cos(heading) * STEP
        }
        return out
    }

    private fun seedPhase(k: Int) = ((route.id.hashCode() * 31 + k) % 628) / 100.0

    // --- terrain ----------------------------------------------------------------------------

    /** Height field over the route's bounding box, [cell] metres per cell. */
    class Terrain(val originX: Double, val originZ: Double, val cols: Int, val rows: Int, val cell: Double) {
        val heights = FloatArray(cols * rows)
        /** 0 = grass, 1 = forest floor, 2 = rock, 3 = snow, 4 = scree, 5 = asphalt/plaza, 6 = road shoulder */
        val material = ByteArray(cols * rows)
        fun index(c: Int, r: Int) = r * cols + c
        fun x(c: Int) = originX + c * cell
        fun z(r: Int) = originZ + r * cell
    }

    private fun buildTerrain(): Terrain {
        val minX = path.minOf { it.x } - MARGIN
        val maxX = path.maxOf { it.x } + MARGIN
        val minZ = path.minOf { it.z } - MARGIN
        val maxZ = path.maxOf { it.z } + MARGIN
        val cell = CELL
        val cols = ((maxX - minX) / cell).toInt() + 2
        val rows = ((maxZ - minZ) / cell).toInt() + 2
        val t = Terrain(minX, minZ, cols, rows, cell)
        val grid = SpatialHash(path, 60.0)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val x = t.x(c)
                val z = t.z(r)
                val near = grid.nearest(x, z)
                val dist = sqrt((near.x - x) * (near.x - x) + (near.z - z) * (near.z - z))
                // Base: the hillside rises along +z from the nearest piece of road.
                val base = near.y + hillside * (z - near.z) + 0.25 * hillside * (x - near.x) * sin(seedPhase(3))
                val amp = when (biome) {
                    Biome.CITY -> 0.0
                    Biome.FIELDS -> 6.0
                    else -> 30.0
                }
                val n = fbm(x * 0.004, z * 0.004) * amp + fbm(x * 0.02, z * 0.02) * amp * 0.15
                val blend = ((dist - SHOULDER) / 35.0).coerceIn(0.0, 1.0)
                val h = near.y * (1 - blend) + (base + n) * blend
                val i = t.index(c, r)
                t.heights[i] = h.toFloat()
                t.material[i] = materialFor(h, dist, blend, x, z)
            }
        }
        return t
    }

    private fun materialFor(h: Double, dist: Double, blend: Double, x: Double, z: Double): Byte {
        if (dist < SHOULDER + 2) return 6
        val noise = fbm(x * 0.03 + 7.0, z * 0.03)
        return when (biome) {
            Biome.CITY -> 5
            Biome.FIELDS -> if (noise > 0.2) 1 else 0
            Biome.VENTOUX -> when {
                h > 1560 -> 4
                h > 1450 && noise > -0.1 -> 4
                noise > 0.0 -> 1
                else -> 0
            }
            Biome.VOLCANIC -> if (noise > 0.35) 2 else 0
            else -> when {
                h > treeLineM + 700 && noise > -0.3 -> 3
                h > treeLineM + 300 -> if (noise > 0.1) 3 else 2
                h > treeLineM -> if (noise > 0.3) 2 else 0
                noise > 0.15 -> 1
                else -> 0
            }
        }.toByte()
    }

    // --- signs and props ---------------------------------------------------------------------

    private fun buildSigns(): List<Sign> {
        val out = mutableListOf<Sign>()
        for (l in route.landmarks) {
            val p = poseAt(l.distanceM)
            val (sx, sz) = beside(p, ROAD_HALF + 2.0)
            out += Sign(sx, p.y, sz, p.heading, l.label, big = true)
        }
        // Kilometre boards count down to the summit like the real ones.
        var km = 1.0
        while (km * 1000 < route.lengthM - 300) {
            val p = poseAt(km * 1000)
            val (sx, sz) = beside(p, ROAD_HALF + 1.5)
            val remaining = (route.lengthM - km * 1000) / 1000
            out += Sign(sx, p.y, sz, p.heading, "noch %.1f km · %.0f %%".format(remaining, route.gradientAt(km * 1000 + 500)), big = false)
            km += 1.0
        }
        // Hairpin numbers on climbs with famous bends.
        if (route.hairpins > 0) {
            val traverses = route.hairpins + 1
            for (i in 1..route.hairpins) {
                val d = route.lengthM * i / traverses
                val p = poseAt(d)
                val (sx, sz) = beside(p, ROAD_HALF + 2.5)
                out += Sign(sx, p.y, sz, p.heading, "Kehre ${route.hairpins - i + 1}", big = false)
            }
        }
        return out
    }

    private fun buildProps(): List<Prop> {
        val out = mutableListOf<Prop>()
        val grid = SpatialHash(path, 60.0)
        if (biome == Biome.CITY) {
            // Haussmann blocks along both sides of the boulevard.
            var d = 0.0
            while (d < route.lengthM) {
                val p = poseAt(d)
                for (side in listOf(-1.0, 1.0)) {
                    val (bx, bz) = beside(p, side * (ROAD_HALF + 14.0))
                    out += Prop(Prop.BUILDING, bx, p.y, bz, rnd.range(0.85, 1.15), p.heading)
                }
                d += 26.0
            }
            var s = 0.0
            while (s < route.lengthM) {
                val p = poseAt(s)
                for (side in listOf(-1.0, 1.0)) {
                    val (tx, tz) = beside(p, side * (ROAD_HALF + 5.0))
                    out += Prop(Prop.BROADLEAF, tx, p.y, tz, rnd.range(0.8, 1.1), rnd.range(0.0, 6.28))
                }
                s += 14.0
            }
            return out
        }
        // Trees and rocks scattered over the terrain, thinning out towards the tree line.
        val t = terrain
        val density = when (biome) { Biome.FIELDS -> 0.35; Biome.VOLCANIC -> 0.15; else -> 0.5 }
        for (r in 0 until t.rows) {
            for (c in 0 until t.cols) {
                val i = t.index(c, r)
                val m = t.material[i].toInt()
                val h = t.heights[i].toDouble()
                val x = t.x(c) + rnd.range(-CELL / 2, CELL / 2)
                val z = t.z(r) + rnd.range(-CELL / 2, CELL / 2)
                val near = grid.nearest(x, z)
                val dist = sqrt((near.x - x) * (near.x - x) + (near.z - z) * (near.z - z))
                if (dist < ROAD_HALF + 3.0 || dist > PROP_RANGE) continue
                val above = h - treeLineM
                val treeChance = density * (if (m == 1) 1.4 else if (m == 0) 0.45 else 0.0) * (1 - (above / 250.0).coerceIn(0.0, 1.0))
                val roll = rnd.next()
                when {
                    roll < treeChance -> {
                        val kind = if (biome == Biome.FIELDS || (biome == Biome.VENTOUX && h < 1100) || above < -900) Prop.BROADLEAF else Prop.CONIFER
                        out += Prop(kind, x, h, z, rnd.range(0.7, 1.4), rnd.range(0.0, 6.28))
                    }
                    (m == 2 || m == 4) && roll < treeChance + 0.08 -> out += Prop(Prop.ROCK, x, h, z, rnd.range(0.6, 2.2), rnd.range(0.0, 6.28))
                    m == 3 && roll < 0.03 && dist < 25 -> out += Prop(Prop.SNOW_POLE, x, h, z, 1.0, 0.0)
                }
            }
        }
        // Fans in the last two kilometres of a climb, as on a Tour stage.
        if (route.kind == RouteKind.CLIMB) {
            var d = (route.lengthM - 2000.0).coerceAtLeast(300.0)
            while (d < route.lengthM - 30) {
                val p = poseAt(d)
                val side = if (rnd.next() < 0.5) -1.0 else 1.0
                val (fx, fz) = beside(p, side * (ROAD_HALF + 0.8 + rnd.range(0.0, 1.5)))
                out += Prop(Prop.SPECTATOR, fx, p.y, fz, rnd.range(0.9, 1.1), p.heading + if (side < 0) PI / 2 else -PI / 2)
                d += rnd.range(2.5, 7.0) * (1 + (route.lengthM - d) / 400)
            }
        }
        return out
    }

    /** Point beside the road: positive [offset] is to the rider's right. */
    private fun beside(p: PathSample, offset: Double): Pair<Double, Double> =
        Pair(p.x + cos(p.heading) * offset, p.z - sin(p.heading) * offset)

    // --- helpers ------------------------------------------------------------------------------

    private class SpatialHash(samples: List<PathSample>, private val cell: Double) {
        private val map = HashMap<Long, MutableList<PathSample>>()
        private val all = samples
        init {
            for (s in samples) map.getOrPut(key(s.x, s.z)) { mutableListOf() }.add(s)
        }
        private fun key(x: Double, z: Double): Long {
            val cx = floor(x / cell).toLong()
            val cz = floor(z / cell).toLong()
            return cx * 1_000_003L + cz
        }
        fun nearest(x: Double, z: Double): PathSample {
            var best: PathSample? = null
            var bestD = Double.MAX_VALUE
            val cx = floor(x / cell).toLong()
            val cz = floor(z / cell).toLong()
            var ring = 0
            while (best == null && ring < 6) {
                for (dx in -ring..ring) for (dz in -ring..ring) {
                    if (ring > 0 && abs(dx) != ring && abs(dz) != ring) continue
                    val list = map[(cx + dx) * 1_000_003L + (cz + dz)] ?: continue
                    for (s in list) {
                        val d = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z)
                        if (d < bestD) { bestD = d; best = s }
                    }
                }
                ring++
            }
            if (best == null) {
                for (s in all) {
                    val d = (s.x - x) * (s.x - x) + (s.z - z) * (s.z - z)
                    if (d < bestD) { bestD = d; best = s }
                }
            }
            return best!!
        }
    }

    /** Small deterministic random source so a route always looks the same. */
    private class Rng(seed: Long) {
        private var s = seed xor 0x5DEECE66DL
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return ((s ushr 11) and 0x1FFFFFFFFFFFFFL).toDouble() / (1L shl 53).toDouble()
        }
        fun range(a: Double, b: Double) = a + (b - a) * next()
    }

    companion object {
        const val STEP = 5.0
        const val CELL = 24.0
        const val MARGIN = 420.0
        const val ROAD_HALF = 3.5
        const val SHOULDER = 7.0
        const val HAIRPIN_LEN = 70.0
        const val PROP_RANGE = 380.0

        fun lerpAngle(a: Double, b: Double, t: Double): Double {
            var diff = (b - a) % (2 * PI)
            if (diff > PI) diff -= 2 * PI
            if (diff < -PI) diff += 2 * PI
            return a + diff * t
        }

        fun smooth(t: Double) = t * t * (3 - 2 * t)

        /** Value noise with three octaves, roughly in -1..1. */
        fun fbm(x: Double, y: Double): Double {
            var sum = 0.0
            var amp = 0.6
            var fx = x
            var fy = y
            repeat(3) {
                sum += valueNoise(fx, fy) * amp
                amp *= 0.5
                fx *= 2.1
                fy *= 2.1
            }
            return sum
        }

        private fun hash(ix: Int, iy: Int): Double {
            var h = ix * 374761393 + iy * 668265263
            h = (h xor (h ushr 13)) * 1274126177
            h = h xor (h ushr 16)
            return (h and 0xFFFF) / 32768.0 - 1.0
        }

        private fun valueNoise(x: Double, y: Double): Double {
            val ix = floor(x).toInt()
            val iy = floor(y).toInt()
            val fx = smooth(x - ix)
            val fy = smooth(y - iy)
            val a = hash(ix, iy)
            val b = hash(ix + 1, iy)
            val c = hash(ix, iy + 1)
            val d = hash(ix + 1, iy + 1)
            return (a + (b - a) * fx) * (1 - fy) + (c + (d - c) * fx) * fy
        }
    }
}
