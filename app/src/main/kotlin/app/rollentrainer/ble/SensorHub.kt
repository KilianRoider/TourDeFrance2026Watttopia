package app.rollentrainer.ble

import android.content.Context
import android.os.SystemClock
import android.util.Log
import app.rollentrainer.core.ble.CrankCadence
import app.rollentrainer.core.ble.Csc
import app.rollentrainer.core.ble.CyclingPower
import app.rollentrainer.core.ble.Ftms
import app.rollentrainer.core.ble.HeartRate
import app.rollentrainer.core.ble.Uuids
import app.rollentrainer.data.SavedSensor
import app.rollentrainer.data.SensorRole
import app.rollentrainer.data.SettingsStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.math.roundToInt
import kotlin.math.sin

/** A reading with the time it arrived, so stale values can be ignored. */
data class Reading<T>(val value: T, val atMs: Long) {
    fun fresh(nowMs: Long, maxAgeMs: Long = 4000) = nowMs - atMs <= maxAgeMs
}

data class RawReadings(
    val trainerPower: Reading<Int>? = null,
    val trainerCadence: Reading<Double>? = null,
    val trainerSpeed: Reading<Double>? = null,
    val trainerHr: Reading<Int>? = null,
    val hr: Reading<Int>? = null,
    val sensorCadence: Reading<Double>? = null,
    val meterPower: Reading<Int>? = null,
    val meterCadence: Reading<Double>? = null,
)

/** What the app shows and records: one value per metric, picked from the best source. */
data class LiveMetrics(
    val power: Int? = null,
    val cadence: Int? = null,
    val heartRate: Int? = null,
    val speedKmh: Double? = null,
    /** Raw sources, for PowerMatch: what the trainer and the power meter report right now. */
    val trainerPower: Int? = null,
    val meterPower: Int? = null,
)

data class SensorStatus(
    val sensor: SavedSensor?,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val battery: Int? = null,
)

/** Owns the connections to all paired sensors and merges their data. */
class SensorHub(
    private val context: Context,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val clients = mutableMapOf<SensorRole, GattClient>()
    private val stateJobs = mutableMapOf<SensorRole, Job>()

    private val _raw = MutableStateFlow(RawReadings())
    val raw: StateFlow<RawReadings> = _raw.asStateFlow()

    private val _status = MutableStateFlow(SensorRole.entries.associateWith { SensorStatus(null) })
    val status: StateFlow<Map<SensorRole, SensorStatus>> = _status.asStateFlow()

    /** True once the trainer granted control, so ERG targets will be applied. */
    private val _trainerControl = MutableStateFlow(false)
    val trainerControl: StateFlow<Boolean> = _trainerControl.asStateFlow()

    private val _trainerRange = MutableStateFlow<IntRange?>(null)
    val trainerRange: StateFlow<IntRange?> = _trainerRange.asStateFlow()

    private val sensorCadence = CrankCadence()
    private val meterCadence = CrankCadence()
    private var lastTarget: Int? = null
    private var lastTargetSentAt = 0L
    private var simulation = false
    private var lastGrade: Double? = null
    private var lastGradeSentAt = 0L
    private var lastControlRequestAt = 0L
    /** The simulated sensors of the screenshot demo, see [startDemo]; null in normal use. */
    private var demo: Job? = null

    /** FTMS is a procedure protocol: one control-point command at a time, each answered by an indication. */
    private val cpLock = Mutex()
    private var cpResponse: CompletableDeferred<Int>? = null

    private fun now() = SystemClock.elapsedRealtime()

    /** Connects every saved sensor. Safe to call repeatedly. */
    fun connectSaved() {
        settings.value.sensors.forEach { (role, sensor) -> connect(role, sensor) }
    }

    fun connect(role: SensorRole, sensor: SavedSensor) {
        if (demo != null) return
        val existing = clients[role]
        if (existing != null && existing.address == sensor.address) {
            existing.connect()
            return
        }
        existing?.disconnect()
        val client = GattClient(context, sensor.address, scope, onReady = { setup(role) }) { uuid, value ->
            handle(role, uuid, value)
        }
        clients[role] = client
        stateJobs[role]?.cancel()
        stateJobs[role] = scope.launch {
            client.state.collect { s ->
                _status.update { it + (role to (it[role] ?: SensorStatus(sensor)).copy(sensor = sensor, state = s)) }
                if (role == SensorRole.TRAINER && s != ConnectionState.CONNECTED) _trainerControl.value = false
            }
        }
        client.connect()
    }

    /** Saves a sensor for a role and connects it. */
    fun pair(role: SensorRole, sensor: SavedSensor) {
        settings.update { it.copy(sensors = it.sensors + (role to sensor)) }
        connect(role, sensor)
    }

    fun forget(role: SensorRole) {
        clients.remove(role)?.disconnect()
        stateJobs.remove(role)?.cancel()
        settings.update { it.copy(sensors = it.sensors - role) }
        _status.update { it + (role to SensorStatus(null)) }
        if (role == SensorRole.TRAINER) _trainerControl.value = false
    }

    fun disconnectAll() {
        clients.values.forEach { it.disconnect() }
    }

    /**
     * Demo mode only (CI screenshots on an emulator, which has no trainer): a simulated smart trainer and
     * heart-rate strap report as connected and feed the readings a real one would, four times a second.
     * ERG targets are followed; in simulation mode the rider holds about [baseWatts]. Real Bluetooth stays off.
     */
    fun startDemo(baseWatts: Int) {
        if (demo != null) return
        val trainer = SavedSensor("00:00:00:00:00:01", "KICKR CORE 2 (Demo)")
        val strap = SavedSensor("00:00:00:00:00:02", "HRM-Pro (Demo)")
        _status.update {
            it + (SensorRole.TRAINER to SensorStatus(trainer, ConnectionState.CONNECTED, 92)) +
                (SensorRole.HEART_RATE to SensorStatus(strap, ConnectionState.CONNECTED, 78))
        }
        _trainerControl.value = true
        _trainerRange.value = 0..2000
        val rnd = java.util.Random(26)
        fun emit() {
            val t = now()
            val sec = t / 1000.0
            val base = (if (simulation) null else lastTarget) ?: baseWatts
            val watts = (base + 10 * sin(sec / 6) + rnd.nextGaussian() * 6).roundToInt().coerceAtLeast(0)
            val rpm = 88 + 3 * sin(sec / 11) + rnd.nextGaussian()
            val hr = (140 + 4 * sin(sec / 37) + rnd.nextGaussian()).roundToInt()
            val kmh = 31 + 1.5 * sin(sec / 9)
            _raw.update {
                it.copy(
                    trainerPower = Reading(watts, t), trainerCadence = Reading(rpm, t),
                    trainerSpeed = Reading(kmh, t), hr = Reading(hr, t),
                )
            }
        }
        // The first reading at once, so a ride started right after this sees a pedalling rider.
        emit()
        demo = scope.launch {
            while (true) {
                delay(250)
                emit()
            }
        }
    }

    private suspend fun GattClient.setup(role: SensorRole) {
        when (role) {
            SensorRole.TRAINER -> {
                read(Uuids.FTMS, Uuids.SUPPORTED_POWER_RANGE)?.let { _trainerRange.value = Ftms.parsePowerRange(it) }
                subscribe(Uuids.FTMS, Uuids.INDOOR_BIKE_DATA)
                subscribe(Uuids.FTMS, Uuids.FTMS_STATUS)
                if (subscribe(Uuids.FTMS, Uuids.FTMS_CONTROL_POINT, indicate = true)) {
                    lastControlRequestAt = now()
                    control(Ftms.requestControl())
                    // The response arrives as an indication and sets trainerControl; ensureControl() retries if it does not.
                } else {
                    Log.w(TAG, "Trainer has no FTMS control point")
                }
            }
            SensorRole.HEART_RATE -> subscribe(Uuids.HEART_RATE, Uuids.HEART_RATE_MEASUREMENT)
            SensorRole.CADENCE -> {
                if (!subscribe(Uuids.CSC, Uuids.CSC_MEASUREMENT)) {
                    subscribe(Uuids.CYCLING_POWER, Uuids.CYCLING_POWER_MEASUREMENT)
                }
            }
            SensorRole.POWER -> subscribe(Uuids.CYCLING_POWER, Uuids.CYCLING_POWER_MEASUREMENT)
        }
        read(Uuids.BATTERY, Uuids.BATTERY_LEVEL)?.firstOrNull()?.let { b ->
            _status.update { it + (role to (it[role] ?: SensorStatus(null)).copy(battery = b.toInt() and 0xFF)) }
        }
    }

    private fun handle(role: SensorRole, uuid: UUID, value: ByteArray) {
        val t = now()
        when (uuid) {
            Uuids.INDOOR_BIKE_DATA -> Ftms.parseIndoorBikeData(value)?.let { d ->
                _raw.update { r ->
                    r.copy(
                        trainerPower = d.powerW?.let { Reading(it.coerceAtLeast(0), t) } ?: r.trainerPower,
                        trainerCadence = d.cadenceRpm?.let { Reading(it, t) } ?: r.trainerCadence,
                        trainerSpeed = d.speedKmh?.let { Reading(it, t) } ?: r.trainerSpeed,
                        trainerHr = d.heartRate?.let { Reading(it, t) } ?: r.trainerHr,
                    )
                }
            }
            Uuids.FTMS_CONTROL_POINT -> Ftms.parseControlResponse(value)?.let { (op, result) ->
                cpResponse?.complete(result)
                when {
                    op == Ftms.OP_REQUEST_CONTROL -> {
                        _trainerControl.value = result == Ftms.RESULT_SUCCESS
                        if (result == Ftms.RESULT_SUCCESS) scope.launch { resendAfterControl() }
                    }
                    // Any command answered with "control not permitted" means we lost control without a status event.
                    result == Ftms.RESULT_CONTROL_NOT_PERMITTED -> {
                        _trainerControl.value = false
                        lastTargetSentAt = 0L
                        lastGradeSentAt = 0L
                        lastControlRequestAt = 0L
                        ensureControl()
                    }
                    result != Ftms.RESULT_SUCCESS -> {
                        Log.w(TAG, "FTMS op 0x%02x failed with 0x%02x".format(op, result))
                        // The next tick repeats the target or grade instead of waiting for the periodic resend.
                        lastTargetSentAt = 0L
                        lastGradeSentAt = 0L
                    }
                }
            }
            Uuids.FTMS_STATUS -> {
                // 0xFF = control permission lost (e.g. another app took over): ask again.
                if (value.firstOrNull()?.toInt()?.and(0xFF) == 0xFF) {
                    _trainerControl.value = false
                    lastControlRequestAt = 0L
                    ensureControl()
                }
            }
            Uuids.HEART_RATE_MEASUREMENT -> HeartRate.parse(value)?.takeIf { it > 0 }?.let { hr ->
                _raw.update { it.copy(hr = Reading(hr, t)) }
            }
            Uuids.CSC_MEASUREMENT -> Csc.parseCrank(value)?.let { (revs, time) ->
                val rpm = sensorCadence.update(revs, time, t)
                _raw.update { it.copy(sensorCadence = Reading(rpm, t)) }
            }
            Uuids.CYCLING_POWER_MEASUREMENT -> CyclingPower.parse(value)?.let { m ->
                val rpm = m.crank?.let { (revs, time) -> meterCadence.update(revs, time, t) }
                _raw.update { r ->
                    if (role == SensorRole.CADENCE) {
                        r.copy(sensorCadence = rpm?.let { Reading(it, t) } ?: r.sensorCadence)
                    } else {
                        r.copy(meterPower = Reading(m.powerW, t), meterCadence = rpm?.let { Reading(it, t) } ?: r.meterCadence)
                    }
                }
            }
        }
    }

    /** Merged values: dedicated sensors win over the trainer's own estimates. */
    fun current(): LiveMetrics {
        val r = _raw.value
        val t = now()
        // PowerMatch regulates the trainer so that the meter hits the target, so the meter must be what is shown.
        val cfg = settings.value
        val usePm = (cfg.preferPowerMeter || cfg.powerMatch) && r.meterPower?.fresh(t) == true
        val power = if (usePm) r.meterPower?.value else r.trainerPower?.takeIf { it.fresh(t) }?.value
            ?: r.meterPower?.takeIf { it.fresh(t) }?.value
        val cadence = r.sensorCadence?.takeIf { it.fresh(t) }?.value
            ?: r.meterCadence?.takeIf { it.fresh(t) }?.value
            ?: r.trainerCadence?.takeIf { it.fresh(t) }?.value
        val hr = r.hr?.takeIf { it.fresh(t, 6000) }?.value ?: r.trainerHr?.takeIf { it.fresh(t, 6000) }?.value
        val speed = r.trainerSpeed?.takeIf { it.fresh(t) }?.value
        return LiveMetrics(
            power, cadence?.let { Math.round(it).toInt() }, hr, speed,
            trainerPower = r.trainerPower?.takeIf { it.fresh(t) }?.value,
            meterPower = r.meterPower?.takeIf { it.fresh(t) }?.value,
        )
    }

    /** Sends an ERG target when it changed, at most twice per second. */
    fun setTargetPower(watts: Int) {
        val range = _trainerRange.value
        val w = if (range != null && range.first < range.last) watts.coerceIn(range.first, range.last) else watts
        val t = now()
        val modeSwitch = simulation
        simulation = false
        if (!modeSwitch && w == lastTarget && t - lastTargetSentAt < RESEND_MS) return
        if (!modeSwitch && t - lastTargetSentAt < 500 && w != lastTarget) {
            // Too soon after the last command; the next tick sends it.
            return
        }
        lastTarget = w
        lastTargetSentAt = t
        if (!_trainerControl.value) { ensureControl(); return }
        scope.launch {
            // A refused write is retried on the next tick instead of silently holding the old target for 10 s.
            if (control(Ftms.setTargetPower(w)) == CP_WRITE_FAILED) lastTargetSentAt = 0L
        }
    }

    /**
     * Free ride without ERG: the trainer simulates a road with [gradePct]. Zwift calls this
     * "ERG off"; the rider chooses the effort with gears and cadence.
     */
    fun setSimulation(gradePct: Double) {
        val t = now()
        val modeSwitch = !simulation
        simulation = true
        if (!modeSwitch && gradePct == lastGrade && t - lastGradeSentAt < RESEND_MS) return
        if (!modeSwitch && t - lastGradeSentAt < 500) return
        lastGrade = gradePct
        lastGradeSentAt = t
        if (!_trainerControl.value) { ensureControl(); return }
        scope.launch {
            if (control(Ftms.setSimulation(gradePct)) == CP_WRITE_FAILED) lastGradeSentAt = 0L
        }
    }

    /**
     * Releases the trainer at the end of a ride: a flat road in simulation mode, so coasting is free and
     * pedalling feels like riding on the flat. (A 0 W ERG target is outside some trainers' supported range
     * and would be rejected, leaving the last interval's resistance active.)
     */
    fun releaseTrainer() {
        lastTarget = null
        simulation = true
        lastGrade = 0.0
        lastGradeSentAt = now()
        scope.launch { control(Ftms.setSimulation(0.0)) }
    }

    /**
     * Writes one control-point command and waits for the trainer's response indication, so that commands
     * never overlap (a second write during a running procedure is refused with "procedure already in
     * progress"). Returns the result code, null when no response came in time, or [CP_WRITE_FAILED].
     */
    private suspend fun control(cmd: ByteArray): Int? = cpLock.withLock {
        // The simulated trainer of the demo accepts every command.
        if (demo != null) return@withLock Ftms.RESULT_SUCCESS
        val client = clients[SensorRole.TRAINER] ?: return@withLock CP_WRITE_FAILED
        val response = CompletableDeferred<Int>()
        cpResponse = response
        val written = client.write(Uuids.FTMS, Uuids.FTMS_CONTROL_POINT, cmd)
        val result = if (written) withTimeoutOrNull(CP_RESPONSE_TIMEOUT_MS) { response.await() } else CP_WRITE_FAILED
        cpResponse = null
        delay(CP_GAP_MS) // some firmware is slow to clear the procedure
        result
    }

    /** After control was granted (or regained) the trainer starts from scratch: start it and repeat the current command. */
    private suspend fun resendAfterControl() {
        control(Ftms.startOrResume())
        if (simulation) lastGrade?.let { control(Ftms.setSimulation(it)) }
        else lastTarget?.let { control(Ftms.setTargetPower(it)) }
    }

    /** Asks for control again every few seconds while the trainer is connected but has not granted it. */
    private fun ensureControl() {
        val t = now()
        if (_trainerControl.value || t - lastControlRequestAt < CONTROL_RETRY_MS) return
        if (_status.value[SensorRole.TRAINER]?.state != ConnectionState.CONNECTED) return
        lastControlRequestAt = t
        scope.launch { control(Ftms.requestControl()) }
    }

    companion object {
        private const val TAG = "SensorHub"
        /** Some trainers drop a target after a reconnect; repeating it now and then is cheap. */
        private const val RESEND_MS = 10_000L
        private const val CONTROL_RETRY_MS = 5_000L
        private const val CP_RESPONSE_TIMEOUT_MS = 1_500L
        private const val CP_GAP_MS = 80L
        private const val CP_WRITE_FAILED = -1
    }
}
