package app.rollentrainer.core.race

import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RoutePoint
import app.rollentrainer.core.route.RouteState
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.random.Random

data class RaceOptions(
    /** Road width; the route has none yet, 7 m is a typical Tour road. */
    val roadWidthM: Double = 7.0,
    /** Fixed simulation step, whatever the caller's tick length: keeps the race deterministic. */
    val stepSec: Double = 0.1,
    val ttIntervalSec: Double = 60.0,
    val tttIntervalSec: Double = 180.0,
    val recentEvents: Int = 6,
)

/**
 * Pro mode: the user rides a stage as one of the pros, in an AI field. His power is scaled by the pro's
 * FTP over his own ([powerScale]); the physics use the pro's weight and drag area, with drafting from the
 * riders around him. The field races: a break goes, teams control and chase, trains lead out the sprint,
 * favourites attack on the climbs; time trials are ridden against the clock with start intervals.
 *
 * Drive it like [app.rollentrainer.core.route.RouteEngine]: [tick] with the measured power, then use
 * [RaceState.route] exactly as the route state (trainer grade, recording, virtual gears). The same seed
 * and the same inputs give the same race.
 */
class RaceEngine(
    val route: Route,
    val roster: Roster,
    userRiderId: Int,
    userFtpW: Int,
    seed: Long = 1L,
    val options: RaceOptions = RaceOptions(),
) {
    val userRider: ProRider = roster.riders[userRiderId]
    /** Pro power = user power × this. */
    val powerScale: Double = userRider.ftpW.toDouble() / userFtpW.coerceAtLeast(50)
    // The same seed gives the same race on the same stage, and different races on different stages.
    private val rng = Random(seed * 1_000_003L + (route.stage?.number ?: 0) * 7_919L + route.lengthM.toLong())
    internal val plan = RacePlan.create(route, roster, userRiderId, rng)
    val style: StageStyle get() = plan.style
    internal val field = Peloton(route, roster, userRiderId, plan.style, options.roadWidthM)
    internal val tactics = Tactics(field, plan, rng)
    private val user = userRiderId
    private val lock = Any()
    private val frame = RiderFrame(roster.size)
    private var acc = 0.0
    private var nextDecision = 0.0
    private val recent = ArrayDeque<RaceEvent>()
    private var finalResult: StageResult? = null

    /** Written under the lock by [tick]; volatile so that a reader on another thread sees the latest one. */
    @Volatile
    var state: RaceState
        private set

    init {
        when (plan.style) {
            StageStyle.ITT -> setupItt()
            StageStyle.TTT -> setupTtt()
            else -> setupRoad()
        }
        nextDecision = field.t
        // Time trials: the riders who start before the user are already on the road or finished. In an
        // individual one they ride alone until the last minutes before his start (seconds of field steps
        // otherwise); then the whole field steps, so the riders around him at the start pass as in the race.
        if (plan.style == StageStyle.ITT && field.t < -SOLO_LEAD_SEC) {
            field.soloTimeTrial(-SOLO_LEAD_SEC, SOLO_STEP_SEC, options.stepSec)
            tactics.syncTimeTrial()
            nextDecision = field.t
        }
        while (field.t < -1e-9) substep()
        tactics.events.clear()
        field.fillFrame(frame)
        state = buildState(emptyList(), 0.0, 0.0)
    }

    val riderCount: Int get() = roster.size

    /** The pro's riders in roster order; [RiderFrame] entries use the same index. */
    val riders: List<ProRider> get() = roster.riders

    /**
     * Advances the race by [dt] seconds with the user's measured power. While [paused] nothing moves
     * (the race waits for the user, as the pacers do in RouteEngine).
     */
    fun tick(dt: Double, userPowerW: Int?, paused: Boolean = false): RaceState = synchronized(lock) {
        if (paused || dt <= 0) {
            state = state.copy(route = state.route.copy(speedMps = 0.0, passedLandmark = null), newEvents = emptyList())
            return state
        }
        field.userPowerW = (userPowerW ?: 0).coerceAtLeast(0) * powerScale
        val before = field.d[user]
        acc += dt
        val h = options.stepSec
        while (acc >= h - 1e-9) { substep(); acc -= h }
        val fresh = ArrayList(tactics.events)
        tactics.events.clear()
        field.fillFrame(frame)
        state = buildState(fresh, before, dt)
        state
    }

    fun moveLeft() = synchronized(lock) { field.moveUser(-1) }
    fun moveRight() = synchronized(lock) { field.moveUser(1) }

    /** Copies the latest per-rider frame into [dst] (owned by the renderer, sized [riderCount]). */
    fun copyFrame(dst: RiderFrame) = synchronized(lock) { frame.copyInto(dst) }

    /**
     * The stage result. With [complete] the AI riders still on the road are raced to the line first (the
     * user, if he has not finished, does not finish); call it when the ride ends.
     */
    fun result(complete: Boolean = true): StageResult = synchronized(lock) {
        finalResult?.let { return it }
        if (!complete) return ranking(false)
        if (field.mode[user] != Mode.DONE) {
            field.mode[user] = Mode.DONE
            field.v[user] = 0.0
        }
        field.userPowerW = 0.0
        val limit = field.t + 6 * 3600
        // Nobody watches any more: the riders of an individual time trial ride the rest alone.
        if (plan.style == StageStyle.ITT) field.soloTimeTrial(limit, SOLO_STEP_SEC, options.stepSec)
        while (field.t < limit && (0 until roster.size).any { it != user && field.finishAt[it].isNaN() && field.mode[it] != Mode.DONE }) substep()
        tactics.events.clear()
        ranking(true).also { finalResult = it }
    }

    /**
     * The state and the stage result taken together under the lock, for the end of the ride: the result
     * is complete exactly when that state has the user across the line, whatever tick runs meanwhile.
     */
    fun finalSnapshot(): Pair<RaceState, StageResult> = synchronized(lock) {
        val now = state
        now to result(complete = now.finished)
    }

    private fun substep() {
        field.step(options.stepSec)
        if (field.t >= nextDecision - 1e-9) {
            tactics.decide()
            nextDecision += DECIDE_SEC
        }
    }

    // ---- start set-ups ----

    /** The bunch lines up behind the start, the user in the middle of the front row. */
    private fun setupRoad() {
        val w = max(1, (options.roadWidthM / 0.95).toInt() - 1)
        val others = (0 until roster.size).filter { it != user }.shuffled(rng)
        val leaders = plan.leaderOf.filter { it >= 0 && it != user }.toSet()
        val order = others.filter { it in leaders } + others.filter { it !in leaders }
        val userSlot = w / 2
        var slot = 0
        fun place(i: Int, s: Int) {
            val row = s / w
            val col = s % w
            field.d[i] = -row * 2.0
            field.x[i] = (col - (w - 1) / 2.0) * 0.95
        }
        place(user, userSlot)
        for (i in order) {
            if (slot == userSlot) slot++
            place(i, slot++)
        }
        val breakAway = tactics.placeBreak()
        if (breakAway.isNotEmpty()) {
            val speed = 11.0
            val ahead = (plan.gapTargetSec(route.lengthM) * speed).coerceAtMost(route.lengthM * 0.5)
            breakAway.forEachIndexed { k, i ->
                field.d[i] = ahead - k * 2.0
                field.x[i] = if (k % 2 == 0) 0.2 else -0.2
            }
            field.seedHead(ahead, speed)
        }
    }

    private fun ttPacing(i: Int, profile: Route): Pair<Double, Double> {
        val r = roster.riders[i]
        val params = RiderParams(totalMassKg = field.mass[i], cda = field.cda0[i])
        var p = field.cp[i] * 0.95
        var sec = Physics.estimateSeconds(profile, p, params).toDouble()
        p = RacePhysics.powerForDuration(field.cp[i], field.wp[i], sec) * (0.98 + 0.04 * rng.nextDouble())
        sec = Physics.estimateSeconds(profile, p, params).toDouble()
        return (if (r.role == RiderRole.SPRINTER) p * 0.97 else p) to sec
    }

    /**
     * The route for the pacing estimates: a point every [PACING_STEP_M] is exact enough for them, and on a
     * detailed profile (a point every few metres) much cheaper, with two estimates for each of the riders.
     */
    private fun pacingProfile(): Route {
        val n = (route.lengthM / PACING_STEP_M).toInt()
        if (route.points.size <= n + 2) return route
        val pts = (0..n).map { k -> RoutePoint(k * PACING_STEP_M, route.elevationAt(k * PACING_STEP_M)) } +
            RoutePoint(route.lengthM, route.elevationAt(route.lengthM))
        return route.copy(points = pts)
    }

    /** Individual time trial: slowest first, one rider a minute; the user starts at the race clock's zero. */
    private fun setupItt() {
        val est = DoubleArray(roster.size)
        val profile = pacingProfile()
        for (i in 0 until roster.size) {
            val (p, sec) = ttPacing(i, profile)
            field.modePower[i] = p
            est[i] = sec
        }
        val order = (0 until roster.size).sortedByDescending { est[it] }
        val mine = order.indexOf(user)
        for ((k, i) in order.withIndex()) {
            field.startAt[i] = (k - mine) * options.ttIntervalSec
            field.mode[i] = Mode.WAIT
            field.d[i] = 0.0
            field.x[i] = Peloton.TT_LANE
        }
        field.t = (field.startAt.minOrNull() ?: 0.0) - options.stepSec
    }

    /** Team time trial: teams one behind the other in single file, the slowest team first. */
    private fun setupTtt() {
        val est = DoubleArray(roster.size)
        val profile = pacingProfile()
        for (i in 0 until roster.size) est[i] = ttPacing(i, profile).second
        val teams = roster.teams.indices.filter { t -> roster.riders.any { it.team.index == t } }
        val teamTime = teams.associateWith { t -> roster.riders.filter { it.team.index == t }.map { est[it.id] }.sorted().let { it[minOf(4, it.size - 1)] } }
        val order = teams.sortedByDescending { teamTime[it] }
        val mine = order.indexOf(userRider.team.index)
        for ((k, t) in order.withIndex()) {
            val members = roster.riders.filter { it.team.index == t }.sortedBy { est[it.id] }
            members.forEachIndexed { j, r ->
                field.startAt[r.id] = (k - mine) * options.tttIntervalSec
                field.mode[r.id] = Mode.WAIT
                field.d[r.id] = -j * 2.0
                field.x[r.id] = 0.0
            }
        }
        field.t = (field.startAt.minOrNull() ?: 0.0) - options.stepSec
    }

    // ---- state for the app ----

    private fun buildState(fresh: List<RaceEvent>, before: Double, dt: Double): RaceState {
        val f = field
        val u = user
        val finishedNow = f.mode[u] == Mode.DONE && !f.finishAt[u].isNaN()
        val dist = f.d[u].coerceIn(0.0, route.lengthM)
        val elapsed = max(0.0, f.t - f.startAt[u])
        val passed = route.landmarks.firstOrNull { it.distanceM > before && it.distanceM <= dist }
        val justFinished = finishedNow && before < route.lengthM && dt > 0
        val userTime = if (finishedNow) f.finishAt[u] - f.startAt[u] else null
        val events = ArrayList(fresh)
        if (justFinished) {
            val rank = rankOf(u)
            events += RaceEvent(f.t, route.lengthM, RaceEventKind.FINISH, "Ziel! Platz $rank", listOf(u))
        }
        for (e in events) {
            recent.addLast(e)
            while (recent.size > options.recentEvents) recent.removeFirst()
        }
        val routeState = RouteState(
            route = route,
            distanceM = dist,
            speedMps = if (justFinished) ((dist - before.coerceAtLeast(0.0)) / dt).coerceAtLeast(0.0) else if (finishedNow) 0.0 else f.v[u],
            gradePct = route.gradientAt(dist),
            elevationM = route.elevationAt(dist),
            ascentM = route.ascentUntil(dist),
            elapsedSec = elapsed,
            finished = finishedNow,
            passedLandmark = passed,
            finishTimeSec = userTime,
        )
        val groups = buildGroups()
        val ug = if (f.active(u)) f.grp[u] else -1
        val head = gapToHead(u)
        val ahead = if (ug > 0 && !f.timeTrial) f.gapAt(dist) - f.gapAt(f.d[f.groupRear(ug - 1)]) else null
        val behind = if (ug >= 0 && ug < f.groupCount - 1 && !f.timeTrial) f.gapAt(f.d[f.groupFront(ug + 1)]) - f.gapAt(dist) else null
        return RaceState(
            route = routeState,
            phase = tactics.phase,
            style = plan.style,
            raceTimeSec = elapsed,
            proPowerW = f.userPowerW.roundToInt(),
            powerScale = powerScale,
            draftReduction = f.draft[u],
            rank = rankOf(u),
            ridersInRace = roster.size,
            gapToHeadSec = head,
            gapToGroupAheadSec = ahead?.coerceAtLeast(0.0),
            gapToGroupBehindSec = behind?.coerceAtLeast(0.0),
            userGroup = ug,
            groups = groups,
            newEvents = events,
            recentEvents = recent.toList(),
            kmToGo = (route.lengthM - dist) / 1000,
            finished = finishedNow,
            result = if (finishedNow) finalResult ?: ranking(false) else null,
        )
    }

    private fun buildGroups(): List<RaceGroup> {
        val f = field
        if (f.groupCount == 0) return emptyList()
        var main = 0
        for (g in 1 until f.groupCount) if (f.groupSize(g) > f.groupSize(main)) main = g
        return (0 until f.groupCount).map { g ->
            val ids = (f.gOff[g] until f.gOff[g + 1]).map { f.gm[it] }
            val label = when {
                f.teamTrial -> f.roster.riders[ids[0]].team.name
                f.timeTrial -> "Zeitfahrer"
                g == main -> "Feld"
                g == 0 -> if (ids.size <= 12) "Spitze" else "Spitzengruppe"
                g < main -> "Verfolger"
                ids.size >= 5 -> "Gruppetto"
                else -> "Abgehängt"
            }
            RaceGroup(
                index = g, size = ids.size, frontDistanceM = f.d[ids.first()], rearDistanceM = f.d[ids.last()],
                gapSec = if (f.timeTrial) 0.0 else f.gapAt(f.d[ids.first()]), speedMps = f.groupSpeed(g), label = label,
                containsUser = user in ids, riderIds = ids,
            )
        }
    }

    /** In a time trial the user's own time: in the team time trial his team-mates are not his rivals. */
    private fun rival(i: Int, u: Int): Boolean = i != u && !(field.teamTrial && field.team[i] == field.team[u])

    /** Time behind the head of the race; in a time trial behind the best time so far at the same point. */
    private fun gapToHead(u: Int): Double {
        val f = field
        if (f.timeTrial) {
            val k = (f.d[u].coerceAtLeast(0.0) / Peloton.SPLIT_M).toInt().coerceIn(0, f.splitCount - 1)
            val mine = f.splits[u * f.splitCount + k]
            if (mine.isNaN()) return 0.0
            var best = Float.MAX_VALUE
            for (i in 0 until f.n) if (rival(i, u)) { val s = f.splits[i * f.splitCount + k]; if (!s.isNaN() && s < best) best = s }
            return if (best == Float.MAX_VALUE) 0.0 else (mine - best).toDouble()
        }
        if (!f.finishAt[u].isNaN()) {
            var first = f.finishAt[u]
            for (i in 0 until f.n) if (!f.finishAt[i].isNaN() && f.finishAt[i] < first) first = f.finishAt[i]
            return f.finishAt[u] - first
        }
        return f.gapAt(f.d[u])
    }

    private fun rankOf(u: Int): Int {
        val f = field
        if (f.timeTrial) {
            if (!f.finishAt[u].isNaN()) {
                // Across the line: his place in the result, shared with the team-mates on the team's time.
                val time = stageTimes()
                var c = 0
                for (i in 0 until f.n) if (i != u && !time[i].isNaN() && time[i] < time[u]) c++
                return c + 1
            }
            val k = (f.d[u].coerceAtLeast(0.0) / Peloton.SPLIT_M).toInt().coerceIn(0, f.splitCount - 1)
            val mine = f.splits[u * f.splitCount + k]
            if (mine.isNaN()) return 1
            var c = 0
            for (i in 0 until f.n) if (rival(i, u)) { val s = f.splits[i * f.splitCount + k]; if (!s.isNaN() && s < mine) c++ }
            return c + 1
        }
        var c = 0
        val mineAt = f.finishAt[u]
        for (i in 0 until f.n) {
            if (i == u) continue
            val ahead = if (!mineAt.isNaN()) !f.finishAt[i].isNaN() && f.finishAt[i] < mineAt
            else !f.finishAt[i].isNaN() || f.d[i] > f.d[u]
            if (ahead) c++
        }
        return c + 1
    }

    /**
     * Time trial: each finisher's time (NaN = not across the line). In the team time trial a rider who
     * crosses within a second of the team-mate ahead of him finishes with his team and gets its time, the
     * time of its first rider across the line.
     */
    private fun stageTimes(): DoubleArray {
        val f = field
        val time = DoubleArray(f.n) { if (f.finishAt[it].isNaN()) Double.NaN else f.finishAt[it] - f.startAt[it] }
        if (!f.teamTrial) return time
        for (mates in (0 until f.n).filter { !time[it].isNaN() }.groupBy { f.team[it] }.values) {
            val sorted = mates.sortedBy { f.finishAt[it] }
            for (k in 1 until sorted.size) {
                if (f.finishAt[sorted[k]] - f.finishAt[sorted[k - 1]] < TEAM_GAP_SEC) time[sorted[k]] = time[sorted[k - 1]]
            }
        }
        return time
    }

    /** Finishers by time; in a road race riders within a second of the rider ahead get his time. */
    private fun ranking(complete: Boolean): StageResult {
        val f = field
        val done = (0 until f.n).filter { !f.finishAt[it].isNaN() }
        val rows = ArrayList<ResultRow>(done.size)
        if (f.timeTrial) {
            val time = stageTimes()
            // On the same time the user is listed first, then the order across the line.
            val sorted = done.sortedWith(compareBy<Int>({ time[it] }, { it != user }, { f.finishAt[it] }))
            val best = sorted.firstOrNull()?.let { time[it] } ?: 0.0
            var rank = 0
            sorted.forEachIndexed { k, i ->
                // Riders on the same time share the place: a team that wins the team time trial wins it together.
                if (k == 0 || time[i] > time[sorted[k - 1]]) rank = k + 1
                rows += row(rank, i, time[i], best)
            }
        } else {
            val sorted = done.sortedBy { f.finishAt[it] }
            // Group time: a rider on the wheel of the one before gets his time. The Tour widens the gap
            // that splits a group to three seconds on sprint finishes, so a strung-out bunch is not
            // split up by the sprint itself.
            val groupGap = if (plan.finale == FinaleKind.SPRINT) 3.0 else 1.0
            var time = 0.0
            var best = 0.0
            sorted.forEachIndexed { k, i ->
                val own = f.finishAt[i] - f.startAt[i]
                time = if (k > 0 && f.finishAt[i] - f.finishAt[sorted[k - 1]] < groupGap) time else own
                if (k == 0) best = time
                rows += row(k + 1, i, time, best)
            }
        }
        return StageResult(rows, complete)
    }

    private fun row(rank: Int, i: Int, time: Double, best: Double): ResultRow {
        val r = roster.riders[i]
        return ResultRow(rank, i, r.name, r.team.name, time, time - best, i == user)
    }

    /** Distance at which the break was caught (m to go), or NaN; for tests and the summary. */
    val caughtToGoM: Double get() = tactics.caughtToGoM

    companion object {
        /** How often the tactics look at the race. */
        const val DECIDE_SEC = 1.0
        /** Individual time trial: the riders ride alone until this long before the user's start, with this step. */
        const val SOLO_LEAD_SEC = 120.0
        const val SOLO_STEP_SEC = 0.5
        const val PACING_STEP_M = 200.0
        /** Team time trial: who crosses this close behind a team-mate finishes with the team. */
        const val TEAM_GAP_SEC = 1.0
    }
}
