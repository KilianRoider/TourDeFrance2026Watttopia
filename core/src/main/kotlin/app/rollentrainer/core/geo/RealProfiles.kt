package app.rollentrainer.core.geo

import app.rollentrainer.core.route.Landmark
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RoutePoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

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
        val landmarks = p.landmarks?.filter { it.distanceM >= 0 && it.distanceM <= p.lengthM }?.sortedBy { it.distanceM }
            ?: route.landmarks.map { Landmark(it.distanceM * k, it.label) }.filter { it.distanceM < p.lengthM }
        val placed = p.climbs?.associateBy { it.name }.orEmpty()
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
}
