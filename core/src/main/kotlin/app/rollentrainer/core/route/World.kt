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

    /** Hairpins only exist on a slope; on flat ground the road just runs on. */
    private val effectiveHairpins: Int get() = if (hillside > 0.0001) route.hairpins.coerceAtLeast(0) else 0

    /** Distance of each hairpin's apex along the road, filled while the path is built. */
    private var hairpinApex = DoubleArray(0)

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
        val hairpins = effectiveHairpins
        val traverses = hairpins + 1
        // Where the route knows its bends, the turn starts half a hairpin before each apex; otherwise
        // they are spread evenly with a little jitter relative to the spacing, so the order never changes.
        val known = route.hairpinsAtM
        val boundaries = if (known.size == hairpins && hairpins > 0) DoubleArray(hairpins) { i -> (known[i] - HAIRPIN_LEN / 2).coerceAtLeast(STEP) }
        else DoubleArray(hairpins) { i -> length / traverses * (i + 1 + rnd.range(-0.25, 0.25)) }
        hairpinApex = DoubleArray(hairpins)
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
                hairpinApex[traverse] = (d + HAIRPIN_LEN / 2).coerceAtMost(length)
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

        /**
         * Height of the terrain surface at (x, z), interpolated on the same two triangles per cell that
         * the mesh uses (split along the diagonal from (c+1, r) to (c, r+1)).
         */
        fun heightAt(x: Double, z: Double): Double {
            val fc = (x - originX) / cell
            val fr = (z - originZ) / cell
            val c = floor(fc).toInt().coerceIn(0, cols - 2)
            val r = floor(fr).toInt().coerceIn(0, rows - 2)
            val fx = (fc - c).coerceIn(0.0, 1.0)
            val fz = (fr - r).coerceIn(0.0, 1.0)
            val a = heights[index(c, r)].toDouble()
            val b = heights[index(c + 1, r)].toDouble()
            val d = heights[index(c, r + 1)].toDouble()
            val e = heights[index(c + 1, r + 1)].toDouble()
            return if (fx + fz <= 1.0) a + (b - a) * fx + (d - a) * fz
            else e + (d - e) * (1 - fx) + (b - e) * (1 - fz)
        }
    }

    /** Per terrain vertex: the nearest road segment, the distance to it and the road height there. */
    private lateinit var nearSeg: IntArray
    private lateinit var nearDist: DoubleArray

    /** Distance from (x, z) to road segment [j] (path[j] to path[j+1]); writes the road height to [out]. */
    private fun segmentDistance(j: Int, x: Double, z: Double, out: DoubleArray): Double {
        val a = path[j]
        val b = path[(j + 1).coerceAtMost(path.size - 1)]
        val sx = b.x - a.x
        val sz = b.z - a.z
        val len2 = sx * sx + sz * sz
        val raw = if (len2 < 1e-9) 0.0 else ((x - a.x) * sx + (z - a.z) * sz) / len2
        val t = raw.coerceIn(0.0, 1.0)
        val px = a.x + sx * t
        val pz = a.z + sz * t
        // Before the start and after the finish the ground carries the road's grade on for a while, so
        // the coarse terrain cells there do not lift the first and last metres of asphalt.
        val len = sqrt(len2)
        val reach = if (len < 1e-9) 0.0 else END_RAMP / len
        val ty = when {
            j == 0 && raw < 0 -> raw.coerceAtLeast(-reach)
            j == path.size - 2 && raw > 1 -> raw.coerceAtMost(1 + reach)
            else -> t
        }
        out[0] = a.y + (b.y - a.y) * ty
        out[1] = pz
        return sqrt((x - px) * (x - px) + (z - pz) * (z - pz))
    }

    /**
     * The terrain hangs on the road: right next to it the ground sits just below the asphalt (so no
     * triangle of the 24 m grid can poke through the road), further out it follows the mountainside
     * plane, corrected by how far the real profile is above or below the average grade (blurred, so
     * the switchbacks don't leave steps), plus noise for hills and gullies.
     */
    private fun buildTerrain(): Terrain {
        val minX = path.minOf { it.x } - MARGIN
        val maxX = path.maxOf { it.x } + MARGIN
        val minZ = path.minOf { it.z } - MARGIN
        val maxZ = path.maxOf { it.z } + MARGIN
        val cell = CELL
        val cols = ((maxX - minX) / cell).toInt() + 2
        val rows = ((maxZ - minZ) / cell).toInt() + 2
        val t = Terrain(minX, minZ, cols, rows, cell)
        val n = cols * rows
        val seg = IntArray(n) { -1 }
        val dist = DoubleArray(n) { Double.MAX_VALUE }
        val roadY = DoubleArray(n)
        val roadZ = DoubleArray(n)
        val out = DoubleArray(2)
        val segments = (path.size - 1).coerceAtLeast(1)

        // 1. Exact nearest segment for every vertex within RASTER of the road.
        for (j in 0 until segments) {
            val a = path[j]
            val b = path[(j + 1).coerceAtMost(path.size - 1)]
            val c0 = floor((minOf(a.x, b.x) - RASTER - minX) / cell).toInt().coerceIn(0, cols - 1)
            val c1 = floor((maxOf(a.x, b.x) + RASTER - minX) / cell).toInt().coerceIn(0, cols - 1) + 1
            val r0 = floor((minOf(a.z, b.z) - RASTER - minZ) / cell).toInt().coerceIn(0, rows - 1)
            val r1 = floor((maxOf(a.z, b.z) + RASTER - minZ) / cell).toInt().coerceIn(0, rows - 1) + 1
            for (r in r0 until r1.coerceAtMost(rows)) for (c in c0 until c1.coerceAtMost(cols)) {
                val i = t.index(c, r)
                val d = segmentDistance(j, t.x(c), t.z(r), out)
                if (d < dist[i]) { dist[i] = d; seg[i] = j; roadY[i] = out[0]; roadZ[i] = out[1] }
            }
        }
        // 2. Further out, propagate the nearest segment from the neighbours (two sweeps, like a distance transform).
        fun relax(i: Int, c: Int, r: Int, nc: Int, nr: Int) {
            if (nc < 0 || nr < 0 || nc >= cols || nr >= rows) return
            val cand = seg[t.index(nc, nr)]
            if (cand < 0 || cand == seg[i]) return
            val d = segmentDistance(cand, t.x(c), t.z(r), out)
            if (d < dist[i]) { dist[i] = d; seg[i] = cand; roadY[i] = out[0]; roadZ[i] = out[1] }
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = t.index(c, r)
            relax(i, c, r, c - 1, r); relax(i, c, r, c, r - 1); relax(i, c, r, c - 1, r - 1); relax(i, c, r, c + 1, r - 1)
        }
        for (r in rows - 1 downTo 0) for (c in cols - 1 downTo 0) {
            val i = t.index(c, r)
            relax(i, c, r, c + 1, r); relax(i, c, r, c, r + 1); relax(i, c, r, c + 1, r + 1); relax(i, c, r, c - 1, r + 1)
        }
        // 3. Where another pass of the road (a switchback above or below) is also close, the ground takes
        //    the lower of the two, so the lower road is never buried; the upper one stands on its wall.
        val flatY = DoubleArray(n) { roadY[it] }
        for (j in 0 until segments) {
            val a = path[j]
            val b = path[(j + 1).coerceAtMost(path.size - 1)]
            val c0 = floor((minOf(a.x, b.x) - FLAT_R - minX) / cell).toInt().coerceIn(0, cols - 1)
            val c1 = floor((maxOf(a.x, b.x) + FLAT_R - minX) / cell).toInt().coerceIn(0, cols - 1) + 1
            val r0 = floor((minOf(a.z, b.z) - FLAT_R - minZ) / cell).toInt().coerceIn(0, rows - 1)
            val r1 = floor((maxOf(a.z, b.z) + FLAT_R - minZ) / cell).toInt().coerceIn(0, rows - 1) + 1
            for (r in r0 until r1.coerceAtMost(rows)) for (c in c0 until c1.coerceAtMost(cols)) {
                val i = t.index(c, r)
                val s = seg[i]
                if (s < 0) continue
                // Another pass: far away along the road, or running in another direction (the other leg
                // of a hairpin).
                var turn = abs(path[s].heading - a.heading) % (2 * PI)
                if (turn > PI) turn = 2 * PI - turn
                if (abs(path[s].d - a.d) < OTHER_PASS && turn < PI / 3) continue
                if (segmentDistance(j, t.x(c), t.z(r), out) < FLAT_R && out[0] < flatY[i]) flatY[i] = out[0]
            }
        }
        // 4. Deviation of the road from the mountainside plane, blurred into a smooth field.
        val offset = DoubleArray(n) { i -> if (seg[i] < 0) 0.0 else roadY[i] - hillside * roadZ[i] }
        blur(offset, cols, rows, 5, 3)

        val amp = when (biome) {
            Biome.CITY -> 0.0
            Biome.FIELDS -> 6.0
            Biome.VOLCANIC -> 18.0
            else -> 30.0
        }
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = t.index(c, r)
            val x = t.x(c)
            val z = t.z(r)
            val d = dist[i]
            val noise = fbm(x * 0.004, z * 0.004) * amp + fbm(x * 0.02, z * 0.02) * amp * 0.15
            val far = hillside * z + offset[i] + noise * smooth(((d - FLAT_R) / 160.0).coerceIn(0.0, 1.0))
            val near = flatY[i] - SINK
            val blend = smooth(((d - FLAT_R) / 45.0).coerceIn(0.0, 1.0))
            val h = near + (far - near) * blend
            t.heights[i] = h.toFloat()
            t.material[i] = materialFor(h, d, x, z)
        }
        carve(t)
        nearSeg = seg
        nearDist = dist
        return t
    }

    /**
     * The cells are coarse, so in tight bends a cell can still bulge over the asphalt. Wherever that
     * happens across the road, the four corners of that cell are lowered until the road is clear.
     */
    private fun carve(t: Terrain) {
        repeat(4) {
            var changed = false
            for (p in path) for (k in -4..4) {
                val off = k / 4.0 * ROAD_HALF
                val x = p.x - cos(p.heading) * off
                val z = p.z + sin(p.heading) * off
                val over = t.heightAt(x, z) - (p.y - CARVE_CLEAR)
                if (over <= 0) continue
                val c = floor((x - t.originX) / t.cell).toInt().coerceIn(0, t.cols - 2)
                val r = floor((z - t.originZ) / t.cell).toInt().coerceIn(0, t.rows - 2)
                for (i in intArrayOf(t.index(c, r), t.index(c + 1, r), t.index(c, r + 1), t.index(c + 1, r + 1))) {
                    t.heights[i] -= over.toFloat()
                }
                changed = true
            }
            if (!changed) return
        }
    }

    /** Separable box blur, [passes] times with [radius] cells; edges are clamped. */
    private fun blur(v: DoubleArray, cols: Int, rows: Int, radius: Int, passes: Int) {
        val tmp = DoubleArray(maxOf(cols, rows))
        repeat(passes) {
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    var s = 0.0
                    for (k in -radius..radius) s += v[r * cols + (c + k).coerceIn(0, cols - 1)]
                    tmp[c] = s / (2 * radius + 1)
                }
                for (c in 0 until cols) v[r * cols + c] = tmp[c]
            }
            for (c in 0 until cols) {
                for (r in 0 until rows) {
                    var s = 0.0
                    for (k in -radius..radius) s += v[(r + k).coerceIn(0, rows - 1) * cols + c]
                    tmp[r] = s / (2 * radius + 1)
                }
                for (r in 0 until rows) v[r * cols + c] = tmp[r]
            }
        }
    }

    /** Distance from (x, z) to the road, from the nearest segments of the closest terrain vertex. */
    private fun roadDistance(x: Double, z: Double): Double {
        val t = terrain
        val c = ((x - t.originX) / t.cell).let { kotlin.math.round(it).toInt() }.coerceIn(0, t.cols - 1)
        val r = ((z - t.originZ) / t.cell).let { kotlin.math.round(it).toInt() }.coerceIn(0, t.rows - 1)
        val out = DoubleArray(2)
        var best = Double.MAX_VALUE
        for (dr in -1..1) for (dc in -1..1) {
            val cc = c + dc
            val rr = r + dr
            if (cc < 0 || rr < 0 || cc >= t.cols || rr >= t.rows) continue
            val s = nearSeg[t.index(cc, rr)]
            if (s < 0) continue
            for (j in (s - 3).coerceAtLeast(0)..(s + 3).coerceAtMost(path.size - 2)) {
                val d = segmentDistance(j, x, z, out)
                if (d < best) best = d
            }
        }
        return best
    }

    private fun materialFor(h: Double, dist: Double, x: Double, z: Double): Byte {
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
        fun sign(p: PathSample, offset: Double, text: String, big: Boolean) {
            val (sx, sz) = beside(p, offset)
            out += Sign(sx, terrain.heightAt(sx, sz), sz, p.heading, text, big)
        }
        for (l in route.landmarks) sign(poseAt(l.distanceM), ROAD_HALF + 2.0, l.label, big = true)
        // Kilometre boards count down to the summit like the real ones.
        var km = 1.0
        while (km * 1000 < route.lengthM - 300) {
            val remaining = (route.lengthM - km * 1000) / 1000
            sign(poseAt(km * 1000), ROAD_HALF + 1.5, "noch %.1f km · %.0f %%".format(remaining, route.gradientAt(km * 1000 + 500)), big = false)
            km += 1.0
        }
        // Hairpin numbers, counted from the top down like the bends of the Alpe d'Huez; the board stands
        // just before the apex.
        val count = hairpinApex.size
        for ((i, apex) in hairpinApex.withIndex()) {
            sign(poseAt((apex - 25.0).coerceAtLeast(0.0)), ROAD_HALF + 2.5, "Kehre ${count - i}", big = false)
        }
        return out
    }

    private fun buildProps(): List<Prop> {
        val out = mutableListOf<Prop>()
        val t = terrain
        if (biome == Biome.CITY) {
            // Haussmann blocks along both sides of the boulevard.
            var d = 0.0
            while (d < route.lengthM) {
                val p = poseAt(d)
                for (side in listOf(-1.0, 1.0)) {
                    val (bx, bz) = beside(p, side * (ROAD_HALF + 14.0))
                    out += Prop(Prop.BUILDING, bx, t.heightAt(bx, bz), bz, rnd.range(0.85, 1.15), p.heading)
                }
                d += 26.0
            }
            var s = 0.0
            while (s < route.lengthM) {
                val p = poseAt(s)
                for (side in listOf(-1.0, 1.0)) {
                    val (tx, tz) = beside(p, side * (ROAD_HALF + 5.0))
                    out += Prop(Prop.BROADLEAF, tx, t.heightAt(tx, tz), tz, rnd.range(0.8, 1.1), rnd.range(0.0, 6.28))
                }
                s += 14.0
            }
            return out
        }
        // Trees and rocks scattered over the terrain, thinning out towards the tree line.
        val density = when (biome) { Biome.FIELDS -> 0.35; Biome.VOLCANIC -> 0.15; else -> 0.5 }
        for (r in 0 until t.rows) {
            for (c in 0 until t.cols) {
                val i = t.index(c, r)
                if (nearDist[i] > PROP_RANGE + CELL) {
                    // Keep the random sequence the same length so the scene does not depend on the range.
                    rnd.next(); rnd.next(); rnd.next()
                    continue
                }
                val m = t.material[i].toInt()
                val x = t.x(c) + rnd.range(-CELL / 2, CELL / 2)
                val z = t.z(r) + rnd.range(-CELL / 2, CELL / 2)
                val roll = rnd.next()
                val dist = roadDistance(x, z)
                if (dist < ROAD_HALF + 3.0 || dist > PROP_RANGE) continue
                val h = t.heightAt(x, z)
                val above = h - treeLineM
                val treeChance = density * (if (m == 1) 1.4 else if (m == 0) 0.45 else 0.0) * (1 - (above / 250.0).coerceIn(0.0, 1.0))
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
                // Facing the road from either verge.
                out += Prop(Prop.SPECTATOR, fx, t.heightAt(fx, fz), fz, rnd.range(0.9, 1.1), p.heading + if (side > 0) PI / 2 else -PI / 2)
                d += rnd.range(2.5, 7.0) * (1 + (route.lengthM - d) / 400)
            }
        }
        return out
    }

    /**
     * Point beside the road: positive [offset] is to the rider's right. The world is right-handed with y
     * up, so with the heading measured from +z towards +x the right-hand side is (-cos h, +sin h).
     */
    private fun beside(p: PathSample, offset: Double): Pair<Double, Double> =
        Pair(p.x - cos(p.heading) * offset, p.z + sin(p.heading) * offset)

    // --- helpers ------------------------------------------------------------------------------

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
        /** Vertices closer than this to the road are levelled with it: road half width + one cell diagonal. */
        const val FLAT_R = ROAD_HALF + CELL * 1.415 + 1.0
        /** The levelled ground sits this far below the asphalt, the road mesh covers the step with its kerb. */
        const val SINK = 0.35
        /** How far the road's grade carries on into the ground before the start and after the finish. */
        const val END_RAMP = 60.0
        /** Minimum gap between the road's centre line height and the ground under it. */
        const val CARVE_CLEAR = 0.05
        /** Search radius for the exact nearest road segment. */
        const val RASTER = FLAT_R + 2 * CELL
        /** Road distance from which a nearby piece of road counts as another pass (a switchback). */
        const val OTHER_PASS = 160.0

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
