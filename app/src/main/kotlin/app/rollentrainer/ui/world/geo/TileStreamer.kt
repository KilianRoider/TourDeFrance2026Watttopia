package app.rollentrainer.ui.world.geo

import android.content.res.AssetManager
import android.util.Log
import app.rollentrainer.core.geo.GeoLayer
import app.rollentrainer.core.geo.TileKey
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.PriorityBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/** Quality and memory settings by screen class (phones get smaller textures and radii). */
internal class GeoQuality(val phone: Boolean) {
    val groundSize = if (phone) 256 else 512
    /** Ground normal maps at half the albedo size on a tablet: 20 layers, ~21 MB saved, invisible past a few metres. */
    val groundNormalSize = 256
    val roadSize = if (phone) 512 else 1024
    val objectTexSize = if (phone) 256 else 512
    val shadowSize = if (phone) 1024 else 2048
    val shadowRadius = if (phone) 70.0 else 95.0
    /** Draw radii per terrain LOD (camera distance to the tile's square). */
    val lod0Radius = if (phone) 2000.0 else 2600.0
    val lod1Radius = if (phone) 10000.0 else 13000.0
    val lod2Radius = 60000.0
    /** Route road chunks and race dressing are kept within this distance along the path. */
    val roadRadius = if (phone) 2200.0 else 2800.0
    val uploadBytesPerFrame = if (phone) 1_500_000 else 2_000_000
    val uploadNanosPerFrame = 3_000_000L
    /** Upload budget while the loading screen still shows (nothing can hitch then). */
    val loadingBytesPerFrame = 24_000_000
    /**
     * Triangles for everything but terrain and road, shadows included: instanced scenery and dressing,
     * buildings and other one-off meshes, riders. Terrain and road usually take 0.25-0.4 M more.
     */
    val objectTriangles = if (phone) 750_000L else 1_100_000L
    /**
     * The whole frame (RENDERER.md: 1.5 M, a little margin kept). A dense city's street network can
     * take 0.55 M on its own (Champs-Elysees); the objects then get what is left, see [objectTriangles].
     */
    val totalTriangles = if (phone) 1_100_000L else 1_450_000L
}

/**
 * The streaming worker: one background thread that reads chunks from the pack, builds CPU meshes and
 * rasters, decodes images and runs the scenery/dressing generators, in priority order. Results go to
 * [results]; the GL thread uploads them within a per-frame budget. Nothing here touches GL.
 */
internal class TileStreamer(
    private val scene: GeoScene,
    private val assets: AssetManager,
    private val quality: GeoQuality,
    private val scenery: SceneryGenerator,
    private val dressing: RaceDressing,
) {
    private val pack = scene.pack
    val path = pack.path
    private val index = PathIndex(path, pack.lod(0).tileM)
    private val heights = HeightCache(pack)
    private val corridor = CorridorCache(pack)
    private val kerbTowns = RoadBuilder.kerbTowns(pack.places)
    /** Heights next to the road as drawn, kerbs and pavements included (W2 and W3 stand things on them). */
    val ground = GroundSampler(path, index, heights, corridor, RoadBuilder.kerbSamples(path, kerbTowns))
    private val terrain = TerrainBuilder(pack, heights, corridor, index)
    // A pack read that fails under a bridge only costs the piers their length (they stop 20 m under the deck).
    private val roads = RoadBuilder(path, kerbTowns) { x, y -> runCatching { ground.height(x, y) }.getOrNull() }
    /** Measured on the worker before the first tile is decoded (a few LOD-1 images). */
    private val grade: SatGrade by lazy { runCatching { SatGrade.measure(pack) }.getOrDefault(SatGrade.NONE) }
    val roadChunks: Int get() = roads.chunkCount

    sealed class Job(val priority: Double) : Comparable<Job> {
        val seq = SEQ.incrementAndGet()
        override fun compareTo(other: Job): Int = when {
            priority < other.priority -> -1
            priority > other.priority -> 1
            else -> seq.compareTo(other.seq)
        }
    }

    class MaterialsJob : Job(-3.0)
    class PrototypesJob : Job(-2.0)
    class ObjectTextureJob(val name: String) : Job(-1.5)
    class TileJob(val key: TileKey, p: Double) : Job(p)
    class RoadJob(val chunk: Int, p: Double) : Job(p)
    class DressingJob(val chunk: Int, p: Double) : Job(p)
    class AssembleJob(val snapshot: InstanceAssembler.Snapshot) : Job(-1.0)

    /** Results for the GL thread. */
    class MaterialLayer(val array: Int, val layer: Int, val data: Materials.Layer)
    class Prototypes(val scenery: List<Prototype>, val dressing: List<Prototype>, val riders: RiderModelSet, val kits: List<Kit>)
    class ObjectTexture(val name: String, val data: Materials.ObjectTex?)
    class Tile(val cpu: TerrainCpu?, val key: TileKey, val scenery: SceneryChunk?, val bounds: FloatArray? = null, val shadow: ShadowMesh? = null)
    class Road(val cpu: RoadCpu)
    class Dressing(val chunk: Int, val data: SceneryChunk, val bounds: FloatArray = meshBounds(data), val shadow: ShadowMesh? = shadowMerge(data))
    class Assembled(val result: InstanceAssembler.Result)
    class Failed(val error: Throwable)

    private val queue = PriorityBlockingQueue<Job>()
    val results = ConcurrentLinkedQueue<Any>()
    /** Bounds the decoded texture layers waiting for upload (each up to 8 MB). */
    private val layerPermits = Semaphore(3)
    @Volatile private var running = true
    /** Keys the GL thread still wants; jobs for others are skipped. */
    @Volatile var wanted: Set<Any> = emptySet()
    private val thread = Thread(::loop, "geo-stream").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }
    private val assembler = InstanceAssembler()

    fun start() = thread.start()

    /** Ends the worker. The interrupt is safe for the scene: its pack reads are not interruptible (AssetByteSource). */
    fun stop() {
        running = false
        thread.interrupt()
    }

    fun post(job: Job) { queue.add(job) }

    /** Called by the GL thread after it uploaded a material layer, so the next one may be decoded. */
    fun layerUploaded() = layerPermits.release()

    private fun loop() {
        while (running) {
            val job = try { queue.take() } catch (e: InterruptedException) { break }
            try {
                run(job)
            } catch (e: InterruptedException) {
                break
            } catch (e: Throwable) {
                Log.e(TAG, "streaming job ${job.javaClass.simpleName} failed", e)
                results.add(runCatching { failure(job, e) }.getOrElse { Failed(e) })
            }
        }
    }

    /**
     * What a failed job posts. Every job answers, or the GL thread would wait for it for good (its key stays
     * requested, an assembly stays pending, and loading never ends): a broken tile, texture or dressing
     * range is skipped, a failed assembly leaves no instances until the next one; a failure of the basics
     * or of the road makes the screen fall back.
     */
    private fun failure(job: Job, e: Throwable): Any = when (job) {
        is MaterialsJob, is PrototypesJob, is RoadJob -> Failed(e)
        is TileJob -> Tile(null, job.key, null)
        is ObjectTextureJob -> ObjectTexture(job.name, null)
        is DressingJob -> Dressing(job.chunk, emptyDressing())
        is AssembleJob -> job.snapshot.let { Assembled(InstanceAssembler.Result(it.originX, it.originY, it.originZ, emptyList(), it.generation, 0)) }
    }

    private fun emptyDressing() = SceneryChunk(path.x[0], 0.0, -path.y[0])

    /** One array layer; a texture that is missing or broken becomes a flat layer (logged) instead of failing the world. */
    private fun layer(name: String, size: Int, normalSize: Int): Materials.Layer = try {
        Materials.decode(assets, name, size, normalSize)
    } catch (e: Exception) {
        Log.w(TAG, "material $name unusable, using a flat layer", e)
        Materials.flat(name, size, normalSize)
    }

    private fun run(job: Job) {
        when (job) {
            is MaterialsJob -> {
                for ((i, name) in Materials.ROAD.withIndex()) {
                    layerPermits.acquire()
                    results.add(MaterialLayer(ROAD_ARRAY, i, layer(name, quality.roadSize, quality.roadSize)))
                }
                for ((i, name) in Materials.GROUND.withIndex()) {
                    layerPermits.acquire()
                    results.add(MaterialLayer(GROUND_ARRAY, i, layer(name, quality.groundSize, quality.groundNormalSize)))
                }
            }
            is PrototypesJob -> {
                val ctx = scene.sceneContext
                results.add(Prototypes(scenery.prototypes(ctx), dressing.prototypes(ctx), dressing.riderPrototypes(), dressing.kits))
            }
            is ObjectTextureJob -> {
                // Atlases with lettering (signs, road writing) need more pixels than a bark or facade repeat.
                val max = if (job.name.startsWith("race_") || job.name.startsWith("decal_")) quality.objectTexSize * 2 else quality.objectTexSize
                val tex = runCatching {
                    if (TownSigns.isSign(job.name)) TownSigns.render(job.name, quality.objectTexSize / 2)
                    else Materials.decodeObject(assets, job.name, max)
                }
                    .onFailure { Log.w(TAG, "object texture ${job.name}: ${it.message}") }.getOrNull()
                results.add(ObjectTexture(job.name, tex))
            }
            is TileJob -> {
                if (job.key !in wanted) { results.add(Skipped(job.key)); return }
                val t0 = System.nanoTime()
                val img = pack.image(job.key)?.let { Materials.decodeImage(it.bytes) }
                img?.let { (buf, size) -> grade.apply(buf, size * size) }
                val cpu = terrain.build(job.key, img?.first, img?.second ?: 0)
                var chunk: SceneryChunk? = null
                val t1 = System.nanoTime()
                if (job.key.lod == 0 && cpu != null) {
                    val h = heights.get(job.key)
                    if (h != null) {
                        val (ox, oy) = pack.tileOrigin(job.key)
                        val secs = HashSet<Int>()
                        for (i in index.segments(job.key.tx, job.key.ty)) secs += i / (pack.meta.corridor?.sectionSamples ?: 2048)
                        val input = SceneryTileInput(
                            scene.sceneContext, job.key, ox, oy, pack.lod(0).tileM, h,
                            pack.landCover(job.key), pack.buildings(job.key), pack.roads(job.key), pack.water(job.key),
                            secs.mapNotNull { corridor.section(it) },
                        )
                        chunk = runCatching { scenery.tile(input) }.onFailure { Log.e(TAG, "scenery ${job.key}", it) }.getOrNull()
                    }
                }
                results.add(Tile(cpu, job.key, chunk, chunk?.let { meshBounds(it) }, chunk?.let { shadowMerge(it) }))
                // Loading time is mostly this job: worth seeing per tile when tuning on a device.
                Log.d(TAG, "tile ${job.key.lod}/${job.key.tx}/${job.key.ty}: terrain ${(t1 - t0) / 1_000_000} ms, scenery ${(System.nanoTime() - t1) / 1_000_000} ms")
            }
            is RoadJob -> {
                if (RoadKey(job.chunk) !in wanted) { results.add(Skipped(RoadKey(job.chunk))); return }
                results.add(Road(roads.build(job.chunk)))
            }
            is DressingJob -> {
                if (DressKey(job.chunk) !in wanted) { results.add(Skipped(DressKey(job.chunk))); return }
                val from = job.chunk * RoadBuilder.CHUNK_SAMPLES * path.stepM
                val to = min(path.lengthM, from + RoadBuilder.CHUNK_SAMPLES * path.stepM)
                // Bounds and shadow too (Dressing's defaults): a range that breaks there is left empty as well.
                val d = runCatching { Dressing(job.chunk, dressing.range(scene.sceneContext, from, to) { x, y -> ground.height(x, y) }) }
                    .onFailure { Log.e(TAG, "dressing ${job.chunk}", it) }.getOrNull() ?: Dressing(job.chunk, emptyDressing())
                results.add(d)
            }
            is AssembleJob -> results.add(Assembled(assembler.assemble(job.snapshot)))
        }
    }

    class Skipped(val key: Any)
    data class RoadKey(val chunk: Int)
    data class DressKey(val chunk: Int)

    companion object {
        private const val TAG = "WorldView"
        private val SEQ = AtomicLong()
        const val GROUND_ARRAY = 0
        const val ROAD_ARRAY = 1

        @Suppress("unused")
        private fun clampPriority(p: Double) = max(0.0, p)

        /** The pack layer used to know which tiles exist. */
        val TILE_LAYER = GeoLayer.HEIGHT
    }
}
