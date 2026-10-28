package app.rollentrainer.core.race

import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteKind
import kotlin.random.Random

/**
 * What the race will try to do today, drawn once from the seed: who goes in the break and when, how much
 * room the bunch gives it, where it should be caught, which teams ride and where the favourites attack.
 * Distances are metres on the route (negative = before the start of a finale cut).
 */
internal class RacePlan(
    val style: StageStyle,
    val finale: FinaleKind,
    val decisiveClimb: ProfileClimb?,
    val climbs: List<ProfileClimb>,
    /** Length of the whole stage and how much of it lies before this route starts (a finale cut). */
    val fullLengthM: Double,
    val startOffsetM: Double,
    val breakRiders: IntArray,
    val breakAtM: Double,
    val maxGapSec: Double,
    /** Distance to go at which the bunch wants the break back; negative = the break is let go to the line. */
    val catchToGoM: Double,
    val chaseFromToGoM: Double,
    /** Teams that ride on the front to control the break (best first). */
    val controlTeams: IntArray,
    /** Teams that take over in the finale: sprint trains, the favourites' teams, the puncheurs' teams. */
    val finaleTeams: IntArray,
    /** How far before the top of the deciding climb the attacks begin. */
    val attackToTopM: Double,
    /** Per rider: distance before the line at which he opens his sprint. */
    val launchM: DoubleArray,
    /** Per team: its leader for the day (protected, not pulling) and its sprinter, or -1. */
    val leaderOf: IntArray,
    val sprinterOf: IntArray,
    /** A late attack in a flat finale: distance to go, or a negative value for none. */
    val lateAttackToGoM: Double,
) {
    val hasBreak: Boolean get() = breakRiders.isNotEmpty()

    /** The gap (s) the bunch allows the break with [toGoM] left. */
    fun gapTargetSec(toGoM: Double): Double {
        if (!hasBreak) return 0.0
        val ridden = fullLengthM - startOffsetM - toGoM
        val ramp = ((ridden - breakAtM) / RAMP_M).coerceIn(0.0, 1.0) * maxGapSec
        val chase = when {
            toGoM >= chaseFromToGoM -> maxGapSec
            catchToGoM >= 0 -> maxGapSec * ((toGoM - catchToGoM) / (chaseFromToGoM - catchToGoM)).coerceIn(0.0, 1.0)
            // A break that is let go: the bunch only nibbles at the gap.
            else -> maxGapSec * (0.4 + 0.6 * (toGoM / chaseFromToGoM).coerceIn(0.0, 1.0))
        }
        return minOf(ramp, chase)
    }

    companion object {
        const val RAMP_M = 30_000.0

        fun styleOf(route: Route): StageStyle = when (route.stage?.type) {
            "flat" -> StageStyle.FLAT
            "hilly" -> StageStyle.HILLY
            "mountain" -> StageStyle.MOUNTAIN
            "itt" -> StageStyle.ITT
            "ttt" -> StageStyle.TTT
            else -> when (route.kind) {
                RouteKind.CLIMB -> StageStyle.MOUNTAIN
                RouteKind.FLAT -> StageStyle.FLAT
                else -> StageStyle.HILLY
            }
        }

        /** A finale cut starts in the middle of the stage, the race situation already set up. */
        fun startOffset(route: Route): Double = when {
            route.offsetM > 0 -> route.offsetM
            route.variant.startsWith("Finale") && route.fraction in 0.01..0.999 -> route.lengthM / route.fraction - route.lengthM
            else -> 0.0
        }

        fun create(route: Route, roster: Roster, user: Int, rng: Random): RacePlan {
            val style = styleOf(route)
            val climbs = Terrain.climbs(route)
            val (finale, climb) = Terrain.finale(route, climbs)
            val riders = roster.riders
            val nTeams = roster.teams.size
            val offset = startOffset(route)
            val full = route.lengthM + offset

            // Leaders: the strongest climber of each team (the user leads his team if he is a GC rider or climber).
            val leaderOf = IntArray(nTeams) { -1 }
            val sprinterOf = IntArray(nTeams) { -1 }
            for (r in riders) {
                val t = r.team.index
                if (r.role == RiderRole.GC || r.role == RiderRole.CLIMBER) {
                    val cur = leaderOf[t]
                    if (cur < 0 || strength(r) > strength(riders[cur])) leaderOf[t] = r.id
                }
                if (r.role == RiderRole.SPRINTER || (r.sprints && r.role == RiderRole.PUNCHEUR)) {
                    // A team with a real sprinter rides for him; the fast puncheur is then his lead-out man.
                    val cur = sprinterOf[t]
                    val better = cur < 0 || (r.role == RiderRole.SPRINTER && riders[cur].role != RiderRole.SPRINTER) ||
                        (r.role == riders[cur].role && r.peakPowerW > riders[cur].peakPowerW)
                    if (better) sprinterOf[t] = r.id
                }
            }
            val gcTeams = (0 until nTeams).filter { leaderOf[it] >= 0 }.sortedByDescending { strength(riders[leaderOf[it]]) }
            val sprintTeams = (0 until nTeams).filter { sprinterOf[it] >= 0 && riders[sprinterOf[it]].role == RiderRole.SPRINTER }
                .sortedByDescending { riders[sprinterOf[it]].peakPowerW }
            val punchTeams = (0 until nTeams).sortedByDescending { t ->
                roster.riders.filter { it.team.index == t && (it.role == RiderRole.PUNCHEUR || it.role == RiderRole.GC) }
                    .maxOfOrNull { it.peak1mWkg } ?: 0.0
            }
            val control = when {
                style == StageStyle.MOUNTAIN || finale == FinaleKind.SUMMIT -> gcTeams.take(2)
                finale == FinaleKind.SPRINT && sprintTeams.isNotEmpty() -> sprintTeams.take(2) + gcTeams.take(1)
                else -> (punchTeams.take(1) + gcTeams.take(1) + sprintTeams.take(1)).distinct()
            }.distinct()
            val finaleTeams = when (finale) {
                FinaleKind.SPRINT -> sprintTeams.take(4).ifEmpty { gcTeams.take(2) }
                FinaleKind.SUMMIT, FinaleKind.HILL -> gcTeams.take(3)
                FinaleKind.PUNCH -> (punchTeams.take(2) + gcTeams.take(1)).distinct()
            }

            val tt = style == StageStyle.ITT || style == StageStyle.TTT
            val roadRace = !tt && full >= 60_000 && riders.size >= 20
            val breakSize = when (style) {
                StageStyle.FLAT -> pick(rng, intArrayOf(2, 3, 4, 5), doubleArrayOf(0.25, 0.4, 0.25, 0.1))
                StageStyle.HILLY -> 3 + rng.nextInt(4)
                else -> 4 + rng.nextInt(5)
            }
            val breakRiders = if (roadRace) chooseBreak(roster, user, style, breakSize, control, leaderOf, sprinterOf, rng) else IntArray(0)
            val breakAt = 3_000.0 + rng.nextDouble() * 12_000.0
            val maxGap = when (style) {
                StageStyle.FLAT -> 150.0 + rng.nextDouble() * 120
                StageStyle.HILLY -> 180.0 + rng.nextDouble() * 150
                else -> 240.0 + rng.nextDouble() * 180
            }
            val catchToGo = when {
                style == StageStyle.FLAT || (finale == FinaleKind.SPRINT && style != StageStyle.MOUNTAIN && rng.nextDouble() < 0.7) ->
                    8_000.0 + rng.nextDouble() * 12_000
                finale == FinaleKind.SUMMIT && climb != null && rng.nextDouble() < 0.7 ->
                    (route.lengthM - climb.topM) + climb.lengthM * (0.2 + 0.5 * rng.nextDouble())
                finale == FinaleKind.PUNCH && rng.nextDouble() < 0.75 -> 3_000.0 + rng.nextDouble() * 9_000
                finale == FinaleKind.HILL && climb != null && rng.nextDouble() < 0.6 ->
                    (route.lengthM - climb.topM) + climb.lengthM * 0.5
                rng.nextDouble() < 0.5 -> 4_000.0 + rng.nextDouble() * 10_000
                else -> -1.0
            }
            // About 12 km of chasing per minute of gap, plus a margin: a bunch at full speed closes 1 min in ~10 km.
            val chaseFrom = (maxOf(catchToGo, 0.0) + maxGap / 60 * 12_000 + 10_000).coerceAtMost(full * 0.75)
            val attackToTop = when (finale) {
                FinaleKind.SUMMIT -> (2_000 + rng.nextDouble() * 4_000).coerceAtMost((climb?.lengthM ?: 4000.0) * 0.7)
                FinaleKind.HILL -> (800 + rng.nextDouble() * 1_700).coerceAtMost((climb?.lengthM ?: 2000.0) * 0.6)
                FinaleKind.PUNCH -> (250 + rng.nextDouble() * 450).coerceAtMost((climb?.lengthM ?: 700.0) * 0.9)
                FinaleKind.SPRINT -> 0.0
            }
            val launch = DoubleArray(riders.size) { i ->
                if (riders[i].role == RiderRole.SPRINTER) 180 + rng.nextDouble() * 140 else 120 + rng.nextDouble() * 140
            }
            val lateAttack = if (finale == FinaleKind.SPRINT && roadRace && rng.nextDouble() < 0.4) 3_000 + rng.nextDouble() * 5_000 else -1.0
            return RacePlan(
                style, finale, climb, climbs, full, offset, breakRiders, breakAt - offset, maxGap, catchToGo, chaseFrom,
                control.toIntArray(), finaleTeams.toIntArray(), attackToTop, launch, leaderOf, sprinterOf, lateAttack,
            )
        }

        /** Climbing strength: critical power per kilogram. */
        fun strength(r: ProRider): Double = r.criticalPowerW / r.weightKg

        private fun pick(rng: Random, values: IntArray, weights: DoubleArray): Int {
            var x = rng.nextDouble() * weights.sum()
            for (k in values.indices) { x -= weights[k]; if (x <= 0) return values[k] }
            return values.last()
        }

        /** Smaller teams send riders in the breaks to be seen on TV, the teams that control the race do not. */
        private val SHOW_TEAMS = setOf("TTE", "CJR", "COF", "TPP", "UXM", "PQT", "TUD", "LOI", "NSN")

        private fun chooseBreak(
            roster: Roster, user: Int, style: StageStyle, size: Int, control: List<Int>,
            leaderOf: IntArray, sprinterOf: IntArray, rng: Random,
        ): IntArray {
            val pool = roster.riders.filter { r ->
                r.id != user && r.team.index !in control && leaderOf[r.team.index] != r.id && sprinterOf[r.team.index] != r.id
            }
            val weights = pool.map { r ->
                val role = when (style) {
                    StageStyle.FLAT -> when (r.role) {
                        RiderRole.ROULEUR, RiderRole.TT -> 3.0; RiderRole.PUNCHEUR -> 2.0; RiderRole.DOMESTIQUE -> 1.5
                        RiderRole.CLIMBER -> 0.5; RiderRole.SPRINTER -> 0.2; RiderRole.GC -> 0.0
                    }
                    StageStyle.HILLY -> when (r.role) {
                        RiderRole.PUNCHEUR -> 3.0; RiderRole.ROULEUR, RiderRole.TT -> 2.0; RiderRole.CLIMBER -> 1.5
                        RiderRole.DOMESTIQUE -> 1.0; RiderRole.SPRINTER -> 0.1; RiderRole.GC -> 0.2
                    }
                    else -> when (r.role) {
                        RiderRole.CLIMBER -> 3.0; RiderRole.PUNCHEUR -> 2.5; RiderRole.ROULEUR, RiderRole.TT -> 1.0
                        RiderRole.DOMESTIQUE -> 1.0; RiderRole.GC -> 0.3; RiderRole.SPRINTER -> 0.0
                    }
                }
                role * (if (r.team.code in SHOW_TEAMS) 2.0 else 1.0) * (r.criticalPowerW / 350.0)
            }.toMutableList()
            val chosen = mutableListOf<Int>()
            val usedTeams = mutableSetOf<Int>()
            while (chosen.size < size) {
                val total = weights.sum()
                if (total <= 0) break
                var x = rng.nextDouble() * total
                var k = 0
                while (k < weights.size - 1 && x > weights[k]) { x -= weights[k]; k++ }
                val r = pool[k]
                weights[k] = 0.0
                if (r.team.index in usedTeams) continue
                usedTeams += r.team.index
                chosen += r.id
            }
            return chosen.toIntArray()
        }
    }
}
