package app.rollentrainer.core.race

import kotlin.math.max
import kotlin.math.pow

/** A team with its kit colours as 0xRRGGBB: jersey base, second colour (sleeves, shorts), a small accent. */
data class Team(
    val index: Int,
    val code: String,
    val name: String,
    val primaryRgb: Int,
    val secondaryRgb: Int,
    val accentRgb: Int,
)

/** What a rider is in the race for; decides where he rides in the bunch and what he does in a finale. */
enum class RiderRole(val code: String, val title: String) {
    GC("GC", "Klassementfahrer"),
    CLIMBER("CLI", "Bergfahrer"),
    SPRINTER("SPR", "Sprinter"),
    PUNCHEUR("PUN", "Puncheur"),
    ROULEUR("ROU", "Rouleur"),
    DOMESTIQUE("DOM", "Helfer"),
    TT("TT", "Zeitfahrer"),
    ;

    companion object {
        fun of(code: String): RiderRole = entries.firstOrNull { it.code == code } ?: DOMESTIQUE
    }
}

/**
 * A professional with his estimated physiology. The peak powers in W/kg describe the power curve; from
 * them come the critical power and W' that limit what the rider can do in the simulation.
 */
data class ProRider(
    /** Index in the roster, also the index in every per-rider array of the race. */
    val id: Int,
    val bib: Int,
    val name: String,
    /** IOC code, "SLO". */
    val nation: String,
    val team: Team,
    val role: RiderRole,
    val weightKg: Double,
    val ftpW: Int,
    val peak20sWkg: Double,
    val peak1mWkg: Double,
    val peak5mWkg: Double,
    val peak20mWkg: Double,
) {
    val ftpWkg: Double get() = ftpW / weightKg

    /**
     * Drag area on a road bike in the drops. Frontal area grows with body size roughly as mass^(2/3);
     * time trial specialists sit a little lower even on the road bike.
     */
    val cda: Double get() = 0.30 * (weightKg / 70.0).pow(2.0 / 3.0) * (if (role == RiderRole.TT) 0.97 else 1.0)

    /** On the time trial bike. */
    val cdaTt: Double get() = cda * 0.74

    /** Critical power and W' from the 5- and 20-minute peaks (two-parameter model P = CP + W'/t). */
    val criticalPowerW: Double
        get() = peak20mWkg * weightKg - wPrimeFit / 1200.0

    private val wPrimeFit: Double get() = ((peak5mWkg - peak20mWkg) * weightKg * 400.0).coerceAtLeast(8000.0)

    /** W' in joules, large enough that the stated 1-minute and 20-second peaks are reachable when fresh. */
    val wPrimeJ: Double
        get() {
            val cp = criticalPowerW
            return max(wPrimeFit, max((peak1mWkg * weightKg - cp) * 60 * 0.95, (peak20sWkg * weightKg - cp) * 20 * 0.95))
        }

    /** Highest instantaneous power when fresh: a little above the 20-second average. */
    val peakPowerW: Double get() = peak20sWkg * weightKg * 1.12

    /** How much of his critical power a rider loses over a long, hard day (0.03 = 3 %). */
    val durability: Double
        get() = when (role) {
            RiderRole.GC -> 0.03
            RiderRole.CLIMBER -> 0.045
            RiderRole.TT, RiderRole.ROULEUR -> 0.05
            RiderRole.PUNCHEUR -> 0.055
            RiderRole.DOMESTIQUE -> 0.06
            RiderRole.SPRINTER -> 0.07
        }

    /** Can contest a bunch sprint (sprinters, and the fast puncheurs like Pedersen or Van der Poel). */
    val sprints: Boolean get() = role == RiderRole.SPRINTER || peak20sWkg >= 16.5

    /** "T. Pogačar", "M. van der Poel": for the HUD where the full name is too long. */
    val shortName: String
        get() {
            val cut = name.indexOf(' ')
            return if (cut <= 0) name else "${name.first()}. ${name.substring(cut + 1)}"
        }
}

/** The riders of a race and their teams. */
class Roster(val teams: List<Team>, val riders: List<ProRider>) {
    val size: Int get() = riders.size

    fun byName(name: String): ProRider? = riders.firstOrNull { it.name.equals(name, ignoreCase = true) }
        ?: riders.firstOrNull { it.name.contains(name, ignoreCase = true) }

    fun byBib(bib: Int): ProRider? = riders.firstOrNull { it.bib == bib }

    fun ofTeam(team: Int): List<ProRider> = riders.filter { it.team.index == team }

    /** A sub-roster (tests, small races); the ids are renumbered so they stay array indices. */
    fun only(filter: (ProRider) -> Boolean): Roster {
        val kept = riders.filter(filter)
        return Roster(teams, kept.mapIndexed { i, r -> r.copy(id = i) })
    }

    companion object {
        /** Parses the compact roster lines "bib|name|nation|team|role|kg|ftp|20s|1m|5m|20m". */
        fun parse(teams: List<Team>, data: String): Roster {
            val riders = data.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.mapIndexed { i, line ->
                val f = line.split('|')
                ProRider(
                    id = i, bib = f[0].toInt(), name = f[1], nation = f[2], team = teams[f[3].toInt()],
                    role = RiderRole.of(f[4]), weightKg = f[5].toDouble(), ftpW = f[6].toInt(),
                    peak20sWkg = f[7].toDouble(), peak1mWkg = f[8].toDouble(), peak5mWkg = f[9].toDouble(), peak20mWkg = f[10].toDouble(),
                )
            }.toList()
            return Roster(teams, riders)
        }
    }
}
