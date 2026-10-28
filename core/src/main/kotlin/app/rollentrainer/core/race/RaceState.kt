package app.rollentrainer.core.race

import app.rollentrainer.core.route.RouteState
import kotlin.math.abs
import kotlin.math.roundToInt

/** Stage types the race knows; from [app.rollentrainer.core.route.StageInfo.type] or the route's terrain. */
enum class StageStyle { FLAT, HILLY, MOUNTAIN, ITT, TTT }

/** Where the race stands, for the HUD and the tactics. */
enum class RacePhase(val title: String) {
    ROLL_OUT("Start"),
    BREAK_FORMING("Attacken"),
    CONTROL("Feld kontrolliert"),
    CHASE("Verfolgung"),
    FINALE("Finale"),
    SPRINT("Sprint"),
    TIME_TRIAL("Zeitfahren"),
    FINISHED("Ziel"),
}

enum class RaceEventKind { ATTACK, BREAKAWAY, GAP, CATCH, CONTROL, CLIMB, LEADOUT, SPRINT, KM, DROPPED, SPLIT, SPLIT_TIME, FINISH, USER }

/** Something the HUD announces; texts are German. */
data class RaceEvent(
    val timeSec: Double,
    /** Distance of the head of the race (or of the user for his own events) when it happened. */
    val distanceM: Double,
    val kind: RaceEventKind,
    val text: String,
    val riderIds: List<Int> = emptyList(),
)

/** A group on the road, from the front of the race backwards. */
data class RaceGroup(
    /** 0 = head of the race. */
    val index: Int,
    val size: Int,
    val frontDistanceM: Double,
    val rearDistanceM: Double,
    /** Time behind the head of the race at the front of this group. */
    val gapSec: Double,
    val speedMps: Double,
    /** "Spitze", "Ausreißer", "Feld", "Verfolger", "Gruppetto", "Abgehängt". */
    val label: String,
    val containsUser: Boolean,
    /** Rider ids, front first. */
    val riderIds: List<Int>,
)

data class ResultRow(
    val rank: Int,
    val riderId: Int,
    val name: String,
    val team: String,
    /** Stage time; riders finishing in the same group get the time of its first rider. */
    val timeSec: Double,
    val gapSec: Double,
    val isUser: Boolean,
)

data class StageResult(val rows: List<ResultRow>, val complete: Boolean) {
    val winner: ResultRow? get() = rows.firstOrNull()
    val user: ResultRow? get() = rows.firstOrNull { it.isUser }
}

/**
 * The race after a tick. [route] is what [app.rollentrainer.core.route.RouteEngine] would report for the
 * user, so a ride session can keep its recording, trainer grade and virtual gearing unchanged.
 */
data class RaceState(
    val route: RouteState,
    val phase: RacePhase,
    val style: StageStyle,
    /** Race clock; for a time trial the user's own time since his start. */
    val raceTimeSec: Double,
    /** The pro's power: the user's power times [powerScale]. */
    val proPowerW: Int,
    val powerScale: Double,
    /** Drag saved by drafting right now, 0..0.5. */
    val draftReduction: Double,
    /** 1 = first on the road (or fastest so far in a time trial). */
    val rank: Int,
    val ridersInRace: Int,
    val gapToHeadSec: Double,
    val gapToGroupAheadSec: Double?,
    val gapToGroupBehindSec: Double?,
    /** Index in [groups] of the user's group. */
    val userGroup: Int,
    val groups: List<RaceGroup>,
    /** Announced during this tick. */
    val newEvents: List<RaceEvent>,
    /** The last few announcements, newest last. */
    val recentEvents: List<RaceEvent>,
    val kmToGo: Double,
    val finished: Boolean,
    val result: StageResult?,
) {
    val userGroupInfo: RaceGroup? get() = groups.getOrNull(userGroup)
}

/**
 * Per-rider data for drawing the field, one entry per roster id, refreshed every tick; meant for
 * instanced rendering. Copy it with [RaceEngine.copyFrame] into a buffer the renderer owns.
 */
class RiderFrame(val size: Int) {
    /** Race time of this frame; extrapolate with the speeds for frames in between ticks. */
    var timeSec: Double = 0.0
    val distanceM = DoubleArray(size)
    /** Offset from the centre line, metres, positive to the right in the direction of travel. */
    val lateralM = FloatArray(size)
    val speedMps = FloatArray(size)
    val lateralSpeedMps = FloatArray(size)
    val cadenceRpm = FloatArray(size)
    /** Crank angle in radians, integrated from the cadence, so legs move smoothly. */
    val crankRad = FloatArray(size)
    /** Bike lean in radians from lane changes (positive = leaning right); add the road's curvature. */
    val leanRad = FloatArray(size)
    val flags = IntArray(size)

    fun copyInto(dst: RiderFrame) {
        dst.timeSec = timeSec
        distanceM.copyInto(dst.distanceM)
        lateralM.copyInto(dst.lateralM)
        speedMps.copyInto(dst.speedMps)
        lateralSpeedMps.copyInto(dst.lateralSpeedMps)
        cadenceRpm.copyInto(dst.cadenceRpm)
        crankRad.copyInto(dst.crankRad)
        leanRad.copyInto(dst.leanRad)
        flags.copyInto(dst.flags)
    }

    companion object {
        /** On the road (not yet started in a time trial or rolled out after the line = not drawn). */
        const val VISIBLE = 1
        const val USER = 2
        /** Out of the saddle: attacks, sprints, steep ramps. */
        const val STANDING = 4
        const val SPRINTING = 8
        /** Not pedalling. */
        const val COASTING = 16
        const val FINISHED = 32
        /** Pulling at the front of a group. */
        const val PULLING = 64
        /** Time trial bike and position. */
        const val TT_BIKE = 128
    }
}

/** German time formats for the HUD. */
object RaceText {
    /** 200 → "3:20", 3725 → "1:02:05". */
    fun clock(sec: Double): String {
        val s = abs(sec).roundToInt()
        val h = s / 3600
        val m = (s % 3600) / 60
        val r = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, r) else "%d:%02d".format(m, r)
    }

    /** "+0:12" or "−0:05". */
    fun gap(sec: Double): String = (if (sec < 0) "−" else "+") + clock(sec)

    /** 4.5 → "4,5 km", 12.0 → "12 km". */
    fun km(km: Double): String =
        if (km >= 10 || abs(km - km.roundToInt()) < 0.05) "${km.roundToInt()} km" else "%.1f km".format(km).replace('.', ',')
}
