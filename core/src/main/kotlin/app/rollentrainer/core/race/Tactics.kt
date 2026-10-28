package app.rollentrainer.core.race

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * The race director: about once a second it reads the groups on the road and decides who rides on the
 * front and how hard, where everybody wants to be in the bunch, who attacks and who answers, and what
 * the HUD announces. The bodies then do it in [Peloton].
 */
internal class Tactics(private val f: Peloton, private val plan: RacePlan, private val rng: Random) {
    private val n = f.n
    private val riders = f.roster.riders
    val events = ArrayList<RaceEvent>()
    var phase = if (f.timeTrial) RacePhase.TIME_TRIAL else RacePhase.ROLL_OUT
        private set

    private val duty = IntArray(n)
    private val prefDepth = DoubleArray(n) { 0.2 + 0.7 * rng.nextDouble() }
    private val prefNext = DoubleArray(n) { 30 + 120 * rng.nextDouble() }
    private val restUntil = DoubleArray(n)
    private val turnEnd = DoubleArray(n)
    private val swingUntil = DoubleArray(n)
    /** Wants to be at the front for the finale and fights for the place (leaders, sprinters, their trains). */
    private val mover = BooleanArray(n)

    private var breakState = BREAK_NONE
    private var breakAtM = plan.breakAtM
    private var breakLaunchT = 0.0
    private var breakTries = 0
    /** Distance to go when the break was caught, for the HUD and the tests. */
    var caughtToGoM = Double.NaN
        private set

    private var ctrlI = 0.0
    private var lastGap = Double.NaN
    private var gapRate = 0.0
    private var nextGapReportM = 0.0
    private var markIndex = 0
    private val climbAnnounced = BooleanArray(plan.climbs.size)
    private var controlAnnounced = false
    private var leadoutAnnounced = false
    private var sprintAnnounced = false
    private var winnerAnnounced = false
    private var lateAttackDone = false
    private var lastAttackT = -1e9
    private var lastUserAttackT = -1e9
    private var attackCount = 0
    private var selectionShown = Int.MAX_VALUE
    private var userAttacking = false
    private var userFrontSince = Double.NaN
    private val droppedShown = BooleanArray(n)
    private var userMates = IntArray(0)
    private var userEventT = -1e9
    private var mainLane = 0.0
    private var mainLaneNext = 120.0
    private var bestTime = Double.MAX_VALUE
    private var splitMarks: DoubleArray = DoubleArray(0)
    private var splitIndex = 0

    private var main = 0
    private val gapOf = DoubleArray(n + 1)
    private val scratch = IntArray(n)
    private val keys = DoubleArray(n)
    private val strengthRank = IntArray(n)

    init {
        for (t in plan.leaderOf.indices) {
            val l = plan.leaderOf[t]
            if (l >= 0 && l != f.user) { duty[l] = DUTY_LEADER; f.reserve[l] = 0.15 }
            val s = plan.sprinterOf[t]
            if (s >= 0 && s != f.user && riders[s].role == RiderRole.SPRINTER) { duty[s] = DUTY_SPRINTER; f.reserve[s] = 0.2 }
        }
        for (i in 0 until n) f.launch[i] = plan.launchM[i]
        val byStrength = (0 until n).sortedByDescending { f.strength[it] }
        byStrength.forEachIndexed { rank, i -> strengthRank[i] = rank }
        val marks = mutableListOf(f.length / 3, f.length * 2 / 3)
        f.route.landmarks.filter { it.label.contains("Zwischenzeit") && it.distanceM in 500.0..(f.length - 500) }.forEach { marks += it.distanceM }
        splitMarks = marks.distinct().sorted().toDoubleArray()
    }

    /** A break that existed before a finale cut starts: the riders, already up the road. */
    fun placeBreak(): IntArray {
        if (!plan.hasBreak || plan.breakAtM > 0) return IntArray(0)
        val gap = plan.gapTargetSec(f.length)
        if (gap < 15) {
            // Gone long enough to have its full lead and still this close: the bunch has (nearly) caught it
            // before the cut starts, so no new break goes. Only a break planned just before the cut is let go now.
            val ramp = (-plan.breakAtM / RacePlan.RAMP_M).coerceIn(0.0, 1.0) * plan.maxGapSec
            if (ramp >= 15) {
                breakState = BREAK_CAUGHT
                caughtToGoM = f.length
            }
            return IntArray(0)
        }
        val chosen = plan.breakRiders.filter { it != f.user }.toIntArray()
        for (i in chosen) duty[i] = DUTY_BREAK
        breakState = BREAK_UP
        nextGapReportM = 5_000.0
        return chosen
    }

    fun decide() {
        if (f.timeTrial) { decideTimeTrial(); checkFinish(); return }
        if (f.groupCount == 0) { checkFinish(); return }
        main = mainGroup()
        for (g in 0 until f.groupCount) gapOf[g] = f.gapAt(f.d[f.groupFront(g)])
        val front = f.d[f.groupFront(main)]
        val toGo = f.length - front
        updateBreak(front, toGo)
        updateModes()
        finaleTactics(toGo)
        userAttack(toGo)
        updatePhase(front, toGo)
        for (g in 0 until f.groupCount) organise(g, front, toGo)
        walkPreferences(front, toGo)
        announce(front, toGo)
        userEvents()
        checkFinish()
    }

    // ---- groups ----

    /** The peloton: the front-most big group (a bunch that has split in two is led by its front half). */
    private fun mainGroup(): Int {
        var largest = 0
        for (g in 1 until f.groupCount) if (f.groupSize(g) > f.groupSize(largest)) largest = g
        val need = max(15, (f.groupSize(largest) * 0.4).toInt())
        for (g in 0 until largest) if (f.groupSize(g) >= need) return g
        return largest
    }

    private fun countDuty(g: Int, what: Int): Int {
        var c = 0
        for (k in f.gOff[g] until f.gOff[g + 1]) if (duty[f.gm[k]] == what) c++
        return c
    }

    private fun breakGroup(): Int {
        var best = -1
        var most = 0
        for (g in 0 until main) {
            val c = countDuty(g, DUTY_BREAK)
            if (c > most) { most = c; best = g }
        }
        return best
    }

    // ---- the break ----

    private fun updateBreak(front: Double, toGo: Double) {
        if (!plan.hasBreak) return
        when (breakState) {
            BREAK_NONE -> {
                // Who wants to go in the break first moves up to the front of the bunch.
                if (front >= breakAtM - 2_500) for (i in plan.breakRiders) if (i != f.user && f.mode[i] == Mode.PACK) {
                    prefDepth[i] = 0.0
                    prefNext[i] = f.t + 900
                }
                val u = f.user
                val userAhead = f.active(u) && f.grp[u] in 0 until main && f.t > ROLL_OUT_SEC && f.v[u] > 6 &&
                    gapOf[main] - f.gapAt(f.d[u]) > 5
                if (f.t > ROLL_OUT_SEC && (front >= breakAtM || userAhead)) launchBreak(if (userAhead) f.user else -1)
            }
            BREAK_LAUNCHED -> {
                val g = breakGroup()
                if (g >= 0 && gapOf[main] - gapOf[g] >= 15) {
                    breakState = BREAK_UP
                    nextGapReportM = front + 10_000
                    announceBreak(g)
                } else if (f.t - breakLaunchT > 420) {
                    breakTries++
                    for (i in 0 until n) if (duty[i] == DUTY_BREAK) duty[i] = DUTY_NONE
                    breakState = if (breakTries >= 3) BREAK_GIVEN_UP else BREAK_NONE
                    breakAtM = front + 2_000 + 4_000 * rng.nextDouble()
                }
            }
            BREAK_UP -> if (main == 0 && !winnerAnnounced) {
                breakState = BREAK_CAUGHT
                caughtToGoM = toGo
                for (i in 0 until n) if (duty[i] == DUTY_BREAK) { duty[i] = DUTY_NONE; f.reserve[i] = 0.3 }
                emit(RaceEventKind.CATCH, front, "Das Feld ist ${RaceText.km(toGo / 1000)} vor dem Ziel wieder zusammen")
            }
        }
    }

    private fun launchBreak(bridgeTo: Int) {
        val chosen = plan.breakRiders.filter { f.active(it) && f.mode[it] == Mode.PACK }
        if (chosen.isEmpty()) { breakState = BREAK_GIVEN_UP; return }
        breakState = BREAK_LAUNCHED
        breakLaunchT = f.t
        val first = if (bridgeTo >= 0) bridgeTo else chosen.maxBy { f.d[it] }
        for (i in chosen) {
            duty[i] = DUTY_BREAK
            f.reserve[i] = 0.05
            f.pacer[i] = false
            if (i == first) {
                f.mode[i] = Mode.ATTACK
                f.modePower[i] = 1.25 * f.cpEff(i)
                f.modeUntil[i] = f.t + 50 + 30 * rng.nextDouble()
                f.laneX[i] = f.freeLane(i)
            } else {
                f.mode[i] = Mode.CHASE
                f.anchor[i] = first
                f.modeUntil[i] = f.t + 180
            }
        }
        val who = if (first == f.user) "Du attackierst – ${chosen.size} Fahrer folgen" else "Attacke von ${name(first)}!"
        emit(RaceEventKind.ATTACK, f.d[first], who, listOf(first))
    }

    private fun announceBreak(g: Int) {
        val ids = (f.gOff[g] until f.gOff[g + 1]).map { f.gm[it] }
        val names = ids.map { if (it == f.user) "du" else riders[it].shortName }
        val text = if (ids.size <= 4) "Ausreißergruppe: ${names.joinToString(", ")}" else "Ausreißergruppe mit ${ids.size} Fahrern, u. a. ${names.take(3).joinToString(", ")}"
        emit(RaceEventKind.BREAKAWAY, f.d[ids[0]], text, ids)
    }

    /** Gap control: the bunch rides so that the break's lead follows the plan. */
    private fun gapControl(gap: Double, toGo: Double, speed: Double): Double {
        val target = plan.gapTargetSec(toGo)
        val slope = plan.gapTargetSec(toGo - speed) - target
        if (lastGap.isNaN()) lastGap = gap
        gapRate = 0.85 * gapRate + 0.15 * (gap - lastGap)
        lastGap = gap
        // Far out a gap may drift back to the plan over ten minutes; near the planned catch it is closed for real.
        val tc = if (plan.catchToGoM >= 0 && toGo < plan.catchToGoM + 5_000) 200.0 else 600.0
        val desired = (slope - (gap - target) / tc).coerceIn(-0.12, 0.08)
        val e = gapRate - desired
        ctrlI = (ctrlI + e).coerceIn(-12.0, 12.0)
        // A real chase is short hard turns above threshold, not a steady tempo.
        return (0.8 + 3.0 * e + 0.03 * ctrlI).coerceIn(0.5, 1.15)
    }

    // ---- modes and attacks ----

    private fun updateModes() {
        for (i in 0 until n) {
            if (i == f.user) continue
            when (f.mode[i]) {
                Mode.ATTACK -> if (f.t >= f.modeUntil[i]) f.mode[i] = Mode.PACK
                Mode.CHASE -> {
                    val a = f.anchor[i]
                    // On his wheel, not merely in the same group: an attacker from inside the bunch is still in it.
                    val caught = a >= 0 && f.active(a) && f.grp[a] == f.grp[i] && abs(f.d[a] - f.d[i]) < 8.0
                    val gone = a < 0 || !f.active(a) || f.t >= f.modeUntil[i] ||
                        (f.wbal[i] < 0.04 * f.wp[i] && f.d[a] - f.d[i] > 25)
                    if (caught || gone) { f.mode[i] = Mode.PACK; f.anchor[i] = -1 }
                }
            }
        }
    }

    private fun finaleTactics(toGo: Double) {
        // Once the stage is won the groups behind just ride in; a sprint for the minor places is still on.
        if (!winnerAnnounced) when (plan.finale) {
            FinaleKind.SUMMIT -> climbAttacks(plan.decisiveClimb, 1.2, false)
            FinaleKind.HILL -> climbAttacks(plan.decisiveClimb, 1.15, true)
            FinaleKind.PUNCH -> punchAttacks()
            FinaleKind.SPRINT -> lateAttack(toGo)
        }
        // In the last two kilometres everybody in a small group, and the fast men in the bunch, sprint for the line.
        for (g in 0 until f.groupCount) {
            val small = f.groupSize(g) <= 15
            if (f.length - f.d[f.groupFront(g)] > 2_000) continue
            for (k in f.gOff[g] until f.gOff[g + 1]) {
                val i = f.gm[k]
                if (i != f.user) f.sprintOk[i] = riders[i].sprints || small
            }
        }
    }

    private fun climbAttacks(c: ProfileClimb?, power: Double, punchers: Boolean) {
        if (c == null || f.t - lastAttackT < 20) return
        for (g in 0 until f.groupCount) {
            if (f.groupSize(g) < 2) continue
            val fd = f.d[f.groupFront(g)]
            if (fd < c.startM || fd > c.topM || c.topM - fd > plan.attackToTopM) continue
            var maxS = 0.0
            for (k in f.gOff[g] until f.gOff[g + 1]) { val i = f.gm[k]; if (f.mode[i] == Mode.PACK) maxS = max(maxS, f.strength[i]) }
            for (k in f.gOff[g] until f.gOff[g + 1]) {
                val i = f.gm[k]
                if (i == f.user || f.mode[i] != Mode.PACK || f.wbal[i] < 0.55 * f.wp[i]) continue
                val role = riders[i].role
                if (role != RiderRole.GC && role != RiderRole.CLIMBER && !(punchers && (role == RiderRole.PUNCHEUR || riders[i].sprints))) continue
                val rel = f.strength[i] / maxS
                if (rel < 0.94) continue
                val p = 0.035 * rel.pow(8) * (if (attackCount == 0) 1.5 else 1.0)
                if (rng.nextDouble() < p) {
                    attack(i, g, power * f.cpEff(i), 20 + 15 * rng.nextDouble())
                    return
                }
            }
        }
    }

    private fun punchAttacks() {
        val c = plan.decisiveClimb
        if (f.t - lastAttackT < 5) return
        for (g in 0 until f.groupCount) {
            if (f.groupSize(g) < 2) continue
            val fd = f.d[f.groupFront(g)]
            val top = c?.topM ?: f.length
            if (fd > top || top - fd > plan.attackToTopM) continue
            for (k in f.gOff[g] until f.gOff[g + 1]) {
                val i = f.gm[k]
                if (i == f.user || f.mode[i] != Mode.PACK || f.wbal[i] < 0.5 * f.wp[i]) continue
                val r = riders[i]
                if (r.role != RiderRole.PUNCHEUR && r.role != RiderRole.GC && !r.sprints) continue
                if (rng.nextDouble() < 0.15) {
                    val fresh = 1 - f.durab[i] * min(1.0, f.kj[i] / Peloton.FATIGUE_KJ)
                    attack(i, g, f.p1m[i] * fresh, ((top - fd) / 7.0).coerceIn(15.0, 70.0))
                    return
                }
            }
        }
    }

    private fun lateAttack(toGo: Double) {
        if (lateAttackDone || plan.lateAttackToGoM < 0 || toGo > plan.lateAttackToGoM || main != 0) return
        lateAttackDone = true
        val cands = (f.gOff[main] until f.gOff[main + 1]).map { f.gm[it] }.filter { i ->
            i != f.user && f.mode[i] == Mode.PACK && f.wbal[i] > 0.7 * f.wp[i] && f.team[i] !in plan.finaleTeams &&
                riders[i].role.let { it == RiderRole.ROULEUR || it == RiderRole.PUNCHEUR || it == RiderRole.TT }
        }
        if (cands.isEmpty()) return
        val a = cands[rng.nextInt(cands.size)]
        attack(a, main, 1.2 * f.cpEff(a), 40.0)
    }

    private fun attack(i: Int, g: Int, power: Double, seconds: Double) {
        f.mode[i] = Mode.ATTACK
        f.modePower[i] = power
        f.modeUntil[i] = f.t + seconds
        f.pacer[i] = false
        f.laneX[i] = f.freeLane(i)
        f.reserve[i] = 0.0
        lastAttackT = f.t
        attackCount++
        emit(RaceEventKind.ATTACK, f.d[i], "Attacke von ${name(i)}!", listOf(i))
        answer(i, g)
    }

    /** Rivals who think they are as strong jump on the wheel; the rest ride their own pace. */
    private fun answer(attacker: Int, g: Int) {
        if (g < 0 || g >= f.groupCount) return
        for (k in f.gOff[g] until f.gOff[g + 1]) {
            val j = f.gm[k]
            if (j == f.user || j == attacker || f.mode[j] != Mode.PACK || f.wbal[j] < 0.35 * f.wp[j]) continue
            val role = riders[j].role
            val rival = role == RiderRole.GC || role == RiderRole.CLIMBER || role == RiderRole.PUNCHEUR || duty[j] == DUTY_LEADER
            if (rival && f.strength[j] >= 0.965 * f.strength[attacker] && rng.nextDouble() < 0.8) {
                f.mode[j] = Mode.CHASE
                f.anchor[j] = attacker
                f.modeUntil[j] = f.t + 45
                f.pacer[j] = false
                f.reserve[j] = 0.0
            }
        }
    }

    /** The user jumping away from his group in a finale: the AI rivals answer as they would to anybody. */
    private fun userAttack(toGo: Double) {
        val u = f.user
        if (!f.active(u) || f.t - lastUserAttackT < 30) return
        val dec = plan.decisiveClimb
        val decisive = toGo < 25_000 || (dec != null && f.d[u] >= dec.startM - 2_000)
        if (!decisive) return
        val g = f.grp[u]
        if (g < 0) return
        // One announcement per attack: the user is "attacking" until he is back inside a group.
        if (userAttacking) {
            if (f.groupSize(g) > 1 && f.groupFront(g) != u) userAttacking = false
            return
        }
        val behind: Int
        if (f.groupSize(g) == 1) {
            behind = g + 1
            if (behind >= f.groupCount || gapOf[behind] - f.gapAt(f.d[u]) > 8) return
            if (f.v[u] < f.groupSpeed(behind) + 0.8) return
        } else {
            if (f.groupFront(g) != u) return
            val second = f.gm[f.gOff[g] + 1]
            if (f.d[u] - f.d[second] < 3 || f.v[u] - f.v[second] < 1.0) return
            behind = g
        }
        lastUserAttackT = f.t
        userAttacking = true
        emit(RaceEventKind.USER, f.d[u], "Du attackierst!", listOf(u))
        answer(u, behind)
    }

    private fun updatePhase(front: Double, toGo: Double) {
        if (phase == RacePhase.FINISHED) return
        val dec = plan.decisiveClimb
        phase = when {
            phase == RacePhase.SPRINT || f.newSprinter >= 0 -> RacePhase.SPRINT
            f.t < ROLL_OUT_SEC -> RacePhase.ROLL_OUT
            plan.hasBreak && (breakState == BREAK_NONE || breakState == BREAK_LAUNCHED) -> RacePhase.BREAK_FORMING
            breakState == BREAK_UP && main > 0 && toGo > 12_000 -> if (toGo <= plan.chaseFromToGoM) RacePhase.CHASE else RacePhase.CONTROL
            toGo < 12_000 || (dec != null && front >= dec.startM) -> RacePhase.FINALE
            else -> RacePhase.CONTROL
        }
    }

    // ---- who rides on the front, how hard, and the shape of each group ----

    private fun organise(g: Int, front: Double, toGo: Double) {
        val gFront = f.d[f.groupFront(g)]
        val gToGo = f.length - gFront
        val dec = plan.decisiveClimb
        val onDecisive = dec != null && gFront >= dec.startM && gFront <= dec.topM && plan.finale != FinaleKind.SPRINT
        var helpers: IntArray? = null
        var turn = 40.0
        var sitUp = false
        val factor: Double
        if (g == main) {
            val ahead = g > 0
            val gap = gapOf[g]
            val sprintFinale = plan.finale == FinaleKind.SPRINT && gToGo < 12_000 && (!ahead || gap < 25)
            val climb = plan.climbs.firstOrNull { gFront >= it.startM && gFront <= it.topM }
            helpers = plan.controlTeams
            var fa = when {
                f.t < ROLL_OUT_SEC -> 0.35 + 0.3 * f.t / ROLL_OUT_SEC
                sprintFinale -> {
                    helpers = plan.finaleTeams
                    // Somebody still clear: the trains ride for the sprint and close the gap on the way.
                    if (ahead && breakState == BREAK_UP) max(sprintFactor(gToGo), gapControl(gap - gapOf[breakGroup().let { if (it >= 0) it else 0 }], gToGo, f.groupSpeed(g)))
                    else sprintFactor(gToGo)
                }
                onDecisive -> { helpers = plan.finaleTeams; if (plan.finale == FinaleKind.PUNCH) 1.0 else 0.93 }
                breakState == BREAK_UP && ahead -> gapControl(gap - gapOf[breakGroup().let { if (it >= 0) it else 0 }], gToGo, f.groupSpeed(g))
                plan.hasBreak && breakState == BREAK_NONE -> 0.75
                plan.hasBreak && breakState == BREAK_LAUNCHED -> if (ahead && aheadTooBig()) 0.92 else 0.62
                ahead -> if (gToGo < 40_000) 1.0 else 0.8
                dec != null && gFront > dec.topM && plan.finale != FinaleKind.SPRINT -> 0.9
                else -> 0.78 + if (gToGo < 30_000) 0.06 else 0.0
            }
            // Climbs are ridden at tempo, except while the break goes clear or is being let go: then the gap decides.
            val breakFree = plan.hasBreak && (breakState == BREAK_NONE || breakState == BREAK_LAUNCHED ||
                (breakState == BREAK_UP && ahead && gToGo > plan.chaseFromToGoM))
            if (climb != null && !onDecisive && f.t >= ROLL_OUT_SEC && !breakFree) {
                fa = max(fa, if (climb.isMajor && plan.style == StageStyle.MOUNTAIN) 0.88 else 0.78)
            }
            if (dec != null && !onDecisive && gFront < dec.startM && dec.startM - gFront < 15_000 && plan.finale != FinaleKind.SPRINT) {
                helpers = plan.finaleTeams
                fa = max(fa, 0.85)
            }
            factor = fa
            turn = when {
                sprintFinale && gToGo < 3_000 -> 20.0
                sprintFinale -> 35.0
                onDecisive -> 900.0
                factor >= 0.9 -> 45.0
                else -> 90.0 + 60 * rng.nextDouble()
            }
            sitUp = (sprintFinale && gToGo < 4_000) || onDecisive
        } else if (g < main) {
            val breakers = countDuty(g, DUTY_BREAK) > 0
            val major = plan.climbs.any { gFront >= it.startM && gFront <= it.topM && it.isMajor }
            // Far from the finish, off the front by accident (the start, a gap in the bunch): wait to be caught.
            val racing = breakers || gToGo < 40_000 || major || f.t < f.modeUntil[f.groupFront(g)]
            factor = when {
                !racing -> 0.5
                f.groupSize(g) == 1 -> soloFactor(f.groupFront(g), gToGo)
                breakers -> 0.88
                else -> 0.95
            }
            turn = if (countDuty(g, DUTY_BREAK) > 0) 30.0 else 25.0
        } else {
            val toMain = gapOf[g] - gapOf[main]
            val leader = (f.gOff[g] until f.gOff[g + 1]).any { duty[f.gm[it]] == DUTY_LEADER && strengthRank[f.gm[it]] < 30 }
            val climbing = plan.climbs.any { gFront >= it.startM && gFront <= it.topM }
            factor = when {
                leader && toGo < 40_000 -> if (f.groupSize(g) == 1) soloFactor(f.groupFront(g), gToGo) else 0.97
                toMain < 40 && gToGo > 5_000 && !(plan.style == StageStyle.MOUNTAIN && climbing) -> 0.95
                else -> 0.8
            }
        }
        val p = choosePacer(g, helpers, factor, turn, sitUp)
        if (p >= 0) formation(g, p, factor, gToGo)
    }

    private fun aheadTooBig(): Boolean {
        var count = 0
        for (g in 0 until main) count += f.groupSize(g)
        return count > plan.breakRiders.size + 3
    }

    private fun sprintFactor(toGo: Double): Double = when {
        toGo > 3_000 -> 0.82 + (12_000 - toGo) / 9_000 * 0.18
        toGo > 1_000 -> 1.12
        else -> 1.2
    }

    /** Alone ahead: a time trial to the line, from the power curve and what is left of W'. */
    private fun soloFactor(i: Int, toGo: Double): Double {
        val cpE = f.cpEff(i)
        val sec = toGo / max(f.v[i], 4.0)
        return (RacePhysics.powerForDuration(cpE, f.wbal[i], sec) / cpE).coerceIn(0.75, 1.15)
    }

    private fun isHelper(i: Int, helpers: IntArray?): Boolean {
        if (i == f.user || f.mode[i] != Mode.PACK || f.t < restUntil[i]) return false
        if (helpers == null) return true
        return f.team[i] in helpers && duty[i] != DUTY_LEADER && duty[i] != DUTY_SPRINTER
    }

    private fun choosePacer(g: Int, helpers: IntArray?, factor: Double, turn: Double, sitUp: Boolean): Int {
        var cur = -1
        for (k in f.gOff[g] until f.gOff[g + 1]) {
            val i = f.gm[k]
            if (!f.pacer[i]) continue
            if (cur < 0 && f.mode[i] == Mode.PACK && i != f.user) cur = i else f.pacer[i] = false
        }
        if (cur >= 0) {
            val tired = f.wbal[cur] < 0.12 * f.wp[cur]
            val ok = f.t < turnEnd[cur] && !tired && !f.boxedIn(cur) && (helpers == null || (f.team[cur] in helpers && duty[cur] != DUTY_LEADER && duty[cur] != DUTY_SPRINTER) || !anyHelperNear(g, helpers))
            if (!ok) {
                f.pacer[cur] = false
                f.paceFactor[cur] = 0.8
                restUntil[cur] = f.t + 25
                prefDepth[cur] = 0.2 + 0.2 * rng.nextDouble()
                prefNext[cur] = f.t + 40
                if (tired && sitUp && duty[cur] == DUTY_NONE) { f.mode[cur] = Mode.SITUP; f.reserve[cur] = 1.0 }
                cur = -1
            }
        }
        if (cur < 0) cur = pickPacer(g, helpers)
        if (cur < 0) return -1
        if (!f.pacer[cur]) {
            f.pacer[cur] = true
            turnEnd[cur] = f.t + turn * (0.8 + 0.4 * rng.nextDouble())
            if (g == main && helpers != null && !controlAnnounced && breakState == BREAK_UP && factor > 0.7 && f.team[cur] in helpers) {
                controlAnnounced = true
                emit(RaceEventKind.CONTROL, f.d[cur], "${riders[cur].team.name} kontrolliert das Feld")
            }
        }
        f.paceFactor[cur] = factor
        if (g == main) {
            if (f.t >= mainLaneNext) { mainLane = (rng.nextDouble() * 2 - 1) * f.edge * 0.4; mainLaneNext = f.t + 90 + 120 * rng.nextDouble() }
            f.paceLane[cur] = mainLane
        } else f.paceLane[cur] = f.x[cur].coerceIn(-f.edge + 0.5, f.edge - 0.5)
        return cur
    }

    private fun anyHelperNear(g: Int, helpers: IntArray): Boolean {
        val front = f.d[f.groupFront(g)]
        for (k in f.gOff[g] until f.gOff[g + 1]) {
            val i = f.gm[k]
            if (front - f.d[i] < PACER_ZONE_M && isHelper(i, helpers)) return true
        }
        return false
    }

    private fun pickPacer(g: Int, helpers: IntArray?): Int {
        // A helper near the front takes over (the team moves up first, their places are at the front);
        // until one is there, a willing rider at the front, then anybody.
        val front = f.d[f.groupFront(g)]
        for (pass in 0..3) {
            for (k in f.gOff[g] until f.gOff[g + 1]) {
                val i = f.gm[k]
                val near = front - f.d[i] < PACER_ZONE_M && !f.boxedIn(i)
                val ok = when (pass) {
                    0 -> near && isHelper(i, helpers)
                    1 -> near && isHelper(i, null) && duty[i] != DUTY_LEADER && duty[i] != DUTY_SPRINTER
                    2 -> near && i != f.user && f.mode[i] == Mode.PACK
                    else -> i != f.user && f.mode[i] == Mode.PACK
                }
                if (ok) return i
            }
        }
        return -1
    }

    /**
     * The shape of a group behind its pacer: rows that widen from the pacer's wheel to the width of the
     * road when the pace is easy, a long line two or three abreast when it is hard. Riders are put into
     * the rows by where they are now, pulled a few metres towards where they want to be, so they move up
     * and down gradually.
     */
    private fun formation(g: Int, p: Int, factor: Double, toGo: Double) {
        f.slotRef[p] = p
        f.slotBack[p] = 0.0
        var all = 0
        for (k in f.gOff[g] until f.gOff[g + 1]) {
            val i = f.gm[k]
            if (i != p && i != f.user && f.mode[i] == Mode.PACK) all++
        }
        if (all == 0) return
        val grade = f.gradeAt(f.d[p])
        var tension = ((factor - 0.62) / 0.4).coerceIn(0.0, 1.0)
        if (grade > 4) tension = max(tension, 0.6)
        if (plan.finale == FinaleKind.SPRINT && toGo < 3_000) tension = 1.0
        // One lane stays free at the side of an easy bunch: that is where riders move up and attack.
        val wFree = (f.roadWidth / 0.95).toInt() - 1
        val wMax = if (f.teamTrial) 1 else max(1, lerp(wFree.toDouble(), 2.0, tension).roundToInt())
        val spread = lerp(1.0, 0.3, tension)
        val rowSp = lerp(2.2, 1.95, tension)
        val estDepth = all.toDouble() / wMax * rowSp
        // Riders who must get to the front for the finale move up along the free lane outside the bunch.
        val side = if (f.paceLane[p] <= 0) 1.0 else -1.0
        val outsideX = (f.paceLane[p] + side * ((wMax - 1) / 2.0 * 0.95 + 0.95)).coerceIn(-f.edge, f.edge)
        var count = 0
        for (k in f.gOff[g] until f.gOff[g + 1]) {
            val i = f.gm[k]
            if (i == p || i == f.user || f.mode[i] != Mode.PACK) continue
            val depth = f.d[p] - f.d[i]
            val off = depth - prefDepth[i] * estDepth
            if (mover[i] && off > 8 && wMax > 1) {
                f.slotRef[i] = p
                f.slotBack[i] = max(2.0, depth - 5)
                f.slotX[i] = outsideX
                continue
            }
            // Most riders keep their place; one far from where he wants to be gains or loses a little ground.
            keys[count] = if (abs(off) < 6) depth else depth - off.coerceIn(-1.5, 1.5)
            scratch[count++] = i
        }
        if (count == 0) return
        sortByKeys(count)
        var row = 1
        var c = 0
        val colSp = 0.95
        while (c < count) {
            val cap = rowCap(row, wMax, spread)
            val rowWidth = min(cap, count - c)
            // Within a row the riders keep their order across the road, so nobody has to cross a wheel.
            sortRowByLane(c, rowWidth)
            val half = (rowWidth - 1) / 2.0 * colSp
            val centre = f.paceLane[p].coerceIn(-f.edge + half, f.edge - half)
            val stagger = if (row % 2 == 0) 0.08 else -0.08
            for (k in 0 until rowWidth) {
                val i = scratch[c + k]
                f.slotRef[i] = p
                f.slotBack[i] = row * rowSp
                f.slotX[i] = (centre + (k - (rowWidth - 1) / 2.0) * colSp + stagger).coerceIn(-f.edge, f.edge)
            }
            c += rowWidth
            row++
        }
        if (plan.finale == FinaleKind.SPRINT && toGo < 4_000 && wMax > 1) trains(g, p, outsideX)
    }

    /** In the run-in each sprinter fights for the wheel of the last team-mate near the front of the bunch. */
    private fun trains(g: Int, p: Int, outsideX: Double) {
        for (t in plan.finaleTeams) {
            val s = plan.sprinterOf[t]
            if (s < 0 || s == f.user || f.grp[s] != g || f.mode[s] != Mode.PACK) continue
            var last = -1
            for (k in f.gOff[g] until f.gOff[g + 1]) {
                val i = f.gm[k]
                if (i == s || i == f.user || f.team[i] != t || f.mode[i] != Mode.PACK) continue
                val depth = f.d[p] - f.d[i]
                if (depth <= TRAIN_ZONE_M && (last < 0 || f.d[i] < f.d[last])) last = i
            }
            if (last < 0 || f.d[s] > f.d[last]) continue
            val target = f.d[p] - f.d[last] + Drafting.BIKE_LENGTH_M + 0.15
            f.slotRef[s] = p
            f.slotBack[s] = max(target, f.d[p] - f.d[s] - 5)
            f.slotX[s] = if (f.d[last] - f.d[s] < 6) f.x[last] else outsideX
        }
    }

    private fun sortRowByLane(from: Int, len: Int) {
        for (a in from + 1 until from + len) {
            val vi = scratch[a]
            var b = a - 1
            while (b >= from && f.x[scratch[b]] > f.x[vi]) { scratch[b + 1] = scratch[b]; b-- }
            scratch[b + 1] = vi
        }
    }

    private fun rowCap(row: Int, wMax: Int, spread: Double): Int = min(wMax, 1 + (row * spread).toInt())

    private fun sortByKeys(count: Int) {
        for (a in 1 until count) {
            val ki = keys[a]
            val vi = scratch[a]
            var b = a - 1
            while (b >= 0 && keys[b] > ki) { keys[b + 1] = keys[b]; scratch[b + 1] = scratch[b]; b-- }
            keys[b + 1] = ki
            scratch[b + 1] = vi
        }
    }

    private fun walkPreferences(front: Double, toGo: Double) {
        val dec = plan.decisiveClimb
        val finale = toGo < 12_000 || (dec != null && dec.startM - front < 15_000 && plan.finale != FinaleKind.SPRINT)
        val teams = if (finale) plan.finaleTeams else plan.controlTeams
        for (i in 0 until n) {
            mover[i] = finale && (duty[i] == DUTY_LEADER || duty[i] == DUTY_SPRINTER || (f.team[i] in teams && duty[i] == DUTY_NONE))
            if (i == f.user || f.mode[i] != Mode.PACK || f.t < prefNext[i]) continue
            prefDepth[i] = when {
                // Favourites stay near the front, safe from crashes; in the last 3 km of a sprint stage they leave it to the trains.
                duty[i] == DUTY_LEADER && plan.finale == FinaleKind.SPRINT && toGo < 3_500 -> 0.25 + 0.3 * rng.nextDouble()
                duty[i] == DUTY_LEADER -> if (finale) 0.03 + 0.09 * rng.nextDouble() else 0.06 + 0.16 * rng.nextDouble()
                duty[i] == DUTY_SPRINTER -> if (toGo < 10_000) 0.02 + 0.08 * rng.nextDouble() else 0.3 + 0.4 * rng.nextDouble()
                f.team[i] in teams && duty[i] == DUTY_NONE -> 0.06 * rng.nextDouble()
                else -> 0.12 + 0.83 * rng.nextDouble()
            }
            prefNext[i] = f.t + if (finale) 15 + 15 * rng.nextDouble() else 60 + 180 * rng.nextDouble()
            if (duty[i] == DUTY_LEADER) f.reserve[i] = if (finale) 0.0 else 0.15
            if (duty[i] == DUTY_SPRINTER) f.reserve[i] = if (toGo < 15_000) 0.0 else 0.2
        }
    }

    // ---- announcements ----

    private fun announce(front: Double, toGo: Double) {
        // After the winner is in, "the front" is just the next group home: no more race radio.
        if (winnerAnnounced) return
        while (markIndex < KM_MARKS.size && toGo <= KM_MARKS[markIndex]) {
            val m = KM_MARKS[markIndex++]
            if (m >= f.length - 1_000) continue
            emit(RaceEventKind.KM, front, if (m <= 1_000) "Flamme rouge – noch 1 km" else "Noch ${RaceText.km(m / 1000)}")
        }
        plan.climbs.forEachIndexed { k, c ->
            if (!climbAnnounced[k] && front >= c.startM && c.score >= 6_000) {
                climbAnnounced[k] = true
                val what = "${RaceText.km(c.lengthM / 1000)} mit ${"%.1f".format(c.avgPct).replace('.', ',')} %"
                emit(RaceEventKind.CLIMB, front, if (c.name.isNotBlank()) "Anstieg: ${c.name} – $what" else "Anstieg: $what")
            }
        }
        if (breakState == BREAK_UP && main > 0 && front >= nextGapReportM) {
            nextGapReportM = front + 10_000
            val gap = gapOf[main] - gapOf[0]
            if (gap >= 15) {
                val who = if (countDuty(0, DUTY_BREAK) > 0) "Ausreißergruppe" else "Spitzengruppe"
                emit(RaceEventKind.GAP, front, "$who hat ${RaceText.clock(gap)} Vorsprung")
            }
        }
        if (plan.finale == FinaleKind.SPRINT && !leadoutAnnounced && toGo < 5_000 && main == 0) {
            leadoutAnnounced = true
            emit(RaceEventKind.LEADOUT, front, "Die Sprintzüge formieren sich")
        }
        val s = f.newSprinter
        if (s >= 0) {
            f.newSprinter = -1
            val g = f.grp[s]
            if (!sprintAnnounced && !winnerAnnounced && (g == 0 || g == main || g < 0)) {
                sprintAnnounced = true
                emit(RaceEventKind.SPRINT, f.d[s], if (s == f.user) "Sprint!" else "Sprint! ${name(s)} eröffnet", listOf(s))
            }
        }
        val dec = plan.decisiveClimb
        if (!winnerAnnounced && dec != null && plan.finale != FinaleKind.SPRINT && f.groupCount > 0 && f.d[f.groupFront(0)] in dec.startM..dec.topM) {
            val size = f.groupSize(0)
            val step = SELECTION.lastOrNull { size <= it }
            if (step != null && step < selectionShown && size < 40) {
                selectionShown = step
                emit(RaceEventKind.SPLIT, f.d[f.groupFront(0)], "An der Spitze nur noch $size Fahrer")
            }
            for (i in 0 until n) {
                if (i == f.user || droppedShown[i] || duty[i] != DUTY_LEADER || strengthRank[i] >= 12 || !f.active(i)) continue
                val gi = f.grp[i]
                if (gi > 0 && f.gapAt(f.d[i]) > 10) {
                    droppedShown[i] = true
                    emit(RaceEventKind.DROPPED, f.d[i], "${name(i)} ist abgehängt", listOf(i))
                }
            }
        }
    }

    private fun userEvents() {
        val u = f.user
        if (!f.active(u)) return
        val g = f.grp[u]
        if (g < 0) return
        if (userMates.size >= 2 && f.t - userEventT > 20 && f.v[u] > 3) {
            var ahead = 0
            var behind = 0
            for (m in userMates) {
                if (!f.active(m)) continue
                val gm = f.grp[m]
                if (gm in 0 until g) ahead++ else if (gm > g) behind++
            }
            val need = max(2, (userMates.size * 0.6).toInt())
            if (ahead >= need) {
                userEventT = f.t
                emit(RaceEventKind.USER, f.d[u], "Du hast den Anschluss verloren")
            } else if (behind >= need) {
                userEventT = f.t
                emit(RaceEventKind.USER, f.d[u], if (g == 0) "Du hast eine Lücke gerissen – du bist an der Spitze" else "Du hast eine Lücke gerissen")
            }
        }
        val size = f.groupSize(g)
        userMates = IntArray(size - 1).also { arr ->
            var c = 0
            for (k in f.gOff[g] until f.gOff[g + 1]) { val i = f.gm[k]; if (i != u) arr[c++] = i }
        }
    }

    private fun checkFinish() {
        if (winnerAnnounced) return
        var first = -1
        for (i in 0 until n) if (!f.finishAt[i].isNaN() && (first < 0 || f.finishAt[i] < f.finishAt[first])) first = i
        if (first < 0 || f.timeTrial) return
        winnerAnnounced = true
        phase = RacePhase.FINISHED
        emit(RaceEventKind.FINISH, f.length, if (first == f.user) "Du gewinnst die Etappe!" else "${riders[first].name} gewinnt die Etappe!", listOf(first))
    }

    // ---- time trials ----

    private fun decideTimeTrial() {
        if (f.teamTrial) organiseTeams()
        // A new best time at the line.
        var best = -1
        for (i in 0 until n) {
            val fin = f.finishAt[i]
            if (fin.isNaN() || f.mode[i] != Mode.DONE || f.t - fin >= 1.5) continue
            val time = fin - f.startAt[i]
            if (time < bestTime - 1e-6) { bestTime = time; best = i }
        }
        // A whole team crosses the line within a second: one announcement.
        if (best >= 0 && f.t > 0) emit(RaceEventKind.SPLIT_TIME, f.length, "Neue Bestzeit: ${ttName(best)} in ${RaceText.clock(bestTime)}", listOf(best))
        val u = f.user
        while (splitIndex < splitMarks.size && f.active(u) && f.d[u] >= splitMarks[splitIndex]) {
            val at = splitMarks[splitIndex++]
            val k = (at / Peloton.SPLIT_M).toInt()
            val mine = f.splits[u * f.splitCount + k]
            var best = Float.MAX_VALUE
            var who = -1
            for (i in 0 until n) {
                // In the team time trial the user's team-mates are his own time, not rivals.
                if (i == u || (f.teamTrial && f.team[i] == f.team[u])) continue
                val s = f.splits[i * f.splitCount + k]
                if (!s.isNaN() && s < best) { best = s; who = i }
            }
            val text = if (who < 0 || mine.isNaN() || mine <= best) "Zwischenzeit: Bestzeit!"
            else "Zwischenzeit: ${RaceText.gap((mine - best).toDouble())} auf ${ttName(who)}"
            emit(RaceEventKind.SPLIT_TIME, at, text)
        }
        if (f.mode[u] == Mode.DONE) phase = RacePhase.FINISHED
    }

    /** After the riders were moved on without the tactics ([Peloton.soloTimeTrial]): the best time so far. */
    fun syncTimeTrial() {
        for (i in 0 until n) {
            val fin = f.finishAt[i]
            if (!fin.isNaN()) bestTime = min(bestTime, fin - f.startAt[i])
        }
    }

    /**
     * Team time trial: each team rides single file and the rider on the front pulls for half a minute,
     * then swings aside and drifts back to the end of the line. The pace is what the weakest rider still
     * in the line can follow, so the train stays together.
     */
    private fun organiseTeams() {
        val teams = f.roster.teams.size
        for (tm in 0 until teams) {
            var count = 0
            var head = -1
            for (k in 0 until n) {
                val i = f.ord[k]
                if (f.team[i] != tm || !f.active(i)) continue
                if (head < 0) head = i
                if (f.d[head] - f.d[i] < TTT_CONTACT_M) scratch[count++] = i
            }
            if (head < 0) continue
            // The AI riders of the train; the user rides in it but takes his own turns.
            var p = -1
            for (c in 0 until count) { val i = scratch[c]; if (f.pacer[i] && i != f.user) { if (p < 0) p = i else f.pacer[i] = false } }
            if (p >= 0 && (f.t >= turnEnd[p] || f.wbal[p] < 0.15 * f.wp[p])) {
                f.pacer[p] = false
                restUntil[p] = f.t + 20
                swingUntil[p] = f.t + SWING_SEC
                p = -1
            }
            if (p < 0) {
                for (c in 0 until count) {
                    val i = scratch[c]
                    if (i != f.user && f.mode[i] == Mode.PACK && f.t >= restUntil[i]) { p = i; break }
                }
                if (p < 0) for (c in 0 until count) { val i = scratch[c]; if (i != f.user && f.mode[i] == Mode.PACK) { p = i; break } }
                if (p < 0) continue
                f.pacer[p] = true
                turnEnd[p] = f.t + 25 + 10 * rng.nextDouble()
            }
            var factor = 1.12
            // On the flat a rider in the line needs about 70 % of the pacer's effort, on a climb nearly all of it.
            val climb = (f.gradeAt(f.d[p]) / 5).coerceIn(0.0, 1.0)
            var allowed = Double.MAX_VALUE
            for (c in 0 until count) {
                val j = scratch[c]
                // The user counts with his pro's threshold: a user riding at his own FTP can hold the train.
                if (j == p || (j != f.user && (f.mode[j] != Mode.PACK || f.t < swingUntil[j])) || (j == f.user && f.d[j] > f.d[p])) continue
                val k = lerp(1.3 * f.cda0[p] / f.cda0[j], 0.97 * f.mass[p] / f.mass[j], climb)
                allowed = min(allowed, f.cpEff(j) * k)
            }
            factor = min(factor, max(0.8, allowed / f.cpEff(p)))
            // A stretched line (the start, a corner, a rider struggling) is pulled together before the pace goes up again.
            var tail = p
            for (c in 0 until count) { val i = scratch[c]; if (i != f.user && f.t >= swingUntil[i] && f.d[i] < f.d[tail]) tail = i }
            if (f.d[p] - f.d[tail] > (count - 1) * TTT_SPACING + 8) factor = min(factor, 0.75)
            // The team waits for a user who has lost the back of the line (and rides on his wheel when he is in front, below).
            val du = f.d[f.user]
            if (tm == f.team[f.user] && f.active(f.user) && du < f.d[tail] - 6 && f.d[p] - du < TTT_CONTACT_M) factor = min(factor, 0.85)
            f.paceFactor[p] = factor
            f.paceLane[p] = 0.0
            f.slotRef[p] = p
            f.slotBack[p] = 0.0
            // The user is taking a turn on the front: the line rides on his wheel instead of chasing him,
            // and when his turn is over the next man comes through.
            val u = f.user
            if (tm == f.team[u] && f.active(u) && f.d[u] > f.d[p] && f.d[u] - f.d[p] < TTT_CONTACT_M) {
                if (userFrontSince.isNaN()) userFrontSince = f.t
                if (f.t - userFrontSince < USER_TURN_SEC || f.d[u] - f.d[p] > 6) {
                    f.slotRef[p] = u
                    f.slotBack[p] = TTT_SPACING
                    f.slotX[p] = 0.0
                } else f.paceFactor[p] = max(factor, 1.05)
            } else if (tm == f.team[u]) userFrontSince = Double.NaN
            // The others queue behind the pacer in the order they are in now; who just pulled swings out and goes last.
            var row = 1
            var lastInLine = p
            for (c in 0 until count) {
                val i = scratch[c]
                if (i == p) continue
                // The user holds his place in the line; the team-mates behind him queue on his wheel.
                if (i == f.user) { if (f.d[i] < f.d[p]) { row++; lastInLine = i }; continue }
                f.slotRef[i] = p
                if (f.t < swingUntil[i] && f.d[i] > f.d[lastInLine] - Drafting.BIKE_LENGTH_M) {
                    f.slotBack[i] = (count - 1) * TTT_SPACING
                    f.slotX[i] = SWING_X
                    continue
                }
                swingUntil[i] = 0.0
                f.slotBack[i] = row++ * TTT_SPACING
                f.slotX[i] = 0.0
                lastInLine = i
            }
            // Riders dropped by their train ride alone at time trial pace.
            for (k in 0 until n) {
                val i = f.ord[k]
                if (f.team[i] == tm && f.active(i) && i != f.user && f.d[head] - f.d[i] >= TTT_CONTACT_M && f.mode[i] == Mode.PACK) {
                    f.pacer[i] = false
                    f.slotRef[i] = i
                    f.paceFactor[i] = 0.95
                    f.paceLane[i] = Peloton.TT_LANE
                }
            }
        }
    }

    // ---- helpers ----

    private fun name(i: Int): String = if (i == f.user) "dir" else riders[i].name

    /** In the team time trial it is the team that sets a time. */
    private fun ttName(i: Int): String = when {
        f.teamTrial -> if (f.team[i] == f.team[f.user]) "dein Team" else riders[i].team.name
        i == f.user -> "du"
        else -> riders[i].name
    }

    private fun emit(kind: RaceEventKind, at: Double, text: String, ids: List<Int> = emptyList()) {
        events += RaceEvent(f.t, at, kind, text, ids)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t

    companion object {
        private const val TRAIN_ZONE_M = 25.0
        private const val SWING_SEC = 15.0
        private const val SWING_X = 0.9
        private const val TTT_SPACING = 1.95
        private const val USER_TURN_SEC = 30.0
        /** A rider further back than this has lost his team's train for good. */
        private const val TTT_CONTACT_M = 120.0
        const val ROLL_OUT_SEC = 60.0
        /** Only a rider this close to the front of his group takes over the pace. */
        const val PACER_ZONE_M = 10.0
        const val DUTY_NONE = 0
        const val DUTY_LEADER = 1
        const val DUTY_SPRINTER = 2
        const val DUTY_BREAK = 3
        const val BREAK_NONE = 0
        const val BREAK_LAUNCHED = 1
        const val BREAK_UP = 2
        const val BREAK_CAUGHT = 3
        const val BREAK_GIVEN_UP = 4
        val KM_MARKS = doubleArrayOf(50_000.0, 20_000.0, 10_000.0, 5_000.0, 3_000.0, 1_000.0)
        val SELECTION = intArrayOf(30, 20, 10, 5)
    }
}
