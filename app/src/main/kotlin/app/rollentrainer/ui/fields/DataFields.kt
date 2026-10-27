package app.rollentrainer.ui.fields

import androidx.compose.ui.graphics.Color
import app.rollentrainer.core.engine.ErgState
import app.rollentrainer.core.metrics.HeartRateZone
import app.rollentrainer.core.metrics.ZoneScheme
import app.rollentrainer.data.AppSettings
import app.rollentrainer.ride.RideState
import app.rollentrainer.ui.components.formatDuration
import app.rollentrainer.ui.theme.Metric
import app.rollentrainer.ui.theme.zoneColorAt
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
    POWER_ZONE("powerZone", "Leistungszone", FieldGroup.POWER, "Zone nach dem gewählten Zonenschema"),
    POWER_AVG("powerAvg", "Ø Leistung", FieldGroup.POWER, "Durchschnitt der ganzen Fahrt"),
    POWER_LAP("powerLap", "Ø Abschnitt", FieldGroup.POWER, "Durchschnitt im aktuellen Abschnitt"),
    POWER_MAX("powerMax", "Max. Leistung", FieldGroup.POWER, "Höchster Wert der Fahrt"),
    NP("np", "Normalized Power", FieldGroup.POWER, "Gewichtete Durchschnittsleistung"),
    IF("if", "Intensität (IF)", FieldGroup.POWER, "Normalized Power geteilt durch FTP"),
    TSS("tss", "TSS", FieldGroup.POWER, "Training Stress Score bis jetzt"),
    WATTS_PER_KG("wkg", "W/kg", FieldGroup.POWER, "Leistung pro Kilogramm Körpergewicht"),
    WORK("work", "Arbeit", FieldGroup.POWER, "Geleistete Arbeit in Kilojoule"),
    ACCURACY("accuracy", "Trefferquote", FieldGroup.POWER, "Wie genau du im Abschnitt am Ziel liegst"),
    WBAL("wbal", "W'-Bilanz", FieldGroup.POWER, "Verbleibende anaerobe Reserve (Skiba); sinkt über der FTP, füllt sich darunter"),
    POWER_MATCH("powerMatch", "PowerMatch", FieldGroup.POWER, "Korrektur zwischen Powermeter und Trainer"),

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
    NEXT_SEGMENT("nextSeg", "Gleich", FieldGroup.WORKOUT, "Name, Dauer und Ziel des folgenden Abschnitts"),
    SEGMENT_NAME("segName", "Abschnitt", FieldGroup.WORKOUT, "Name und Nummer des Abschnitts"),
    PROGRESS("progress", "Fortschritt", FieldGroup.WORKOUT, "Anteil des Trainings in Prozent"),
    INTENSITY("intensity", "Intensität ±", FieldGroup.WORKOUT, "Eingestellter Intensitätsregler"),
    TIME_IN_ZONE("tiz", "Zeit in Zone", FieldGroup.WORKOUT, "Zeit in der aktuellen Leistungszone"),
    ERG("erg", "ERG", FieldGroup.WORKOUT, "ERG an, aus (Simulation) oder gelöst"),
    GRADE("grade", "Steigung", FieldGroup.WORKOUT, "Simulierte Steigung, wenn ERG aus ist"),

    SPEED("speed", "Geschwindigkeit", FieldGroup.OTHER, "Vom Trainer geschätzt"),
    DISTANCE("distance", "Distanz", FieldGroup.OTHER, "Vom Trainer geschätzt"),
    CALORIES("calories", "Kalorien", FieldGroup.OTHER, "Geschätzter Verbrauch"),
    ;

    companion object {
        fun byKey(key: String): DataField? = entries.firstOrNull { it.key == key }
    }
}

private val clockFormat = SimpleDateFormat("HH:mm", Locale.GERMANY)

private val Good = Color(0xFF22C55E)
private val Warn = Color(0xFFEAB308)
private val Bad = Color(0xFFEF4444)

/** Computes a field's value from the live ride state. */
fun DataField.value(s: RideState, settings: AppSettings): FieldValue {
    val st = s.stats
    val ftp = settings.ftp
    val power = s.displayPower
    val target = if (s.engine.erg) s.engine.targetWatts else null
    val scheme: ZoneScheme = settings.zoneScheme
    fun w(v: Int?) = FieldValue(v?.toString() ?: "–", "W")
    fun dash() = FieldValue("–")
    return when (this) {
        DataField.POWER -> FieldValue(power?.toString() ?: "–", "W", accent = Metric.Power)
        DataField.POWER_3S -> w(s.samples.takeLast(3).mapNotNull { it.power }.takeIf { it.isNotEmpty() }?.average()?.roundToInt())
        DataField.POWER_10S -> w(st.power10s)
        DataField.POWER_30S -> w(st.power30s)
        DataField.TARGET -> if (!s.engine.erg && s.engine.targetWatts != null) FieldValue(s.engine.targetWatts.toString(), "W", "Richtwert · ERG aus", Metric.Target)
            else FieldValue(target?.toString() ?: "–", "W", accent = Metric.Target)
        DataField.TARGET_DIFF -> {
            if (power == null || target == null) dash()
            else {
                val d = power - target
                val color = when {
                    abs(d) <= target * 0.05 -> Good
                    abs(d) <= target * 0.10 -> Warn
                    else -> Bad
                }
                FieldValue((if (d > 0) "+" else "") + d, "W", accent = color)
            }
        }
        DataField.POWER_PCT_FTP -> if (power == null || ftp <= 0) dash() else FieldValue((power * 100.0 / ftp).roundToInt().toString(), "%")
        DataField.POWER_ZONE -> {
            if (power == null || ftp <= 0) dash()
            else {
                val z = scheme.indexOf(power * 100.0 / ftp)
                FieldValue(scheme.name(z), "", scheme.labels[z], zoneColorAt(z))
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
        DataField.WBAL -> {
            val pct = (s.wBalJ / s.wPrimeJ * 100).roundToInt()
            val color = when {
                pct > 50 -> Good
                pct > 20 -> Warn
                else -> Bad
            }
            FieldValue("%.1f".format(s.wBalJ / 1000), "kJ", "$pct % übrig", color)
        }
        DataField.POWER_MATCH -> if (!settings.powerMatch) FieldValue("Aus", "", "In den Einstellungen")
            else s.powerMatchW?.let { FieldValue((if (it > 0) "+" else "") + it, "W", "Powermeter − Trainer") } ?: FieldValue("0", "W", "Kein Powermeter-Wert")

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

        DataField.CADENCE -> {
            val c = s.live.cadence
            val t = s.engine.targetCadence
            val accent = when {
                c == null || t == null -> Metric.Cadence
                abs(c - t) <= 5 -> Good
                abs(c - t) <= 10 -> Warn
                else -> Bad
            }
            FieldValue(c?.toString() ?: "–", "rpm", t?.let { "Ziel $it rpm" }, accent)
        }
        DataField.CADENCE_AVG -> st.avgCadence?.let { FieldValue(it.toString(), "rpm") } ?: dash()
        DataField.CADENCE_LAP -> st.lapAvgCadence?.let { FieldValue(it.toString(), "rpm") } ?: dash()
        DataField.CADENCE_TARGET -> {
            val t = s.engine.targetCadence ?: return FieldValue("–", "", "Kein Ziel")
            val c = s.live.cadence
            val color = when {
                c == null -> null
                abs(c - t) <= 5 -> Good
                abs(c - t) <= 10 -> Warn
                else -> Bad
            }
            FieldValue(t.toString(), "rpm", c?.let { if (it < t - 5) "Schneller treten" else if (it > t + 5) "Langsamer treten" else "Passt" }, color)
        }

        DataField.SEGMENT_REMAINING -> if (s.timeline == null || s.engine.continuing) dash() else FieldValue(formatDuration(s.engine.segmentRemainingSec))
        DataField.SEGMENT_ELAPSED -> {
            val seg = s.timeline?.segments?.getOrNull(s.engine.segmentIndex) ?: return dash()
            FieldValue(formatDuration((s.engine.workoutSec - seg.startSec).toInt()))
        }
        DataField.WORKOUT_ELAPSED -> FieldValue(formatDuration(s.engine.workoutSec.toInt()))
        DataField.WORKOUT_REMAINING -> if (s.timeline == null) dash() else FieldValue(formatDuration((s.engine.totalSec - s.engine.workoutSec.toInt()).coerceAtLeast(0)))
        DataField.RIDE_TIME -> FieldValue(formatDuration(s.elapsedSec))
        DataField.CLOCK -> FieldValue(clockFormat.format(Date()))
        DataField.ETA -> {
            if (s.timeline == null) dash()
            else {
                val remaining = (s.engine.totalSec - s.engine.workoutSec.toInt()).coerceAtLeast(0)
                FieldValue(clockFormat.format(Date(System.currentTimeMillis() + remaining * 1000L)))
            }
        }

        DataField.NEXT_TARGET -> s.engine.nextTargetWatts?.let { FieldValue(it.toString(), "W", if (s.engine.nextSegment?.erg == false) "frei" else null) }
            ?: if (s.timeline != null) FieldValue("–", "", "Letzter Abschnitt") else dash()
        DataField.NEXT_SEGMENT -> {
            val n = s.engine.nextSegment ?: return if (s.timeline != null) FieldValue("–", "", "Letzter Abschnitt") else dash()
            val tgt = s.engine.nextTargetWatts
            FieldValue(formatDuration(n.durationSec), "", (n.label.ifBlank { "Abschnitt" }) + (tgt?.let { " · $it W" } ?: "") + if (!n.erg) " · frei" else "")
        }
        DataField.SEGMENT_NAME -> {
            val tl = s.timeline ?: return dash()
            val i = s.engine.segmentIndex
            val seg = tl.segments.getOrNull(i) ?: return if (s.engine.continuing) FieldValue("Frei", "", "Weiterfahren") else dash()
            FieldValue("${i + 1}/${tl.segments.size}", "", seg.label.ifBlank { "Abschnitt" })
        }
        DataField.PROGRESS -> if (s.engine.totalSec == 0) dash() else FieldValue((s.engine.workoutSec * 100 / s.engine.totalSec).roundToInt().coerceAtMost(100).toString(), "%")
        DataField.INTENSITY -> FieldValue(s.engine.intensityPct.toString(), "%")
        DataField.TIME_IN_ZONE -> {
            if (power == null || ftp <= 0) dash()
            else {
                val z = scheme.indexOf(power * 100.0 / ftp)
                val seconds = if (scheme == ZoneScheme.COGGAN) st.zoneSeconds else scheme.timeInZones(s.samples.map { it.power }, ftp)
                FieldValue(formatDuration(seconds.getOrElse(z) { 0 }), "", "in ${scheme.name(z)}", zoneColorAt(z))
            }
        }
        DataField.ERG -> when {
            !s.engine.erg -> FieldValue("Aus", "", "Simulation ${"%.1f".format(s.engine.gradePct)} %", Metric.Target)
            s.engine.ergState == ErgState.RELEASED -> FieldValue("Gelöst", "", "Tritt wieder an", Warn)
            s.engine.ergState == ErgState.RE_ENGAGING -> FieldValue("Steigt", "", "Ziel kommt zurück", Warn)
            else -> FieldValue("An", "", null, Good)
        }
        DataField.GRADE -> FieldValue("%.1f".format(s.engine.gradePct), "%", if (s.engine.erg) "Nur ohne ERG" else null)

        DataField.SPEED -> s.live.speedKmh?.let { FieldValue("%.1f".format(it), "km/h") } ?: dash()
        DataField.DISTANCE -> FieldValue("%.2f".format(st.distanceM / 1000), "km")
        DataField.CALORIES -> FieldValue(st.calories.toString(), "kcal")
    }
}
