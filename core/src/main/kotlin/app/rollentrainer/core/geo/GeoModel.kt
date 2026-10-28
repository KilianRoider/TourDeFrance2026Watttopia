package app.rollentrainer.core.geo

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.atan2

/*
 * Data of a route pack (.wwgeo, see tools/geodata/FORMAT.md). All positions are in the route's
 * local metric projection: x east, y north, metres; elevations in metres above sea level.
 * Map y is north: a renderer with y up maps (x, y, z) to (x, z, -y).
 * Tile contents are stored relative to the tile's south-west corner (originX, originY) so that
 * floats keep centimetre precision however far the tile is from the projection origin.
 */

@Serializable
data class GeoProjection(
    val type: String,
    val lat0: Double,
    val lon0: Double,
    val k0: Double = 1.0,
    val ellipsoid: String = "WGS84",
    val proj4: String = "",
)

@Serializable
data class LodInfo(
    val lod: Int,
    /** Edge length of a square tile, metres. */
    val tileM: Double,
    /** Height cells per tile side (grid + 1 samples, edges shared with the neighbour). */
    val grid: Int,
    /** Imagery pixels per side. */
    val image: Int,
    /** Land cover cells per side (0 = this LOD has none). */
    val landcover: Int,
    /** Tiles exist out to this distance from the road. */
    val reachM: Double,
    /** Vector coordinate unit, metres. */
    val unitM: Double,
)

@Serializable
data class Hairpin(val s: Double, val turnDeg: Double, val z: Double)

@Serializable
data class WaypointInfo(val name: String = "", val kind: String = "", val km: Double? = null, val lat: Double, val lon: Double)

@Serializable
data class CorridorInfo(val offsetsM: List<Double> = emptyList(), val sectionSamples: Int = 2048)

@Serializable
data class GeoMeta(
    val format: String,
    val version: Int,
    val pipeline: Int = 0,
    val id: String,
    val built: String = "",
    val lengthM: Double,
    val officialLengthKm: Double? = null,
    val projection: GeoProjection,
    val pathStepM: Double,
    val pathBounds: List<Double> = emptyList(),
    val corridor: CorridorInfo? = null,
    val lods: List<LodInfo>,
    val landcoverClasses: List<String> = emptyList(),
    val roadClasses: List<String> = emptyList(),
    val buildingClasses: List<String> = emptyList(),
    val waterClasses: List<String> = emptyList(),
    val hairpins: List<Hairpin> = emptyList(),
    val waypoints: List<WaypointInfo> = emptyList(),
    val attribution: List<String> = emptyList(),
)

@Serializable
data class Place(
    val name: String,
    val subtype: String = "",
    @SerialName("class") val placeClass: String = "",
    val population: Int? = null,
    val x: Double,
    val y: Double,
    /** Distance along the route of the nearest road point. */
    val s: Double,
    /** Signed distance from the road: positive = left of the direction of travel. */
    val offset: Double,
)

/** A built-up stretch of the route: town sign at [sIn], end-of-town sign at [sOut]. */
@Serializable
data class TownStretch(val name: String, val sIn: Double, val sOut: Double, val population: Int? = null)

@Serializable
data class PlacesData(val places: List<Place> = emptyList(), val towns: List<TownStretch> = emptyList())

/** Bits of [PathPose.flags] and the road flags of other roads. */
object PathFlags {
    const val BRIDGE = 1
    const val TUNNEL = 2
    const val UNPAVED = 4
    const val COBBLES = 8
    const val LINK = 16
    const val ROUNDABOUT = 32
}

/** Land cover codes of the land cover tiles. */
object LandCover {
    const val NONE = 0
    const val TREE = 1
    const val SHRUB = 2
    const val GRASS = 3
    const val CROP = 4
    const val BUILT = 5
    const val BARE = 6
    const val SNOW = 7
    const val WATER = 8
    const val WETLAND = 9
    const val MANGROVE = 10
    const val MOSS = 11
    const val VINEYARD = 12
    const val ORCHARD = 13
    const val ROAD = 14
}

/** Road class codes (the route's samples and other roads); 20 = railway. */
object RoadClass {
    val names = listOf(
        "unknown", "motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential",
        "living_street", "service", "pedestrian", "cycleway", "track", "footway", "path", "steps", "bridleway",
    )
    const val RAIL = 20
    fun name(code: Int): String = if (code == RAIL) "rail" else names.getOrElse(code) { "unknown" }
}

/** Where the rider is: position, direction of travel and the road there. */
data class PathPose(
    val distanceM: Double,
    val x: Double,
    val y: Double,
    val z: Double,
    /** Unit direction of travel in the map plane. */
    val dirX: Double,
    val dirY: Double,
    /** Grade in percent over +-10 m. */
    val gradePct: Double,
    val widthM: Double,
    val flags: Int,
    val roadClass: Int,
) {
    /** Compass bearing in radians: 0 = north, pi/2 = east. */
    val bearingRad: Double get() = atan2(dirX, dirY)
    val onBridge: Boolean get() = flags and PathFlags.BRIDGE != 0
    val inTunnel: Boolean get() = flags and PathFlags.TUNNEL != 0
}

/**
 * Carved terrain beside the road for path samples [firstSample] until firstSample + count: for every
 * sample and side, the height relative to the road elevation at [offsetsM] metres beyond the road
 * edge (offset 0 = the edge, 0.15 m under the road). [freeLeft]/[freeRight] say how many offsets
 * are clear of other parts of the route (at a hairpin the next leg starts there); the renderer
 * builds verges and embankments from the free ones at 5 m resolution.
 */
class CorridorSection(
    val firstSample: Int,
    val count: Int,
    val offsetsM: FloatArray,
    val freeLeft: ByteArray,
    val freeRight: ByteArray,
    private val left: FloatArray,
    private val right: FloatArray,
) {
    val offsets: Int get() = offsetsM.size
    /** Height relative to the road of path sample [sample] (absolute index) at offset index [k]. */
    fun leftAt(sample: Int, k: Int): Float = left[(sample - firstSample) * offsets + k]
    fun rightAt(sample: Int, k: Int): Float = right[(sample - firstSample) * offsets + k]
    fun freeLeftAt(sample: Int): Int = freeLeft[sample - firstSample].toInt()
    fun freeRightAt(sample: Int): Int = freeRight[sample - firstSample].toInt()
}

/** The layers stored per tile. */
enum class GeoLayer(val code: Int) { HEIGHT(10), LANDCOVER(11), IMAGE(12), BUILDINGS(20), ROADS(21), WATER(22) }

data class TileKey(val lod: Int, val tx: Int, val ty: Int)

/** Terrain heights of a tile; row 0 is the north edge, (cells + 1) samples per side. */
class HeightTile(val key: TileKey, val originX: Double, val originY: Double, val sizeM: Double, val cells: Int, val heights: FloatArray) {
    val samples: Int get() = cells + 1
    val spacingM: Double get() = sizeM / cells

    /** Sample at column [c] (west to east) and row [r] (north to south). */
    fun at(c: Int, r: Int): Float = heights[r.coerceIn(0, cells) * samples + c.coerceIn(0, cells)]

    /** Bilinear height at a point in route coordinates (clamped to the tile). */
    fun heightAt(x: Double, y: Double): Double {
        val fx = ((x - originX) / spacingM).coerceIn(0.0, cells.toDouble())
        val fr = ((originY + sizeM - y) / spacingM).coerceIn(0.0, cells.toDouble())
        val c0 = minOf(fx.toInt(), cells - 1)
        val r0 = minOf(fr.toInt(), cells - 1)
        val tx = fx - c0
        val ty = fr - r0
        val a = at(c0, r0) + (at(c0 + 1, r0) - at(c0, r0)) * tx
        val b = at(c0, r0 + 1) + (at(c0 + 1, r0 + 1) - at(c0, r0 + 1)) * tx
        return a + (b - a) * ty
    }
}

/** Land cover cells of a tile ([LandCover] codes), row 0 north, cell centres at (i + 0.5) * size / cells. */
class LandCoverTile(val key: TileKey, val originX: Double, val originY: Double, val sizeM: Double, val cells: Int, val classes: ByteArray) {
    fun classAt(x: Double, y: Double): Int {
        val c = ((x - originX) / sizeM * cells).toInt().coerceIn(0, cells - 1)
        val r = ((originY + sizeM - y) / sizeM * cells).toInt().coerceIn(0, cells - 1)
        return classes[r * cells + c].toInt() and 0xFF
    }
}

/** Satellite colour of a tile as an encoded image (row 0 north), ready for BitmapFactory or ImageIO. */
class ImageTile(val key: TileKey, val originX: Double, val originY: Double, val sizeM: Double, val format: String, val bytes: ByteArray)

/**
 * A building footprint. [ring] holds x, y pairs relative to the tile origin (not closed: the last
 * vertex connects back to the first). The walls go from [baseZ] + [minHeightM] up to [baseZ] + [heightM];
 * [roofHeightM] of that is roof when known.
 */
class Building(
    val buildingClass: Int,
    val heightEstimated: Boolean,
    val roofShape: Int,
    val floors: Int,
    val heightM: Float,
    val minHeightM: Float,
    val roofHeightM: Float,
    val baseZ: Float,
    /** ARGB, 0 = unknown. */
    val roofColour: Int,
    val facadeColour: Int,
    val ring: FloatArray,
)

class BuildingTile(val key: TileKey, val originX: Double, val originY: Double, val buildings: List<Building>)

/** A road (or railway) beside the route; [points] are x, y pairs relative to the tile origin. */
class RoadLine(val roadClass: Int, val flags: Int, val widthM: Float, val points: FloatArray)

class RoadTile(val key: TileKey, val originX: Double, val originY: Double, val roads: List<RoadLine>)

/**
 * A water area (rings[0] outer, others holes) or a water line (one ring = polyline), relative to the
 * tile origin. [levelM] is the surface elevation of lakes and ponds, NaN where the water follows the
 * terrain (rivers, streams).
 */
class WaterFeature(val isArea: Boolean, val waterClass: Int, val levelM: Float, val rings: List<FloatArray>)

class WaterTile(val key: TileKey, val originX: Double, val originY: Double, val features: List<WaterFeature>)
