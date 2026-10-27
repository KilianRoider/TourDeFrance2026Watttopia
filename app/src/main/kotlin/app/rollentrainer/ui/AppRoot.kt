package app.rollentrainer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBike
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.rollentrainer.AppContainer
import app.rollentrainer.ui.screens.EditorScreen
import app.rollentrainer.ui.screens.HistoryScreen
import app.rollentrainer.ui.screens.RideDetailScreen
import app.rollentrainer.ui.screens.RideScreen
import app.rollentrainer.ui.screens.SettingsScreen
import app.rollentrainer.ui.screens.WorkoutsScreen
import kotlinx.coroutines.flow.MutableStateFlow

enum class Tab(val title: String, val icon: ImageVector) {
    WORKOUTS("Trainings", Icons.Outlined.DirectionsBike),
    HISTORY("Verlauf", Icons.Outlined.ShowChart),
    SETTINGS("Einstellungen", Icons.Outlined.Settings),
}

sealed interface Screen {
    data class Main(val tab: Tab) : Screen
    data class Editor(val workoutId: String?, val copy: Boolean = false) : Screen
    data class RideDetail(val rideId: String) : Screen
}

/** A tiny back stack; the app has few screens and needs no navigation library. */
class Navigator {
    val stack = mutableStateListOf<Screen>(Screen.Main(Tab.WORKOUTS))
    val current: Screen get() = stack.last()
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
    fun tab(t: Tab) {
        stack.clear()
        stack.add(Screen.Main(t))
    }
}

val LocalWide = staticCompositionLocalOf { false }

/** Bluetooth permissions differ before and after Android 12. */
fun blePermissions(): Array<String> = if (Build.VERSION.SDK_INT >= 31) {
    arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
} else {
    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
}

@Composable
fun AppRoot(container: AppContainer, stravaMessage: MutableStateFlow<String?>) {
    val nav = remember { Navigator() }
    val ride by container.session.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val wide = LocalConfiguration.current.screenWidthDp >= 720

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) container.sensors.connectSaved()
    }
    LaunchedEffect(Unit) {
        val needed = blePermissions().toMutableList()
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) container.sensors.connectSaved() else permissionLauncher.launch(missing.toTypedArray())
    }

    // A running ride takes over the whole screen until it ends.
    if (ride != null) {
        BackHandler { /* ignore: ending a ride needs the explicit button */ }
        androidx.compose.runtime.CompositionLocalProvider(LocalWide provides wide) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                RideScreen(container) { rideId -> rideId?.let { nav.push(Screen.RideDetail(it)) } }
            }
        }
        return
    }

    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    androidx.compose.runtime.CompositionLocalProvider(LocalWide provides wide) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val screen = nav.current
            val content: @Composable () -> Unit = {
                when (screen) {
                    is Screen.Main -> when (screen.tab) {
                        Tab.WORKOUTS -> WorkoutsScreen(
                            container,
                            onEdit = { id, copy -> nav.push(Screen.Editor(id, copy)) },
                        )
                        Tab.HISTORY -> HistoryScreen(container, onOpen = { nav.push(Screen.RideDetail(it)) })
                        Tab.SETTINGS -> SettingsScreen(container, stravaMessage)
                    }
                    is Screen.Editor -> EditorScreen(container, screen.workoutId, screen.copy, onDone = { nav.pop() })
                    is Screen.RideDetail -> RideDetailScreen(container, screen.rideId, onBack = { nav.pop() })
                }
            }
            val selected = (nav.stack.first() as? Screen.Main)?.tab ?: Tab.WORKOUTS
            if (wide) {
                Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    NavigationRail(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = t == selected,
                                onClick = { nav.tab(t) },
                                icon = { Icon(t.icon, contentDescription = t.title) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) { content() }
                }
            } else {
                Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.weight(1f)) { content() }
                    NavigationBar(containerColor = MaterialTheme.colorScheme.background) {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = t == selected,
                                onClick = { nav.tab(t) },
                                icon = { Icon(t.icon, contentDescription = t.title) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                }
            }
        }
    }
}
