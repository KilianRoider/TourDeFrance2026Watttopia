package app.rollentrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.RaceText
import app.rollentrainer.core.race.StageResult
import app.rollentrainer.core.race.StageStyle
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.ride.RaceRecord
import app.rollentrainer.ui.components.Panel
import app.rollentrainer.ui.components.Stat
import app.rollentrainer.ui.components.formatDuration
import kotlin.math.roundToInt

/** One line of a stage result as the tables show it, from the live race or from a stored record. */
data class ResultLine(
    val rank: Int,
    val name: String,
    /** "R. Evenepoel", for narrow tables. */
    val shortName: String,
    val team: String,
    val teamRgb: Int,
    val timeSec: Double,
    val gapSec: Double,
    val isUser: Boolean,
)

/** The top ten of a live result, then the rider's own line when he is further back. */
fun resultLines(result: StageResult): List<ResultLine> {
    val riders = Tour2026.roster.riders
    fun line(r: app.rollentrainer.core.race.ResultRow): ResultLine {
        val p = riders.getOrNull(r.riderId)
        return ResultLine(r.rank, r.name, p?.shortName ?: r.name, r.team, p?.team?.primaryRgb ?: 0x888888, r.timeSec, r.gapSec, r.isUser)
    }
    val top = result.rows.take(10).map(::line)
    val mine = result.user
    return if (mine != null && mine.rank > 10) top + line(mine) else top
}

fun resultLines(record: RaceRecord): List<ResultLine> =
    record.rows.map {
        ResultLine(it.rank, it.name, Tour2026.roster.byBib(it.bib)?.shortName ?: it.name, it.team, it.teamRgb, it.timeSec, it.gapSec, it.isUser)
    }

/**
 * The result table: place, team colour, rider (and team where there is room, else the short name), the
 * winner's time and the others' gaps; riders who finished in the winner's group get "gl. Zeit", as a
 * results sheet says. Sets its own text colours: a dialog's text slot would grey everything out.
 */
@Composable
fun RaceResultTable(lines: List<ResultLine>, modifier: Modifier = Modifier, showTeams: Boolean = true) {
    val text = MaterialTheme.colorScheme.onSurface
    val mine = MaterialTheme.colorScheme.onPrimaryContainer
    Column(modifier) {
        var last = 0
        lines.forEach { l ->
            if (l.rank > last + 1 && last > 0) {
                Text("…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
            }
            last = l.rank
            val strong = l.isUser
            Row(
                Modifier.fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (strong) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent)
                    .padding(horizontal = 6.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val color = if (strong) mine else text
                Text(
                    "${l.rank}.", style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End,
                    modifier = Modifier.width(34.dp), fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = color,
                )
                Spacer(Modifier.width(8.dp))
                // Outlined, so a black or white jersey still shows on a dark or light card.
                Box(
                    Modifier.size(10.dp).clip(RoundedCornerShape(2.dp)).background(rgb(l.teamRgb))
                        .border(0.5.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(2.dp)),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (showTeams) l.name else l.shortName, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (strong) FontWeight.Bold else FontWeight.Normal, color = color,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (showTeams) {
                    Text(
                        l.team, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(0.9f).padding(start = 8.dp),
                    )
                }
                Text(
                    when {
                        l.rank == 1 -> RaceText.clock(l.timeSec)
                        l.gapSec < 0.5 -> "gl. Zeit"
                        else -> RaceText.gap(l.gapSec)
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), textAlign = TextAlign.End,
                    modifier = Modifier.width(72.dp), maxLines = 1, color = color,
                )
            }
        }
    }
}

/**
 * For the end dialog of a pro-mode ride: across the line the place and the result (final for the
 * riders already in, which in a road race are all who can still beat the rider); before the line the
 * standing on the road and what ending now means.
 */
@Composable
fun RaceResultBlock(race: RaceState, pro: ProRider, modifier: Modifier = Modifier, showTeams: Boolean = false) {
    Column(modifier) {
        val result = race.result
        val mine = result?.user
        if (race.finished && result != null && mine != null) {
            Text(
                if (mine.rank == 1) "Etappensieg als ${pro.name}!" else "Platz ${mine.rank} von ${race.ridersInRace} als ${pro.name}",
                style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Zeit ${RaceText.clock(mine.timeSec)}" + if (mine.rank > 1) " · ${if (mine.gapSec < 0.5) "gleiche Zeit wie der Sieger" else RaceText.gap(mine.gapSec)}" else "",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            RaceResultTable(resultLines(result), Modifier.fillMaxWidth(), showTeams)
            if (!result.complete) {
                Spacer(Modifier.height(4.dp))
                Text(
                    if (race.style == StageStyle.ITT || race.style == StageStyle.TTT) "Vorläufig: es sind noch Fahrer auf der Strecke." else "Noch nicht alle im Ziel; die Wertung wird beim Beenden vervollständigt.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Text(
                "Platz ${race.rank} auf der Straße · " + (if (race.gapToHeadSec < 1) "an der Spitze" else "${RaceText.gap(race.gapToHeadSec)} auf die Spitze") +
                    " · noch ${RaceText.km(race.kmToGo)}",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                "Wenn du jetzt beendest, gibst du die Etappe als ${pro.shortName} auf.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The pro-mode result of a saved ride, for the ride's detail page. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RaceRecordPanel(record: RaceRecord, compact: Boolean, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth()) {
        val where = if (record.stageNumber > 0) "Etappe ${record.stageNumber}" + (if (record.variant.isNotBlank()) " · ${record.variant}" else "") else "Rennen"
        Text("Profi-Modus · $where", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Tour2026.roster.byBib(record.riderBib)?.let {
                TeamSwatch(it.team, size = 22.dp)
                Spacer(Modifier.width(10.dp))
            }
            Text(record.headline, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        }
        Text(record.teamName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 28.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Stat(if (record.finished) "Platz" else "Auf der Straße", "${record.rank} / ${record.riders}")
            if (record.finished) {
                record.timeSec?.let { Stat("Zeit", formatDuration(it.roundToInt())) }
                if (record.rank > 1) Stat("Rückstand", if (record.gapSec < 0.5) "gl. Zeit" else RaceText.gap(record.gapSec))
            } else {
                Stat("Gefahren", "${RaceText.km(record.kmRidden)} / ${RaceText.km(record.kmTotal)}")
            }
            Stat("Leistung", "× " + "%.2f".format(record.powerScale).replace('.', ','))
            Stat("FTP", "${record.userFtpW} → ${record.riderFtpW} W")
        }
        if (record.rows.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Text(if (record.finished || record.rows.size >= 10) "Etappenergebnis" else "Bereits im Ziel", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            // On a tablet a full-width table pulls the gaps far away from the names.
            RaceResultTable(resultLines(record), Modifier.widthIn(max = 720.dp).fillMaxWidth(), showTeams = !compact)
            if (!record.complete) {
                Text(
                    "Beim Beenden waren noch Fahrer unterwegs.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}
