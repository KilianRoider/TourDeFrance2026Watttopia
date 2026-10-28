package app.rollentrainer.ui.world.geo

import kotlin.math.PI
import kotlin.math.floor

/**
 * The riders on the GPU: every rigid part of a [RiderModelSet] (body, wheel, each leg pose, per LOD)
 * is one instanced draw. Per frame the renderer adds the riders it sees ([add]); each rider becomes
 * one body instance, two wheel instances and one instance of the leg pose nearest its crank angle,
 * all with 12 floats: A (x, y, z relative to the camera, yaw), B (pitch, lean, kit, spin),
 * C (hub x, y, z, scale). Arrays and buffers grow but are never reallocated per frame.
 */
internal class RiderGpu(seated: RiderModelSet, standing: RiderModelSet?, materialOf: (SceneMaterial) -> MaterialGpu) {
    private class Group(val parts: List<PartGpu>) {
        val stream = StreamBuffer(3)
        var data = FloatArray(12 * 8)
        var count = 0

        init { for (p in parts) p.mesh.attachInstances(stream.current, 3) }

        fun put(x: Float, y: Float, z: Float, yaw: Float, pitch: Float, lean: Float, kit: Float, spin: Float, hx: Float, hy: Float, hz: Float) {
            if ((count + 1) * 12 > data.size) data = data.copyOf(data.size * 2)
            val o = count * 12
            data[o] = x; data[o + 1] = y; data[o + 2] = z; data[o + 3] = yaw
            data[o + 4] = pitch; data[o + 5] = lean; data[o + 6] = kit; data[o + 7] = spin
            data[o + 8] = hx; data[o + 9] = hy; data[o + 10] = hz; data[o + 11] = 1f
            count++
        }

        fun upload() {
            if (count == 0) return
            val buf = stream.upload(data, count * 12)
            for (i in 0 until parts.size) parts[i].mesh.attachInstances(buf, 3)
        }

        fun delete() {
            for (p in parts) p.mesh.delete()
            stream.delete()
        }
    }

    private class LodGpu(lod: RiderLod, materialOf: (SceneMaterial) -> MaterialGpu) {
        val maxDistance = lod.maxDistanceM
        val body = Group(lod.body.map { (m, mesh) -> PartGpu(materialOf(m), MeshGpu(mesh)) })
        val bodyStanding = lod.bodyStanding?.let { l -> Group(l.map { (m, mesh) -> PartGpu(materialOf(m), MeshGpu(mesh)) }) }
        val wheel = Group(lod.wheel.map { (m, mesh) -> PartGpu(materialOf(m), MeshGpu(mesh)) })
        val legs = lod.legPoses.map { pose -> Group(pose.map { (m, mesh) -> PartGpu(materialOf(m), MeshGpu(mesh)) }) }
        val front = lod.frontHub
        val rear = lod.rearHub
        val groups: List<Group> = listOfNotNull(body, bodyStanding, wheel) + legs
    }

    private val seatedLods = seated.lods.map { LodGpu(it, materialOf) }
    private val standingLods = standing?.lods?.map { LodGpu(it, materialOf) }
    private val all = seatedLods + (standingLods ?: emptyList())
    /** Riders added this frame (for the stats). */
    var riders = 0
        private set

    // Index loops below: these run every frame on the GL thread, where list iterators would be garbage.
    fun begin() {
        riders = 0
        for (li in 0 until all.size) { val gs = all[li].groups; for (gi in 0 until gs.size) gs[gi].count = 0 }
    }

    /**
     * One rider at camera-relative ([x], [y], [z]) (contact point under the bottom bracket), [yaw] as for
     * instances, [pitch] up the grade and [lean] (radians, + left), crank and wheel angles in radians;
     * [distance] from the camera picks the LOD.
     */
    fun add(
        x: Double, y: Double, z: Double, yaw: Float, pitch: Float, lean: Float, kit: Int,
        crank: Double, wheel: Double, standing: Float, distance: Double,
    ) {
        val sets = if (standing > 0.5f && standingLods != null) standingLods else seatedLods
        var li = 0
        while (li < sets.size - 1 && distance > sets[li].maxDistance) li++
        if (distance > sets[li].maxDistance * 1.5) return
        val l = sets[li]
        val fx = x.toFloat(); val fy = y.toFloat(); val fz = z.toFloat(); val k = kit.toFloat()
        val bodyGroup = if (standing > 0.5f && l.bodyStanding != null) l.bodyStanding else l.body
        bodyGroup.put(fx, fy, fz, yaw, pitch, lean, k, 0f, 0f, 0f, 0f)
        // Wheel angle wrapped so the float keeps its precision over a long ride.
        val w = (wheel - floor(wheel / TWO_PI) * TWO_PI).toFloat()
        l.wheel.put(fx, fy, fz, yaw, pitch, lean, k, w, l.front[0], l.front[1], l.front[2])
        l.wheel.put(fx, fy, fz, yaw, pitch, lean, k, w, l.rear[0], l.rear[1], l.rear[2])
        val n = l.legs.size
        if (n > 0) {
            val c = crank - floor(crank / TWO_PI) * TWO_PI
            val pose = ((c / TWO_PI * n + 0.5).toInt()) % n
            l.legs[pose].put(fx, fy, fz, yaw, pitch, lean, k, 0f, 0f, 0f, 0f)
        }
        riders++
    }

    fun upload() {
        for (li in 0 until all.size) { val gs = all[li].groups; for (gi in 0 until gs.size) gs[gi].upload() }
    }

    /** Draws every non-empty group; [bind] sets the part's material first. */
    fun draw(bind: (MaterialGpu) -> Unit) {
        for (li in 0 until all.size) {
            val gs = all[li].groups
            for (gi in 0 until gs.size) {
                val g = gs[gi]
                if (g.count == 0) continue
                for (pi in 0 until g.parts.size) {
                    val p = g.parts[pi]
                    bind(p.material)
                    p.mesh.drawInstanced(g.count)
                }
            }
        }
    }

    /** Triangles drawn by the last [draw] (for the stats). */
    fun triangles(): Long {
        var t = 0L
        for (li in 0 until all.size) {
            val gs = all[li].groups
            for (gi in 0 until gs.size) { val g = gs[gi]; if (g.count > 0) for (pi in 0 until g.parts.size) t += g.parts[pi].mesh.indexCount / 3L * g.count }
        }
        return t
    }

    fun delete() {
        for (l in all) for (g in l.groups) g.delete()
    }

    private companion object {
        const val TWO_PI = 2 * PI
    }
}
