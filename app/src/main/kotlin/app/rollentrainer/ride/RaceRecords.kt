package app.rollentrainer.ride

import android.content.Context
import app.rollentrainer.core.model.AppJson
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.RaceText
import app.rollentrainer.core.race.StageResult
import app.rollentrainer.core.race.StageStyle
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.core.route.Route
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.File
import kotlin.math.roundToInt

/** One line of a stored stage result. */
@Serializable
data class RecordRow(
    val rank: Int,
    val bib: Int,
    val name: String,
    val team: String,
    /** Jersey colour 0xRRGGBB, so the history can show the team without the roster. */
    val teamRgb: Int,
    val timeSec: Double,
    val gapSec: Double,
    val isUser: Boolean,
)

/**
 * A pro-mode ride's outcome, kept beside the saved ride (the core's Ride has no place for it): which pro
 * the rider was, where he finished, and the top of the stage result. The one-line summary also goes
 * into the ride's notes, so it survives on its own and reaches Strava with the description.
 */
@Serializable
data class RaceRecord(
    val rideId: String,
    val routeId: String,
    val stageNumber: Int = 0,
    /** The cut ridden ("Finale 30 km"), empty for the whole stage. */
    val variant: String = "",
    val riderBib: Int,
    val riderName: String,
    val teamName: String,
    val riderFtpW: Int,
    val userFtpW: Int,
    val powerScale: Double,
    /** [StageStyle] name. */
    val style: String,
    /** Crossed the line; otherwise the ride ended on the road. */
    val finished: Boolean,
    /** Final place, or the place on the road when the ride ended early. */
    val rank: Int,
    val riders: Int,
    /** Behind the winner when finished, behind the head of the race otherwise. */
    val gapSec: Double,
    val timeSec: Double? = null,
    val kmRidden: Double,
    val kmTotal: Double,
    /** The top ten, then the rider's own line when he is not among them. */
    val rows: List<RecordRow> = emptyList(),
    /** False when riders were still on the road when the result was taken. */
    val complete: Boolean = true,
    val seed: Long = 0,
) {
    val isTimeTrial: Boolean get() = style == StageStyle.ITT.name || style == StageStyle.TTT.name
    val winner: RecordRow? get() = rows.firstOrNull { it.rank == 1 }

    /** "Etappensieg als Tadej Pogačar", "Platz 37 von 184 als …", "Aufgegeben nach 50 von 200 km als …". */
    val headline: String
        get() = when {
            !finished -> "Aufgegeben nach ${RaceText.km(kmRidden)} von ${RaceText.km(kmTotal)} als $riderName"
            rank == 1 -> "Etappensieg als $riderName!"
            else -> "Platz $rank von $riders als $riderName"
        }

    /** For the history list: "Etappe 12 · Platz 37 als Tadej Pogačar". */
    val historyLine: String
        get() {
            val where = if (stageNumber > 0) "Etappe $stageNumber · " else ""
            return where + when {
                !finished -> "aufgegeben bei km ${kmRidden.roundToInt()} als $riderName"
                rank == 1 -> "Etappensieg als $riderName"
                else -> "Platz $rank als $riderName"
            }
        }

    /** The line written into the ride's notes (and so into the Strava description). */
    val notesLine: String
        get() = buildString {
            append(NOTES_PREFIX).append(" als ").append(riderName).append(" (").append(teamName).append("): ")
            when {
                !finished -> append("aufgegeben nach ${RaceText.km(kmRidden)} von ${RaceText.km(kmTotal)}, Platz $rank auf der Straße")
                rank == 1 -> append("Etappensieg")
                else -> {
                    append("Platz $rank von $riders, ")
                    // A bunch sprint gives the whole bunch the winner's time.
                    if (gapSec < 0.5) append("gleiche Zeit wie ") else append(RaceText.gap(gapSec)).append(" auf ")
                    append(winner?.name ?: "den Sieger")
                }
            }
            append(" · Leistung × ").append("%.2f".format(powerScale).replace('.', ','))
        }

    companion object {
        const val NOTES_PREFIX = "Profi-Modus"

        /** The notes without the line written by pro mode, for showing next to the full result. */
        fun userNotes(notes: String): String =
            notes.lines().filterNot { it.startsWith(NOTES_PREFIX) }.joinToString("\n").trim()

        /** Builds the record from the race when the ride ends; [result] is the stage result to keep. */
        fun of(rideId: String, route: Route, rider: ProRider, userFtpW: Int, state: RaceState, result: StageResult, seed: Long): RaceRecord {
            val roster = Tour2026.roster
            fun row(r: app.rollentrainer.core.race.ResultRow): RecordRow {
                val p = roster.riders.getOrNull(r.riderId)
                return RecordRow(r.rank, p?.bib ?: 0, r.name, r.team, p?.team?.primaryRgb ?: 0x888888, r.timeSec, r.gapSec, r.isUser)
            }
            val top = result.rows.take(10).map(::row)
            val mine = result.user
            val rows = if (mine != null && mine.rank > 10) top + row(mine) else top
            val finished = state.finished && mine != null
            return RaceRecord(
                rideId = rideId,
                routeId = route.id,
                stageNumber = route.stage?.number ?: 0,
                variant = route.variant,
                riderBib = rider.bib,
                riderName = rider.name,
                teamName = rider.team.name,
                riderFtpW = rider.ftpW,
                userFtpW = userFtpW,
                powerScale = state.powerScale,
                style = state.style.name,
                finished = finished,
                rank = if (finished) mine!!.rank else state.rank,
                riders = state.ridersInRace,
                gapSec = if (finished) mine!!.gapSec else state.gapToHeadSec,
                timeSec = if (finished) mine!!.timeSec else null,
                kmRidden = state.route.distanceM / 1000,
                kmTotal = route.lengthKm,
                rows = rows,
                complete = result.complete,
                seed = seed,
            )
        }
    }
}

/**
 * The stored pro-mode results, one small JSON file per ride in files/race; all of them are loaded at
 * start (a season is a few dozen files), so the history and the stage list can show them without waiting.
 */
class RaceRecords(context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "race")
    private val _all = MutableStateFlow<Map<String, RaceRecord>>(emptyMap())
    /** By ride id. */
    val all: StateFlow<Map<String, RaceRecord>> = _all.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            dir.mkdirs()
            val loaded = dir.listFiles { f -> f.extension == "json" }.orEmpty()
                .mapNotNull { runCatching { AppJson.decodeFromString(RaceRecord.serializer(), it.readText()) }.getOrNull() }
                .associateBy { it.rideId }
            // A record saved while loading must not be lost.
            _all.update { loaded + it }
        }
    }

    fun find(rideId: String): RaceRecord? = _all.value[rideId]

    suspend fun save(record: RaceRecord) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        File(dir, "${record.rideId}.json").writeText(AppJson.encodeToString(RaceRecord.serializer(), record))
        _all.update { it + (record.rideId to record) }
    }

    suspend fun delete(rideId: String) = withContext(Dispatchers.IO) {
        File(dir, "$rideId.json").delete()
        _all.update { it - rideId }
    }
}
