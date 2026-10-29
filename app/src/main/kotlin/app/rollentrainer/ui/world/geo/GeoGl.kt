package app.rollentrainer.ui.world.geo

import android.graphics.Bitmap
import android.opengl.GLES30
import app.rollentrainer.ui.world.Program
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer
import java.nio.ShortBuffer

/** GL helpers of the geodata renderer (GL thread only). */
internal object GeoGl {
    const val GL_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FE
    const val GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT = 0x84FF

    /** Largest anisotropy the device offers (1 = extension missing); queried once per context. */
    var maxAniso = 1f
        private set

    fun init() {
        val ext = GLES30.glGetString(GLES30.GL_EXTENSIONS) ?: ""
        maxAniso = if (ext.contains("GL_EXT_texture_filter_anisotropic")) {
            val f = FloatArray(1)
            GLES30.glGetFloatv(GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT, f, 0)
            f[0].coerceAtLeast(1f)
        } else 1f
    }

    fun genTexture(): Int = IntArray(1).also { GLES30.glGenTextures(1, it, 0) }[0]
    fun genBuffer(): Int = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
    fun genVao(): Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
    fun deleteTexture(t: Int) { if (t != 0) GLES30.glDeleteTextures(1, intArrayOf(t), 0) }
    fun deleteBuffer(b: Int) { if (b != 0) GLES30.glDeleteBuffers(1, intArrayOf(b), 0) }
    fun deleteVao(v: Int) { if (v != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(v), 0) }

    fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
    fun floats(a: FloatArray, n: Int = a.size): FloatBuffer = direct(n * 4).asFloatBuffer().also { it.put(a, 0, n); it.position(0) }
    fun ints(a: IntArray, n: Int = a.size): IntBuffer = direct(n * 4).asIntBuffer().also { it.put(a, 0, n); it.position(0) }
    fun shorts(a: ShortArray, n: Int = a.size): ShortBuffer = direct(n * 2).asShortBuffer().also { it.put(a, 0, n); it.position(0) }

    fun setFilter(target: Int, mip: Boolean, aniso: Float, wrap: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, if (mip) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, wrap)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, wrap)
        if (aniso > 1f && maxAniso > 1f) GLES30.glTexParameterf(target, GL_TEXTURE_MAX_ANISOTROPY_EXT, minOf(aniso, maxAniso))
    }

    /**
     * An RGBA8 texture from raw pixels ([w] x [h], rows top first). [srgb] stores colour as sRGB, so
     * sampling returns linear values and mipmaps average in linear space.
     */
    fun texture2D(pixels: Buffer, w: Int, h: Int, srgb: Boolean, mip: Boolean, aniso: Float, wrap: Int): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        pixels.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, if (srgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        setFilter(GLES30.GL_TEXTURE_2D, mip, aniso, wrap)
        if (mip) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        return t
    }

    /** Like [texture2D] with mipmaps, but the levels below the first come from [mips] (see Materials.coverageMips). */
    fun texture2DMips(pixels: Buffer, w: Int, h: Int, mips: List<ByteBuffer>, aniso: Float, wrap: Int): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        pixels.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_SRGB8_ALPHA8, w, h, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, pixels)
        var lw = w; var lh = h
        for ((i, m) in mips.withIndex()) {
            lw = maxOf(1, lw / 2); lh = maxOf(1, lh / 2)
            m.position(0)
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, i + 1, GLES30.GL_SRGB8_ALPHA8, lw, lh, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, m)
        }
        setFilter(GLES30.GL_TEXTURE_2D, true, aniso, wrap)
        return t
    }

    /** Pixels of an ARGB_8888 bitmap as RGBA bytes (Android stores them in that order). */
    fun pixelsOf(b: Bitmap): ByteBuffer {
        val buf = direct(b.width * b.height * 4)
        b.copyPixelsToBuffer(buf)
        buf.position(0)
        return buf
    }

    /** An unsigned-integer single-channel texture (land cover classes), sampled with texelFetch. */
    fun textureR8ui(data: ByteArray, w: Int, h: Int): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val b = direct(w * h).put(data, 0, w * h)
        b.position(0)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8UI, w, h, 0, GLES30.GL_RED_INTEGER, GLES30.GL_UNSIGNED_BYTE, b)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        return t
    }

    /** An empty 2D texture array; layers are filled with [uploadLayer], mipmaps generated at the end. */
    fun textureArray(size: Int, layers: Int, srgb: Boolean): Int {
        val t = genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, t)
        var levels = 1
        var s = size
        while (s > 1) { s /= 2; levels++ }
        GLES30.glTexStorage3D(GLES30.GL_TEXTURE_2D_ARRAY, levels, if (srgb) GLES30.GL_SRGB8_ALPHA8 else GLES30.GL_RGBA8, size, size, layers)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_REPEAT)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D_ARRAY, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_REPEAT)
        if (maxAniso > 1f) GLES30.glTexParameterf(GLES30.GL_TEXTURE_2D_ARRAY, GL_TEXTURE_MAX_ANISOTROPY_EXT, minOf(8f, maxAniso))
        return t
    }

    /** Rows [y0, y0 + rows) of one layer (the upload can be split over several frames). */
    fun uploadLayerRows(tex: Int, layer: Int, size: Int, y0: Int, rows: Int, pixels: ByteBuffer) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        val slice = pixels.duplicate().order(ByteOrder.nativeOrder())
        slice.position(y0 * size * 4)
        slice.limit((y0 + rows) * size * 4)
        GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, y0, layer, size, rows, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, slice.slice())
    }

    fun finishArray(tex: Int) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D_ARRAY)
    }

    fun staticBuffer(target: Int, data: Buffer, bytes: Int): Int {
        val b = genBuffer()
        GLES30.glBindBuffer(target, b)
        data.position(0)
        GLES30.glBufferData(target, bytes, data, GLES30.GL_STATIC_DRAW)
        return b
    }

    /** Vertex attributes 0..4 of the [MeshBuffer] layout for the bound VAO and array buffer. */
    fun meshAttribs() {
        val stride = Vtx.FLOATS * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, Vtx.POS * 4)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, Vtx.NORMAL * 4)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, Vtx.UV * 4)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, Vtx.COLOR * 4)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 1, GLES30.GL_FLOAT, false, stride, Vtx.EXTRA * 4)
    }

    /** Instance attributes [first]..: [count] vec4 per instance with divisor 1 from the bound buffer. */
    fun instanceAttribs(first: Int, count: Int) {
        val stride = count * 16
        for (i in 0 until count) {
            GLES30.glEnableVertexAttribArray(first + i)
            GLES30.glVertexAttribPointer(first + i, 4, GLES30.GL_FLOAT, false, stride, i * 16)
            GLES30.glVertexAttribDivisor(first + i, 1)
        }
    }

    /** Defaults of the instance attributes for a plain (non-instanced) draw: at the origin, unit scale, no tint. */
    fun defaultInstance() {
        GLES30.glVertexAttrib4f(5, 0f, 0f, 0f, 0f)
        GLES30.glVertexAttrib4f(6, 1f, WHITE_TINT, 0f, 0f)
    }

    const val WHITE_TINT = 16777215f
}

/**
 * A linked program bound to the "Frame" uniform block, with fixed sampler units. A shader the driver
 * rejects throws (IllegalStateException), so the renderer can hand over to the procedural world.
 */
internal class GeoProgram(vs: String, fs: String, samplers: Map<String, Int> = emptyMap()) {
    val p = Program(vs, fs, strict = true)

    init {
        val block = GLES30.glGetUniformBlockIndex(p.id, "Frame")
        if (block != GLES30.GL_INVALID_INDEX) GLES30.glUniformBlockBinding(p.id, block, 0)
        p.use()
        for ((name, unit) in samplers) p.set(name, unit)
    }

    fun use() = p.use()
}

/** A [MeshBuffer] on the GPU (VAO with the 12-float layout, 32-bit indices), optionally drawn instanced. */
/**
 * A position-only mesh for the shadow pass (see ShadowMesh). The other vertex attributes stay disabled,
 * so the shader reads their constant values: no UV, no animation weight, the default instance.
 */
internal class PosMeshGpu(m: ShadowMesh) {
    val vao = GeoGl.genVao()
    private val vbo: Int
    private val ibo: Int
    val indexCount = m.indexCount
    val bytes = m.vertexCount * 12 + m.indexCount * 4

    init {
        GLES30.glBindVertexArray(vao)
        vbo = GeoGl.staticBuffer(GLES30.GL_ARRAY_BUFFER, GeoGl.floats(m.positions, m.vertexCount * 3), m.vertexCount * 12)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        ibo = GeoGl.staticBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, GeoGl.ints(m.indices, m.indexCount), m.indexCount * 4)
        GLES30.glBindVertexArray(0)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun delete() {
        GeoGl.deleteVao(vao)
        GeoGl.deleteBuffer(vbo)
        GeoGl.deleteBuffer(ibo)
    }
}

internal class MeshGpu(m: MeshBuffer) {
    val vao = GeoGl.genVao()
    private val vbo: Int
    private val ibo: Int
    val indexCount = m.indexCount
    val bytes = m.vertexCount * Vtx.FLOATS * 4 + m.indexCount * 4
    /** Bound instance buffer (owned elsewhere) and its attribute count, or 0. */
    private var instanceBuffer = 0

    init {
        GLES30.glBindVertexArray(vao)
        vbo = GeoGl.staticBuffer(GLES30.GL_ARRAY_BUFFER, GeoGl.floats(m.vertices, m.vertexCount * Vtx.FLOATS), m.vertexCount * Vtx.FLOATS * 4)
        GeoGl.meshAttribs()
        ibo = GeoGl.staticBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, GeoGl.ints(m.indices, m.indexCount), m.indexCount * 4)
        GLES30.glBindVertexArray(0)
    }

    /** Points the instance attributes (5.. 5 + [vec4s]) of this mesh's VAO at [buffer]. */
    fun attachInstances(buffer: Int, vec4s: Int) {
        if (buffer == instanceBuffer) return
        instanceBuffer = buffer
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GeoGl.instanceAttribs(5, vec4s)
        GLES30.glBindVertexArray(0)
    }

    /**
     * A second VAO over the same vertex and index buffers for the shadow pass, whose instances come from
     * another buffer (all shadow casters of a prototype, see InstanceAssembler.ShadowBucket).
     */
    private var shadowVao = 0
    private var shadowInstanceBuffer = 0

    fun attachShadowInstances(buffer: Int, vec4s: Int) {
        if (shadowVao == 0) {
            shadowVao = GeoGl.genVao()
            GLES30.glBindVertexArray(shadowVao)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GeoGl.meshAttribs()
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glBindVertexArray(0)
        }
        if (buffer == shadowInstanceBuffer) return
        shadowInstanceBuffer = buffer
        GLES30.glBindVertexArray(shadowVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, buffer)
        GeoGl.instanceAttribs(5, vec4s)
        GLES30.glBindVertexArray(0)
    }

    fun drawShadowInstanced(count: Int) {
        if (count <= 0 || shadowVao == 0) return
        GLES30.glBindVertexArray(shadowVao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0, count)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    /** Indices [first], [first] + [count] only (the tunnel tubes of a road chunk in the shadow pass). */
    fun drawRange(first: Int, count: Int) {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, count, GLES30.GL_UNSIGNED_INT, first * 4)
    }

    fun drawInstanced(count: Int, first: Int = 0) {
        if (count <= 0) return
        GLES30.glBindVertexArray(vao)
        if (first != 0) {
            // ES 3.0 has no base instance: re-point the instance attributes at the first instance.
            error("first instance must be 0")
        }
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0, count)
    }

    fun delete() {
        GeoGl.deleteVao(vao)
        if (shadowVao != 0) GeoGl.deleteVao(shadowVao)
        GeoGl.deleteBuffer(vbo)
        GeoGl.deleteBuffer(ibo)
    }
}

/**
 * Instance data rewritten every frame or a few times a second, without stalling on draws still in
 * flight: a ring of buffers, each rewritten with glBufferSubData when its turn comes again (re-specifying
 * storage only when it must grow). The staging buffer is reused, so nothing is allocated per upload.
 */
internal class StreamBuffer(ring: Int) {
    private val ids = IntArray(ring) { GeoGl.genBuffer() }
    private val capacity = IntArray(ring)
    private var next = 0
    private var staging: FloatBuffer = GeoGl.direct(4096).asFloatBuffer()
    /** The buffer written last (bind it to the VAOs that draw from it). */
    var current = ids[0]
        private set
    val bytes: Int get() = capacity.sum()

    fun upload(data: FloatArray, floats: Int): Int {
        val k = next
        next = (next + 1) % ids.size
        val id = ids[k]
        val bytes = floats * 4
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, id)
        if (bytes > capacity[k]) {
            capacity[k] = maxOf(bytes * 3 / 2, 4096)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, capacity[k], null, GLES30.GL_DYNAMIC_DRAW)
        }
        if (floats > staging.capacity()) staging = GeoGl.direct(maxOf(floats * 4 * 3 / 2, 4096)).asFloatBuffer()
        if (floats > 0) {
            staging.position(0)
            staging.put(data, 0, floats)
            staging.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, bytes, staging)
        }
        current = id
        return id
    }

    fun delete() { for (b in ids) GeoGl.deleteBuffer(b) }
}
