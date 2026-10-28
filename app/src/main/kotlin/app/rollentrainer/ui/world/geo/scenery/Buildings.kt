package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.Building
import app.rollentrainer.core.geo.LandCover
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

/** What kind of building a footprint becomes (decides textures, storeys and roof). */
internal enum class Arch { HOUSE, TOWN, APARTMENT, FARM, BARN, SHED, INDUSTRIAL, CHURCH, CHALET, GREENHOUSE }

/**
 * Buildings from the BUILDINGS footprints: walls on the real footprint with the facade texture's
 * window rhythm fitted to each wall (whole bays, storeys counted down from the eaves so the top
 * row of windows sits under the roof), roofs by roof shape (regional default when unknown),
 * merged per material into a few meshes per tile.
 *
 * Pitched roofs need a rectangle: near-rectangular footprints use their minimum bounding
 * rectangle, L / T / U shapes are split into up to four rectangles whose roofs intersect like
 * real cross gables, anything else gets a flat roof (with a parapet in towns).
 */
internal class Buildings(private val j: TileJob) {
    private val p = j.profile
    private val tm = TileMesh(j.ox, j.oy, j.anchorY)
    private val plainDark = lin(0x4A4440)

    var count = 0
        private set

    fun run() {
        val list = j.inp.buildings?.buildings ?: return
        val fps = ArrayList<Pair<Building, DoubleArray>>(list.size)
        for (b in list) {
            if (b.buildingClass == ROOF_ONLY || b.ring.size < 6) continue
            val pts = DoubleArray(b.ring.size) { i -> (if (i % 2 == 0) j.ox else j.oy) + b.ring[i] }
            fps += b to pts
        }
        // Neighbour density decides village / town looks.
        val grid = HashMap<Long, Int>()
        val cents = fps.map { (_, pts) -> centroid(pts) }
        for (c in cents) grid.merge(cellKey(c[0], c[1]), 1, Int::plus)
        for ((k, bp) in fps.withIndex()) {
            val c = cents[k]
            var dense = 0
            val gx = floor(c[0] / DENS_M).toLong(); val gy = floor(c[1] / DENS_M).toLong()
            for (dy in -1L..1L) for (dx in -1L..1L) dense += grid[((gx + dx) shl 32) xor ((gy + dy) and 0xFFFFFFFFL)] ?: 0
            try {
                one(bp.first, bp.second, c, dense)
                count++
            } catch (e: RuntimeException) {
                // A malformed footprint must not cost the tile its scenery.
            }
        }
    }

    private fun cellKey(x: Double, y: Double): Long = (floor(x / DENS_M).toLong() shl 32) xor (floor(y / DENS_M).toLong() and 0xFFFFFFFFL)

    private fun centroid(p: DoubleArray): DoubleArray {
        var sx = 0.0; var sy = 0.0
        val n = p.size / 2
        for (i in 0 until n) { sx += p[i * 2]; sy += p[i * 2 + 1] }
        return doubleArrayOf(sx / n, sy / n)
    }

    // ------------------------------------------------------------------ one building

    private class Plan(
        val arch: Arch,
        val facade: TexSize.Facade,
        val upper: TexSize.Facade?,       // chalets: wooden upper storeys
        val floors: Int,
        val roof: RoofKind,
        val pitch: Double,
        val eaves: Double,
        val roofTex: String,
        val tint: FloatArray,
        val roofTint: FloatArray,
        val shopFront: Boolean,
        val chimney: Boolean,
        val ridgeAlongRoad: Boolean,
        val parapet: Boolean,
        /** Ground-floor variant with a front door, for the wall facing the road. */
        val door: TexSize.Facade?,
    )

    private fun one(b: Building, raw: DoubleArray, c: DoubleArray, dense: Int) {
        var pts = Geom2.simplify(raw)
        if (Geom2.signedArea(pts) < 0) pts = Geom2.reversed(pts)
        val area = Geom2.signedArea(pts)
        if (area < 5.0 || pts.size < 6) return
        val rnd = Rand(Hash.of(j.ctx.seed.toLong(), (c[0] * 10).roundToInt().toLong(), (c[1] * 10).roundToInt().toLong(), 0xB01D))
        val obb = Geom2.obb(pts)
        val plan = plan(b, area, obb, c, dense, rnd)
        val zb = b.baseZ.toDouble() + (if (b.minHeightM > 0.5f) b.minHeightM.toDouble() else -0.6)
        val ground = b.baseZ.toDouble() + 0.25
        val shopH = if (plan.shopFront) 3.5 else 0.0
        val upperFloors = if (plan.shopFront) plan.floors - 1 else plan.floors
        val ze = ground + shopH + upperFloors * plan.facade.storeyM
        val fill = area / obb.area

        // Roof geometry first: it decides whether walls need a parapet.
        var kind = plan.roof
        val rects: List<Geom2.Obb> = when {
            kind == RoofKind.FLAT -> emptyList()
            fill >= 0.86 -> listOf(obb)
            else -> decompose(pts, obb)?.also { if (kind != RoofKind.GABLED && kind != RoofKind.HIPPED) kind = RoofKind.GABLED } ?: emptyList()
        }
        if (rects.isEmpty()) kind = RoofKind.FLAT
        val parapet = kind == RoofKind.FLAT && plan.parapet
        val wallTop = if (parapet) ze + 0.55 else ze

        walls(pts, zb, ground, ze, wallTop, plan)
        when (kind) {
            RoofKind.FLAT -> flatRoof(pts, ze, parapet, plan)
            else -> {
                if (rects.size > 1) ceiling(pts, ze - 0.06)
                for ((k, r0) in rects.withIndex()) {
                    val r = if (k == 0 && plan.ridgeAlongRoad) alongRoad(r0) else r0
                    when (kind) {
                        RoofKind.GABLED -> gabled(r, ze, plan.pitch, plan.eaves, plan)
                        RoofKind.HIPPED, RoofKind.PYRAMIDAL -> hipped(r, ze, plan.pitch, plan.eaves, plan)
                        RoofKind.HALF_HIPPED -> if (rnd.chance(0.5f)) hipped(r, ze, plan.pitch, plan.eaves, plan) else gabled(r, ze, plan.pitch, plan.eaves, plan)
                        RoofKind.MANSARD -> mansard(r, ze, plan)
                        RoofKind.SKILLION -> skillion(r, ze, plan)
                        RoofKind.FLAT -> Unit
                    }
                }
                if (plan.chimney && rects.isNotEmpty()) chimney(rects[0], ze, plan, rnd)
            }
        }
        if (plan.arch == Arch.CHURCH && area > 110) tower(obb, zb, ze, plan, rnd)
    }

    private fun plan(b: Building, area: Double, obb: Geom2.Obb, c: DoubleArray, dense: Int, rnd: Rand): Plan {
        val hit = j.routeHit(c[0], c[1])
        val distRoad = hit?.beyondEdge ?: j.dist.at(c[0], c[1])
        val town = hit != null && j.reg.features(j.ctx).inTown(hit.s)
        val coverBuilt = j.landCover(c[0], c[1]) == LandCover.BUILT
        val urban = dense >= 14 || (town && dense >= 6) || (j.style == RegionStyle.PARIS && dense >= 8)
        val z = b.baseZ.toDouble()
        val cls = b.buildingClass
        var arch = when (cls) {
            15, 16, 28 -> Arch.CHURCH
            11, 12, 13, 14, 23, 24, 25, 29, 30 -> if (area > 250) Arch.INDUSTRIAL else Arch.TOWN
            7, 8 -> Arch.BARN
            9, 10 -> Arch.SHED
            32 -> Arch.GREENHOUSE
            3 -> Arch.APARTMENT
            6 -> Arch.FARM
            else -> when {
                area < 28 -> Arch.SHED
                area > 900 && !urban -> if (coverBuilt) Arch.INDUSTRIAL else Arch.BARN
                area > 1600 -> Arch.INDUSTRIAL
                urban -> if (area > 450 && j.style != RegionStyle.PARIS && rnd.chance(0.5f)) Arch.APARTMENT else Arch.TOWN
                dense <= 3 && !coverBuilt && area > 160 && rnd.chance(0.5f) -> Arch.FARM
                else -> Arch.HOUSE
            }
        }
        if ((arch == Arch.HOUSE || arch == Arch.FARM) && p.chalet > 0f) {
            val alt = ((z - 600) / 500).coerceIn(0.2, 1.0)
            if (rnd.chance(p.chalet * alt.toFloat())) arch = Arch.CHALET
        }

        val facadeName = when (arch) {
            Arch.HOUSE -> pick(p.houseFacades, rnd)
            Arch.TOWN -> pick(p.townFacades, rnd)
            Arch.APARTMENT -> if (j.style == RegionStyle.PARIS) "facade_haussmann" else if (rnd.chance(0.6f)) "facade_modern" else pick(p.townFacades, rnd)
            Arch.FARM -> pick(p.farmFacades, rnd)
            Arch.BARN -> if (rnd.chance(0.75f)) "facade_barn" else pick(p.farmFacades, rnd)
            Arch.SHED -> if (rnd.chance(0.5f)) "facade_barn" else pick(p.houseFacades, rnd)
            Arch.INDUSTRIAL -> if (has("scn_facade_industrial")) "scn_facade_industrial" else "facade_modern"
            Arch.CHURCH -> "facade_church"
            Arch.CHALET -> if (rnd.chance(0.6f)) "facade_stone_village" else "facade_plaster_cream"
            Arch.GREENHOUSE -> "facade_modern"
        }.let { if (it.startsWith("scn_") && !has(it)) "facade_plaster_cream" else it }
        val facade = TexSize.facade(facadeName)
        val upper = if (arch == Arch.CHALET) TexSize.facade("facade_chalet") else null

        // Storeys: the pack's count when known, else from a measured height, else typical counts.
        val knownH = !b.heightEstimated && b.heightM > 2f
        var floors = when {
            b.floors > 0 -> b.floors
            knownH -> max(1, ((b.heightM - (if (b.roofHeightM > 0f) b.roofHeightM else 2f)) / facade.storeyM).roundToInt())
            else -> when (arch) {
                Arch.HOUSE -> if (j.style == RegionStyle.MEDITERRANEAN || j.style == RegionStyle.CATALONIA) 2 + (if (rnd.chance(0.3f)) 1 else 0)
                    else 1 + (if (rnd.chance(0.6f)) 1 else 0)
                Arch.TOWN -> when (j.style) {
                    RegionStyle.PARIS -> 5 + rnd.int(2)
                    RegionStyle.CATALONIA -> 3 + rnd.int(3)
                    else -> 2 + rnd.int(2) + (if (dense > 30 && rnd.chance(0.4f)) 1 else 0)
                }
                Arch.APARTMENT -> (if (b.heightM > 6f) (b.heightM / facade.storeyM).roundToInt() else 5).coerceIn(3, 12)
                Arch.FARM -> 2
                Arch.BARN -> 2
                Arch.SHED -> 1
                Arch.INDUSTRIAL -> 2 + (if (area > 3000) 1 else 0)
                Arch.CHURCH -> 1
                Arch.CHALET -> 2 + (if (rnd.chance(0.4f)) 1 else 0)
                Arch.GREENHOUSE -> 1
            }
        }
        floors = floors.coerceIn(1, if (arch == Arch.CHURCH) 2 else 20)

        // Roof kind: the pack's shape when known, else the region's habit for this kind of building.
        val fromPack = when (b.roofShape) {
            1, 13 -> RoofKind.FLAT
            2, 9, 11 -> RoofKind.GABLED
            3 -> RoofKind.HIPPED
            4, 6, 7, 8, 14 -> RoofKind.PYRAMIDAL
            5 -> RoofKind.SKILLION
            10 -> RoofKind.MANSARD
            12 -> RoofKind.HALF_HIPPED
            else -> null
        }
        val roof = fromPack ?: when (arch) {
            Arch.INDUSTRIAL -> if (area > 1500 || rnd.chance(0.45f)) RoofKind.FLAT else RoofKind.GABLED
            Arch.APARTMENT -> if (j.style == RegionStyle.PARIS) RoofKind.MANSARD else if (rnd.chance(0.7f)) RoofKind.FLAT else RoofKind.HIPPED
            Arch.SHED -> if (rnd.chance(0.35f)) RoofKind.SKILLION else RoofKind.GABLED
            Arch.GREENHOUSE, Arch.CHURCH, Arch.CHALET, Arch.BARN -> RoofKind.GABLED
            Arch.TOWN -> if (j.style == RegionStyle.PARIS) (if (rnd.chance(0.8f)) RoofKind.MANSARD else RoofKind.FLAT) else pickKind(rnd)
            else -> pickKind(rnd)
        }
        val pitchDeg = when (arch) {
            Arch.INDUSTRIAL -> 9.0 + rnd.f() * 5
            Arch.GREENHOUSE -> 22.0
            Arch.CHALET -> 24.0 + rnd.sym(4f)
            Arch.BARN -> p.pitchDeg - 6.0 + rnd.sym(p.pitchVar)
            Arch.SHED -> if (roof == RoofKind.SKILLION) 12.0 else p.pitchDeg - 4.0 + rnd.sym(p.pitchVar)
            Arch.CHURCH -> if (p.pitchDeg < 30) p.pitchDeg + 6.0 else max(48.0, p.pitchDeg + 6.0)
            else -> p.pitchDeg + rnd.sym(p.pitchVar).toDouble()
        }.let { d ->
            // A measured roof height overrides: pitch from rise over half the width.
            if (b.roofHeightM > 0.3f && obb.b > 1.0) atan(b.roofHeightM / obb.b) * 180 / PI else d
        }.coerceIn(5.0, 62.0)
        val eaves = when (arch) {
            Arch.CHALET -> 1.0 + rnd.f() * 0.5
            Arch.INDUSTRIAL, Arch.GREENHOUSE -> 0.25
            Arch.CHURCH -> 0.3
            Arch.SHED -> 0.25
            else -> p.eavesM.toDouble()
        }
        val roofTex = when {
            arch == Arch.INDUSTRIAL -> if (has("scn_roof_metal")) "scn_roof_metal" else "roof_zinc"
            arch == Arch.CHURCH && j.style != RegionStyle.MEDITERRANEAN && j.style != RegionStyle.CATALONIA -> "roof_slate"
            arch == Arch.CHALET && rnd.chance(0.4f) && has("scn_roof_metal") -> "scn_roof_metal"
            else -> pick(p.roofs, rnd)
        }.let { if (it.startsWith("scn_") && !has(it)) "roof_slate" else it }

        val tint = facadeTint(b.facadeColour, rnd)
        val roofTint = roofTint(b.roofColour, rnd)
        val shopFront = arch == Arch.TOWN && town && distRoad < 14 && floors >= 2 && rnd.chance(if (j.style == RegionStyle.PARIS) 0.85f else 0.55f)
        val chimney = (arch == Arch.HOUSE || arch == Arch.TOWN || arch == Arch.FARM || arch == Arch.CHALET) && rnd.chance(0.7f)
        val aspect = obb.a / max(0.1, obb.b)
        val ridgeAlongRoad = (arch == Arch.TOWN || arch == Arch.HOUSE) && distRoad < 30 && aspect < 1.7
        val parapet = arch == Arch.TOWN || arch == Arch.APARTMENT || arch == Arch.INDUSTRIAL
        // Houses show their front door to the street (where the facade has a door variant).
        val door = if ((arch == Arch.HOUSE || arch == Arch.TOWN) && !shopFront && upper == null && distRoad < 30 && rnd.chance(0.85f))
            TexSize.facades[facade.name + "_door"] else null
        return Plan(arch, facade, upper, floors, roof, pitchDeg * PI / 180, eaves, roofTex, tint, roofTint, shopFront, chimney, ridgeAlongRoad, parapet, door)
    }

    private fun pickKind(rnd: Rand): RoofKind = pick(p.roofKinds, rnd)

    private fun <T> pick(m: Map<T, Float>, rnd: Rand): T {
        val keys = m.keys.toList()
        val w = FloatArray(keys.size) { m.getValue(keys[it]) }
        return keys[rnd.weighted(w)]
    }

    /** Textures this build ships (W2's scn_ ones may be missing while they are produced). */
    private fun has(tex: String) = tex in AVAILABLE

    private fun facadeTint(argb: Int, rnd: Rand): FloatArray {
        if ((argb ushr 24) != 0) {
            // A mapped colour: shift the texture towards it without darkening it twice.
            val c = lin(argb and 0xFFFFFF)
            val l = (c[0] * 0.3f + c[1] * 0.59f + c[2] * 0.11f).coerceAtLeast(0.05f)
            val k = 0.85f / l
            return mix(floatArrayOf(1f, 1f, 1f), floatArrayOf((c[0] * k).coerceAtMost(1.3f), (c[1] * k).coerceAtMost(1.3f), (c[2] * k).coerceAtMost(1.3f)), 0.55f)
        }
        val w = lin(p.wallTint)
        val l = 0.9f + rnd.f() * 0.14f
        val h = rnd.sym(0.035f)
        return floatArrayOf(w[0] * l * (1 + h), w[1] * l, w[2] * l * (1 - h))
    }

    private fun roofTint(argb: Int, rnd: Rand): FloatArray {
        if ((argb ushr 24) != 0) {
            val c = lin(argb and 0xFFFFFF)
            val l = (c[0] * 0.3f + c[1] * 0.59f + c[2] * 0.11f).coerceAtLeast(0.05f)
            val k = 0.8f / l
            return mix(floatArrayOf(1f, 1f, 1f), floatArrayOf(c[0] * k, c[1] * k, c[2] * k), 0.5f)
        }
        val l = 0.82f + rnd.f() * 0.22f
        return floatArrayOf(l, l * (0.98f + rnd.f() * 0.04f), l * 0.97f)
    }

    // ------------------------------------------------------------------ walls

    private fun walls(pts: DoubleArray, zb: Double, ground: Double, ze: Double, top: Double, plan: Plan) {
        val n = pts.size / 2
        val fac = plan.facade
        val mat = Mats.surface(fac.name)
        val upperMat = plan.upper?.let { Mats.surface(it.name) }
        val shop = if (plan.shopFront) TexSize.facade("facade_shop") else null
        val shopMat = shop?.let { Mats.surface(it.name) }
        for (i in 0 until n) {
            val k = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]
            val bx = pts[k * 2]; val by = pts[k * 2 + 1]
            val len = hypot(bx - ax, by - ay)
            if (len < 0.05) continue
            val nx = ((by - ay) / len).toFloat()
            val ny = (-(bx - ax) / len).toFloat()
            if (shop != null && shopMat != null && facesRoad(ax, ay, bx, by, nx, ny)) {
                val zs = ground + 3.5
                wallQuad(j.meshes.builder(shopMat), shop, ax, ay, bx, by, len, zb, zs, zs, nx, ny, plan.tint)
                wallQuad(j.meshes.builder(mat), fac, ax, ay, bx, by, len, zs, top, ze, nx, ny, plan.tint)
            } else if (upperMat != null && plan.upper != null && plan.floors >= 2) {
                // Chalet: masonry ground floor, wooden storeys with balconies above.
                val zs = ground + fac.storeyM
                wallQuad(j.meshes.builder(mat), fac, ax, ay, bx, by, len, zb, zs, zs, nx, ny, plan.tint)
                wallQuad(j.meshes.builder(upperMat), plan.upper, ax, ay, bx, by, len, zs, top, ze, nx, ny, floatArrayOf(1f, 1f, 1f))
            } else if (plan.door != null && (len / fac.bayM).roundToInt() >= 2 && facesRoad(ax, ay, bx, by, nx, ny)) {
                // Ground floor from the door variant (window, door, window ...), storeys above as usual.
                val zs = ground + fac.storeyM
                wallQuad(j.meshes.builder(Mats.surface(plan.door.name)), plan.door, ax, ay, bx, by, len, zb, min(zs, top), zs, nx, ny, plan.tint)
                if (top - zs > 0.01) wallQuad(j.meshes.builder(mat), fac, ax, ay, bx, by, len, zs, top, ze, nx, ny, plan.tint)
            } else {
                wallQuad(j.meshes.builder(mat), fac, ax, ay, bx, by, len, zb, top, ze, nx, ny, plan.tint)
            }
        }
    }

    private fun facesRoad(ax: Double, ay: Double, bx: Double, by: Double, nx: Float, ny: Float): Boolean {
        val mx = (ax + bx) / 2; val my = (ay + by) / 2
        val h = j.routeHit(mx + nx * 1.0, my + ny * 1.0) ?: return false
        if (h.beyondEdge > 18) return false
        // Direction from the wall to the road point: the road is on the outward side.
        val pose = j.ctx.path
        val i = h.i.coerceIn(0, pose.size - 1)
        val dx = pose.x[i] - mx; val dy = pose.y[i] - my
        val l = hypot(dx, dy).coerceAtLeast(1e-6)
        return (dx * nx + dy * ny) / l > 0.4
    }

    /**
     * One wall band from z0 to z1. The facade's v counts storeys down from [vTop] (eaves), so the
     * texture's storey rows line up with the roof; u fits a whole number of window bays into the
     * wall, or the windowless strip for walls too short for a bay.
     */
    private fun wallQuad(mb: MeshBuilder, f: TexSize.Facade, ax: Double, ay: Double, bx: Double, by: Double, len: Double,
                         z0: Double, z1: Double, vTop: Double, nx: Float, ny: Float, tint: FloatArray) {
        val bays = (len / f.bayM).roundToInt()
        val u0: Float
        val u1: Float
        if (bays == 0) { u0 = f.blankU0; u1 = f.blankU1 } else { u0 = 0f; u1 = bays.toFloat() / f.bays }
        val v0 = ((vTop - z0) / f.storeyM).toFloat()
        val v1 = ((vTop - z1) / f.storeyM).toFloat()
        val cb = mul(tint, 0.8f)
        val i = mb.vertexCount
        tm.vtx(mb, ax, ay, z0, nx, ny, 0f, u0, v0, cb)
        tm.vtx(mb, bx, by, z0, nx, ny, 0f, u1, v0, cb)
        tm.vtx(mb, bx, by, z1, nx, ny, 0f, u1, v1, tint)
        tm.vtx(mb, ax, ay, z1, nx, ny, 0f, u0, v1, tint)
        mb.quad(i, i + 1, i + 2, i + 3)
    }

    /**
     * A gable / skillion wall (a convex polygon above the eaves) textured from the facade's
     * windowless strip. The strip is under a metre wide, so mapping it once across a whole gable
     * smeared the stones into planks: the wall is cut into vertical slabs no wider than
     * [GABLE_STRETCH] times the strip, each showing the strip mirrored from its neighbour (so the
     * slabs meet without a seam). A typical 8 m gable becomes 4–5 slabs (~10 triangles).
     */
    private fun gableWall(f: TexSize.Facade, pts: List<DoubleArray>, ze: Double, nx: Double, ny: Double, tint: FloatArray) {
        val mb = j.meshes.builder(Mats.surface(f.name))
        val ex = -ny; val ey = nx
        val aOf = DoubleArray(pts.size) { pts[it][0] * ex + pts[it][1] * ey }
        val minA = aOf.minOrNull() ?: return
        val maxA = aOf.maxOrNull() ?: return
        val span = maxA - minA
        if (span < 1e-3) return
        val stripM = ((f.blankU1 - f.blankU0) * f.widthM * GABLE_STRETCH).toDouble().coerceAtLeast(0.5)
        val n = max(1, ceil(span / stripM - 0.15).toInt())
        // Reference point on the wall plane: x, y of any a on it.
        val rx = pts[0][0] - ex * aOf[0]
        val ry = pts[0][1] - ey * aOf[0]
        val poly = pts.indices.map { doubleArrayOf(aOf[it], pts[it][2]) }
        for (k in 0 until n) {
            val a0 = minA + span * k / n
            val a1 = minA + span * (k + 1) / n
            val slab = clipSlab(poly, a0, a1)
            if (slab.size < 3) continue
            val first = mb.vertexCount
            for (q in slab) {
                val t = ((q[0] - a0) / (a1 - a0)).toFloat().coerceIn(0f, 1f)
                val tt = if (k % 2 == 0) t else 1f - t
                tm.vtx(mb, rx + ex * q[0], ry + ey * q[0], q[1], nx.toFloat(), ny.toFloat(), 0f,
                    f.blankU0 + (f.blankU1 - f.blankU0) * tt, ((ze - q[1]) / f.storeyM).toFloat(), tint)
            }
            for (i in 1 until slab.size - 1) orientedTri(mb, first, first + i, first + i + 1, nx, ny, 0.0)
        }
    }

    /** The part of convex polygon [poly] ((a, z) pairs) with a0 <= a <= a1. */
    private fun clipSlab(poly: List<DoubleArray>, a0: Double, a1: Double): List<DoubleArray> {
        fun clip(src: List<DoubleArray>, keep: (Double) -> Boolean, edge: Double): List<DoubleArray> {
            val out = ArrayList<DoubleArray>(src.size + 2)
            for (i in src.indices) {
                val p = src[i]
                val q = src[(i + 1) % src.size]
                val pin = keep(p[0])
                val qin = keep(q[0])
                if (pin) out += p
                if (pin != qin) {
                    val t = (edge - p[0]) / (q[0] - p[0])
                    out += doubleArrayOf(edge, p[1] + (q[1] - p[1]) * t)
                }
            }
            return out
        }
        val left = clip(poly, { it >= a0 - 1e-9 }, a0)
        if (left.size < 3) return left
        return clip(left, { it <= a1 + 1e-9 }, a1)
    }

    /** Adds triangle (a, b, c), flipped if needed so it faces along (nx, ny, nz) in the pack frame. */
    private fun orientedTri(mb: MeshBuilder, a: Int, b: Int, c: Int, nx: Double, ny: Double, nz: Double) {
        // Vertices are stored in GL (x, z_pack, -y_pack); compare in that frame.
        val ax = mb.posX(a); val ay = mb.posY(a); val az = mb.posZ(a)
        val e1x = mb.posX(b) - ax; val e1y = mb.posY(b) - ay; val e1z = mb.posZ(b) - az
        val e2x = mb.posX(c) - ax; val e2y = mb.posY(c) - ay; val e2z = mb.posZ(c) - az
        val cx = e1y * e2z - e1z * e2y
        val cy = e1z * e2x - e1x * e2z
        val cz = e1x * e2y - e1y * e2x
        val d = cx * nx + cy * nz + cz * (-ny)
        if (d >= 0) mb.tri(a, b, c) else mb.tri(a, c, b)
    }

    private fun orientedQuad(mb: MeshBuilder, a: Int, b: Int, c: Int, d: Int, nx: Double, ny: Double, nz: Double) {
        orientedTri(mb, a, b, c, nx, ny, nz)
        orientedTri(mb, a, c, d, nx, ny, nz)
    }

    // ------------------------------------------------------------------ roofs

    private fun roofMat(plan: Plan): SceneMaterial = Mats.surface(plan.roofTex, if (plan.roofTex == "roof_zinc" || plan.roofTex == "scn_roof_metal") 0.55f else 0.8f)

    /** Rectangle corner in pack coordinates from (along u, along v). */
    private fun at(r: Geom2.Obb, u: Double, v: Double) = doubleArrayOf(r.cx + r.ux * u + r.vx * v, r.cy + r.uy * u + r.vy * v)

    private fun alongRoad(r: Geom2.Obb): Geom2.Obb {
        val h = j.routeHit(r.cx, r.cy) ?: return r
        val du = abs(r.ux * h.dirX + r.uy * h.dirY)
        val dv = abs(r.vx * h.dirX + r.vy * h.dirY)
        return if (dv > du) Geom2.Obb(r.cx, r.cy, r.vx, r.vy, r.b, r.a) else r
    }

    private fun gabled(r: Geom2.Obb, ze: Double, pitch: Double, e: Double, plan: Plan) {
        val mat = roofMat(plan)
        val mb = j.meshes.builder(mat)
        val tile = TexSize.roof(plan.roofTex).toDouble()
        val tp = tan(pitch)
        val h = r.b * tp
        val zEave = ze - e * tp
        val zRidge = ze + h
        val eg = min(e, 0.45) + 0.05
        val L = r.a + eg
        val slope = (r.b + e) / cos(pitch)
        val soffit = j.meshes.builder(Mats.plain)
        val sc = mul(plainDark, 1.4f)
        for (s in intArrayOf(1, -1)) {
            val e0 = at(r, -L, s * (r.b + e)); val e1 = at(r, L, s * (r.b + e))
            val r1 = at(r, L, 0.0); val r0 = at(r, -L, 0.0)
            val nx = r.vx * s * sin(pitch); val ny = r.vy * s * sin(pitch); val nz = cos(pitch)
            val uL = (2 * L / tile).toFloat()
            val vE = (slope / tile).toFloat()
            val i = mb.vertexCount
            tm.vtx(mb, e0[0], e0[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, vE, plan.roofTint)
            tm.vtx(mb, e1[0], e1[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), uL, vE, plan.roofTint)
            tm.vtx(mb, r1[0], r1[1], zRidge, nx.toFloat(), ny.toFloat(), nz.toFloat(), uL, 0f, plan.roofTint)
            tm.vtx(mb, r0[0], r0[1], zRidge, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, 0f, plan.roofTint)
            orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, nz)
            // Underside (seen below the eaves) and the fascia board along the eaves.
            val k = soffit.vertexCount
            for (q in arrayOf(e0, e1)) tm.vtx(soffit, q[0], q[1], zEave - 0.02, (-nx).toFloat(), (-ny).toFloat(), (-nz).toFloat(), 0f, 0f, sc)
            tm.vtx(soffit, r1[0], r1[1], zRidge - 0.02, (-nx).toFloat(), (-ny).toFloat(), (-nz).toFloat(), 0f, 0f, sc)
            tm.vtx(soffit, r0[0], r0[1], zRidge - 0.02, (-nx).toFloat(), (-ny).toFloat(), (-nz).toFloat(), 0f, 0f, sc)
            orientedQuad(soffit, k, k + 1, k + 2, k + 3, -nx, -ny, -nz)
            fascia(soffit, e0, e1, zEave, r.vx * s, r.vy * s)
        }
        // Gable walls at the rectangle's ends.
        for (t in intArrayOf(1, -1)) {
            val a0 = at(r, t * r.a, -r.b); val a1 = at(r, t * r.a, r.b); val ap = at(r, t * r.a, 0.0)
            gableWall(plan.upper ?: plan.facade, listOf(doubleArrayOf(a0[0], a0[1], ze), doubleArrayOf(a1[0], a1[1], ze), doubleArrayOf(ap[0], ap[1], zRidge)),
                ze, r.ux * t, r.uy * t, plan.tint)
            // Verge boards along the gable's slopes, so the roof shows a thickness at its ends.
            val g0 = at(r, t * L, -(r.b + e)); val g1 = at(r, t * L, r.b + e); val gp = at(r, t * L, 0.0)
            verge(soffit, g0, gp, zEave, zRidge, r.ux * t, r.uy * t)
            verge(soffit, gp, g1, zRidge, zEave, r.ux * t, r.uy * t)
        }
    }

    private fun fascia(mb: MeshBuilder, a: DoubleArray, b: DoubleArray, z: Double, nx: Double, ny: Double) {
        val c = lin(0x6A625A)
        val i = mb.vertexCount
        tm.vtx(mb, a[0], a[1], z - 0.18, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, mul(c, 0.8f))
        tm.vtx(mb, b[0], b[1], z - 0.18, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, mul(c, 0.8f))
        tm.vtx(mb, b[0], b[1], z + 0.02, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, c)
        tm.vtx(mb, a[0], a[1], z + 0.02, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, c)
        orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, 0.0)
    }

    private fun verge(mb: MeshBuilder, a: DoubleArray, b: DoubleArray, za: Double, zb: Double, nx: Double, ny: Double) {
        val c = lin(0x6A625A)
        val i = mb.vertexCount
        tm.vtx(mb, a[0], a[1], za - 0.18, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, mul(c, 0.8f))
        tm.vtx(mb, b[0], b[1], zb - 0.18, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, mul(c, 0.8f))
        tm.vtx(mb, b[0], b[1], zb + 0.02, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, c)
        tm.vtx(mb, a[0], a[1], za + 0.02, nx.toFloat(), ny.toFloat(), 0f, 0f, 0f, c)
        orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, 0.0)
    }

    /** Hipped roof (pyramidal when square) over [r0] with its ridge along the long side. */
    private fun hipped(r0: Geom2.Obb, ze: Double, pitch: Double, e: Double, plan: Plan, zBase: Double = ze, tex: String = plan.roofTex) {
        val r = if (r0.a >= r0.b) r0 else Geom2.Obb(r0.cx, r0.cy, r0.vx, r0.vy, r0.b, r0.a)
        val mb = j.meshes.builder(if (tex == plan.roofTex) roofMat(plan) else Mats.surface(tex, 0.55f))
        val soffit = j.meshes.builder(Mats.plain)
        val tile = TexSize.roof(tex).toDouble()
        val tp = tan(pitch)
        val A = r.a + e
        val B = r.b + e
        val zEave = zBase - e * tp
        val zRidge = zEave + B * tp
        val rr = A - B
        val sl = B / cos(pitch)
        val vE = (sl / tile).toFloat()
        val sc = mul(plainDark, 1.4f)
        // Long sides: trapezoids.
        for (s in intArrayOf(1, -1)) {
            val e0 = at(r, -A, s * B); val e1 = at(r, A, s * B); val q1 = at(r, rr, 0.0); val q0 = at(r, -rr, 0.0)
            val nx = r.vx * s * sin(pitch); val ny = r.vy * s * sin(pitch); val nz = cos(pitch)
            val i = mb.vertexCount
            tm.vtx(mb, e0[0], e0[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, vE, plan.roofTint)
            tm.vtx(mb, e1[0], e1[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), (2 * A / tile).toFloat(), vE, plan.roofTint)
            tm.vtx(mb, q1[0], q1[1], zRidge, nx.toFloat(), ny.toFloat(), nz.toFloat(), ((A + rr) / tile).toFloat(), 0f, plan.roofTint)
            tm.vtx(mb, q0[0], q0[1], zRidge, nx.toFloat(), ny.toFloat(), nz.toFloat(), ((A - rr) / tile).toFloat(), 0f, plan.roofTint)
            orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, nz)
            if (e > 0.05) {
                val k = soffit.vertexCount
                tm.vtx(soffit, e0[0], e0[1], zEave - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                tm.vtx(soffit, e1[0], e1[1], zEave - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                tm.vtx(soffit, q1[0], q1[1], zRidge - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                tm.vtx(soffit, q0[0], q0[1], zRidge - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                orientedQuad(soffit, k, k + 1, k + 2, k + 3, -nx, -ny, -nz)
                fascia(soffit, e0, e1, zEave, r.vx * s, r.vy * s)
            }
        }
        // Ends: triangles.
        for (t in intArrayOf(1, -1)) {
            val e0 = at(r, t * A, -B); val e1 = at(r, t * A, B); val q = at(r, t * rr, 0.0)
            val nx = r.ux * t * sin(pitch); val ny = r.uy * t * sin(pitch); val nz = cos(pitch)
            val i = mb.vertexCount
            tm.vtx(mb, e0[0], e0[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, vE, plan.roofTint)
            tm.vtx(mb, e1[0], e1[1], zEave, nx.toFloat(), ny.toFloat(), nz.toFloat(), (2 * B / tile).toFloat(), vE, plan.roofTint)
            tm.vtx(mb, q[0], q[1], zRidge, nx.toFloat(), ny.toFloat(), nz.toFloat(), (B / tile).toFloat(), 0f, plan.roofTint)
            orientedTri(mb, i, i + 1, i + 2, nx, ny, nz)
            if (e > 0.05) {
                val k = soffit.vertexCount
                tm.vtx(soffit, e0[0], e0[1], zEave - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                tm.vtx(soffit, e1[0], e1[1], zEave - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                tm.vtx(soffit, q[0], q[1], zRidge - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
                orientedTri(soffit, k, k + 1, k + 2, -nx, -ny, -nz)
                fascia(soffit, e0, e1, zEave, r.ux * t, r.uy * t)
            }
        }
    }

    /**
     * Paris mansard: a steep (~75°) lower slope one attic storey high, then a shallow hipped top;
     * slate below, zinc above, as on the Haussmann blocks.
     */
    private fun mansard(r: Geom2.Obb, ze: Double, plan: Plan) {
        val hm = 2.9
        val steep = 75 * PI / 180
        val d = hm / tan(steep)
        val lower = Mats.surface(if (plan.roofTex == "roof_zinc") "roof_slate" else plan.roofTex, 0.6f)
        val mb = j.meshes.builder(lower)
        val tile = TexSize.roof("roof_slate").toDouble()
        val A = r.a + 0.15; val B = r.b + 0.15
        val a2 = max(0.5, A - d - 0.15); val b2 = max(0.5, B - d - 0.15)
        val corners = arrayOf(doubleArrayOf(-A, -B), doubleArrayOf(A, -B), doubleArrayOf(A, B), doubleArrayOf(-A, B))
        val inner = arrayOf(doubleArrayOf(-a2, -b2), doubleArrayOf(a2, -b2), doubleArrayOf(a2, b2), doubleArrayOf(-a2, b2))
        val sl = hm / sin(steep)
        for (k in 0 until 4) {
            val k2 = (k + 1) % 4
            val p0 = at(r, corners[k][0], corners[k][1]); val p1 = at(r, corners[k2][0], corners[k2][1])
            val q1 = at(r, inner[k2][0], inner[k2][1]); val q0 = at(r, inner[k][0], inner[k][1])
            // outward direction of this side
            val mu = (corners[k][0] + corners[k2][0]) / 2; val mv = (corners[k][1] + corners[k2][1]) / 2
            val ox = r.ux * mu + r.vx * mv; val oy = r.uy * mu + r.vy * mv
            val ol = hypot(ox, oy).coerceAtLeast(1e-6)
            val nx = ox / ol * sin(steep); val ny = oy / ol * sin(steep); val nz = cos(steep)
            val w = hypot(p1[0] - p0[0], p1[1] - p0[1])
            val i = mb.vertexCount
            tm.vtx(mb, p0[0], p0[1], ze - 0.05, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, (sl / tile).toFloat(), plan.roofTint)
            tm.vtx(mb, p1[0], p1[1], ze - 0.05, nx.toFloat(), ny.toFloat(), nz.toFloat(), (w / tile).toFloat(), (sl / tile).toFloat(), plan.roofTint)
            tm.vtx(mb, q1[0], q1[1], ze + hm, nx.toFloat(), ny.toFloat(), nz.toFloat(), ((w - d) / tile).toFloat(), 0f, plan.roofTint)
            tm.vtx(mb, q0[0], q0[1], ze + hm, nx.toFloat(), ny.toFloat(), nz.toFloat(), (d / tile).toFloat(), 0f, plan.roofTint)
            orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, nz)
            fascia(j.meshes.builder(Mats.plain), p0, p1, ze + 0.1, ox / ol, oy / ol)
        }
        // Dormer windows on the steep slope facing the street.
        dormers(r, ze, hm, d, plan)
        hipped(Geom2.Obb(r.cx, r.cy, r.ux, r.uy, a2, b2), ze + hm, 16 * PI / 180, 0.0, plan, ze + hm, "roof_zinc")
    }

    /**
     * Small dormers on the long sides of mansards near the route: a front with the Haussmann
     * window strip and a little zinc roof, one per 3 m bay. They make Paris roofs read at once.
     */
    private fun dormers(r: Geom2.Obb, ze: Double, hm: Double, d: Double, plan: Plan) {
        if (j.dist.at(r.cx, r.cy) > 120) return
        val fac = TexSize.facade("facade_haussmann")
        val front = j.meshes.builder(Mats.surface(fac.name))
        val roof = j.meshes.builder(Mats.surface("roof_zinc", 0.55f))
        val n = ((2 * r.a) / fac.bayM).toInt()
        if (n < 1) return
        val step = 2 * r.a / n
        val w = 0.62
        val h0 = 0.45
        val h1 = 1.75
        for (s in intArrayOf(1, -1)) {
            val nx = r.vx * s; val ny = r.vy * s
            for (k in 0 until n) {
                val u = -r.a + (k + 0.5) * step
                // Front plane sits at the eave line pushed in a little (the slope is 75°).
                val f0 = at(r, u - w, s * (r.b + 0.15 - 0.25)); val f1 = at(r, u + w, s * (r.b + 0.15 - 0.25))
                val i = front.vertexCount
                // A window from the facade texture: the left window of the top storey row.
                val uw0 = 0.5f / fac.bays - 0.16f; val uw1 = 0.5f / fac.bays + 0.16f
                tm.vtx(front, f0[0], f0[1], ze + h0, nx.toFloat(), ny.toFloat(), 0f, uw0, 0.95f, plan.tint)
                tm.vtx(front, f1[0], f1[1], ze + h0, nx.toFloat(), ny.toFloat(), 0f, uw1, 0.95f, plan.tint)
                tm.vtx(front, f1[0], f1[1], ze + h1, nx.toFloat(), ny.toFloat(), 0f, uw1, 0.25f, plan.tint)
                tm.vtx(front, f0[0], f0[1], ze + h1, nx.toFloat(), ny.toFloat(), 0f, uw0, 0.25f, plan.tint)
                orientedQuad(front, i, i + 1, i + 2, i + 3, nx, ny, 0.0)
                // Dormer roof: a small gable running back into the slope.
                val back = 1.0
                val b0 = at(r, u - w - 0.08, s * (r.b + 0.15 - 0.25 - back)); val b1 = at(r, u + w + 0.08, s * (r.b + 0.15 - 0.25 - back))
                val e0 = at(r, u - w - 0.08, s * (r.b + 0.15 - 0.2)); val e1 = at(r, u + w + 0.08, s * (r.b + 0.15 - 0.2))
                val ridgeF = at(r, u, s * (r.b + 0.15 - 0.2)); val ridgeB = at(r, u, s * (r.b + 0.15 - 0.25 - back))
                val zr = ze + h1 + 0.45
                for ((ea, eb, side) in listOf(Triple(e0, b0, -1.0), Triple(e1, b1, 1.0))) {
                    val ux = r.ux * side; val uy = r.uy * side
                    val j0 = roof.vertexCount
                    tm.vtx(roof, ea[0], ea[1], ze + h1 - 0.05, (ux * 0.7).toFloat(), (uy * 0.7).toFloat(), 0.7f, 0f, 0.4f, plan.roofTint)
                    tm.vtx(roof, eb[0], eb[1], ze + h1 - 0.05, (ux * 0.7).toFloat(), (uy * 0.7).toFloat(), 0.7f, 0.4f, 0.4f, plan.roofTint)
                    tm.vtx(roof, ridgeB[0], ridgeB[1], zr, (ux * 0.7).toFloat(), (uy * 0.7).toFloat(), 0.7f, 0.4f, 0f, plan.roofTint)
                    tm.vtx(roof, ridgeF[0], ridgeF[1], zr, (ux * 0.7).toFloat(), (uy * 0.7).toFloat(), 0.7f, 0f, 0f, plan.roofTint)
                    orientedQuad(roof, j0, j0 + 1, j0 + 2, j0 + 3, ux * 0.7, uy * 0.7, 0.7)
                }
                // Pediment above the window.
                val j1 = front.vertexCount
                tm.vtx(front, f0[0], f0[1], ze + h1, nx.toFloat(), ny.toFloat(), 0f, fac.blankU0, 0.1f, plan.tint)
                tm.vtx(front, f1[0], f1[1], ze + h1, nx.toFloat(), ny.toFloat(), 0f, fac.blankU1, 0.1f, plan.tint)
                tm.vtx(front, ridgeF[0], ridgeF[1], zr - 0.03, nx.toFloat(), ny.toFloat(), 0f, (fac.blankU0 + fac.blankU1) / 2, 0f, plan.tint)
                orientedTri(front, j1, j1 + 1, j1 + 2, nx, ny, 0.0)
            }
        }
    }

    private fun skillion(r: Geom2.Obb, ze: Double, plan: Plan) {
        val mb = j.meshes.builder(roofMat(plan))
        val tile = TexSize.roof(plan.roofTex).toDouble()
        val pitch = plan.pitch
        val tp = tan(pitch)
        // Slope across the short side; the high side away from the road.
        var s = 1
        j.routeHit(r.cx, r.cy)?.let { h ->
            val ix = h.i.coerceIn(0, j.ctx.path.size - 1)
            val dx = j.ctx.path.x[ix] - r.cx; val dy = j.ctx.path.y[ix] - r.cy
            if (dx * r.vx + dy * r.vy > 0) s = -1
        }
        val e = plan.eaves
        val h = 2 * r.b * tp
        val lowZ = ze - e * tp
        val highZ = ze + h + e * tp
        val l0 = at(r, -(r.a + e), -s * (r.b + e)); val l1 = at(r, r.a + e, -s * (r.b + e))
        val h1 = at(r, r.a + e, s * (r.b + e)); val h0 = at(r, -(r.a + e), s * (r.b + e))
        val nx = r.vx * -s * sin(pitch); val ny = r.vy * -s * sin(pitch); val nz = cos(pitch)
        val sl = 2 * (r.b + e) / cos(pitch)
        val i = mb.vertexCount
        tm.vtx(mb, l0[0], l0[1], lowZ, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, (sl / tile).toFloat(), plan.roofTint)
        tm.vtx(mb, l1[0], l1[1], lowZ, nx.toFloat(), ny.toFloat(), nz.toFloat(), (2 * (r.a + e) / tile).toFloat(), (sl / tile).toFloat(), plan.roofTint)
        tm.vtx(mb, h1[0], h1[1], highZ, nx.toFloat(), ny.toFloat(), nz.toFloat(), (2 * (r.a + e) / tile).toFloat(), 0f, plan.roofTint)
        tm.vtx(mb, h0[0], h0[1], highZ, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, 0f, plan.roofTint)
        orientedQuad(mb, i, i + 1, i + 2, i + 3, nx, ny, nz)
        val soffit = j.meshes.builder(Mats.plain)
        val k = soffit.vertexCount
        val sc = mul(plainDark, 1.4f)
        for ((q, z) in listOf(l0 to lowZ, l1 to lowZ, h1 to highZ, h0 to highZ)) tm.vtx(soffit, q[0], q[1], z - 0.02, 0f, 0f, -1f, 0f, 0f, sc)
        orientedQuad(soffit, k, k + 1, k + 2, k + 3, -nx, -ny, -nz)
        // Walls up to the slope: side triangles and the high wall.
        val f = plan.facade
        for (t in intArrayOf(1, -1)) {
            val a0 = at(r, t * r.a, -s * r.b); val a1 = at(r, t * r.a, s * r.b)
            gableWall(f, listOf(doubleArrayOf(a0[0], a0[1], ze), doubleArrayOf(a1[0], a1[1], ze), doubleArrayOf(a1[0], a1[1], ze + h)), ze, r.ux * t, r.uy * t, plan.tint)
        }
        val w0 = at(r, -r.a, s * r.b); val w1 = at(r, r.a, s * r.b)
        gableWall(f, listOf(doubleArrayOf(w0[0], w0[1], ze), doubleArrayOf(w1[0], w1[1], ze), doubleArrayOf(w1[0], w1[1], ze + h), doubleArrayOf(w0[0], w0[1], ze + h)),
            ze, r.vx * s, r.vy * s, plan.tint)
    }

    private fun flatRoof(pts: DoubleArray, ze: Double, parapet: Boolean, plan: Plan) {
        val tex = "gravel_shoulder"
        val tile = 1.5 * 3 // gravel at a coarser scale reads as roofing felt and ballast from the road
        val mb = j.meshes.builder(Mats.surface(tex, 0.95f))
        val tris = Geom2.triangulate(pts)
        val n = pts.size / 2
        val c = floatArrayOf(0.62f * plan.roofTint[0], 0.62f * plan.roofTint[1], 0.62f * plan.roofTint[2])
        val first = mb.vertexCount
        for (i in 0 until n) {
            tm.vtx(mb, pts[i * 2], pts[i * 2 + 1], ze + 0.02, 0f, 0f, 1f, ((pts[i * 2] - j.ox) / tile).toFloat(), ((j.oy - pts[i * 2 + 1]) / tile).toFloat(), c)
        }
        var t = 0
        while (t + 2 < tris.size) { orientedTri(mb, first + tris[t], first + tris[t + 1], first + tris[t + 2], 0.0, 0.0, 1.0); t += 3 }
        if (!parapet) return
        // Parapet: inner face and coping, 0.3 m inside the walls.
        val plain = j.meshes.builder(Mats.plain)
        val inner = mul(plan.tint, 0.62f)
        val cope = lin(0xB8B2A8)
        for (i in 0 until n) {
            val k = (i + 1) % n
            val ax = pts[i * 2]; val ay = pts[i * 2 + 1]; val bx = pts[k * 2]; val by = pts[k * 2 + 1]
            val l = hypot(bx - ax, by - ay)
            if (l < 0.3) continue
            val ix = -(by - ay) / l * 0.3; val iy = (bx - ax) / l * 0.3 // inward (left of a→b)
            val zt = ze + 0.55
            val q = plain.vertexCount
            tm.vtx(plain, ax + ix, ay + iy, ze, (ix / 0.3).toFloat(), (iy / 0.3).toFloat(), 0f, 0f, 0f, inner)
            tm.vtx(plain, bx + ix, by + iy, ze, (ix / 0.3).toFloat(), (iy / 0.3).toFloat(), 0f, 0f, 0f, inner)
            tm.vtx(plain, bx + ix, by + iy, zt, (ix / 0.3).toFloat(), (iy / 0.3).toFloat(), 0f, 0f, 0f, inner)
            tm.vtx(plain, ax + ix, ay + iy, zt, (ix / 0.3).toFloat(), (iy / 0.3).toFloat(), 0f, 0f, 0f, inner)
            orientedQuad(plain, q, q + 1, q + 2, q + 3, ix, iy, 0.0)
            val w = plain.vertexCount
            tm.vtx(plain, ax, ay, zt, 0f, 0f, 1f, 0f, 0f, cope)
            tm.vtx(plain, bx, by, zt, 0f, 0f, 1f, 0f, 0f, cope)
            tm.vtx(plain, bx + ix, by + iy, zt, 0f, 0f, 1f, 0f, 0f, cope)
            tm.vtx(plain, ax + ix, ay + iy, zt, 0f, 0f, 1f, 0f, 0f, cope)
            orientedQuad(plain, w, w + 1, w + 2, w + 3, 0.0, 0.0, 1.0)
        }
    }

    /** A dark flat lid at the eaves under split roofs, so no gap between the roof parts shows the sky. */
    private fun ceiling(pts: DoubleArray, z: Double) {
        val mb = j.meshes.builder(Mats.plain)
        val tris = Geom2.triangulate(pts)
        val first = mb.vertexCount
        val c = plainDark
        for (i in 0 until pts.size / 2) tm.vtx(mb, pts[i * 2], pts[i * 2 + 1], z, 0f, 0f, 1f, 0f, 0f, c)
        var t = 0
        while (t + 2 < tris.size) { orientedTri(mb, first + tris[t], first + tris[t + 1], first + tris[t + 2], 0.0, 0.0, 1.0); t += 3 }
    }

    private fun chimney(r: Geom2.Obb, ze: Double, plan: Plan, rnd: Rand) {
        val mb = j.meshes.builder(Mats.plain)
        val col = when (j.style) {
            RegionStyle.NORTH -> lin(0x8A4A38)
            RegionStyle.PARIS -> lin(0xB0A898)
            else -> mul(lin(0xC8BCA8), plan.tint[1])
        }
        val u = (if (rnd.chance(0.5f)) 1 else -1) * r.a * rnd.range(0.25f, 0.6f)
        val v = r.b * rnd.range(-0.35f, 0.35f)
        val zTop = ze + r.b * tan(plan.pitch) + 0.6 + rnd.f() * 0.4
        val w = 0.32
        val corners = arrayOf(at(r, u - w, v - w), at(r, u + w, v - w), at(r, u + w, v + w), at(r, u - w, v + w))
        for (k in 0 until 4) {
            val a = corners[k]; val b = corners[(k + 1) % 4]
            val mx = (a[0] + b[0]) / 2 - r.cx - r.ux * u - r.vx * v
            val my = (a[1] + b[1]) / 2 - r.cy - r.uy * u - r.vy * v
            val l = hypot(mx, my).coerceAtLeast(1e-6)
            val i = mb.vertexCount
            tm.vtx(mb, a[0], a[1], ze, (mx / l).toFloat(), (my / l).toFloat(), 0f, 0f, 0f, mul(col, 0.8f))
            tm.vtx(mb, b[0], b[1], ze, (mx / l).toFloat(), (my / l).toFloat(), 0f, 0f, 0f, mul(col, 0.8f))
            tm.vtx(mb, b[0], b[1], zTop, (mx / l).toFloat(), (my / l).toFloat(), 0f, 0f, 0f, col)
            tm.vtx(mb, a[0], a[1], zTop, (mx / l).toFloat(), (my / l).toFloat(), 0f, 0f, 0f, col)
            orientedQuad(mb, i, i + 1, i + 2, i + 3, mx / l, my / l, 0.0)
        }
        val i = mb.vertexCount
        for (q in corners) tm.vtx(mb, q[0], q[1], zTop, 0f, 0f, 1f, 0f, 0f, mul(col, 0.4f))
        orientedQuad(mb, i, i + 1, i + 2, i + 3, 0.0, 0.0, 1.0)
    }

    /** Bell tower with a spire at one end of a church: what makes a French village skyline. */
    private fun tower(obb: Geom2.Obb, zb: Double, ze: Double, plan: Plan, rnd: Rand) {
        val side = (obb.b * 2 * 0.75).coerceIn(4.0, 7.5)
        val t = if (rnd.chance(0.5f)) 1 else -1
        val cu = t * (obb.a - side / 2)
        val half = side / 2
        val zTop = ze + 8 + rnd.f() * 8
        val fac = TexSize.facade("facade_church")
        val wallMb = j.meshes.builder(Mats.surface(fac.name))
        val corners = arrayOf(at(obb, cu - half, -half), at(obb, cu + half, -half), at(obb, cu + half, half), at(obb, cu - half, half))
        for (k in 0 until 4) {
            val a = corners[k]; val b = corners[(k + 1) % 4]
            val len = hypot(b[0] - a[0], b[1] - a[1])
            val nx = ((b[1] - a[1]) / len).toFloat(); val ny = (-(b[0] - a[0]) / len).toFloat()
            wallQuad(wallMb, fac, a[0], a[1], b[0], b[1], len, zb, zTop, zTop, nx, ny, plan.tint)
        }
        val south = j.style == RegionStyle.MEDITERRANEAN || j.style == RegionStyle.CATALONIA || j.style == RegionStyle.SOUTH_WEST
        val spireH = if (south) side * 0.45 else side * (1.6 + rnd.f() * 0.8)
        val roof = j.meshes.builder(Mats.surface(if (south) "roof_roman_tiles" else "roof_slate", 0.6f))
        val apex = at(obb, cu, 0.0)
        val o = 0.25
        val rc = arrayOf(at(obb, cu - half - o, -half - o), at(obb, cu + half + o, -half - o), at(obb, cu + half + o, half + o), at(obb, cu - half - o, half + o))
        for (k in 0 until 4) {
            val a = rc[k]; val b = rc[(k + 1) % 4]
            val mx = (a[0] + b[0]) / 2 - apex[0]; val my = (a[1] + b[1]) / 2 - apex[1]
            val l = hypot(mx, my)
            val slope = atan(spireH / (half + o))
            val nx = mx / l * sin(slope); val ny = my / l * sin(slope); val nz = cos(slope)
            val i = roof.vertexCount
            val sl = hypot(spireH, half + o) / 3.0
            tm.vtx(roof, a[0], a[1], zTop - 0.1, nx.toFloat(), ny.toFloat(), nz.toFloat(), 0f, sl.toFloat(), plan.roofTint)
            tm.vtx(roof, b[0], b[1], zTop - 0.1, nx.toFloat(), ny.toFloat(), nz.toFloat(), (side / 3).toFloat(), sl.toFloat(), plan.roofTint)
            tm.vtx(roof, apex[0], apex[1], zTop + spireH, nx.toFloat(), ny.toFloat(), nz.toFloat(), (side / 6).toFloat(), 0f, plan.roofTint)
            orientedTri(roof, i, i + 1, i + 2, nx, ny, nz)
        }
    }

    // ------------------------------------------------------------------ decomposition

    /**
     * Splits an L / T / U footprint into up to four rectangles (in the bounding rectangle's frame)
     * that cover ≥ 88 % of it, each at least 3 m wide; null when the shape is not rectilinear
     * enough for pitched roofs.
     */
    private fun decompose(pts: DoubleArray, obb: Geom2.Obb): List<Geom2.Obb>? {
        val nu = ceil(2 * obb.a / 1.0).toInt().coerceIn(4, 20)
        val nv = ceil(2 * obb.b / 1.0).toInt().coerceIn(4, 20)
        val du = 2 * obb.a / nu
        val dv = 2 * obb.b / nv
        val inside = BooleanArray(nu * nv)
        var total = 0
        for (iv in 0 until nv) for (iu in 0 until nu) {
            val u = -obb.a + (iu + 0.5) * du
            val v = -obb.b + (iv + 0.5) * dv
            val q = at(obb, u, v)
            if (Geom2.inside(q[0], q[1], pts)) { inside[iv * nu + iu] = true; total++ }
        }
        if (total < 6) return null
        val covered = BooleanArray(nu * nv)
        val out = ArrayList<Geom2.Obb>()
        val minU = ceil(3.0 / du).toInt().coerceAtLeast(1)
        val minV = ceil(3.0 / dv).toInt().coerceAtLeast(1)
        // Prefix sums of inside cells, for O(1) "fully inside" tests.
        val ps = IntArray((nu + 1) * (nv + 1))
        for (iv in 0 until nv) for (iu in 0 until nu) {
            ps[(iv + 1) * (nu + 1) + iu + 1] = (if (inside[iv * nu + iu]) 1 else 0) + ps[iv * (nu + 1) + iu + 1] + ps[(iv + 1) * (nu + 1) + iu] - ps[iv * (nu + 1) + iu]
        }
        fun sumIn(u0: Int, v0: Int, u1: Int, v1: Int) = ps[v1 * (nu + 1) + u1] - ps[v0 * (nu + 1) + u1] - ps[v1 * (nu + 1) + u0] + ps[v0 * (nu + 1) + u0]
        var coveredCount = 0
        // Prefix sums of cells not yet covered, rebuilt per round: scores in O(1).
        val pf = IntArray((nu + 1) * (nv + 1))
        fun sumFresh(u0: Int, v0: Int, u1: Int, v1: Int) = pf[v1 * (nu + 1) + u1] - pf[v0 * (nu + 1) + u1] - pf[v1 * (nu + 1) + u0] + pf[v0 * (nu + 1) + u0]
        while (out.size < 4) {
            for (iv in 0 until nv) for (iu in 0 until nu) {
                val f = if (inside[iv * nu + iu] && !covered[iv * nu + iu]) 1 else 0
                pf[(iv + 1) * (nu + 1) + iu + 1] = f + pf[iv * (nu + 1) + iu + 1] + pf[(iv + 1) * (nu + 1) + iu] - pf[iv * (nu + 1) + iu]
            }
            var best = 0
            var bu0 = 0; var bv0 = 0; var bu1 = 0; var bv1 = 0
            for (v0 in 0 until nv) for (u0 in 0 until nu) {
                if (!inside[v0 * nu + u0]) continue
                for (v1 in v0 + minV..nv) for (u1 in u0 + minU..nu) {
                    val cells = (u1 - u0) * (v1 - v0)
                    if (sumIn(u0, v0, u1, v1) != cells) continue
                    val fresh = sumFresh(u0, v0, u1, v1)
                    if (fresh > best) { best = fresh; bu0 = u0; bv0 = v0; bu1 = u1; bv1 = v1 }
                }
            }
            if (best < max(3, total / 12)) break
            for (iv in bv0 until bv1) for (iu in bu0 until bu1) if (!covered[iv * nu + iu]) { covered[iv * nu + iu] = true; coveredCount++ }
            val cu = -obb.a + (bu0 + bu1) / 2.0 * du
            val cv = -obb.b + (bv0 + bv1) / 2.0 * dv
            val ha = (bu1 - bu0) * du / 2
            val hb = (bv1 - bv0) * dv / 2
            val c = at(obb, cu, cv)
            out += if (ha >= hb) Geom2.Obb(c[0], c[1], obb.ux, obb.uy, ha, hb) else Geom2.Obb(c[0], c[1], obb.vx, obb.vy, hb, ha)
            if (coveredCount >= total * 0.92) break
        }
        if (coveredCount < total * 0.88 || out.isEmpty()) return null
        return out
    }

    companion object {
        const val ROOF_ONLY = 26
        const val DENS_M = 40.0

        /** Textures of this package that exist in assets/tex (checked by a test). */
        /** Widest stretch of a facade's blank strip on gables (plaster hides it, stone shows it). */
        const val GABLE_STRETCH = 2.2f
        val AVAILABLE: Set<String> = setOf("scn_facade_industrial", "scn_roof_metal", "scn_facade_brick", "scn_facade_halftimber", "scn_facade_tuffeau", "scn_roof_lauze")
    }
}
