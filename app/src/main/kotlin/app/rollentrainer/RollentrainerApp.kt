package app.rollentrainer

import android.app.Application
import app.rollentrainer.ble.BleScanner
import app.rollentrainer.ble.SensorHub
import app.rollentrainer.data.RideRepository
import app.rollentrainer.data.SettingsStore
import app.rollentrainer.data.WorkoutRepository
import app.rollentrainer.ride.RideSession
import app.rollentrainer.strava.StravaClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Holds the app-wide singletons; screens reach them through [App.container]. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings = SettingsStore(app)
    val workouts = WorkoutRepository(app, scope)
    val rides = RideRepository(app, scope)
    val scanner = BleScanner(app)
    val sensors = SensorHub(app, settings, scope)
    val strava = StravaClient(settings)
    val session = RideSession(app, sensors, settings, rides, strava, scope)
}

class RollentrainerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
