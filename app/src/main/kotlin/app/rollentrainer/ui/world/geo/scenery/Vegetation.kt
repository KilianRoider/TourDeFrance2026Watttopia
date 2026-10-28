package app.rollentrainer.ui.world.geo.scenery

import app.rollentrainer.ui.world.geo.ProtoLod
import app.rollentrainer.ui.world.geo.Prototype
import kotlin.math.max
import kotlin.math.sqrt

/** Builds the instanceable tree prototypes (near / mid / impostor LODs) from [TreeBuilders]. */
internal object TreeProtos {
    class Built(val species: Species, val proto: Prototype, val impostorFrame: FloatArray?, val model: TreeModel)

    private val cache = HashMap<Species, Built>()

    @Synchronized
    fun get(sp: Species): Built = cache.getOrPut(sp) { build(sp) }

    private fun build(sp: Species): Built {
        val model = TreeBuilders.build(sp)
        val mat = Mats.foliage(sp.atlas)
        val near = MeshBuilder(4096, 8192)
        model.emitNear(near)
        val mid = MeshBuilder(1024, 2048)
        model.emitMid(mid, sp.midCards)
        val lods = ArrayList<ProtoLod>()
        lods += ProtoLod(listOf(mat to near.build()), sp.nearM)
        lods += ProtoLod(listOf(mat to mid.build()), sp.midM)
        var frame: FloatArray? = null
        // MUGO borrows the pine atlas, whose impostor shows a pine: it keeps the (cheap) mid model instead.
        if (sp == Species.MUGO) {
            lods += ProtoLod(listOf(mat to mid.build()), sp.farM)
        } else {
            frame = model.impostorFrame()
            val far = MeshBuilder(8, 12)
            model.emitImpostor(far, frame)
            lods += ProtoLod(listOf(mat to far.build()), sp.farM)
        }
        val b = near.bounds()
        val radius = max(sqrt(max(b[0] * b[0], b[3] * b[3]) + max(b[2] * b[2], b[5] * b[5])), b[4] * 0.5f).let { r ->
            // Bounding sphere about the model origin that contains the crown top.
            sqrt(r * r + b[4] * b[4] * 0.25f) + 0.5f
        }
        return Built(sp, Prototype(sp.id, lods, radius), frame, model)
    }
}
