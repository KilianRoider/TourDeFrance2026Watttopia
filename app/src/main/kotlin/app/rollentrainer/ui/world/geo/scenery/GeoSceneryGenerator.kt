package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.core.geo.BuildingTile
import app.rollentrainer.core.geo.GeoLayer
import app.rollentrainer.core.geo.GeoPack
import app.rollentrainer.core.geo.LandCover
import app.rollentrainer.core.geo.TileKey
import app.rollentrainer.ui.world.geo.InstanceSet
import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.RegionStyle
import app.rollentrainer.ui.world.geo.SceneContext
import app.rollentrainer.ui.world.geo.SceneMaterial
import app.rollentrainer.ui.world.geo.SceneryChunk
import app.rollentrainer.ui.world.geo.SceneryGenerator
import app.rollentrainer.ui.world.geo.SceneryTileInput
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * W2's scenery: region-typical vegetation, buildings from the footprints and street furniture,
 * placed from the pack's land cover, roads, water, buildings and the route corridor.
 *
 * Usage: one instance per scene. [prototypes] once (builds every tree/plant/furniture model the
 * route's regions need, ~0.3–1 s of CPU: run it on the worker), then [tile] per LOD-0 tile on the
 * streaming worker. Thread-safe; deterministic for (route seed, tile).
 *
 * [pack] is optional: with it a tile also keeps its vegetation and furniture out of the footprints
 * stored in the neighbouring tiles that reach across the border (see [NeighbourBuildings]).
 */
class GeoSceneryGenerator(pack: GeoPack? = null) : SceneryGenerator {
    private val registries = ConcurrentHashMap<SceneContext, Registry>()
    private val neighbours = pack?.let { NeighbourBuildings(it) }

    internal fun registry(ctx: SceneContext): Registry = registries.getOrPut(ctx) { Registry.of(ctx) }

    override fun prototypes(ctx: SceneContext): List<Prototype> = registry(ctx).prototypes

    override fun tile(input: SceneryTileInput): SceneryChunk = TileJob(input, registry(input.ctx), neighbours).run()

    companion object {
        /**
         * The crop growing at (x, y) (pack coordinates) when the land cover there is cropland, so
         * the terrain shader can texture the field to match the maize / sunflower / lavender rows
         * the generator stands on it: 0 wheat (ripe or harvested), 1 maize, 2 sunflower,
         * 3 lavender, 4 pasture/hay. Deterministic per route; fields are ~150–250 m patches.
         */
        fun cropAt(ctx: SceneContext, x: Double, y: Double): Int {
            val lat = RegionPicker.latLon(ctx.meta, x, y)
            val style = RegionPicker.at(lat[0], lat[1], 0.0)
            return Fields.crop(ctx.seed.toLong(), Regions.profile(style), x, y).ordinal.let { o ->
                when (Crop.values()[o]) { Crop.WHEAT -> 0; Crop.MAIZE -> 1; Crop.SUNFLOWER -> 2; Crop.LAVENDER -> 3; Crop.PASTURE -> 4 }
            }
        }
    }
}

/**
 * Building footprints of the tiles around a tile that reach into it: a building belongs to the tile
 * holding its centroid and its ring may extend beyond that tile (FORMAT.md), so a tile's own list
 * misses the part of the house next door standing on it. Decoded tiles in a small LRU cache.
 */
internal class NeighbourBuildings(private val pack: GeoPack) {
    private val cache = object : LinkedHashMap<TileKey, BuildingTile?>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TileKey, BuildingTile?>?) = size > 24
    }

    private fun tile(key: TileKey): BuildingTile? {
        synchronized(cache) { if (cache.containsKey(key)) return cache[key] }
        val t = if (pack.has(GeoLayer.BUILDINGS, key)) pack.buildings(key) else null
        synchronized(cache) { cache[key] = t }
        return t
    }

    /** Rings (absolute pack coordinates) from the 8 tiles around [key] whose bounds reach x0..x1, y0..y1. */
    fun ringsReaching(key: TileKey, x0: Double, y0: Double, x1: Double, y1: Double): List<DoubleArray> {
        val out = ArrayList<DoubleArray>()
        for (dy in -1..1) for (dx in -1..1) {
            if (dx == 0 && dy == 0) continue
            val t = tile(TileKey(key.lod, key.tx + dx, key.ty + dy)) ?: continue
            for (b in t.buildings) {
                val r = b.ring
                if (r.size < 6) continue
                var ax = Double.MAX_VALUE; var ay = Double.MAX_VALUE; var bx = -Double.MAX_VALUE; var by = -Double.MAX_VALUE
                for (k in 0 until r.size / 2) {
                    val x = t.originX + r[k * 2]; val y = t.originY + r[k * 2 + 1]
                    ax = min(ax, x); bx = max(bx, x); ay = min(ay, y); by = max(by, y)
                }
                if (bx < x0 || ax > x1 || by < y0 || ay > y1) continue
                out += DoubleArray(r.size) { i -> (if (i % 2 == 0) t.originX else t.originY) + r[i] }
            }
        }
        return out
    }
}

/** The prototypes a scene uses: everything the regions along the route can place. */
internal class Registry(val regions: Set<RegionStyle>, val prototypes: List<Prototype>) {
    val ids: Set<String> = prototypes.map { it.id }.toHashSet()
    fun has(id: String) = id in ids

    @Volatile private var feat: RouteFeatures? = null

    /** Route-wide features (bends, warning signs), computed on first use. */
    fun features(ctx: SceneContext): RouteFeatures = feat ?: synchronized(this) { feat ?: RouteFeatures(ctx).also { feat = it } }

    companion object {
        fun regionsAlong(ctx: SceneContext): Set<RegionStyle> {
            val set = linkedSetOf(ctx.region)
            val p = ctx.path
            var i = 0
            val step = maxOf(1, (800.0 / p.stepM).toInt())
            while (i < p.size) {
                // Probe the road and ±1 km beside it, the reach of the LOD-0 tiles.
                for (off in doubleArrayOf(0.0, -1000.0, 1000.0)) {
                    val ll = RegionPicker.latLon(ctx.meta, p.x[i] + off, p.y[i] + off * 0.3)
                    set += RegionPicker.at(ll[0], ll[1], p.z[i])
                    set += RegionPicker.at(ll[0], ll[1], p.z[i] + 400)
                    set += RegionPicker.at(ll[0], ll[1], maxOf(0.0, p.z[i] - 400))
                }
                i += step
            }
            return set
        }

        fun of(ctx: SceneContext): Registry {
            val regions = regionsAlong(ctx)
            val profiles = regions.map { Regions.profile(it) }
            val species = sortedSetOf<Species>()
            val maxZ = ctx.path.z.maxOrNull() ?: 0.0
            for (pr in profiles) {
                pr.forest.forEach { species += it.second.keys }
                pr.open.forEach { species += it.second.keys }
                species += pr.garden.keys
                pr.avenue?.let { species += it }
                species += pr.orchardTree
                if (pr.poplarRows > 0f) species += Species.POPLAR
                if (maxZ > pr.treeline - 600) species += Species.MUGO
            }
            val protos = ArrayList<Prototype>()
            for (sp in species) protos += TreeProtos.get(sp).proto
            protos += PlantSet.forRegions(profiles)
            protos += FurnitureProtos.all()
            return Registry(regions, protos.distinctBy { it.id })
        }
    }
}

/** Plant / crop / rock prototypes, built once and shared. */
internal object PlantSet {
    private val cache = ConcurrentHashMap<String, Prototype>()
    private fun get(id: String, make: () -> Prototype): Prototype = cache.getOrPut(id, make)

    /**
     * Photo-scanned tufts ([MeadowAtlas]) in several variants per size, so neighbouring tufts differ:
     * ids [GRASS_SMALL]k, [GRASS]k, [GRASS_TALL]k, [GRASS_DRY]k, [WEED]k with k < the list's size.
     */
    const val GRASS_SMALL = "scn_grass_s"
    const val GRASS = "scn_grass_m"
    const val GRASS_TALL = "scn_grass_l"
    const val GRASS_DRY = "scn_grass_dry"
    const val WEED = "scn_weed"
    private fun meadow(prefix: String, cells: List<UvRect>, size: Float, maxDist: Float, cards: Int = 3) =
        cells.mapIndexed { k, r -> get("$prefix$k") { PlantProtos.meadow("$prefix$k", r, size, maxDist, cards) } }
    val grassSmall get() = meadow(GRASS_SMALL, MeadowAtlas.GREEN, 0.38f, 22f, cards = 2)
    val grass get() = meadow(GRASS, MeadowAtlas.GREEN, 0.7f, 34f)
    val grassTall get() = meadow(GRASS_TALL, MeadowAtlas.TALL + MeadowAtlas.GREEN.take(3), 1.1f, 45f, cards = 4)
    val grassDry get() = meadow(GRASS_DRY, MeadowAtlas.DRY, 0.8f, 34f)
    val weeds get() = meadow(WEED, MeadowAtlas.WEEDS, 0.55f, 28f, cards = 2)
    fun variants(prefix: String) = when (prefix) {
        GRASS_SMALL, GRASS -> MeadowAtlas.GREEN.size
        GRASS_TALL -> MeadowAtlas.TALL.size + 3
        GRASS_DRY -> MeadowAtlas.DRY.size
        WEED -> MeadowAtlas.WEEDS.size
        else -> 1
    }
    val flowers get() = get("scn_flowers") { PlantProtos.grass("scn_flowers", PlantAtlas.FLOWERS, 0.7f, 0.5f, 40f) }
    val poppies get() = get("scn_poppies") { PlantProtos.grass("scn_poppies", PlantAtlas.POPPIES, 0.7f, 0.6f, 40f) }
    val alpineFlowers get() = get("scn_flowers_alpine") { PlantProtos.grass("scn_flowers_alpine", PlantAtlas.ALPINE_FLOWERS, 0.6f, 0.35f, 40f) }
    val fern get() = get("scn_fern") { PlantProtos.fern() }
    val shrub get() = get("scn_shrub") { PlantProtos.shrub("scn_shrub", PlantAtlas.SHRUB, 2.6f, 1.6f, 34, 1.15f, 301) }
    val garrigue get() = get("scn_garrigue") { PlantProtos.shrub("scn_garrigue", PlantAtlas.GARRIGUE, 1.1f, 1.1f, 22, 0.9f, 302) }
    val gorse get() = get("scn_gorse") { PlantProtos.shrub("scn_gorse", PlantAtlas.GORSE, 1.5f, 1.2f, 24, 0.95f, 303) }
    val rhodo get() = get("scn_rhodo") { PlantProtos.shrub("scn_rhodo", PlantAtlas.RHODO, 0.7f, 0.9f, 16, 0.7f, 304, 40f, 120f) }
    val hedge get() = get("scn_hedge") { PlantProtos.hedge() }
    val maize get() = get("scn_maize") { PlantProtos.rowBlock("scn_maize", PlantAtlas.MAIZE, 6f, 4, 0.75f, 2.5f, 2f, 90f, 300f) }
    val sunflower get() = get("scn_sunflower") { PlantProtos.rowBlock("scn_sunflower", PlantAtlas.SUNFLOWER, 6f, 4, 0.75f, 1.7f, 2f, 90f, 300f) }
    val lavender get() = get("scn_lavender") { PlantProtos.rowBlock("scn_lavender", PlantAtlas.LAVENDER, 6f, 2, 1.5f, 0.75f, 1.5f, 80f, 260f, vShape = 0.3f) }
    fun vine(spacing: Float): Prototype {
        val rows = (6f / spacing + 0.5f).toInt().coerceAtLeast(2)
        val id = "scn_vine_$rows"
        return get(id) { PlantProtos.rowBlock(id, PlantAtlas.VINE, 6f, rows, 6f / rows, 1.25f, 2f, 80f, 320f, base = 0.35f, posts = true) }
    }
    val bale get() = get("scn_bale") { PlantProtos.bale() }
    fun boulder(texture: String, k: Int): Prototype {
        val id = "scn_boulder_${texture.removePrefix("rock_")}_$k"
        return get(id) { PlantProtos.boulder(id, texture, 1000L + k * 17 + Hash.str(texture), 1.0f) }
    }
    fun pebble(texture: String, k: Int): Prototype {
        val id = "scn_pebble_${texture.removePrefix("rock_")}_$k"
        return get(id) { PlantProtos.pebble(id, texture, 2000L + k * 29 + Hash.str(texture)) }
    }

    fun forRegions(profiles: List<RegionProfile>): List<Prototype> {
        val out = ArrayList<Prototype>()
        out += grass
        out += grassSmall
        out += grassTall
        out += grassDry
        out += weeds
        out += flowers
        out += poppies
        out += fern
        out += hedge
        out += bale
        out += shrub
        if (profiles.any { it.shrubKind == 1 || it.style == RegionStyle.SOUTH_WEST }) out += garrigue
        if (profiles.any { it.shrubKind == 2 }) out += gorse
        if (profiles.any { it.treeline < 2500f }) { out += rhodo; out += alpineFlowers }
        if (profiles.any { (it.crops[Crop.MAIZE] ?: 0f) > 0f }) out += maize
        if (profiles.any { (it.crops[Crop.SUNFLOWER] ?: 0f) > 0f }) out += sunflower
        if (profiles.any { (it.crops[Crop.LAVENDER] ?: 0f) > 0f }) out += lavender
        for (p in profiles) out += vine(p.vineRowM)
        for (tex in profiles.map { it.rock }.toSet()) for (k in 0 until 3) { out += boulder(tex, k); out += pebble(tex, k) }
        return out.distinctBy { it.id }
    }
}

/** Collects instances per prototype in the GL frame of the chunk anchor. */
internal class InstanceStore(private val ox: Double, private val oy: Double, private val anchorY: Double) {
    private val sets = LinkedHashMap<String, FloatList>()

    /**
     * Adds an instance at pack position (x, y, z). [yaw] in the [InstanceSet] convention; use
     * [yawFacing] / [yawAlong] to compute it from pack directions.
     */
    fun put(id: String, x: Double, y: Double, z: Double, yaw: Float, scale: Float, tint: Int = 0xFFFFFF, phase: Float = 0f, variant: Float = 0f) {
        val l = sets.getOrPut(id) { FloatList(256) }
        l.add((x - ox).toFloat())
        l.add((z - anchorY).toFloat())
        l.add((-(y - oy)).toFloat())
        l.add(yaw)
        l.add(scale)
        l.add((tint and 0xFFFFFF).toFloat())
        l.add(phase)
        l.add(variant)
    }

    fun count(id: String): Int = (sets[id]?.size ?: 0) / InstanceSet.FLOATS
    val total: Int get() = sets.values.sumOf { it.size } / InstanceSet.FLOATS

    /** Instance sets, each sorted into 160 m blocks so the renderer can cull contiguous ranges. */
    fun build(): List<InstanceSet> = sets.map { (id, l) ->
        val n = l.size / InstanceSet.FLOATS
        val order = (0 until n).sortedWith(compareBy({ floor(l.data[it * 8 + 2] / 160f).toInt() }, { floor(l.data[it * 8] / 160f).toInt() }, { l.data[it * 8 + 2] }, { l.data[it * 8] }))
        val out = FloatArray(n * InstanceSet.FLOATS)
        for ((k, i) in order.withIndex()) System.arraycopy(l.data, i * 8, out, k * 8, 8)
        InstanceSet(id, out, n)
    }

    companion object {
        /** Yaw that turns a prototype's front (-z) to face pack direction (fx, fy). */
        fun yawFacing(fx: Double, fy: Double): Float = atan2(-fx, fy).toFloat()

        /** Yaw that lays a prototype's +x axis along pack direction (dx, dy). */
        fun yawAlong(dx: Double, dy: Double): Float = atan2(dy, dx).toFloat()

        /** A subtle per-instance colour variation as a packed tint. */
        fun tint(rnd: Rand, lum: Float = 0.08f, hue: Float = 0.05f): Int {
            val l = 1f - lum + rnd.f() * lum * 1.4f
            val h = rnd.sym(hue)
            val r = (255 * (l * (1 + h)).coerceIn(0f, 1f)).toInt()
            val g = (255 * l.coerceIn(0f, 1f)).toInt()
            val b = (255 * (l * (1 - h * 1.5f)).coerceIn(0f, 1f)).toInt()
            return (r shl 16) or (g shl 8) or b
        }
    }
}

internal class FloatList(cap: Int) {
    var data = FloatArray(cap)
    var size = 0
    fun add(v: Float) {
        if (size == data.size) data = data.copyOf(size * 2)
        data[size++] = v
    }
}

/** Merges meshes per material into a few buffers per tile. */
internal class MeshStore {
    private val byKey = LinkedHashMap<String, Pair<SceneMaterial, MeshBuilder>>()
    fun builder(m: SceneMaterial): MeshBuilder = byKey.getOrPut(m.key) { m to MeshBuilder(1024, 2048) }.second
    fun build(): List<Pair<SceneMaterial, MeshBuffer>> = byKey.values.filter { !it.second.isEmpty }.map { it.first to it.second.build() }
    val triangles: Int get() = byKey.values.sumOf { it.second.triangleCount }
}

/** Debug and tool access (exporters, tests). */
object SceneryDebug {
    fun treePrototypes(): List<Pair<Prototype, FloatArray?>> = Species.values().map { TreeProtos.get(it).let { b -> b.proto to b.impostorFrame } }

    fun impostorFrame(protoId: String): FloatArray? = Species.values().firstOrNull { it.id == protoId }?.let { TreeProtos.get(it).impostorFrame }

    /** Corridor sections overlapping a tile (what the renderer passes in SceneryTileInput.corridor). */
    fun corridorFor(pack: app.rollentrainer.core.geo.GeoPack, ox: Double, oy: Double, size: Double): List<app.rollentrainer.core.geo.CorridorSection> {
        val p = pack.path
        val per = pack.meta.corridor?.sectionSamples ?: return emptyList()
        val secs = sortedSetOf<Int>()
        for (i in 0 until p.size) {
            if (p.x[i] > ox - 60 && p.x[i] < ox + size + 60 && p.y[i] > oy - 60 && p.y[i] < oy + size + 60) secs += i / per
        }
        return secs.mapNotNull { pack.corridor(it) }
    }

    /** Land cover code names for logs. */
    fun lcName(c: Int): String = when (c) {
        LandCover.TREE -> "tree"; LandCover.SHRUB -> "shrub"; LandCover.GRASS -> "grass"; LandCover.CROP -> "crop"
        LandCover.BUILT -> "built"; LandCover.BARE -> "bare"; LandCover.WATER -> "water"; LandCover.VINEYARD -> "vineyard"
        LandCover.ORCHARD -> "orchard"; LandCover.ROAD -> "road"; else -> "lc$c"
    }
}
