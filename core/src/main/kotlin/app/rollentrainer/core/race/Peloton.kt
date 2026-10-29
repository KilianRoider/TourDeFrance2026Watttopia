package app.rollentrainer.core.race

import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** What a rider is doing; set by the tactics, carried out by [Peloton]. */
internal object Mode {
    /** Riding in a group: pacing it when he is its pacer, else holding his slot in the formation. */
    const val PACK = 0
    const val ATTACK = 1
    /** Closing a gap to [Peloton.anchor]'s wheel. */
    const val CHASE = 2
    const val SPRINT = 3
    /** Job done (lead-out, mountain pull): rolls on easily at the side of the road. */
    const val SITUP = 4
    /** Alone against the clock. */
    const val TT = 5
    /** Waiting for his start in a time trial: not on the road. */
    const val WAIT = 6
    /** Across the line, rolling out. */
    const val DONE = 7
}

/**
 * The bodies on the road: position, lane, speed, power and fatigue of every rider in flat arrays (index
 * = roster id), and one sub step of physics, drafting, lane keeping and collision handling. The
 * [Tactics] decide modes, pacers, pace factors and formation slots about once a second; this class
 * carries them out every sub step. Nothing here allocates per step.
 */
internal class Peloton(val route: Route, val roster: Roster, val user: Int, val style: StageStyle, roadWidthM: Double) {
    val n = roster.size
    val length = route.lengthM
    val edge = roadWidthM / 2 - 0.35
    val roadWidth = roadWidthM
    val timeTrial = style == StageStyle.ITT || style == StageStyle.TTT
    val teamTrial = style == StageStyle.TTT

    // Per rider, fixed.
    val mass = DoubleArray(n) { roster.riders[it].weightKg + RiderParams.BIKE_KG }
    val cda0 = DoubleArray(n) { if (timeTrial) roster.riders[it].cdaTt else roster.riders[it].cda }
    val cp = DoubleArray(n) { roster.riders[it].criticalPowerW }
    val wp = DoubleArray(n) { roster.riders[it].wPrimeJ }
    val peak = DoubleArray(n) { roster.riders[it].peakPowerW }
    val p1m = DoubleArray(n) { roster.riders[it].peak1mWkg * roster.riders[it].weightKg }
    val durab = DoubleArray(n) { roster.riders[it].durability }
    val strength = DoubleArray(n) { roster.riders[it].criticalPowerW / roster.riders[it].weightKg }
    val team = IntArray(n) { roster.riders[it].team.index }

    // Per rider, state.
    val d = DoubleArray(n)
    val x = DoubleArray(n)
    val v = DoubleArray(n)
    val vx = DoubleArray(n)
    val pw = DoubleArray(n)
    val brake = DoubleArray(n)
    val wbal = DoubleArray(n) { wp[it] }
    val kj = DoubleArray(n)
    val draft = DoubleArray(n)
    val cad = DoubleArray(n)
    val crank = DoubleArray(n) { it * 0.7 }
    val xt = DoubleArray(n)
    val standing = BooleanArray(n)

    // Per rider, orders from the tactics.
    val mode = IntArray(n)
    val modeUntil = DoubleArray(n)
    val modePower = DoubleArray(n)
    val anchor = IntArray(n) { -1 }
    val laneX = DoubleArray(n)
    /** Share of W' a follower keeps in reserve before he lets a wheel go (0 = fights to the end). */
    val reserve = DoubleArray(n) { 0.3 }
    val pacer = BooleanArray(n)
    val paceFactor = DoubleArray(n) { 0.8 }
    val paceLane = DoubleArray(n)
    val slotRef = IntArray(n) { -1 }
    val slotBack = DoubleArray(n)
    val slotX = DoubleArray(n)
    /** May open a sprint for the line, and where. */
    val sprintOk = BooleanArray(n)
    val launch = DoubleArray(n) { 200.0 }
    val startAt = DoubleArray(n)
    val finishAt = DoubleArray(n) { Double.NaN }

    // Scratch per sub step.
    private val blocker = IntArray(n) { -1 }

    /** Someone is right in front, in the same line: such a rider cannot set the pace for a group. */
    fun boxedIn(i: Int): Boolean {
        val b = blocker[i]
        return b >= 0 && d[b] - d[i] < 4.0 && v[b] < v[i] + 1.0
    }
    private val push = DoubleArray(n)
    private val passX = DoubleArray(n)
    // Minus infinity = not passing (a time trial runs at negative clock times before the user's start).
    private val passUntil = DoubleArray(n) { Double.NEGATIVE_INFINITY }
    private val passAt = DoubleArray(n) { -1e9 }

    // The user's controls.
    var userPowerW = 0.0
    private var manualX = 0.0
    private var manualUntil = -1.0

    var t = 0.0
    private var steps = 0L
    /** First rider who opened a sprint since the tactics last looked. */
    var newSprinter = -1

    // Order on the road (front first) and the groups.
    val ord = IntArray(n) { it }
    private val pos = IntArray(n) { it }
    val grp = IntArray(n)
    var groupCount = 0
        private set
    val gOff = IntArray(n + 1)
    val gm = IntArray(n)
    private val gRef = IntArray(n)

    // Time trial splits: elapsed time at every SPLIT_M since the rider's start.
    val splitCount = (length / SPLIT_M).toInt() + 1
    val splits: FloatArray = if (timeTrial) FloatArray(n * splitCount) { Float.NaN } else FloatArray(0)

    // When the head of the race first reached each HEAD_BIN metres: the reference for time gaps.
    private val headTime = DoubleArray((length / HEAD_BIN).toInt() + 2) { Double.NaN }
    var headD = Double.NEGATIVE_INFINITY
        private set

    fun active(i: Int): Boolean = mode[i] != Mode.WAIT && mode[i] != Mode.DONE
    fun isAi(i: Int): Boolean = i != user

    fun cpEff(i: Int): Double = cp[i] * (1 - durab[i] * min(1.0, kj[i] / FATIGUE_KJ))

    fun gradeAt(dist: Double): Double = if (dist >= length) 0.0 else route.gradientAt(dist)

    fun groupSize(g: Int): Int = gOff[g + 1] - gOff[g]
    fun groupFront(g: Int): Int = gm[gOff[g]]
    fun groupRear(g: Int): Int = gm[gOff[g + 1] - 1]

    // ---- user controls ----

    fun moveUser(dir: Int) {
        manualX = (x[user] + dir * LANE_STEP).coerceIn(-edge, edge)
        manualUntil = t + MANUAL_HOLD_SEC
    }

    // ---- head of the race ----

    /** Seconds the rider at [dist] is behind the head of the race. */
    fun gapAt(dist: Double): Double {
        if (dist >= headD) return 0.0
        val k = (dist.coerceAtLeast(0.0) / HEAD_BIN).toInt().coerceIn(0, headTime.size - 2)
        val a = headTime[k]
        if (a.isNaN()) return 0.0
        val b = headTime[k + 1]
        val f = (dist.coerceAtLeast(0.0) - k * HEAD_BIN) / HEAD_BIN
        val at = if (b.isNaN()) a else a + (b - a) * f
        return (t - at).coerceAtLeast(0.0)
    }

    /** Head log for a race situation set up at the start (a break already up the road at [speed]). */
    fun seedHead(frontD: Double, speed: Double) {
        headD = frontD
        var k = 0
        while (k * HEAD_BIN <= frontD && k < headTime.size) {
            headTime[k] = t - (frontD - k * HEAD_BIN) / speed
            k++
        }
    }

    private fun updateHead() {
        var m = headD
        for (i in 0 until n) if (mode[i] != Mode.WAIT && d[i] > m) m = d[i]
        m = min(m, length)
        if (m <= headD) return
        val from = (max(headD, 0.0) / HEAD_BIN).toInt()
        val to = (m / HEAD_BIN).toInt()
        for (k in from..min(to, headTime.size - 1)) if (headTime[k].isNaN()) headTime[k] = t
        headD = m
    }

    // ---- one sub step ----

    fun step(h: Double) {
        t += h
        startRiders()
        sortOrder()
        buildGroups()
        if (steps % DRAFT_EVERY == 0L) computeDraft()
        neighbours()
        for (i in 0 until n) if (i != user) control(i) else controlUser()
        moveLaterally(h)
        integrate(h)
        // Moving one rider out of another's way can push him into a third: repeat until nobody overlaps.
        for (pass in 0 until RESOLVE_PASSES) {
            sortOrder()
            if (resolve() == 0) break
        }
        if (!timeTrial) updateHead()
        steps++
    }

    private fun startRiders() {
        for (i in 0 until n) if (mode[i] == Mode.WAIT && t >= startAt[i]) {
            mode[i] = if (teamTrial) Mode.PACK else Mode.TT
            v[i] = 0.0
        }
    }

    /** Insertion sort by distance, front first: O(n) as the order hardly changes between steps. */
    private fun sortOrder() {
        for (k in 1 until n) {
            val i = ord[k]
            val di = d[i]
            var j = k - 1
            while (j >= 0 && d[ord[j]] < di) { ord[j + 1] = ord[j]; j-- }
            ord[j + 1] = i
        }
        for (k in 0 until n) pos[ord[k]] = k
    }

    /** Groups: riders closer than about a second (at least [GROUP_GAP_M]) to the rider ahead belong together. */
    private fun buildGroups() {
        groupCount = 0
        var count = 0
        var prev = -1
        for (k in 0 until n) {
            val i = ord[k]
            if (!active(i)) { grp[i] = -1; continue }
            if (prev < 0 || d[prev] - d[i] > max(GROUP_GAP_M, v[i] * 1.0)) {
                gOff[groupCount] = count
                groupCount++
            }
            grp[i] = groupCount - 1
            gm[count++] = i
            prev = i
        }
        gOff[groupCount] = count
        for (g in 0 until groupCount) {
            var ref = -1
            var firstAi = -1
            for (k in gOff[g] until gOff[g + 1]) {
                val i = gm[k]
                if (i == user || mode[i] != Mode.PACK) continue
                if (firstAi < 0) firstAi = i
                if (pacer[i]) { ref = i; break }
            }
            gRef[g] = if (ref >= 0) ref else firstAi
        }
    }

    private fun computeDraft() {
        val itt = style == StageStyle.ITT
        for (k in 0 until n) {
            val i = ord[k]
            if (!active(i) || itt) { draft[i] = 0.0; continue }
            var best = 0.0
            var bestCrowd = 0.0
            var crowd = 0.0
            var q = k - 1
            while (q >= 0) {
                val j = ord[q]
                q--
                val gap = d[j] - d[i] - Drafting.BIKE_LENGTH_M
                if (gap > Drafting.CROWD_AHEAD_M) break
                if (!active(j) || (teamTrial && team[j] != team[i])) continue
                val dx = x[j] - x[i]
                val w = Drafting.wake(gap, dx)
                val c = Drafting.crowdWeight(gap, dx)
                crowd += c
                if (w > best) { best = w; bestCrowd = c }
            }
            var followed = false
            q = k + 1
            while (q < n) {
                val j = ord[q]
                q++
                val gap = d[i] - d[j] - Drafting.BIKE_LENGTH_M
                if (gap > 1.0) break
                if (gap >= -0.2 && active(j) && abs(x[j] - x[i]) < 0.5 && (!teamTrial || team[j] == team[i])) { followed = true; break }
            }
            draft[i] = Drafting.reduction(best, crowd - bestCrowd, followed)
        }
    }

    /** The wheel straight ahead (if any) and the sideways push away from riders alongside. */
    private fun neighbours() {
        for (k in 0 until n) {
            val i = ord[k]
            blocker[i] = -1
            push[i] = 0.0
            if (!active(i)) continue
            var best = Double.MAX_VALUE
            var q = k - 1
            while (q >= 0) {
                val j = ord[q]
                q--
                val dd = d[j] - d[i]
                if (dd > BLOCK_LOOK_M) break
                if (!active(j)) continue
                val dx = x[j] - x[i]
                if (abs(dx) < BLOCK_X && dd < best) { best = dd; blocker[i] = j }
                if (dd < SEP_D) push[i] += sepPush(i, j, dx, dd)
            }
            q = k + 1
            while (q < n) {
                val j = ord[q]
                q++
                val dd = d[i] - d[j]
                if (dd > SEP_D) break
                if (!active(j)) continue
                push[i] += sepPush(i, j, x[j] - x[i], dd)
            }
        }
    }

    private fun sepPush(i: Int, j: Int, dx: Double, dd: Double): Double {
        val radius = if (j == user) SEP_X + 0.15 else SEP_X
        val adx = abs(dx)
        if (adx >= radius) return 0.0
        val w = ((SEP_D - dd) / (SEP_D - 1.0)).coerceIn(0.0, 1.0)
        val dir = if (dx > 0) -1.0 else if (dx < 0) 1.0 else if (i < j) -1.0 else 1.0
        return dir * (radius - adx) * SEP_GAIN * w * (if (j == user) 2.0 else 1.0)
    }

    /** Lateral position with the least traffic in the next few metres, preferring small moves. */
    fun freeLane(i: Int): Double {
        var bestX = x[i]
        var bestCost = Double.MAX_VALUE
        val k0 = pos[i]
        var c = -edge
        while (c <= edge + 1e-9) {
            var cost = 0.25 * abs(c - x[i])
            var q = k0 - 1
            while (q >= 0) {
                val j = ord[q]
                q--
                val dd = d[j] - d[i]
                if (dd > FREE_AHEAD_M) break
                if (active(j) && abs(c - x[j]) < 0.75) cost += if (dd < 3.5) 2.0 else 1.0
            }
            q = k0 + 1
            while (q < n) {
                val j = ord[q]
                q++
                val dd = d[i] - d[j]
                if (dd > 1.0) break
                if (active(j) && abs(c - x[j]) < 0.75) cost += 2.5
            }
            if (cost < bestCost) { bestCost = cost; bestX = c }
            c += 0.3
        }
        return bestX
    }

    /** The rider [i] follows: his own pacer if it is in his group, else the group's. */
    private fun refOf(i: Int): Int {
        val r = slotRef[i]
        // A team-time-trial rider who has lost a few lengths still rides for his own train, not for whoever is near.
        // In the team time trial the user is part of the line, and the man behind him rides on his wheel.
        if (r >= 0 && (r != user || teamTrial) && (mode[r] == Mode.PACK || r == user) &&
            (grp[r] == grp[i] || (teamTrial && team[r] == team[i] && d[r] - d[i] < 150))) return r
        val g = grp[i]
        return if (g >= 0) gRef[g] else -1
    }

    /** Pacing on a gradient: a little harder uphill, easing off downhill. */
    fun gradeMod(g: Double): Double = when {
        g > 1 -> 1 + min(0.06, 0.012 * (g - 1))
        g < -2 -> max(0.15, 1 + 0.2 * (g + 2))
        else -> 1.0
    }

    private fun control(i: Int) {
        val m = mode[i]
        if (m == Mode.WAIT) { pw[i] = 0.0; brake[i] = 0.0; v[i] = 0.0; xt[i] = x[i]; return }
        if (m == Mode.DONE) { pw[i] = 0.0; brake[i] = if (v[i] > 6) 0.8 else 0.0; xt[i] = x[i]; return }
        val g = gradeAt(d[i])
        val cpE = cpEff(i)
        val fresh = 1 - durab[i] * min(1.0, kj[i] / FATIGUE_KJ)
        val maxP = RacePhysics.maxPower(cpE, peak[i] * fresh, wbal[i], wp[i])
        val cdaE = cda0[i] * (1 - draft[i])
        var wantV = Double.NaN
        var p = 0.0
        var cap = maxP
        var lane = x[i]
        var tau = TAU
        when (m) {
            Mode.ATTACK -> { p = min(modePower[i], maxP); lane = laneX[i] }
            Mode.SPRINT -> { p = maxP; lane = laneX[i] }
            Mode.SITUP -> { p = 0.55 * cpE * gradeMod(g); lane = edge * 0.75 }
            Mode.TT -> {
                p = min(modePower[i] * (1 + (0.012 * g).coerceIn(-0.3, 0.07)), maxP)
                lane = TT_LANE
            }
            Mode.CHASE -> {
                val a = anchor[i]
                if (a < 0 || !active(a)) p = 0.9 * cpE
                else {
                    wantV = v[a] + ((d[a] - 2.0 - d[i]) * 0.3).coerceIn(-0.3, 3.0)
                    lane = x[a]
                }
            }
            else -> {
                val r = refOf(i)
                when {
                    r == i -> { p = min(paceFactor[i] * cpE * gradeMod(g), maxP); lane = paceLane[i] }
                    r >= 0 -> {
                        // Holding a place in the bunch: gentle corrections, no sprints for a slot.
                        wantV = v[r] + ((d[r] - slotBack[i] - d[i]) * 0.35).coerceIn(-1.0, 1.0)
                        cap = if (wbal[i] > reserve[i] * wp[i]) min(maxP, cpE * 1.5) else cpE
                        tau = FOLLOW_TAU
                        lane = slotX[i]
                    }
                    else -> { p = min(paceFactor[i] * cpE * gradeMod(g), maxP); lane = paceLane[i] }
                }
                if (sprintOk[i] && length - d[i] <= launch[i] && length > d[i]) {
                    val gi = grp[i]
                    if (gi >= 0 && d[groupFront(gi)] - d[i] < SPRINT_ZONE_M) startSprint(i)
                }
            }
        }
        // Never into the wheel ahead: hold the speed down, and look for a way round when held up.
        val b = blocker[i]
        var held = false
        if (b >= 0) {
            val room = d[b] - d[i] - MIN_D - 0.15
            val vLim = v[b] + max(0.0, room) * 0.8
            // Following in a line is not being held up: only wanting clearly more than the wheel allows is.
            if (wantV.isNaN()) {
                if (v[i] >= vLim - 0.05 && room < 1.5) { wantV = vLim; cap = p; held = true }
            } else if (wantV > vLim) { held = room < 1.5 && wantV > vLim + 0.6; wantV = vLim }
        }
        if (held && m != Mode.TT) {
            if (t - passAt[i] > 0.5) { passX[i] = freeLane(i); passAt[i] = t }
            passUntil[i] = t + 1.0
        }
        if (m == Mode.TT && (held || t < passUntil[i])) {
            if (held) passUntil[i] = t + 3.0
            lane = -TT_LANE
        }
        if (!wantV.isNaN()) {
            val req = RacePhysics.powerToReach(v[i], wantV, g, mass[i], cdaE, tau)
            p = req.coerceIn(0.0, cap)
            brake[i] = if (req < 0 && wantV < v[i] - 0.1) min(MAX_BRAKE, (v[i] - wantV) / TAU) else 0.0
        } else brake[i] = 0.0
        pw[i] = p
        xt[i] = if (m != Mode.TT && t < passUntil[i]) passX[i] else lane
        if (m == Mode.ATTACK || m == Mode.SPRINT) if (held) laneX[i] = passX[i]
        val sprinting = m == Mode.SPRINT
        cad[i] = RacePhysics.cadence(p, cpE, g, sprinting)
        standing[i] = sprinting || (m == Mode.ATTACK && p > cpE * 1.1) || (g > 9 && p > cpE * 0.95)
    }

    private fun startSprint(i: Int) {
        mode[i] = Mode.SPRINT
        pacer[i] = false
        laneX[i] = freeLane(i)
        if (newSprinter < 0) newSprinter = i
    }

    private fun controlUser() {
        val u = user
        if (!active(u)) { pw[u] = 0.0; brake[u] = 0.0; xt[u] = x[u]; if (mode[u] == Mode.WAIT) v[u] = 0.0; return }
        pw[u] = userPowerW
        brake[u] = 0.0
        val g = gradeAt(d[u])
        cad[u] = RacePhysics.cadence(userPowerW, cpEff(u), g, userPowerW > cp[u] * 2.2)
        standing[u] = userPowerW > cp[u] * 1.6 || (g > 9 && userPowerW > cp[u])
        if (t < manualUntil) { xt[u] = manualX; return }
        if (t < passUntil[u]) { xt[u] = passX[u]; return }
        val b = blocker[u]
        // Team time trial: in the line the user stays on the wheel; he comes through when the man ahead swings off.
        if (b >= 0 && teamTrial && team[b] == team[u]) { xt[u] = x[b]; return }
        if (b >= 0) {
            val room = d[b] - d[u] - MIN_D
            // Faster than the wheel in front: swing out into free road; slower or level: stay on it.
            if ((v[u] > v[b] + 0.3 && room < 2.5) || (room < 0.4 && userPowerW > pw[b] * 1.05 + 10)) {
                passX[u] = freeLane(u)
                passUntil[u] = t + 1.5
                xt[u] = passX[u]
            } else xt[u] = x[b]
            return
        }
        // Team time trial: the team rides one line down the middle; on the front the user takes his turn in it.
        if (teamTrial) { xt[u] = 0.0; return }
        // Nobody straight ahead: tuck in behind a nearby wheel going the same speed, for the draft.
        var best = -1
        var bestD = Double.MAX_VALUE
        var q = pos[u] - 1
        while (q >= 0) {
            val j = ord[q]
            q--
            val dd = d[j] - d[u]
            if (dd > 8.0) break
            if (!active(j) || dd < MIN_D) continue
            if (abs(x[j] - x[u]) < 1.3 && abs(v[j] - v[u]) < 1.0 && dd < bestD) { bestD = dd; best = j }
        }
        xt[u] = if (best >= 0) x[best] else x[u]
    }

    private fun moveLaterally(h: Double) {
        val k = min(1.0, 6 * h)
        for (i in 0 until n) {
            if (mode[i] == Mode.WAIT) continue
            val maxLat = if (i == user) 1.3 else 1.0
            val want = (1.6 * (xt[i] - x[i])).coerceIn(-maxLat, maxLat) + if (i == user) 0.0 else push[i]
            vx[i] = (vx[i] + (want - vx[i]) * k).coerceIn(-1.6, 1.6)
            x[i] += vx[i] * h
            if (x[i] > edge) { x[i] = edge; vx[i] = 0.0 } else if (x[i] < -edge) { x[i] = -edge; vx[i] = 0.0 }
        }
    }

    private fun integrate(h: Double) {
        for (i in 0 until n) if (i != user) advance(i, h, Double.MAX_VALUE)
        val u = user
        if (mode[u] == Mode.WAIT) return
        // The user cannot ride through a rider ahead in his lane: he is held at a bike length behind.
        var limit = Double.MAX_VALUE
        var limitV = 0.0
        if (mode[u] != Mode.DONE) {
            for (j in 0 until n) {
                if (j == u || !active(j) || d[j] <= d[u] || abs(x[j] - x[u]) >= MIN_X) continue
                val l = d[j] - MIN_D
                if (l < limit) { limit = l; limitV = v[j] }
            }
        }
        advance(u, h, limit, limitV)
    }

    private fun advance(i: Int, h: Double, limit: Double, limitV: Double = 0.0) {
        val m = mode[i]
        if (m == Mode.WAIT) return
        val before = d[i]
        val g = gradeAt(before)
        var nv = RacePhysics.step(v[i], pw[i], g, h, mass[i], cda0[i] * (1 - draft[i]), brake[i])
        var nd = before + nv * h
        if (nd > limit) {
            // Held behind the wheel: no further than a bike length behind it, and at its speed, never
            // slower (a rider who cuts in close must not bring the user to a stop).
            nd = max(before, limit)
            nv = min(nv, max(limitV, (nd - before) / h))
        }
        // By position, not only by crossing in this step: making room for the user can push a rider over the line.
        if (m != Mode.DONE && nd >= length) {
            finishAt[i] = t - h + max(0.0, length - before) / max(nv, 0.1)
            mode[i] = Mode.DONE
            pacer[i] = false
        }
        if (timeTrial && m != Mode.DONE) recordSplits(i, before, nd, nv, h)
        v[i] = nv
        d[i] = nd
        if (i != user) wbal[i] = RacePhysics.wBalance(wbal[i], pw[i], cpEff(i), wp[i], h)
        kj[i] += pw[i] * h / 1000
        crank[i] = (crank[i] + cad[i] / 60 * 2 * Math.PI * h) % (2 * Math.PI)
    }

    /**
     * Individual time trial, the hours nobody watches (before the user's start, after his finish): every AI
     * rider rides on alone from now to [until] with the coarse step [h], as [control] and [advance] ride
     * him in [Mode.TT] but without the riders around him (no wheel to pass, no lane change). Only the
     * times and splits count there, and one rider alone is a few sums a step instead of a whole field step.
     * Below [SOLO_SLOW_MPS] (the start, steep ramps) the speed changes too fast for it: there he rides
     * with the [fine] step, else he would gain about a second on the race. Riders who start before
     * [until] set off at their start; riders across the line roll out to a stop.
     */
    fun soloTimeTrial(until: Double, h: Double, fine: Double) {
        var end = t
        for (i in 0 until n) {
            if (i == user) continue
            var now = t
            if (mode[i] == Mode.WAIT) {
                if (startAt[i] >= until) continue
                now = max(t, startAt[i])
                mode[i] = Mode.TT
                v[i] = 0.0
            }
            x[i] = TT_LANE
            vx[i] = 0.0
            while (now < until - 1e-9) {
                val m = mode[i]
                if (m == Mode.DONE && v[i] <= 0.0) break
                val s = min(if (m != Mode.DONE && v[i] < SOLO_SLOW_MPS) fine else h, until - now)
                now += s
                val before = d[i]
                val g = gradeAt(before)
                val cpE = cpEff(i)
                var p = 0.0
                var br = if (v[i] > 6) 0.8 else 0.0
                if (m != Mode.DONE) {
                    val fresh = 1 - durab[i] * min(1.0, kj[i] / FATIGUE_KJ)
                    p = min(modePower[i] * (1 + (0.012 * g).coerceIn(-0.3, 0.07)), RacePhysics.maxPower(cpE, peak[i] * fresh, wbal[i], wp[i]))
                    br = 0.0
                }
                val nv = RacePhysics.step(v[i], p, g, s, mass[i], cda0[i], br)
                val nd = before + nv * s
                if (m != Mode.DONE) {
                    recordSplits(i, before, nd, nv, s, now)
                    if (nd >= length) {
                        finishAt[i] = now - s + max(0.0, length - before) / max(nv, 0.1)
                        mode[i] = Mode.DONE
                        pacer[i] = false
                    }
                }
                v[i] = nv
                d[i] = nd
                pw[i] = p
                brake[i] = br
                wbal[i] = RacePhysics.wBalance(wbal[i], p, cpE, wp[i], s)
                kj[i] += p * s / 1000
            }
            end = max(end, now)
        }
        // Who stopped earlier (rolled out, at a standstill) stays where he is: the field is at the latest time.
        t = end
    }

    /** Splits passed in a step of [h] that ends at [now]. */
    private fun recordSplits(i: Int, from: Double, to: Double, speed: Double, h: Double, now: Double = t) {
        var k = (max(from, 0.0) / SPLIT_M).toInt() + 1
        if (from < 0 && to >= 0) k = 0
        while (k < splitCount && k * SPLIT_M <= to) {
            if (k * SPLIT_M > from || (k == 0 && from < 0)) {
                val at = now - h + (k * SPLIT_M - from) / max(speed, 0.1)
                splits[i * splitCount + k] = (at - startAt[i]).toFloat()
            }
            k++
        }
    }

    /**
     * Separates riders whose bikes overlap: side by side they move apart sideways, one behind the other
     * the rear one drops back to a bike length. The user is never moved; the AI riders make room for him.
     */
    private fun resolve(): Int {
        var fixes = 0
        for (k in 1 until n) {
            val i = ord[k]
            if (!active(i)) continue
            var q = k - 1
            while (q >= 0) {
                val j = ord[q]
                q--
                val dd = d[j] - d[i]
                if (dd >= MIN_D) break
                if (!active(j)) continue
                val dx = x[i] - x[j]
                if (abs(dx) >= MIN_X) continue
                fixes++
                when {
                    i == user || j == user -> makeRoom(if (i == user) j else i)
                    // Side by side, or a small sideways step is less than dropping back: make room sideways.
                    (dd < SIDE_BY_SIDE_M || MIN_X - abs(dx) < (MIN_D - dd) * 0.5) && splitSideways(i, j, dx) -> {}
                    else -> { d[i] = d[j] - MIN_D; v[i] = min(v[i], v[j]) }
                }
            }
        }
        return fixes
    }

    private fun makeRoom(a: Int) {
        val u = user
        val dx = x[a] - x[u]
        val dir = if (dx > 0) 1.0 else if (dx < 0) -1.0 else if (x[u] <= 0) 1.0 else -1.0
        val target = x[u] + dir * (MIN_X + 0.02)
        if (abs(target) <= edge) { x[a] = target; return }
        val other = x[u] - dir * (MIN_X + 0.02)
        if (abs(other) <= edge && abs(d[a] - d[u]) >= SIDE_BY_SIDE_M) { x[a] = other; return }
        if (d[a] <= d[u]) { d[a] = d[u] - MIN_D; v[a] = min(v[a], v[u]) } else d[a] = d[u] + MIN_D
    }

    /** Side by side: both give way a little. False when the edge of the road leaves no room for it. */
    private fun splitSideways(i: Int, j: Int, dx: Double): Boolean {
        val need = MIN_X + 0.02 - abs(dx)
        val dir = if (dx > 0) 1.0 else if (dx < 0) -1.0 else if (i < j) 1.0 else -1.0
        var xi = x[i] + dir * need / 2
        var xj = x[j] - dir * need / 2
        if (abs(xi) > edge) { val over = abs(xi) - edge; xi -= dir * over; xj -= dir * over }
        if (abs(xj) > edge) { val over = abs(xj) - edge; xj += dir * over; xi += dir * over }
        xi = xi.coerceIn(-edge, edge)
        xj = xj.coerceIn(-edge, edge)
        if (abs(xi - xj) < MIN_X - 1e-6) return false
        x[i] = xi
        x[j] = xj
        return true
    }

    /** Average speed of a group's riders, for the HUD. */
    fun groupSpeed(g: Int): Double {
        var s = 0.0
        for (k in gOff[g] until gOff[g + 1]) s += v[gm[k]]
        return s / max(1, groupSize(g))
    }

    fun fillFrame(f: RiderFrame) {
        f.timeSec = t
        for (i in 0 until n) {
            f.distanceM[i] = d[i]
            f.lateralM[i] = x[i].toFloat()
            f.speedMps[i] = v[i].toFloat()
            f.lateralSpeedMps[i] = vx[i].toFloat()
            f.cadenceRpm[i] = cad[i].toFloat()
            f.crankRad[i] = crank[i].toFloat()
            f.leanRad[i] = (vx[i] * 0.12).coerceIn(-0.25, 0.25).toFloat()
            var fl = 0
            val m = mode[i]
            if (m != Mode.WAIT && !(m == Mode.DONE && d[i] > length + ROLL_OUT_VISIBLE_M)) fl = fl or RiderFrame.VISIBLE
            if (i == user) fl = fl or RiderFrame.USER
            if (standing[i]) fl = fl or RiderFrame.STANDING
            if (m == Mode.SPRINT) fl = fl or RiderFrame.SPRINTING
            if (cad[i] <= 0.0) fl = fl or RiderFrame.COASTING
            if (m == Mode.DONE) fl = fl or RiderFrame.FINISHED
            if (pacer[i] && m == Mode.PACK) fl = fl or RiderFrame.PULLING
            if (timeTrial) fl = fl or RiderFrame.TT_BIKE
            f.flags[i] = fl
        }
    }

    companion object {
        /** Centre to centre distance below which two bikes in one lane would touch. */
        const val RESOLVE_PASSES = 10
        const val MIN_D = 1.8
        /** Lateral distance below which two riders would touch (handlebars and shoulders). */
        const val MIN_X = 0.55
        const val SIDE_BY_SIDE_M = 0.9
        const val BLOCK_X = 0.6
        const val BLOCK_LOOK_M = 6.0
        const val SEP_D = 2.0
        const val SEP_X = 0.9
        const val SEP_GAIN = 2.5
        const val FREE_AHEAD_M = 8.0
        const val GROUP_GAP_M = 8.0
        const val TAU = 1.5
        /** Only riders this close to the front of their group open a sprint; further back it is lost anyway. */
        const val SPRINT_ZONE_M = 30.0
        const val FOLLOW_TAU = 2.5
        const val MAX_BRAKE = 4.0
        const val FATIGUE_KJ = 4500.0
        const val DRAFT_EVERY = 3L
        const val LANE_STEP = 0.9
        const val MANUAL_HOLD_SEC = 5.0
        const val TT_LANE = 1.0
        const val SPLIT_M = 250.0
        const val HEAD_BIN = 20.0
        const val ROLL_OUT_VISIBLE_M = 120.0
        const val SOLO_SLOW_MPS = 8.0

        fun roundKm(m: Double): Int = (m / 1000).roundToInt()
    }
}
