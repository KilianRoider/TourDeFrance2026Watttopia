package app.rollentrainer.core.zwo

import app.rollentrainer.core.model.Block
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
            val id = UUID.randomUUID().toString()
            when (el.tagName) {
                "SteadyState", "SolidState" -> blocks += Block.Steady(id, int("Duration"), pct("Power") ?: Power.pct(50))
                "Warmup", "Cooldown", "Ramp" -> blocks += Block.Ramp(
                    id, int("Duration"),
                    pct("PowerLow") ?: Power.pct(50),
                    pct("PowerHigh") ?: Power.pct(50),
                    when (el.tagName) {
                        "Warmup" -> "Einfahren"
                        "Cooldown" -> "Ausfahren"
                        else -> ""
                    },
                )
                "IntervalsT" -> blocks += Block.Intervals(
                    id, int("Repeat").coerceAtLeast(1),
                    int("OnDuration"), pct("OnPower") ?: Power.pct(100),
                    int("OffDuration"), pct("OffPower") ?: Power.pct(50),
                )
                "FreeRide", "MaxEffort" -> blocks += Block.Steady(id, int("Duration"), Power.pct(50), "Frei")
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
        appendLine("  <author>Rollentrainer</author>")
        appendLine("  <name>${esc(workout.name)}</name>")
        appendLine("  <description>${esc(workout.description)}</description>")
        appendLine("  <sportType>bike</sportType>")
        appendLine("  <workout>")
        for (b in workout.blocks) when (b) {
            is Block.Steady -> appendLine("    <SteadyState Duration=\"${b.durationSec}\" Power=\"${f(b.power)}\"/>")
            is Block.Ramp -> appendLine("    <Ramp Duration=\"${b.durationSec}\" PowerLow=\"${f(b.from)}\" PowerHigh=\"${f(b.to)}\"/>")
            is Block.Intervals -> appendLine(
                "    <IntervalsT Repeat=\"${b.repeat}\" OnDuration=\"${b.onSec}\" OffDuration=\"${b.offSec}\" " +
                    "OnPower=\"${f(b.onPower)}\" OffPower=\"${f(b.offPower)}\"/>"
            )
        }
        appendLine("  </workout>")
        appendLine("</workout_file>")
    }
}
