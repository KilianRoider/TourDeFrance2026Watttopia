package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.LandCover
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Fields, vineyards and orchards.
 *
 * The land cover only says "cropland" in 10 m cells; it does not know parcels or crops. Parcels are
 * approximated by a jittered-grid Voronoi diagram (~190 m cells, the size of French arable fields),
 * each cell gets a crop from the region's July mix. Tall crops (maize, sunflowers, lavender) are
 * planted as row blocks in the strip the rider sees closely; further out the terrain texture does
 * the job. Vineyards and orchards are rows along the parcel's main axis, which comes from the
 * shape of the connected vineyard area (or the fall line on slopes, where rows run downhill).
 */
internal class Fields(private val j: TileJob) {
    private val lc = j.lc
    private val cells = lc?.cells ?: 0
    private val cellM = if (cells > 0) j.size / cells else 10.0
    private val p = j.profile

    fun run() {
        if (lc == null) return
        crops()
        vineAndOrchards()
    }

    private fun cls(c: Int, r: Int): Int {
        if (c < 0 || r < 0 || c >= cells || r >= cells) return -1
        return lc!!.classes[r * cells + c].toInt() and 0xFF
    }

    private fun cx(c: Int) = j.ox + (c + 0.5) * cellM
    private fun cy(r: Int) = j.oy + j.size - (r + 0.5) * cellM

    // ------------------------------------------------------------------ crops

    private fun crops() {
        val reach = 48.0
        // Lattices are evaluated per land cover cell; each block centre belongs to exactly one cell.
        for (r in 0 until cells) for (c in 0 until cells) {
            if (cls(c, r) != LandCover.CROP) continue
            val x = cx(c)
            val y = cy(r)
            val d = j.dist.at(x, y)
            if (d > 260) continue
            val field = field(j.ctx.seed.toLong(), x, y)
            val crop = cropOf(j.ctx.seed.toLong(), p, field)
            val rnd = Rand(Hash.of(j.seed, c.toLong(), r.toLong(), 0xF1E1D))
            when (crop) {
                Crop.MAIZE, Crop.SUNFLOWER, Crop.LAVENDER -> if (d < reach + 20) rowCrop(crop, field, c, r, reach)
                Crop.WHEAT -> {
                    // Harvested fields in July: a few round bales near the road.
                    if (Hash.unit(Hash.of(field.id, 0xBA1E)) < 0.45f && d < 220 && rnd.chance(0.035f)) bale(x + rnd.sym(4f), y + rnd.sym(4f), rnd)
                }
                Crop.PASTURE -> Unit
            }
        }
    }

    private fun rowCrop(crop: Crop, field: Field, c: Int, r: Int, reach: Double) {
        val id = when (crop) { Crop.MAIZE -> "scn_maize"; Crop.SUNFLOWER -> "scn_sunflower"; else -> "scn_lavender" }
        if (!j.reg.has(id)) return
        val across = if (crop == Crop.LAVENDER) 3.0 else 3.0 // rows × spacing of the block (see PlantSet)
        val along = 6.0
        val ang = fieldAngle(field)
        val ux = cos(ang); val uy = sin(ang)
        val vx = -uy; val vy = ux
        val x0 = j.ox + c * cellM
        val y1 = j.oy + j.size - r * cellM
        val x1 = x0 + cellM
        val y0 = y1 - cellM
        // Lattice coordinates of the cell corners.
        var umin = Double.MAX_VALUE; var umax = -Double.MAX_VALUE; var vmin = Double.MAX_VALUE; var vmax = -Double.MAX_VALUE
        for (px in doubleArrayOf(x0, x1)) for (py in doubleArrayOf(y0, y1)) {
            val du = (px - field.x) * ux + (py - field.y) * uy
            val dv = (px - field.x) * vx + (py - field.y) * vy
            umin = min(umin, du); umax = max(umax, du); vmin = min(vmin, dv); vmax = max(vmax, dv)
        }
        var a = floor(umin / along).toInt()
        while (a * along <= umax) {
            var b = floor(vmin / across).toInt()
            while (b * across <= vmax) {
                val px = field.x + ux * a * along + vx * b * across
                val py = field.y + uy * a * along + vy * b * across
                b++
                if (px < x0 || px >= x1 || py < y0 || py >= y1) continue
                val dd = j.beyondRoute(px, py)
                if (dd > reach || dd < 3.8) continue
                if (j.landCover(px, py) != LandCover.CROP) continue
                if (field(j.ctx.seed.toLong(), px, py).id != field.id) continue
                if (!j.free(px, py, clearRoute = 3.8, clearSide = 1.0, r = 3.3)) continue
                val z = j.groundAt(px, py)
                val h = Hash.of(field.id, a.toLong(), b.toLong())
                j.out.put(id, px, py, z - 0.05, InstanceStore.yawAlong(ux, uy), 0.92f + Hash.unit(h) * 0.14f,
                    InstanceStore.tint(Rand(h), 0.08f, 0.04f), Hash.unit(h ushr 7))
            }
            a++
        }
    }

    private fun bale(x: Double, y: Double, rnd: Rand) {
        if (!j.reg.has("scn_bale") || !j.free(x, y, clearRoute = 4.0, clearSide = 2.0)) return
        val n = 1 + rnd.int(3)
        val a = rnd.angle().toDouble()
        for (k in 0 until n) {
            val px = x + cos(a) * k * 1.6
            val py = y + sin(a) * k * 1.6
            if (!j.free(px, py, clearRoute = 4.0, clearSide = 2.0)) continue
            j.out.put("scn_bale", px, py, j.groundAt(px, py) - 0.05, (a + PI / 2 + rnd.sym(0.2f)).toFloat(), rnd.range(0.95f, 1.05f),
                InstanceStore.tint(rnd, 0.1f, 0.04f), 0f)
        }
    }

    /** Row direction of a field: parallel to the road for fields beside it (as parcels are), else random. */
    private fun fieldAngle(f: Field): Double {
        val h = j.routeHit(f.x, f.y)
        val base = if (h != null) atan2(h.dirY, h.dirX) else Hash.unit(Hash.of(f.id, 0xA9)) * PI
        return base + (if (Hash.unit(Hash.of(f.id, 0x90)) < 0.35f) PI / 2 else 0.0)
    }

    // ------------------------------------------------------------------ vines, orchards

    private fun vineAndOrchards() {
        val seen = BooleanArray(cells * cells)
        val stack = IntArray(cells * cells)
        for (start in 0 until cells * cells) {
            val k0 = lc!!.classes[start].toInt() and 0xFF
            if (seen[start] || (k0 != LandCover.VINEYARD && k0 != LandCover.ORCHARD)) continue
            // Flood fill one connected area of the same class.
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            val members = ArrayList<Int>()
            while (sp > 0) {
                val i = stack[--sp]
                members += i
                val c = i % cells; val r = i / cells
                for ((dc, dr) in NB4) {
                    val nc = c + dc; val nr = r + dr
                    if (nc < 0 || nr < 0 || nc >= cells || nr >= cells) continue
                    val ni = nr * cells + nc
                    if (!seen[ni] && (lc.classes[ni].toInt() and 0xFF) == k0) { seen[ni] = true; stack[sp++] = ni }
                }
            }
            if (k0 == LandCover.VINEYARD) vineyard(members) else orchard(members)
        }
    }

    /** Main axis of an area: PCA of the cell centres; on slopes the fall line (vines run downhill). */
    private fun axisOf(members: List<Int>): Pair<Double, Double> {
        var mx = 0.0; var my = 0.0
        for (i in members) { mx += cx(i % cells); my += cy(i / cells) }
        mx /= members.size; my /= members.size
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in members) {
            val dx = cx(i % cells) - mx; val dy = cy(i / cells) - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val tr = sxx + syy
        val det = sxx * syy - sxy * sxy
        val l1 = tr / 2 + sqrt(max(0.0, tr * tr / 4 - det))
        val l2 = tr / 2 - sqrt(max(0.0, tr * tr / 4 - det))
        val g = gradient(mx, my)
        val slope = hypot(g[0], g[1])
        val ang = when {
            slope > 0.08 -> atan2(g[1], g[0])
            l2 > 1e-6 && l1 / l2 > 2.2 -> 0.5 * atan2(2 * sxy, sxx - syy)
            else -> j.routeHit(mx, my)?.let { atan2(it.dirY, it.dirX) } ?: 0.0
        }
        return Pair(cos(ang), sin(ang))
    }

    private fun gradient(x: Double, y: Double): DoubleArray {
        val d = 15.0
        val gx = (j.ground.terrain(x + d, y) - j.ground.terrain(x - d, y)) / (2 * d)
        val gy = (j.ground.terrain(x, y + d) - j.ground.terrain(x, y - d)) / (2 * d)
        return doubleArrayOf(gx, gy)
    }

    private fun vineyard(members: List<Int>) {
        val rows = (6f / p.vineRowM + 0.5f).toInt().coerceAtLeast(2)
        val id = "scn_vine_$rows"
        if (!j.reg.has(id)) return
        val (ux, uy) = axisOf(members)
        lattice(members, 6.0, 6.0, ux, uy, maxDist = 170.0) { x, y, h ->
            if (!j.free(x, y, clearRoute = 3.0, clearSide = 1.2, r = 3.0)) return@lattice
            j.out.put(id, x, y, j.groundAt(x, y) - 0.05, InstanceStore.yawAlong(ux, uy), 1f, InstanceStore.tint(Rand(h), 0.08f, 0.05f), Hash.unit(h))
        }
    }

    private fun orchard(members: List<Int>) {
        val sp = p.orchardTree
        if (!j.reg.has(sp.id)) return
        val (ux, uy) = axisOf(members)
        val spacingU = if (sp == Species.OLIVE) 7.0 else 5.0
        val spacingV = if (sp == Species.OLIVE) 7.0 else 6.0
        lattice(members, spacingU, spacingV, ux, uy, maxDist = 450.0) { x, y, h ->
            val rnd = Rand(h)
            if (!j.free(x, y, clearRoute = 3.0, clearSide = 1.5, r = 1.0)) return@lattice
            if (rnd.chance(0.05f)) return@lattice // gaps where trees died
            val jx = x + rnd.sym(0.35f); val jy = y + rnd.sym(0.35f)
            j.tree(sp, jx, jy, (0.88f + rnd.gauss() * 0.08f).coerceIn(0.7f, 1.05f), rnd, 0.15)
        }
    }

    /**
     * Calls [emit] for every point of a lattice (spacing [su] along (ux, uy), [sv] across) that falls
     * into one of the area's cells and lies within [maxDist] of the route. The lattice origin is
     * global per area (its first cell), so blocks of adjacent cells line up into continuous rows.
     */
    private inline fun lattice(members: List<Int>, su: Double, sv: Double, ux: Double, uy: Double, maxDist: Double,
                               emit: (Double, Double, Long) -> Unit) {
        val vx = -uy; val vy = ux
        val first = members.minOrNull() ?: return
        // Origin on a world-anchored lattice so neighbouring tiles agree when an area crosses tiles.
        val ox0 = floor(cx(first % cells) / su) * su
        val oy0 = floor(cy(first / cells) / sv) * sv
        for (i in members) {
            val c = i % cells; val r = i / cells
            val x = cx(c); val y = cy(r)
            if (j.dist.at(x, y) > maxDist + 10) continue
            val x0 = x - cellM / 2; val x1 = x + cellM / 2; val y0 = y - cellM / 2; val y1 = y + cellM / 2
            var umin = Double.MAX_VALUE; var umax = -Double.MAX_VALUE; var vmin = Double.MAX_VALUE; var vmax = -Double.MAX_VALUE
            for (px in doubleArrayOf(x0, x1)) for (py in doubleArrayOf(y0, y1)) {
                val du = (px - ox0) * ux + (py - oy0) * uy
                val dv = (px - ox0) * vx + (py - oy0) * vy
                umin = min(umin, du); umax = max(umax, du); vmin = min(vmin, dv); vmax = max(vmax, dv)
            }
            var a = floor(umin / su).toInt()
            while (a * su <= umax) {
                var b = floor(vmin / sv).toInt()
                while (b * sv <= vmax) {
                    val px = ox0 + ux * a * su + vx * b * sv
                    val py = oy0 + uy * a * su + vy * b * sv
                    if (px >= x0 && px < x1 && py >= y0 && py < y1 && j.dist.at(px, py) <= maxDist) {
                        emit(px, py, Hash.of(j.seed, a.toLong(), b.toLong(), i.toLong()))
                    }
                    b++
                }
                a++
            }
        }
    }

    class Field(val id: Long, val x: Double, val y: Double)

    companion object {
        private val NB4 = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        const val FIELD_M = 190.0

        /** The pseudo-parcel containing (x, y): nearest jittered lattice point. */
        fun field(seed: Long, x: Double, y: Double): Field {
            val gx = floor(x / FIELD_M).toInt()
            val gy = floor(y / FIELD_M).toInt()
            var best = Double.MAX_VALUE
            var bid = 0L; var bx = 0.0; var by = 0.0
            for (dy in -1..1) for (dx in -1..1) {
                val h = Hash.of(seed, (gx + dx).toLong(), (gy + dy).toLong(), 0xF1E1DL)
                // Stretched cells: parcels are longer than wide.
                val px = (gx + dx + 0.15 + 0.7 * Hash.unit(h)) * FIELD_M
                val py = (gy + dy + 0.15 + 0.7 * Hash.unit(h ushr 13)) * FIELD_M
                val d = (px - x) * (px - x) + (py - y) * (py - y) * 1.6
                if (d < best) { best = d; bid = h; bx = px; by = py }
            }
            return Field(bid, bx, by)
        }

        fun cropOf(seed: Long, p: RegionProfile, f: Field): Crop {
            val keys = p.crops.keys.toList()
            if (keys.isEmpty()) return Crop.WHEAT
            val w = FloatArray(keys.size) { p.crops.getValue(keys[it]) }
            return keys[Rand(Hash.of(f.id, seed)).weighted(w)]
        }

        fun crop(seed: Long, p: RegionProfile, x: Double, y: Double): Crop = cropOf(seed, p, field(seed, x, y))
    }
}
