package app.rollentrainer.ui.world.geo

import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

/**
 * The material textures of the terrain and the road (assets/tex, made by work package T): albedo
 * (sRGB) + "_n" tangent-space normal (OpenGL +Y) + "_m" (R cavity AO, G roughness, B height). They are
 * repacked into two texture arrays per set: albedo RGB + height in A, and normal XY + AO + roughness,
 * so a layer costs two samples. Real-world sizes come from T's metadata (scratchpad/textures/meta).
 */
internal object Materials {
    /** Ground detail layers in array order; the terrain shader indexes them through [GroundRules]. */
    val GROUND = listOf(
        "grass_meadow", "grass_alpine", "grass_dry", "cereal_green", "wheat_ripe", "field_ploughed", "maize_rows",
        "vineyard_soil", "forest_floor", "pine_needles", "soil_dirt", "mud_wetland", "scree", "rock_limestone",
        "rock_granite", "snow", "gravel_shoulder", "pavers_stone", "sand_beach", "kerb_concrete",
    )

    /** Road surfaces that need more resolution than the ground. */
    val ROAD = listOf("asphalt_fresh", "asphalt_polish", "asphalt_worn", "cobbles_pave")

    /** Width and height of one texture repeat in metres (from T's metadata). */
    private val SIZE = mapOf(
        "asphalt_fresh" to (2.0f to 2.0f), "asphalt_polish" to (2.0f to 2.0f), "asphalt_worn" to (2.0f to 2.0f),
        "cereal_green" to (2.0f to 2.0f), "cobbles_pave" to (2.0f to 2.0f), "field_ploughed" to (4.0f to 4.0f),
        "forest_floor" to (2.5f to 2.5f), "grass_alpine" to (2.0f to 2.0f), "grass_dry" to (2.0f to 2.0f),
        "grass_meadow" to (2.0f to 2.0f), "gravel_shoulder" to (1.5f to 1.5f), "kerb_concrete" to (2.0f to 0.5f),
        "maize_rows" to (3.0f to 3.0f), "mud_wetland" to (2.0f to 2.0f), "pavers_stone" to (2.4f to 2.4f),
        "pine_needles" to (1.5f to 1.5f), "rock_granite" to (3.0f to 3.0f), "rock_limestone" to (3.0f to 3.0f),
        "rock_schist" to (3.0f to 3.0f), "sand_beach" to (1.5f to 1.5f), "scree" to (2.0f to 2.0f), "snow" to (2.0f to 2.0f),
        "soil_dirt" to (2.0f to 2.0f), "vineyard_soil" to (2.0f to 2.0f), "wheat_ripe" to (2.0f to 2.0f),
        "bark_oak" to (1.0f to 1.0f), "facade_barn" to (6.0f to 3.0f), "facade_basque" to (6.0f to 3.0f),
        "facade_chalet" to (6.0f to 3.0f), "facade_church" to (4.0f to 8.0f), "facade_eixample" to (6.0f to 3.3f),
        "facade_haussmann" to (6.0f to 3.4f), "facade_modern" to (6.0f to 3.0f), "facade_plaster_cream" to (6.0f to 3.0f),
        "facade_plaster_ochre" to (6.0f to 3.0f), "facade_pyrenean" to (6.0f to 3.0f), "facade_shop" to (6.0f to 3.5f),
        "facade_stone_village" to (6.0f to 3.0f), "roof_flat_tiles" to (2.5f to 2.5f), "roof_roman_tiles" to (3.0f to 3.0f),
        "roof_slate" to (3.0f to 3.0f), "roof_zinc" to (3.0f to 3.0f),
    )

    fun sizeOf(name: String): Pair<Float, Float> = SIZE[name] ?: (2f to 2f)
    fun ground(name: String): Int = GROUND.indexOf(name)

    /** sRGB 0..255 to linear 0..1. */
    private val LIN = FloatArray(256) { i -> val c = i / 255f; if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f) }

    /**
     * One repacked layer: [albedo] RGBA (sRGB rgb + height), [normal] RGBA (normal xy, AO, roughness),
     * both [size] x [size], and the mean linear albedo (the terrain tints detail to the satellite colour).
     */
    class Layer(val name: String, val size: Int, val albedo: ByteBuffer, val normal: ByteBuffer, val mean: FloatArray, val normalSize: Int = size)

    /**
     * Decodes and repacks a material at [size] x [size] (worker thread). Missing companions get neutral
     * values. The normal/AO/roughness map comes out at [normalSize] (a box filter down from [size]): its
     * detail matters only a few metres from the camera, so it may be smaller than the albedo.
     */
    fun decode(assets: AssetManager, name: String, size: Int, normalSize: Int = size): Layer {
        val a = bitmap(assets, "tex/$name.webp", size) ?: error("texture $name missing")
        val n = bitmap(assets, "tex/${name}_n.webp", size)
        val m = bitmap(assets, "tex/${name}_m.webp", size)
        val count = size * size
        val ap = IntArray(count); a.getPixels(ap, 0, size, 0, 0, size, size)
        val np = n?.let { IntArray(count).also { p -> it.getPixels(p, 0, size, 0, 0, size, size) } }
        val mp = m?.let { IntArray(count).also { p -> it.getPixels(p, 0, size, 0, 0, size, size) } }
        a.recycle(); n?.recycle(); m?.recycle()
        val albedo = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        val normal = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        var sr = 0.0; var sg = 0.0; var sb = 0.0
        for (i in 0 until count) {
            val c = ap[i]
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            sr += LIN[r]; sg += LIN[g]; sb += LIN[b]
            val mm = mp?.get(i) ?: 0x00FFC880.toInt()
            albedo.put(r.toByte()); albedo.put(g.toByte()); albedo.put(b.toByte()); albedo.put((mm and 0xFF).toByte())
            val nn = np?.get(i) ?: 0xFF8080FF.toInt()
            normal.put(((nn shr 16) and 0xFF).toByte()); normal.put(((nn shr 8) and 0xFF).toByte())
            normal.put(((mm shr 16) and 0xFF).toByte()); normal.put(((mm shr 8) and 0xFF).toByte())
        }
        albedo.position(0); normal.position(0)
        val mean = floatArrayOf((sr / count).toFloat(), (sg / count).toFloat(), (sb / count).toFloat())
        if (normalSize >= size) return Layer(name, size, albedo, normal, mean)
        return Layer(name, size, albedo, boxDown(normal, size, normalSize), mean, normalSize)
    }

    /**
     * A plain layer standing in for a texture that failed to decode: mid grey, flat, rough. The terrain
     * and road shaders tint detail by the layer's mean, so the satellite colour shows through unchanged
     * and one missing asset costs some close-up detail instead of the whole geodata world.
     */
    fun flat(name: String, size: Int, normalSize: Int = size): Layer {
        val albedo = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until size * size) { albedo.put(0x80.toByte()); albedo.put(0x80.toByte()); albedo.put(0x80.toByte()); albedo.put(0x80.toByte()) }
        val normal = ByteBuffer.allocateDirect(normalSize * normalSize * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until normalSize * normalSize) { normal.put(0x80.toByte()); normal.put(0x80.toByte()); normal.put(-1); normal.put(0xC8.toByte()) }
        albedo.position(0); normal.position(0)
        val g = LIN[0x80]
        return Layer(name, size, albedo, normal, floatArrayOf(g, g, g), normalSize)
    }

    /** Box-filters square RGBA8 pixels from [size] down to [out] (a power-of-two fraction of it). */
    private fun boxDown(src: ByteBuffer, size: Int, out: Int): ByteBuffer {
        val f = size / out
        val dst = ByteBuffer.allocateDirect(out * out * 4).order(ByteOrder.nativeOrder())
        val inv = 1f / (f * f)
        for (y in 0 until out) for (x in 0 until out) {
            for (c in 0 until 4) {
                var s = 0
                for (j in 0 until f) for (i in 0 until f) s += src.get(((y * f + j) * size + x * f + i) * 4 + c).toInt() and 0xFF
                dst.put(((y * out + x) * 4) + c, (s * inv + 0.5f).toInt().toByte())
            }
        }
        return dst
    }

    /**
     * An object texture: albedo RGBA with its own alpha (cutouts), and the normal map if there is one.
     * [albedoMips]: levels 1 .. 1x1 of an alpha-tested albedo built with [coverageMips], or null for
     * plain hardware mipmaps.
     */
    class ObjectTex(
        val name: String, val width: Int, val height: Int, val albedo: ByteBuffer, val normal: ByteBuffer?,
        val albedoMips: List<ByteBuffer>? = null,
    )

    /**
     * Decodes an object texture (facades, bark, atlases of flags and signs) keeping its aspect and alpha,
     * at most [maxSize] on the longer side (worker thread). Normal + _m are packed like the arrays.
     */
    fun decodeObject(assets: AssetManager, name: String, maxSize: Int): ObjectTex {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        assets.open("tex/$name.webp").use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) error("texture $name unreadable")
        val scale = minOf(1f, maxSize.toFloat() / maxOf(bounds.outWidth, bounds.outHeight))
        // Power-of-two sizes so mipmaps and REPEAT behave the same on every GPU.
        val w = pot((bounds.outWidth * scale).toInt())
        val h = pot((bounds.outHeight * scale).toInt())
        val a = bitmap(assets, "tex/$name.webp", w, h) ?: error("texture $name missing")
        val n = bitmap(assets, "tex/${name}_n.webp", w, h)
        val m = bitmap(assets, "tex/${name}_m.webp", w, h)
        val count = w * h
        val ap = IntArray(count); a.getPixels(ap, 0, w, 0, 0, w, h)
        val np = n?.let { IntArray(count).also { p -> it.getPixels(p, 0, w, 0, 0, w, h) } }
        val mp = m?.let { IntArray(count).also { p -> it.getPixels(p, 0, w, 0, 0, w, h) } }
        a.recycle(); n?.recycle(); m?.recycle()
        val albedo = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        for (c in ap) {
            albedo.put(((c shr 16) and 0xFF).toByte()); albedo.put(((c shr 8) and 0xFF).toByte())
            albedo.put((c and 0xFF).toByte()); albedo.put(((c ushr 24) and 0xFF).toByte())
        }
        albedo.position(0)
        var normal: ByteBuffer? = null
        if (np != null) {
            val nb = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
            for (i in 0 until count) {
                val nn = np[i]
                val mm = mp?.get(i) ?: 0x00FFC880.toInt()
                nb.put(((nn shr 16) and 0xFF).toByte()); nb.put(((nn shr 8) and 0xFF).toByte())
                nb.put(((mm shr 16) and 0xFF).toByte()); nb.put(((mm shr 8) and 0xFF).toByte())
            }
            nb.position(0)
            normal = nb
        }
        return ObjectTex(name, w, h, albedo, normal, coverageMips(albedo, w, h))
    }

    private val SRGB = ByteArray(4096) { i ->
        val c = i / 4095f
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        (s * 255f + 0.5f).toInt().coerceIn(0, 255).toByte()
    }

    /**
     * Mip levels 1 .. 1x1 for an alpha-tested texture (leaf cards, sign cut-outs), or null when the
     * texture is opaque. Box-filtered mipmaps average the alpha of thin leaves and twigs below the
     * cut-off, so crowns thin out into sparse dark dots a few tens of metres away; here every level
     * keeps the share of texels above the cut-off that the full-size texture has (alpha rescaled per
     * level), and the colour is averaged in linear light weighted by alpha. Worker thread.
     */
    fun coverageMips(px: ByteBuffer, w: Int, h: Int, cutoff: Int = 128): List<ByteBuffer>? {
        val n0 = w * h
        var above = 0; var partial = 0
        for (i in 0 until n0) {
            val a = px.get(i * 4 + 3).toInt() and 0xFF
            if (a >= cutoff) above++
            if (a < 250) partial++
        }
        val c0 = above.toFloat() / n0
        if (partial < n0 / 50 || c0 < 0.005f) return null
        val out = ArrayList<ByteBuffer>()
        var src = ByteArray(n0 * 4).also { a -> for (i in 0 until n0 * 4) a[i] = px.get(i) }
        var cw = w; var ch = h
        val hist = IntArray(256)
        while (cw > 1 || ch > 1) {
            val nw = maxOf(1, cw / 2); val nh = maxOf(1, ch / 2)
            val dst = ByteArray(nw * nh * 4)
            hist.fill(0)
            for (y in 0 until nh) for (x in 0 until nw) {
                var r = 0f; var g = 0f; var b = 0f; var asum = 0f; var wsum = 0f
                for (j in 0 until 2) for (i in 0 until 2) {
                    val sx = minOf(cw - 1, x * 2 + i); val sy = minOf(ch - 1, y * 2 + j)
                    val o = (sy * cw + sx) * 4
                    val a = (src[o + 3].toInt() and 0xFF) / 255f
                    val wt = a + 1e-3f
                    r += LIN[src[o].toInt() and 0xFF] * wt; g += LIN[src[o + 1].toInt() and 0xFF] * wt; b += LIN[src[o + 2].toInt() and 0xFF] * wt
                    wsum += wt; asum += a
                }
                val o = (y * nw + x) * 4
                dst[o] = SRGB[(r / wsum * 4095f).toInt().coerceIn(0, 4095)]
                dst[o + 1] = SRGB[(g / wsum * 4095f).toInt().coerceIn(0, 4095)]
                dst[o + 2] = SRGB[(b / wsum * 4095f).toInt().coerceIn(0, 4095)]
                val a8 = (asum / 4f * 255f + 0.5f).toInt().coerceIn(0, 255)
                dst[o + 3] = a8.toByte()
                hist[a8]++
            }
            // The alpha above which the level has the full-size texture's coverage, scaled to the cut-off.
            val target = (c0 * nw * nh).toInt()
            var acc = 0; var t = 255
            while (t > 1 && acc + hist[t] < target) { acc += hist[t]; t-- }
            val scale = (cutoff.toFloat() / t).coerceIn(1f, 4f)
            if (scale > 1.001f) for (i in 0 until nw * nh) {
                val o = i * 4 + 3
                dst[o] = ((dst[o].toInt() and 0xFF) * scale + 0.5f).toInt().coerceAtMost(255).toByte()
            }
            out += ByteBuffer.allocateDirect(dst.size).order(ByteOrder.nativeOrder()).also { it.put(dst); it.position(0) }
            src = dst; cw = nw; ch = nh
        }
        return out
    }

    private fun pot(v: Int): Int {
        var p = 4
        while (p * 2 <= v.coerceAtLeast(4)) p *= 2
        // Round to the nearer power of two.
        return if (v - p > p * 2 - v) p * 2 else p
    }

    /**
     * Decodes an asset scaled to exactly [size] x [size]: subsampled while decoding (cheap, less memory),
     * then filtered to the final size (non-square textures such as the kerb are stretched; the shader
     * scales their UVs back).
     */
    fun bitmap(assets: AssetManager, path: String, size: Int, height: Int = size): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            assets.open(path).use { BitmapFactory.decodeStream(it, null, bounds) }
        } catch (e: java.io.IOException) {
            return null
        }
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= size && bounds.outHeight / (sample * 2) >= height) sample *= 2
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inScaled = false
            inPremultiplied = false
        }
        val b = assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
        if (b.width == size && b.height == height) return b
        val s = Bitmap.createScaledBitmap(b, size, height, true)
        if (s !== b) b.recycle()
        return s
    }

    /** Decodes a satellite tile (JPEG) to RGBA bytes; returns the buffer and its edge length. */
    fun decodeImage(bytes: ByteArray): Pair<ByteBuffer, Int>? {
        val opts = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888; inScaled = false }
        val b = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return null
        val w = b.width
        val h = b.height
        val buf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        b.copyPixelsToBuffer(buf)
        buf.position(0)
        b.recycle()
        if (w != h) return null
        return buf to w
    }
}

/**
 * How land cover classes map to ground layers for a region: what grows on meadows, in forests, on
 * bare ground, and from which altitude the grass turns alpine and the bare rock to snow.
 */
internal class GroundRules(region: RegionStyle) {
    val classLayer = IntArray(16)
    val alpineFromM: Float
    val snowFromM: Float
    val rockLayer: Int
    val forestLayer: Int
    val alpineLayer = Materials.ground("grass_alpine")
    val snowLayer = Materials.ground("snow")
    val gravelLayer = Materials.ground("gravel_shoulder")
    val soilLayer = Materials.ground("soil_dirt")
    val cropLayers = intArrayOf(Materials.ground("wheat_ripe"), Materials.ground("field_ploughed"), Materials.ground("cereal_green"), Materials.ground("maize_rows"))
    /** Asphalt array layer for the region's roads: fresh (0), polished (1), worn (2). */
    val asphaltLayer: Int
    /** Satellite brightness to albedo. */
    val satGain: Float
    /**
     * Mean albedo of the road surface: the asphalt textures are photographed anywhere from 0.02 (fresh,
     * black) to 0.09 (worn), but a Tour road in the sun reads as mid grey on TV, a little lighter on the
     * old mountain roads. The shader scales every asphalt layer to this mean.
     */
    val asphaltAlbedo: Float

    init {
        val south = region == RegionStyle.MEDITERRANEAN || region == RegionStyle.CATALONIA || region == RegionStyle.SOUTH_WEST
        val mountains = region == RegionStyle.ALPS || region == RegionStyle.PYRENEES || region == RegionStyle.JURA_VOSGES || region == RegionStyle.MASSIF_CENTRAL
        val meadow = Materials.ground(if (south) "grass_dry" else "grass_meadow")
        forestLayer = Materials.ground(if (mountains) "pine_needles" else "forest_floor")
        rockLayer = Materials.ground(if (region == RegionStyle.ALPS || region == RegionStyle.PYRENEES) "rock_granite" else "rock_limestone")
        for (i in 0 until 16) classLayer[i] = meadow
        classLayer[1] = forestLayer
        classLayer[2] = Materials.ground(if (south) "grass_dry" else if (mountains) "grass_alpine" else "grass_meadow")
        classLayer[3] = meadow
        classLayer[4] = Materials.ground("cereal_green")
        classLayer[5] = Materials.ground(if (region == RegionStyle.PARIS || region == RegionStyle.CATALONIA) "pavers_stone" else "soil_dirt")
        classLayer[6] = Materials.ground(if (mountains) "scree" else "soil_dirt")
        classLayer[7] = snowLayer
        classLayer[8] = meadow
        classLayer[9] = Materials.ground("mud_wetland")
        classLayer[10] = Materials.ground("mud_wetland")
        classLayer[11] = alpineLayer
        classLayer[12] = Materials.ground("vineyard_soil")
        classLayer[13] = Materials.ground("grass_meadow")
        // Road cells: the 10 m land cover marks the road and its verges; the route and the side roads are
        // drawn as meshes on top, so what shows of these cells is the grass verge.
        classLayer[14] = meadow
        alpineFromM = when (region) { RegionStyle.ALPS -> 1650f; RegionStyle.PYRENEES -> 1750f; else -> 1500f }
        snowFromM = when (region) { RegionStyle.ALPS -> 2850f; RegionStyle.PYRENEES -> 2900f; else -> 9000f }
        asphaltLayer = when (region) {
            RegionStyle.ALPS, RegionStyle.PYRENEES -> 1
            RegionStyle.PARIS, RegionStyle.CATALONIA -> 0
            RegionStyle.BRITTANY, RegionStyle.NORTH, RegionStyle.MASSIF_CENTRAL -> 2
            else -> 1
        }
        satGain = 1.0f
        asphaltAlbedo = when (region) {
            RegionStyle.ALPS, RegionStyle.PYRENEES -> 0.095f
            RegionStyle.PARIS -> 0.065f
            RegionStyle.MEDITERRANEAN, RegionStyle.CATALONIA, RegionStyle.SOUTH_WEST -> 0.09f
            else -> 0.08f
        }
    }
}
