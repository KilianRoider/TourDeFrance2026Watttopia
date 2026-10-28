package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.Prototype
import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.SceneMaterial

/*
 * The crowd beyond the 3D spectators' last LOD (People.LOD_FAR, 600 m): work package T's crowd strips,
 * 6 x 3 m cards of two rows of fans that tile sideways, standing along the road so the switchbacks
 * above on a mountain and the finish straight seen from afar stay lined with people. Each prototype
 * has an empty near LOD up to People.LOD_FAR, so card and 3D crowd never show together.
 */
object FarCrowd {
    /** Card size and tiling period (T's manifest: size_m 6 x 3). */
    const val WIDTH = 6.0
    const val HEIGHT = 3.0f
    const val LOD_END = 3000f

    /** Calm (open country, towns), mixed (climbs), flags (the party zones and finales). */
    val CALM = "crowd_strip_calm"
    val MIXED = "crowd_strip_mixed"
    val FLAGS = "crowd_strip_flags"

    private fun material(tex: String) = SceneMaterial("race_$tex", texture = tex, alphaCutout = true, doubleSided = true, roughness = 0.9f, castsShadow = false)

    /** One card facing local -z (the road), bottom edge on y = 0, image left on the viewer's left. */
    private fun card() = MeshBuilder(8).apply {
        val n = v3(0f, 0f, -1f)
        val w = (WIDTH / 2).toFloat()
        val a = vertex(v3(w, 0f, 0f), n, 0f, 1f)
        val b = vertex(v3(-w, 0f, 0f), n, 1f, 1f)
        val c = vertex(v3(-w, HEIGHT, 0f), n, 1f, 0f)
        val d = vertex(v3(w, HEIGHT, 0f), n, 0f, 0f)
        quad(a, b, c, d)
    }.build()

    fun prototypes(): List<Prototype> {
        val quad = card()
        return listOf(CALM, MIXED, FLAGS).map { id ->
            Prototype(id, listOf(ProtoLod(emptyList(), People.LOD_FAR), ProtoLod(listOf(material("${id}_a") to quad), LOD_END)), 3.4f)
        }
    }
}
