package app.rollentrainer.core.geo

import app.rollentrainer.core.route.RoutePoint
import kotlinx.serialization.json.Json
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Reader of a route pack (.wwgeo, format in tools/geodata/FORMAT.md). Opening reads only the
 * header, the directory and the small metadata; the path, places and every tile are read and
 * decoded on demand, so a renderer can stream the world section by section. Thread-safe as long as
 * the [ByteSource] is; nothing is cached here except the path and places.
 */
class GeoPack private constructor(private val src: ByteSource, private val dir: Map<ChunkKey, Entry>) : AutoCloseable {
    private data class ChunkKey(val type: Int, val lod: Int, val tx: Int, val ty: Int)
    private class Entry(val codec: Int, val offset: Long, val length: Int, val rawLength: Int, val crc: Int)

    val meta: GeoMeta = JSON.decodeFromString(GeoMeta.serializer(), String(chunk(T_META, 0, 0, 0)!!, Charsets.UTF_8))

    val path: RoutePath by lazy { decodePath(chunk(T_PATH, 0, 0, 0) ?: error("pack without path")) }

    val places: PlacesData by lazy {
        chunk(T_PLACES, 0, 0, 0)?.let { JSON.decodeFromString(PlacesData.serializer(), String(it, Charsets.UTF_8)) } ?: PlacesData()
    }

    val id: String get() = meta.id
    val lengthM: Double get() = meta.lengthM

    fun lod(l: Int): LodInfo = meta.lods.first { it.lod == l }

    /** The real elevation profile of the route for the trainer and the profile view. */
    fun profile(stepM: Double = 20.0): List<RoutePoint> = path.profile(stepM)

    fun has(layer: GeoLayer, key: TileKey): Boolean = dir.containsKey(ChunkKey(layer.code, key.lod, key.tx, key.ty))

    fun tiles(layer: GeoLayer, lod: Int): List<TileKey> =
        dir.keys.filter { it.type == layer.code && it.lod == lod }.map { TileKey(it.lod, it.tx, it.ty) }.sortedWith(compareBy({ it.tx }, { it.ty }))

    /** South-west corner of a tile in route coordinates. */
    fun tileOrigin(key: TileKey): Pair<Double, Double> {
        val t = lod(key.lod).tileM
        return key.tx * t to key.ty * t
    }

    /** Tiles of [layer] at [lod] whose square comes within [radiusM] of (x, y). */
    fun tilesNear(x: Double, y: Double, radiusM: Double, lod: Int, layer: GeoLayer = GeoLayer.HEIGHT): List<TileKey> {
        val t = lod(lod).tileM
        val out = ArrayList<TileKey>()
        for (tx in floor((x - radiusM) / t).toInt()..floor((x + radiusM) / t).toInt()) {
            for (ty in floor((y - radiusM) / t).toInt()..floor((y + radiusM) / t).toInt()) {
                val dx = max(0.0, max(tx * t - x, x - (tx + 1) * t))
                val dy = max(0.0, max(ty * t - y, y - (ty + 1) * t))
                val k = TileKey(lod, tx, ty)
                if (hypot(dx, dy) <= radiusM && has(layer, k)) out += k
            }
        }
        return out
    }

    /** Tiles of [layer] at [lod] within [marginM] of the road between two distances (a world section). */
    fun tilesAlong(fromM: Double, toM: Double, lod: Int, marginM: Double = lod(lod).reachM, layer: GeoLayer = GeoLayer.HEIGHT): List<TileKey> {
        val p = path
        val set = LinkedHashSet<TileKey>()
        val step = max(1, (lod(lod).tileM / 4 / p.stepM).toInt())
        val r = p.indexRange(fromM, toM)
        var i = r.first
        while (true) {
            set += tilesNear(p.x[i], p.y[i], marginM, lod, layer)
            if (i == r.last) break
            i = minOf(i + step, r.last)
        }
        return set.toList()
    }

    /** Number of corridor sections (each covers [CorridorSection.count] path samples). */
    val corridorSections: Int get() = dir.keys.count { it.type == T_CORRIDOR }

    fun corridor(section: Int): CorridorSection? {
        val b = chunk(T_CORRIDOR, 0, section, 0) ?: return null
        val bb = le(b)
        val first = bb.int
        val n = bb.int
        val k = bb.get().toInt() and 0xFF
        val offs = FloatArray(k) { bb.float }
        val fl = ByteArray(n)
        val fr = ByteArray(n)
        val left = FloatArray(n * k)
        val right = FloatArray(n * k)
        for (i in 0 until n) {
            fl[i] = bb.get()
            fr[i] = bb.get()
            for (j in 0 until k) left[i * k + j] = bb.short / 10f
            for (j in 0 until k) right[i * k + j] = bb.short / 10f
        }
        return CorridorSection(first, n, offs, fl, fr, left, right)
    }

    /** The corridor section containing the path sample at distance [d]. */
    fun corridorAt(d: Double): CorridorSection? {
        val per = meta.corridor?.sectionSamples ?: return null
        return corridor(minOf((d.coerceIn(0.0, lengthM) / meta.pathStepM).toInt(), path.size - 1) / per)
    }

    fun heights(key: TileKey): HeightTile? {
        val b = chunk(T_HEIGHT, key) ?: return null
        val bb = le(b)
        val cols = bb.short.toInt() and 0xFFFF
        val rows = bb.short.toInt() and 0xFFFF
        val base = bb.float
        val step = bb.float
        // Residuals of the predictor left + up - upleft: the values are their 2D prefix sum (mod 2^16).
        val q = IntArray(cols * rows)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val res = bb.short.toInt() and 0xFFFF
                val left = if (c > 0) q[r * cols + c - 1] else 0
                val up = if (r > 0) q[(r - 1) * cols + c] else 0
                val ul = if (r > 0 && c > 0) q[(r - 1) * cols + c - 1] else 0
                q[r * cols + c] = (res + left + up - ul) and 0xFFFF
            }
        }
        val h = FloatArray(q.size) { base + q[it] * step }
        val (ox, oy) = tileOrigin(key)
        return HeightTile(key, ox, oy, lod(key.lod).tileM, cols - 1, h)
    }

    fun landCover(key: TileKey): LandCoverTile? {
        val b = chunk(T_LANDCOVER, key) ?: return null
        val bb = le(b)
        val cols = bb.short.toInt() and 0xFFFF
        bb.short
        val (ox, oy) = tileOrigin(key)
        return LandCoverTile(key, ox, oy, lod(key.lod).tileM, cols, b.copyOfRange(4, b.size))
    }

    fun image(key: TileKey): ImageTile? {
        val e = dir[ChunkKey(T_IMAGE, key.lod, key.tx, key.ty)] ?: return null
        val b = chunk(T_IMAGE, key) ?: return null
        val (ox, oy) = tileOrigin(key)
        val fmt = when (e.codec) { C_JPEG -> "jpeg"; C_PNG -> "png"; C_WEBP -> "webp"; else -> "raw" }
        return ImageTile(key, ox, oy, lod(key.lod).tileM, fmt, b)
    }

    fun buildings(key: TileKey): BuildingTile? {
        val b = chunk(T_BUILDINGS, key) ?: return null
        val bb = le(b)
        val n = bb.int
        val unit = bb.float
        val list = ArrayList<Building>(n)
        repeat(n) {
            val cls = bb.get().toInt() and 0xFF
            val fl = bb.get().toInt() and 0xFF
            val roof = bb.get().toInt() and 0xFF
            val floors = bb.get().toInt() and 0xFF
            val h = (bb.short.toInt() and 0xFFFF) / 10f
            val mh = (bb.short.toInt() and 0xFFFF) / 10f
            val rh = (bb.short.toInt() and 0xFFFF) / 10f
            bb.short
            val base = bb.float
            val rc = bb.int
            val fc = bb.int
            list += Building(cls, fl and 1 != 0, roof, floors, h, mh, rh, base, rc, fc, polyline(bb, unit))
        }
        val (ox, oy) = tileOrigin(key)
        return BuildingTile(key, ox, oy, list)
    }

    fun roads(key: TileKey): RoadTile? {
        val b = chunk(T_ROADS, key) ?: return null
        val bb = le(b)
        val n = bb.int
        val unit = bb.float
        val list = ArrayList<RoadLine>(n)
        repeat(n) {
            val cls = bb.get().toInt() and 0xFF
            val fl = bb.get().toInt() and 0xFF
            val w = (bb.get().toInt() and 0xFF) / 10f
            bb.get()
            list += RoadLine(cls, fl, w, polyline(bb, unit))
        }
        val (ox, oy) = tileOrigin(key)
        return RoadTile(key, ox, oy, list)
    }

    fun water(key: TileKey): WaterTile? {
        val b = chunk(T_WATER, key) ?: return null
        val bb = le(b)
        val n = bb.int
        val unit = bb.float
        val list = ArrayList<WaterFeature>(n)
        repeat(n) {
            val kind = bb.get().toInt() and 0xFF
            val cls = bb.get().toInt() and 0xFF
            val rings = bb.short.toInt() and 0xFFFF
            val level = bb.float
            list += WaterFeature(kind == 0, cls, level, List(rings) { polyline(bb, unit) })
        }
        val (ox, oy) = tileOrigin(key)
        return WaterTile(key, ox, oy, list)
    }

    override fun close() = src.close()

    private fun polyline(bb: ByteBuffer, unit: Float): FloatArray {
        val n = bb.short.toInt() and 0xFFFF
        val out = FloatArray(n * 2)
        var x = bb.short.toInt()
        var y = bb.short.toInt()
        out[0] = x * unit
        out[1] = y * unit
        for (i in 1 until n) {
            x += bb.short.toInt()
            y += bb.short.toInt()
            out[2 * i] = x * unit
            out[2 * i + 1] = y * unit
        }
        return out
    }

    private fun decodePath(b: ByteArray): RoutePath {
        val bb = le(b)
        val n = bb.int
        val step = bb.float.toDouble()
        val length = bb.double
        val starts = IntArray(3) { bb.int }
        val scale = doubleArrayOf(100.0, 100.0, 1000.0)
        val arrays = Array(3) { k ->
            val out = DoubleArray(n)
            var v = starts[k].toLong()
            out[0] = v / scale[k]
            for (i in 1 until n) {
                v += bb.short
                out[i] = v / scale[k]
            }
            out
        }
        val width = FloatArray(n) { (bb.get().toInt() and 0xFF) / 10f }
        val flags = ByteArray(n).also { bb.get(it) }
        val cls = ByteArray(n).also { bb.get(it) }
        return RoutePath(step, length, arrays[0], arrays[1], arrays[2], width, flags, cls)
    }

    private fun chunk(type: Int, key: TileKey) = chunk(type, key.lod, key.tx, key.ty)

    private fun chunk(type: Int, lod: Int, tx: Int, ty: Int): ByteArray? {
        val e = dir[ChunkKey(type, lod, tx, ty)] ?: return null
        val data = src.read(e.offset, e.length)
        val crc = CRC32().apply { update(data) }.value.toInt()
        check(crc == e.crc) { "corrupt chunk $type/$lod/$tx/$ty" }
        return if (e.codec == C_DEFLATE) inflate(data, e.rawLength) else data
    }

    companion object {
        const val T_META = 1
        const val T_PATH = 2
        const val T_PLACES = 3
        const val T_CORRIDOR = 4
        const val T_HEIGHT = 10
        const val T_LANDCOVER = 11
        const val T_IMAGE = 12
        const val T_BUILDINGS = 20
        const val T_ROADS = 21
        const val T_WATER = 22
        const val C_RAW = 0
        const val C_DEFLATE = 1
        const val C_JPEG = 2
        const val C_PNG = 3
        const val C_WEBP = 4
        private const val HEADER = 64
        private const val ENTRY = 32
        private val MAGIC = byteArrayOf(0x57, 0x57, 0x47, 0x45, 0x4F, 0, 0, 1) // "WWGEO\0\0\1"
        private val JSON = Json { ignoreUnknownKeys = true }

        fun open(src: ByteSource): GeoPack {
            val h = src.read(0, HEADER)
            require(h.copyOfRange(0, 8).contentEquals(MAGIC)) { "not a wwgeo pack" }
            val bb = le(h)
            bb.position(8)
            val version = bb.short.toInt()
            require(version == 1) { "unsupported pack version $version" }
            bb.short
            val count = bb.int
            val dirOff = bb.long
            val dirLen = bb.int
            require(dirLen == count * ENTRY) { "bad directory" }
            val d = le(src.read(dirOff, dirLen))
            val map = HashMap<ChunkKey, Entry>(count * 2)
            repeat(count) {
                val type = d.short.toInt() and 0xFFFF
                val lod = d.get().toInt() and 0xFF
                val codec = d.get().toInt() and 0xFF
                val tx = d.int
                val ty = d.int
                val off = d.long
                val len = d.int
                val raw = d.int
                val crc = d.int
                map[ChunkKey(type, lod, tx, ty)] = Entry(codec, off, len, raw, crc)
            }
            return GeoPack(src, map)
        }

        private fun le(b: ByteArray): ByteBuffer = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN)

        internal fun inflate(data: ByteArray, rawLength: Int): ByteArray {
            val inf = Inflater()
            try {
                inf.setInput(data)
                val out = ByteArray(rawLength)
                var n = 0
                while (n < rawLength) {
                    val k = inf.inflate(out, n, rawLength - n)
                    if (k == 0 && (inf.finished() || inf.needsInput())) break
                    n += k
                }
                check(n == rawLength) { "short chunk: $n of $rawLength bytes" }
                return out
            } finally {
                inf.end()
            }
        }
    }
}
