package app.rollentrainer.core

import app.rollentrainer.core.fit.FitWriter
import app.rollentrainer.core.model.Ride
import app.rollentrainer.core.model.RideSummary
import app.rollentrainer.core.model.Sample
import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.SessionMesg
import com.garmin.fit.Sport
import com.garmin.fit.SubSport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

class FitTest {
    @Test fun garminDecoderAcceptsOurFile() {
        val samples = (0 until 600).map {
            Sample(t = it, power = 200 + it % 20, target = 200, hr = 120 + it / 20, cadence = 90, speedKmh = 30.0)
        }
        val ride = Ride(
            id = "r", startEpochSec = 1_761_550_000, name = "Test", ftp = 250,
            samples = samples, summary = RideSummary.of(samples, 250),
        )
        val bytes = FitWriter.encode(ride)

        val decode = Decode()
        assertTrue(decode.checkFileIntegrity(ByteArrayInputStream(bytes)))

        val records = mutableListOf<RecordMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(com.garmin.fit.RecordMesgListener { records += it })
        broadcaster.addListener(com.garmin.fit.SessionMesgListener { sessions += it })
        broadcaster.run(ByteArrayInputStream(bytes))

        assertEquals(600, records.size)
        assertEquals(219, records[19].power.toInt())
        assertEquals(90, records[0].cadence.toInt())
        assertEquals(1, sessions.size)
        val s = sessions[0]
        assertEquals(Sport.CYCLING, s.sport)
        assertEquals(SubSport.INDOOR_CYCLING, s.subSport)
        assertEquals(600f, s.totalTimerTime, 0.01f)
        assertEquals(ride.summary.avgPower, s.avgPower.toInt())
        assertEquals(5000f, s.totalDistance, 1f)
        assertEquals(ride.startEpochSec - FitWriter.FIT_EPOCH_OFFSET, s.startTime.timestamp)
    }

    @Test fun virtualRouteRideCarriesAltitudeAndAscent() {
        // 300 s climbing 1 m per second, then 100 s down 0.5 m per second.
        val samples = (0 until 400).map {
            val alt = if (it < 300) 700.0 + it else 999.0 - (it - 299) * 0.5
            Sample(t = it, power = 250, cadence = 85, speedKmh = 12.0, altitudeM = alt)
        }
        val ride = Ride(
            id = "v", startEpochSec = 1_761_550_000, name = "Berg", ftp = 250,
            samples = samples, summary = RideSummary.of(samples, 250),
        )
        val bytes = FitWriter.encode(ride)
        assertTrue(Decode().checkFileIntegrity(ByteArrayInputStream(bytes)))
        val records = mutableListOf<RecordMesg>()
        val sessions = mutableListOf<SessionMesg>()
        val broadcaster = MesgBroadcaster(Decode())
        broadcaster.addListener(com.garmin.fit.RecordMesgListener { records += it })
        broadcaster.addListener(com.garmin.fit.SessionMesgListener { sessions += it })
        broadcaster.run(ByteArrayInputStream(bytes))

        assertEquals(700f, records[0].altitude, 0.2f)
        assertEquals(999f, records[299].altitude, 0.2f)
        val s = sessions.single()
        assertEquals(SubSport.VIRTUAL_ACTIVITY, s.subSport)
        assertEquals(299, s.totalAscent.toInt())
        assertEquals(50, s.totalDescent.toInt())
    }
}
