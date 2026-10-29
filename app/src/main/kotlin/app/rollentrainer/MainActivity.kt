package app.rollentrainer

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.rollentrainer.debug.DemoMode
import app.rollentrainer.ui.AppRoot
import app.rollentrainer.ui.theme.RollentrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Messages from the Strava login that the settings screen shows once. */
    val stravaMessage = MutableStateFlow<String?>(null)

    /** The scene the screenshot workflow asked for (see [DemoMode]); null in normal use. */
    private val demoScene = MutableStateFlow<DemoMode.Scene?>(null)

    /** A demo scene waits for the activity to turn to the orientation the workflow asked for. */
    private var demoPending = false
    /** The intent of that scene (onNewIntent does not replace [getIntent]). */
    private var demoIntent: Intent? = null

    private val container get() = (application as RollentrainerApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Recreated with the same intent (a theme or language change; turning the device no longer recreates it),
        // the scene is already running, unless it was put off until the orientation the workflow asked for was in place.
        if (savedInstanceState == null || savedInstanceState.getBoolean(KEY_DEMO_PENDING)) handleDemo(intent)
        handleIntent(intent)
        lifecycleScope.launch {
            // Keep the display on while riding, if the user wants it.
            container.session.state.collect { ride ->
                val keepOn = DemoMode.active || (ride != null && container.settings.value.keepScreenOn)
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            RollentrainerTheme {
                AppRoot(container, stravaMessage, demoScene)
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_DEMO_PENDING, demoPending)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleDemo(intent)
        handleIntent(intent)
    }

    /**
     * The activity turns without being recreated (configChanges in the manifest: a ride's GL world and its
     * streamed terrain survive it, Compose lays out again). A demo scene waiting for the turn starts here.
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (demoPending) handleDemo(demoIntent ?: intent, newConfig.orientation)
    }

    private fun handleDemo(intent: Intent?, orientation: Int = resources.configuration.orientation) {
        val scene = DemoMode.sceneFrom(this, intent) ?: return
        // The emulator ignores the system rotation settings on some images, so the screenshot workflow asks the
        // activity itself for its orientation. Once it has turned, onConfigurationChanged starts the scene.
        val wanted = when (intent?.getStringExtra(EXTRA_ORIENTATION)) {
            "landscape" -> Configuration.ORIENTATION_LANDSCAPE
            "portrait" -> Configuration.ORIENTATION_PORTRAIT
            else -> null
        }
        if (wanted != null) {
            requestedOrientation = if (wanted == Configuration.ORIENTATION_LANDSCAPE) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            if (orientation != wanted) {
                demoPending = true
                demoIntent = intent
                return
            }
        }
        demoPending = false
        demoIntent = null
        demoScene.value = DemoMode.start(container, scene)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != "rollentrainer") return
        lifecycleScope.launch {
            stravaMessage.value = container.strava.handleRedirect(uri).fold(
                onSuccess = { name -> if (name.isBlank()) "Mit Strava verbunden" else "Mit Strava verbunden als $name" },
                onFailure = { it.message ?: "Strava-Verbindung fehlgeschlagen" },
            )
        }
    }

    private companion object {
        const val EXTRA_ORIENTATION = "wattwerk_orientation"
        const val KEY_DEMO_PENDING = "wattwerk_demo_pending"
    }
}
