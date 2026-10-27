package app.rollentrainer.core.fit

import app.rollentrainer.core.model.Ride
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Minimal FIT activity encoder (file_id, event, record, lap, session, activity),
 * enough for Strava, Garmin Connect, intervals.icu and TrainingPeaks to import a ride.
 */
object FitWriter {
    /** Seconds between the Unix epoch and the FIT epoch (1989-12-31T00:00:00Z). */
    const val FIT_EPOCH_OFFSET = 631065600L

    private const val ENUM = 0x00
    private const val UINT8 = 0x02
    private const val UINT16 = 0x84
    private const val UINT32 = 0x86
    private const val UINT32Z = 0x8C

    private const val MSG_FILE_ID = 0
    private const val MSG_SESSION = 18
    private const val MSG_LAP = 19
    private const val MSG_RECORD = 20
    private const val MSG_EVENT = 21
    private const val MSG_ACTIVITY = 34

    private const val SPORT_CYCLING = 2
    private const val SUB_SPORT_INDOOR_CYCLING = 6
    private const val SUB_SPORT_VIRTUAL_ACTIVITY = 58

    private class Field(val num: Int, val size: Int, val type: Int)

    private class Encoder {
        val out = ByteArrayOutputStream()

        fun define(local: Int, global: Int, fields: List<Field>) {
            out.write(0x40 or local)
            out.write(0) // reserved
            out.write(0) // little endian
            u16(global)
            out.write(fields.size)
            for (f in fields) {
                out.write(f.num)
                out.write(f.size)
                out.write(f.type)
            }
        }

        fun header(local: Int) = out.write(local)
        fun u8(v: Int?) = out.write(v?.coerceIn(0, 254) ?: 0xFF)
        fun u16(v: Int?) {
            val x = v?.coerceIn(0, 0xFFFE) ?: 0xFFFF
            out.write(x and 0xFF)
            out.write(x shr 8 and 0xFF)
        }
        fun u32(v: Long?) {
            val x = v?.coerceIn(0, 0xFFFFFFFEL) ?: 0xFFFFFFFFL
            for (i in 0 until 4) out.write((x shr (8 * i) and 0xFF).toInt())
        }
        fun enum(v: Int) = out.write(v)
    }

    fun encode(ride: Ride): ByteArray {
        val e = Encoder()
        val start = ride.startEpochSec - FIT_EPOCH_OFFSET
        val samples = ride.samples
        val end = start + samples.size
        val s = ride.summary
        val elapsedMs = samples.size * 1000L
        // A ride on a virtual route carries its road height, so Strava and Garmin show the climb.
        val altitudes = samples.mapNotNull { it.altitudeM }
        val virtual = altitudes.isNotEmpty()
        val subSport = if (virtual) SUB_SPORT_VIRTUAL_ACTIVITY else SUB_SPORT_INDOOR_CYCLING
        var ascent = 0.0
        var descent = 0.0
        for (i in 1 until altitudes.size) {
            val d = altitudes[i] - altitudes[i - 1]
            if (d > 0) ascent += d else descent -= d
        }
        val totalAscent = if (virtual) ascent.roundToInt() else null
        val totalDescent = if (virtual) descent.roundToInt() else null

        // file_id
        e.define(0, MSG_FILE_ID, listOf(
            Field(0, 1, ENUM), Field(1, 2, UINT16), Field(2, 2, UINT16),
            Field(3, 4, UINT32Z), Field(4, 4, UINT32),
        ))
        e.header(0)
        e.enum(4) // activity file
        e.u16(255) // manufacturer: development
        e.u16(1)
        e.u32((ride.startEpochSec and 0x7FFFFFFF).coerceAtLeast(1))
        e.u32(start)

        // event: timer start / stop
        e.define(1, MSG_EVENT, listOf(Field(253, 4, UINT32), Field(0, 1, ENUM), Field(1, 1, ENUM)))
        e.header(1); e.u32(start); e.enum(0); e.enum(0)

        // records, one per second
        e.define(2, MSG_RECORD, listOf(
            Field(253, 4, UINT32), Field(7, 2, UINT16), Field(3, 1, UINT8),
            Field(4, 1, UINT8), Field(6, 2, UINT16), Field(5, 4, UINT32), Field(2, 2, UINT16),
        ))
        var distance = 0.0
        for ((i, smp) in samples.withIndex()) {
            val speedMs = (smp.speedKmh ?: 0.0) / 3.6
            distance += speedMs
            e.header(2)
            e.u32(start + i)
            e.u16(smp.power)
            e.u8(smp.hr)
            e.u8(smp.cadence)
            e.u16(smp.speedKmh?.let { (speedMs * 1000).roundToInt() })
            e.u32((distance * 100).roundToLong())
            // altitude: scale 5, offset 500 m
            e.u16(smp.altitudeM?.let { ((it + 500) * 5).roundToInt() })
        }

        e.header(1); e.u32(end); e.enum(0); e.enum(4) // stop all

        // lap (the whole ride as one lap)
        e.define(3, MSG_LAP, listOf(
            Field(253, 4, UINT32), Field(254, 2, UINT16), Field(0, 1, ENUM), Field(1, 1, ENUM),
            Field(2, 4, UINT32), Field(7, 4, UINT32), Field(8, 4, UINT32), Field(9, 4, UINT32),
            Field(19, 2, UINT16), Field(20, 2, UINT16), Field(15, 1, UINT8), Field(16, 1, UINT8),
            Field(17, 1, UINT8), Field(25, 1, ENUM), Field(39, 1, ENUM),
            Field(21, 2, UINT16), Field(22, 2, UINT16),
        ))
        e.header(3)
        e.u32(end); e.u16(0); e.enum(9); e.enum(1)
        e.u32(start); e.u32(elapsedMs); e.u32(elapsedMs); e.u32((s.distanceM * 100).roundToLong())
        e.u16(s.avgPower); e.u16(s.maxPower); e.u8(s.avgHr); e.u8(s.maxHr); e.u8(s.avgCadence)
        e.enum(SPORT_CYCLING); e.enum(subSport)
        e.u16(totalAscent); e.u16(totalDescent)

        // session
        e.define(4, MSG_SESSION, listOf(
            Field(253, 4, UINT32), Field(254, 2, UINT16), Field(0, 1, ENUM), Field(1, 1, ENUM),
            Field(2, 4, UINT32), Field(5, 1, ENUM), Field(6, 1, ENUM), Field(7, 4, UINT32),
            Field(8, 4, UINT32), Field(9, 4, UINT32), Field(11, 2, UINT16), Field(16, 1, UINT8),
            Field(17, 1, UINT8), Field(18, 1, UINT8), Field(19, 1, UINT8), Field(20, 2, UINT16),
            Field(21, 2, UINT16), Field(25, 2, UINT16), Field(26, 2, UINT16), Field(28, 1, ENUM),
            Field(34, 2, UINT16), Field(35, 2, UINT16), Field(36, 2, UINT16), Field(48, 4, UINT32),
            Field(22, 2, UINT16), Field(23, 2, UINT16),
        ))
        e.header(4)
        e.u32(end); e.u16(0); e.enum(8); e.enum(1) // event session, stop
        e.u32(start); e.enum(SPORT_CYCLING); e.enum(subSport)
        e.u32(elapsedMs); e.u32(elapsedMs); e.u32((s.distanceM * 100).roundToLong())
        e.u16(s.calories); e.u8(s.avgHr); e.u8(s.maxHr); e.u8(s.avgCadence); e.u8(s.maxCadence)
        e.u16(s.avgPower); e.u16(s.maxPower); e.u16(0); e.u16(1); e.enum(0)
        e.u16(s.normalizedPower); e.u16((s.tss * 10).roundToInt())
        e.u16((s.intensityFactor * 1000).roundToInt()); e.u32(s.workKj * 1000L)
        e.u16(totalAscent); e.u16(totalDescent)

        // activity
        e.define(5, MSG_ACTIVITY, listOf(
            Field(253, 4, UINT32), Field(0, 4, UINT32), Field(1, 2, UINT16),
            Field(2, 1, ENUM), Field(3, 1, ENUM), Field(4, 1, ENUM),
        ))
        e.header(5)
        e.u32(end); e.u32(elapsedMs); e.u16(1); e.enum(0); e.enum(26); e.enum(1)

        val data = e.out.toByteArray()
        val header = ByteArray(14)
        header[0] = 14
        header[1] = 0x10 // protocol 1.0
        header[2] = (2132 and 0xFF).toByte() // profile 21.32
        header[3] = (2132 shr 8).toByte()
        for (i in 0 until 4) header[4 + i] = (data.size shr (8 * i) and 0xFF).toByte()
        header[8] = '.'.code.toByte(); header[9] = 'F'.code.toByte()
        header[10] = 'I'.code.toByte(); header[11] = 'T'.code.toByte()
        val hcrc = crc(header, 0, 12)
        header[12] = (hcrc and 0xFF).toByte()
        header[13] = (hcrc shr 8 and 0xFF).toByte()

        val file = header + data
        val fcrc = crc(file, 0, file.size)
        return file + byteArrayOf((fcrc and 0xFF).toByte(), (fcrc shr 8 and 0xFF).toByte())
    }

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    fun crc(bytes: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val b = bytes[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[b and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(b shr 4) and 0xF]
        }
        return crc
    }
}
