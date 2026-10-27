package app.rollentrainer.core.ble

import java.util.UUID

/** Bluetooth SIG UUIDs for the services the app speaks. */
object Uuids {
    private fun sig(short: Int): UUID =
        UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", short))

    val CCCD: UUID = sig(0x2902)

    // Fitness Machine Service
    val FTMS: UUID = sig(0x1826)
    val FTMS_FEATURE: UUID = sig(0x2ACC)
    val INDOOR_BIKE_DATA: UUID = sig(0x2AD2)
    val SUPPORTED_POWER_RANGE: UUID = sig(0x2AD8)
    val FTMS_CONTROL_POINT: UUID = sig(0x2AD9)
    val FTMS_STATUS: UUID = sig(0x2ADA)

    // Heart Rate
    val HEART_RATE: UUID = sig(0x180D)
    val HEART_RATE_MEASUREMENT: UUID = sig(0x2A37)

    // Cycling Speed and Cadence
    val CSC: UUID = sig(0x1816)
    val CSC_MEASUREMENT: UUID = sig(0x2A5B)

    // Cycling Power
    val CYCLING_POWER: UUID = sig(0x1818)
    val CYCLING_POWER_MEASUREMENT: UUID = sig(0x2A63)

    // Battery
    val BATTERY: UUID = sig(0x180F)
    val BATTERY_LEVEL: UUID = sig(0x2A19)
}

/** Little-endian reader over a GATT value. */
class ByteReader(private val b: ByteArray) {
    var pos = 0
        private set

    fun remaining() = b.size - pos
    fun has(n: Int) = remaining() >= n

    fun u8(): Int = b[pos++].toInt() and 0xFF
    fun s8(): Int = b[pos++].toInt()
    fun u16(): Int = u8() or (u8() shl 8)
    fun s16(): Int = u16().toShort().toInt()
    fun u24(): Int = u8() or (u8() shl 8) or (u8() shl 16)
    fun u32(): Long = (u16().toLong()) or (u16().toLong() shl 16)
    fun skip(n: Int) { pos += n }
}

/** Values from the FTMS Indoor Bike Data characteristic. Null means the trainer did not send it. */
data class IndoorBikeData(
    val speedKmh: Double? = null,
    val cadenceRpm: Double? = null,
    val distanceM: Int? = null,
    val resistance: Int? = null,
    val powerW: Int? = null,
    val heartRate: Int? = null,
    val elapsedSec: Int? = null,
)

object Ftms {
    // Control point op codes (FTMS 1.0, section 4.16)
    const val OP_REQUEST_CONTROL = 0x00
    const val OP_RESET = 0x01
    const val OP_SET_TARGET_RESISTANCE = 0x04
    const val OP_SET_TARGET_POWER = 0x05
    const val OP_START_RESUME = 0x07
    const val OP_STOP_PAUSE = 0x08
    const val OP_SET_SIMULATION = 0x11
    const val OP_RESPONSE = 0x80

    const val RESULT_SUCCESS = 0x01

    fun requestControl() = byteArrayOf(OP_REQUEST_CONTROL.toByte())
    fun startOrResume() = byteArrayOf(OP_START_RESUME.toByte())

    /** Stop (1) or pause (2). */
    fun stop(pause: Boolean) = byteArrayOf(OP_STOP_PAUSE.toByte(), (if (pause) 2 else 1).toByte())

    /** Resistance level 0..100 in simulation-free "level" mode (unit 0.1). */
    fun setTargetResistance(level: Double): ByteArray {
        val v = (level * 10).toInt().coerceIn(0, 255)
        return byteArrayOf(OP_SET_TARGET_RESISTANCE.toByte(), v.toByte())
    }

    /**
     * Indoor bike simulation: wind speed (m/s), grade (%), rolling resistance and wind resistance
     * coefficients. Trainers switch from ERG to a "flat road" with this command.
     */
    fun setSimulation(gradePct: Double, windMps: Double = 0.0, crr: Double = 0.004, cw: Double = 0.51): ByteArray {
        val wind = (windMps * 1000).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val grade = (gradePct * 100).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        val c = (crr * 10_000).toInt().coerceIn(0, 255)
        val w = (cw * 100).toInt().coerceIn(0, 255)
        return byteArrayOf(
            OP_SET_SIMULATION.toByte(),
            (wind and 0xFF).toByte(), (wind shr 8 and 0xFF).toByte(),
            (grade and 0xFF).toByte(), (grade shr 8 and 0xFF).toByte(),
            c.toByte(), w.toByte(),
        )
    }

    fun setTargetPower(watts: Int): ByteArray {
        val w = watts.coerceIn(0, Short.MAX_VALUE.toInt())
        return byteArrayOf(OP_SET_TARGET_POWER.toByte(), (w and 0xFF).toByte(), (w shr 8 and 0xFF).toByte())
    }

    /** Parses Indoor Bike Data (0x2AD2). Fields appear in flag-bit order. */
    fun parseIndoorBikeData(value: ByteArray): IndoorBikeData? {
        if (value.size < 2) return null
        val r = ByteReader(value)
        val flags = r.u16()
        fun bit(n: Int) = flags and (1 shl n) != 0
        var speed: Double? = null
        var cadence: Double? = null
        var distance: Int? = null
        var resistance: Int? = null
        var power: Int? = null
        var hr: Int? = null
        var elapsed: Int? = null
        try {
            // Bit 0 is "More Data": when it is 0 the instantaneous speed is present.
            if (!bit(0)) speed = r.u16() / 100.0
            if (bit(1)) r.skip(2) // average speed
            if (bit(2)) cadence = r.u16() / 2.0
            if (bit(3)) r.skip(2) // average cadence
            if (bit(4)) distance = r.u24()
            if (bit(5)) resistance = r.s16()
            if (bit(6)) power = r.s16()
            if (bit(7)) r.skip(2) // average power
            if (bit(8)) r.skip(5) // energy: total, per hour, per minute
            if (bit(9)) hr = r.u8()
            if (bit(10)) r.skip(1) // metabolic equivalent
            if (bit(11)) elapsed = r.u16()
        } catch (_: IndexOutOfBoundsException) {
            // Truncated packet: keep what was read so far.
        }
        return IndoorBikeData(speed, cadence, distance, resistance, power, hr?.takeIf { it > 0 }, elapsed)
    }

    /** Result of a control point indication: request op code and result code. */
    fun parseControlResponse(value: ByteArray): Pair<Int, Int>? {
        if (value.size < 3 || value[0].toInt() and 0xFF != OP_RESPONSE) return null
        return (value[1].toInt() and 0xFF) to (value[2].toInt() and 0xFF)
    }

    /** Supported Power Range (0x2AD8): min, max, increment in watts. */
    fun parsePowerRange(value: ByteArray): IntRange? {
        if (value.size < 4) return null
        val r = ByteReader(value)
        return r.s16()..r.s16()
    }
}

object HeartRate {
    /** Parses Heart Rate Measurement (0x2A37) and returns beats per minute. */
    fun parse(value: ByteArray): Int? {
        if (value.size < 2) return null
        val r = ByteReader(value)
        val flags = r.u8()
        return if (flags and 0x01 != 0) {
            if (value.size < 3) null else r.u16()
        } else r.u8()
    }
}

/**
 * Turns cumulative crank revolutions plus event times (1/1024 s) into a cadence.
 * Used for both CSC sensors and power meters, which report crank data the same way.
 */
class CrankCadence(private val timeoutMs: Long = 3000) {
    private var lastRevs = -1
    private var lastEvent = -1
    private var lastChangeAt = 0L
    private var cadence = 0.0

    /** Feeds one measurement and returns the current cadence in rpm. */
    fun update(revs: Int, eventTime1024: Int, nowMs: Long): Double {
        if (lastRevs >= 0) {
            val dRevs = (revs - lastRevs) and 0xFFFF
            val dTime = (eventTime1024 - lastEvent) and 0xFFFF
            if (dRevs > 0 && dTime > 0) {
                val rpm = dRevs * 60.0 * 1024.0 / dTime
                // Ignore spikes from a missed or duplicated event.
                if (rpm < 250) cadence = rpm
                lastChangeAt = nowMs
            } else if (nowMs - lastChangeAt > timeoutMs) {
                cadence = 0.0
            }
        } else {
            lastChangeAt = nowMs
        }
        lastRevs = revs
        lastEvent = eventTime1024
        return cadence
    }

    fun current(nowMs: Long): Double = if (nowMs - lastChangeAt > timeoutMs) 0.0 else cadence
}

object Csc {
    /** Returns cumulative crank revolutions and last crank event time if the packet has them. */
    fun parseCrank(value: ByteArray): Pair<Int, Int>? {
        if (value.isEmpty()) return null
        val r = ByteReader(value)
        val flags = r.u8()
        if (flags and 0x01 != 0) {
            if (!r.has(6)) return null
            r.skip(6) // wheel revolutions (u32) + wheel event time (u16)
        }
        if (flags and 0x02 == 0 || !r.has(4)) return null
        return r.u16() to r.u16()
    }
}

data class PowerMeasurement(val powerW: Int, val crank: Pair<Int, Int>?)

object CyclingPower {
    /** Parses Cycling Power Measurement (0x2A63). */
    fun parse(value: ByteArray): PowerMeasurement? {
        if (value.size < 4) return null
        val r = ByteReader(value)
        val flags = r.u16()
        val power = r.s16()
        fun bit(n: Int) = flags and (1 shl n) != 0
        var crank: Pair<Int, Int>? = null
        try {
            if (bit(0)) r.skip(1) // pedal power balance
            if (bit(2)) r.skip(2) // accumulated torque
            if (bit(4)) r.skip(6) // wheel revolution data
            if (bit(5)) crank = r.u16() to r.u16()
        } catch (_: IndexOutOfBoundsException) {
        }
        return PowerMeasurement(power.coerceAtLeast(0), crank)
    }
}
