package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.core.race.Team
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.ui.world.geo.Kit

/*
 * Rider kits. Index 0 is the player's own red/white kit; 1..23 are the teams of the race roster
 * (core/race/Tour2026), read from it in roster order (kit = team index + 1) so the 3D kits and the
 * UI's team swatches always agree; then the leader jerseys. Plain colours only: no names, logos or
 * sponsors are drawn, and the kits carry generic names.
 */
object Kits {
    private fun k(name: String, jersey: Int, accent: Int, shorts: Int, helmet: Int, bike: Int) =
        Kit(name, Col.hex(jersey), Col.hex(accent), Col.hex(shorts), Col.hex(helmet), Col.hex(bike))

    /**
     * A team kit from the roster's three colours: the jersey base, the second colour for shorts and
     * bike (the roster's "sleeves, shorts"), the small accent on the hem, yoke, side panels, cuffs and
     * leg bands. The helmet matches the jersey, as most teams' do.
     */
    fun team(t: Team): Kit = k("Équipe ${t.index + 1}", t.primaryRgb, t.accentRgb, t.secondaryRgb, t.primaryRgb, t.secondaryRgb)

    /** Index 0 is the player (red/white), then the 23 roster teams, then the leader jerseys. */
    val all: List<Kit> = buildList {
        add(k("Wattwerk", 0xD42027, 0xF5F5F5, 0x1A1A1A, 0xF5F5F5, 0xC81E24))
        val teams = Tour2026.teams.sortedBy { it.index }
        require(teams.size == TEAMS_END - 1) { "kit slots expect ${TEAMS_END - 1} teams, roster has ${teams.size}" }
        teams.forEach { add(team(it)) }
        // Leader jerseys: the team colour stays on shorts, helmet and bike in a real race; here they
        // carry neutral black so the jersey stands out.
        add(k("Maillot jaune", 0xFFD21F, 0xF2B705, 0x1A1A1A, 0xFFD21F, 0xF2C500))
        add(k("Maillot vert", 0x2BA84A, 0x1D7A35, 0x1A1A1A, 0x2BA84A, 0x1A1A1A))
        add(k("Maillot à pois", 0xF7F7F7, 0xD0101F, 0x1A1A1A, 0xF7F7F7, 0xF7F7F7))
        add(k("Maillot blanc", 0xF7F7F7, 0xDADADA, 0x1A1A1A, 0xF7F7F7, 0xF7F7F7))
    }

    const val PLAYER = 0
    /** Team kits: indices 1 until [TEAMS_END]; kit = roster team index + 1. */
    const val TEAMS_END = 24
    const val YELLOW = 24
    const val GREEN = 25
    const val POLKA = 26
    const val WHITE = 27

    /** Kit index of roster team [teamIndex] (Team.index). */
    fun forTeam(teamIndex: Int): Int = 1 + teamIndex
}
