package app.rollentrainer.ui.fields

import androidx.compose.ui.graphics.Color
import app.rollentrainer.core.metrics.HeartRateZone
import app.rollentrainer.core.metrics.PowerZone
import app.rollentrainer.data.AppSettings
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.theme.Metric
import app.rollentrainer.ui.theme.zoneColor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** What a field shows: the number, its unit, an optional second line and accent colour. */
data class FieldValue(
    val text: String,
    val unit: String = "",
    val sub: String? = null,
    val accent: Color? = null,
)

enum class FieldGroup(val title: String) {
    POWER("Leistung"),
    HEART("Herzfrequenz"),
    CADENCE("Trittfrequenz"),
    TIME("Zeit"),
    WORKOUT("Training"),
    OTHER("Sonstiges"),
}

/**
 * The catalog of data fields a rider can place on the ride screen, like the data pages of a
 * Garmin Edge. Keys are stored in settings, so they must never be renamed.
 */
enum class DataField(
    val key: String,
    val label: String,
    val group: FieldGroup,
    val description: String,
) {
    POWER("power", "Leistung", FieldGroup.POWER, "Aktuelle Leistung, geglättet wie in den Einstellungen gewählt"),
    POWER_3S("power3s", "Leistung 3 s", FieldGroup.POWER, "Über 3 Sekunden gemittelt"),
    POWER_10S("power10s", "Leistung 10 s", FieldGroup.POWER, "Über 10 Sekunden gemittelt"),
    POWER_30S("power30s", "Leistung 30 s", FieldGroup.POWER, "Über 30 Sekunden gemittelt"),
    TARGET("target", "Zielleistung", FieldGroup.POWER, "Vorgabe an den Trainer im aktuellen Abschnitt"),
    TARGET_DIFF("targetDiff", "Abweichung", FieldGroup.POWER, "Leistung minus Ziel"),
    POWER_PCT_FTP("powerPct", "% FTP", FieldGroup.POWER, "Aktuelle Leistung in Prozent deiner FTP"),
    POWER_ZONE("powerZone", "Leistungszone", FieldGroup.POWER, "Zone 1 bis 7 nach Coggan"),
    POWER_AVG("powerAvg", "Ø Leistung", FieldGroup.POWER, "Durchschnitt der ganzen Fahrt"),
    POWER_LAP("powerLap", "Ø Abschnitt", FieldGroup.POWER, "Durchschnitt im aktuellen Abschnitt"),
    POWER_MAX("powerMax", "Max. Leistung", FieldGroup.POWER, "Höchster Wert der Fahrt"),
    NP("np", "Normalized Power", FieldGroup.POWER, "Gewichtete Durchschnittsleistung"),
    IF("if", "Intensität (IF)", FieldGroup.POWER, "Normalized Power geteilt durch FTP"),
    TSS("tss", "TSS", FieldGroup.POWER, "Training Stress Score bis jetzt"),
    WATTS_PER_KG("wkg", "W/kg", FieldGroup.POWER, "Leistung pro Kilogramm Körpergewicht"),
    WORK("work", "Arbeit", FieldGroup.POWER, "Geleistete Arbeit in Kilojoule"),
    ACCURACY("accuracy", "Trefferquote", FieldGroup.POWER, "Wie genau du im Abschnitt am Ziel liegst"),

    HR("hr", "Herzfrequenz", FieldGroup.HEART, "Aktueller Puls"),
    HR_ZONE("hrZone", "Pulszone", FieldGroup.HEART, "Zone 1 bis 5 nach maximaler Herzfrequenz"),
    HR_PCT("hrPct", "% HFmax", FieldGroup.HEART, "Puls in Prozent der maximalen Herzfrequenz"),
    HR_AVG("hrAvg", "Ø Puls", FieldGroup.HEART, "Durchschnitt der ganzen Fahrt"),
    HR_LAP("hrLap", "Ø Puls Abschnitt", FieldGroup.HEART, "Durchschnitt im aktuellen Abschnitt"),
    HR_MAX("hrMax", "Max. Puls", FieldGroup.HEART, "Höchster Wert der Fahrt"),

    CADENCE("cadence", "Trittfrequenz", FieldGroup.CADENCE, "Aktuelle Umdrehungen pro Minute"),
    CADENCE_AVG("cadenceAvg", "Ø Trittfrequenz", FieldGroup.CADENCE, "Durchschnitt ohne Rollphasen"),
    CADENCE_LAP("cadenceLap", "Ø Trittfr. Abschnitt", FieldGroup.CADENCE, "Durchschnitt im aktuellen Abschnitt"),
    CADENCE_TARGET("cadenceTarget", "Ziel-Trittfrequenz", FieldGroup.CADENCE, "Vorgabe aus dem Trainingsblock, falls gesetzt"),

    SEGMENT_REMAINING("segRemaining", "Abschnitt verbleibend", FieldGroup.TIME, "Countdown bis zum nächsten Abschnitt"),
    SEGMENT_ELAPSED("segElapsed", "Abschnitt gefahren", FieldGroup.TIME, "Zeit im aktuellen Abschnitt"),
    WORKOUT_ELAPSED("workoutElapsed", "Trainingszeit", FieldGroup.TIME, "Gefahrene Zeit im Training"),
    WORKOUT_REMAINING("workoutRemaining", "Verbleibend", FieldGroup.TIME, "Restzeit des Trainings"),
    RIDE_TIME("rideTime", "Fahrzeit", FieldGroup.TIME, "Aufgezeichnete Zeit ohne Pausen"),
    CLOCK("clock", "Uhrzeit", FieldGroup.TIME, "Aktuelle Uhrzeit"),
    ETA("eta", "Fertig um", FieldGroup.TIME, "Voraussichtliche Uhrzeit am Ende"),

    NEXT_TARGET("nextTarget", "Nächstes Ziel", FieldGroup.WORKOUT, "Zielleistung des folgenden Abschnitts"),
    SEGMENT_NAME("segName", "Abschnitt", FieldGroup.WORKOUT, "Name und Nummer des Abschnitts"),
    PROGRESS("progress", "Fortschritt", FieldGroup.WORKOUT, "Anteil des Trainings in Prozent"),
    INTENSITY("intensity", "Intensität ±", FieldGroup.WORKOUT, "Eingestellter Intensitätsregler"),
    TIME_IN_ZONE("tiz", "Zeit in Zone", FieldGroup.WORKOUT, "Sekunden in der aktuellen Leistungszone"),

    SPEED("speed", "Geschwindigkeit", FieldGroup.OTHER, "Vom Trainer geschätzt"),
    DISTANCE("distance", "Distanz", FieldGroup.OTHER, "Vom Trainer geschätzt"),
    CALORIES("calories", "Kalorien", FieldGroup.OTHER, "Geschätzter Verbrauch"),
    ;

    companion object {
        fun byKey(key: String): DataField? = entries.firstOrNull { it.key == key }

        /** Keys for the three layouts riders can pick with one tap. */
        val PRESET_MINIMAL = listOf("power", "target", "segRemaining", "cadence")
        val PRESET_STANDARD = listOf("power", "hr", "cadence", "segRemaining", "workoutRemaining", "nextTarget", "powerLap", "tss", "work")
        val PRESET_FULL = listOf(
            "power", "target", "targetDiff", "hr", "hrZone", "cadence", "segRemaining", "segElapsed",
            "workoutRemaining", "nextTarget", "powerLap", "np", "if", "tss", "work", "wkg", "accuracy", "clock",
        )
        val DEFAULT = PRESET_STANDARD
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

/** Computes a field's value from the live ride state. */
fun DataField.value(s: RideState, settings: AppSettings): FieldValue {
    val st = s.stats
    val ftp = settings.ftp
    val power = s.displayPower
    val target = s.engine.targetWatts
    fun w(v: Int?) = FieldValue(v?.toString() ?: "–", "W")
    fun dash() = FieldValue("–")
    return when (this) {
        DataField.POWER -> FieldValue(power?.toString() ?: "–", "W", accent = Metric.Power)
        DataField.POWER_3S -> w(s.samples.takeLast(3).mapNotNull { it.power }.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        DataField.POWER_10S -> w(st.power10s)
        DataField.POWER_30S -> w(st.power30s)
        DataField.TARGET -> FieldValue(target?.toString() ?: "–", "W", accent = Metric.Target)
        DataField.TARGET_DIFF -> {
            if (power == null || target == null) dash()
            else {
                val d = power - target
                val color = when {
                    abs(d) <= target * 0.05 -> Color(0xFF22C55E)
                    abs(d) <= target * 0.10 -> Color(0xFFEAB308)
                    else -> Color(0xFFEF4444)
                }
                FieldValue((if (d > 0) "+" else "") + d, "W", accent = color)
            }
        }
        DataField.POWER_PCT_FTP -> if (power == null || ftp <= 0) dash() else FieldValue((power * 100.0 / ftp).roundToInt().toString(), "%")
        DataField.POWER_ZONE -> {
            if (power == null || ftp <= 0) dash()
            else {
                val z = PowerZone.of(power * 100.0 / ftp)
                FieldValue(z.name, "", z.label, zoneColor(z))
            }
        }
        DataField.POWER_AVG -> w(st.avgPower.takeIf { st.seconds > 0 })
        DataField.POWER_LAP -> w(st.lapAvgPower.takeIf { st.lapSeconds > 0 })
        DataField.POWER_MAX -> w(st.maxPower.takeIf { st.seconds > 0 })
        DataField.NP -> w(st.normalizedPower.takeIf { st.seconds > 0 })
        DataField.IF -> if (st.seconds == 0) dash() else FieldValue("%.2f".format(st.intensityFactor))
        DataField.TSS -> FieldValue(st.tss.roundToInt().toString())
        DataField.WATTS_PER_KG -> if (power == null) dash() else FieldValue("%.1f".format(power / settings.weightKg), "W/kg")
        DataField.WORK -> FieldValue(st.workKj.roundToInt().toString(), "kJ")
        DataField.ACCURACY -> st.lapAccuracyPct?.let { FieldValue(it.toString(), "%") } ?: dash()

        DataField.HR -> {
            val hr = s.live.heartRate
            FieldValue(hr?.toString() ?: "–", "bpm", hr?.let { HeartRateZone.of(it, settings.maxHr).let { z -> "${z.name} · ${z.label}" } }, Metric.HeartRate)
        }
        DataField.HR_ZONE -> {
            val hr = s.live.heartRate ?: return dash()
            val z = HeartRateZone.of(hr, settings.maxHr)
            FieldValue(z.name, "", z.label, Metric.HeartRate)
        }
        DataField.HR_PCT -> s.live.heartRate?.let { FieldValue((it * 100.0 / settings.maxHr).roundToInt().toString(), "%") } ?: dash()
        DataField.HR_AVG -> st.avgHr?.let { FieldValue(it.toString(), "bpm") } ?: dash()
        DataField.HR_LAP -> st.lapAvgHr?.let { FieldValue(it.toString(), "bpm") } ?: dash()
        DataField.HR_MAX -> st.maxHr?.let { FieldValue(it.toString(), "bpm") } ?: dash()

        DataField.CADENCE -> FieldValue(s.live.cadence?.toString() ?: "–", "rpm", s.engine.targetCadence?.let { "Ziel $it rpm" }, Metric.Cadence)
        DataField.CADENCE_AVG -> st.avgCadence?.let { FieldValue(it.toString(), "rpm") } ?: dash()
        DataField.CADENCE_LAP -> st.lapAvgCadence?.let { FieldValue(it.toString(), "rpm") } ?: dash()
        DataField.CADENCE_TARGET -> {
            val t = s.engine.targetCadence ?: return FieldValue("–", "", "Kein Ziel")
            val c = s.live.cadence
            val color = when {
                c == null -> null
                abs(c - t) <= 5 -> Color(0xFF22C55E)
                abs(c - t) <= 10 -> Color(0xFFEAB308)
                else -> Color(0xFFEF4444)
            }
            FieldValue(t.toString(), "rpm", c?.let { if (it < t - 5) "Schneller treten" else if (it > t + 5) "Langsamer treten" else "Passt" }, color)
        }

        DataField.SEGMENT_REMAINING -> if (s.timeline == null) dash() else FieldValue(formatDuration(s.engine.segmentRemainingSec))
        DataField.SEGMENT_ELAPSED -> {
            val seg = s.timeline?.segments?.getOrNull(s.engine.segmentIndex) ?: return dash()
            FieldValue(formatDuration((s.engine.workoutSec - seg.startSec).toInt()))
        }
        DataField.WORKOUT_ELAPSED -> FieldValue(formatDuration(s.engine.workoutSec.toInt()))
        DataField.WORKOUT_REMAINING -> if (s.timeline == null) dash() else FieldValue(formatDuration(s.engine.totalSec - s.engine.workoutSec.toInt()))
        DataField.RIDE_TIME -> FieldValue(formatDuration(s.elapsedSec))
        DataField.CLOCK -> FieldValue(clockFormat.format(Date()))
        DataField.ETA -> {
            if (s.timeline == null) dash()
            else {
                val remaining = s.engine.totalSec - s.engine.workoutSec.toInt()
                FieldValue(clockFormat.format(Date(System.currentTimeMillis() + remaining * 1000L)))
            }
        }

        DataField.NEXT_TARGET -> s.engine.nextTargetWatts?.let { FieldValue(it.toString(), "W") }
            ?: if (s.timeline != null) FieldValue("–", "", "Letzter Abschnitt") else dash()
        DataField.SEGMENT_NAME -> {
            val tl = s.timeline ?: return dash()
            val i = s.engine.segmentIndex
            val seg = tl.segments.getOrNull(i) ?: return dash()
            FieldValue("${i + 1}/${tl.segments.size}", "", seg.label.ifBlank { "Abschnitt" })
        }
        DataField.PROGRESS -> if (s.engine.totalSec == 0) dash() else FieldValue((s.engine.workoutSec * 100 / s.engine.totalSec).roundToInt().toString(), "%")
        DataField.INTENSITY -> FieldValue(s.engine.intensityPct.toString(), "%")
        DataField.TIME_IN_ZONE -> {
            if (power == null || ftp <= 0) dash()
            else {
                val z = PowerZone.of(power * 100.0 / ftp)
                FieldValue(formatDuration(st.zoneSeconds[z.ordinal]), "", "in ${z.name}", zoneColor(z))
            }
        }

        DataField.SPEED -> s.live.speedKmh?.let { FieldValue("%.1f".format(it), "km/h") } ?: dash()
        DataField.DISTANCE -> FieldValue("%.2f".format(st.distanceM / 1000), "km")
        DataField.CALORIES -> FieldValue(st.calories.toString(), "kcal")
    }
}
