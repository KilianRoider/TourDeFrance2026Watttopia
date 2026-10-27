package app.rollentrainer

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import app.rollentrainer.ui.AppRoot
import app.rollentrainer.ui.theme.RollentrainerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    /** Messages from the Strava login that the settings screen shows once. */
    val stravaMessage = MutableStateFlow<String?>(null)

    private val container get() = (application as RollentrainerApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleIntent(intent)
        lifecycleScope.launch {
            // Keep the display on while riding, if the user wants it.
            container.session.state.collect { ride ->
                val keepOn = ride != null && container.settings.value.keepScreenOn
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        setContent {
            RollentrainerTheme {
                AppRoot(container, stravaMessage)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
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
}
