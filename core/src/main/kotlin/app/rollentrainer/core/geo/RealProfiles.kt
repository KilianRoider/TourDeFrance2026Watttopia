package app.rollentrainer.core.geo

import app.rollentrainer.core.route.Climb
import app.rollentrainer.core.route.Landmark
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RoutePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater
import kotlin.math.abs

/**
 * Real elevation profiles of the built-in routes, measured by the geodata pipeline
 * (tools/geodata) along the real road. They ship as small resources of this module
 * (`app/rollentrainer/core/geo/profiles/<id>.bin`, ~1–20 KB each) so the profile is right even
 * without the large world packs. A route without such a resource keeps its hand-made profile.
 */
object RealProfiles {
    private const val DIR = "/app/rollentrainer/core/geo/profiles/"
    private val MAGIC = byteArrayOf(0x57, 0x57, 0x50, 0x52, 0x4F, 0x46, 0, 1) // "WWPROF\0\1"
    private val JSON = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class MetaLandmark(val m: Double, val label: String)

    @Serializable
    private data class MetaClimb(val name: String, val summitKm: Double, val summitM: Double? = null)

    @Serializable
    private data class Meta(
        val id: String = "",
        val officialKm: Double? = null,
        val pipeline: Int = 0,
        val hairpinsAtM: List<Double> = emptyList(),
        val landmarks: List<MetaLandmark>? = null,
        val climbs: List<MetaClimb>? = null,
    )

    /** Where a stage climb's summit lies on the real road (by climb name). */
    data class ClimbPosition(val name: String, val summitKm: Double, val summitM: Double?)

    /**
     * [landmarks] and [climbs], when present, are the route's landmark signs and stage climb
     * summits placed on the real road by the pipeline (tools/geodata/landmarks.py): moved to where
     * the place really is, renamed or dropped where the hand-made list was wrong.
     */
    class Profile(
        val id: String,
        val points: List<RoutePoint>,
        val officialKm: Double?,
        val hairpinsAtM: List<Double>,
        val landmarks: List<Landmark>? = null,
        val climbs: List<ClimbPosition>? = null,
    ) {
        val lengthM: Double get() = points.last().distanceM
    }

    fun load(id: String): Profile? {
        val bytes = RealProfiles::class.java.getResourceAsStream("$DIR$id.bin")?.use { it.readBytes() } ?: return null
        return parse(bytes)
    }

    fun parse(bytes: ByteArray): Profile {
        require(bytes.size > 8 && bytes.copyOfRange(0, 8).contentEquals(MAGIC)) { "not a profile resource" }
        val inf = Inflater()
        val body = try {
            inf.setInput(bytes, 8, bytes.size - 8)
            val out = ByteArrayOutputStream(bytes.size * 4)
            val buf = ByteArray(8192)
            while (!inf.finished()) {
                val k = inf.inflate(buf)
                if (k == 0 && inf.needsInput()) break
                out.write(buf, 0, k)
            }
            out.toByteArray()
        } finally {
            inf.end()
        }
        val bb = ByteBuffer.wrap(body).order(ByteOrder.LITTLE_ENDIAN)
        val n = bb.int
        val step = bb.float.toDouble()
        val length = bb.double
        var zc = bb.int.toLong()
        val metaLen = bb.int
        val metaBytes = ByteArray(metaLen).also { bb.get(it) }
        val meta = JSON.decodeFromString(Meta.serializer(), String(metaBytes, Charsets.UTF_8))
        val pts = ArrayList<RoutePoint>(n)
        pts += RoutePoint(0.0, zc / 100.0)
        for (i in 1 until n) {
            zc += bb.short
            pts += RoutePoint(if (i == n - 1) length else i * step, zc / 100.0)
        }
        return Profile(
            meta.id, pts, meta.officialKm, meta.hairpinsAtM,
            meta.landmarks?.map { Landmark(it.m, it.label) },
            meta.climbs?.map { ClimbPosition(it.name, it.summitKm, it.summitM) },
        )
    }

    /**
     * The route with its real profile when there is one. Distances are those of the real road.
     * Landmarks and climb summits come from the profile where the pipeline placed them on the real
     * road; otherwise, like the hairpins, they keep their relative position along the route.
     */
    fun apply(route: Route): Route {
        val p = load(route.id) ?: return route
        return withProfile(route, p)
    }

    fun withProfile(route: Route, p: Profile): Route {
        val k = if (route.lengthM > 0) p.lengthM / route.lengthM else 1.0
        val landmarks = p.landmarks?.filter { it.distanceM >= 0 && it.distanceM <= p.lengthM }
            ?.let { onePerSpot(it, route.landmarks, k, p.lengthM) }?.sortedBy { it.distanceM }
            ?: route.landmarks.map { Landmark(it.distanceM * k, it.label) }.filter { it.distanceM < p.lengthM }
        val placed = onePerSummit(p.climbs.orEmpty(), route.stage?.climbs.orEmpty(), k, p.points).associateBy { it.name }
        return route.copy(
            points = p.points,
            landmarks = landmarks,
            hairpinsAtM = route.hairpinsAtM.map { it * k },
            stage = route.stage?.let { s ->
                s.copy(climbs = s.climbs.map { c ->
                    val at = placed[c.name]
                    if (at != null) c.copy(summitKm = at.summitKm.coerceAtMost(p.lengthM / 1000), summitM = at.summitM ?: c.summitM)
                    else c.copy(summitKm = (c.summitKm * k).coerceAtMost(p.lengthM / 1000))
                })
            },
        )
    }

    /** Signs closer than this are one spot: a rider passes both within one tick, and only one is shown. */
    private const val SAME_SPOT_M = 25.0
    /** Climb summits closer than this are the same summit. */
    private const val SAME_SUMMIT_M = 50.0

    /**
     * The pipeline could put two signs on one spot when both labels matched the same waypoint (Izoard's
     * "Casse Déserte" and "Coppi-Bobet-Stele" both on the stele): the ride shows one sign per tick, so
     * the other never appeared. Of signs on one spot the one whose hand-made position is nearest stays;
     * each other one goes back to its stretched hand-made position, or is left out when it has none or
     * that spot is taken as well.
     */
    private fun onePerSpot(placed: List<Landmark>, handMade: List<Landmark>, k: Double, lengthM: Double): List<Landmark> {
        val stretched = handMade.associate { it.label to it.distanceM * k }
        fun miss(l: Landmark) = stretched[l.label]?.let { abs(it - l.distanceM) } ?: Double.MAX_VALUE
        val kept = ArrayList<Landmark>(placed.size)
        val moved = ArrayList<Landmark>()
        for (l in placed.sortedBy(::miss)) {
            if (kept.any { abs(it.distanceM - l.distanceM) < SAME_SPOT_M }) moved += l else kept += l
        }
        for (l in moved) {
            val at = stretched[l.label] ?: continue
            if (at > 0 && at < lengthM && kept.none { abs(it.distanceM - at) < SAME_SPOT_M }) kept += Landmark(at, l.label)
        }
        return kept
    }

    /**
     * The pipeline could put two climbs on one summit when both matched the same waypoint (stage 1's two
     * Montjuïc climbs both at km 17.0, none at the stadium). Of climbs on one summit the one whose own
     * summit lies nearest keeps it; each other one gets the profile's high point next to its stretched
     * summit, or the stretched summit itself when that high point is another climb's.
     */
    private fun onePerSummit(placed: List<ClimbPosition>, climbs: List<Climb>, k: Double, points: List<RoutePoint>): List<ClimbPosition> {
        val stretched = climbs.associate { it.name to it.summitKm * k }
        val kept = ArrayList<ClimbPosition>(placed.size)
        val moved = ArrayList<String>()
        for (at in placed.filter { it.name in stretched }.sortedBy { abs(stretched.getValue(it.name) - it.summitKm) }) {
            if (kept.any { abs(it.summitKm - at.summitKm) * 1000 < SAME_SUMMIT_M }) moved += at.name else kept += at
        }
        for (name in moved) {
            val km = stretched.getValue(name)
            val top = points.filter { abs(it.distanceM - km * 1000) < 700 }.maxByOrNull { it.elevationM }
            kept += if (top != null && kept.none { abs(it.summitKm * 1000 - top.distanceM) < SAME_SUMMIT_M }) {
                ClimbPosition(name, top.distanceM / 1000, top.elevationM)
            } else {
                ClimbPosition(name, km, null)
            }
        }
        return kept
    }
}
