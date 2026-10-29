package app.rollentrainer.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RiderRole
import app.rollentrainer.core.race.Roster
import app.rollentrainer.core.race.Team
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.ui.components.Panel
import java.text.Normalizer
import kotlin.math.roundToInt

/*
 * Pro mode ("today I am Tadej Pogačar"): the switch and the chosen pro on the Tour list, and the picker
 * with the 184 riders of the 2026 Tour. The rider's power is multiplied by the pro's FTP over his own,
 * so the factor is shown wherever a pro is offered.
 */

/** Pro FTP over the rider's, as the race uses it ("× 2,18"). */
fun proFactor(rider: ProRider, userFtp: Int): Double = rider.ftpW.toDouble() / userFtp.coerceAtLeast(50)

/** "× 2,18", with a no-break space so a wrapped line never leaves the sign behind. */
fun proFactorText(rider: ProRider, userFtp: Int): String = "×\u00A0" + "%.2f".format(proFactor(rider, userFtp)).replace('.', ',')

private fun decimal(v: Double, digits: Int = 1): String = "%.${digits}f".format(v).replace('.', ',')

/** 0xRRGGBB from the roster as a Compose colour. */
fun rgb(c: Int): Color = Color(0xFF000000.toInt() or (c and 0xFFFFFF))

/**
 * A tiny jersey in the team's colours: body in the primary colour, a sleeve band in the second one and
 * the accent as a chest stripe. Enough to tell 23 teams apart in a list without logos.
 */
@Composable
fun TeamSwatch(team: Team, modifier: Modifier = Modifier, size: Dp = 22.dp) {
    Box(
        modifier
            .size(width = size * 1.25f, height = size)
            .clip(RoundedCornerShape(size / 5))
            .background(rgb(team.primaryRgb))
            .border(1.dp, Color.White.copy(alpha = 0.35f), RoundedCornerShape(size / 5)),
    ) {
        Box(Modifier.fillMaxWidth().height(size * 0.28f).align(Alignment.BottomCenter).background(rgb(team.secondaryRgb)))
        Box(Modifier.fillMaxWidth().height(size * 0.14f).align(Alignment.Center).background(rgb(team.accentRgb)))
    }
}

/** The switch and the chosen pro, at the top of the Tour list. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProModePanel(
    enabled: Boolean,
    rider: ProRider,
    userFtp: Int,
    onEnabled: (Boolean) -> Unit,
    onChoose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Panel(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable { onEnabled(!enabled) }, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Profi-Modus", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Fahr die Etappe als Profi im Feld der Tour 2026: 184 Fahrer, Windschatten, Ausreißer, Attacken am Berg und Sprintzüge im Finale.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.width(12.dp))
            Switch(enabled, onEnabled)
        }
        if (!enabled) return@Panel
        Spacer(Modifier.height(12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val narrow = maxWidth < 420.dp
            val factor: @Composable () -> Unit = {
                Column(horizontalAlignment = if (narrow) Alignment.Start else Alignment.End) {
                    Text("Deine Leistung", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(proFactorText(rider, userFtp), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
                    Text("$userFtp W → ${rider.ftpW} W FTP", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TeamSwatch(rider.team, size = 30.dp)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Heute bist du ${rider.name}", style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text("${rider.team.name} · ${rider.role.title}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Nr. ${rider.bib} · ${rider.weightKg.roundToInt()} kg · FTP ${rider.ftpW} W · ${decimal(rider.ftpWkg)} W/kg",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!narrow) {
                        Spacer(Modifier.width(12.dp))
                        factor()
                    }
                }
                Spacer(Modifier.height(10.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (narrow) factor()
                    OutlinedButton(onClick = onChoose, modifier = Modifier.align(Alignment.CenterVertically)) { Text("Fahrer wählen") }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "Deine Watt werden mit ${rider.ftpW} / $userFtp multipliziert; Gewicht, Luftwiderstand und Windschatten sind die des Profis. " +
                        "Der Trainer stellt die Steigung wie gewohnt, im Windschatten wird es leichter. Mit ◀ ▶ wechselst du im Feld die Spur, " +
                        "mit mehr Watt fährst du nach vorn.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** The picker as a full-screen dialog (a centred sheet on a tablet). */
@Composable
fun ProRiderPickerDialog(selectedBib: Int, userFtp: Int, onPick: (ProRider) -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val sheet = maxWidth >= 720.dp
            Surface(
                modifier = if (sheet) Modifier.widthIn(max = 760.dp).fillMaxHeight(0.92f) else Modifier.fillMaxSize(),
                shape = if (sheet) RoundedCornerShape(20.dp) else RoundedCornerShape(0.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                ProRiderPicker(Tour2026.roster, selectedBib, userFtp, onPick, onDismiss)
            }
        }
    }
}

private val ROLE_FILTERS: List<Pair<RiderRole?, String>> = listOf(
    null to "Alle",
    RiderRole.GC to "Klassement",
    RiderRole.SPRINTER to "Sprinter",
    RiderRole.CLIMBER to "Bergfahrer",
    RiderRole.PUNCHEUR to "Puncheure",
    RiderRole.TT to "Zeitfahrer",
    RiderRole.ROULEUR to "Rouleure",
    RiderRole.DOMESTIQUE to "Helfer",
)

/** Lower case without accents, so "pogacar" finds Pogačar and "skujins" Skujiņš. */
internal fun searchKey(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/** One entry of the list: a team header or a rider. */
private sealed interface PickerItem {
    val key: String
    class Header(val team: Team, val count: Int) : PickerItem { override val key = "t${team.index}" }
    class Rider(val rider: ProRider) : PickerItem { override val key = "r${rider.id}" }
}

/**
 * The 184 riders grouped by team in start-list order, with name search (also by team, nation or bib)
 * and role filters. Each row shows what matters for the choice: role, weight, FTP, W/kg and what the
 * rider's own power would be multiplied by.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProRiderPicker(
    roster: Roster,
    selectedBib: Int,
    userFtp: Int,
    onPick: (ProRider) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    initialQuery: String = "",
) {
    var query by rememberSaveable { mutableStateOf(initialQuery) }
    var roleName by rememberSaveable { mutableStateOf<String?>(null) }
    val role = roleName?.let { n -> RiderRole.entries.firstOrNull { it.name == n } }
    val items = remember(roster, query, role) {
        val q = searchKey(query.trim())
        val matching = roster.riders.filter { r ->
            (role == null || r.role == role) &&
                (q.isEmpty() || searchKey(r.name).contains(q) || searchKey(r.team.name).contains(q) ||
                    r.nation.lowercase() == q || r.bib.toString() == q)
        }
        matching.groupBy { it.team.index }.toSortedMap().flatMap { (_, riders) ->
            listOf<PickerItem>(PickerItem.Header(riders.first().team, riders.size)) + riders.sortedBy { it.bib }.map { PickerItem.Rider(it) }
        }
    }
    // Opens at the current choice's team (its header on top), so changing from Pogačar to his team mate is one tap.
    val start = remember(roster) {
        val team = roster.byBib(selectedBib)?.team?.index
        var index = 0
        for ((t, rs) in roster.riders.groupBy { it.team.index }.toSortedMap()) {
            if (t == team) break
            index += 1 + rs.size
        }
        if (team == null) 0 else index
    }
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = if (initialQuery.isEmpty()) start else 0)
    BoxWithConstraints(modifier.fillMaxSize()) {
        val compact = maxWidth < 600.dp
        val pad = if (compact) 16.dp else 24.dp
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(start = pad, end = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Wer bist du heute?", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "Deine FTP: $userFtp W · deine Leistung wird mit dem Faktor rechts multipliziert",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                placeholder = { Text(if (compact) "Name, Team oder Nummer" else "Name, Team, Nation oder Startnummer", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 8.dp),
            )
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = pad), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ROLE_FILTERS.forEach { (r, label) ->
                    FilterChip(selected = role == r, onClick = { roleName = r?.name }, label = { Text(label) })
                }
            }
            Spacer(Modifier.height(4.dp))
            if (items.isEmpty()) {
                Text(
                    "Kein Fahrer gefunden.", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = pad, vertical = 24.dp),
                )
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(items, key = { it.key }) { item ->
                    when (item) {
                        is PickerItem.Header -> TeamHeader(item.team, item.count, pad)
                        is PickerItem.Rider -> RiderRow(item.rider, item.rider.bib == selectedBib, userFtp, compact, pad) { onPick(item.rider) }
                    }
                }
            }
        }
    }
}

@Composable
private fun TeamHeader(team: Team, count: Int, pad: Dp) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = pad, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TeamSwatch(team, size = 18.dp)
        Spacer(Modifier.width(10.dp))
        Text(team.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (count == 8) team.code else "${team.code} · $count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RiderRow(rider: ProRider, selected: Boolean, userFtp: Int, compact: Boolean, pad: Dp, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent
    val sub = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().background(bg).clickable(onClick = onClick).padding(horizontal = pad, vertical = if (compact) 8.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            rider.bib.toString(), style = MaterialTheme.typography.labelLarge, color = sub,
            modifier = Modifier.width(if (compact) 32.dp else 40.dp),
        )
        if (compact) {
            Column(Modifier.weight(1f)) {
                Text(rider.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // Two short lines rather than one cut off: on a phone the W/kg would be the part lost.
                Text("${rider.role.title} · ${rider.nation}", style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${rider.weightKg.roundToInt()} kg · ${rider.ftpW} W · ${decimal(rider.ftpWkg)} W/kg",
                    style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"), color = sub, maxLines = 1,
                )
            }
        } else {
            Column(Modifier.weight(1.6f)) {
                Text(rider.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${rider.role.title} · ${rider.nation}", style = MaterialTheme.typography.bodySmall, color = sub, maxLines = 1)
            }
            Cell("${rider.weightKg.roundToInt()} kg", Modifier.weight(0.55f))
            Cell("${rider.ftpW} W", Modifier.weight(0.6f))
            Cell("${decimal(rider.ftpWkg)} W/kg", Modifier.weight(0.75f))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            proFactorText(rider, userFtp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (compact) 52.dp else 60.dp), maxLines = 1,
        )
        Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            if (selected) Icon(Icons.Default.Check, contentDescription = "Gewählt", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun Cell(text: String, modifier: Modifier) {
    Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontFeatureSettings = "tnum"), modifier = modifier, maxLines = 1)
}

/** Lets a caller (the stage list) start with the remembered pro, falling back to the first rider of the list. */
fun proRiderByBib(bib: Int): ProRider = Tour2026.roster.byBib(bib) ?: Tour2026.roster.riders.first()

/** Button for a stage card in pro mode: "Als T. Pogačar". */
fun proStartLabel(rider: ProRider): String = "Als ${rider.shortName}"
