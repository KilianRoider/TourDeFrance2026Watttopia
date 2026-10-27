package app.rollentrainer.data

import android.content.Context
import app.rollentrainer.core.fit.FitWriter
import app.rollentrainer.core.import.Mrc
import app.rollentrainer.core.model.AppJson
import app.rollentrainer.core.model.Presets
import app.rollentrainer.core.model.SpinningPresets
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.zwo.Zwo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Stores the user's workouts as JSON files; built-in presets are always listed first. */
class WorkoutRepository(context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "workouts").apply { mkdirs() }
    private val _workouts = MutableStateFlow(Presets.all + SpinningPresets.all)
    val workouts: StateFlow<List<Workout>> = _workouts.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    private fun reload() {
        val own = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { AppJson.decodeFromString(Workout.serializer(), it.readText()) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
        _workouts.value = own + Presets.all + SpinningPresets.all
    }

    fun find(id: String): Workout? = _workouts.value.firstOrNull { it.id == id }

    suspend fun save(workout: Workout) = withContext(Dispatchers.IO) {
        File(dir, "${workout.id}.json").writeText(AppJson.encodeToString(Workout.serializer(), workout.copy(builtIn = false)))
        reload()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        reload()
    }

    /** Imports a Zwift (.zwo) or TrainerRoad/ErgVideo (.mrc, .erg) workout file. */
    suspend fun import(stream: java.io.InputStream, fileName: String = ""): Workout = withContext(Dispatchers.IO) {
        val lower = fileName.lowercase()
        val workout = if (lower.endsWith(".mrc") || lower.endsWith(".erg")) {
            Mrc.parse(stream.bufferedReader().readText(), fileName)
        } else {
            val bytes = stream.readBytes()
            val head = String(bytes, 0, bytes.size.coerceAtMost(200)).trimStart()
            if (head.startsWith("[")) Mrc.parse(String(bytes), fileName) else Zwo.parse(bytes.inputStream())
        }
        save(workout)
        workout
    }
}

/** Stores finished rides; each ride is one JSON file with its samples. */
class RideRepository(private val context: Context, scope: CoroutineScope) {
    private val dir = File(context.filesDir, "rides").apply { mkdirs() }
    private val _rides = MutableStateFlow<List<Ride>>(emptyList())
    val rides: StateFlow<List<Ride>> = _rides.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) { reload() }
    }

    private fun reload() {
        _rides.value = dir.listFiles { f -> f.extension == "json" }.orEmpty()
            .mapNotNull { runCatching { AppJson.decodeFromString(Ride.serializer(), it.readText()) }.getOrNull() }
            .sortedByDescending { it.startEpochSec }
    }

    fun find(id: String): Ride? = _rides.value.firstOrNull { it.id == id }

    suspend fun save(ride: Ride) = withContext(Dispatchers.IO) {
        File(dir, "${ride.id}.json").writeText(AppJson.encodeToString(Ride.serializer(), ride))
        reload()
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        File(dir, "$id.json").delete()
        reload()
    }

    /** Writes the ride as a FIT file into the shareable export folder. */
    suspend fun exportFit(ride: Ride): File = withContext(Dispatchers.IO) {
        val out = File(context.cacheDir, "exports").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.GERMANY).format(Date(ride.startEpochSec * 1000))
        File(out, "Wattwerk_$stamp.fit").apply { writeBytes(FitWriter.encode(ride)) }
    }
}
