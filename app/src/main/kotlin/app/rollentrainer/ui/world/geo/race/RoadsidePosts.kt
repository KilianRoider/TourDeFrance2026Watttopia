package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.ui.world.geo.SceneContext
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Sharp bends of the route, for W2's warning signs and chevron boards (scenery/Roadside via
 * RouteFeatures) and for [RoadsidePosts]: computed once per scene from the path, the TOWN stretches
 * and the pack's hairpins.
 */
internal class RouteBends(ctx: SceneContext) {
    private val path: RoutePath = ctx.path
    private val towns = ctx.places.towns

    /** Heading (rad, CCW from east) at each sample, from its neighbours. */
    val heading = DoubleArray(path.size) { i ->
        val a = max(0, i - 1)
        val b = min(path.size - 1, i + 1)
        atan2(path.y[b] - path.y[a], path.x[b] - path.x[a])
    }

    /** Warning signs: distance along the route and the sign prototype. */
    val warnings: List<Pair<Double, String>>

    /** Bends sharp enough for J4 chevron boards: (s at the apex, turn in rad, + = left). */
    val chevronBends: List<Pair<Double, Double>>

    init {
        val step = 10.0
        val n = (path.lengthM / step).toInt()
        val turn = DoubleArray(n + 1) { k -> turnAt(k * step, 25.0) }
        // Clusters of sharp bends.
        val bends = ArrayList<Triple<Double, Double, Double>>() // start, apex s, total turn
        var k = 0
        while (k <= n) {
            if (abs(turn[k]) > 50 * DEG) {
                val sign = if (turn[k] > 0) 1 else -1
                var e = k
                var best = k
                while (e + 1 <= n && turn[e + 1] * sign > 30 * DEG) { e++; if (abs(turn[e]) > abs(turn[best])) best = e }
                bends += Triple(k * step, best * step, turnAt(best * step, 40.0))
                k = e + 1
            } else k++
        }
        val warn = ArrayList<Pair<Double, String>>()
        var lastWarn = -1e9
        for ((idx, b) in bends.withIndex()) {
            val start = b.first
            if (start - lastWarn < 400 || start < 150) continue
            if (inTown(start - 110)) continue
            // A series: another sharp bend within 250 m after this one.
            val series = idx + 1 < bends.size && bends[idx + 1].first - start < 250
            val left = b.third > 0
            val id = when {
                series && left -> "scn_sign_bends_l"
                series -> "scn_sign_bends_r"
                left -> "scn_sign_bend_l"
                else -> "scn_sign_bend_r"
            }
            warn += (start - 110) to id
            lastWarn = start
        }
        warnings = warn
        val chev = ArrayList<Pair<Double, Double>>()
        for (b in bends) if (abs(b.third) > 75 * DEG) chev += b.second to b.third
        // The pack's hairpins carry their own signed turn (unwrapped, + = left, as [heading]).
        for (h in ctx.meta.hairpins) if (chev.none { abs(it.first - h.s) < 40 }) {
            chev += h.s to (if (abs(h.turnDeg) > 1.0) h.turnDeg * DEG else turnAt(h.s, 40.0))
        }
        chevronBends = chev.sortedBy { it.first }
    }

    /**
     * Signed turn (rad, + = left) over [s] +- [half], summed sample by sample: a hairpin turns more
     * than 180 degrees within +-40 m, which the difference of the two end headings would wrap to the
     * opposite sign. Consecutive headings (central differences) never differ by half a turn.
     */
    fun turnAt(s: Double, half: Double): Double {
        val a = idx(s - half)
        val b = idx(s + half)
        var t = 0.0
        for (i in a until b) t += angleDiff(heading[i], heading[i + 1])
        return t
    }

    fun idx(s: Double): Int = (s / path.stepM).toInt().coerceIn(0, path.size - 1)

    private fun inTown(s: Double): Boolean = towns.any { s >= it.sIn && s <= it.sOut }

    companion object {
        const val DEG = PI / 180.0
        fun angleDiff(a: Double, b: Double): Double {
            var d = b - a
            while (d > PI) d -= 2 * PI
            while (d < -PI) d += 2 * PI
            return d
        }
    }
}

/**
 * Where W2's roadside posts may stand (scenery/Roadside: street lamps, delineators, snow poles,
 * kilometre bornes, speed, town and warning signs, chevron boards), so W3's spectators and barriers
 * keep out of them. W2 places its posts per tile and W3 its crowd per range, independently and in
 * any order, so neither can read what the other placed: these are the positions Roadside's rules
 * give, as a pure function of the route. They are a superset of what W2 places (it drops some per
 * tile: no room, a building, the lamp style of the tile), so a slot is at most left empty for a
 * post that is not there. Keep the rules and offsets in step with Roadside.
 */
internal class RoadsidePosts(ctx: SceneContext, bends: RouteBends, private val repeatAt: (Double) -> Boolean) {
    private val path: RoutePath = ctx.path
    private val towns = ctx.places.towns.sortedBy { it.sIn }

    /** Posts as (x, y, radius of the post's foot, radius of the post or its sign at body height), 4 per post. */
    private var data = DoubleArray(1024)
    private var count = 0
    private val cells = HashMap<Long, IntArray>()

    init {
        val len = path.lengthM
        // Delineators every 50 m and snow poles every 25 m out of towns, a borne every kilometre
        // (Roadside.delineators, snowPoles, borne).
        var s = 0.0
        while (s <= len) {
            if (!inTown(s)) {
                val q = path.poseAt(s)
                val rural = q.roadClass in 2..7
                if (s % 50.0 == 0.0 && rural && q.z <= 1500) {
                    add(s, -1, 0.75, 0.06, 0.06)
                    add(s + 6.0, 1, 0.75, 0.06, 0.06)
                }
                if (q.z >= 1500 && q.roadClass <= 7) {
                    add(s, -1, 1.05, 0.03, 0.03)
                    add(s + 12.5, 1, 1.05, 0.03, 0.03)
                }
                if (s >= 500 && s % 1000.0 == 0.0 && rural) add(s, -1, 0.6, 0.25, 0.25)
            }
            s += 25.0
        }
        for (t in towns) {
            // Street lamps on both sides, at the spacings of Paris (26 m) and of other towns (32 m).
            for (step in doubleArrayOf(26.0, 32.0)) {
                var ls = floor(t.sIn / step) * step + step
                while (ls < t.sOut) {
                    add(ls, -1, 0.6, 0.16, 0.16)
                    add(ls + step / 2, 1, 0.6, 0.16, 0.16)
                    ls += step
                }
            }
            // Speed limits after the entry and the exit.
            if (t.sIn + 18 <= len) add(t.sIn + 18, -1, 0.9, 0.04, 0.33)
            if (t.sOut + 30 <= len) add(t.sOut + 30, -1, 0.9, 0.04, 0.33)
            // Entry and exit signs (or 6 m on): two posts under a 1.3 m panel turned 10 degrees.
            if (t.name.isNotBlank()) for (e in doubleArrayOf(t.sIn, t.sOut)) {
                if (e <= 0 || e >= len) continue
                townSign(e)
                townSign(e + 6)
            }
        }
        // Warning signs (0.9 m plates at head height) and chevron boards on the outside of hairpins.
        for ((ws, _) in bends.warnings) add(ws, -1, 0.9, 0.04, 0.45)
        for ((apex, turn) in bends.chevronBends) {
            val outer = if (turn > 0) -1 else 1
            val nb = if (abs(turn) > 140 * RouteBends.DEG) 5 else 3
            for (k in 0 until nb) add(apex + (k - (nb - 1) / 2.0) * 7.0, outer, 1.4, 0.03, 0.25, allowBridge = true)
        }
        // Bucketed by centre in 2 m cells; a query widens its box by the largest radius (MAX_R).
        val tmp = HashMap<Long, MutableList<Int>>()
        for (k in 0 until count) tmp.getOrPut(key(data[k * 4], data[k * 4 + 1])) { ArrayList(2) }.add(k)
        for ((kk, v) in tmp) cells[kk] = v.toIntArray()
    }

    private fun inTown(s: Double): Boolean = towns.any { s >= it.sIn && s <= it.sOut }

    private fun key(x: Double, y: Double): Long = key(floor(x / CELL).toInt(), floor(y / CELL).toInt())
    private fun key(cx: Int, cy: Int): Long = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)

    /** As Roadside.spot: [off] m beyond the road edge on [side] (+1 left of travel), not in tunnels. */
    private fun at(s: Double, side: Int, off: Double, allowBridge: Boolean): DoubleArray? {
        if (s < 0 || s > path.lengthM || repeatAt(s)) return null
        val q = path.poseAt(s)
        if (q.inTunnel || (!allowBridge && q.onBridge)) return null
        val hw = q.widthM / 2
        return doubleArrayOf(q.x - q.dirY * side * (hw + off), q.y + q.dirX * side * (hw + off), q.dirX, q.dirY)
    }

    private fun add(s: Double, side: Int, off: Double, rPost: Double, rBody: Double, allowBridge: Boolean = false) {
        val p = at(s, side, off, allowBridge) ?: return
        put(p[0], p[1], rPost, rBody)
    }

    private fun put(x: Double, y: Double, rPost: Double, rBody: Double) {
        if (count * 4 + 4 > data.size) data = data.copyOf(data.size * 2)
        data[count * 4] = x; data[count * 4 + 1] = y; data[count * 4 + 2] = rPost; data[count * 4 + 3] = rBody
        count++
    }

    /** Roadside.townSign: the panel's two posts, 0.78 m apart; the 1.3 m panel reaches 0.26 m beyond each. */
    private fun townSign(s: Double) {
        val p = at(s, -1, 1.0, false) ?: return
        val a = atan2(-p[3], -p[2]) - 10 * RouteBends.DEG
        val rx = -sin(a); val ry = cos(a)
        for (u in doubleArrayOf(-0.3, 0.3)) put(p[0] + rx * u * 1.3, p[1] + ry * u * 1.3, 0.04, 0.3)
    }

    /** Calls [f] with every post that may reach into the box (x0, y0)-(x1, y1). */
    private inline fun near(x0: Double, y0: Double, x1: Double, y1: Double, f: (Int) -> Unit) {
        for (cy in floor((y0 - MAX_R) / CELL).toInt()..floor((y1 + MAX_R) / CELL).toInt()) {
            for (cx in floor((x0 - MAX_R) / CELL).toInt()..floor((x1 + MAX_R) / CELL).toInt()) {
                val a = cells[key(cx, cy)] ?: continue
                for (k in a) f(k)
            }
        }
    }

    /** Whether a body of radius [r] at (x, y) would stand in a post or in the sign it carries. */
    fun blocks(x: Double, y: Double, r: Double): Boolean {
        near(x - r, y - r, x + r, y + r) { k -> if (hypot(data[k * 4] - x, data[k * 4 + 1] - y) < data[k * 4 + 3] + r) return true }
        return false
    }

    /** Whether the line (ax, ay)-(bx, by) passes within [r] of a post's foot. */
    fun crosses(ax: Double, ay: Double, bx: Double, by: Double, r: Double): Boolean {
        val vx = bx - ax; val vy = by - ay
        val l2 = vx * vx + vy * vy
        near(min(ax, bx) - r, min(ay, by) - r, max(ax, bx) + r, max(ay, by) + r) { k ->
            val px = data[k * 4]; val py = data[k * 4 + 1]
            val t = if (l2 > 1e-12) (((px - ax) * vx + (py - ay) * vy) / l2).coerceIn(0.0, 1.0) else 0.0
            if (hypot(ax + vx * t - px, ay + vy * t - py) < data[k * 4 + 2] + r) return true
        }
        return false
    }

    /**
     * Whether a post stands in the rectangle [x0]..[x1] x [z0]..[z1] of the frame centred on (cx, cy)
     * facing (fx, fy): local +x is the right of the facing, local -z the facing (as a camp's frame).
     */
    fun inRect(cx: Double, cy: Double, fx: Double, fy: Double, x0: Double, x1: Double, z0: Double, z1: Double): Boolean {
        val reach = hypot(max(abs(x0), abs(x1)), max(abs(z0), abs(z1)))
        near(cx - reach, cy - reach, cx + reach, cy + reach) { k ->
            val dx = data[k * 4] - cx; val dy = data[k * 4 + 1] - cy
            val rb = data[k * 4 + 3]
            val lx = fy * dx - fx * dy
            val lz = -fx * dx - fy * dy
            if (lx > x0 - rb && lx < x1 + rb && lz > z0 - rb && lz < z1 + rb) return true
        }
        return false
    }

    companion object {
        private const val CELL = 2.0
        /** The largest radius of a post or its sign (warning signs). */
        private const val MAX_R = 0.45
    }
}
