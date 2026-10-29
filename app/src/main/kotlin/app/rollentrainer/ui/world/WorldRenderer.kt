package app.rollentrainer.ui.world

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import app.rollentrainer.core.route.Biome
import app.rollentrainer.core.route.PathSample
import app.rollentrainer.core.route.Prop
import app.rollentrainer.core.route.World
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** What the ride loop tells the renderer once a second; the renderer interpolates in between. */
class FrameState {
    @Volatile var distanceM = 0.0
    @Volatile var speedMps = 0.0
    @Volatile var cadenceRpm = 0.0
    @Volatile var pacerDistanceM = Double.NaN
    @Volatile var pacerSpeedMps = 0.0
    @Volatile var updatedAtMs = 0L
    @Volatile var paused = true
    /**
     * Camera: false = chase camera behind the bike (default), true = first person from the rider's
     * head. The screen flips it from its camera toggle; the renderer resets its smoothing on a change.
     */
    @Volatile var firstPerson = false
    /**
     * Offset of the rider from the road's centre line, metres (+ = left); NaN = the renderer's default lane.
     * Only the geodata renderer uses it (a race moves the rider across the road).
     */
    @Volatile var lateralM = Double.NaN
    /**
     * Other riders of a race simulation (null = none), replaced as a whole on every tick of the ride loop,
     * with [pelotonUpdatedAtMs] the SystemClock time of that tick. Only the geodata renderer draws them.
     */
    @Volatile var peloton: List<app.rollentrainer.ui.world.geo.RaceRider>? = null
    @Volatile var pelotonUpdatedAtMs = 0L
    /**
     * Kit of the player's rider in the geodata renderer: 0 = the Wattwerk kit (default), in pro mode the
     * team kit of the chosen pro (RaceFeed.kitOf), the same index the other riders of the race use.
     */
    @Volatile var playerKit = 0
    /** Local clock time (CEST) for the sun of the geodata renderer; Tour stages finish mid-afternoon. */
    @Volatile var timeOfDayH = 16.0
    /**
     * The rider's picture settings for the geodata renderer (Settings, "Bild"): exposure, contrast and
     * saturation as factors (1 = neutral), [detail] scales the structure of the ground and road textures,
     * [photoSky] shows the photographed sky instead of the computed one.
     */
    @Volatile var exposure = 1f
    @Volatile var contrast = 1f
    @Volatile var saturation = 1f
    @Volatile var detail = 1f
    @Volatile var photoSky = true
}

/**
 * Draws the virtual road: sky, far mountains, terrain, the road, trees and buildings (instanced),
 * signs, the rider and the pacer. The world is built on a background thread; its meshes are built on
 * another background thread and uploaded on the GL thread when they arrive.
 */
class WorldRenderer(val frame: FrameState) : GLSurfaceView.Renderer {
    @Volatile var pendingWorld: World? = null
    private var world: World? = null

    /** Meshes of a world, built off the GL thread. */
    private class BuiltWorld(val world: World, val terrain: MeshData, val road: MeshData, val skyline: MeshData)
    @Volatile private var built: BuiltWorld? = null
    private var building: World? = null

    private var litProgram: Program? = null
    private var terrainProgram: Program? = null
    private var roadProgram: Program? = null
    private var skyProgram: Program? = null
    private var texProgram: Program? = null
    private var skyVao = 0
    private var signVao = 0

    private var terrain: GpuMesh? = null
    private var road: GpuMesh? = null
    private var skyline: GpuMesh? = null
    private var treeLine = 9000f
    private val propMeshes = HashMap<Int, GpuMesh>()
    private val propBuckets = HashMap<Int, HashMap<Long, MutableList<Prop>>>()
    private val instanceScratch: FloatBuffer = Gl.floatBuffer(FloatArray(MAX_INSTANCES * 8))
    private val instanceArray = FloatArray(MAX_INSTANCES * 8)
    private var lastCullX = Double.NaN
    private var lastCullZ = Double.NaN
    private val visibleCounts = HashMap<Int, Int>()

    private class GpuSign(val texture: Int, val model: FloatArray)
    private val signs = mutableListOf<GpuSign>()

    private var rider: RiderModel? = null
    private var pacer: RiderModel? = null
    private val riderGpu = HashMap<MeshData, GpuMesh>()
    private val riderBase = FloatArray(16)
    private val drawRiderPart: (MeshData, FloatArray) -> Unit = { mesh, m ->
        litProgram?.setMat4("uModel", m)
        riderGpu.getOrPut(mesh) { GpuMesh(mesh) }.draw()
    }

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val invViewProj = FloatArray(16)
    private val model = FloatArray(16)
    private var aspect = 1f
    private var crank = 0f
    private var wheel = 0f
    private var lastFrameMs = 0L
    private var smoothDistance = 0.0
    /** How far below the eye the far-mountain ring sits; low-relief biomes need less so peaks still show. */
    private var skylineDrop = WorldMeshes.SKYLINE_DROP
    private var pacerCrank = 0f
    private var pacerWheel = 0f
    private val skylineModel = FloatArray(16)
    /** Smoothed camera and its targets for this frame. */
    private val camPos = FloatArray(3)
    private val camLook = FloatArray(3)
    private val targetEye = FloatArray(3)
    private val targetLook = FloatArray(3)
    private var camValid = false
    private var lastFirstPerson = false
    private var fogColor = floatArrayOf(0.72f, 0.8f, 0.9f)
    private var zenith = floatArrayOf(0.3f, 0.52f, 0.86f)
    private val sunDir = floatArrayOf(0.48f, 0.62f, 0.62f).also {
        val l = sqrt(it[0] * it[0] + it[1] * it[1] + it[2] * it[2])
        it[0] /= l; it[1] /= l; it[2] /= l
    }
    private val sunColor = floatArrayOf(1.05f, 0.95f, 0.80f)
    private val skyColor = floatArrayOf(0.55f, 0.68f, 0.90f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litProgram = Program(Shaders.LIT_VS, Shaders.LIT_FS)
        terrainProgram = Program(Shaders.TERRAIN_VS, Shaders.TERRAIN_FS)
        roadProgram = Program(Shaders.LIT_VS, Shaders.ROAD_FS)
        skyProgram = Program(Shaders.SKY_VS, Shaders.SKY_FS)
        texProgram = Program(Shaders.TEX_VS, Shaders.TEX_FS)
        skyVao = fullscreenQuad()
        signVao = signQuad()
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        rider = RiderModel(floatArrayOf(0.95f, 0.25f, 0.2f), floatArrayOf(0.95f, 0.95f, 0.95f))
        pacer = RiderModel(floatArrayOf(0.2f, 0.45f, 0.95f), floatArrayOf(0.15f, 0.15f, 0.18f))
        riderGpu.clear()
        for (p in Prop.CONIFER..Prop.SPECTATOR) {
            propMeshes[p] = GpuMesh(WorldMeshes.prop(p)).also { it.enableInstancing(MAX_INSTANCES) }
        }
        // A context loss drops all buffers, so the world has to be uploaded again.
        world = null
        building = null
        built = null
        terrain = null; road = null; skyline = null
        signs.clear()
        visibleCounts.clear()
        camValid = false
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val pending = pendingWorld
        if (pending != null && pending !== world && pending !== building) startBuild(pending)
        val ready = built
        if (ready != null && ready.world === pending && ready.world !== world) { upload(ready); built = null }
        val w = world
        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (w == null) return

        // Interpolate the rider between the one-second ticks of the ride loop.
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        val f = frame
        // While paused the rider stays where the smoothing has got to (it runs a little ahead of the last
        // tick), instead of being pulled back to the tick's distance.
        // At a section handover the frame's distance already belongs to the world still being built; the
        // old world coasts on at the rider's speed instead of snapping to the new section's distance.
        val handover = pending != null && pending !== w
        val predicted = when {
            handover -> smoothDistance + (if (f.paused) 0.0 else f.speedMps * dt)
            f.paused -> maxOf(f.distanceM, smoothDistance).coerceAtMost(f.distanceM + 10)
            else -> f.distanceM + f.speedMps * ((now - f.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
        }
        // Follow smoothly, never roll backwards for a small overshoot; jump only for big differences.
        val shownBefore = smoothDistance
        val next = smoothDistance + (predicted - smoothDistance) * (1 - Math.exp(-dt * 4.0))
        val jumped = kotlin.math.abs(predicted - smoothDistance) > 20
        smoothDistance = when {
            jumped -> predicted
            next < smoothDistance -> smoothDistance
            else -> next
        }
        if (jumped) camValid = false
        val d = smoothDistance.coerceIn(0.0, w.route.lengthM)
        // Wheels and cranks follow the motion on screen, so the bike never pedals on the spot while the
        // smoothing catches up (for example right after a pause).
        val shownMove = (smoothDistance - shownBefore).coerceIn(0.0, 5.0)
        if (!f.paused && shownMove > 0.0005) {
            crank += (f.cadenceRpm / 60.0 * 2 * PI * dt).toFloat()
            wheel += (shownMove / RiderModel.WHEEL_R * 180 / PI).toFloat()
        }
        if (!f.paused && f.pacerSpeedMps > 0.3) {
            // The pacer spins a steady 85 rpm and rolls at its own speed; at the top it stands still.
            pacerCrank += (85.0 / 60.0 * 2 * PI * dt).toFloat()
            pacerWheel += ((f.pacerSpeedMps * dt) / RiderModel.WHEEL_R * 180 / PI).toFloat()
        }

        val pose = w.poseAt(d)
        val firstPerson = f.firstPerson
        if (firstPerson != lastFirstPerson) { lastFirstPerson = firstPerson; camValid = false }
        updateCamera(w, d, pose, firstPerson, dt)
        Matrix.setLookAtM(view, 0, camPos[0], camPos[1], camPos[2], camLook[0], camLook[1], camLook[2], 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, if (firstPerson) 62f else 58f, aspect, 0.3f, WorldMeshes.FAR_PLANE)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        Matrix.invertM(invViewProj, 0, viewProj, 0)

        drawSky(now)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        // The far mountains travel with the camera like a backdrop.
        Matrix.setIdentityM(skylineModel, 0)
        Matrix.translateM(skylineModel, 0, camPos[0], camPos[1] - skylineDrop, camPos[2])
        litProgram?.let { drawStatic(it, skyline, 0.0004f, skylineModel) }
        // Push the terrain back a little in depth so the road on top of it always wins.
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1f, 2f)
        terrainProgram?.let { p ->
            drawStatic(p, terrain, FOG)
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        roadProgram?.let { drawStatic(it, road, FOG) }
        drawProps(camPos[0].toDouble(), camPos[2].toDouble())
        drawSigns()
        drawRiders(w, d, pose, firstPerson)
    }

    // --- camera ----------------------------------------------------------------------------------

    /**
     * Chase: eye 7.5 m behind the rider on the road, 2.3 m up, looking 4 m ahead of them. First person:
     * eye at the rider's head on their lane, looking along the road 25 m ahead. Both are smoothed over
     * frames; the smoothing restarts on a mode change or a jump.
     */
    private fun updateCamera(w: World, d: Double, pose: PathSample, firstPerson: Boolean, dt: Float) {
        val rate: Float
        if (firstPerson) {
            val h = pose.heading
            val rx = -cos(h); val rz = sin(h)       // the rider's right
            val fx = sin(h); val fz = cos(h)        // forward
            val lane = -WorldMeshes.RIDER_OFFSET.toDouble()
            targetEye[0] = (pose.x + rx * lane + fx * RiderModel.EYE_FORWARD).toFloat()
            targetEye[1] = (pose.y + WorldMeshes.ROAD_LIFT + RiderModel.EYE_HEIGHT).toFloat()
            targetEye[2] = (pose.z + rz * lane + fz * RiderModel.EYE_FORWARD).toFloat()
            val aheadD = d + 25.0
            val ahead = w.poseAt(aheadD.coerceAtMost(w.route.lengthM))
            // Past the finish the look-at carries straight on along the last heading.
            val over = (aheadD - w.route.lengthM).coerceAtLeast(0.0)
            val ah = ahead.heading
            targetLook[0] = (ahead.x - cos(ah) * lane + sin(ah) * over).toFloat()
            targetLook[1] = (ahead.y + WorldMeshes.ROAD_LIFT + 1.2).toFloat()
            targetLook[2] = (ahead.z + sin(ah) * lane + cos(ah) * over).toFloat()
            rate = 10f
        } else {
            val behind = w.poseAt((d - 7.5).coerceAtLeast(0.0))
            targetEye[0] = behind.x.toFloat()
            targetEye[1] = (behind.y + 2.3).toFloat()
            targetEye[2] = behind.z.toFloat()
            targetLook[0] = (pose.x + sin(pose.heading) * 4).toFloat()
            targetLook[1] = (pose.y + 1.1).toFloat()
            targetLook[2] = (pose.z + cos(pose.heading) * 4).toFloat()
            rate = 6f
        }
        if (!camValid) {
            System.arraycopy(targetEye, 0, camPos, 0, 3)
            System.arraycopy(targetLook, 0, camLook, 0, 3)
            camValid = true
            return
        }
        val k = 1f - exp(-dt * rate)
        for (i in 0 until 3) {
            camPos[i] += (targetEye[i] - camPos[i]) * k
            camLook[i] += (targetLook[i] - camLook[i]) * k
        }
    }

    // --- upload ------------------------------------------------------------------------------

    /** Builds the heavy meshes off the GL thread; [onDrawFrame] uploads them when they are ready. */
    private fun startBuild(w: World) {
        building = w
        Thread({
            val result = BuiltWorld(
                w,
                WorldMeshes.terrain(w.terrain, w.biome, w.path),
                WorldMeshes.road(w.path, w.terrain),
                WorldMeshes.skyline(WorldMeshes.SKYLINE_RADIUS, WorldMeshes.skylineRelief(w.biome), w.route.id.hashCode() and 0xFF),
            )
            built = result
        }, "world-meshes").start()
    }

    private fun upload(b: BuiltWorld) {
        val w = b.world
        terrain?.delete(); road?.delete(); skyline?.delete()
        signs.forEach { GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0) }
        signs.clear()
        terrain = GpuMesh(b.terrain)
        road = GpuMesh(b.road)
        skyline = GpuMesh(b.skyline)
        treeLine = w.treeLineM.toFloat()
        val relief = WorldMeshes.skylineRelief(w.biome)
        // Paris has no mountains on the horizon; elsewhere the ring sits low enough that its peaks show.
        skylineDrop = if (w.biome == Biome.CITY) WorldMeshes.SKYLINE_DROP
        else minOf(WorldMeshes.SKYLINE_DROP, relief * WorldMeshes.SKYLINE_RADIUS * 0.4f)
        propBuckets.clear()
        visibleCounts.clear()
        for (p in w.props) {
            val key = bucketKey(p.x, p.z)
            propBuckets.getOrPut(p.kind) { HashMap() }.getOrPut(key) { mutableListOf() }.add(p)
        }
        lastCullX = Double.NaN
        for (s in w.signs) {
            val m = FloatArray(16)
            Matrix.setIdentityM(m, 0)
            Matrix.translateM(m, 0, s.x.toFloat(), s.y.toFloat(), s.z.toFloat())
            // The board faces the rider coming up the road.
            Matrix.rotateM(m, 0, Math.toDegrees(s.heading).toFloat() + 180f, 0f, 1f, 0f)
            val scale = if (s.big) 1.6f else 1.0f
            Matrix.scaleM(m, 0, scale, scale, scale)
            signs += GpuSign(Gl.textTexture(s.text, s.big), m)
        }
        val (fog, zen) = when (w.biome) {
            Biome.VENTOUX -> floatArrayOf(0.8f, 0.84f, 0.9f) to floatArrayOf(0.25f, 0.45f, 0.85f)
            Biome.CITY -> floatArrayOf(0.78f, 0.8f, 0.86f) to floatArrayOf(0.4f, 0.6f, 0.9f)
            Biome.FIELDS -> floatArrayOf(0.76f, 0.82f, 0.88f) to floatArrayOf(0.42f, 0.62f, 0.9f)
            else -> floatArrayOf(0.72f, 0.8f, 0.9f) to floatArrayOf(0.3f, 0.52f, 0.86f)
        }
        fogColor = fog; zenith = zen
        world = w
        building = null
        smoothDistance = frame.distanceM
        camValid = false
        // The emulator screenshot workflow waits for this line before it takes a picture.
        Log.i("WorldView", "world ready: ${w.route.id}")
    }

    private fun bucketKey(x: Double, z: Double): Long = floor(x / BUCKET).toLong() * 100_003L + floor(z / BUCKET).toLong()

    // --- drawing -------------------------------------------------------------------------------

    private fun setCommonUniforms(p: Program, fogDensity: Float) {
        p.use()
        p.setMat4("uViewProj", viewProj)
        p.set3("uSunDir", sunDir)
        p.set3("uSunColor", sunColor)
        p.set3("uSkyColor", skyColor)
        p.set3("uCamPos", camPos)
        p.set3("uFogColor", fogColor)
        p.set("uFogDensity", fogDensity)
        p.set("uTreeLine", treeLine)
        p.set("uWindows", 0f)
        p.set("uTintMode", 0f)
    }

    private fun drawStatic(p: Program, mesh: GpuMesh?, fogDensity: Float, modelMatrix: FloatArray? = null) {
        mesh ?: return
        setCommonUniforms(p, fogDensity)
        if (modelMatrix != null) System.arraycopy(modelMatrix, 0, model, 0, 16) else Matrix.setIdentityM(model, 0)
        p.setMat4("uModel", model)
        GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
        GLES30.glVertexAttrib4f(4, 0f, 1f, 1f, 1f)
        mesh.draw()
    }

    private fun drawProps(camX: Double, camZ: Double) {
        val p = litProgram ?: return
        setCommonUniforms(p, FOG)
        Matrix.setIdentityM(model, 0)
        p.setMat4("uModel", model)
        // Only props within range are uploaded; the list is refreshed when the camera moved far enough.
        val moved = lastCullX.isNaN() || sqrt((camX - lastCullX) * (camX - lastCullX) + (camZ - lastCullZ) * (camZ - lastCullZ)) > 40
        if (moved) {
            lastCullX = camX; lastCullZ = camZ
            for ((kind, buckets) in propBuckets) {
                var n = 0
                val bx = floor(camX / BUCKET).toLong(); val bz = floor(camZ / BUCKET).toLong()
                val reach = (PROP_VIEW / BUCKET).toInt() + 1
                loop@ for (dx in -reach..reach) for (dz in -reach..reach) {
                    val list = buckets[(bx + dx) * 100_003L + (bz + dz)] ?: continue
                    for (pr in list) {
                        val ddx = pr.x - camX; val ddz = pr.z - camZ
                        if (ddx * ddx + ddz * ddz > PROP_VIEW * PROP_VIEW) continue
                        if (n >= MAX_INSTANCES) break@loop
                        val o = n * 8
                        instanceArray[o] = pr.x.toFloat(); instanceArray[o + 1] = pr.y.toFloat(); instanceArray[o + 2] = pr.z.toFloat()
                        instanceArray[o + 3] = pr.scale.toFloat(); instanceArray[o + 4] = pr.rotation.toFloat()
                        instanceTint(kind, pr, o + 5)
                        n++
                    }
                }
                visibleCounts[kind] = n
                instanceScratch.position(0)
                instanceScratch.put(instanceArray, 0, n * 8)
                propMeshes[kind]?.uploadInstances(instanceScratch, n)
            }
        }
        for ((kind, mesh) in propMeshes) {
            val n = visibleCounts[kind] ?: 0
            if (n == 0) continue
            p.set("uWindows", if (kind == Prop.BUILDING) 1f else 0f)
            p.set("uTintMode", if (kind == Prop.SPECTATOR) 1f else 0f)
            mesh.drawInstanced(n)
        }
        p.set("uWindows", 0f)
        p.set("uTintMode", 0f)
    }

    /** Per-instance colour: jerseys for the fans, slight variation for trees, rocks and facades. */
    private fun instanceTint(kind: Int, pr: Prop, o: Int) {
        val seed = (pr.x * 7.3 + pr.z * 3.1).toInt()
        val a = Shapes.hash01(seed); val b = Shapes.hash01(seed + 101); val c = Shapes.hash01(seed + 202)
        when (kind) {
            Prop.SPECTATOR -> {
                // Saturated jersey colours (applied to the white jersey only).
                val h = (pr.x * 7.3 + pr.z * 3.1)
                instanceArray[o] = 0.4f + 0.6f * sin(h).toFloat().let { it * it }
                instanceArray[o + 1] = 0.3f + 0.7f * cos(h * 1.7).toFloat().let { it * it }
                instanceArray[o + 2] = 0.3f + 0.7f * sin(h * 0.6 + 2).toFloat().let { it * it }
            }
            Prop.CONIFER, Prop.BROADLEAF -> {
                // Some trees yellower, some bluer and darker.
                instanceArray[o] = 0.82f + 0.36f * a
                instanceArray[o + 1] = 0.85f + 0.3f * b
                instanceArray[o + 2] = 0.80f + 0.3f * c
            }
            Prop.ROCK -> { val g = 0.8f + 0.35f * a; instanceArray[o] = g; instanceArray[o + 1] = g; instanceArray[o + 2] = g * (0.97f + 0.06f * b) }
            Prop.BUILDING -> { instanceArray[o] = 0.94f + 0.1f * a; instanceArray[o + 1] = 0.94f + 0.08f * b; instanceArray[o + 2] = 0.94f + 0.06f * c }
            else -> { instanceArray[o] = 1f; instanceArray[o + 1] = 1f; instanceArray[o + 2] = 1f }
        }
    }

    private fun drawSigns() {
        val p = texProgram ?: return
        if (signs.isEmpty()) return
        p.use()
        p.setMat4("uViewProj", viewProj)
        p.set3("uCamPos", camPos)
        p.set3("uFogColor", fogColor)
        p.set("uFogDensity", FOG)
        p.set("uTex", 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(signVao)
        for (s in signs) {
            val dx = s.model[12] - camPos[0]; val dz = s.model[14] - camPos[2]
            if (dx * dx + dz * dz > 500f * 500f) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.texture)
            p.setMat4("uModel", s.model)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 12)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun drawRiders(w: World, d: Double, pose: PathSample, firstPerson: Boolean) {
        val p = litProgram ?: return
        setCommonUniforms(p, FOG)
        GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
        GLES30.glVertexAttrib4f(4, 0f, 1f, 1f, 1f)
        val base = riderBase
        // In first person the camera sits in the rider's head, so their own body is not drawn.
        if (!firstPerson) {
            Matrix.setIdentityM(base, 0)
            Matrix.translateM(base, 0, pose.x.toFloat(), pose.y.toFloat() + WorldMeshes.ROAD_LIFT, pose.z.toFloat())
            Matrix.rotateM(base, 0, Math.toDegrees(pose.heading).toFloat(), 0f, 1f, 0f)
            // Lean into the grade so the bike sits on the road.
            Matrix.rotateM(base, 0, -Math.toDegrees(Math.atan(w.route.gradientAt(d) / 100)).toFloat(), 1f, 0f, 0f)
            // Ride on the right-hand side of the road.
            Matrix.translateM(base, 0, WorldMeshes.RIDER_OFFSET, 0f, 0f)
            rider?.render(base, crank, wheel, drawRiderPart)
        }

        val pd = frame.pacerDistanceM
        if (!pd.isNaN()) {
            val predicted = if (frame.paused) pd else pd + frame.pacerSpeedMps * ((SystemClock.elapsedRealtime() - frame.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
            val pdist = predicted.coerceIn(0.0, w.route.lengthM)
            if (kotlin.math.abs(pdist - d) < 600) {
                val pp = w.poseAt(pdist)
                Matrix.setIdentityM(base, 0)
                Matrix.translateM(base, 0, pp.x.toFloat(), pp.y.toFloat() + WorldMeshes.ROAD_LIFT, pp.z.toFloat())
                Matrix.rotateM(base, 0, Math.toDegrees(pp.heading).toFloat(), 0f, 1f, 0f)
                Matrix.rotateM(base, 0, -Math.toDegrees(Math.atan(w.route.gradientAt(pdist) / 100)).toFloat(), 1f, 0f, 0f)
                Matrix.translateM(base, 0, WorldMeshes.PACER_OFFSET, 0f, 0f)
                pacer?.render(base, pacerCrank, pacerWheel, drawRiderPart)
            }
        }
    }

    private fun drawSky(nowMs: Long) {
        val p = skyProgram ?: return
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        p.use()
        p.setMat4("uInvViewProj", invViewProj)
        p.set3("uSunDir", sunDir)
        p.set3("uSunColor", sunColor)
        p.set3("uHorizon", fogColor)
        p.set3("uZenith", zenith)
        p.set("uTime", (nowMs % 3_600_000L) / 1000f)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6)
        GLES30.glBindVertexArray(0)
    }

    private fun fullscreenQuad(): Int {
        val vao = Gl.vao()
        val vbo = Gl.buffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val fb = Gl.floatBuffer(floatArrayOf(-1f, -1f, 1f, -1f, 1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f))
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindVertexArray(0)
        return vao
    }

    /** A board 2 m wide on a post, as position + uv; the post is a dark strip below the board. */
    private fun signQuad(): Int {
        val vao = Gl.vao()
        val vbo = Gl.buffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val w = 1.0f; val b = 1.5f; val t = 2.1f
        val data = floatArrayOf(
            -w, b, 0f, 0f, 1f,  w, b, 0f, 1f, 1f,  w, t, 0f, 1f, 0f,
            -w, b, 0f, 0f, 1f,  w, t, 0f, 1f, 0f,  -w, t, 0f, 0f, 0f,
            // Post: uses the dark border pixels at the texture's edge.
            -0.05f, 0f, 0f, 0.005f, 0.5f,  0.05f, 0f, 0f, 0.012f, 0.5f,  0.05f, b, 0f, 0.012f, 0.5f,
            -0.05f, 0f, 0f, 0.005f, 0.5f,  0.05f, b, 0f, 0.012f, 0.5f,  -0.05f, b, 0f, 0.005f, 0.5f,
        )
        val fb = Gl.floatBuffer(data)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindVertexArray(0)
        return vao
    }

    companion object {
        const val MAX_INSTANCES = 6000
        const val BUCKET = 120.0
        const val PROP_VIEW = 650.0
        const val FOG = 0.0009f
    }
}
