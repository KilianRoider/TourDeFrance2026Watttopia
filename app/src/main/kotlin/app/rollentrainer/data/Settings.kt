package app.rollentrainer.data

import android.content.Context
import app.rollentrainer.core.model.AppJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** The four kinds of sensors the app can pair. */
@Serializable
enum class SensorRole(val title: String, val hint: String) {
    TRAINER("Smart-Trainer", "Steuert die Leistung im ERG-Modus (Bluetooth FTMS)"),
    HEART_RATE("Herzfrequenz", "Brustgurt oder Uhr mit Pulsübertragung"),
    CADENCE("Trittfrequenz", "Kurbelsensor (Bluetooth CSC)"),
    POWER("Powermeter", "Optional: Pedale oder Kurbel als Leistungsquelle"),
}

@Serializable
data class SavedSensor(val address: String, val name: String)

@Serializable
data class StravaAuth(
    val accessToken: String,
    val refreshToken: String,
    val expiresAt: Long,
    val athleteName: String = "",
)

@Serializable
data class AppSettings(
    val ftp: Int = 200,
    val weightKg: Double = 75.0,
    val maxHr: Int = 185,
    val autoPause: Boolean = true,
    val sounds: Boolean = true,
    val keepScreenOn: Boolean = true,
    /** Use the power meter instead of the trainer for display and recording. */
    val preferPowerMeter: Boolean = false,
    /** Seconds over which displayed power is averaged. */
    val powerSmoothingSec: Int = 3,
    /** Target power while pedaling slower than this is softened to avoid the ERG "spiral". */
    val lowCadenceRpm: Int = 0,
    val sensors: Map<SensorRole, SavedSensor> = emptyMap(),
    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val strava: StravaAuth? = null,
    val stravaAutoUpload: Boolean = true,
)

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { AppJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        ?: AppSettings()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit().putString(KEY, AppJson.encodeToString(AppSettings.serializer(), next)).apply()
    }

    private companion object {
        const val KEY = "app_settings"
    }
}
