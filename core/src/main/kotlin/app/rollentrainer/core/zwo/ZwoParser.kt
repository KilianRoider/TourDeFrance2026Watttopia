package app.rollentrainer.core.zwo

import app.rollentrainer.core.model.Block
import app.rollentrainer.core.model.Cue
import app.rollentrainer.core.model.Power
import app.rollentrainer.core.model.Workout
import org.w3c.dom.Element
import java.io.InputStream
import java.util.UUID
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.roundToInt

/** Reads and writes Zwift workout files (.zwo), the de-facto exchange format for indoor workouts. */
object Zwo {
    fun parse(input: InputStream): Workout {
        val factory = DocumentBuilderFactory.newInstance()
        // ZWO files never need external entities; disabling them keeps the parser safe.
        runCatching { factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        factory.isExpandEntityReferences = false
        val doc = factory.newDocumentBuilder().parse(input)
        val root = doc.documentElement
        fun text(tag: String) = root.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()

        val blocks = mutableListOf<Block>()
        val container = root.getElementsByTagName("workout").item(0) as? Element
            ?: error("Keine <workout>-Sektion gefunden")
        val children = container.childNodes
        for (i in 0 until children.length) {
            val el = children.item(i) as? Element ?: continue
            fun pct(name: String): Power? =
                el.getAttribute(name).takeIf { it.isNotBlank() }?.toDoubleOrNull()?.let { Power.pct((it * 100).roundToInt()) }
            fun int(name: String): Int = el.getAttribute(name).toDoubleOrNull()?.roundToInt() ?: 0
            fun cad(name: String): Int? = el.getAttribute(name).toDoubleOrNull()?.roundToInt()?.takeIf { it > 0 }
            val cues = buildList {
                val events = el.getElementsByTagName("textevent")
                for (k in 0 until events.length) {
                    val ev = events.item(k) as? Element ?: continue
                    val msg = ev.getAttribute("message").trim()
                    if (msg.isNotEmpty()) add(Cue(ev.getAttribute("timeoffset").toDoubleOrNull()?.roundToInt() ?: 0, msg))
                }
            }.sortedBy { it.offsetSec }
            val id = UUID.randomUUID().toString()
            when (el.tagName) {
                "SteadyState", "SolidState" -> blocks += Block.Steady(id, int("Duration"), pct("Power") ?: Power.pct(50), cadence = cad("Cadence"), cues = cues)
                "Warmup", "Cooldown", "Ramp" -> blocks += Block.Ramp(
                    id, int("Duration"),
                    pct("PowerLow") ?: Power.pct(50),
                    pct("PowerHigh") ?: Power.pct(50),
                    when (el.tagName) {
                        "Warmup" -> "Einfahren"
                        "Cooldown" -> "Ausfahren"
                        else -> ""
                    },
                    cadence = cad("Cadence"),
                    cues = cues,
                )
                "IntervalsT" -> blocks += Block.Intervals(
                    id, int("Repeat").coerceAtLeast(1),
                    int("OnDuration"), pct("OnPower") ?: Power.pct(100),
                    int("OffDuration"), pct("OffPower") ?: Power.pct(50),
                    cadence = cad("Cadence"), offCadence = cad("CadenceResting"), cues = cues,
                )
                "FreeRide", "MaxEffort" -> blocks += Block.FreeRide(
                    id, int("Duration"),
                    if (el.tagName == "MaxEffort") "Maximal" else "Frei",
                    pct("Power") ?: Power.pct(if (el.tagName == "MaxEffort") 150 else 100),
                    cadence = cad("Cadence"), cues = cues,
                )
            }
        }
        return Workout(
            id = UUID.randomUUID().toString(),
            name = text("name").ifBlank { "Importiertes Training" },
            description = text("description"),
            blocks = blocks,
        )
    }

    fun write(workout: Workout, ftp: Int): String = buildString {
        fun f(p: Power) = String.format(java.util.Locale.US, "%.2f", p.percentOfFtp(ftp) / 100.0)
        fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
        appendLine("<workout_file>")
        appendLine("  <author>Wattwerk</author>")
        appendLine("  <name>${esc(workout.name)}</name>")
        appendLine("  <description>${esc(workout.description)}</description>")
        appendLine("  <sportType>bike</sportType>")
        appendLine("  <workout>")
        fun cadAttr(name: String, v: Int?) = if (v != null) " $name=\"$v\"" else ""
        fun body(b: Block, open: String) {
            if (b.cues.isEmpty()) {
                appendLine("    <$open/>")
            } else {
                appendLine("    <$open>")
                for (c in b.cues) appendLine("      <textevent timeoffset=\"${c.offsetSec}\" message=\"${esc(c.text)}\"/>")
                appendLine("    </${open.substringBefore(' ')}>")
            }
        }
        for (b in workout.blocks) when (b) {
            is Block.Steady -> body(b, "SteadyState Duration=\"${b.durationSec}\" Power=\"${f(b.power)}\"${cadAttr("Cadence", b.cadence)}")
            is Block.Ramp -> body(b, "Ramp Duration=\"${b.durationSec}\" PowerLow=\"${f(b.from)}\" PowerHigh=\"${f(b.to)}\"${cadAttr("Cadence", b.cadence)}")
            is Block.FreeRide -> body(b, "FreeRide Duration=\"${b.durationSec}\" FlatRoad=\"1\" Power=\"${f(b.guide)}\"${cadAttr("Cadence", b.cadence)}")
            is Block.Intervals -> body(
                b,
                "IntervalsT Repeat=\"${b.repeat}\" OnDuration=\"${b.onSec}\" OffDuration=\"${b.offSec}\" " +
                    "OnPower=\"${f(b.onPower)}\" OffPower=\"${f(b.offPower)}\"${cadAttr("Cadence", b.cadence)}${cadAttr("CadenceResting", b.offCadence)}",
            )
        }
        appendLine("  </workout>")
        appendLine("</workout_file>")
    }
}
