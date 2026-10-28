package app.rollentrainer.ui.world.geo

import app.rollentrainer.core.geo.GeoPack
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Colour grade for a pack's satellite imagery. Sentinel-2 style summer composites are hazy: the
 * darkest forest sits at sRGB ~0.22 instead of ~0.05 and the meadows look grey-beige, nothing like the
 * saturated greens a broadcast camera shows. The grade lifts the haze with a neutral black point (the
 * 0.5th percentile of the darkest channel; a per-channel black point turns forests brown), stretches
 * the contrast (capped, packs that are already clean stay as they are) and adds a little saturation.
 * One grade per pack, measured from a sample of tiles, so neighbouring tiles and LODs match exactly.
 *
 * Pure black pixels are holes in the imagery (masked clouds); they get the tile's mean colour.
 */
internal class SatGrade(private val black: FloatArray, private val gain: Float, private val saturation: Float) {
    /** Levels as a lookup per channel (sRGB byte in, sRGB 0..1 out before saturation). */
    private val lut = Array(3) { c -> FloatArray(256) { v -> ((v / 255f - black[c]) * gain).coerceIn(0f, 1f) } }

    /** Grades RGBA pixels in place. */
    fun apply(px: ByteBuffer, count: Int) {
        var sr = 0f; var sg = 0f; var sb = 0f; var valid = 0
        var holes = 0
        val lr = lut[0]; val lg = lut[1]; val lb = lut[2]
        for (i in 0 until count) {
            val o = i * 4
            val r = px.get(o).toInt() and 0xFF
            val g = px.get(o + 1).toInt() and 0xFF
            val b = px.get(o + 2).toInt() and 0xFF
            if (r < HOLE && g < HOLE && b < HOLE) { holes++; px.put(o + 3, 0); continue }
            var fr = lr[r]; var fg = lg[g]; var fb = lb[b]
            val l = fr * 0.3f + fg * 0.59f + fb * 0.11f
            fr = (l + (fr - l) * saturation).coerceIn(0f, 1f)
            fg = (l + (fg - l) * saturation).coerceIn(0f, 1f)
            fb = (l + (fb - l) * saturation).coerceIn(0f, 1f)
            sr += fr; sg += fg; sb += fb; valid++
            px.put(o, (fr * 255f + 0.5f).toInt().toByte())
            px.put(o + 1, (fg * 255f + 0.5f).toInt().toByte())
            px.put(o + 2, (fb * 255f + 0.5f).toInt().toByte())
        }
        if (holes == 0) return
        val mr: Byte; val mg: Byte; val mb: Byte
        if (valid > 0) {
            mr = (sr / valid * 255f).toInt().toByte(); mg = (sg / valid * 255f).toInt().toByte(); mb = (sb / valid * 255f).toInt().toByte()
        } else {
            mr = 120.toByte(); mg = 116.toByte(); mb = 104.toByte()
        }
        for (i in 0 until count) {
            val o = i * 4
            if (px.get(o + 3).toInt() == 0) { px.put(o, mr); px.put(o + 1, mg); px.put(o + 2, mb); px.put(o + 3, -1) }
        }
    }

    companion object {
        private const val HOLE = 9
        val NONE = SatGrade(floatArrayOf(0f, 0f, 0f), 1f, 1f)

        /** Measures the grade from up to [samples] LOD-1 tiles spread over the pack (worker thread). */
        fun measure(pack: GeoPack, samples: Int = 20): SatGrade {
            val keys = pack.tiles(app.rollentrainer.core.geo.GeoLayer.IMAGE, 1).ifEmpty { pack.tiles(app.rollentrainer.core.geo.GeoLayer.IMAGE, 0) }
            if (keys.isEmpty()) return NONE
            val step = max(1, keys.size / samples)
            val minHist = IntArray(256)
            val maxHist = IntArray(256)
            var n = 0L
            var i = 0
            while (i < keys.size) {
                val img = pack.image(keys[i])?.let { Materials.decodeImage(it.bytes) }
                i += step
                if (img == null) continue
                val (buf, size) = img
                for (p in 0 until size * size) {
                    val o = p * 4
                    val r = buf.get(o).toInt() and 0xFF
                    val g = buf.get(o + 1).toInt() and 0xFF
                    val b = buf.get(o + 2).toInt() and 0xFF
                    val lo = min(r, min(g, b))
                    val hi = max(r, max(g, b))
                    // Holes and blown-out snow/cloud say nothing about the haze.
                    if (hi < 16 || lo > 244) continue
                    minHist[lo]++; maxHist[hi]++; n++
                }
            }
            if (n < 1000) return NONE
            fun percentile(h: IntArray, q: Double): Float {
                val target = (n * q).toLong()
                var acc = 0L
                for (v in 0 until 256) { acc += h[v]; if (acc >= target) return v / 255f }
                return 1f
            }
            val m = percentile(minHist, 0.005) * 0.85f
            val white = percentile(maxHist, 0.99)
            val gain = (1f / (white - m).coerceAtLeast(0.2f)).coerceIn(1f, 1.35f)
            // Haze is a little bluer than neutral.
            return SatGrade(floatArrayOf(m, m, m * 1.05f), gain, 1.15f)
        }
    }
}
