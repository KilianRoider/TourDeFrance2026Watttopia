package app.rollentrainer.ui.world.geo.race

import app.rollentrainer.ui.world.geo.MeshBuffer
import app.rollentrainer.ui.world.geo.SceneMaterial
import kotlin.math.PI
import kotlin.math.min

/**
 * The one-off meshes of a range, one builder per material so a chunk costs at most one draw per
 * material (the contract's SceneryChunk.meshes).
 */
internal class ChunkMeshes {
    val props = MeshBuilder(1024)
    val signs = MeshBuilder(256)
    val cloth = MeshBuilder(64)
    val line = MeshBuilder(64)
    val paint = MeshBuilder(256)
    private val all get() = listOf(props, signs, cloth, line, paint)

    /** Runs [block] with every builder in the frame [f] (structure-local to chunk-local). */
    fun inFrame(f: Affine, block: () -> Unit) {
        val saved = all.map { it.xf }
        for (b in all) b.xf = f
        try { block() } finally { all.forEachIndexed { i, b -> b.xf = saved[i] } }
    }

    fun build(): List<Pair<SceneMaterial, MeshBuffer>> = listOf(
        RaceMaterials.PROPS to props, RaceMaterials.SIGNS to signs, RaceMaterials.CLOTH to cloth,
        RaceMaterials.LINE to line, RaceMaterials.ROAD_PAINT to paint,
    ).filter { !it.second.isEmpty() }.map { it.first to it.second.build() }
}

/*
 * Banners, arches and the finish gantry, built in a structure frame: origin on the road's centre line
 * at the path elevation, -z the direction of travel, +x the right-hand side. Printed faces look at the
 * riders (+z) and are repeated on the back. gL / gR are the ground heights under the left and right
 * legs relative to the origin, so nothing floats on a sloping verge.
 */
internal object Structures {
    private val GREY = Col.hex(0x9EA3A8)
    private val NAVY = Col.hex(0x14285E)

    /** Banner across the road on two poles (kilometre banners, sprint, KOM, Bienvenue). */
    fun banner(m: ChunkMeshes, cell: Int, hw: Float, gL: Float, gR: Float, height: Float = 5.0f) {
        val x = hw + 0.7f
        val h = 0.85f
        m.props.withColor(GREY) {
            m.props.tube(v3(-x, gL - 0.3f, 0f), v3(-x, height + h / 2 + 0.25f, 0f), 0.06f, 0.05f, 6)
            m.props.tube(v3(x, gR - 0.3f, 0f), v3(x, height + h / 2 + 0.25f, 0f), 0.06f, 0.05f, 6)
            m.props.tube(v3(-x, height + h / 2 + 0.05f, 0f), v3(x, height + h / 2 + 0.05f, 0f), 0.025f, 0.025f, 4, capA = false, capB = false)
        }
        val r = Atlas.banner(cell)
        Props.panel(m.signs, -x + 0.06f, height - h / 2, x - 0.06f, height + h / 2, 0.02f, true, r)
        Props.panel(m.signs, -x + 0.06f, height - h / 2, x - 0.06f, height + h / 2, -0.02f, false, r)
    }

    /**
     * Inflatable half-ring arch over the road (flamme rouge, départ): legs beyond the road edge, a
     * printed band on its crown, and for the flamme rouge the red triangle hanging from the apex.
     */
    fun inflatable(m: ChunkMeshes, color: FloatArray, cell: Int?, hw: Float, gL: Float, gR: Float, flamme: Boolean) {
        val radius = hw + 1.0f
        val tube = 0.42f
        m.props.withColor(color) {
            m.props.with(Affine.rotY(PI.toFloat() / 2)) { m.props.torusX(V3.ZERO, radius, tube, 22, 8, -PI.toFloat() / 2, PI.toFloat() / 2) }
            // Down to the verge where it lies below the road, and a ballast base on the ground.
            m.props.tube(v3(-radius, min(gL, 0f) - 0.1f, 0f), v3(-radius, 0.02f, 0f), tube, tube, 8, capB = false)
            m.props.tube(v3(radius, min(gR, 0f) - 0.1f, 0f), v3(radius, 0.02f, 0f), tube, tube, 8, capB = false)
        }
        m.props.withColor(Col.scale(color, 0.55f)) {
            m.props.boxMinMax(v3(-radius - 0.55f, gL - 0.1f, -0.55f), v3(-radius + 0.55f, gL + 0.3f, 0.55f), skipBottom = true)
            m.props.boxMinMax(v3(radius - 0.55f, gR - 0.1f, -0.55f), v3(radius + 0.55f, gR + 0.3f, 0.55f), skipBottom = true)
        }
        if (cell != null) {
            val w = min(2 * radius - 1.6f, 6.4f)
            val h = w / 8f
            val r = Atlas.banner(cell)
            Props.panel(m.signs, -w / 2, radius - h / 2, w / 2, radius + h / 2, tube + 0.03f, true, r)
            Props.panel(m.signs, -w / 2, radius - h / 2, w / 2, radius + h / 2, -tube - 0.03f, false, r)
        }
        if (flamme) {
            m.cloth.withColor(Col.hex(0xD7141A)) {
                val y = radius - tube - 0.02f
                val a = m.cloth.vertex(v3(-0.55f, y, 0f), V3.Z)
                val b = m.cloth.vertex(v3(0.55f, y, 0f), V3.Z)
                val c = m.cloth.vertex(v3(0f, y - 0.95f, 0f), V3.Z)
                m.cloth.tri(a, c, b)
            }
        }
    }

    /** Finish gantry: two pillars, the ARRIVÉE beam over the road and the race clock on top. */
    fun gantry(m: ChunkMeshes, hw: Float, gL: Float, gR: Float) {
        val x = hw + 1.3f
        val top = 7.0f
        val beam = 5.5f
        m.props.withColor(NAVY) {
            m.props.boxMinMax(v3(-x - 0.35f, gL - 0.2f, -0.35f), v3(-x + 0.35f, top, 0.35f), skipBottom = true)
            m.props.boxMinMax(v3(x - 0.35f, gR - 0.2f, -0.35f), v3(x + 0.35f, top, 0.35f), skipBottom = true)
            m.props.boxMinMax(v3(-x - 0.35f, beam, -0.4f), v3(x + 0.35f, top, 0.4f))
        }
        val r = Atlas.banner(Atlas.ARRIVEE)
        Props.panel(m.signs, -x - 0.35f, beam, x + 0.35f, top, 0.41f, true, r)
        Props.panel(m.signs, -x - 0.35f, beam, x + 0.35f, top, -0.41f, false, r)
        m.props.withColor(Col.hex(0x101010)) { m.props.boxMinMax(v3(-1.1f, top, -0.3f), v3(1.1f, top + 1.1f, 0.3f), skipBottom = true) }
        val rc = Atlas.bannerMiddle(Atlas.CLOCK)
        Props.panel(m.signs, -1.1f, top, 1.1f, top + 1.1f, 0.31f, true, rc)
        Props.panel(m.signs, -1.1f, top, 1.1f, top + 1.1f, -0.31f, false, rc)
    }

    /** Distance board (500 m … 50 m) on a post at lateral [x], above the barriers. */
    fun board(m: ChunkMeshes, metres: Int, x: Float, g: Float) {
        val cell = Atlas.BOARDS[metres] ?: return
        m.props.withColor(GREY) { m.props.tube(v3(x, g - 0.2f, 0f), v3(x, 1.85f + g, 0f), 0.035f, 0.035f, 5) }
        Props.panel(m.signs, x - 0.55f, g + 1.3f, x + 0.55f, g + 1.85f, 0.045f, true, Atlas.bannerMiddle(cell))
        m.props.withColor(Col.hex(0xD8D8D8)) { Props.panel(m.props, x - 0.55f, g + 1.3f, x + 0.55f, g + 1.85f, -0.045f, false) }
    }
}
