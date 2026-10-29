package app.rollentrainer.ride

import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceState
import app.rollentrainer.core.race.RiderFrame
import app.rollentrainer.core.race.StageStyle
import app.rollentrainer.core.route.Physics
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.ui.world.geo.RaceRider
import kotlin.math.roundToInt

/**
 * The field around the rider as the 3D world wants it: [riders] in the renderer's terms (lateral offset
 * positive to the LEFT, the race core's is positive to the right) and the rider's own lateral offset
 * for FrameState.lateralM. Immutable, so the GL thread can read it while the next one is built.
 */
class PelotonView(val riders: List<RaceRider>, val userLateralM: Double)

/**
 * Turns the race core's per-rider frame into [PelotonView]s. The ride loop copies the frame after every
 * race tick ([update], on the race's worker thread); the screen asks for a view once per ride state
 * ([view], main thread). Both go through [lock], so a view never mixes two ticks.
 */
internal class RaceFeed(private val riders: List<ProRider>) {
    private val lock = Any()
    private val frame = RiderFrame(riders.size)
    private var hasFrame = false

    fun update(copy: (RiderFrame) -> Unit) = synchronized(lock) {
        copy(frame)
        hasFrame = true
    }

    /**
     * Riders on the road between [behindM] behind and [aheadM] ahead of the rider; the renderer culls far
     * riders itself, but a break five minutes up the road must not pile up at the end of a world section.
     * Distances are shifted by [offsetM] into the caller's frame (the section start of the current world).
     */
    fun view(offsetM: Double, behindM: Double, aheadM: Double): PelotonView? = synchronized(lock) {
        if (!hasFrame) return null
        var user = -1
        for (i in 0 until frame.size) if (frame.flags[i] and RiderFrame.USER != 0) { user = i; break }
        val ud = if (user >= 0) frame.distanceM[user] else 0.0
        val list = ArrayList<RaceRider>(64)
        for (i in 0 until frame.size) {
            val f = frame.flags[i]
            if (i == user || f and RiderFrame.VISIBLE == 0) continue
            val d = frame.distanceM[i]
            if (d < ud - behindM || d > ud + aheadM) continue
            list += RaceRider(
                id = i,
                distanceM = d - offsetM,
                lateralM = -frame.lateralM[i].toDouble(),
                speedMps = frame.speedMps[i].toDouble(),
                cadenceRpm = if (f and RiderFrame.COASTING != 0) 0.0 else frame.cadenceRpm[i].toDouble(),
                kit = kitOf(riders[i]),
                standing = if (f and (RiderFrame.STANDING or RiderFrame.SPRINTING) != 0) 1f else 0f,
            )
        }
        PelotonView(list, if (user >= 0) -frame.lateralM[user].toDouble() else Double.NaN)
    }

    companion object {
        /** Kits of the 3D world: index 0 is the player, the teams follow in roster order (ui/world/geo/race/Kits). */
        fun kitOf(r: ProRider): Int = r.team.index + 1
    }
}

/**
 * Demo mode only (CI screenshots): rides the user's pro in the main bunch, a few rows behind its front,
 * instead of the simulated trainer's fixed watts, which would carry a scaled-up Pogačar off the front.
 * Returns the power to feed to the race as if measured on the trainer (the race scales it up again).
 */
internal class BunchFollower(private val rider: ProRider, private val powerScale: Double) {
    fun powerW(state: RaceState?): Int {
        val st = state ?: return (rider.ftpW * 0.6 / powerScale).roundToInt()
        val r = st.route
        if (st.style == StageStyle.ITT) return (rider.ftpW * 0.95 / powerScale).roundToInt()
        // In a team time trial the rider's own team; on the road the biggest group, the bunch.
        val group = (if (st.style == StageStyle.TTT) st.userGroupInfo else st.groups.maxByOrNull { it.size })
            ?: return (rider.ftpW * 0.7 / powerScale).roundToInt()
        // A third of the way back in a big bunch: sheltered, with riders all around for the picture.
        val length = group.frontDistanceM - group.rearDistanceM
        val target = group.frontDistanceM - (if (group.size > 20) (length * 0.35).coerceIn(10.0, 60.0) else 6.0)
        val error = target - r.distanceM
        val want = (group.speedMps + (error * 0.04).coerceIn(-2.5, 2.5)).coerceAtLeast(1.0)
        val params = RiderParams(totalMassKg = rider.weightKg + RiderParams.BIKE_KG, cda = rider.cda * (1 - st.draftReduction))
        val steady = Physics.resistance(want, r.gradePct, params) * want / params.drivetrainEfficiency
        // Speed up or slow down within about five seconds.
        val accel = params.totalMassKg * (want - r.speedMps) / 5.0 * maxOf(r.speedMps, 1.0)
        val pro = (steady + accel).coerceIn(0.0, rider.ftpW * 1.6)
        return (pro / powerScale).roundToInt()
    }
}
