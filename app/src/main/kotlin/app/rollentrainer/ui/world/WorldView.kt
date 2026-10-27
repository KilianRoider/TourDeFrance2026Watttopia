package app.rollentrainer.ui.world

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLSurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.World
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
    var surface by remember { mutableStateOf<GLSurfaceView?>(null) }
    LaunchedEffect(world) { renderer.pendingWorld = world }
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
                setEGLConfigChooser(FallbackConfigChooser())
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
 * Picks the best ES 3 config the device offers instead of throwing when the first wish is missing:
 * RGB888 with a 24-bit depth buffer, then 16-bit depth, then RGB565.
 */
private class FallbackConfigChooser : GLSurfaceView.EGLConfigChooser {
    override fun chooseConfig(egl: EGL10, display: EGLDisplay): EGLConfig {
        val wishes = listOf(intArrayOf(8, 8, 8, 24), intArrayOf(8, 8, 8, 16), intArrayOf(5, 6, 5, 16))
        for (w in wishes) {
            val spec = intArrayOf(
                EGL10.EGL_RED_SIZE, w[0], EGL10.EGL_GREEN_SIZE, w[1], EGL10.EGL_BLUE_SIZE, w[2],
                EGL10.EGL_DEPTH_SIZE, w[3], EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL10.EGL_NONE,
            )
            val count = IntArray(1)
            if (!egl.eglChooseConfig(display, spec, null, 0, count) || count[0] <= 0) continue
            val configs = arrayOfNulls<EGLConfig>(count[0])
            if (!egl.eglChooseConfig(display, spec, configs, count[0], count)) continue
            // eglChooseConfig sorts deeper colour first; take the first that matches the wish exactly.
            val exact = configs.filterNotNull().firstOrNull { c ->
                attr(egl, display, c, EGL10.EGL_RED_SIZE) == w[0] && attr(egl, display, c, EGL10.EGL_GREEN_SIZE) == w[1] &&
                    attr(egl, display, c, EGL10.EGL_BLUE_SIZE) == w[2] && attr(egl, display, c, EGL10.EGL_DEPTH_SIZE) >= w[3]
            }
            (exact ?: configs.firstOrNull())?.let { return it }
        }
        throw IllegalArgumentException("No OpenGL ES 3 configuration")
    }

    private fun attr(egl: EGL10, display: EGLDisplay, config: EGLConfig, name: Int): Int {
        val v = IntArray(1)
        return if (egl.eglGetConfigAttrib(display, config, name, v)) v[0] else 0
    }

    companion object {
        /** EGL_OPENGL_ES3_BIT_KHR */
        const val EGL_OPENGL_ES3_BIT = 0x40
    }
}

/** Keeps the last built world, so turning the tablet or coming back to the ride does not rebuild it. */
object WorldCache {
    private var key: Pair<String, Double>? = null
    private var world: World? = null

    @Synchronized
    fun get(route: Route): World {
        val k = route.id to route.lengthM
        world?.let { if (key == k) return it }
        return World(route, route.biome).also { world = it; key = k }
    }
}
