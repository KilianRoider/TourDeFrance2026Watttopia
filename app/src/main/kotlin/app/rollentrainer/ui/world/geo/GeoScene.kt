package app.rollentrainer.ui.world.geo

import android.content.res.AssetFileDescriptor
import android.content.res.AssetManager
import app.rollentrainer.core.geo.ByteSource
import app.rollentrainer.core.geo.GeoMeta
import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.Routes
import java.io.EOFException
import java.io.FileInputStream
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * A route together with its geodata pack: what [GeoWorldRenderer] draws. The ride's route may be a
 * shortened or finale cut of the whole route; [offsetM] is where it starts in the pack, so the rider's
 * distance maps 1:1 to the pack distance s = distance + offset (both come from the same pipeline).
 */
class GeoScene private constructor(
    /** The route as ridden (whole, shortened, finale). */
    val route: Route,
    val pack: GeoPack,
    private val source: ByteSource,
    /** Pack distance of the route's start. */
    val offsetM: Double,
) : AutoCloseable {
    val id: String get() = pack.id
    val meta: GeoMeta get() = pack.meta
    val region: RegionStyle = RegionStyles.of(route, pack.meta)

    /**
     * Shared by all generators: the WHOLE built-in route (its landmarks, climbs and hairpins are in
     * whole-route distances, which are pack distances), not the cut that is ridden.
     */
    val sceneContext: SceneContext by lazy {
        val whole = (if (route.builtIn) Routes.byId(route.id) else null) ?: route
        SceneContext(whole, pack.meta, pack.path, pack.places, region, route.id.hashCode())
    }

    /** Pack distance of a distance along the ridden route. */
    fun packDistance(routeDistanceM: Double): Double = (routeDistanceM + offsetM).coerceIn(0.0, pack.lengthM)

    /** Set by the screen; called on the GL thread when the first complete frame is shown, or loading failed. */
    @Volatile var listener: Listener? = null

    interface Listener {
        fun onReady()
        fun onFailed(error: Throwable)
    }

    @Volatile var closed = false
        private set

    override fun close() {
        closed = true
        runCatching { source.close() }
    }

    companion object {
        /** Packs ship as uncompressed APK assets (noCompress "wwgeo" in app/build.gradle.kts). */
        fun assetPath(id: String): String = "geo/$id.wwgeo"

        /** True when the APK carries a pack for this route (only built-in routes have one). */
        fun hasPack(assets: AssetManager, route: Route): Boolean {
            if (!route.builtIn) return false
            return runCatching { assets.list("geo")?.contains("${route.id}.wwgeo") == true }.getOrDefault(false)
        }

        /** Opens the route's pack from the APK assets with ranged reads; throws when it is missing or broken. */
        fun open(assets: AssetManager, route: Route): GeoScene = open(route, AssetByteSource(assets.openFd(assetPath(route.id))))

        fun open(route: Route, source: ByteSource): GeoScene {
            try {
                val pack = GeoPack.open(source)
                // Touch the path now (on the caller's background thread), so a broken pack fails here and the
                // screen can fall back to the procedural world before anything is drawn.
                pack.path
                return GeoScene(route, pack, source, packOffset(route, pack.lengthM))
            } catch (e: Throwable) {
                runCatching { source.close() }
                throw e
            }
        }

        /**
         * Where the ridden route starts in the pack. Sections keep [Route.offsetM]; a finale is the END of
         * the route (its offset is reset to 0 by Route.finale), so it starts at pack length minus the length
         * of the whole cut. Shortened routes keep the start.
         */
        fun packOffset(route: Route, packLengthM: Double): Double {
            val base = if (route.variant.startsWith("Finale")) (packLengthM - route.wholeLengthM).coerceAtLeast(0.0) else 0.0
            return (base + route.offsetM).coerceIn(0.0, packLengthM)
        }
    }
}

/**
 * Ranged reads from an uncompressed APK asset: the asset is a region of the APK file, read with
 * positional reads on its file channel (thread-safe, no shared file pointer).
 */
class AssetByteSource(private val afd: AssetFileDescriptor) : ByteSource {
    private val stream = FileInputStream(afd.fileDescriptor)
    private val channel = stream.channel
    private val start = afd.startOffset
    override val size: Long = afd.length

    override fun read(offset: Long, length: Int): ByteArray {
        require(offset >= 0 && offset + length <= size) { "read $offset+$length beyond $size" }
        val buf = ByteBuffer.allocate(length)
        var pos = start + offset
        while (buf.hasRemaining()) {
            val n = channel.read(buf, pos)
            if (n < 0) throw EOFException("asset ended at ${pos - start}")
            pos += n
        }
        return buf.array()
    }

    override fun close() {
        runCatching { channel.close() }
        runCatching { stream.close() }
        runCatching { afd.close() }
    }
}

/** Picks the landscape character from where the pack lies (its projection centre) and how high it is. */
object RegionStyles {
    fun of(route: Route, meta: GeoMeta): RegionStyle {
        val lat = meta.projection.lat0
        val lon = meta.projection.lon0
        val high = route.meanElevationM
        fun near(la: Double, lo: Double, km: Double) = hypot((lat - la) * 111.0, (lon - lo) * 111.0 * cos(Math.toRadians(lat))) < km
        return when {
            near(48.86, 2.35, 25.0) -> RegionStyle.PARIS
            lat < 42.6 && lon > 0.6 -> RegionStyle.CATALONIA
            lat < 43.2 && lon in -2.0..3.2 && high > 500 -> RegionStyle.PYRENEES
            lon > 5.3 && lat in 44.0..46.4 && high > 700 -> RegionStyle.ALPS
            lon > 5.0 && lat in 44.0..46.4 && route.summitElevationM > 1200 -> RegionStyle.ALPS
            lon > 5.3 && lat >= 46.4 -> RegionStyle.JURA_VOSGES
            lat < 44.4 && lon > 3.0 -> RegionStyle.MEDITERRANEAN
            lat < 43.9 && lon in -2.0..3.0 -> RegionStyle.PYRENEES.takeIf { high > 500 } ?: RegionStyle.SOUTH_WEST
            lat < 45.6 && lon < 1.6 -> RegionStyle.SOUTH_WEST
            lon in 1.8..4.0 && lat in 44.3..46.2 && high > 450 -> RegionStyle.MASSIF_CENTRAL
            lon < -1.6 && lat > 47.3 -> RegionStyle.BRITTANY
            lat > 49.0 -> RegionStyle.NORTH
            lon in 2.8..5.4 && lat in 46.2..48.0 -> RegionStyle.BURGUNDY
            lon < 2.8 && lat in 46.6..48.0 -> RegionStyle.LOIRE
            lon < -0.5 -> RegionStyle.ATLANTIC
            abs(lat) > 0 -> RegionStyle.CENTRAL
            else -> RegionStyle.CENTRAL
        }
    }
}
