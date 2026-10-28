package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.BuildingTile
import app.rollentrainer.core.geo.CorridorSection
import app.rollentrainer.core.geo.GeoMeta
import app.rollentrainer.core.geo.HeightTile
import app.rollentrainer.core.geo.LandCoverTile
import app.rollentrainer.core.geo.PlacesData
import app.rollentrainer.core.geo.RoadTile
import app.rollentrainer.core.geo.RoutePath
import app.rollentrainer.core.geo.TileKey
import app.rollentrainer.core.geo.WaterTile
import app.rollentrainer.core.route.Route

/*
 * The contract between the geodata renderer (GeoWorldRenderer, GL thread) and the generators that
 * fill the world with scenery and race dressing. Everything here is plain CPU data without GL or
 * android.* types, so the generators run on the streaming worker and in JVM tests.
 *
 * Axes: GL convention, metres. x = east, y = up, z = south. A pack point (x, y, z) maps to
 * (x, z, -y). Positions are relative to an anchor (a tile's south-west corner, or a path range's
 * anchor) so floats stay precise on a 200 km stage; the renderer adds anchor - camera in Double.
 */

/** Interleaved vertices of [Vtx.FLOATS] floats each, plus triangle indices. */
class MeshBuffer(
    val vertices: FloatArray,
    val vertexCount: Int,
    val indices: IntArray,
    val indexCount: Int,
) {
    init {
        require(vertices.size >= vertexCount * Vtx.FLOATS) { "vertex array too short" }
        require(indices.size >= indexCount && indexCount % 3 == 0) { "bad index count" }
    }
}

/** Vertex layout of [MeshBuffer]: position, normal, texture coordinate, linear colour, one extra channel. */
object Vtx {
    const val POS = 0
    const val NORMAL = 3
    const val UV = 6
    const val COLOR = 8
    /**
 * WIND: sway weight 0..1 (0 at the trunk base). CHEER: arm weight (0 = body, which still bobs). FLAG:
 * distance from the pole in metres; the cloth hangs in the local x-y plane and waves along z.
 */
    const val EXTRA = 11
    const val FLOATS = 12
}

/**
 * How a vertex animates in the shader, driven by [Vtx.EXTRA] and the instance phase. FLAG materials
 * use an atlas of 8 x 4 cells: the mesh UVs address cell 0 and the renderer adds the cell of the
 * instance's variant, uv += (variant mod 8, floor(variant / 8)) / (8, 4) (unless the material sets
 * [SceneMaterial.variantCells]). CHEER reads the instance variant as the crowd's energy: 0 calm (a
 * slow sway), 1 cheering, 2 a jumping party crowd (values in between blend).
 */
enum class Anim { NONE, WIND, CHEER, FLAG }

/**
 * Atlas cells picked per instance: the mesh UVs address cell 0 and the renderer adds
 * (variant mod [cols], floor(variant / [cols])) * ([cellU], [cellV]) (UV units, v down).
 */
data class VariantCells(val cols: Int, val cellU: Float, val cellV: Float)

/**
 * What a mesh is drawn with. [texture] names an albedo texture in assets/tex (without ".webp"); its
 * _n and _m companions are used when present. [uvMetres] says the UVs are in metres and the
 * renderer scales them by the texture's real size. Vertex colour multiplies the albedo (white =
 * texture as is); without a texture the vertex colour is the albedo.
 */
data class SceneMaterial(
    val key: String,
    val texture: String? = null,
    val uvMetres: Boolean = false,
    val alphaCutout: Boolean = false,
    val doubleSided: Boolean = false,
    val roughness: Float = 0.8f,
    val anim: Anim = Anim.NONE,
    val castsShadow: Boolean = true,
    /** Atlas cell by instance variant (see [VariantCells]), e.g. one barrier prototype for every printed cover. */
    val variantCells: VariantCells? = null,
    /**
     * Lies on another surface (paint on the road, a finish line): drawn with a depth bias towards the
     * camera so it neither flickers against the asphalt nor needs a visible lift.
     */
    val decal: Boolean = false,
)

/** One level of detail of a prototype, used up to [maxDistanceM] from the camera. */
class ProtoLod(val parts: List<Pair<SceneMaterial, MeshBuffer>>, val maxDistanceM: Float)

/** An instanceable object (a tree species, a spectator, a barrier element) with its LODs, nearest first. */
class Prototype(val id: String, val lods: List<ProtoLod>, val radiusM: Float)

/**
 * Placements of one prototype, [count] × [FLOATS] floats: x, y, z (relative to the chunk's anchor),
 * yaw (radians, 0 = facing -z/north, counter-clockwise seen from above), uniform scale, tint
 * (packed 0xRRGGBB as a float-exact int < 2^24, multiplies the vertex colour; 0xFFFFFF = none),
 * animation phase (0..1), variant (free for the generator, e.g. pose or flag index).
 */
class InstanceSet(val prototypeId: String, val data: FloatArray, val count: Int) {
    companion object {
        const val FLOATS = 8
    }
}

/**
 * Generated content for one tile or path range. [anchorX]/[anchorZ]/[anchorY] are in GL world metres
 * (pack x, pack z, -pack y). [meshes] are one-off meshes (merged buildings, road writing).
 */
class SceneryChunk(
    val anchorX: Double,
    val anchorY: Double,
    val anchorZ: Double,
    val instances: List<InstanceSet> = emptyList(),
    val meshes: List<Pair<SceneMaterial, MeshBuffer>> = emptyList(),
)

/** The landscape character of a route, chosen from its position and the stage. */
enum class RegionStyle {
    CATALONIA, MEDITERRANEAN, PYRENEES, SOUTH_WEST, ATLANTIC, BRITTANY, LOIRE, CENTRAL, MASSIF_CENTRAL,
    BURGUNDY, ALPS, JURA_VOSGES, NORTH, PARIS,
}

/** Everything known about the route and its pack, shared by all generators. */
class SceneContext(
    val route: Route,
    val meta: GeoMeta,
    val path: RoutePath,
    val places: PlacesData,
    val region: RegionStyle,
    val seed: Int,
)

/** The inputs for generating one LOD-0 tile; layers may be missing. */
class SceneryTileInput(
    val ctx: SceneContext,
    val key: TileKey,
    val originX: Double,
    val originY: Double,
    val sizeM: Double,
    val heights: HeightTile,
    val landCover: LandCoverTile?,
    val buildings: BuildingTile?,
    val roads: RoadTile?,
    val water: WaterTile?,
    /** Corridor sections overlapping the tile, to keep scenery off the road and its verges. */
    val corridor: List<CorridorSection>,
)

/** Vegetation, buildings and street furniture (work package W2). */
interface SceneryGenerator {
    /** All prototypes this generator places; called once per scene. */
    fun prototypes(ctx: SceneContext): List<Prototype>

    /** Content of one LOD-0 tile; runs on the streaming worker, must be deterministic for (route, tile). */
    fun tile(input: SceneryTileInput): SceneryChunk
}

/**
 * A rider of the race simulation in route terms, as the ride loop knows it (W1 addition): the renderer
 * puts it on the road, interpolates between updates with [speedMps] and animates crank and wheels.
 * Fed through FrameState.peloton (see ui/world/WorldRenderer.kt).
 */
class RaceRider(
    /** Stable id (crank phases are kept per id). */
    val id: Int,
    /** Distance along the ridden route, metres (like FrameState.distanceM). */
    val distanceM: Double,
    /** Offset from the road's centre line, metres, positive = left of the direction of travel. */
    val lateralM: Double,
    val speedMps: Double,
    val cadenceRpm: Double,
    /** Index into [RaceDressing.kits]. */
    val kit: Int,
    /** 0 seated, 1 standing on the pedals. */
    val standing: Float = 0f,
)

/** A bike rider to draw this frame (the player, the pacer, peloton riders). */
class RiderInstance(
    /** GL world position of the contact point under the bottom bracket, metres. */
    val x: Double,
    val y: Double,
    val z: Double,
    /** Heading (same convention as [InstanceSet] yaw) and road grade in percent (pitches the bike). */
    val yaw: Float,
    val gradePct: Float,
    val crankRad: Float,
    val wheelRad: Float,
    /** Index into [RaceDressing.kits]. */
    val kit: Int,
    /** 0 seated, 1 standing on the pedals (climbing, sprinting). */
    val standing: Float = 0f,
    /** Lean into bends, radians (positive = to the left). */
    val leanRad: Float = 0f,
)

/** A team kit: jersey, shorts, helmet and bike colours (linear RGB 0..1), generic, no sponsors. */
class Kit(val name: String, val jersey: FloatArray, val jerseyAccent: FloatArray, val shorts: FloatArray, val helmet: FloatArray, val bike: FloatArray)

/** Spectators, barriers, banners, arches, campers, road writing and the riders (work package W3). */
interface RaceDressing {
    fun prototypes(ctx: SceneContext): List<Prototype>

    /** Static dressing between route distances [fromM] and [toM] (metres along the pack path). */
    fun range(ctx: SceneContext, fromM: Double, toM: Double, heights: (x: Double, y: Double) -> Double?): SceneryChunk

    /** How busy the roadside is at route distance [s], 0..1 (also feeds the crowd sound). */
    fun crowdDensityAt(ctx: SceneContext, s: Double): Double

    /** Kits for [RiderInstance.kit]; index 0 is the player. */
    val kits: List<Kit>

    /** Rider + bike model, rigid parts so riders can be instanced; nearest LOD first. */
    fun riderPrototypes(): RiderModelSet
}

/**
 * The rider model split into rigid parts that the renderer poses per [RiderInstance]: the frame and
 * body move with the bike, wheels spin about their hubs, cranks and legs follow the crank angle.
 * Positions in the bike's local frame (origin at the ground under the bottom bracket, forward = -z,
 * up = +y). Leg meshes are given for [legPoses] crank angles; the renderer picks the nearest.
 */
class RiderModelSet(
    val lods: List<RiderLod>,
    /**
     * (W1 addition) The whole set out of the saddle, used for riders with standing > 0.5, or null to
     * use [RiderLod.bodyStanding] / the seated body.
     */
    val standing: RiderModelSet? = null,
)

class RiderLod(
    val maxDistanceM: Float,
    /** Frame, rider torso, arms and head; vertex colour channels select kit colours (see [KitChannel]). */
    val body: List<Pair<SceneMaterial, MeshBuffer>>,
    /**
     * ONE wheel modelled around its own hub at the origin, axle along x; the renderer draws it at
     * [frontHub] and [rearHub] and spins it about x (rolling forward towards -z).
     */
    val wheel: List<Pair<SceneMaterial, MeshBuffer>>,
    val frontHub: FloatArray,
    val rearHub: FloatArray,
    /**
     * Crank arms + pedals + legs for evenly spaced crank angles over one revolution, in the bike frame:
     * pose k is the right crank at angle 2 pi k / n from straight up, increasing as it pedals (the right
     * pedal moves forward first). The renderer picks the pose nearest to [RiderInstance.crankRad].
     */
    val legPoses: List<List<Pair<SceneMaterial, MeshBuffer>>>,
    /** Same as [body] but out of the saddle, or null to reuse [body]. */
    val bodyStanding: List<Pair<SceneMaterial, MeshBuffer>>? = null,
)

/**
 * Colour channels for rider meshes: a vertex colour of exactly one of these values is replaced by
 * the matching kit colour in the shader (everything else is drawn as is).
 */
object KitChannel {
    val JERSEY = floatArrayOf(1f, 0f, 1f)
    val ACCENT = floatArrayOf(0f, 1f, 1f)
    val SHORTS = floatArrayOf(1f, 1f, 0f)
    val HELMET = floatArrayOf(0f, 1f, 0f)
    val BIKE = floatArrayOf(0f, 0f, 1f)
}
