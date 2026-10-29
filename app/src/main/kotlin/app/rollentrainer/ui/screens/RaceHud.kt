package app.rollentrainer.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceEvent
import app.rollentrainer.core.race.RaceEventKind
import app.rollentrainer.core.race.RaceGroup
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.RaceText
import app.rollentrainer.core.race.StageStyle
import app.rollentrainer.core.race.Tour2026
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/*
 * The race on top of the 3D road in pro mode: where the rider is in the race, the gaps, the draft and
 * the pro's power, the race radio as short German announcements, and the two buttons to change lanes in
 * the bunch. Self-contained: RouteRideScreen only places it (top right, under the clock).
 */

private val HudBg = Color(0xB3101820)
private val Dim = Color.White.copy(alpha = 0.7f)
private val Gold = Color(0xFFFFD230)
private val ProPower = Color(0xFFFF8A65)

/**
 * [race] null = the field is still being set up. [compact] is the phone variant (about 220 dp wide, also
 * fits a phone in landscape next to the closed metrics panel); the tablet variant is about 300 dp wide
 * and adds the groups on the road and a short log of the race radio. [finishing] shows that the result
 * is being computed after "Beenden". [maxHeight] caps the whole HUD (it must be finite): the card scrolls
 * within what the lane buttons leave, so they always stay on screen; the race radio gets what is left under them.
 */
@Composable
fun RaceHud(
    race: RaceState?,
    pro: ProRider,
    compact: Boolean,
    maxHeight: Dp,
    onLeft: () -> Unit,
    onRight: () -> Unit,
    modifier: Modifier = Modifier,
    finishing: Boolean = false,
) {
    val width = if (compact) 224.dp else 304.dp
    val lanes = race != null && !race.finished && !finishing && race.style != StageStyle.ITT && race.style != StageStyle.TTT
    // The lane buttons take 6 + 48 dp under the card.
    val cardMax = (maxHeight - if (lanes) 54.dp else 0.dp).coerceAtLeast(40.dp)
    val cardShape = RoundedCornerShape(12.dp)
    Column(modifier.width(width).heightIn(max = maxHeight), horizontalAlignment = Alignment.End) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = cardMax).clip(cardShape).background(HudBg, cardShape).verticalScroll(rememberScrollState())
                .padding(horizontal = if (compact) 10.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp),
        ) {
            RiderLine(pro, race, compact)
            Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
            when {
                finishing -> Waiting("Wertung wird berechnet\u00A0…")
                race == null -> Waiting("Das Feld stellt sich auf\u00A0…")
                race.finished -> FinishedBlock(race, compact)
                race.style == StageStyle.ITT || race.style == StageStyle.TTT -> TimeTrialBlock(race, compact)
                else -> RoadBlock(race, compact)
            }
        }
        if (lanes) {
            Spacer(Modifier.height(6.dp))
            LaneButtons(compact, onLeft, onRight)
        }
        // Weighted, so it is measured after the card and the buttons: it never moves them, it scrolls when short of room.
        if (race != null) {
            Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.End) {
                RaceRadio(race, compact)
            }
        }
    }
}

/** Who the rider is today and what he pushes as the pro. */
@Composable
private fun RiderLine(pro: ProRider, race: RaceState?, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        TeamSwatch(pro.team, size = if (compact) 14.dp else 18.dp)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (compact) pro.shortName else pro.name, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
            )
            if (!compact) Text(pro.team.name, color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (race != null && !race.finished) {
            Column(horizontalAlignment = Alignment.End) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(race.proPowerW.toString(), color = ProPower, fontWeight = FontWeight.SemiBold, fontSize = if (compact) 17.sp else 20.sp, lineHeight = 20.sp)
                    Text(" W", color = Dim, fontSize = 11.sp, lineHeight = 14.sp)
                }
                if (!compact) Text("Profi · ${"× %.2f".format(race.powerScale).replace('.', ',')}", color = Dim, fontSize = 10.sp, lineHeight = 12.sp)
            }
        }
    }
}

@Composable
private fun Waiting(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 4.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text(text, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}

/** "12 / 184" with the group beside it. */
@Composable
private fun RankRow(rank: Int, of: Int, compact: Boolean, right: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                rank.toString(), color = if (rank <= 3) Gold else Color.White, fontWeight = FontWeight.Bold,
                fontSize = if (compact) 26.sp else 32.sp, lineHeight = if (compact) 28.sp else 34.sp,
            )
            Text(" / $of", color = Dim, fontSize = 12.sp, lineHeight = 16.sp, modifier = Modifier.padding(bottom = 3.dp))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) { right() }
    }
}

/** The bunch label as a rider would say it: "Feld · 142", "Spitze · 3", "allein" for one rider. */
private fun groupText(g: RaceGroup?): String = when {
    g == null -> "–"
    // One rider on his own: the rider himself is "allein", anyone else is named, as race radio would.
    g.size == 1 && g.containsUser -> "allein"
    g.size == 1 -> "${g.label}: ${Tour2026.roster.riders.getOrNull(g.riderIds.first())?.shortName ?: "1 Fahrer"}"
    else -> "${g.label} · ${g.size}"
}

@Composable
private fun RoadBlock(race: RaceState, compact: Boolean) {
    val group = race.userGroupInfo
    RankRow(race.rank, race.ridersInRace, compact) {
        Text(groupText(group), color = Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            if (race.gapToHeadSec < 1) "an der Spitze" else "Spitze ${RaceText.gap(race.gapToHeadSec)}",
            color = if (race.gapToHeadSec < 1) Gold else Dim, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        )
    }
    Spacer(Modifier.height(if (compact) 4.dp else 6.dp))
    DraftBar(race.draftReduction, compact)
    val ahead = race.gapToGroupAheadSec
    val behind = race.gapToGroupBehindSec
    val groups = race.groups
    val gi = race.userGroup
    if (compact) {
        if (ahead != null || behind != null) {
            Spacer(Modifier.height(4.dp))
            Row {
                Text(ahead?.let { "▲ ${RaceText.gap(it)}" } ?: "", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                Text(behind?.let { "▼ ${RaceText.clock(it)}" } ?: "", color = Dim, style = MaterialTheme.typography.labelMedium)
            }
        }
        Spacer(Modifier.height(2.dp))
        Text("noch ${RaceText.km(race.kmToGo)} · ${race.phase.title}", color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    } else {
        Spacer(Modifier.height(6.dp))
        if (ahead != null) Line("Gruppe vor dir", "${RaceText.gap(ahead)} · ${groupText(groups.getOrNull(gi - 1))}")
        if (behind != null) Line("Gruppe hinter dir", "${RaceText.clock(behind)} · ${groupText(groups.getOrNull(gi + 1))}")
        Line("Noch", RaceText.km(race.kmToGo))
        Line("Rennen", race.phase.title)
        if (groups.size > 1) {
            Spacer(Modifier.height(6.dp))
            GroupsOnRoad(groups, gi)
        }
    }
}

/** The draft as a bar: 0 alone in the wind, 50 % deep in the bunch. */
@Composable
private fun DraftBar(draft: Double, compact: Boolean) {
    val pct = (draft * 100).roundToInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Windschatten", color = Dim, style = MaterialTheme.typography.labelSmall, maxLines = 1, softWrap = false, modifier = Modifier.width(if (compact) 84.dp else 96.dp))
        Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.15f))) {
            Box(Modifier.fillMaxWidth((draft / 0.5).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Color(0xFF4FC3F7)))
        }
        Text("$pct %", color = Color.White, style = MaterialTheme.typography.labelMedium, modifier = Modifier.width(40.dp).padding(start = 6.dp), maxLines = 1)
    }
}

@Composable
private fun Line(label: String, value: String, color: Color = Color.White) {
    Row(Modifier.fillMaxWidth().padding(vertical = 1.dp)) {
        Text(label, color = Dim, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, color = color, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The race as groups on the road, front first; at most five lines around the rider's own. */
@Composable
private fun GroupsOnRoad(groups: List<RaceGroup>, mine: Int) {
    val from = (mine - 2).coerceIn(0, (groups.size - 5).coerceAtLeast(0))
    val shown = groups.drop(from).take(5)
    Column(Modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.06f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) {
        if (from > 0) Text("…", color = Dim, fontSize = 11.sp, lineHeight = 12.sp)
        shown.forEach { g ->
            val me = g.index == mine
            Row(Modifier.fillMaxWidth()) {
                Text(
                    (if (me) "▶ " else "") + groupText(g), color = if (me) Gold else Color.White, fontSize = 12.sp, lineHeight = 16.sp,
                    fontWeight = if (me) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(if (g.index == 0) "" else RaceText.gap(g.gapSec), color = Dim, fontSize = 12.sp, lineHeight = 16.sp)
            }
        }
        if (from + shown.size < groups.size) Text("… ${groups.size - from - shown.size} weitere", color = Dim, fontSize = 11.sp, lineHeight = 12.sp)
    }
}

@Composable
private fun TimeTrialBlock(race: RaceState, compact: Boolean) {
    val team = race.style == StageStyle.TTT
    RankRow(race.rank, race.ridersInRace, compact) {
        Text(if (team) "Mannschaftszeitfahren" else "Zeitfahren", color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            when {
                race.gapToHeadSec < 0.5 -> "Bestzeit"
                compact -> "Bestzeit ${RaceText.gap(race.gapToHeadSec)}"
                else -> "${RaceText.gap(race.gapToHeadSec)} auf Bestzeit"
            },
            color = if (race.gapToHeadSec < 0.5) Gold else Dim, style = MaterialTheme.typography.labelMedium, maxLines = 1,
        )
    }
    Spacer(Modifier.height(4.dp))
    if (team) DraftBar(race.draftReduction, compact)
    Spacer(Modifier.height(2.dp))
    Text(
        "Zeit ${RaceText.clock(race.raceTimeSec)} · noch ${RaceText.km(race.kmToGo)}", color = Dim,
        style = if (compact) MaterialTheme.typography.labelSmall else MaterialTheme.typography.labelMedium, maxLines = 1,
    )
}

@Composable
private fun FinishedBlock(race: RaceState, compact: Boolean) {
    val mine = race.result?.user
    val rank = mine?.rank ?: race.rank
    RankRow(rank, race.ridersInRace, compact) {
        Text(if (rank == 1) "Etappensieg!" else "Im Ziel", color = if (rank == 1) Gold else Color.White, fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.labelLarge)
        // The winner has no gap to show: his stage time instead, as on the podium lines.
        mine?.let {
            Text(
                if (it.rank == 1) RaceText.clock(it.timeSec) else if (it.gapSec < 0.5) "gleiche Zeit" else RaceText.gap(it.gapSec),
                color = Dim, style = MaterialTheme.typography.labelMedium,
            )
        }
    }
    val result = race.result
    if (result != null && !compact) {
        Spacer(Modifier.height(6.dp))
        result.rows.take(3).forEach { r ->
            Line("${r.rank}. ${r.name}", if (r.rank == 1) RaceText.clock(r.timeSec) else if (r.gapSec < 0.5) "gl. Zeit" else RaceText.gap(r.gapSec), if (r.isUser) Gold else Color.White)
        }
    }
    if (result != null && !result.complete) {
        Spacer(Modifier.height(2.dp))
        Text("Die anderen kommen noch an\u00A0…", color = Dim, style = MaterialTheme.typography.labelSmall)
    }
}

/** Lane change in the bunch; the core holds the new lane for a few seconds. Finger-sized on every screen. */
@Composable
private fun LaneButtons(compact: Boolean, onLeft: () -> Unit, onRight: () -> Unit) {
    val pad = PaddingValues(horizontal = if (compact) 10.dp else 14.dp)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onLeft, contentPadding = pad, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (compact) "◀ links" else "◀ nach links", maxLines = 1)
        }
        FilledTonalButton(onClick = onRight, contentPadding = pad, modifier = Modifier.heightIn(min = 48.dp)) {
            Text(if (compact) "rechts ▶" else "nach rechts ▶", maxLines = 1)
        }
    }
}

/** Colour of an announcement: attacks and sprints stand out, the rider's own moves in the app's accent. */
private fun eventColor(e: RaceEvent): Color = when (e.kind) {
    RaceEventKind.ATTACK, RaceEventKind.BREAKAWAY -> Color(0xFFFF8A65)
    RaceEventKind.SPRINT, RaceEventKind.LEADOUT -> Color(0xFFEF5350)
    RaceEventKind.FINISH -> Gold
    RaceEventKind.USER -> Color(0xFFFF5A1F)
    RaceEventKind.CLIMB -> Color(0xFFAED581)
    RaceEventKind.CATCH, RaceEventKind.CONTROL -> Color(0xFF81D4FA)
    else -> Color.White
}

/**
 * Race radio: the newest announcement for a few seconds as a toast under the card; the tablet keeps the
 * last three as a short log. The state holds every announcement for one tick only, so the toast follows
 * the newest of the recent ones.
 */
@Composable
private fun RaceRadio(race: RaceState, compact: Boolean) {
    val latest = race.recentEvents.lastOrNull()
    var shown by remember { mutableStateOf<RaceEvent?>(null) }
    LaunchedEffect(latest) {
        if (latest == null) return@LaunchedEffect
        shown = latest
        delay(TOAST_MS)
        if (shown == latest) shown = null
    }
    val toast = shown
    AnimatedVisibility(toast != null, enter = fadeIn(), exit = fadeOut()) {
        val e = toast ?: latest
        if (e != null) {
            Box(
                Modifier.padding(top = 6.dp).background(Color(0xE6101820), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(eventColor(e)))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        e.text, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        style = if (compact) MaterialTheme.typography.labelLarge else MaterialTheme.typography.titleSmall,
                    )
                }
            }
        }
    }
    if (!compact) {
        val log = race.recentEvents.dropLast(if (toast != null) 1 else 0).takeLast(3).reversed()
        if (log.isNotEmpty()) {
            Column(Modifier.padding(top = 6.dp).width(LOG_WIDTH).background(Color(0x80101820), RoundedCornerShape(10.dp)).padding(horizontal = 10.dp, vertical = 6.dp)) {
                log.forEach { e ->
                    Text(e.text, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

private const val TOAST_MS = 6000L
private val LOG_WIDTH: Dp = 304.dp
