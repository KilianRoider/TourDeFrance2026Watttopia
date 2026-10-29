package app.rollentrainer.ui.world.geo

import android.content.Context
import android.content.res.AssetManager
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import app.rollentrainer.core.geo.GeoLayer
import app.rollentrainer.core.geo.PathFlags
import app.rollentrainer.core.geo.TileKey
import app.rollentrainer.ui.world.FrameState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The world from a geodata pack: real terrain (satellite colour + land-cover detail textures), the real
 * road with its verges, bridges and tunnels, scenery and race dressing from the generators (W2/W3),
 * riders, an analytic sky with aerial perspective and sun shadows. Everything streams: a worker builds
 * tiles and chunks, this GL thread uploads them within a per-frame budget and draws camera-relative.
 *
 * Used by WorldView when the route has a pack; the procedural WorldRenderer stays the fallback.
 */
class GeoWorldRenderer(val frame: FrameState, private val context: Context) : GLSurfaceView.Renderer {
    /** The scene to show; set from any thread (WorldView, the preview driver). */
    @Volatile var pendingScene: GeoScene? = null

    private val assets: AssetManager = context.assets
    private var scene: GeoScene? = null
    private var streamer: TileStreamer? = null
    private var quality = GeoQuality(false)
    private var failed = false
    private var ready = false
    private var surfaceW = 1
    private var surfaceH = 1
    private var aspect = 1f

    // --- programs and shared GL objects --------------------------------------------------------
    private lateinit var terrainProg: GeoProgram
    private lateinit var terrainFarProg: GeoProgram
    private lateinit var terrainCutProg: GeoProgram
    private lateinit var roadProg: GeoProgram
    private lateinit var objectProg: GeoProgram
    private lateinit var objectShadowProg: GeoProgram
    private lateinit var objectShadowCutProg: GeoProgram
    private lateinit var riderProg: GeoProgram
    private lateinit var riderShadowProg: GeoProgram
    private lateinit var skyProg: GeoProgram
    private var glReady = false
    /**
     * Why this context could not be set up (a shader the driver rejects), or null. Unlike [failed] it
     * survives [startScene]: every scene is told (once, see [glErrorScene]) until a new context works.
     */
    private var glError: Throwable? = null
    private var glErrorScene: GeoScene? = null
    private var frameUbo = 0
    private var gridIbo = 0
    private var skyVao = 0
    private var noiseTex = 0
    private var groundTex = 0
    private var groundNTex = 0
    private var roadTex = 0
    private var roadNTex = 0
    private var shadowTex = 0
    private var shadowFbo = 0
    private var kitTex = 0
    /** The photographed sky panorama (assets/sky, see tools/assets-cc0/sky.py), 0 until loaded or without one. */
    private var skyTex = 0
    private var skySunU = 0f
    private var skyMax = 0f
    private var skyTried = false
    /** Rows of [kitTex]; kit indices are clamped to it (texelFetch outside the texture is undefined). */
    private var kitCount = 1
    private val layerMean = FloatArray(24 * 3)
    private val roadMean = FloatArray(4) { 0.05f }
    private val layerSize = FloatArray(24)
    private var groundLayersDone = 0
    private var roadLayersDone = 0
    private var rules: GroundRules? = null

    // --- resident world ------------------------------------------------------------------------
    private class GpuTile(val key: TileKey, cpu: TerrainCpu, gridIbo: Int) {
        val anchorX = cpu.anchorX
        val anchorZ = cpu.anchorZ
        val size = cpu.sizeM
        val vbo: Int
        val vao: Int
        var corrVao = 0
        var corrIbo = 0
        val corrCount = cpu.corridorIndices.size
        val sat: Int
        val lc: Int
        val lcCells = cpu.landCoverCells
        val side: MeshGpu?
        val blockMin = cpu.blockMinY
        val blockMax = cpu.blockMaxY
        val minY = cpu.minY
        val maxY = cpu.maxY
        val bytes: Int
        /** The 16 finer tiles under this one's blocks (row from the north, column), for the LOD hierarchy. */
        val children: Array<TileKey>? = if (key.lod > 0) Array(16) { b -> TileKey(key.lod - 1, key.tx * 4 + b % 4, key.ty * 4 + (3 - b / 4)) } else null

        init {
            vao = GeoGl.genVao()
            GLES30.glBindVertexArray(vao)
            vbo = GeoGl.staticBuffer(GLES30.GL_ARRAY_BUFFER, cpu.vertices, cpu.vertexCount * TerrainGrid.STRIDE)
            terrainAttribs()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, gridIbo)
            if (corrCount > 0) {
                corrVao = GeoGl.genVao()
                GLES30.glBindVertexArray(corrVao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
                terrainAttribs()
                corrIbo = GeoGl.staticBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, GeoGl.ints(cpu.corridorIndices), corrCount * 4)
            }
            GLES30.glBindVertexArray(0)
            sat = if (cpu.image != null && cpu.imageSize > 0) GeoGl.texture2D(cpu.image, cpu.imageSize, cpu.imageSize, true, true, 8f, GLES30.GL_CLAMP_TO_EDGE) else 0
            lc = if (cpu.landCover != null && cpu.landCoverCells > 0) GeoGl.textureR8ui(cpu.landCover, cpu.landCoverCells, cpu.landCoverCells) else 0
            side = cpu.sideRoads?.let { MeshGpu(it) }
            bytes = cpu.vertexCount * TerrainGrid.STRIDE + corrCount * 4 + cpu.imageSize * cpu.imageSize * 4 * 4 / 3 +
                cpu.landCoverCells * cpu.landCoverCells + (side?.bytes ?: 0)
        }

        private fun terrainAttribs() {
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, TerrainGrid.STRIDE, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 4, GLES30.GL_BYTE, true, TerrainGrid.STRIDE, 12)
        }

        fun delete() {
            GeoGl.deleteVao(vao); GeoGl.deleteVao(corrVao)
            GeoGl.deleteBuffer(vbo); GeoGl.deleteBuffer(corrIbo)
            GeoGl.deleteTexture(sat); GeoGl.deleteTexture(lc)
            side?.delete()
        }
    }

    /** A road chunk on the GPU with the RoadCpu fields drawing needs (its CPU mesh is not kept). */
    private class GpuRoad(cpu: RoadCpu) {
        val mesh = MeshGpu(cpu.mesh)
        val anchorX = cpu.anchorX
        val anchorZ = cpu.anchorZ
        val minX = cpu.minX; val maxX = cpu.maxX; val minY = cpu.minY; val maxY = cpu.maxY; val minZ = cpu.minZ; val maxZ = cpu.maxZ
        val tubeFirst = cpu.tubeFirst
        val tubeCount = cpu.tubeCount
    }

    /**
     * A scenery tile or dressing range: one-off meshes on the GPU, instances kept for the assembler. The
     * chunk's CPU meshes are not kept (they would stay on the heap as long as the chunk is resident).
     */
    private class GpuChunk(chunk: SceneryChunk, val meshes: List<Pair<MaterialGpu, MeshGpu>>, val bounds: FloatArray, shadowCpu: ShadowMesh?) {
        val anchorX = chunk.anchorX
        val anchorY = chunk.anchorY
        val anchorZ = chunk.anchorZ
        /** The assembler's view of the instances, or null without any. */
        val source: InstanceAssembler.Source? =
            if (chunk.instances.isNotEmpty()) InstanceAssembler.Source(chunk.anchorX, chunk.anchorY, chunk.anchorZ, chunk.instances) else null
        /** The merged shadow casters (one shadow draw for the chunk) and the meshes they stand in for. */
        val shadow: PosMeshGpu? = shadowCpu?.let { PosMeshGpu(it) }
        val shadowBounds: FloatArray? = shadowCpu?.bounds
        val shadowMerged: BooleanArray? = shadowCpu?.merged
        val bytes = meshes.sumOf { it.second.bytes } + (shadow?.bytes ?: 0)
        fun delete() { for ((_, m) in meshes) m.delete(); shadow?.delete() }
    }

    private val tiles = HashMap<TileKey, GpuTile>()
    /** The maps' values as lists, rebuilt only when a map changed: no iterator garbage per frame. */
    private val tileList = ArrayList<GpuTile>()
    private val sceneryList = ArrayList<GpuChunk>()
    private val roadList = ArrayList<GpuRoad>()
    private val dressList = ArrayList<GpuChunk>()
    private var listsDirty = true
    private val tileScenery = HashMap<TileKey, GpuChunk>()
    private val roadsGpu = HashMap<Int, GpuRoad>()
    private val dressGpu = HashMap<Int, GpuChunk>()
    private val requested = HashSet<Any>()
    /** Tiles whose chunks are broken: not asked for again, and not waited for by the ready check. */
    private val failedTiles = HashSet<TileKey>()
    /** Wanted keys with their priority (lower first), refreshed by [updateStreaming]. */
    private var wanted = HashMap<Any, Double>()
    private var lastStreamX = Double.NaN
    private var lastStreamZ = Double.NaN
    private var lastStreamMs = 0L
    private val pendingUploads = ArrayList<Any>()

    // --- prototypes, materials, riders ---------------------------------------------------------
    private val materials = HashMap<String, MaterialGpu>()
    private val protos = HashMap<String, ProtoGpu>()
    private val protoList = ArrayList<ProtoGpu>()
    /** The assembler's view of [protoList], built once after the prototypes are uploaded. */
    private var protoInfo: Map<String, InstanceAssembler.ProtoInfo>? = null
    private var protosReady = false
    private val texRequested = HashSet<String>()
    private val texPending = HashSet<String>()
    private val objectTextures = ArrayList<Int>()
    /** Town sign faces by material key (see [releaseUnusedSigns]). */
    private val signTextures = HashMap<String, Int>()
    private val signsInUse = HashSet<String>()
    private var objectTexBytes = 0
    private var riders: RiderGpu? = null
    private var assemblyPending = false
    private var assemblyGeneration = 0
    private var assemblyDone = false
    private var chunksChanged = true
    private var assemblyOriginX = 0.0
    private var assemblyOriginY = 0.0
    private var assemblyOriginZ = 0.0
    private var lastAssemblyMs = 0L
    private var lastAssemblyX = Double.NaN
    private var lastAssemblyZ = Double.NaN
    private var lastAssemblyDirX = 0.0
    private var lastAssemblyDirZ = 0.0
    /**
     * Triangles of everything but terrain and road last frame: instanced objects, one-off meshes and riders,
     * main pass and shadows. The LOD bias holds it to [objectBudget]; only the instanced
     * scenery and dressing can give way, so a big peloton or a town of buildings costs the trees detail.
     */
    private var objectTris = 0L
    /** [objectTris] of the frame being drawn. */
    private var frameTris = 0L
    /** Terrain and road triangles of last frame and of the frame being drawn; they shrink the object budget. */
    private var groundTris = 0L
    private var frameGroundTris = 0L
    private val bindRiderMain: (MaterialGpu) -> Unit = { setMaterial(riderProg, it) }
    private val bindRiderShadow: (MaterialGpu) -> Unit = { }
    private var lodBias = 1f
    /** Tunnel pieces near the camera for the near terrain's cut (GeoShaders TUNNEL_CUT): camera-relative, per frame. */
    private val tunA = FloatArray(4 * MAX_TUNNEL_PIECES)
    private val tunB = FloatArray(4 * MAX_TUNNEL_PIECES)
    private val tunBox = FloatArray(4)
    private var tunN = 0
    /** How deep the player is in a tunnel (0 outside, 1 from ~30 m in), for the riders' light. */
    private var riderDark = 0f

    // --- per frame -----------------------------------------------------------------------------
    private val camera = GeoCamera()
    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private val lightVP = FloatArray(16)
    private val shadowMat = FloatArray(16)
    private val tmpM = FloatArray(16)
    private val planes = FloatArray(24)
    private val block = FloatArray(76)
    private val blockBuf: FloatBuffer = ByteBuffer.allocateDirect(76 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val sun = DoubleArray(3)
    private var sunLat = Double.NaN
    private var sunTime = Double.NaN
    private var lastFrameMs = 0L
    private var smoothDistance = Double.NaN
    private var crank = 0.0
    private var wheel = 0.0
    private var pacerCrank = 0.0
    private var pacerWheel = 0.0
    private var lastFirstPerson = false
    private val riderPos = DoubleArray(3)
    private val riderFwd = DoubleArray(3)
    private val behindPos = DoubleArray(3)
    private val aheadPos = DoubleArray(3)
    private val aheadFarPos = DoubleArray(3)
    private var riderLean = 0.0
    /** Crank and wheel phase per race rider id (ids are indices into the field, so an array: no boxing per frame). */
    private var pelotonPhase = arrayOfNulls<DoubleArray>(256)
    private var timeSec = 0f
    /** [timeSec] without the hourly wrap (s), for the cloud drift. */
    private var clockSec = 0.0
    private var shadowOn = true
    private var drawnLogged = false

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Names of the old context mean nothing now (deleting them would hit new objects).
        shadowTex = 0
        shadowFbo = 0
        skyTex = 0
        skyTried = false
        try {
            GeoGl.init()
            // A new context: everything on the GPU is gone. Programs now, the world streams in again.
            terrainProg = GeoProgram(GeoShaders.TERRAIN_VS, GeoShaders.TERRAIN_FS, mapOf("uSat" to 0, "uLc" to 1, "uGround" to 2, "uGroundN" to 3, "uNoise" to 4, "uShadow" to 5))
            terrainFarProg = GeoProgram(GeoShaders.TERRAIN_VS, GeoShaders.TERRAIN_FAR_FS, mapOf("uSat" to 0, "uNoise" to 4, "uShadow" to 5))
            terrainCutProg = GeoProgram(GeoShaders.TERRAIN_VS, GeoShaders.TERRAIN_CUT_FS, mapOf("uSat" to 0, "uLc" to 1, "uGround" to 2, "uGroundN" to 3, "uNoise" to 4, "uShadow" to 5))
            roadProg = GeoProgram(GeoShaders.ROAD_VS, GeoShaders.ROAD_FS, mapOf("uGround" to 2, "uGroundN" to 3, "uNoise" to 4, "uShadow" to 5, "uRoad" to 6, "uRoadN" to 7))
            objectProg = GeoProgram(GeoShaders.OBJECT_VS, GeoShaders.OBJECT_FS, mapOf("uShadow" to 5, "uAlbedo" to 8, "uNormalMap" to 9))
            objectShadowProg = GeoProgram(GeoShaders.OBJECT_SHADOW_VS, GeoShaders.SHADOW_FS)
            objectShadowCutProg = GeoProgram(GeoShaders.OBJECT_SHADOW_VS, GeoShaders.SHADOW_CUTOUT_FS, mapOf("uAlbedo" to 8))
            riderProg = GeoProgram(GeoShaders.RIDER_VS, GeoShaders.OBJECT_FS, mapOf("uShadow" to 5, "uAlbedo" to 8, "uNormalMap" to 9, "uKits" to 10))
            riderShadowProg = GeoProgram(GeoShaders.RIDER_SHADOW_VS, GeoShaders.SHADOW_FS, mapOf("uKits" to 10))
            skyProg = GeoProgram(GeoShaders.SKY_VS, GeoShaders.SKY_FS, mapOf("uNoise" to 4, "uSkyTex" to 11))
            frameUbo = GeoGl.genBuffer()
            GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, frameUbo)
            GLES30.glBufferData(GLES30.GL_UNIFORM_BUFFER, 76 * 4, null, GLES30.GL_DYNAMIC_DRAW)
            GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, 0, frameUbo)
            gridIbo = GeoGl.staticBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, GeoGl.shorts(TerrainGrid.indices), TerrainGrid.indices.size * 2)
            skyVao = GeoGl.genVao()
            GLES30.glBindVertexArray(skyVao)
            GeoGl.staticBuffer(GLES30.GL_ARRAY_BUFFER, GeoGl.floats(floatArrayOf(-1f, -1f, 3f, -1f, -1f, 3f)), 24)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
            GLES30.glBindVertexArray(0)
            noiseTex = NoiseTexture.create()
            glError = null
            glErrorScene = null
            glReady = true
        } catch (e: Throwable) {
            // No scene may be set yet: onDrawFrame tells it, and the procedural world takes over.
            Log.e(TAG, "geodata renderer: GL setup failed", e)
            glError = e
            glReady = false
            streamer?.stop()
            streamer = null
        }
        resetWorld(keepScene = true)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        surfaceW = width.coerceAtLeast(1)
        surfaceH = height.coerceAtLeast(1)
        aspect = surfaceW.toFloat() / surfaceH
    }

    /** Stops the worker; call when the view goes away (the GL objects die with the context). */
    fun release() {
        streamer?.stop()
        streamer = null
    }

    // =============================================================================================
    // Frame

    override fun onDrawFrame(gl: GL10?) {
        val ge = glError
        if (ge != null) {
            // The context has no usable programs: no scene is started, each one only hears of the error.
            val sc = pendingScene ?: scene
            if (sc != null && sc !== glErrorScene) {
                glErrorScene = sc
                sc.listener?.onFailed(ge)
            }
            GLES30.glClearColor(0.11f, 0.15f, 0.2f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        try {
            drawFrame()
        } catch (e: Throwable) {
            if (!failed) {
                failed = true
                Log.e(TAG, "geodata renderer failed", e)
                scene?.listener?.onFailed(e)
            }
            GLES30.glClearColor(0.11f, 0.15f, 0.2f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        }
    }

    private fun drawFrame() {
        val pending = pendingScene
        if (pending !== scene) startScene(pending)
        val sc = scene
        if (sc == null || failed || streamer == null) {
            GLES30.glClearColor(0.11f, 0.15f, 0.2f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
            return
        }
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016 else ((now - lastFrameMs) / 1000.0).coerceIn(0.0, 0.1)
        lastFrameMs = now
        timeSec = ((now % 3_600_000L) / 1000.0).toFloat()
        clockSec = now / 1000.0

        processResults(now)
        updateRider(sc, now, dt)
        updateCamera(sc, dt)
        updateStreaming(sc, now)
        // After the uploads and the evictions: the lists must not keep objects deleted this frame.
        if (listsDirty) {
            tileList.clear(); tileList.addAll(tiles.values)
            sceneryList.clear(); sceneryList.addAll(tileScenery.values)
            roadList.clear(); roadList.addAll(roadsGpu.values)
            dressList.clear(); dressList.addAll(dressGpu.values)
            listsDirty = false
        }
        if (protosReady && (chunksChanged || assemblyDue(now))) requestAssembly(now)

        if (!ready) {
            if (checkReady(sc)) {
                ready = true
                drawnLogged = false
                camera.invalidate()
                updateCamera(sc, 0.0)
                // The emulator screenshot workflow waits for this line before it takes a picture.
                Log.i("WorldView", "world ready: ${sc.route.id}")
                sc.listener?.onReady()
            } else {
                GLES30.glClearColor(0.11f, 0.15f, 0.2f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
                return
            }
        }
        setupFrame(sc)
        updateTunnelCut(sc)
        prepareRiders(sc, dt)
        prepareVehicles(sc, dt)
        frameTris = (riders?.triangles() ?: 0L) * (if (shadowOn) 2 else 1)
        frameGroundTris = 0L
        if (shadowOn) drawShadows()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, surfaceW, surfaceH)
        val hz = horizonSrgb
        GLES30.glClearColor(hz[0], hz[1], hz[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        bindSharedTextures()
        drawRiders(sc, shadowPass = false)
        drawRoads()
        drawTerrain(near = true)
        drawObjects(shadowPass = false)
        drawTerrain(near = false)
        drawSky()
        GLES30.glBindVertexArray(0)
        groundTris = frameGroundTris
        if (!drawnLogged) {
            // The first full frame can take many seconds on a software GPU (the emulator): the screenshot
            // workflow waits for this line, after the GPU has really finished the frame.
            GLES30.glFinish()
            drawnLogged = true
            Log.i("WorldView", "world drawn: ${sc.route.id}")
        }
    }

    // =============================================================================================
    // Scene lifecycle and streaming

    private fun startScene(sc: GeoScene?) {
        resetWorld(keepScene = false)
        streamer?.stop()
        streamer = null
        scene = sc
        failed = false
        ready = false
        smoothDistance = Double.NaN
        camera.invalidate()
        if (sc == null || sc.closed) return
        // Phones: the smaller screen side under 600 dp. The display's size, not the surface's: the
        // surface may be rendered smaller than the screen (WorldView's pixel cap).
        val dm = context.resources.displayMetrics
        val density = dm.density.coerceAtLeast(1f)
        val side = if (dm.widthPixels > 0 && dm.heightPixels > 0) min(dm.widthPixels, dm.heightPixels) else min(surfaceW, surfaceH)
        quality = GeoQuality(side / density < 600f)
        val (sceneryGen, dressingGen) = Generators.forScene(sc)
        rules = GroundRules(sc.region)
        val st = TileStreamer(sc, assets, quality, sceneryGen, dressingGen)
        streamer = st
        createMaterialArrays()
        loadSky()
        st.start()
        st.post(TileStreamer.MaterialsJob())
        st.post(TileStreamer.PrototypesJob())
    }

    /** Drops the resident world (GPU objects only when the context is still the one they belong to). */
    private fun resetWorld(keepScene: Boolean) {
        if (!keepScene) {
            for (t in tiles.values) t.delete()
            for (c in tileScenery.values) c.delete()
            for (r in roadsGpu.values) r.mesh.delete()
            for (c in dressGpu.values) c.delete()
            for (p in protoList) p.delete()
            riders?.delete()
            for (t in objectTextures) GeoGl.deleteTexture(t)
            for (t in signTextures.values) GeoGl.deleteTexture(t)
            GeoGl.deleteTexture(groundTex); GeoGl.deleteTexture(groundNTex); GeoGl.deleteTexture(roadTex); GeoGl.deleteTexture(roadNTex)
            GeoGl.deleteTexture(kitTex)
        }
        tiles.clear(); tileScenery.clear(); roadsGpu.clear(); dressGpu.clear(); requested.clear(); wanted.clear(); failedTiles.clear()
        tileList.clear(); sceneryList.clear(); roadList.clear(); dressList.clear(); listsDirty = true
        protos.clear(); protoList.clear(); protoInfo = null; materials.clear(); texRequested.clear(); texPending.clear(); objectTextures.clear(); signTextures.clear(); objectTexBytes = 0
        riders = null; protosReady = false; assemblyPending = false; assemblyDone = false; chunksChanged = true
        lodBias = 1f; objectTris = 0L; groundTris = 0L
        // The vehicles start over on the new route instead of gliding in from the old one.
        motoAhead = Double.NaN; carBehind = Double.NaN; heliAhead = Double.NaN; heliYaw = Double.NaN
        groundTex = 0; groundNTex = 0; roadTex = 0; roadNTex = 0; kitTex = 0
        groundLayersDone = 0; roadLayersDone = 0
        pendingUploads.clear()
        lastStreamX = Double.NaN
        ready = false
        if (keepScene && scene != null) {
            // Same scene on a new context: restart streaming from scratch.
            val sc = scene
            scene = null
            pendingScene = pendingScene ?: sc
        }
    }

    /**
     * The sky panorama, once per context. 4096 px wide on a tablet; 2048 on a phone, the smallest texture
     * size every OpenGL ES 3 device has to support.
     */
    private fun loadSky() {
        if (skyTex != 0 || skyTried) return
        skyTried = true
        try {
            val meta = org.json.JSONObject(assets.open("sky/$SKY.json").use { it.readBytes().decodeToString() })
            val w = if (quality.phone) 2048 else 4096
            val bmp = Materials.bitmap(assets, "sky/$SKY.webp", w, w / 4) ?: return
            val buf = ByteBuffer.allocateDirect(bmp.width * bmp.height * 4).order(ByteOrder.nativeOrder())
            bmp.copyPixelsToBuffer(buf)
            skyTex = GeoGl.texture2D(buf, bmp.width, bmp.height, true, false, 1f, GLES30.GL_REPEAT)
            bmp.recycle()
            skySunU = meta.getDouble("sunU").toFloat()
            skyMax = meta.getDouble("max").toFloat()
        } catch (e: Exception) {
            Log.w(TAG, "sky panorama not loaded; analytic sky", e)
            skyTex = 0
        }
    }

    private fun createMaterialArrays() {
        groundTex = GeoGl.textureArray(quality.groundSize, Materials.GROUND.size, true)
        groundNTex = GeoGl.textureArray(quality.groundNormalSize, Materials.GROUND.size, false)
        roadTex = GeoGl.textureArray(quality.roadSize, Materials.ROAD.size, true)
        roadNTex = GeoGl.textureArray(quality.roadSize, Materials.ROAD.size, false)
        for ((i, name) in Materials.GROUND.withIndex()) layerSize[i] = Materials.sizeOf(name).first
        createShadowMap()
    }

    private fun createShadowMap() {
        GeoGl.deleteTexture(shadowTex)
        if (shadowFbo != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(shadowFbo), 0)
        val size = quality.shadowSize
        shadowTex = GeoGl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT24, size, size)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        shadowFbo = fbo[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        shadowOn = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE
        if (!shadowOn) Log.w(TAG, "shadow map framebuffer incomplete; shadows off")
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /** Which tiles, road and dressing chunks should be resident, with priorities; posts new jobs, evicts old. */
    private fun updateStreaming(sc: GeoScene, now: Long) {
        val st = streamer ?: return
        val cx = camera.eye[0]
        val cy = -camera.eye[2]
        val moved = lastStreamX.isNaN() || hypot(cx - lastStreamX, cy - lastStreamZ) > 30.0
        if (!moved && now - lastStreamMs < 700) return
        lastStreamX = cx; lastStreamZ = cy; lastStreamMs = now
        val pack = sc.pack
        val path = pack.path
        val w = HashMap<Any, Double>(256)
        // Look-ahead point: where the rider will be in ~20 s.
        val sNow = sc.packDistance(smoothDistance.takeIf { !it.isNaN() } ?: frame.distanceM)
        val ahead = path.poseAt(sNow + max(150.0, frame.speedMps * 20))
        fun prio(key: TileKey): Double {
            val t = pack.lod(key.lod).tileM
            val (ox, oy) = pack.tileOrigin(key)
            val dx = max(0.0, max(ox - cx, cx - (ox + t)))
            val dy = max(0.0, max(oy - cy, cy - (oy + t)))
            val da = hypot(max(0.0, max(ox - ahead.x, ahead.x - (ox + t))), max(0.0, max(oy - ahead.y, ahead.y - (oy + t))))
            val d = min(hypot(dx, dy), da)
            // Screen-size priority: a coarse tile matters as much as a fine one 4x closer.
            return when (key.lod) { 0 -> d; 1 -> d / 4 + 60; else -> d / 16 + 40 }
        }
        val radii = doubleArrayOf(quality.lod0Radius, quality.lod1Radius, quality.lod2Radius)
        for (lod in 0..2) {
            for (k in pack.tilesNear(cx, cy, radii[lod], lod, TileStreamer.TILE_LAYER)) w[k] = prio(k)
            if (lod == 0) for (k in pack.tilesNear(ahead.x, ahead.y, 700.0, lod, TileStreamer.TILE_LAYER)) w.putIfAbsent(k, prio(k))
        }
        // Road chunks within reach of the camera (also the legs of a climb seen above and below).
        val chunkLen = RoadBuilder.CHUNK_SAMPLES
        for (c in 0 until st.roadChunks) {
            val a = c * chunkLen
            val b = min(path.size - 1, a + chunkLen)
            var best = Double.MAX_VALUE
            var i = a
            while (i <= b) {
                best = min(best, hypot(path.x[i] - cx, path.y[i] - cy))
                i += 10
            }
            best = min(best, hypot(path.x[b] - cx, path.y[b] - cy))
            if (best < quality.roadRadius) w[TileStreamer.RoadKey(c)] = best * 0.8
            if (best < DRESS_RADIUS) w[TileStreamer.DressKey(c)] = best + 30
        }
        wanted = w
        st.wanted = HashSet(w.keys)
        // New jobs, nearest first.
        for ((k, p) in w.entries.sortedBy { it.value }) {
            if (k in requested) continue
            requested += k
            when (k) {
                is TileKey -> st.post(TileStreamer.TileJob(k, p))
                is TileStreamer.RoadKey -> st.post(TileStreamer.RoadJob(k.chunk, p))
                is TileStreamer.DressKey -> st.post(TileStreamer.DressingJob(k.chunk, p))
            }
        }
        // Evict what fell out of reach (with some slack so the border does not churn).
        val evict = ArrayList<Any>()
        for (k in tiles.keys) if (k !in w && tileDistance(sc, k, cx, cy) > radii[k.lod] * 1.25) evict += k
        for (k in roadsGpu.keys) if (TileStreamer.RoadKey(k) !in w) evict += TileStreamer.RoadKey(k)
        for (k in dressGpu.keys) if (TileStreamer.DressKey(k) !in w) evict += TileStreamer.DressKey(k)
        if (evict.isNotEmpty()) listsDirty = true
        for (k in evict) {
            when (k) {
                is TileKey -> { tiles.remove(k)?.delete(); tileScenery.remove(k)?.delete(); chunksChanged = true }
                is TileStreamer.RoadKey -> roadsGpu.remove(k.chunk)?.mesh?.delete()
                is TileStreamer.DressKey -> { dressGpu.remove(k.chunk)?.delete(); chunksChanged = true }
            }
            requested -= k
        }
        if (evict.isNotEmpty()) releaseUnusedSigns()
        // Requests that are no longer wanted and not resident may be asked for again later.
        requested.retainAll { it in w || it in residentKeys(it) || it in failedTiles }
    }

    private fun residentKeys(k: Any): Set<Any> = when (k) {
        is TileKey -> if (tiles.containsKey(k)) setOf(k) else emptySet()
        is TileStreamer.RoadKey -> if (roadsGpu.containsKey(k.chunk)) setOf(k) else emptySet()
        is TileStreamer.DressKey -> if (dressGpu.containsKey(k.chunk)) setOf(k) else emptySet()
        else -> emptySet()
    }

    private fun tileDistance(sc: GeoScene, k: TileKey, cx: Double, cy: Double): Double {
        val t = sc.pack.lod(k.lod).tileM
        val (ox, oy) = sc.pack.tileOrigin(k)
        return hypot(max(0.0, max(ox - cx, cx - (ox + t))), max(0.0, max(oy - cy, cy - (oy + t))))
    }

    /** Uploads finished worker results within the frame's budget (bytes and time). */
    private fun processResults(now: Long) {
        val st = streamer ?: return
        while (true) pendingUploads += (st.results.poll() ?: break)
        if (pendingUploads.isEmpty()) return
        val budget = if (ready) quality.uploadBytesPerFrame else quality.loadingBytesPerFrame
        val t0 = SystemClock.elapsedRealtimeNanos()
        var bytes = 0
        val it = pendingUploads.iterator()
        while (it.hasNext()) {
            if (bytes >= budget) break
            if (SystemClock.elapsedRealtimeNanos() - t0 > quality.uploadNanosPerFrame && ready) break
            val r = it.next()
            it.remove()
            bytes += upload(r)
        }
        @Suppress("UNUSED_EXPRESSION") now
    }

    private fun upload(r: Any): Int {
        listsDirty = true
        when (r) {
            is TileStreamer.MaterialLayer -> return uploadLayer(r)
            is TileStreamer.Prototypes -> return uploadPrototypes(r)
            is TileStreamer.ObjectTexture -> return uploadObjectTexture(r)
            is TileStreamer.Tile -> {
                val cpu = r.cpu
                if (cpu == null) { failedTiles += r.key; return 0 }
                if (r.key !in wanted) { requested -= r.key; return 0 }
                tiles.remove(r.key)?.delete()
                val g = GpuTile(r.key, cpu, gridIbo)
                tiles[r.key] = g
                var bytes = g.bytes
                r.scenery?.let { c ->
                    tileScenery.remove(r.key)?.delete()
                    val chunk = chunkGpu(c, r.bounds ?: meshBounds(c), r.shadow)
                    tileScenery[r.key] = chunk
                    bytes += chunk.bytes
                    chunksChanged = true
                }
                return bytes
            }
            is TileStreamer.Road -> {
                val k = TileStreamer.RoadKey(r.cpu.chunk)
                if (k !in wanted) { requested -= k; return 0 }
                roadsGpu.remove(r.cpu.chunk)?.mesh?.delete()
                val g = GpuRoad(r.cpu)
                roadsGpu[r.cpu.chunk] = g
                return g.mesh.bytes
            }
            is TileStreamer.Dressing -> {
                val k = TileStreamer.DressKey(r.chunk)
                if (k !in wanted) { requested -= k; return 0 }
                dressGpu.remove(r.chunk)?.delete()
                val c = chunkGpu(r.data, r.bounds, r.shadow)
                dressGpu[r.chunk] = c
                chunksChanged = true
                return c.bytes
            }
            is TileStreamer.Assembled -> return uploadAssembly(r.result)
            is TileStreamer.Skipped -> { requested -= r.key; return 0 }
            is TileStreamer.Failed -> throw r.error
        }
        return 0
    }

    private fun uploadLayer(r: TileStreamer.MaterialLayer): Int {
        val d = r.data
        val ground = r.array == TileStreamer.GROUND_ARRAY
        val (a, n) = if (ground) groundTex to groundNTex else roadTex to roadNTex
        GeoGl.uploadLayerRows(a, r.layer, d.size, 0, d.size, d.albedo)
        GeoGl.uploadLayerRows(n, r.layer, d.normalSize, 0, d.normalSize, d.normal)
        streamer?.layerUploaded()
        if (ground) {
            layerMean[r.layer * 3] = d.mean[0].coerceAtLeast(0.005f)
            layerMean[r.layer * 3 + 1] = d.mean[1].coerceAtLeast(0.005f)
            layerMean[r.layer * 3 + 2] = d.mean[2].coerceAtLeast(0.005f)
            if (++groundLayersDone == Materials.GROUND.size) { GeoGl.finishArray(groundTex); GeoGl.finishArray(groundNTex) }
        } else {
            roadMean[r.layer] = (d.mean[0] * 0.2126f + d.mean[1] * 0.7152f + d.mean[2] * 0.0722f).coerceAtLeast(0.005f)
            if (++roadLayersDone == Materials.ROAD.size) { GeoGl.finishArray(roadTex); GeoGl.finishArray(roadNTex) }
        }
        return (d.size * d.size + d.normalSize * d.normalSize) * 4
    }

    private fun materialOf(m: SceneMaterial): MaterialGpu = materials.getOrPut(m.key) {
        MaterialGpu(m).also { g ->
            // Town signs carry no texture name: their face is drawn per town from the key (TownSigns).
            val tex = m.texture ?: m.key.takeIf { TownSigns.isSign(it) }
            if (tex != null) {
                if (m.uvMetres && m.texture != null) { val (su, sv) = Materials.sizeOf(tex); g.uvScaleU = 1f / su; g.uvScaleV = 1f / sv }
                if (texRequested.add(tex)) { texPending += tex; streamer?.post(TileStreamer.ObjectTextureJob(tex)) }
            }
        }
    }

    private fun uploadObjectTexture(r: TileStreamer.ObjectTexture): Int {
        texPending -= r.name
        val d = r.data ?: return 0
        val sign = TownSigns.isSign(r.name)
        val wrap = if (sign) GLES30.GL_CLAMP_TO_EDGE else GLES30.GL_REPEAT
        val mips = d.albedoMips
        val a = if (mips != null) GeoGl.texture2DMips(d.albedo, d.width, d.height, mips, 4f, wrap)
        else GeoGl.texture2D(d.albedo, d.width, d.height, true, true, 4f, wrap)
        val n = d.normal?.let { GeoGl.texture2D(it, d.width, d.height, false, true, 4f, wrap) } ?: 0
        if (sign) signTextures[r.name] = a else { objectTextures += a; if (n != 0) objectTextures += n }
        for (m in materials.values) if ((m.m.texture ?: m.m.key) == r.name) { m.albedo = a; m.normal = n }
        objectTexBytes += d.width * d.height * (if (n != 0) 8 else 4) * 4 / 3
        debugObjectTex[r.name] = d.width * d.height * (if (n != 0) 8 else 4) * 4 / 3
        return d.width * d.height * 8
    }

    /**
     * Town sign faces are one texture per town, so a long stage would collect dozens of them: when
     * chunks are evicted, the signs no resident chunk uses any more are deleted (and drawn again if
     * the town comes back into reach).
     */
    private fun releaseUnusedSigns() {
        if (signTextures.isEmpty()) return
        signsInUse.clear()
        for (c in tileScenery.values) for ((m, _) in c.meshes) if (TownSigns.isSign(m.m.key)) signsInUse += m.m.key
        for (c in dressGpu.values) for ((m, _) in c.meshes) if (TownSigns.isSign(m.m.key)) signsInUse += m.m.key
        val it = signTextures.entries.iterator()
        while (it.hasNext()) {
            val (k, tex) = it.next()
            if (k in signsInUse) continue
            GeoGl.deleteTexture(tex)
            materials.remove(k)
            texRequested.remove(k)
            objectTexBytes -= quality.objectTexSize / 2 * (quality.objectTexSize / 4) * 4 * 4 / 3
            it.remove()
        }
    }

    private fun uploadPrototypes(r: TileStreamer.Prototypes): Int {
        var bytes = 0
        for (p in r.scenery + r.dressing) {
            val lods = p.lods.map { l -> l.parts.map { (m, mesh) -> PartGpu(materialOf(m), MeshGpu(mesh)) } }
            val g = ProtoGpu(p, lods)
            protos[p.id] = g
            protoList += g
            bytes += g.bytes
        }
        riders = RiderGpu(r.riders, r.riders.standing) { materialOf(it) }
        kitTex = kitTexture(r.kits)
        kitCount = max(1, r.kits.size)
        // Team cars are white with the team's colour as tint (linear, like vertex colours).
        kitTints = FloatArray(kitCount) { i ->
            val c = r.kits.getOrNull(i)?.jersey ?: floatArrayOf(1f, 1f, 1f)
            fun b(v: Float) = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
            ((b(c[0]) shl 16) or (b(c[1]) shl 8) or b(c[2])).toFloat()
        }
        protosReady = true
        return bytes
    }

    private fun kitTexture(kits: List<Kit>): Int {
        val n = max(1, kits.size)
        val buf = GeoGl.direct(8 * n * 4)
        fun enc(c: Float): Byte {
            val v = c.coerceIn(0f, 1f)
            val s = if (v <= 0.0031308f) v * 12.92f else 1.055f * Math.pow(v.toDouble(), 1 / 2.4).toFloat() - 0.055f
            return (s * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
        }
        for (k in 0 until n) {
            val kit = kits.getOrNull(k)
            val cols = if (kit == null) List(5) { floatArrayOf(0.5f, 0.5f, 0.5f) } else listOf(kit.jersey, kit.jerseyAccent, kit.shorts, kit.helmet, kit.bike)
            for (j in 0 until 8) {
                val c = cols.getOrElse(j) { floatArrayOf(1f, 1f, 1f) }
                buf.put(enc(c[0])); buf.put(enc(c[1])); buf.put(enc(c[2])); buf.put(-1)
            }
        }
        buf.position(0)
        return GeoGl.texture2D(buf, 8, n, true, false, 1f, GLES30.GL_CLAMP_TO_EDGE).also {
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        }
    }

    private fun chunkGpu(c: SceneryChunk, bounds: FloatArray, shadow: ShadowMesh?): GpuChunk =
        GpuChunk(c, c.meshes.map { (m, mesh) -> materialOf(m) to MeshGpu(mesh) }, bounds, shadow)

    private fun assemblyDue(now: Long): Boolean {
        if (assemblyPending) return false
        val ex = camera.eye[0]; val ez = camera.eye[2]
        if (lastAssemblyX.isNaN()) return true
        val moved = hypot(ex - lastAssemblyX, ez - lastAssemblyZ)
        val dx = camera.look[0] - ex; val dz = camera.look[2] - ez
        val l = hypot(dx, dz).coerceAtLeast(1e-6)
        val turn = (dx / l) * lastAssemblyDirX + (dz / l) * lastAssemblyDirZ
        // Over the triangle budget: assemble again with coarser LODs, also when the camera stands still.
        val overBudget = objectTris > objectBudget() * 1.05 && lodBias < MAX_LOD_BIAS
        return (moved > 12.0 || turn < 0.985 || overBudget) && now - lastAssemblyMs > 150
    }

    /**
     * Object triangles for the next assembly: [GeoQuality.objectTriangles], less where terrain and road
     * already take more than their usual share of [GeoQuality.totalTriangles]. Never under half, so a
     * city keeps its trees and crowds near the road (those LODs go first anyway).
     */
    private fun objectBudget(): Long {
        val q = quality
        return min(q.objectTriangles, max(q.objectTriangles / 2, q.totalTriangles - groundTris))
    }

    private fun requestAssembly(now: Long) {
        if (assemblyPending) return
        val st = streamer ?: return
        val sources = ArrayList<InstanceAssembler.Source>()
        for (c in tileScenery.values) c.source?.let { sources += it }
        for (c in dressGpu.values) c.source?.let { sources += it }
        val info = protoInfo ?: HashMap<String, InstanceAssembler.ProtoInfo>().also { m ->
            for (p in protoList) {
                val lods = p.proto.lods
                m[p.proto.id] = InstanceAssembler.ProtoInfo(
                    FloatArray(lods.size) { lods[it].maxDistanceM }, p.proto.radiusM,
                    BooleanArray(lods.size) { l -> lods[l].parts.any { it.first.castsShadow } }, p.shadowFarLod >= 0,
                )
            }
            if (protosReady) protoInfo = m
        }
        val ex = camera.eye[0]; val ey = camera.eye[1]; val ez = camera.eye[2]
        val dx = camera.look[0] - ex; val dz = camera.look[2] - ez
        val l = hypot(dx, dz).coerceAtLeast(1e-6)
        // Half the horizontal field of view plus a margin for turning until the next rebuild.
        val hfov = 2 * atan(Math.tan(Math.toRadians(currentVfov / 2.0)) * aspect)
        val cosLimit = cos(min(PI, hfov / 2 + Math.toRadians(40.0))).toFloat()
        val sCenter = shadowCenter()
        // LOD bias towards the object triangle budget: up quickly, back down slowly (no oscillation). The
        // step up grows with the overshoot (the count falls about with the square of the bias), so a city
        // that starts at twice the budget is in it after two or three assemblies, not seven.
        val budget = objectBudget()
        if (objectTris > budget) lodBias = min(MAX_LOD_BIAS, lodBias * sqrt(objectTris.toFloat() / budget).coerceIn(1.2f, 1.6f))
        else if (objectTris < budget * 0.7) lodBias = max(1f, lodBias / 1.05f)
        assemblyGeneration++
        st.post(TileStreamer.AssembleJob(InstanceAssembler.Snapshot(ex, ey, ez, (dx / l).toFloat(), (dz / l).toFloat(), cosLimit,
            sCenter[0], sCenter[2], quality.shadowRadius * 1.15 + 10, sources, info, assemblyGeneration, lodBias)))
        assemblyPending = true
        chunksChanged = false
        lastAssemblyMs = now
        lastAssemblyX = ex; lastAssemblyZ = ez
        lastAssemblyDirX = dx / l; lastAssemblyDirZ = dz / l
    }

    private fun uploadAssembly(r: InstanceAssembler.Result): Int {
        assemblyPending = false
        for (p in protoList) p.clear()
        var bytes = 0
        for (b in r.buckets) {
            val p = protos[b.protoId] ?: continue
            if (b.lod >= p.lods.size) continue
            p.upload(b.lod, b.data, b.count, b.nearCount)
            bytes += b.count * 32
        }
        for (b in r.shadows) {
            val p = protos[b.protoId] ?: continue
            p.uploadShadow(b.data, b.count, b.far)
            bytes += b.count * 32
        }
        assemblyOriginX = r.originX; assemblyOriginY = r.originY; assemblyOriginZ = r.originZ
        assemblyDone = true
        return bytes
    }

    private fun checkReady(sc: GeoScene): Boolean {
        if (groundLayersDone < Materials.GROUND.size || roadLayersDone < Materials.ROAD.size) return false
        if (!protosReady || texPending.isNotEmpty()) return false
        if (smoothDistance.isNaN()) return false
        val pose = sc.pack.path.poseAt(sc.packDistance(smoothDistance))
        for ((k, p) in wanted) {
            val need = when (k) {
                is TileKey -> when (k.lod) { 0 -> p < 1500; 1 -> tileDistance(sc, k, pose.x, pose.y) < 7000; else -> true }
                is TileStreamer.RoadKey -> p < 1500 * 0.8
                is TileStreamer.DressKey -> p < 800
                else -> false
            }
            if (!need) continue
            val have = when (k) {
                is TileKey -> tiles.containsKey(k) || k in failedTiles
                is TileStreamer.RoadKey -> roadsGpu.containsKey(k.chunk)
                is TileStreamer.DressKey -> dressGpu.containsKey(k.chunk)
                else -> true
            }
            if (!have) return false
        }
        if (wanted.isEmpty()) return false
        // The scenery must be placed before the first frame, or trees would pop in.
        if (chunksChanged || assemblyPending || !assemblyDone) return false
        return true
    }

    // =============================================================================================
    // Rider and camera

    private fun updateRider(sc: GeoScene, now: Long, dt: Double) {
        val f = frame
        val predicted = when {
            f.paused -> if (smoothDistance.isNaN()) f.distanceM else maxOf(f.distanceM, smoothDistance).coerceAtMost(f.distanceM + 10)
            else -> f.distanceM + f.speedMps * ((now - f.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
        }
        val before = smoothDistance
        if (smoothDistance.isNaN() || abs(predicted - smoothDistance) > 20) {
            smoothDistance = predicted
            camera.invalidate()
        } else {
            val next = smoothDistance + (predicted - smoothDistance) * (1 - exp(-dt * 4.0))
            if (next > smoothDistance) smoothDistance = next
        }
        val moved = if (before.isNaN()) 0.0 else (smoothDistance - before).coerceIn(0.0, 5.0)
        if (!f.paused && moved > 0.0005) {
            crank += f.cadenceRpm / 60.0 * 2 * PI * dt
            wheel += moved / RIDER_WHEEL_R
        }
        if (!f.paused && f.pacerSpeedMps > 0.3) {
            pacerCrank += 85.0 / 60.0 * 2 * PI * dt
            pacerWheel += f.pacerSpeedMps * dt / RIDER_WHEEL_R
        }
        val path = sc.pack.path
        val s = sc.packDistance(smoothDistance)
        val lat = if (f.lateralM.isNaN()) defaultLane(path.width[min(path.size - 1, (s / path.stepM).toInt())].toDouble()) else f.lateralM
        placeOnRoad(sc, s, lat, riderPos, riderFwd)
        riderLean = leanAt(sc, s, max(f.speedMps, 0.0))
    }

    private fun defaultLane(width: Double): Double = -min(width * 0.22, 1.4)

    /** GL position on the road at pack distance [s], [lateral] metres left of the centre line; forward into [fwd]. */
    private fun placeOnRoad(sc: GeoScene, s: Double, lateral: Double, out: DoubleArray, fwd: DoubleArray?) {
        val p = sc.pack.path.poseAt(s)
        // Left of travel in map coordinates is (-dirY, dirX).
        val x = p.x - p.dirY * lateral
        val y = p.y + p.dirX * lateral
        val hw = p.widthM / 2
        val crown = 0.025 * max(0.0, hw - abs(lateral))
        out[0] = x; out[1] = p.z + RoadBuilder.LIFT + crown; out[2] = -y
        if (fwd != null) { fwd[0] = p.dirX; fwd[1] = 0.0; fwd[2] = -p.dirY }
    }

    /** Lean into the bend for the speed: tan(lean) = v^2 / (g r), curvature from the heading change over 20 m. */
    private fun leanAt(sc: GeoScene, s: Double, v: Double): Double {
        val path = sc.pack.path
        val a = path.poseAt(s - 10)
        val b = path.poseAt(s + 10)
        var d = atan2(b.dirY, b.dirX) - atan2(a.dirY, a.dirX)
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        val curvature = d / 20.0
        return atan(v * v * curvature / 9.81).coerceIn(-0.6, 0.6)
    }

    private var currentVfov = 50f
    private var lensShift = 0.1f
    private var lastBobPhase = 0.0

    private fun updateCamera(sc: GeoScene, dt: Double) {
        if (smoothDistance.isNaN()) return
        val fp = frame.firstPerson
        if (fp != lastFirstPerson) { lastFirstPerson = fp; camera.invalidate() }
        val path = sc.pack.path
        val s = sc.packDistance(smoothDistance)
        val lat = if (frame.lateralM.isNaN()) defaultLane(path.width[min(path.size - 1, (s / path.stepM).toInt())].toDouble()) else frame.lateralM
        val portrait = aspect < 1f
        // Moto camera a few metres behind: on TV the rider fills a good part of the frame. A portrait
        // screen's view is very tall (below), so the camera comes closer to keep the rider large.
        val back = if (portrait) 2.0 else 4.6
        placeOnRoad(sc, s - back, lat * 0.85, behindPos, null)
        placeOnRoad(sc, s + if (portrait) 6.0 else 10.0, lat * 0.6, aheadPos, null)
        placeOnRoad(sc, min(path.lengthM, s + 32.0), lat, aheadFarPos, null)
        // Past the finish the look-at carries straight on.
        lastBobPhase += dt * frame.cadenceRpm / 60.0 * 2 * PI
        val bob = if (fp && !frame.paused) 0.012 * sin(lastBobPhase * 2) else 0.0
        camera.update(fp, riderPos, riderFwd, behindPos, aheadPos, aheadFarPos, riderLean, aspect, dt, bob)
        // Both keep a horizontal field of view: 60 degrees on a portrait screen (both sides of the road
        // still show; it is already a 104 degree tall view on a 20:9 phone), 74-80 in landscape.
        currentVfov = if (portrait) camera.verticalFov(if (fp) 62.0 else 60.0, aspect)
        else camera.verticalFov(if (fp) 80.0 else 74.0, aspect)
        // Where that tall view goes: in landscape the horizon sits in the upper third so the road gets the
        // space. A portrait chase view stays centred: the horizon just above half height and the rider in
        // the lower middle, instead of a small rider above half a screen of asphalt.
        lensShift = if (portrait) (if (fp) 0.1f else 0f) else 0.1f
    }

    // =============================================================================================
    // Per-frame constants

    private val horizonSrgb = FloatArray(3)

    private fun setupFrame(sc: GeoScene) {
        val e = camera.eye
        // View from the origin (camera-relative world), with roll about the view axis.
        val fx = (camera.look[0] - e[0]).toFloat(); val fy = (camera.look[1] - e[1]).toFloat(); val fz = (camera.look[2] - e[2]).toFloat()
        Matrix.setLookAtM(view, 0, 0f, 0f, 0f, fx, fy, fz, 0f, 1f, 0f)
        if (abs(camera.roll) > 1e-4) {
            Matrix.setRotateM(tmpM, 0, Math.toDegrees(camera.roll).toFloat(), 0f, 0f, 1f)
            Matrix.multiplyMM(lightView, 0, tmpM, 0, view, 0)
            System.arraycopy(lightView, 0, view, 0, 16)
        }
        val near = if (frame.firstPerson) 0.35f else 0.6f
        Matrix.perspectiveM(proj, 0, currentVfov, aspect, near, FAR_PLANE)
        // Off-axis lens: the view direction sits off the centre of the screen ([lensShift], see updateCamera);
        // verticals stay vertical, unlike tilting the camera.
        proj[9] = -lensShift
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)
        extractPlanes(viewProj, planes)

        // Sun for the route's place and the time of day.
        val lat = sc.meta.projection.lat0
        val t = frame.timeOfDayH
        if (lat != sunLat || t != sunTime) {
            val d = SunPosition.direction(lat, sc.meta.projection.lon0, t)
            sun[0] = d[0]; sun[1] = d[1]; sun[2] = d[2]
            sunLat = lat; sunTime = t
        }
        val elev = Math.toDegrees(Math.asin(sun[1].coerceIn(-1.0, 1.0)))
        // Lower sun: warmer and weaker light, a little more haze glow.
        val warm = ((40.0 - elev) / 35.0).coerceIn(0.0, 1.0).toFloat()
        val sunI = (0.55 + 0.45 * ((elev - 4) / 30.0).coerceIn(0.0, 1.0)).toFloat() * SUN
        val camAlt = e[1].toFloat()
        val b = block
        System.arraycopy(viewProj, 0, b, 0, 16)
        computeShadowMatrices()
        System.arraycopy(shadowMat, 0, b, 16, 16)
        b[32] = sun[0].toFloat(); b[33] = sun[1].toFloat(); b[34] = sun[2].toFloat(); b[35] = 1f / quality.shadowSize
        b[36] = sunI * (1.0f); b[37] = sunI * (0.96f - 0.10f * warm); b[38] = sunI * (0.90f - 0.22f * warm); b[39] = timeSec
        // Sky light on a horizontal surface is a fifth of the sun's: TV shadows are deep but not black.
        b[40] = 0.30f; b[41] = 0.37f; b[42] = 0.50f; b[43] = EXPOSURE
        b[44] = 0.11f; b[45] = 0.10f; b[46] = 0.08f; b[47] = CURVATURE
        // Haze: extinction at sea level falls off with height (scale height 1.6 km).
        val falloff = 1f / 1600f
        val sea = 3.8e-5f
        b[48] = sea * exp(-camAlt * falloff); b[49] = falloff; b[50] = 0.97f; b[51] = if (shadowOn) 1f else 0f
        b[52] = 0.80f; b[53] = 0.96f; b[54] = 1.22f; b[55] = 1.0f
        b[56] = 0.085f; b[57] = 0.19f; b[58] = 0.50f; b[59] = 0.32f
        b[60] = 0.50f + 0.05f * warm; b[61] = 0.60f; b[62] = 0.78f - 0.06f * warm; b[63] = camAlt
        b[64] = (e[0] - floor(e[0] / TEX_PERIOD) * TEX_PERIOD).toFloat()
        b[65] = 0f
        b[66] = (e[2] - floor(e[2] / TEX_PERIOD) * TEX_PERIOD).toFloat()
        b[67] = quality.shadowRadius.toFloat() * 1.05f
        b[68] = 0.8f; b[69] = 0.6f; b[70] = 1.0f; b[71] = 0f
        b[72] = frame.exposure; b[73] = frame.contrast; b[74] = frame.saturation; b[75] = frame.detail
        blockBuf.position(0)
        blockBuf.put(b)
        blockBuf.position(0)
        GLES30.glBindBuffer(GLES30.GL_UNIFORM_BUFFER, frameUbo)
        GLES30.glBufferSubData(GLES30.GL_UNIFORM_BUFFER, 0, 76 * 4, blockBuf)
        GLES30.glBindBufferBase(GLES30.GL_UNIFORM_BUFFER, 0, frameUbo)
        // The clear colour is the horizon haze, so a gap at the horizon never shows.
        for (i in 0 until 3) {
            val x = b[60 + i] * EXPOSURE * frame.exposure
            val a = ((x * (2.51f * x + 0.03f)) / (x * (2.43f * x + 0.59f) + 0.14f)).coerceIn(0f, 1f)
            horizonSrgb[i] = if (a <= 0.0031308f) a * 12.92f else (1.055 * Math.pow(a.toDouble(), 1 / 2.4) - 0.055).toFloat()
        }
        // The same grading as finalColor, so the clear colour matches the haze.
        val hl = horizonSrgb[0] * 0.2126f + horizonSrgb[1] * 0.7152f + horizonSrgb[2] * 0.0722f
        for (i in 0 until 3) horizonSrgb[i] = ((hl + (horizonSrgb[i] - hl) * frame.saturation - 0.5f) * frame.contrast + 0.5f).coerceIn(0f, 1f)
    }

    /** Centre of the shadow box: ahead of the camera on the ground, snapped to whole shadow texels in light space. */
    private val drawRadii = DoubleArray(3)
    private val shadowC = DoubleArray(3)

    private fun shadowCenter(): DoubleArray {
        val e = camera.eye
        val dx = camera.look[0] - e[0]; val dz = camera.look[2] - e[2]
        val l = hypot(dx, dz).coerceAtLeast(1e-6)
        val ahead = quality.shadowRadius * 0.45
        shadowC[0] = riderPos[0] + dx / l * ahead; shadowC[1] = riderPos[1]; shadowC[2] = riderPos[2] + dz / l * ahead
        return shadowC
    }

    private val lightRight = DoubleArray(3)
    private val lightUp = DoubleArray(3)

    private fun computeShadowMatrices() {
        val c = shadowCenter()
        val r = quality.shadowRadius
        // Light basis: right = normalize(up x sun) unless the sun is overhead.
        val sx = sun[0]; val sy = sun[1]; val sz = sun[2]
        var rx = sz; var rz = -sx
        var rl = hypot(rx, rz)
        if (rl < 1e-4) { rx = 1.0; rz = 0.0; rl = 1.0 }
        rx /= rl; rz /= rl
        lightRight[0] = rx; lightRight[1] = 0.0; lightRight[2] = rz
        // up = sun x right
        lightUp[0] = sy * rz - sz * 0.0; lightUp[1] = sz * rx - sx * rz; lightUp[2] = sx * 0.0 - sy * rx
        // Snap the centre to texels along right and up, in world space, so the shadow edges do not swim.
        val texel = 2 * r / quality.shadowSize
        val pr = c[0] * lightRight[0] + c[2] * lightRight[2]
        val pu = c[0] * lightUp[0] + c[1] * lightUp[1] + c[2] * lightUp[2]
        val dr = floor(pr / texel) * texel - pr
        val du = floor(pu / texel) * texel - pu
        val cx = c[0] + lightRight[0] * dr + lightUp[0] * du
        val cy = c[1] + lightUp[1] * du
        val cz = c[2] + lightRight[2] * dr + lightUp[2] * du
        val e = camera.eye
        val ox = (cx - e[0]).toFloat(); val oy = (cy - e[1]).toFloat(); val oz = (cz - e[2]).toFloat()
        val dist = 400f
        Matrix.setLookAtM(lightView, 0, ox + sx.toFloat() * dist, oy + sy.toFloat() * dist, oz + sz.toFloat() * dist, ox, oy, oz,
            lightUp[0].toFloat(), lightUp[1].toFloat(), lightUp[2].toFloat())
        val rf = r.toFloat()
        Matrix.orthoM(lightProj, 0, -rf, rf, -rf, rf, 1f, dist * 2)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
        // Bias: clip [-1, 1] to [0, 1].
        Matrix.setIdentityM(tmpM, 0)
        tmpM[0] = 0.5f; tmpM[5] = 0.5f; tmpM[10] = 0.5f; tmpM[12] = 0.5f; tmpM[13] = 0.5f; tmpM[14] = 0.5f
        Matrix.multiplyMM(shadowMat, 0, tmpM, 0, lightVP, 0)
    }

    private fun bindSharedTextures() {
        bindTex(2, GLES30.GL_TEXTURE_2D_ARRAY, groundTex)
        bindTex(3, GLES30.GL_TEXTURE_2D_ARRAY, groundNTex)
        bindTex(4, GLES30.GL_TEXTURE_2D, noiseTex)
        bindTex(5, GLES30.GL_TEXTURE_2D, if (shadowOn) shadowTex else 0)
        bindTex(6, GLES30.GL_TEXTURE_2D_ARRAY, roadTex)
        bindTex(7, GLES30.GL_TEXTURE_2D_ARRAY, roadNTex)
        bindTex(10, GLES30.GL_TEXTURE_2D, kitTex)
    }

    private fun bindTex(unit: Int, target: Int, tex: Int) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit)
        GLES30.glBindTexture(target, tex)
    }

    // =============================================================================================
    // Drawing

    private fun relX(x: Double) = (x - camera.eye[0]).toFloat()
    private fun relY(y: Double) = (y - camera.eye[1]).toFloat()
    private fun relZ(z: Double) = (z - camera.eye[2]).toFloat()

    private fun drawTerrain(near: Boolean) {
        val sc = scene ?: return
        val r = rules ?: return
        val cut = near && tunN > 0
        val prog = if (cut) terrainCutProg else if (near) terrainProg else terrainFarProg
        prog.use()
        val p = prog.p
        if (cut) {
            GLES30.glUniform4fv(p.loc("uTunA"), tunN, tunA, 0)
            GLES30.glUniform4fv(p.loc("uTunB"), tunN, tunB, 0)
            GLES30.glUniform1i(p.loc("uTunN"), tunN)
            GLES30.glUniform4f(p.loc("uTunBox"), tunBox[0], tunBox[1], tunBox[2], tunBox[3])
        }
        if (near) {
            p.set("uDetailFar", if (quality.phone) 450f else 600f)
            p.set("uShoulderGain", groundGain(Materials.ground("gravel_shoulder"), SHOULDER_ALBEDO))
            GLES30.glUniform1iv(p.loc("uClassLayer"), 16, r.classLayer, 0)
            GLES30.glUniform3fv(p.loc("uLayerMean"), Materials.GROUND.size, layerMean, 0)
            GLES30.glUniform1fv(p.loc("uLayerSize"), Materials.GROUND.size, layerSize, 0)
            GLES30.glUniform4f(p.loc("uRules"), r.alpineFromM, r.snowFromM, r.rockLayer.toFloat(), r.forestLayer.toFloat())
            GLES30.glUniform4f(p.loc("uRules2"), r.alpineLayer.toFloat(), r.snowLayer.toFloat(), r.gravelLayer.toFloat(), r.soilLayer.toFloat())
            GLES30.glUniform4f(p.loc("uCropLayers"), r.cropLayers[0].toFloat(), r.cropLayers[1].toFloat(), r.cropLayers[2].toFloat(), r.cropLayers[3].toFloat())
        }
        p.set("uSatGain", r.satGain)
        val cx = camera.eye[0]; val cz = camera.eye[2]
        val pack = sc.pack
        val radii = drawRadii
        radii[0] = quality.lod0Radius * 1.1; radii[1] = quality.lod1Radius * 1.1; radii[2] = quality.lod2Radius * 1.1
        // Polygon offset pushes the grid behind the road and the corridor strip it lies under.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 2f)
        for (ti in 0 until tileList.size) {
            val t = tileList[ti]
            val lod = t.key.lod
            if ((lod == 0) != near) continue
            if (!active(t, radii)) continue
            // Blocks that a finer resident tile already covers are skipped.
            var mask = 0xFFFF
            if (lod > 0) {
                val children = t.children!!
                for (b in 0 until 16) {
                    val ct = tiles[children[b]]
                    if (ct != null && active(ct, radii)) mask = mask and (1 shl b).inv()
                }
                if (mask == 0) continue
            }
            val ox = relX(t.anchorX); val oz = relZ(t.anchorZ)
            // Frustum test per block.
            val bs = t.size / 4f
            for (b in 0 until 16) {
                if (mask and (1 shl b) == 0) continue
                val br = b / 4; val bc = b % 4
                val x0 = ox + bc * bs; val z0 = oz - t.size + br * bs
                val y0 = t.blockMin[b] - camera.eye[1].toFloat(); val y1 = t.blockMax[b] - camera.eye[1].toFloat()
                if (!boxVisible(x0, y0 - 300f, z0, x0 + bs, y1, z0 + bs)) mask = mask and (1 shl b).inv()
            }
            if (mask == 0) continue
            val dist = hypot(max(0.0, max(t.anchorX - cx, cx - (t.anchorX + t.size))), max(0.0, max((t.anchorZ - t.size) - cz, cz - t.anchorZ)))
            val res = when (lod) {
                0 -> if (dist < 900) 0 else if (dist < 1900) 1 else 2
                1 -> if (dist < 6000) 0 else 1
                else -> if (dist < 18000) 0 else if (dist < 36000) 1 else 2
            }
            GLES30.glUniform3f(p.loc("uOffset"), ox, -camera.eye[1].toFloat(), oz)
            GLES30.glUniform2f(p.loc("uTexOrigin"), texMod(t.anchorX), texMod(-t.anchorZ))
            p.set("uTileSize", t.size)
            bindTex(0, GLES30.GL_TEXTURE_2D, t.sat)
            if (near) {
                bindTex(1, GLES30.GL_TEXTURE_2D, t.lc)
                p.set("uLcCells", t.lcCells.toFloat())
            }
            GLES30.glBindVertexArray(t.vao)
            var b = 0
            while (b < 16) {
                if (mask and (1 shl b) == 0) { b++; continue }
                var e = b
                while (e + 1 < 16 && mask and (1 shl (e + 1)) != 0) e++
                val start = TerrainGrid.rangeStart[res][b]
                val count = TerrainGrid.rangeStart[res][e] + TerrainGrid.rangeCount[res][e] - start
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_SHORT, start * 2)
                frameGroundTris += count / 3
                b = e + 1
            }
            if (near && t.corrCount > 0 && dist < 1600) {
                // The corridor strip is the real ground: no offset, it wins over the grid.
                GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
                GLES30.glBindVertexArray(t.corrVao)
                GLES30.glDrawElements(GLES30.GL_TRIANGLES, t.corrCount, GLES30.GL_UNSIGNED_INT, 0)
                frameGroundTris += t.corrCount / 3
                GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
            }
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        @Suppress("UNUSED_EXPRESSION") pack
    }

    private fun active(t: GpuTile, radii: DoubleArray): Boolean {
        val cx = camera.eye[0]; val cz = camera.eye[2]
        val d = hypot(max(0.0, max(t.anchorX - cx, cx - (t.anchorX + t.size))), max(0.0, max((t.anchorZ - t.size) - cz, cz - t.anchorZ)))
        return d <= radii[t.key.lod]
    }

    /** Scale that brings ground layer [layer] to a mean (luminance) albedo of [target]. */
    private fun groundGain(layer: Int, target: Float): Float {
        val o = layer * 3
        val l = layerMean[o] * 0.2126f + layerMean[o + 1] * 0.7152f + layerMean[o + 2] * 0.0722f
        return if (l > 0.005f) target / l else 1f
    }

    private fun texMod(v: Double): Float = (v - floor(v / TEX_PERIOD) * TEX_PERIOD).toFloat()

    private fun drawRoads() {
        val r = rules ?: return
        val p = roadProg.p
        roadProg.use()
        GLES30.glUniform4f(p.loc("uRoadSize"), 2f, 2f, Materials.sizeOf("pavers_stone").first, 2f)
        GLES30.glUniform4f(p.loc("uGroundIdx"), Materials.ground("pavers_stone").toFloat(), Materials.ground("kerb_concrete").toFloat(), Materials.ground("gravel_shoulder").toFloat(), Materials.ground("soil_dirt").toFloat())
        GLES30.glUniform4f(p.loc("uGravelSize"), Materials.sizeOf("gravel_shoulder").first, 2f, r.asphaltLayer.toFloat(), 1f)
        GLES30.glUniform4f(p.loc("uRoadGain"), r.asphaltAlbedo / roadMean[0], r.asphaltAlbedo / roadMean[1], r.asphaltAlbedo / roadMean[2], COBBLE_ALBEDO / roadMean[3])
        GLES30.glUniform4f(p.loc("uGroundGain"), groundGain(Materials.ground("pavers_stone"), PAVER_ALBEDO), groundGain(Materials.ground("kerb_concrete"), KERB_ALBEDO),
            groundGain(Materials.ground("gravel_shoulder"), GRAVEL_ALBEDO), 1f)
        GeoGl.defaultInstance()
        for (gi in 0 until roadList.size) {
            val c = roadList[gi]
            val ox = relX(c.anchorX); val oz = relZ(c.anchorZ); val oy = -camera.eye[1].toFloat()
            if (!boxVisible(ox + c.minX - 2, oy + c.minY - 2, oz + c.minZ - 2, ox + c.maxX + 2, oy + c.maxY + 8, oz + c.maxZ + 2)) continue
            GLES30.glUniform3f(p.loc("uOffset"), ox, oy, oz)
            GLES30.glUniform2f(p.loc("uTexOrigin"), texMod(c.anchorX), texMod(-c.anchorZ))
            c.mesh.draw()
            frameGroundTris += c.mesh.indexCount / 3
        }
        // Side roads lie on the terrain: pulled towards the camera a little so they win over the grid.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(-1f, -2f)
        for (ti in 0 until tileList.size) {
            val t = tileList[ti]
            val m = t.side ?: continue
            if (t.key.lod != 0) continue
            val ox = relX(t.anchorX); val oz = relZ(t.anchorZ); val oy = -camera.eye[1].toFloat()
            if (!boxVisible(ox, oy + t.minY, oz - t.size, ox + t.size, oy + t.maxY + 5, oz)) continue
            val dist = hypot(ox + t.size / 2.0, oz - t.size / 2.0)
            if (dist > 2200) continue
            GLES30.glUniform3f(p.loc("uOffset"), ox, oy, oz)
            GLES30.glUniform2f(p.loc("uTexOrigin"), texMod(t.anchorX), texMod(-t.anchorZ))
            m.draw()
            frameGroundTris += m.indexCount / 3
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
    }

    /** The material last set with [setMaterial] in the current program (null after a program change). */
    private var lastMaterial: MaterialGpu? = null

    private fun setMaterial(p: GeoProgram, m: MaterialGpu) {
        // Consecutive parts often share a material (W2 merges per material, W3's people all use one):
        // skip the uniform, texture and state calls then.
        if (m === lastMaterial) return
        lastMaterial = m
        val pp = p.p
        GLES30.glUniform1i(pp.loc("uAnim"), m.anim)
        GLES30.glUniform1i(pp.loc("uTex"), m.texFlags)
        GLES30.glUniform2f(pp.loc("uUvScale"), m.uvScaleU, m.uvScaleV)
        pp.set("uCutout", if (m.m.alphaCutout) 0.5f else -1f)
        pp.set("uRough", m.m.roughness)
        pp.set("uFlipBack", if (m.m.alphaCutout && m.m.anim == Anim.WIND) 0f else 1f)
        setCells(pp, m)
        if (m.decal) { GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL); GLES30.glPolygonOffset(-1f, -4f) }
        else GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        if (m.albedo != 0) bindTex(8, GLES30.GL_TEXTURE_2D, m.albedo)
        if (m.normal != 0) bindTex(9, GLES30.GL_TEXTURE_2D, m.normal)
        if (m.m.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE) else GLES30.glEnable(GLES30.GL_CULL_FACE)
        // No alpha to coverage for cutouts: finalColor writes alpha 1, so it would change nothing. Their
        // edges are the shader's discard at uCutout.
    }

    private fun setCells(p: app.rollentrainer.ui.world.Program, m: MaterialGpu) {
        val c = m.cells
        if (c == null) GLES30.glUniform3f(p.loc("uCells"), 0f, 0f, 0f) else GLES30.glUniform3f(p.loc("uCells"), c.cols.toFloat(), c.cellU, c.cellV)
    }

    private fun drawObjects(shadowPass: Boolean) {
        val prog = if (shadowPass) objectShadowProg else objectProg
        prog.use()
        lastMaterial = null
        val p = prog.p
        if (shadowPass) p.setMat4("uLightVP", lightVP)
        // Instanced prototypes (the assembler's lists are relative to its own origin).
        GLES30.glUniform3f(p.loc("uOffset"), relX(assemblyOriginX), relY(assemblyOriginY), relZ(assemblyOriginZ))
        var tris = 0L
        val dc = if (shadowPass) 2 else 0
        debugDraws[dc] = 0; debugDraws[dc + 1] = 0
        if (shadowPass) {
            drawProtoShadows(p)
            prog.use()
        } else {
            for (gi in 0 until protoList.size) {
                val g = protoList[gi]
                for (lod in 0 until g.lods.size) {
                    val parts = g.lods[lod]
                    val n = g.counts[lod]
                    if (n <= 0) continue
                    for (pi in 0 until parts.size) {
                        val part = parts[pi]
                        debugDraws[0]++
                        tris += part.mesh.indexCount / 3L * n
                        setMaterial(prog, part.material)
                        part.mesh.drawInstanced(n)
                    }
                }
            }
            frameTris += tris
        }
        // One-off meshes (buildings, road writing) of the resident chunks.
        GeoGl.defaultInstance()
        // Main pass: frustum and 2.2 km; shadow pass: around the shadow centre (camera-relative), with
        // slack for tall buildings just outside the box whose shadows fall into it.
        val cx: Float; val cz: Float; val maxD: Float
        if (shadowPass) {
            cx = relX(shadowC[0]); cz = relZ(shadowC[2]); maxD = (quality.shadowRadius * 1.15 + 50).toFloat()
        } else { cx = 0f; cz = 0f; maxD = 2200f }
        // Road paint and finish lines bias their depth themselves (MaterialGpu.decal, set in setMaterial).
        for (i in 0 until sceneryList.size) drawChunkMeshes(prog, sceneryList[i], cx, cz, maxD, shadowPass)
        for (i in 0 until dressList.size) drawChunkMeshes(prog, dressList[i], cx, cz, maxD, shadowPass)
        if (!shadowPass) objectTris = frameTris
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /**
     * Shadow casters of the prototypes: one draw per prototype part (all near instances with the
     * prototype's shadow LOD mesh), opaque parts first, then the alpha-tested ones with their program,
     * so the program changes once instead of per part. [p] is the opaque shadow program, already in use.
     */
    private fun drawProtoShadows(p: app.rollentrainer.ui.world.Program) {
        var cutouts = 0
        for (gi in 0 until protoList.size) {
            val g = protoList[gi]
            // Two tiers: casters near the camera with the shadow LOD, the others with the coarse one.
            for (tier in 0..1) {
                val n = if (tier == 0) g.shadowCount else g.shadowFarCount
                if (n <= 0) continue
                val parts = g.lods[if (tier == 0) g.shadowLod else g.shadowFarLod]
                for (pi in 0 until parts.size) {
                    val part = parts[pi]
                    val m = part.material
                    if (!m.m.castsShadow) continue
                    if (m.m.alphaCutout && m.albedo != 0) { cutouts++; continue }
                    GLES30.glUniform1i(p.loc("uAnim"), m.anim)
                    setCells(p, m)
                    part.mesh.drawShadowInstanced(n)
                    frameTris += part.mesh.indexCount / 3L * n
                    debugDraws[2]++
                }
            }
        }
        if (cutouts > 0) {
            objectShadowCutProg.use()
            val c = objectShadowCutProg.p
            c.setMat4("uLightVP", lightVP)
            GLES30.glUniform3f(c.loc("uOffset"), relX(assemblyOriginX), relY(assemblyOriginY), relZ(assemblyOriginZ))
            c.set("uCutout", 0.5f)
            for (gi in 0 until protoList.size) {
                val g = protoList[gi]
                for (tier in 0..1) {
                    val n = if (tier == 0) g.shadowCount else g.shadowFarCount
                    if (n <= 0) continue
                    val parts = g.lods[if (tier == 0) g.shadowLod else g.shadowFarLod]
                    for (pi in 0 until parts.size) {
                        val part = parts[pi]
                        val m = part.material
                        if (!m.m.castsShadow || !(m.m.alphaCutout && m.albedo != 0)) continue
                        GLES30.glUniform1i(c.loc("uAnim"), m.anim)
                        setCells(c, m)
                        GLES30.glUniform2f(c.loc("uUvScale"), m.uvScaleU, m.uvScaleV)
                        bindTex(8, GLES30.GL_TEXTURE_2D, m.albedo)
                        part.mesh.drawShadowInstanced(n)
                        frameTris += part.mesh.indexCount / 3L * n
                        debugDraws[2]++
                    }
                }
            }
        }
    }

    /**
     * One-off meshes of a chunk, each culled by its bounds: against the view frustum and [maxD] around
     * the camera in the main pass, against the shadow area around ([cx], [cz]) in the shadow pass.
     */
    private fun drawChunkMeshes(prog: GeoProgram, c: GpuChunk, cx: Float, cz: Float, maxD: Float, shadowPass: Boolean) {
        if (c.meshes.isEmpty()) return
        val ax = relX(c.anchorX); val ay = relY(c.anchorY); val az = relZ(c.anchorZ)
        val b = c.bounds
        val p = prog.p
        var offsetSet = false
        val merged = if (shadowPass) c.shadowMerged else null
        if (merged != null) {
            val sb = c.shadowBounds!!
            val dx = max(0f, max(ax + sb[0] - cx, cx - (ax + sb[3]))); val dz = max(0f, max(az + sb[2] - cz, cz - (az + sb[5])))
            if (dx * dx + dz * dz <= maxD * maxD) {
                GLES30.glUniform3f(p.loc("uOffset"), ax, ay, az); offsetSet = true
                GLES30.glUniform1i(p.loc("uAnim"), 0)
                c.shadow!!.draw()
                frameTris += c.shadow.indexCount / 3
                debugDraws[3]++
            }
        }
        for (i in 0 until c.meshes.size) {
            if (merged != null && merged[i]) continue
            val pair = c.meshes[i]
            val m = pair.first
            if (shadowPass && !m.m.castsShadow) continue
            val o = i * 6
            val x0 = ax + b[o]; val y0 = ay + b[o + 1]; val z0 = az + b[o + 2]
            val x1 = ax + b[o + 3]; val y1 = ay + b[o + 4]; val z1 = az + b[o + 5]
            // Horizontal distance from the box to the centre of interest.
            val dx = max(0f, max(x0 - cx, cx - x1)); val dz = max(0f, max(z0 - cz, cz - z1))
            if (dx * dx + dz * dz > maxD * maxD) continue
            if (!shadowPass && !boxVisible(x0, y0, z0, x1, y1, z1)) continue
            if (!offsetSet) { GLES30.glUniform3f(p.loc("uOffset"), ax, ay, az); offsetSet = true }
            if (shadowPass) GLES30.glUniform1i(p.loc("uAnim"), m.anim) else setMaterial(prog, m)
            pair.second.draw()
            frameTris += pair.second.indexCount / 3
            debugDraws[if (shadowPass) 3 else 1]++
        }
    }

    /**
     * Draw calls of the last frame by kind, for the preview's report and profiling: instanced prototype
     * parts and one-off chunk meshes in the main pass, then the same in the shadow pass.
     */
    internal val debugDraws = IntArray(4)

    /** GPU bytes of each object texture (with mipmaps), for the preview's report. */
    internal val debugObjectTex = HashMap<String, Int>()

    /** Prototype parts drawn in the main pass of the last frame: "id/lod" to (parts, instances). Not for the GL loop. */
    internal fun debugProtoDraws(): Map<String, IntArray> {
        val out = LinkedHashMap<String, IntArray>()
        for (g in protoList) for (lod in g.lods.indices) if (g.counts[lod] > 0) out["${g.proto.id}/$lod"] = intArrayOf(g.lods[lod].size, g.counts[lod])
        return out
    }

    private fun drawShadows() {
        val sc = scene ?: return
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, quality.shadowSize, quality.shadowSize)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        // Thin geometry (leaves, flags) shadows both ways; slope bias against acne.
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 3f)
        bindTex(10, GLES30.GL_TEXTURE_2D, kitTex)
        drawObjects(shadowPass = true)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 3f)
        drawRiders(sc, shadowPass = true)
        drawTubeShadows()
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /** The tunnel tubes into the shadow map (RoadCpu.tubeFirst/tubeCount): no sun on the road and the riders inside. */
    private fun drawTubeShadows() {
        val prog = objectShadowProg
        val p = prog.p
        val cx = relX(shadowC[0]); val cz = relZ(shadowC[2])
        val maxD = (quality.shadowRadius * 1.15 + 20).toFloat()
        var bound = false
        for (gi in 0 until roadList.size) {
            val c = roadList[gi]
            if (c.tubeCount == 0) continue
            val ox = relX(c.anchorX); val oz = relZ(c.anchorZ)
            val dx = max(0f, max(ox + c.minX - cx, cx - (ox + c.maxX))); val dz = max(0f, max(oz + c.minZ - cz, cz - (oz + c.maxZ)))
            if (dx * dx + dz * dz > maxD * maxD) continue
            if (!bound) {
                prog.use()
                p.setMat4("uLightVP", lightVP)
                GLES30.glUniform1i(p.loc("uAnim"), 0)
                GeoGl.defaultInstance()
                bound = true
            }
            GLES30.glUniform3f(p.loc("uOffset"), ox, -camera.eye[1].toFloat(), oz)
            c.mesh.drawRange(c.tubeFirst, c.tubeCount)
            frameTris += c.tubeCount / 3
            debugDraws[3]++
        }
    }

    /**
     * The tunnel tubes from 150 m behind to 1.5 km ahead of the camera as 10 m pieces (short enough that a
     * bend stays inside the tube's 0.4 m wall), for the near terrain to cut its ground out of them.
     */
    private fun updateTunnelCut(sc: GeoScene) {
        tunN = 0
        riderDark = 0f
        val d = smoothDistance
        if (d.isNaN()) return
        val path = sc.pack.path
        val s = sc.packDistance(d)
        val last = path.size - 1
        val tf = PathFlags.TUNNEL
        // The riders' light: the same ramp as the road's (RoadBuilder.tunnelDark), at the player.
        val ip = (s / path.stepM + 0.5).toInt().coerceIn(0, last)
        if ((path.flags[ip].toInt() and tf) != 0) {
            var back = 0
            while (back < 8 && ip - back - 1 >= 0 && (path.flags[ip - back - 1].toInt() and tf) != 0) back++
            var fwd = 0
            while (fwd < 8 && ip + fwd + 1 <= last && (path.flags[ip + fwd + 1].toInt() and tf) != 0) fwd++
            val t = (((min(back, fwd) + 1) * path.stepM.toFloat() - 5f) / 25f).coerceIn(0f, 1f)
            riderDark = t * t * (3f - 2f * t)
        }
        var i = max(0, ((s - 150.0) / path.stepM).toInt())
        val stop = min(last, ((s + 1500.0) / path.stepM).toInt())
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        val wGap = RoadBuilder.TUBE_GAP + RoadBuilder.TUBE_WALL * 0.5f
        val h = RoadBuilder.TUBE_H + RoadBuilder.TUBE_WALL * 0.5f
        while (i <= stop && tunN < MAX_TUNNEL_PIECES) {
            if ((path.flags[i].toInt() and tf) == 0) { i++; continue }
            var e = i
            while (e < last && (path.flags[e + 1].toInt() and tf) != 0) e++
            // The tube runs from the first tunnel sample to the one after the last (RoadBuilder).
            val end = min(last, e + 1)
            var a = i
            while (a < end && tunN < MAX_TUNNEL_PIECES) {
                val b = min(end, a + 2)
                val w = max(path.width[a], path.width[b]) / 2f + wGap
                val portalA = a == i && (a == 0 || (path.flags[a - 1].toInt() and tf) == 0)
                val k = tunN * 4
                tunA[k] = relX(path.x[a]); tunA[k + 1] = relY(path.z[a] + RoadBuilder.LIFT); tunA[k + 2] = relZ(-path.y[a]); tunA[k + 3] = if (portalA) -w else w
                tunB[k] = relX(path.x[b]); tunB[k + 1] = relY(path.z[b] + RoadBuilder.LIFT); tunB[k + 2] = relZ(-path.y[b]); tunB[k + 3] = if (b == end) -h else h
                x0 = min(x0, min(tunA[k], tunB[k]) - w); x1 = max(x1, max(tunA[k], tunB[k]) + w)
                z0 = min(z0, min(tunA[k + 2], tunB[k + 2]) - w); z1 = max(z1, max(tunA[k + 2], tunB[k + 2]) + w)
                tunN++
                a = b
            }
            // Past the run (e + 1 = end, except for a run up to the last sample, where i = end would never move).
            i = e + 1
        }
        tunBox[0] = x0; tunBox[1] = z0; tunBox[2] = x1; tunBox[3] = z1
    }

    private val cloudOff = FloatArray(6)

    private fun drawSky() {
        skyProg.use()
        skyProg.p.setMat4("uInvViewProj", invViewProj)
        // The cloud layer's offset per noise scale (SKY_FS uCloudOff), in Double: the camera's real position
        // plus the drift with the wind of the Frame block (uWind.xy, 6 m/s) over unwrapped time.
        val drift = clockSec * CLOUD_DRIFT_MPS
        val ox = camera.eye[0] + block[68] * drift
        val oz = camera.eye[2] + block[69] * drift
        for (k in 0 until 3) {
            val sc = CLOUD_SCALES[k]
            cloudOff[k * 2] = (ox / sc - floor(ox / sc)).toFloat()
            cloudOff[k * 2 + 1] = (oz / sc - floor(oz / sc)).toFloat()
        }
        GLES30.glUniform2fv(skyProg.p.loc("uCloudOff"), 3, cloudOff, 0)
        val photo = skyTex != 0 && frame.photoSky
        if (photo) bindTex(11, GLES30.GL_TEXTURE_2D, skyTex)
        GLES30.glUniform2f(skyProg.p.loc("uSkyParams"), skySunU, if (photo) skyMax else 0f)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glDepthMask(true)
    }

    // --- riders ----------------------------------------------------------------------------------

    private val riderTmp = DoubleArray(3)
    private val riderFwdTmp = DoubleArray(3)

    /** This frame's rider instances, before the shadow pass so rider and shadow match; [dt] the frame time (s). */
    private fun prepareRiders(sc: GeoScene, dt: Double) {
        val rg = riders ?: return
        run {
            rg.begin()
            val path = sc.pack.path
            val ox = camera.eye[0]; val oy = camera.eye[1]; val oz = camera.eye[2]
            // The player (not drawn in first person: the camera is in the rider's head).
            if (!frame.firstPerson) {
                val s = sc.packDistance(smoothDistance)
                // Kit 0 is the Wattwerk kit; in pro mode the screen sets the chosen pro's team kit.
                val kit = frame.playerKit.coerceIn(0, kitCount - 1)
                rg.add(riderPos[0] - ox, riderPos[1] - oy, riderPos[2] - oz, yawOf(riderFwd), pitchAt(sc, s), riderLean.toFloat(), kit, crank, wheel, 0f, 0.0)
            }
            val pd = frame.pacerDistanceM
            if (!pd.isNaN()) {
                val pred = if (frame.paused) pd else pd + frame.pacerSpeedMps * ((SystemClock.elapsedRealtime() - frame.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
                val s = sc.packDistance(pred)
                if (abs(s - sc.packDistance(smoothDistance)) < 800) {
                    val lat = defaultLane(path.width[min(path.size - 1, (s / path.stepM).toInt())].toDouble()) + 0.9
                    placeOnRoad(sc, s, lat, riderTmp, riderFwdTmp)
                    val d = hypot(riderTmp[0] - ox, riderTmp[2] - oz)
                    rg.add(riderTmp[0] - ox, riderTmp[1] - oy, riderTmp[2] - oz, yawOf(riderFwdTmp), pitchAt(sc, s), leanAt(sc, s, frame.pacerSpeedMps).toFloat(), 1, pacerCrank, pacerWheel, 0f, d)
                }
            }
            frame.peloton?.let { list ->
                val age = ((SystemClock.elapsedRealtime() - frame.pelotonUpdatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
                for (ri in 0 until list.size) {
                    val r = list[ri]
                    val s = sc.packDistance(r.distanceM + if (frame.paused) 0.0 else r.speedMps * age)
                    placeOnRoad(sc, s, r.lateralM, riderTmp, riderFwdTmp)
                    val dx = riderTmp[0] - ox; val dz = riderTmp[2] - oz
                    val d = hypot(dx, dz)
                    if (d > 1500) continue
                    if (r.id < 0) continue
                    if (r.id >= pelotonPhase.size) pelotonPhase = pelotonPhase.copyOf(r.id + 64)
                    val ph = pelotonPhase[r.id] ?: doubleArrayOf((r.id * 0.37) % (2 * PI), 0.0).also { pelotonPhase[r.id] = it }
                    if (!frame.paused) { ph[0] += r.cadenceRpm / 60.0 * 2 * PI * dt; ph[1] += r.speedMps * dt / RIDER_WHEEL_R }
                    rg.add(dx, riderTmp[1] - oy, dz, yawOf(riderFwdTmp), pitchAt(sc, s), leanAt(sc, s, r.speedMps).toFloat(), r.kit.coerceIn(0, kitCount - 1), ph[0], ph[1], r.standing, d)
                }
            }
            rg.upload()
        }
    }

    // =============================================================================================
    // Race vehicles: W3's models (race/Props.kt), placed here every frame because they move with the race.
    // The TV moto rides 8-15 m ahead of the rider's group, the helicopter hovers about 300 m ahead of the
    // head of the race, a team car follows 30-60 m behind the group. Offsets are smoothed, so they glide
    // instead of jumping when the race (updated once a second) reshapes the group.

    private var motoAhead = Double.NaN
    private var carBehind = Double.NaN
    private var heliAhead = Double.NaN
    private var heliYaw = Double.NaN
    private val heliPos = DoubleArray(3)
    private val vehInst = FloatArray(InstanceSet.FLOATS)
    private val vehA = DoubleArray(3)
    private val vehB = DoubleArray(3)
    private val vehFwd = DoubleArray(3)
    private var kitTints = FloatArray(1) { 0xFFFFFF.toFloat() }

    private fun prepareVehicles(sc: GeoScene, dt: Double) {
        val moto = protos[VEH_MOTO]; val car = protos[VEH_CAR]; val heli = protos[VEH_HELI]
        if (moto == null && car == null && heli == null) return
        val path = sc.pack.path
        val sP = sc.packDistance(smoothDistance)
        val t = timeSec.toDouble()
        // The rider's group (no gap over 15 m) and the head of the race, from the race riders.
        var front = sP; var back = sP; var head = sP
        val list = frame.peloton
        if (list != null && list.isNotEmpty()) {
            val adv = if (frame.paused) 0.0 else ((SystemClock.elapsedRealtime() - frame.pelotonUpdatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
            for (i in 0 until list.size) head = max(head, sc.packDistance(list[i].distanceM + list[i].speedMps * adv))
            var grew = true
            var rounds = 0
            while (grew && rounds++ < 30) {
                grew = false
                for (i in 0 until list.size) {
                    val s = sc.packDistance(list[i].distanceM + list[i].speedMps * adv)
                    if (s > front && s - front < 15.0) { front = s; grew = true }
                    if (s < back && back - s < 15.0) { back = s; grew = true }
                }
            }
        }
        val k = 1 - exp(-dt * 0.6)
        fun glide(cur: Double, target: Double) = if (cur.isNaN()) target else cur + (target - cur) * k
        val width = path.width[min(path.size - 1, (sP / path.stepM).toInt())].toDouble()
        val lat = if (frame.lateralM.isNaN()) defaultLane(width) else frame.lateralM
        val end = path.lengthM - 1.0

        // TV moto: ahead of the group, 1.5-2.5 m left of the rider's line, the cameraman facing back.
        motoAhead = glide(motoAhead, front - sP + 11.5 + 3.5 * sin(t * 0.045))
        val sM = sP + motoAhead
        if (moto != null && sM < end) {
            placeOnRoad(sc, sM, lat + 2.0 + 0.45 * sin(t * 0.11), vehA, vehFwd)
            putVehicle(moto, vehA[0], vehA[1], vehA[2], yawOf(vehFwd), WHITE_TINT, true)
        } else moto?.clear()

        // Team car: behind the group in the middle of the road, white with the team colour.
        carBehind = glide(carBehind, sP - back + 42.0 + 12.0 * sin(t * 0.03))
        val sC = sP - carBehind
        if (car != null && sC > 0) {
            placeOnRoad(sc, sC, 0.0, vehA, vehFwd)
            putVehicle(car, vehA[0], vehA[1], vehA[2], yawOf(vehFwd), kitTints[frame.playerKit.coerceIn(0, kitTints.size - 1)], true)
        } else car?.clear()

        // Helicopter: above the road 300 m ahead of the head of the race, drifting slowly to the side, its
        // heading along the road's general direction (the chord over 300 m, not every hairpin).
        heliAhead = glide(heliAhead, head - sP + 300.0)
        val sH = min(end, sP + heliAhead)
        placeOnRoad(sc, sH, 28.0 * sin(t * 0.037) + 12.0, vehA, null)
        val ty = vehA[1] + 110.0 + 8.0 * sin(t * 0.05)
        placeOnRoad(sc, max(0.0, sH - 150), 0.0, vehB, null)
        placeOnRoad(sc, min(end, sH + 150), 0.0, vehFwd, null)
        val yawT = atan2(-(vehFwd[0] - vehB[0]), -(vehFwd[2] - vehB[2]))
        val kh = 1 - exp(-dt * 0.5)
        if (heliYaw.isNaN()) { heliPos[0] = vehA[0]; heliPos[1] = ty; heliPos[2] = vehA[2]; heliYaw = yawT }
        else {
            heliPos[0] += (vehA[0] - heliPos[0]) * kh; heliPos[1] += (ty - heliPos[1]) * kh; heliPos[2] += (vehA[2] - heliPos[2]) * kh
            var dy = yawT - heliYaw
            while (dy > PI) dy -= 2 * PI
            while (dy < -PI) dy += 2 * PI
            heliYaw += dy * (1 - exp(-dt * 0.4))
        }
        if (heli != null) {
            val yaw = heliYaw.toFloat()
            putVehicle(heli, heliPos[0], heliPos[1], heliPos[2], yaw, WHITE_TINT, false)
            val c = cos(heliYaw); val sn = sin(heliYaw)
            // Hubs in the helicopter's frame, turned like the shader turns a mesh: x' = x c + z s, z' = -x s + z c.
            protos[VEH_ROTOR]?.let { r ->
                val hx = HELI_ROTOR_HUB[0] * c + HELI_ROTOR_HUB[2] * sn; val hz = -HELI_ROTOR_HUB[0] * sn + HELI_ROTOR_HUB[2] * c
                putVehicle(r, heliPos[0] + hx, heliPos[1] + HELI_ROTOR_HUB[1], heliPos[2] + hz, (heliYaw + (t * ROTOR_RAD_S) % (2 * PI)).toFloat(), WHITE_TINT, false)
            }
            // The tail rotor would spin about x, which the yaw-only instance cannot; at this distance it stands still.
            protos[VEH_TAIL]?.let { r ->
                val hx = HELI_TAIL_HUB[0] * c + HELI_TAIL_HUB[2] * sn; val hz = -HELI_TAIL_HUB[0] * sn + HELI_TAIL_HUB[2] * c
                putVehicle(r, heliPos[0] + hx, heliPos[1] + HELI_TAIL_HUB[1], heliPos[2] + hz, yaw, WHITE_TINT, false)
            }
        }
    }

    /** One instance of [g] at GL world (x, y, z) this frame, at the LOD for its distance; nothing beyond the last. */
    private fun putVehicle(g: ProtoGpu, x: Double, y: Double, z: Double, yaw: Float, tint: Float, shadow: Boolean) {
        g.clear()
        val e = camera.eye
        val d = sqrt((x - e[0]) * (x - e[0]) + (y - e[1]) * (y - e[1]) + (z - e[2]) * (z - e[2]))
        val lods = g.proto.lods
        var lod = 0
        while (lod < lods.size && d > lods[lod].maxDistanceM) lod++
        if (lod >= lods.size || g.lods[lod].isEmpty()) return
        val b = vehInst
        // Relative to the assembler's origin: the object pass draws every prototype with that offset.
        b[0] = (x - assemblyOriginX).toFloat(); b[1] = (y - assemblyOriginY).toFloat(); b[2] = (z - assemblyOriginZ).toFloat()
        b[3] = yaw; b[4] = 1f; b[5] = tint; b[6] = 0f; b[7] = 0f
        g.upload(lod, b, 1, 1)
        if (shadow && hypot(x - shadowC[0], z - shadowC[2]) < quality.shadowRadius) g.uploadShadow(b, 1)
    }

    private fun drawRiders(@Suppress("UNUSED_PARAMETER") sc: GeoScene, shadowPass: Boolean) {
        val rg = riders ?: return
        val prog = if (shadowPass) riderShadowProg else riderProg
        prog.use()
        lastMaterial = null
        val p = prog.p
        if (shadowPass) p.setMat4("uLightVP", lightVP)
        else {
            GLES30.glUniform1i(p.loc("uTex"), 0)
            GLES30.glUniform2f(p.loc("uUvScale"), 1f, 1f)
            p.set("uCutout", -1f)
            p.set("uRough", 0.55f)
            p.set("uTunnelDark", riderDark)
        }
        GLES30.glUniform3f(p.loc("uOffset"), 0f, 0f, 0f)
        rg.draw(if (shadowPass) bindRiderShadow else bindRiderMain)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun yawOf(fwd: DoubleArray): Float = atan2(-fwd[0], -fwd[2]).toFloat()

    private fun pitchAt(sc: GeoScene, s: Double): Float = atan(sc.pack.path.gradeAt(s, 4.0) / 100.0).toFloat()

    // --- culling ----------------------------------------------------------------------------------

    private fun extractPlanes(m: FloatArray, out: FloatArray) {
        // Rows of the column-major matrix: plane = row3 +- row k.
        for (i in 0 until 3) {
            for (sgn in 0..1) {
                val o = (i * 2 + sgn) * 4
                val s = if (sgn == 0) 1f else -1f
                out[o] = m[3] + s * m[i]
                out[o + 1] = m[7] + s * m[4 + i]
                out[o + 2] = m[11] + s * m[8 + i]
                out[o + 3] = m[15] + s * m[12 + i]
            }
        }
    }

    /** Camera-relative AABB against the frustum (conservative). */
    private fun boxVisible(x0: Float, y0: Float, z0: Float, x1: Float, y1: Float, z1: Float): Boolean {
        for (k in 0 until 6) {
            val o = k * 4
            val a = planes[o]; val b = planes[o + 1]; val c = planes[o + 2]; val d = planes[o + 3]
            val px = if (a >= 0) x1 else x0
            val py = if (b >= 0) y1 else y0
            val pz = if (c >= 0) z1 else z0
            if (a * px + b * py + c * pz + d < 0) return false
        }
        return true
    }

    companion object {
        private const val TAG = "WorldView"
        const val FAR_PLANE = 90000f
        /** Texture space wraps every 1200 m (a multiple of every material's repeat) to keep floats small. */
        const val TEX_PERIOD = 1200.0
        /** Earth curvature with standard refraction: drop = d^2 / (2 * 7/6 R). */
        const val CURVATURE = (1.0 / (2.0 * 6371000.0 * 7.0 / 6.0)).toFloat()
        const val EXPOSURE = 0.85f
        /** The sky panorama in assets/sky. */
        const val SKY = "clarens"
        /** Sun irradiance (relative to the sky's radiance, which sets the brightness of the sky dome). */
        const val SUN = 2.05f
        const val RIDER_WHEEL_R = 0.34
        const val MAX_LOD_BIAS = 4f
        /** Must match the uTunA/uTunB arrays of GeoShaders (TUNNEL_CUT). */
        const val MAX_TUNNEL_PIECES = 32
        /** Mean albedos the street materials are scaled to (the textures are shot brighter than the real thing). */
        const val PAVER_ALBEDO = 0.21f
        const val KERB_ALBEDO = 0.27f
        const val GRAVEL_ALBEDO = 0.17f
        const val SHOULDER_ALBEDO = 0.13f
        const val COBBLE_ALBEDO = 0.11f
        const val DRESS_RADIUS = 1600.0
        /** The cloud noise scales of GeoShaders.SKY_FS (m) and the clouds' drift per unit of uWind.xy (m/s). */
        private val CLOUD_SCALES = doubleArrayOf(15000.0, 4300.0, 1300.0)
        private const val CLOUD_DRIFT_MPS = 6.0
        /** W3's vehicle prototypes and rotor hubs in the helicopter's frame (race/Props.kt). */
        private const val VEH_MOTO = "race_moto"
        private const val VEH_CAR = "race_team_car"
        private const val VEH_HELI = "race_heli"
        private const val VEH_ROTOR = "race_heli_rotor"
        private const val VEH_TAIL = "race_heli_tail_rotor"
        private val HELI_ROTOR_HUB = doubleArrayOf(0.0, 2.92, -0.35)
        private val HELI_TAIL_HUB = doubleArrayOf(-0.13, 2.75, 7.1)
        /** Slower than a real rotor (about 40 rad/s), which at 60 frames per second would strobe backwards. */
        private const val ROTOR_RAD_S = 11.0
        private val WHITE_TINT = 0xFFFFFF.toFloat()
    }
}

/**
 * Picks the scenery and race dressing generators: W2's vegetation/buildings and W3's Tour dressing.
 * The stubs ([StubScenery], [StubDressing]) stay for tests and as a quick swap while debugging.
 */
internal object Generators {
    fun forScene(scene: GeoScene): Pair<SceneryGenerator, RaceDressing> {
        val scenery: SceneryGenerator = app.rollentrainer.ui.world.geo.scenery.GeoSceneryGenerator(scene.pack)
        val dressing: RaceDressing = app.rollentrainer.ui.world.geo.race.TourDressing(scene.pack)
        return scenery to dressing
    }
}

/** A small tileable noise texture: R/G value noise fBm, B large-scale cloud noise, A fine detail. */
internal object NoiseTexture {
    fun create(): Int {
        val n = 256
        val buf = GeoGl.direct(n * n * 4)
        val r = noise(n, 8, 4, 1)
        val g = noise(n, 8, 4, 2)
        val b = noise(n, 4, 5, 3)
        val a = noise(n, 32, 3, 4)
        for (i in 0 until n * n) {
            buf.put((r[i] * 255).toInt().coerceIn(0, 255).toByte())
            buf.put((g[i] * 255).toInt().coerceIn(0, 255).toByte())
            buf.put((b[i] * 255).toInt().coerceIn(0, 255).toByte())
            buf.put((a[i] * 255).toInt().coerceIn(0, 255).toByte())
        }
        buf.position(0)
        return GeoGl.texture2D(buf, n, n, false, true, 1f, GLES30.GL_REPEAT)
    }

    /** Periodic value-noise fBm on an n x n grid, [cells] cells at the base octave, normalised to 0..1. */
    private fun noise(n: Int, cells: Int, octaves: Int, seed: Int): FloatArray {
        val out = FloatArray(n * n)
        var amp = 1f
        var c = cells
        for (o in 0 until octaves) {
            val lattice = FloatArray(c * c) { hash(it, seed * 31 + o) }
            for (y in 0 until n) for (x in 0 until n) {
                val fx = x.toFloat() / n * c; val fy = y.toFloat() / n * c
                val x0 = fx.toInt(); val y0 = fy.toInt()
                val tx = fx - x0; val ty = fy - y0
                val sx = tx * tx * (3 - 2 * tx); val sy = ty * ty * (3 - 2 * ty)
                val x1 = (x0 + 1) % c; val y1 = (y0 + 1) % c
                val v = (lattice[y0 * c + x0] * (1 - sx) + lattice[y0 * c + x1] * sx) * (1 - sy) +
                    (lattice[y1 * c + x0] * (1 - sx) + lattice[y1 * c + x1] * sx) * sy
                out[y * n + x] += v * amp
            }
            amp *= 0.5f
            c *= 2
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in out) { lo = min(lo, v); hi = max(hi, v) }
        for (i in out.indices) out[i] = (out[i] - lo) / (hi - lo).coerceAtLeast(1e-6f)
        return out
    }

    private fun hash(i: Int, seed: Int): Float {
        var h = i * 374761393 + seed * 668265263
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    @Suppress("unused")
    private fun s(x: Float) = sqrt(x) + cos(x) + sin(x)
}
