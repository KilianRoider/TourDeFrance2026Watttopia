package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.LandCover
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.RoadClass
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.SceneryChunk
import app.rollentrainer.ui.world.geo.SceneryTileInput
import app.rollentrainer.ui.world.geo.race.RacePlan
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/*
 * Generation of one LOD-0 tile. Density is highest where the rider looks (the first 50 m beside
 * the road) and falls off with distance, because beyond a few hundred metres the satellite colour
 * of the terrain carries the look and every instance costs vertex work on a tablet.
 *
 * Exclusions: nothing stands on the carriageway or within the verge clearance of the route (any
 * leg of it: hairpins), on side roads, railways, water or inside building footprints. Solid things
 * (trees, shrubs, hedges, walls, crops, boulders) also keep out of the race crowd's band (W3's
 * RacePlan.crowdBand / campBand), where the spectators and campers stand.
 */
internal class TileJob(val inp: SceneryTileInput, val reg: Registry, neighbours: NeighbourBuildings? = null) {
    val ctx = inp.ctx
    val ox = inp.originX
    val oy = inp.originY
    val size = inp.sizeM
    val seed = Hash.of(ctx.seed.toLong(), inp.key.tx.toLong(), inp.key.ty.toLong(), 0x5CE7EL)
    val anchorY = floor(inp.heights.heights.minOrNull()?.toDouble() ?: 0.0)
    val out = InstanceStore(ox, oy, anchorY)
    val meshes = MeshStore()
    val route = RouteIndex(ctx.path, ox - 80, oy - 80, ox + size + 80, oy + size + 80, reach = 70.0)
    val dist = RouteDistanceField(ctx.path, ox, oy, size, 128, margin = 700.0)
    val ground = Ground(inp.heights, ctx.path, inp.corridor)
    val lc = inp.landCover
    val roads = LineIndex(ox - 20, oy - 20, size + 40, reach = 25.0)
    val blocked = Mask(ox - 30, oy - 30, size + 60, 2.0)
    val water = Mask(ox - 30, oy - 30, size + 60, 4.0)
    val centreLatLon = RegionPicker.latLon(ctx.meta, ox + size / 2, oy + size / 2)
    val meanZ = inp.heights.heights.average()
    val style: RegionStyle = RegionPicker.at(centreLatLon[0], centreLatLon[1], meanZ).let { if (it in reg.regions) it else ctx.region }
    val profile = Regions.profile(style)
    /** W3's race plan of the scene (shared with the race dressing): where the crowd stands. */
    val race: RacePlan = RacePlan.of(ctx)
    private val hit = RouteHit()
    /** Building centroids of this tile counted on a 20 m grid (for [builtUp]). */
    private val bCell = 20.0
    private val bN = (size / bCell).toInt() + 1
    private val bGrid = IntArray(bN * bN)

    init {
        inp.roads?.roads?.forEach { r ->
            val p = r.points
            val hw = (if (r.roadClass == RoadClass.RAIL) 2.5f else max(r.widthM, 2.5f) / 2f).toDouble()
            // Paths and tracks are narrow; motorways and railways need a wide berth.
            val kind = if (r.roadClass == RoadClass.RAIL) 1 else 0
            for (k in 0 until p.size / 2 - 1) {
                roads.add(ox + p[k * 2], oy + p[k * 2 + 1], ox + p[k * 2 + 2], oy + p[k * 2 + 3], hw, kind)
            }
        }
        inp.water?.features?.forEach { f ->
            if (f.isArea) {
                val rings = f.rings.map { r -> DoubleArray(r.size) { i -> (if (i % 2 == 0) ox else oy) + r[i] } }
                blocked.fillRings(rings, 1)
                water.fillRings(rings)
            } else {
                val r = f.rings.firstOrNull() ?: return@forEach
                for (k in 0 until r.size / 2 - 1) {
                    blocked.line(ox + r[k * 2], oy + r[k * 2 + 1], ox + r[k * 2 + 2], oy + r[k * 2 + 3], 3.0)
                    water.line(ox + r[k * 2], oy + r[k * 2 + 1], ox + r[k * 2 + 2], oy + r[k * 2 + 3], 2.0)
                }
            }
        }
        inp.buildings?.buildings?.forEach { b ->
            val ring = DoubleArray(b.ring.size) { i -> (if (i % 2 == 0) ox else oy) + b.ring[i] }
            blocked.fillRings(listOf(ring), 1)
            var cx = 0.0
            var cy = 0.0
            for (k in 0 until b.ring.size / 2) { cx += b.ring[k * 2]; cy += b.ring[k * 2 + 1] }
            val n = max(1, b.ring.size / 2)
            val c = floor(cx / n / bCell).toInt()
            val r = floor(cy / n / bCell).toInt()
            if (c in 0 until bN && r in 0 until bN) bGrid[r * bN + c]++
        }
        // The neighbours' footprints reaching into the tile (and its mask margin) block it too; their
        // own tile builds them.
        neighbours?.ringsReaching(inp.key, ox - 30, oy - 30, ox + size + 30, oy + size + 30)?.forEach { blocked.fillRings(listOf(it), 1) }
    }

    /**
     * Whether there is a building within ~[radius] m (grid resolution 20 m), or the land cover is
     * built-up there. Town limits from the places data include fields and woods between hamlets;
     * street lamps belong where the houses are.
     */
    fun builtUp(x: Double, y: Double, radius: Double = 45.0): Boolean {
        if (landCover(x, y) == LandCover.BUILT) return true
        val c0 = floor((x - ox - radius) / bCell).toInt()
        val c1 = floor((x - ox + radius) / bCell).toInt()
        val r0 = floor((y - oy - radius) / bCell).toInt()
        val r1 = floor((y - oy + radius) / bCell).toInt()
        for (r in max(0, r0)..min(bN - 1, r1)) for (c in max(0, c0)..min(bN - 1, c1)) if (bGrid[r * bN + c] > 0) return true
        return false
    }

    fun run(): SceneryChunk {
        Vegetation(this).run()
        Roadside(this).run()
        Buildings(this).run()
        return SceneryChunk(ox, anchorY, -oy, out.build(), meshes.build())
    }

    // ------------------------------------------------------------------ queries

    fun inTile(x: Double, y: Double) = x >= ox && x < ox + size && y >= oy && y < oy + size

    fun landCover(x: Double, y: Double): Int = lc?.classAt(x, y) ?: LandCover.GRASS

    /** Nearest route point within 70 m, or null. The returned object is reused. */
    fun routeHit(x: Double, y: Double): RouteHit? = if (route.nearest(x, y, hit)) hit else null

    /** Distance beyond the route's road edge (large when far away). */
    fun beyondRoute(x: Double, y: Double): Double = if (route.nearest(x, y, hit)) hit.beyondEdge else 1e3

    /**
     * Whether a thing with a footprint radius [r] may stand at (x, y): not on the route within
     * [clearRoute] m of its edge, not within [clearSide] of a side road or railway, not on water or
     * in a building.
     */
    fun free(x: Double, y: Double, clearRoute: Double, clearSide: Double = 1.5, r: Double = 0.0): Boolean {
        if (!inTile(x, y)) return false
        val near = route.nearest(x, y, hit)
        if ((if (near) hit.beyondEdge else 1e3) < clearRoute + r) return false
        if (roads.edgeDistance(x, y) < clearSide + r) return false
        if (blocked.get(x, y)) return false
        val c = landCover(x, y)
        if (c == LandCover.WATER || c == LandCover.ROAD) return false
        if (near && inCrowdBand(hit, c, r)) return false
        return true
    }

    /**
     * Whether something with footprint radius [r] at (x, y) would stand among the race's spectators
     * (W3, RacePlan): inside the crowd band of a busy stretch, or, off forest cover (camps never park
     * in the wood), where campers may stand on the big climbs. Deterministic per route.
     */
    fun inCrowdBand(x: Double, y: Double, r: Double = 0.0): Boolean {
        val h = routeHit(x, y) ?: return false
        return inCrowdBand(h, landCover(x, y), r)
    }

    private fun inCrowdBand(h: RouteHit, cover: Int, r: Double): Boolean {
        val crowd = race.crowdBand(h.s)
        if (crowd > 0 && h.dist - r < crowd) return true
        val camp = race.campBand(h.s)
        return camp > 0 && h.dist - r < camp && cover != LandCover.TREE
    }

    fun groundAt(x: Double, y: Double): Double = ground.at(x, y, routeHit(x, y))

    fun latLon(x: Double, y: Double) = RegionPicker.latLon(ctx.meta, x, y)

    /** Species for a band list at height [z] (with a little jitter so bands blend). */
    fun pickSpecies(bands: List<Pair<Float, Map<Species, Float>>>, z: Double, rnd: Rand): Species? {
        val zj = z + rnd.sym(90f)
        val band = bands.firstOrNull { zj < it.first } ?: bands.lastOrNull() ?: return null
        val keys = band.second.keys.toList()
        val w = FloatArray(keys.size) { band.second.getValue(keys[it]) }
        val s = keys[rnd.weighted(w)]
        return if (reg.has(s.id)) s else keys.firstOrNull { reg.has(it.id) }
    }

    fun pickWeighted(map: Map<Species, Float>, rnd: Rand): Species? {
        val keys = map.keys.toList()
        if (keys.isEmpty()) return null
        val w = FloatArray(keys.size) { map.getValue(keys[it]) }
        val s = keys[rnd.weighted(w)]
        return if (reg.has(s.id)) s else null
    }

    fun <T> pickMap(map: Map<T, Float>, rnd: Rand): T {
        val keys = map.keys.toList()
        val w = FloatArray(keys.size) { map.getValue(keys[it]) }
        return keys[rnd.weighted(w)]
    }

    /** Puts a tree of species [sp] with natural size variation; [scale] multiplies the species height. */
    fun tree(sp: Species, x: Double, y: Double, scale: Float, rnd: Rand, sink: Double = 0.3) {
        if (!reg.has(sp.id)) return
        val z = groundAt(x, y) - sink * scale
        out.put(sp.id, x, y, z, rnd.angle(), scale, InstanceStore.tint(rnd, 0.1f, 0.05f), rnd.f(), 0f)
    }

    companion object {
        const val DEG = (PI / 180.0)
    }
}

/** Vegetation from land cover, rows, avenues and fields. */
internal class Vegetation(private val j: TileJob) {
    private val p = j.profile
    private val lc = j.lc
    private val cells = lc?.cells ?: 0
    private val cellM = if (cells > 0) j.size / cells else 10.0

    fun run() {
        if (lc != null) coverPass()
        avenues()
        riverRows()
        Fields(j).run()
        roadsideGround()
    }

    private fun cls(c: Int, r: Int): Int {
        if (c < 0 || r < 0 || c >= cells || r >= cells) return -1
        return lc!!.classes[r * cells + c].toInt() and 0xFF
    }

    /** Cell centre in pack coordinates (row 0 is north). */
    private fun cx(c: Int) = j.ox + (c + 0.5) * cellM
    private fun cy(r: Int) = j.oy + j.size - (r + 0.5) * cellM

    /** Trees per m² in forest by distance from the route. */
    private fun forestDensity(d: Double): Double = when {
        d < 50 -> 1.0 / 38
        d < 150 -> lerp(1.0 / 38, 1.0 / 70, (d - 50) / 100)
        d < 500 -> lerp(1.0 / 70, 1.0 / 140, (d - 150) / 350)
        else -> 1.0 / 190
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t.coerceIn(0.0, 1.0)

    private fun coverPass() {
        for (r in 0 until cells) for (c in 0 until cells) {
            val k = cls(c, r)
            if (k == LandCover.TREE || k == LandCover.SHRUB || k == LandCover.GRASS || k == LandCover.CROP || k == LandCover.BUILT ||
                k == LandCover.BARE || k == LandCover.MOSS || k == LandCover.WETLAND || k == LandCover.SNOW) {
                val rnd = Rand(Hash.of(j.seed, c.toLong(), r.toLong()))
                val x = cx(c)
                val y = cy(r)
                val d = j.dist.at(x, y)
                when (k) {
                    LandCover.TREE -> treeCell(c, r, x, y, d, rnd)
                    LandCover.SHRUB -> shrubCell(x, y, d, rnd)
                    LandCover.GRASS -> grassCell(c, r, x, y, d, rnd)
                    LandCover.CROP -> cropCell(c, r, x, y, d, rnd)
                    LandCover.BUILT -> builtCell(x, y, d, rnd)
                    LandCover.BARE, LandCover.MOSS -> bareCell(x, y, d, rnd, k)
                    LandCover.WETLAND -> if (d < 400 && rnd.chance(0.3f)) shrubAt(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 0)
                }
            }
        }
    }

    private fun treeNeighbours(c: Int, r: Int): Int {
        var n = 0
        for (dr in -1..1) for (dc in -1..1) if ((dr != 0 || dc != 0) && cls(c + dc, r + dr) == LandCover.TREE) n++
        return n
    }

    private fun treeCell(c: Int, r: Int, x: Double, y: Double, d: Double, rnd: Rand) {
        val n8 = treeNeighbours(c, r)
        val z = j.ground.terrain(x, y)
        val tl = p.treeline.toDouble()
        // Near and above the tree line trees get smaller and sparser; the land cover still says
        // "tree", so keep a few (krummholz) rather than nothing.
        val alpine = ((z - (tl - 250)) / 350).coerceIn(0.0, 1.0)
        if (n8 <= 2) {
            linearCell(c, r, x, y, d, rnd, n8)
            return
        }
        var density = forestDensity(d) * (1.0 - 0.75 * alpine)
        if (n8 < 7) density *= 0.85
        val count = (density * cellM * cellM + rnd.d()).toInt()
        for (i in 0 until count) {
            val px = x + rnd.sym(cellM.toFloat() * 0.5f)
            val py = y + rnd.sym(cellM.toFloat() * 0.5f)
            if (!j.free(px, py, clearRoute = 2.2 + rnd.f() * 2.5, clearSide = 1.5)) continue
            if (j.ground.slope(px, py) > 1.4) continue
            val sp = (if (alpine > 0.85 && rnd.chance(0.6f)) Species.MUGO else j.pickSpecies(p.forest, z, rnd)) ?: continue
            val base = (0.92f + rnd.gauss() * 0.12f).coerceIn(0.68f, 1.2f)
            val scale = base * (1f - 0.45f * alpine.toFloat()) * (if (n8 < 7) 0.92f else 1f)
            j.tree(sp, px, py, scale, rnd)
        }
        // Forest edge: shrubs on the open side within view of the road.
        if (n8 < 7 && d < 300) {
            for ((dc, dr) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val k = cls(c + dc, r + dr)
                if ((k == LandCover.GRASS || k == LandCover.CROP || k == LandCover.SHRUB) && rnd.chance(0.55f)) {
                    val px = x + dc * cellM * 0.5 + rnd.sym(3f)
                    val py = y - dr * cellM * 0.5 + rnd.sym(3f)
                    shrubAt(px, py, rnd, if (alpine > 0.6) 3 else -1)
                }
            }
        }
    }

    /** Thin lines of tree cells: hedgerows in bocage country, tree rows and single trees elsewhere. */
    private fun linearCell(c: Int, r: Int, x: Double, y: Double, d: Double, rnd: Rand, n8: Int) {
        if (d > 900) {
            if (rnd.chance(0.5f)) openTree(x + rnd.sym(3f), y + rnd.sym(3f), rnd, 0.95f)
            return
        }
        if (p.bocage > 0.3f && n8 >= 1 && d < 600) {
            // Direction of the hedgerow from the neighbouring tree cells (5 × 5 window).
            var sx = 0.0
            var sy = 0.0
            for (dr in -2..2) for (dc in -2..2) {
                if ((dr != 0 || dc != 0) && cls(c + dc, r + dr) == LandCover.TREE) {
                    // fold opposite directions together (a line, not a vector)
                    val ax = dc.toDouble()
                    val ay = -dr.toDouble()
                    val a = atan2(ay, ax) * 2
                    sx += cos(a)
                    sy += sin(a)
                }
            }
            val ang = atan2(sy, sx) / 2
            val dx = cos(ang)
            val dy = sin(ang)
            for (k in -1..1) {
                val px = x + dx * k * 3.6
                val py = y + dy * k * 3.6
                if (!j.free(px, py, clearRoute = 2.5, clearSide = 1.5, r = 0.8)) continue
                val z = j.groundAt(px, py)
                j.out.put("scn_hedge", px, py, z - 0.15, InstanceStore.yawAlong(dx, dy) + rnd.sym(0.08f), rnd.range(0.85f, 1.15f),
                    InstanceStore.tint(rnd, 0.1f, 0.05f), rnd.f())
            }
            if (rnd.chance(0.38f)) openTree(x + rnd.sym(2f), y + rnd.sym(2f), rnd, 0.9f)
        } else {
            val n = if (n8 == 0) 1 else 1 + rnd.int(2)
            for (i in 0 until n) openTree(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 0.95f)
        }
    }

    /** A free-standing tree (fields, pastures, hedgerows): open-grown species, broad and full. */
    fun openTree(x: Double, y: Double, rnd: Rand, scaleBase: Float) {
        if (!j.free(x, y, clearRoute = 3.0, clearSide = 2.0)) return
        val z = j.ground.terrain(x, y)
        if (z > p.treeline + 50) return
        val sp = j.pickSpecies(p.open, z, rnd) ?: return
        val scale = (scaleBase * (0.85f + rnd.gauss() * 0.13f)).coerceIn(0.55f, 1.2f)
        j.tree(sp, x, y, scale, rnd)
    }

    /** kind: -1 regional default, 0 temperate, 1 garrigue, 2 gorse, 3 rhododendron. */
    fun shrubAt(x: Double, y: Double, rnd: Rand, kind: Int) {
        if (!j.free(x, y, clearRoute = 1.2, clearSide = 1.0)) return
        val k = if (kind >= 0) kind else p.shrubKind
        val id = when (k) {
            1 -> "scn_garrigue"
            2 -> if (rnd.chance(0.6f)) "scn_gorse" else "scn_shrub"
            3 -> "scn_rhodo"
            else -> "scn_shrub"
        }.let { if (j.reg.has(it)) it else "scn_shrub" }
        val z = j.groundAt(x, y)
        j.out.put(id, x, y, z - 0.1, rnd.angle(), rnd.range(0.7f, 1.25f), InstanceStore.tint(rnd, 0.12f, 0.06f), rnd.f())
    }

    private fun shrubCell(x: Double, y: Double, d: Double, rnd: Rand) {
        if (d > 450) return
        val z = j.ground.terrain(x, y)
        val alpine = z > p.treeline - 150
        val density = if (d < 60) 1.0 / 22 else if (d < 200) 1.0 / 45 else 1.0 / 90
        val n = (density * cellM * cellM + rnd.d()).toInt()
        for (i in 0 until n) {
            val px = x + rnd.sym(5f)
            val py = y + rnd.sym(5f)
            if (alpine) {
                if (rnd.chance(0.25f) && j.reg.has("scn_tree_mugo")) {
                    if (j.free(px, py, 2.0)) j.tree(Species.MUGO, px, py, rnd.range(0.7f, 1.2f), rnd, 0.1)
                } else shrubAt(px, py, rnd, 3)
            } else shrubAt(px, py, rnd, -1)
        }
        if (!alpine && rnd.chance(0.12f)) openTree(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 0.8f)
    }

    private fun grassCell(c: Int, r: Int, x: Double, y: Double, d: Double, rnd: Rand) {
        val z = j.ground.terrain(x, y)
        val tl = p.treeline
        if (z > tl - 100 && z < tl + 450 && d < 300) {
            // Alpine pasture above the forest: rhododendron and juniper patches, dwarf pines.
            if (rnd.chance(if (d < 80) 0.35f else 0.15f)) shrubAt(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 3)
            if (z < tl + 150 && rnd.chance(0.04f) && j.reg.has("scn_tree_mugo")) {
                val px = x + rnd.sym(4f); val py = y + rnd.sym(4f)
                if (j.free(px, py, 2.0)) j.tree(Species.MUGO, px, py, rnd.range(0.7f, 1.1f), rnd, 0.1)
            }
        }
        if (z > 1300 && (j.style == RegionStyle.ALPS || j.style == RegionStyle.PYRENEES) && d < 500 && rnd.chance(if (d < 100) 0.05f else 0.02f)) {
            boulder(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 0.4f, 1.4f)
        }
        if (z > tl) return
        // Isolated pasture trees, a little more often next to woods (the satellite colour shows them).
        var chance = if (d < 600) 0.035f else 0.015f
        if (treeNeighbours(c, r) > 0) chance *= 2.5f
        if (rnd.chance(chance)) openTree(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 1f)
        if (d < 200 && rnd.chance(0.03f)) shrubAt(x + rnd.sym(4f), y + rnd.sym(4f), rnd, -1)
    }

    private fun cropCell(c: Int, r: Int, x: Double, y: Double, d: Double, rnd: Rand) {
        // Lone field trees and field-edge shrubs where the crop meets something else.
        var edge = false
        for ((dc, dr) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
            val k = cls(c + dc, r + dr)
            if (k != LandCover.CROP && k != LandCover.ROAD && k >= 0) edge = true
        }
        if (edge && rnd.chance(0.05f)) openTree(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 1f)
        else if (!edge && d < 800 && rnd.chance(0.004f)) openTree(x + rnd.sym(4f), y + rnd.sym(4f), rnd, 1.05f)
        if (edge && d < 250 && rnd.chance(0.12f)) shrubAt(x + rnd.sym(4f), y + rnd.sym(4f), rnd, -1)
    }

    private fun builtCell(x: Double, y: Double, d: Double, rnd: Rand) {
        if (d > 600) return
        // Garden and street trees between the houses.
        if (rnd.chance(if (d < 100) 0.22f else 0.12f)) {
            val px = x + rnd.sym(5f)
            val py = y + rnd.sym(5f)
            if (!j.free(px, py, clearRoute = 3.0, clearSide = 2.5, r = 1.0)) return
            val sp = j.pickWeighted(p.garden, rnd) ?: return
            j.tree(sp, px, py, (0.7f + rnd.gauss() * 0.1f).coerceIn(0.5f, 0.95f), rnd)
        }
        if (d < 300 && rnd.chance(0.15f)) shrubAt(x + rnd.sym(5f), y + rnd.sym(5f), rnd, 0)
    }

    private fun bareCell(x: Double, y: Double, d: Double, rnd: Rand, k: Int) {
        if (d > 700) return
        val density = if (d < 80) 1.0 / 110 else if (d < 300) 1.0 / 300 else 1.0 / 700
        val n = (density * cellM * cellM * (if (k == LandCover.MOSS) 0.5 else 1.0) + rnd.d()).toInt()
        for (i in 0 until n) boulder(x + rnd.sym(5f), y + rnd.sym(5f), rnd, 0.3f, if (rnd.chance(0.1f)) 3.0f else 1.3f)
    }

    fun boulder(x: Double, y: Double, rnd: Rand, minS: Float, maxS: Float) {
        if (!j.free(x, y, clearRoute = 1.5, clearSide = 1.0)) return
        val id = "scn_boulder_${p.rock.removePrefix("rock_")}_${rnd.int(3)}"
        if (!j.reg.has(id)) return
        val s = minS + (maxS - minS) * rnd.f() * rnd.f()
        val z = j.groundAt(x, y)
        j.out.put(id, x, y, z - 0.25 * s, rnd.angle(), s, InstanceStore.tint(rnd, 0.1f, 0.02f), 0f)
    }

    // ------------------------------------------------------------------ rows

    /**
     * Roadside avenues: the plane trees of the south and centre, planted ~11 m apart on both
     * sides of straight country roads, often for a kilometre or more near towns.
     */
    private fun avenues() {
        val sp = p.avenue ?: return
        if (p.avenueChance <= 0f || !j.reg.has(sp.id)) return
        val path = j.ctx.path
        val first = j.route.firstSample
        val last = j.route.lastSample
        if (last <= first) return
        val s0 = path.distanceOf(first)
        val s1 = path.distanceOf(last)
        val spacing = 11.0
        var s = floor(s0 / spacing) * spacing
        while (s <= s1) {
            val stretch = floor(s / 900.0).toLong()
            val h = Hash.unit(Hash.of(j.ctx.seed.toLong(), stretch, 0xA7E))
            // Avenues lead into towns: likelier within 2 km of a town stretch.
            val nearTown = j.ctx.places.towns.any { s > it.sIn - 2000 && s < it.sOut + 2000 }
            val chance = p.avenueChance * (if (nearTown) 1.6f else 0.6f)
            if (h < chance) avenueAt(s, sp, stretch)
            s += spacing
        }
    }

    private fun avenueAt(s: Double, sp: Species, stretch: Long) {
        val path = j.ctx.path
        val pose = path.poseAt(s)
        if (!j.inTile(pose.x, pose.y)) return
        if (pose.flags and (PathFlags.TUNNEL or PathFlags.BRIDGE) != 0) return
        if (pose.roadClass !in 3..7) return
        // A circuit's later laps: the first one has planted the avenue (RacePlan.repeat).
        if (j.race.repeatAt(s)) return
        // Straight roads only: avenues line straight roads; bends would put trees into the road.
        val a = path.poseAt(s - 40)
        val b = path.poseAt(s + 40)
        val turn = abs(angleDiff(atan2(a.dirY, a.dirX), atan2(b.dirY, b.dirX)))
        if (turn > 25 * TileJob.DEG) return
        val inTown = j.ctx.places.towns.any { s >= it.sIn && s <= it.sOut }
        if (inTown && j.style != RegionStyle.PARIS && j.style != RegionStyle.MEDITERRANEAN && j.style != RegionStyle.CATALONIA) return
        val rnd = Rand(Hash.of(j.seed, (s * 10).toLong(), 0xA11))
        val sideMode = Hash.unit(Hash.of(stretch, 0x51DE)) // both sides mostly, one side sometimes
        for (side in intArrayOf(1, -1)) {
            if (sideMode < 0.25f && side == -1) continue
            if (rnd.chance(0.07f)) continue // a missing tree
            // Trunks ~2 m beyond the asphalt edge (French avenues stand close; the verge stays usable).
            val off = pose.widthM / 2 + 2.15 + rnd.f() * 0.6
            val nx = -pose.dirY * side
            val ny = pose.dirX * side
            val x = pose.x + nx * off + pose.dirX * rnd.sym(0.6f)
            val y = pose.y + ny * off + pose.dirY * rnd.sym(0.6f)
            val k = j.landCover(x + nx * 4, y + ny * 4)
            if (k == LandCover.TREE || k == LandCover.WATER) continue
            if (!j.free(x, y, clearRoute = 1.6, clearSide = 3.0, r = 0.45)) continue
            j.tree(sp, x, y, (0.82f + rnd.gauss() * 0.07f).coerceIn(0.68f, 0.98f), rnd, 0.2)
        }
    }

    /** Poplar rows along rivers and streams near the road. */
    private fun riverRows() {
        if (p.poplarRows <= 0f || !j.reg.has(Species.POPLAR.id)) return
        val feats = j.inp.water?.features ?: return
        for ((fi, f) in feats.withIndex()) {
            if (f.isArea) continue
            val r = f.rings.firstOrNull() ?: continue
            var acc = 0.0
            for (k in 0 until r.size / 2 - 1) {
                val ax = j.ox + r[k * 2]; val ay = j.oy + r[k * 2 + 1]
                val bx = j.ox + r[k * 2 + 2]; val by = j.oy + r[k * 2 + 3]
                val len = hypot(bx - ax, by - ay)
                if (len < 1e-3) continue
                val dx = (bx - ax) / len; val dy = (by - ay) / len
                var t = (7.0 - acc % 7.0) % 7.0
                while (t < len) {
                    val px = ax + dx * t
                    val py = ay + dy * t
                    val stretch = floor((acc + t) / 160.0).toLong()
                    val h = Hash.unit(Hash.of(j.seed, fi.toLong(), stretch))
                    if (h < p.poplarRows * 0.45f && j.dist.at(px, py) < 900) {
                        val side = if (Hash.unit(Hash.of(stretch, fi.toLong(), 3)) < 0.5f) 1 else -1
                        val x = px - dy * side * 5.5
                        val y = py + dx * side * 5.5
                        val rnd = Rand(Hash.of(j.seed, (x * 7).toLong(), (y * 7).toLong()))
                        if (j.free(x, y, clearRoute = 3.0, clearSide = 2.0) && j.landCover(x, y) != LandCover.BUILT) {
                            j.tree(Species.POPLAR, x, y, (0.85f + rnd.gauss() * 0.08f).coerceIn(0.7f, 1.05f), rnd, 0.2)
                        }
                    }
                    t += 7.0
                }
                acc += len
            }
        }
    }

    // ------------------------------------------------------------------ verge

    /**
     * Grass tufts and wild flowers on the verges, dense in the first metres beside the asphalt,
     * ferns at forest edges. Only for path samples inside this tile (each sample has one owner).
     */
    private fun roadsideGround() {
        val path = j.ctx.path
        val dry = j.style == RegionStyle.CATALONIA || j.style == RegionStyle.MEDITERRANEAN
        val south = dry || j.style == RegionStyle.SOUTH_WEST
        for (i in j.route.firstSample..j.route.lastSample) {
            if (i >= path.size) break
            val x0 = path.x[i]
            val y0 = path.y[i]
            if (!j.inTile(x0, y0)) continue
            if (path.flags[i].toInt() and (PathFlags.TUNNEL or PathFlags.BRIDGE) != 0) continue
            if (j.race.repeat[i]) continue
            val ia = max(0, i - 1)
            val ib = min(path.size - 1, i + 1)
            val dl = hypot(path.x[ib] - path.x[ia], path.y[ib] - path.y[ia]).coerceAtLeast(1e-6)
            val dx = (path.x[ib] - path.x[ia]) / dl
            val dy = (path.y[ib] - path.y[ia]) / dl
            val hw = path.width[i] / 2.0
            val rnd = Rand(Hash.of(j.seed, i.toLong(), 0x7AF7))
            val town = j.ctx.places.towns.any { path.distanceOf(i) in it.sIn..it.sOut }
            for (side in intArrayOf(1, -1)) {
                val free = j.ground.freeAt(i, side > 0)
                val offs = j.ground.offsets(i)
                val reach = if (offs != null && free > 0) offs[min(free, offs.size) - 1].toDouble() + 5.0 else 6.0
                val n = if (town) 3 else 13
                for (k in 0 until n) {
                    // Most tufts right at the edge where the mower does not reach, fewer further out.
                    val o = 0.35 + rnd.d() * rnd.d() * min(reach, 16.0)
                    val along = rnd.sym(2.6f)
                    val nx = -dy * side
                    val ny = dx * side
                    val x = x0 + nx * (hw + o) + dx * along
                    val y = y0 + ny * (hw + o) + dy * along
                    if (!j.inTile(x, y)) continue
                    val h = j.routeHit(x, y) ?: continue
                    if (h.beyondEdge < 0.3) continue
                    if (j.roads.edgeDistance(x, y) < 0.4 || j.blocked.get(x, y)) continue
                    val cover = j.landCover(x, y)
                    if (cover == LandCover.WATER || cover == LandCover.SNOW) continue
                    // No weeds on town pavements: in towns only where the cover is green.
                    if (town && (cover == LandCover.BUILT || cover == LandCover.ROAD)) continue
                    val z = j.ground.at(x, y, h)
                    val alpine = z > p.treeline - 300
                    val id = when {
                        cover == LandCover.TREE && rnd.chance(0.35f) && !dry -> "scn_fern"
                        cover == LandCover.BARE && rnd.chance(0.5f) -> continue
                        rnd.chance(0.16f) -> when {
                            alpine && j.reg.has("scn_flowers_alpine") -> "scn_flowers_alpine"
                            rnd.chance(if (south) 0.45f else 0.3f) -> "scn_poppies"
                            else -> "scn_flowers"
                        }
                        dry && j.reg.has("scn_grass_dry") && rnd.chance(0.7f) -> "scn_grass_dry"
                        else -> "scn_grass"
                    }
                    val sc = if (id == "scn_fern") rnd.range(0.7f, 1.2f) else rnd.range(0.65f, 1.35f) * (if (o < 1.2) 0.8f else 1f)
                    j.out.put(id, x, y, z - 0.03, rnd.angle(), sc, InstanceStore.tint(rnd, 0.14f, 0.07f), rnd.f())
                }
            }
        }
    }

    companion object {
        fun angleDiff(a: Double, b: Double): Double {
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return d
        }
    }
}
