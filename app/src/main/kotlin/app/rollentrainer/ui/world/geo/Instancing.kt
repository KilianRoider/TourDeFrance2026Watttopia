package app.rollentrainer.ui.world.geo

import kotlin.math.sqrt

/**
 * Merges the instance sets of all resident chunks into one instance list per (prototype, LOD) around
 * the camera, on the worker: distance LOD, distance culling, a generous view cone (the list is rebuilt
 * a few times per second while the camera may turn), and the instances near the shadow centre first so
 * the shadow pass draws only those. Positions become relative to the snapshot's camera position.
 */
internal class InstanceAssembler {
    class Source(val anchorX: Double, val anchorY: Double, val anchorZ: Double, val sets: List<InstanceSet>)

    /**
     * [castsShadow] per LOD: false for an empty LOD or one whose parts cast none (W3's far crowd starts with
     * an empty one). [farShadow]: the prototype has a coarser shadow mesh for casters away from the camera
     * ([ProtoGpu.shadowFarLod]).
     */
    class ProtoInfo(
        val lodMax: FloatArray, val radius: Float,
        val castsShadow: BooleanArray = BooleanArray(lodMax.size) { true }, val farShadow: Boolean = false,
    )

    class Snapshot(
        val originX: Double, val originY: Double, val originZ: Double,
        /** Horizontal view direction (unit) and the cosine of the half cone kept. */
        val dirX: Float, val dirZ: Float, val cosLimit: Float,
        val shadowX: Double, val shadowZ: Double, val shadowR: Double,
        val sources: List<Source>,
        val protos: Map<String, ProtoInfo>,
        val generation: Int,
        /** Multiplies the LOD distances' inverse: > 1 switches to coarser LODs sooner (triangle budget). */
        val lodBias: Float = 1f,
    )

    class Bucket(val protoId: String, val lod: Int, val data: FloatArray, val count: Int, val nearCount: Int)

    /**
     * Shadow casters of one prototype: every instance near the shadow centre, whatever its LOD, drawn in
     * the shadow pass with ONE mesh (see [ProtoGpu.shadowLod]): one shadow draw per prototype instead of
     * one per LOD, and a shadow map does not need the near model's detail.
     */
    class ShadowBucket(val protoId: String, val data: FloatArray, val count: Int, val far: Boolean = false)

    class Result(
        val originX: Double, val originY: Double, val originZ: Double, val buckets: List<Bucket>, val generation: Int, val total: Int,
        val shadows: List<ShadowBucket> = emptyList(),
    )

    private class Acc {
        var near = FloatArray(256); var nn = 0
        var far = FloatArray(256); var nf = 0
        var dist = FloatArray(64)
    }

    /**
     * Horizontal bounds of each instance set relative to its anchor and its largest scale (minX, minZ, maxX,
     * maxZ, maxScale), by identity: sets are immutable, and W2 cuts a tile's instances into 160 m blocks, so
     * a whole block beyond its prototype's last LOD is skipped without looking at its instances.
     */
    private var setBounds = java.util.IdentityHashMap<InstanceSet, FloatArray>()

    private fun boundsOf(set: InstanceSet): FloatArray {
        val d = set.data
        var x0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE; var x1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE; var sc = 0f
        for (i in 0 until set.count) {
            val o = i * InstanceSet.FLOATS
            x0 = minOf(x0, d[o]); x1 = maxOf(x1, d[o]); z0 = minOf(z0, d[o + 2]); z1 = maxOf(z1, d[o + 2]); sc = maxOf(sc, d[o + 4])
        }
        return floatArrayOf(x0, z0, x1, z1, sc)
    }

    fun assemble(s: Snapshot): Result {
        val acc = HashMap<Long, Acc>()
        val bounds = java.util.IdentityHashMap<InstanceSet, FloatArray>(setBounds.size + 16)
        val shadowAcc = HashMap<Int, Acc>()
        val ids = s.protos.keys.toList()
        val idIndex = HashMap<String, Int>().also { m -> ids.forEachIndexed { i, id -> m[id] = i } }
        var total = 0
        for (src in s.sources) {
            val ox = (src.anchorX - s.originX).toFloat()
            val oy = (src.anchorY - s.originY).toFloat()
            val oz = (src.anchorZ - s.originZ).toFloat()
            val sx = (src.anchorX - s.shadowX).toFloat()
            val sz = (src.anchorZ - s.shadowZ).toFloat()
            for (set in src.sets) {
                val pi = idIndex[set.prototypeId] ?: continue
                val info = s.protos[set.prototypeId] ?: continue
                // Small things (people, barriers, flags) cast shadows only near the camera, where they show; a crowd of
                // shadow casters 90 m away costs draws and triangles for a few dark pixels.
                val sr = if (info.radius < SMALL_RADIUS) minOf(s.shadowR, SMALL_SHADOW_R) else s.shadowR
                val sr2 = (sr * sr).toFloat()
                val maxD = info.lodMax.last()
                if (set.count == 0) continue
                val b = setBounds[set] ?: boundsOf(set)
                bounds[set] = b
                // Nearest horizontal distance from the camera to the block: none of it is in range when even
                // that, at the block's largest scale, is past the last LOD.
                val ex = maxOf(0f, b[0] + ox, -(b[2] + ox))
                val ez = maxOf(0f, b[1] + oz, -(b[3] + oz))
                if (sqrt(ex * ex + ez * ez) / maxOf(0.5f, b[4]) * s.lodBias > maxD) continue
                val d = set.data
                for (i in 0 until set.count) {
                    val o = i * InstanceSet.FLOATS
                    val rx = d[o] + ox; val ry = d[o + 1] + oy; val rz = d[o + 2] + oz
                    val dh2 = rx * rx + rz * rz
                    val dist = sqrt(dh2 + ry * ry)
                    val scale = d[o + 4]
                    val lodDist = dist / maxOf(0.5f, scale) * s.lodBias
                    if (lodDist > maxD) continue
                    val r = info.radius * scale
                    if (dist > 60f + r) {
                        val inv = 1f / sqrt(dh2)
                        if ((rx * s.dirX + rz * s.dirZ) * inv < s.cosLimit - r / dist) continue
                    }
                    var lod = 0
                    while (lod < info.lodMax.size - 1 && lodDist > info.lodMax[lod]) lod++
                    val key = (pi.toLong() shl 8) or lod.toLong()
                    val a = acc.getOrPut(key) { Acc() }
                    val qx = d[o] + sx; val qz = d[o + 2] + sz
                    val near = lod < 2 && qx * qx + qz * qz < sr2
                    if (near) {
                        if ((a.nn + 1) * 8 > a.near.size) a.near = a.near.copyOf(a.near.size * 2)
                        put(a.near, a.nn * 8, rx, ry, rz, d, o); a.nn++
                        // An instance casts a shadow only where its own LOD would: a far-figure prototype
                        // must not give the near spectators (another prototype) a second shadow.
                        if (!info.castsShadow[lod]) { total++; continue }
                        val sa = shadowAcc.getOrPut(pi) { Acc() }
                        // Casters away from the camera use the coarse shadow mesh where there is one (a tree's
                        // impostor cards): its shadow is a few pixels, the mid mesh's triangles are not.
                        if (info.farShadow && dh2 > SHADOW_DETAIL_R2) {
                            if ((sa.nf + 1) * 8 > sa.far.size) sa.far = sa.far.copyOf(sa.far.size * 2)
                            put(sa.far, sa.nf * 8, rx, ry, rz, d, o); sa.nf++
                        } else {
                            if ((sa.nn + 1) * 8 > sa.near.size) sa.near = sa.near.copyOf(sa.near.size * 2)
                            put(sa.near, sa.nn * 8, rx, ry, rz, d, o); sa.nn++
                        }
                    } else {
                        if ((a.nf + 1) * 8 > a.far.size) a.far = a.far.copyOf(a.far.size * 2)
                        put(a.far, a.nf * 8, rx, ry, rz, d, o); a.nf++
                    }
                    total++
                }
            }
        }
        setBounds = bounds
        val out = ArrayList<Bucket>(acc.size)
        for ((key, a) in acc) {
            val pi = (key shr 8).toInt()
            val lod = (key and 0xFF).toInt()
            var nf = a.nf
            // Cap a bucket: keep the nearest (far list sorted by distance only when needed).
            val cap = MAX_PER_BUCKET - a.nn
            if (nf > cap) {
                val idx = (0 until nf).sortedBy { val o = it * 8; a.far[o] * a.far[o] + a.far[o + 2] * a.far[o + 2] }
                val f2 = FloatArray(cap * 8)
                for ((j, k) in idx.take(cap).withIndex()) System.arraycopy(a.far, k * 8, f2, j * 8, 8)
                a.far = f2
                nf = cap.coerceAtLeast(0)
            }
            val data = FloatArray((a.nn + nf) * 8)
            System.arraycopy(a.near, 0, data, 0, a.nn * 8)
            System.arraycopy(a.far, 0, data, a.nn * 8, nf * 8)
            out += Bucket(ids[pi], lod, data, a.nn + nf, a.nn)
        }
        val shadows = ArrayList<ShadowBucket>(shadowAcc.size)
        for ((pi, a) in shadowAcc) {
            val n = minOf(a.nn, MAX_PER_BUCKET)
            if (n > 0) shadows += ShadowBucket(ids[pi], a.near.copyOf(n * 8), n)
            val nf = minOf(a.nf, MAX_PER_BUCKET)
            if (nf > 0) shadows += ShadowBucket(ids[pi], a.far.copyOf(nf * 8), nf, far = true)
        }
        return Result(s.originX, s.originY, s.originZ, out, s.generation, total, shadows)
    }

    private fun put(dst: FloatArray, o: Int, x: Float, y: Float, z: Float, src: FloatArray, so: Int) {
        dst[o] = x; dst[o + 1] = y; dst[o + 2] = z
        dst[o + 3] = src[so + 3]; dst[o + 4] = src[so + 4]; dst[o + 5] = src[so + 5]; dst[o + 6] = src[so + 6]; dst[o + 7] = src[so + 7]
    }

    companion object {
        const val MAX_PER_BUCKET = 12000
        const val SMALL_RADIUS = 4.5f
        const val SMALL_SHADOW_R = 40.0
        /** Shadow casters farther than this from the camera (horizontally) use the coarse shadow mesh. */
        const val SHADOW_DETAIL_R2 = 50f * 50f
    }
}

/**
 * Axis-aligned bounds of each one-off mesh of [c] relative to its anchor, 6 floats per mesh
 * (min x y z, max x y z), so the renderer can cull buildings and dressing per mesh. Worker thread.
 */
internal fun meshBounds(c: SceneryChunk): FloatArray {
    val out = FloatArray(c.meshes.size * 6)
    for ((i, pair) in c.meshes.withIndex()) {
        val m = pair.second
        var x0 = Float.MAX_VALUE; var y0 = Float.MAX_VALUE; var z0 = Float.MAX_VALUE
        var x1 = -Float.MAX_VALUE; var y1 = -Float.MAX_VALUE; var z1 = -Float.MAX_VALUE
        val v = m.vertices
        for (k in 0 until m.vertexCount) {
            val o = k * Vtx.FLOATS
            val x = v[o]; val y = v[o + 1]; val z = v[o + 2]
            if (x < x0) x0 = x; if (x > x1) x1 = x
            if (y < y0) y0 = y; if (y > y1) y1 = y
            if (z < z0) z0 = z; if (z > z1) z1 = z
        }
        val o = i * 6
        if (m.vertexCount == 0) { x0 = 0f; y0 = 0f; z0 = 0f; x1 = 0f; y1 = 0f; z1 = 0f }
        out[o] = x0; out[o + 1] = y0; out[o + 2] = z0; out[o + 3] = x1; out[o + 4] = y1; out[o + 5] = z1
    }
    return out
}

/**
 * The opaque, non-animated shadow casters of a chunk merged into one position-only mesh (anchor-relative,
 * like the source meshes) so the shadow pass draws a whole tile of buildings, walls and props with one
 * call instead of one per material. [merged] marks the chunk meshes it replaces in the shadow pass.
 */
internal class ShadowMesh(
    val positions: FloatArray, val vertexCount: Int, val indices: IntArray, val indexCount: Int,
    val bounds: FloatArray, val merged: BooleanArray,
)

/** Builds the [ShadowMesh] of [c], or null when fewer than two meshes would be merged. Worker thread. */
internal fun shadowMerge(c: SceneryChunk): ShadowMesh? {
    val merged = BooleanArray(c.meshes.size)
    var nv = 0; var ni = 0; var n = 0
    for ((i, pair) in c.meshes.withIndex()) {
        val m = pair.first; val mesh = pair.second
        if (!m.castsShadow || m.alphaCutout || m.anim != Anim.NONE || mesh.indexCount == 0) continue
        merged[i] = true; nv += mesh.vertexCount; ni += mesh.indexCount; n++
    }
    if (n < 2) return null
    val pos = FloatArray(nv * 3)
    val idx = IntArray(ni)
    val b = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
    var vo = 0; var io = 0
    for ((i, pair) in c.meshes.withIndex()) {
        if (!merged[i]) continue
        val mesh = pair.second
        val v = mesh.vertices
        for (k in 0 until mesh.vertexCount) {
            val o = k * Vtx.FLOATS
            for (a in 0 until 3) {
                val x = v[o + Vtx.POS + a]
                pos[(vo + k) * 3 + a] = x
                if (x < b[a]) b[a] = x
                if (x > b[a + 3]) b[a + 3] = x
            }
        }
        for (k in 0 until mesh.indexCount) idx[io + k] = mesh.indices[k] + vo
        vo += mesh.vertexCount; io += mesh.indexCount
    }
    return ShadowMesh(pos, nv, idx, ni, b, merged)
}

/** A [SceneMaterial] with its textures on the GPU. */
internal class MaterialGpu(val m: SceneMaterial) {
    var albedo = 0
    var normal = 0
    var uvScaleU = 1f
    var uvScaleV = 1f
    val anim: Int = when (m.anim) { Anim.NONE -> 0; Anim.WIND -> 1; Anim.CHEER -> 2; Anim.FLAG -> 3 }
    val texFlags: Int get() = (if (albedo != 0) 1 else 0) or (if (normal != 0) 2 else 0)
    /** Atlas cells by variant; FLAG materials without their own use the 8 x 4 flag atlas of the contract. */
    val cells: VariantCells? = m.variantCells ?: if (m.anim == Anim.FLAG) VariantCells(8, 0.125f, 0.25f) else null
    /** W3's road paint and finish lines predate [SceneMaterial.decal]; they lie on the road all the same. */
    val decal: Boolean = m.decal || m.key == "race_roadpaint" || m.key == "race_line"
}

/** One drawable part of a prototype LOD. */
internal class PartGpu(val material: MaterialGpu, val mesh: MeshGpu)

/** A prototype on the GPU: parts per LOD, and the instance buffer of each LOD (filled by the assembler). */
internal class ProtoGpu(val proto: Prototype, val lods: List<List<PartGpu>>) {
    private val streams = Array(lods.size) { StreamBuffer(2) }
    private val shadowStream = StreamBuffer(2)
    private val shadowFarStream = StreamBuffer(2)
    val counts = IntArray(lods.size)
    val nearCounts = IntArray(lods.size)
    /**
     * The LOD whose mesh draws all shadow casters: the second one where there is one, else the first
     * non-empty one after it (or before it, for a prototype whose last LODs are empty).
     */
    val shadowLod: Int = run {
        val from = minOf(1, lods.size - 1)
        (from until lods.size).firstOrNull { lods[it].isNotEmpty() } ?: (from downTo 0).firstOrNull { lods[it].isNotEmpty() } ?: from
    }
    var shadowCount = 0
        private set
    /**
     * The coarse shadow mesh for casters away from the camera: the last LOD that casts shadows when it is
     * coarser than [shadowLod] (a tree's impostor), else -1 (all casters use [shadowLod]).
     */
    val shadowFarLod: Int = lods.indices.lastOrNull { l -> lods[l].any { it.material.m.castsShadow } }?.takeIf { it > shadowLod } ?: -1
    var shadowFarCount = 0
        private set
    val bytes: Int = lods.sumOf { l -> l.sumOf { it.mesh.bytes } }
    val instanceBytes: Int get() = streams.sumOf { it.bytes } + shadowStream.bytes + shadowFarStream.bytes

    init {
        for ((i, parts) in lods.withIndex()) for (p in parts) p.mesh.attachInstances(streams[i].current, 2)
    }

    fun upload(lod: Int, data: FloatArray, count: Int, near: Int) {
        val buf = streams[lod].upload(data, count * InstanceSet.FLOATS)
        // Index loops: the race vehicles upload every frame on the GL thread.
        val parts = lods[lod]
        for (i in 0 until parts.size) parts[i].mesh.attachInstances(buf, 2)
        counts[lod] = count
        nearCounts[lod] = near
    }

    fun uploadShadow(data: FloatArray, count: Int, far: Boolean = false) {
        if (far && shadowFarLod >= 0) {
            val buf = shadowFarStream.upload(data, count * InstanceSet.FLOATS)
            val parts = lods[shadowFarLod]
            for (i in 0 until parts.size) parts[i].mesh.attachShadowInstances(buf, 2)
            shadowFarCount = count
        } else {
            val buf = shadowStream.upload(data, count * InstanceSet.FLOATS)
            val parts = lods[shadowLod]
            for (i in 0 until parts.size) parts[i].mesh.attachShadowInstances(buf, 2)
            shadowCount = count
        }
    }

    fun clear() { counts.fill(0); nearCounts.fill(0); shadowCount = 0; shadowFarCount = 0 }

    fun delete() {
        for (l in lods) for (p in l) p.mesh.delete()
        for (s in streams) s.delete()
        shadowStream.delete()
        shadowFarStream.delete()
    }
}
