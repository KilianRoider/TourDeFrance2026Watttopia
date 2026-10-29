package app.rollentrainer.debug

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import app.rollentrainer.AppContainer
import app.rollentrainer.core.metrics.Analysis
import app.rollentrainer.core.metrics.LapBoundary
import app.rollentrainer.core.model.Presets
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.RideSummary
import app.rollentrainer.core.model.Sample
import app.rollentrainer.core.model.SpinningPresets
import app.rollentrainer.core.model.TestKind
import app.rollentrainer.core.model.Timeline
import app.rollentrainer.core.model.Workout
import app.rollentrainer.core.race.ProRider
import app.rollentrainer.core.race.RaceEngine
import app.rollentrainer.core.race.Tour2026
import app.rollentrainer.core.route.Pacer
import app.rollentrainer.core.route.RiderParams
import app.rollentrainer.core.route.Route
import app.rollentrainer.core.route.RouteEngine
import app.rollentrainer.core.route.Routes
import app.rollentrainer.core.route.Stages
import app.rollentrainer.ride.BunchFollower
import app.rollentrainer.ride.RaceRecord
import app.rollentrainer.ui.Screen
import app.rollentrainer.ui.Tab
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.Normalizer
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Screenshot demo for CI (.github/workflows/screens.yml): nobody has a trainer on an emulator, so an explicit
 * intent extra opens one scene with a simulated trainer and heart-rate strap, for example
 *
 *     adb shell am start -n app.rollentrainer/.MainActivity --es wattwerk_demo route:alpe-dhuez:3:fp
 *
 * Nothing in the app's own UI leads here, and builds that are not debuggable ignore the extra. The app
 * should be force-stopped between scenes, since a running ride keeps the screen.
 *
 * Scenes:
 *  - `route:<routeId>:<km>[:fp][:pacer]` rides a climb or a stage (tour26-01 … tour26-21) from <km> on;
 *    `fp` = first-person camera (otherwise chase), `pacer` = a pacer 10 W stronger than the rider
 *  - `stage:<number>:<km>[:fp][:pacer]` the same, by stage number
 *  - `race:<number>:<km>[:<rider>][:fp][:finale=<km>]` pro mode on a stage: the race is ridden ahead to <km>
 *    before it is shown and the pro then rides in the bunch; <rider> is a bib or a name without accents,
 *    spaces as underscores (`race:12:60:pogacar`, `race:4:30:van_der_poel`, `race:12:5:1`), Pogačar by
 *    default; `finale=<km>` races only the stage's last <km> (<km> then counts within it). Riding ahead
 *    costs about 0.3 s of CPU per race kilometre on a desktop, so far points are best reached as a finale
 *    (`race:12:12:finale=15` = 3 km to go). The 3D world waits like a route scene's only once screens.yml
 *    lists `race:*` with `route:*|stage:*`; otherwise add `+wait=120`.
 *  - `spinning[:<workoutId>|:first]` a spinning class
 *  - `ride[:<workoutId>|:first][:<n>]` a workout ride with <n> segments skipped
 *  - `home`/`workouts`, `routes`, `routes-tour`, `history`, `history-detail`, `history-race` (a pro-mode
 *    ride's result), `settings`, `editor[:<workoutId>|:first]`; these also seed a few finished rides (one
 *    of them a pro-mode finale) so the history has content
 */
object DemoMode {
    const val EXTRA = "wattwerk_demo"
    /** What the simulated rider pushes when no ERG target is set. */
    const val WATTS = 240
    private const val TAG = "WattwerkDemo"
    private val SEED_IDS = (1..5).map { "demo-ride-$it" }
    private const val RACE_SEED_ID = "demo-ride-race"

    /** Set once a scene was opened in this process: from then on no permission prompts and no real Bluetooth. */
    @Volatile
    var active = false
        private set

    /** Where the UI goes once the scene started; both null when a ride takes over the screen. */
    data class Scene(val name: String, val tab: Tab? = null, val screen: Screen? = null)

    /** The scene [intent] asks for, or null in normal use. */
    fun sceneFrom(context: Context, intent: Intent?): String? {
        val scene = intent?.getStringExtra(EXTRA)?.trim()
        if (scene.isNullOrEmpty()) return null
        if ((context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0) return null
        // The user installs debug builds too, and the activity is exported: only an emulator may open
        // a scene, so no other app on a real device can start a fake ride or add demo rides.
        if (!isEmulator()) return null
        return scene
    }

    private fun isEmulator(): Boolean =
        Build.HARDWARE.contains("ranchu") || Build.HARDWARE.contains("goldfish") ||
            Build.PRODUCT.startsWith("sdk") || Build.FINGERPRINT.contains("generic") ||
            Build.FINGERPRINT.contains("emulator")

    /** Starts [scene]: the simulated sensors, and the ride or the screen it names. */
    fun start(container: AppContainer, scene: String): Scene {
        Log.i(TAG, "scene $scene")
        active = true
        container.sensors.startDemo(WATTS)
        val args = scene.split(':')
        val arg1 = args.getOrNull(1).orEmpty()
        val kind = args[0].lowercase()
        // Rides get the CPU to themselves (the 3D world is built while the scene runs); screens get a history.
        if (kind !in setOf("route", "stage", "race", "spinning", "ride")) seedHistory(container)
        return when (kind) {
            "route", "stage" -> {
                startRoute(container, args)
                Scene(scene)
            }
            "race" -> {
                startRace(container, args)
                Scene(scene)
            }
            "spinning" -> {
                val w = SpinningPresets.byId(arg1) ?: container.workouts.find(arg1) ?: SpinningPresets.all.first()
                container.session.start(w)
                container.session.setSpinningView(true)
                Scene(scene)
            }
            "ride" -> {
                container.session.start(workout(container, arg1))
                container.session.setSpinningView(false)
                repeat(args.getOrNull(2)?.toIntOrNull() ?: 0) { container.session.skip() }
                Scene(scene)
            }
            "home", "workouts" -> Scene(scene, Tab.WORKOUTS)
            "editor" -> {
                val w = workout(container, arg1)
                Scene(scene, Tab.WORKOUTS, Screen.Editor(w.id, copy = w.builtIn))
            }
            "routes", "routes-tour", "routes-climbs" -> Scene(scene, Tab.ROUTES)
            "history" -> Scene(scene, Tab.HISTORY)
            "history-detail" -> Scene(scene, Tab.HISTORY, Screen.RideDetail(SEED_IDS.first()))
            "history-race" -> Scene(scene, Tab.HISTORY, Screen.RideDetail(RACE_SEED_ID))
            "settings" -> Scene(scene, Tab.SETTINGS)
            else -> {
                Log.w(TAG, "unknown scene $scene")
                Scene(scene, Tab.WORKOUTS)
            }
        }
    }

    /** The workout named by [id]; "first" or nothing means the first built-in that is not a test. */
    private fun workout(container: AppContainer, id: String): Workout =
        (if (id.isEmpty() || id == "first") null else container.workouts.find(id))
            ?: Presets.all.firstOrNull { it.test == TestKind.NONE }
            ?: Presets.all.first()

    private fun startRoute(container: AppContainer, args: List<String>) {
        val ref = args.getOrNull(1).orEmpty()
        val byNumber = ref.toIntOrNull()?.let { Stages.byNumber(it) }
        val route = (if (args[0].equals("stage", ignoreCase = true)) byNumber ?: Routes.byId(ref) else Routes.byId(ref) ?: byNumber)
            ?: Routes.all.first().also { Log.w(TAG, "unknown route $ref, riding ${it.id}") }
        val km = args.getOrNull(2)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val flags = args.drop(3).map { it.lowercase() }
        // The ride screen reads the camera once when it opens, so it is set before the ride starts.
        container.settings.update { it.copy(firstPersonView = "fp" in flags || "ego" in flags) }
        val pacers = if ("pacer" in flags) listOf(Pacer("Pacer", WATTS + 10)) else emptyList()
        container.session.startRoute(route, pacers)
        if (km > 0) container.session.demoAdvanceTo(km * 1000, WATTS)
    }

    private fun startRace(container: AppContainer, args: List<String>) {
        val ref = args.getOrNull(1).orEmpty()
        val route = ref.toIntOrNull()?.let { Stages.byNumber(it) } ?: Routes.byId(ref)?.takeIf { it.isStage }
            ?: Stages.all.first().also { Log.w(TAG, "unknown stage $ref, riding ${it.id}") }
        val km = args.getOrNull(2)?.replace(',', '.')?.toDoubleOrNull() ?: 0.0
        val rest = args.drop(3)
        val flags = rest.map { it.lowercase() }
        val finale = flags.firstOrNull { it.startsWith("finale=") }?.substringAfter('=')?.replace(',', '.')?.toDoubleOrNull()
        val who = rest.firstOrNull { it.lowercase() !in setOf("fp", "ego") && !it.lowercase().startsWith("finale=") }
        val rider = who?.let { proRider(it) } ?: Tour2026.roster.byBib(1) ?: Tour2026.roster.riders.first()
        container.settings.update { it.copy(firstPersonView = "fp" in flags || "ego" in flags, proMode = true, proRiderBib = rider.bib) }
        val ridden = if (finale != null && finale > 0) route.finale(finale) else route
        Log.i(TAG, "race ${ridden.displayName} from km $km as ${rider.name}")
        container.session.demoStartRace(ridden, rider.id, km * 1000)
    }

    /** A rider by bib or by name; accents and case do not matter ("pogacar", "van_der_poel"). */
    private fun proRider(ref: String): ProRider? {
        val roster = Tour2026.roster
        ref.toIntOrNull()?.let { return roster.byBib(it) }
        fun key(s: String) = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()
        val k = key(ref.replace('_', ' '))
        return roster.riders.firstOrNull { key(it.name) == k } ?: roster.riders.firstOrNull { key(it.name).contains(k) }
            ?: null.also { Log.w(TAG, "unknown rider $ref") }
    }

    /** A few finished rides for the history, the records and the tour progress. Fixed ids: seeding again overwrites them. */
    private fun seedHistory(container: AppContainer) {
        container.scope.launch(Dispatchers.Default) {
            runCatching {
                val s = container.settings.value
                val now = System.currentTimeMillis() / 1000
                val day = 86_400L
                val rides = Presets.all.filter { it.test == TestKind.NONE }.take(3)
                    .mapIndexed { i, w -> workoutRide(SEED_IDS[i], w, s.ftp, now - (2 * i + 1) * day) }
                    .toMutableList()
                Routes.byId("alpe-dhuez")?.let { rides += routeRide(SEED_IDS[3], it, s.ftp, s.weightKg, now - 7 * day) }
                Stages.all.firstOrNull()?.let { rides += routeRide(SEED_IDS[4], it.finale(30.0), s.ftp, s.weightKg, now - 9 * day) }
                rides.forEach { container.rides.save(it) }
                // The pro-mode ride takes a few seconds of race simulation, so it comes after the others.
                Stages.all.firstOrNull { it.stage?.type == "flat" }?.let { stage ->
                    val (ride, record) = raceRide(RACE_SEED_ID, stage.finale(15.0), s.ftp, now - 4 * day)
                    container.session.raceRecords.save(record)
                    container.rides.save(ride)
                }
            }.onFailure { Log.w(TAG, "could not seed the history", it) }
        }
    }

    /** A pro-mode finale as Pogačar, ridden in the bunch by the demo's follower, with its stored result. */
    private fun raceRide(id: String, route: Route, ftp: Int, start: Long): Pair<Ride, RaceRecord> {
        val rider = Tour2026.roster.byBib(1) ?: Tour2026.roster.riders.first()
        val race = RaceEngine(route, Tour2026.roster, rider.id, ftp, seed = 26)
        val follow = BunchFollower(rider, race.powerScale)
        val rnd = java.util.Random(id.hashCode().toLong())
        val samples = mutableListOf<Sample>()
        var st = race.state
        while (!st.finished && samples.size < 4 * 3600) {
            val p = (follow.powerW(st) + rnd.nextGaussian() * 6).roundToInt().coerceAtLeast(0)
            st = race.tick(1.0, p)
            samples += Sample(samples.size, power = p, hr = heartRate(p, samples.size, rnd), cadence = cadence(rnd), speedKmh = st.route.speedKmh, altitudeM = st.route.elevationM)
        }
        val record = RaceRecord.of(id, route, rider, ftp, st, race.result(complete = true), 26)
        val ride = ride(id, start, route.displayName, samples, ftp, emptyList()).copy(
            routeId = route.id,
            routeFraction = route.fraction,
            routeVariant = route.variant,
            routeTimeSec = st.route.finishTimeSec?.roundToInt(),
            notes = record.notesLine,
        )
        return ride to record
    }

    private fun workoutRide(id: String, w: Workout, ftp: Int, start: Long): Ride {
        val timeline = Timeline(w)
        val rnd = java.util.Random(id.hashCode().toLong())
        val samples = (0 until timeline.totalSec).map { t ->
            val target = timeline.targetWattsAt(t.toDouble(), ftp)
            val p = ((target ?: 150) + rnd.nextGaussian() * 8).roundToInt().coerceAtLeast(0)
            Sample(t, power = p, target = target, hr = heartRate(p, t, rnd), cadence = cadence(rnd), speedKmh = 12.0 + sqrt(p.toDouble()) * 1.2)
        }
        val laps = timeline.segments.mapIndexed { i, seg ->
            LapBoundary(seg.startSec, seg.label.ifBlank { "Abschnitt ${i + 1}" }, rated = seg.erg && !seg.isRamp)
        }
        return ride(id, start, w.name, samples, ftp, laps).copy(workoutId = w.id, test = w.test)
    }

    private fun routeRide(id: String, route: Route, ftp: Int, weightKg: Double, start: Long): Ride {
        val engine = RouteEngine(route, RiderParams.forRider(weightKg))
        val rnd = java.util.Random(id.hashCode().toLong())
        val samples = mutableListOf<Sample>()
        var rs = engine.state
        while (!rs.finished && samples.size < 8 * 3600) {
            val p = (WATTS + rnd.nextGaussian() * 15).roundToInt().coerceAtLeast(0)
            rs = engine.tick(1.0, p, paused = false)
            samples += Sample(samples.size, power = p, hr = heartRate(p, samples.size, rnd), cadence = cadence(rnd), speedKmh = rs.speedKmh, altitudeM = rs.elevationM)
        }
        return ride(id, start, route.displayName, samples, ftp, emptyList()).copy(
            routeId = route.id,
            routeFraction = route.fraction,
            routeVariant = route.variant,
            routeTimeSec = rs.finishTimeSec?.roundToInt(),
        )
    }

    private fun ride(id: String, start: Long, name: String, samples: List<Sample>, ftp: Int, laps: List<LapBoundary>) = Ride(
        id = id,
        startEpochSec = start,
        name = name,
        ftp = ftp,
        samples = samples,
        summary = RideSummary.of(samples, ftp),
        laps = Analysis.lapsOf(samples, laps),
        bests = Analysis.bests(samples),
        rpe = 6,
    )

    /** Rises with the power and drifts up over the first half hour, as a real pulse does. */
    private fun heartRate(power: Int, t: Int, rnd: java.util.Random) =
        (92 + power * 0.2 + min(t, 1800) / 120.0 + rnd.nextGaussian()).roundToInt()

    private fun cadence(rnd: java.util.Random) = (88 + rnd.nextGaussian() * 2).roundToInt()
}
