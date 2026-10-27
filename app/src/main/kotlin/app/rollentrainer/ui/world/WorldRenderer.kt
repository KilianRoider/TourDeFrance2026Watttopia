package app.rollentrainer.ui.world

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import app.rollentrainer.core.route.Biome
import app.rollentrainer.core.route.PathSample
import app.rollentrainer.core.route.Prop
import app.rollentrainer.core.route.World
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
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
}

/**
 * Draws the virtual road: sky, far mountains, terrain, the road, trees and buildings (instanced),
 * signs, the rider and the pacer. The world is built on a background thread and uploaded on the GL
 * thread when it arrives.
 */
class WorldRenderer(val frame: FrameState) : GLSurfaceView.Renderer {
    @Volatile var pendingWorld: World? = null
    private var world: World? = null

    private var litProgram = 0
    private var roadProgram = 0
    private var skyProgram = 0
    private var texProgram = 0
    private var skyVao = 0
    private var signVao = 0

    private var terrain: GpuMesh? = null
    private var road: GpuMesh? = null
    private var skyline: GpuMesh? = null
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

    private val proj = FloatArray(16)
    private val view = FloatArray(16)
    private val viewProj = FloatArray(16)
    private val model = FloatArray(16)
    private var aspect = 1f
    private var crank = 0f
    private var wheel = 0f
    private var lastFrameMs = 0L
    private var smoothDistance = 0.0
    private var pacerCrank = 0f
    private val camPos = FloatArray(3)
    private var fogColor = floatArrayOf(0.72f, 0.8f, 0.9f)
    private var zenith = floatArrayOf(0.3f, 0.52f, 0.86f)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        litProgram = Gl.program(Shaders.LIT_VS, Shaders.LIT_FS)
        roadProgram = Gl.program(Shaders.LIT_VS, Shaders.ROAD_FS)
        skyProgram = Gl.program(Shaders.SKY_VS, Shaders.SKY_FS)
        texProgram = Gl.program(Shaders.TEX_VS, Shaders.TEX_FS)
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
        terrain = null; road = null; skyline = null
        signs.clear()
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        aspect = width.toFloat() / height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        val pending = pendingWorld
        if (pending != null && pending !== world) upload(pending)
        val w = world
        GLES30.glClearColor(fogColor[0], fogColor[1], fogColor[2], 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        if (w == null) return

        // Interpolate the rider between the one-second ticks of the ride loop.
        val now = SystemClock.elapsedRealtime()
        val dt = if (lastFrameMs == 0L) 0.016f else ((now - lastFrameMs) / 1000f).coerceIn(0f, 0.1f)
        lastFrameMs = now
        val f = frame
        val predicted = if (f.paused) f.distanceM else f.distanceM + f.speedMps * ((now - f.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
        smoothDistance += (predicted - smoothDistance) * (1 - Math.exp(-dt * 4.0))
        if (smoothDistance > predicted + 2 || smoothDistance < predicted - 20) smoothDistance = predicted
        val d = smoothDistance.coerceIn(0.0, w.route.lengthM)
        if (!f.paused) {
            crank += (f.cadenceRpm / 60.0 * 2 * PI * dt).toFloat()
            wheel += ((f.speedMps * dt) / RiderModel.WHEEL_R * 180 / PI).toFloat()
        }

        val pose = w.poseAt(d)
        val behind = w.poseAt((d - 7.5).coerceAtLeast(0.0))
        val eyeY = (behind.y + 2.3).toFloat()
        val ex = behind.x.toFloat(); val ez = behind.z.toFloat()
        // The camera sits behind the rider on the road, looking a little ahead of them.
        val lx = (pose.x + sin(pose.heading) * 4).toFloat()
        val ly = (pose.y + 1.1).toFloat()
        val lz = (pose.z + cos(pose.heading) * 4).toFloat()
        camPos[0] = ex; camPos[1] = eyeY; camPos[2] = ez
        Matrix.setLookAtM(view, 0, ex, eyeY, ez, lx, ly, lz, 0f, 1f, 0f)
        Matrix.perspectiveM(proj, 0, 58f, aspect, 0.4f, 4000f)
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)

        drawSky(ly - eyeY)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        drawStatic(litProgram, skyline, 0.00025f)
        drawStatic(litProgram, terrain, FOG)
        drawStatic(roadProgram, road, FOG)
        drawProps(ex.toDouble(), ez.toDouble())
        drawSigns()
        drawRiders(w, d, pose)
    }

    // --- upload ------------------------------------------------------------------------------

    private fun upload(w: World) {
        terrain?.delete(); road?.delete(); skyline?.delete()
        signs.forEach { GLES30.glDeleteTextures(1, intArrayOf(it.texture), 0) }
        signs.clear()
        terrain = GpuMesh(WorldMeshes.terrain(w.terrain, w.biome))
        road = GpuMesh(WorldMeshes.road(w.path))
        val t = w.terrain
        val cx = (t.originX + t.cols * t.cell / 2).toFloat()
        val cz = (t.originZ + t.rows * t.cell / 2).toFloat()
        val radius = (maxOf(t.cols, t.rows) * t.cell / 2 + 2600).toFloat()
        val base = (w.path.minOf { it.y } - 150).toFloat()
        skyline = GpuMesh(WorldMeshes.skyline(cx, cz, base, radius, w.route.id.hashCode() and 0xFF))
        propBuckets.clear()
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
        smoothDistance = frame.distanceM
    }

    private fun bucketKey(x: Double, z: Double): Long = floor(x / BUCKET).toLong() * 100_003L + floor(z / BUCKET).toLong()

    // --- drawing -------------------------------------------------------------------------------

    private fun setCommonUniforms(program: Int, fogDensity: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uViewProj"), 1, false, viewProj, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(program, "uLightDir"), 0.45f, 0.78f, 0.35f)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uCamPos"), 1, camPos, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(program, "uFogColor"), 1, fogColor, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uFogDensity"), fogDensity)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(program, "uWindows"), 0f)
    }

    private fun drawStatic(program: Int, mesh: GpuMesh?, fogDensity: Float) {
        mesh ?: return
        setCommonUniforms(program, fogDensity)
        Matrix.setIdentityM(model, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(program, "uModel"), 1, false, model, 0)
        GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
        GLES30.glVertexAttrib4f(4, 0f, 1f, 1f, 1f)
        mesh.draw()
    }

    private fun drawProps(camX: Double, camZ: Double) {
        setCommonUniforms(litProgram, FOG)
        Matrix.setIdentityM(model, 0)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(litProgram, "uModel"), 1, false, model, 0)
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
                    for (p in list) {
                        val ddx = p.x - camX; val ddz = p.z - camZ
                        if (ddx * ddx + ddz * ddz > PROP_VIEW * PROP_VIEW) continue
                        if (n >= MAX_INSTANCES) break@loop
                        val o = n * 8
                        instanceArray[o] = p.x.toFloat(); instanceArray[o + 1] = p.y.toFloat(); instanceArray[o + 2] = p.z.toFloat()
                        instanceArray[o + 3] = p.scale.toFloat(); instanceArray[o + 4] = p.rotation.toFloat()
                        if (kind == Prop.SPECTATOR) {
                            val h = (p.x * 7.3 + p.z * 3.1)
                            instanceArray[o + 5] = 0.5f + 0.5f * sin(h).toFloat().let { it * it }
                            instanceArray[o + 6] = 0.4f + 0.6f * cos(h * 1.7).toFloat().let { it * it }
                            instanceArray[o + 7] = 0.3f + 0.7f * sin(h * 0.6 + 2).toFloat().let { it * it }
                        } else {
                            instanceArray[o + 5] = 1f; instanceArray[o + 6] = 1f; instanceArray[o + 7] = 1f
                        }
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
            GLES30.glUniform1f(GLES30.glGetUniformLocation(litProgram, "uWindows"), if (kind == Prop.BUILDING) 1f else 0f)
            mesh.drawInstanced(n)
        }
        GLES30.glUniform1f(GLES30.glGetUniformLocation(litProgram, "uWindows"), 0f)
    }

    private fun drawSigns() {
        if (signs.isEmpty()) return
        GLES30.glUseProgram(texProgram)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(texProgram, "uViewProj"), 1, false, viewProj, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(texProgram, "uCamPos"), 1, camPos, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(texProgram, "uFogColor"), 1, fogColor, 0)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(texProgram, "uFogDensity"), FOG)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(texProgram, "uTex"), 0)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glBindVertexArray(signVao)
        for (s in signs) {
            val dx = s.model[12] - camPos[0]; val dz = s.model[14] - camPos[2]
            if (dx * dx + dz * dz > 500f * 500f) continue
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, s.texture)
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(texProgram, "uModel"), 1, false, s.model, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 12)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun drawRiders(w: World, d: Double, pose: PathSample) {
        setCommonUniforms(litProgram, FOG)
        GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
        GLES30.glVertexAttrib4f(4, 0f, 1f, 1f, 1f)
        val draw: (MeshData, FloatArray) -> Unit = { mesh, m ->
            GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(litProgram, "uModel"), 1, false, m, 0)
            riderGpu.getOrPut(mesh) { GpuMesh(mesh) }.draw()
        }
        val base = FloatArray(16)
        Matrix.setIdentityM(base, 0)
        Matrix.translateM(base, 0, pose.x.toFloat(), pose.y.toFloat() + 0.02f, pose.z.toFloat())
        Matrix.rotateM(base, 0, Math.toDegrees(pose.heading).toFloat(), 0f, 1f, 0f)
        // Lean into the grade so the bike sits on the road.
        Matrix.rotateM(base, 0, -Math.toDegrees(Math.atan(w.route.gradientAt(d) / 100)).toFloat(), 1f, 0f, 0f)
        Matrix.translateM(base, 0, 0.9f, 0f, 0f)
        rider?.render(base, crank, wheel, draw)

        val pd = frame.pacerDistanceM
        if (!pd.isNaN()) {
            val predicted = if (frame.paused) pd else pd + frame.pacerSpeedMps * ((SystemClock.elapsedRealtime() - frame.updatedAtMs) / 1000.0).coerceIn(0.0, 1.5)
            val pdist = predicted.coerceIn(0.0, w.route.lengthM)
            if (kotlin.math.abs(pdist - d) < 600) {
                val pp = w.poseAt(pdist)
                pacerCrank += (85.0 / 60.0 * 2 * PI * 0.016).toFloat()
                Matrix.setIdentityM(base, 0)
                Matrix.translateM(base, 0, pp.x.toFloat(), pp.y.toFloat() + 0.02f, pp.z.toFloat())
                Matrix.rotateM(base, 0, Math.toDegrees(pp.heading).toFloat(), 0f, 1f, 0f)
                Matrix.rotateM(base, 0, -Math.toDegrees(Math.atan(w.route.gradientAt(pdist) / 100)).toFloat(), 1f, 0f, 0f)
                Matrix.translateM(base, 0, -0.9f, 0f, 0f)
                pacer?.render(base, pacerCrank, wheel, draw)
            }
        }
    }

    private fun drawSky(lookUp: Float) {
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glUseProgram(skyProgram)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(skyProgram, "uHorizon"), 1, fogColor, 0)
        GLES30.glUniform3fv(GLES30.glGetUniformLocation(skyProgram, "uZenith"), 1, zenith, 0)
        // The horizon moves down on the screen when the camera looks up the climb.
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProgram, "uHorizonY"), (0.5f - lookUp * 0.08f).coerceIn(0.2f, 0.7f))
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
        const val FOG = 0.0011f
    }
}
