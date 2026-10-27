package app.rollentrainer.data

import android.content.Context
import app.rollentrainer.core.metrics.StarTolerance
import app.rollentrainer.core.metrics.ZoneScheme
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

/** One data page of the ride screen: a fixed grid and the field key for every slot ("" = empty). */
@Serializable
data class DataPage(val layout: String, val fields: List<String>)

/** A past FTP value, so the rider sees the trend. */
@Serializable
data class FtpEntry(val epochSec: Long, val ftp: Int, val source: String = "")

/** Text size of the data fields. */
@Serializable
enum class FontScale(val title: String, val factor: Float) {
    S("S", 0.8f), M("M", 1.0f), L("L", 1.2f), XL("XL", 1.45f);
}

@Serializable
data class AppSettings(
    val ftp: Int = 200,
    val weightKg: Double = 75.0,
    val maxHr: Int = 185,
    val ftpHistory: List<FtpEntry> = emptyList(),
    val autoPause: Boolean = true,
    /** Seconds without pedaling until the clock stops: 3, 5 or 10. */
    val autoPauseSec: Int = 3,
    val sounds: Boolean = true,
    /** 0..100 */
    val soundVolume: Int = 80,
    val soundCountdown: Boolean = true,
    val soundSegment: Boolean = true,
    /** Beeps when the cadence drifts from the block's target. */
    val soundCadenceHints: Boolean = true,
    /** Clicks at the target cadence. */
    val metronome: Boolean = false,
    val keepScreenOn: Boolean = true,
    /** Use the power meter instead of the trainer for display and recording. */
    val preferPowerMeter: Boolean = false,
    /** Correct the ERG target so the power meter, not the trainer, hits the target. */
    val powerMatch: Boolean = false,
    /** Seconds over which displayed power is averaged. */
    val powerSmoothingSec: Int = 3,
    /** Below this cadence ERG releases the target so the rider can spin up again; 0 disables it. */
    val ergReleaseRpm: Int = 50,
    val starTolerance: StarTolerance = StarTolerance.NORMAL,
    val zoneScheme: ZoneScheme = ZoneScheme.COGGAN,
    /** Anaerobic work capacity for the W' balance, in kJ. */
    val wPrimeKj: Int = 20,
    /** Weekly TSS goal shown in the history; 0 hides it. */
    val weeklyGoalTss: Int = 0,
    val favoriteWorkoutIds: Set<String> = emptySet(),
    val sensors: Map<SensorRole, SavedSensor> = emptyMap(),
    val stravaClientId: String = "",
    val stravaClientSecret: String = "",
    val strava: StravaAuth? = null,
    val stravaAutoUpload: Boolean = true,
    /** Older layout: data fields in order, first one large. Migrated into [ridePages] on first use. */
    val rideFields: List<String> = listOf("power", "hr", "cadence", "segRemaining", "workoutRemaining", "nextTarget", "powerLap", "tss", "work"),
    val rideColumns: Int = 3,
    /** Data pages during a workout and during a free ride. Empty means "use the default". */
    val ridePages: List<DataPage> = emptyList(),
    val freePages: List<DataPage> = emptyList(),
    val rideFontScale: FontScale = FontScale.M,
    val rideShowCharts: Boolean = true,
    val rideShowProfile: Boolean = true,
    /** Zwift-style bar that shows how far the power is from the target. */
    val rideShowGauge: Boolean = true,
    /** Which live charts are shown: power, hr, cadence, wbal. */
    val rideCharts: List<String> = listOf("power", "hr", "cadence"),
    /** How much of a virtual road's grade the trainer simulates (Zwift calls it trainer difficulty). */
    val trainerDifficultyPct: Int = 100,
    /** Default pacer on virtual routes, in W/kg; 0 = none. */
    val pacerWkg: Double = 3.0,
    /** Spinning-class view: tree URI of the music folder, or empty for the built-in beat. */
    val musicFolder: String = "",
    /** Play the synthesised studio beat when no folder is chosen (or in addition, when the folder is empty). */
    val studioBeat: Boolean = true,
    /** Spoken coach cues in the spinning view. */
    val coachVoice: Boolean = true,
    /** Music volume 0..100 in the spinning view. */
    val musicVolume: Int = 70,
    /** Open workouts in the spinning-class view by default. */
    val spinningByDefault: Boolean = false,
) {
    val autoPauseSeconds: Double get() = if (autoPause) autoPauseSec.coerceIn(1, 30).toDouble() else 0.0
}

class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppSettings> = _state.asStateFlow()
    val value: AppSettings get() = _state.value

    private fun load(): AppSettings = prefs.getString(KEY, null)
        ?.let { runCatching { AppJson.decodeFromString(AppSettings.serializer(), it) }.getOrNull() }
        ?.let { it.copy(wPrimeKj = it.wPrimeKj.coerceIn(5, 50), ftp = it.ftp.coerceIn(50, 600)) }
        ?: AppSettings()

    @Synchronized
    fun update(change: (AppSettings) -> AppSettings) {
        val next = change(_state.value)
        _state.value = next
        prefs.edit().putString(KEY, AppJson.encodeToString(AppSettings.serializer(), next)).apply()
    }

    /** Changes the FTP and remembers the old value in the history. */
    fun setFtp(ftp: Int, source: String) = update { s ->
        val v = ftp.coerceIn(50, 600)
        if (v == s.ftp) s
        else s.copy(ftp = v, ftpHistory = (s.ftpHistory + FtpEntry(System.currentTimeMillis() / 1000, v, source)).takeLast(50))
    }

    private companion object {
        const val KEY = "app_settings"
    }
}
