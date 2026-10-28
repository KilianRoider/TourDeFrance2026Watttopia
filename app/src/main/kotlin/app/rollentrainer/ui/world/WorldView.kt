package app.rollentrainer.ui.world

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.World
import app.rollentrainer.ui.world.geo.GeoScene
import app.rollentrainer.ui.world.geo.GeoWorldRenderer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLDisplay

/**
 * The 3D road inside Compose. The GL surface sits behind the window, so everything drawn over it
 * must stay transparent where the world should show through.
 */
@Composable
fun WorldView(world: World?, frame: FrameState, modifier: Modifier = Modifier) {
    val renderer = remember { WorldRenderer(frame) }
    LaunchedEffect(world) { renderer.pendingWorld = world }
    WorldSurface(renderer, modifier)
}

/**
 * The real terrain of a route from its geodata pack ([GeoWorldRenderer]). The scene's listener hears when
 * the first complete frame is on screen (hide the loading text then) or when loading failed (fall back
 * to [WorldView]). The streaming worker stops when this leaves the composition; the caller closes the scene.
 */
@Composable
fun GeoWorldView(scene: GeoScene, frame: FrameState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val renderer = remember { GeoWorldRenderer(frame, context.applicationContext) }
    LaunchedEffect(scene) { renderer.pendingScene = scene }
    DisposableEffect(renderer) { onDispose { renderer.release() } }
    WorldSurface(renderer, modifier, maxPixels = GEO_MAX_PIXELS)
}

/**
 * The geodata world renders at most this many pixels; larger views get a smaller surface that the
 * compositor scales up (a 2560x1600 tablet renders ~1960x1225). Textured terrain, thousands of trees
 * and 4x MSAA at the full 4 MP would not hold 30 fps on a mid-range tablet GPU, and the broadcast
 * look is soft anyway.
 */
private const val GEO_MAX_PIXELS = 2_400_000

/**
 * A GL surface for [renderer] that pauses and resumes with the lifecycle. With [maxPixels] the
 * surface is scaled down (same aspect) when the view is larger.
 */
@Composable
private fun WorldSurface(renderer: GLSurfaceView.Renderer, modifier: Modifier, maxPixels: Int = 0) {
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, surface) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> surface?.onPause()
                Lifecycle.Event.ON_RESUME -> surface?.onResume()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(3)
                // Multisampled when the device offers it; see FallbackConfigChooser. Without maxPixels the
                // surface keeps the view's own size, so it renders at the native resolution.
                setEGLConfigChooser(FallbackConfigChooser())
                if (maxPixels > 0) {
                    addOnLayoutChangeListener { v, l, t, r, b, _, _, _, _ ->
                        val w = r - l; val h = b - t
                        val gl = v as GLSurfaceView
                        if (w > 0 && h > 0 && w.toLong() * h > maxPixels) {
                            val s = Math.sqrt(maxPixels.toDouble() / (w.toLong() * h))
                            gl.holder.setFixedSize((w * s).toInt(), (h * s).toInt())
                        } else gl.holder.setSizeFromLayout()
                    }
                }
                preserveEGLContextOnPause = true
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
                surface = this
            }
        },
        modifier = modifier,
    )
}

/** True when the device can run the OpenGL ES 3.0 world. */
fun supportsWorld3d(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
    return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}

/**
 * Picks the best ES 3 config the device offers instead of throwing when the first wish is missing.
 * Anti-aliasing first: 4x MSAA, then 2x, then none; within each, RGB888 with a 24-bit depth buffer,
 * then 16-bit depth, then RGB565. So a device without multisampling still gets the deepest
 * colour and depth it has, and one with it never trades colour depth for samples.
 */
private class FallbackConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val colours = listOf(intArrayOf(8, 8, 8, 24), intArrayOf(8, 8, 8, 16), intArrayOf(5, 6, 5, 16))
        for (samples in intArrayOf(4, 2, 0)) for (w in colours) {
            val spec = ArrayList<Int>(16)
            spec += listOf(
                EGL10.EGL_RED_SIZE, w[0], EGL10.EGL_GREEN_SIZE, w[1], EGL10.EGL_BLUE_SIZE, w[2],
                EGL10.EGL_DEPTH_SIZE, w[3], EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
            )
            if (samples > 0) spec += listOf(EGL10.EGL_SAMPLE_BUFFERS, 1, EGL10.EGL_SAMPLES, samples)
            spec += EGL10.EGL_NONE
            val attribs = spec.toIntArray()
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, attribs, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, attribs, configs, count[0], count)) continue
            // eglChooseConfig treats sizes as minimums and sorts deeper colour first; take the first that
            // matches the wish exactly in colour and has at least the depth and samples asked for.
            val exact = configs.filterNotNull().firstOrNull { c ->
                attr(egl, display, c, EGL10.EGL_RED_SIZE) == w[0] && attr(egl, display, c, EGL10.EGL_GREEN_SIZE) == w[1] &&
                    attr(egl, display, c, EGL10.EGL_BLUE_SIZE) == w[2] && attr(egl, display, c, EGL10.EGL_DEPTH_SIZE) >= w[3] &&
                    (samples == 0 || (attr(egl, display, c, EGL10.EGL_SAMPLE_BUFFERS) == 1 && attr(egl, display, c, EGL10.EGL_SAMPLES) >= samples))
            }
            val chosen = exact ?: configs.firstOrNull() ?: continue
            Log.i(TAG, "EGL config: rgb ${w[0]}${w[1]}${w[2]} depth ${attr(egl, display, chosen, EGL10.EGL_DEPTH_SIZE)} samples ${attr(egl, display, chosen, EGL10.EGL_SAMPLES)}")
            return chosen
        }
        throw IllegalArgumentException("No OpenGL ES 3 configuration")
    }

    private fun attr(egl: EGL10, display: EGLDisplay, config: EGLConfig, name: Int): Int {
        val v = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, config, name, v)) v[0] else 0
    }

    companion object {
        private const val TAG = "WorldView"
        /** EGL_OPENGL_ES3_BIT_KHR */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}

/** Keeps the last built world, so turning the tablet or coming back to the ride does not rebuild it. */
object WorldCache {
    private class Entry(val key: String, val world: World)
    /** The current section and the next one, so a long route hands over without a pause. */
    private val entries = ArrayDeque<Entry>()

    private fun keyOf(route: Route) = "${route.id}|${route.lengthM}|${route.offsetM}"

    @Synchronized
    fun peek(route: Route): World? = entries.firstOrNull { it.key == keyOf(route) }?.world

    fun get(route: Route): World {
        peek(route)?.let { return it }
        val w = World(route, route.biome)
        synchronized(this) {
            if (entries.none { it.key == keyOf(route) }) {
                entries.addLast(Entry(keyOf(route), w))
                while (entries.size > 2) entries.removeFirst()
            }
        }
        return w
    }
}
