package app.rollentrainer.ui.world

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.opengl.GLES30
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/** Thin helpers around OpenGL ES 3.0 so the renderer reads like a scene description. */
object Gl {
    private const val TAG = "WorldGl"

    fun program(vertex: String, fragment: String): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex)
        val fs = shader(GLES30.GL_FRAGMENT_SHADER, fragment)
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "link: " + GLES30.glGetProgramInfoLog(p))
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) Log.e(TAG, "compile: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    fun floatBuffer(data: FloatArray, count: Int = data.size): FloatBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().also { it.put(data, 0, count); it.position(0) }

    fun intBuffer(data: IntArray, count: Int = data.size): IntBuffer =
        ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder()).asIntBuffer().also { it.put(data, 0, count); it.position(0) }

    fun buffer(): Int = IntArray(1).also { GLES30.glGenBuffers(1, it, 0) }[0]
    fun vao(): Int = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]

    /** Renders a sign board with [text] into a texture; yellow boards are the big landmark signs. */
    fun textTexture(text: String, big: Boolean): Int {
        val w = 512
        val h = if (big) 192 else 128
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bg = Paint().apply { color = if (big) Color.rgb(250, 210, 40) else Color.WHITE }
        c.drawRoundRect(0f, 0f, w.toFloat(), h.toFloat(), 18f, 18f, bg)
        val border = Paint().apply { color = Color.rgb(40, 40, 40); style = Paint.Style.STROKE; strokeWidth = 8f }
        c.drawRoundRect(4f, 4f, w - 4f, h - 4f, 18f, 18f, border)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 20)
            textAlign = Paint.Align.CENTER
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        var size = if (big) 72f else 52f
        paint.textSize = size
        while (paint.measureText(text) > w - 40 && size > 20f) { size -= 4f; paint.textSize = size }
        val y = h / 2f - (paint.descent() + paint.ascent()) / 2f
        c.drawText(text, w / 2f, y, paint)
        val id = IntArray(1)
        GLES30.glGenTextures(1, id, 0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id[0])
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        bmp.recycle()
        return id[0]
    }
}

/** A mesh on the GPU: one VAO with position/normal/colour and an index buffer. */
class GpuMesh(data: MeshData) {
    val vao = Gl.vao()
    private val vbo = Gl.buffer()
    private val ibo = Gl.buffer()
    val indexCount = data.indexCount
    /** Optional per-instance buffer (8 floats: x y z scale, rotY r g b). */
    private var instanceVbo = -1
    var instanceCapacity = 0
        private set

    init {
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        val fb = Gl.floatBuffer(data.vertices, data.vertexCount * MeshData.STRIDE)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, fb.capacity() * 4, fb, GLES30.GL_STATIC_DRAW)
        val stride = MeshData.STRIDE * 4
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2)
        GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        val ib = Gl.intBuffer(data.indices, data.indexCount)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, ib.capacity() * 4, ib, GLES30.GL_STATIC_DRAW)
        // Instance attributes default to "one instance at the origin, unit scale, white".
        GLES30.glDisableVertexAttribArray(3)
        GLES30.glDisableVertexAttribArray(4)
        GLES30.glVertexAttrib4f(3, 0f, 0f, 0f, 1f)
        GLES30.glVertexAttrib4f(4, 0f, 1f, 1f, 1f)
        GLES30.glBindVertexArray(0)
    }

    /** Prepares the mesh for instancing with up to [capacity] instances. */
    fun enableInstancing(capacity: Int) {
        instanceCapacity = capacity
        instanceVbo = Gl.buffer()
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, capacity * 8 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(3)
        GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, 32, 0)
        GLES30.glVertexAttribDivisor(3, 1)
        GLES30.glEnableVertexAttribArray(4)
        GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, 32, 16)
        GLES30.glVertexAttribDivisor(4, 1)
        GLES30.glBindVertexArray(0)
    }

    fun uploadInstances(buffer: FloatBuffer, count: Int) {
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, instanceVbo)
        buffer.position(0)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, count.coerceAtMost(instanceCapacity) * 32, buffer)
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    fun drawInstanced(count: Int) {
        if (count <= 0) return
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElementsInstanced(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0, count.coerceAtMost(instanceCapacity))
        GLES30.glBindVertexArray(0)
    }

    fun delete() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
        if (instanceVbo >= 0) GLES30.glDeleteBuffers(1, intArrayOf(instanceVbo), 0)
    }
}

object Shaders {
    const val LIT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec4 aInst;     // x y z scale
layout(location = 4) in vec4 aInstRot;  // rotY r g b
uniform mat4 uViewProj;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vColor;
out vec3 vWorld;
void main() {
    float c = cos(aInstRot.x);
    float s = sin(aInstRot.x);
    vec3 p = aPos * aInst.w;
    vec3 rp = vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c) + aInst.xyz;
    vec3 rn = vec3(aNormal.x * c + aNormal.z * s, aNormal.y, -aNormal.x * s + aNormal.z * c);
    vec4 world = uModel * vec4(rp, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uModel) * rn);
    vColor = aColor * aInstRot.yzw;
    gl_Position = uViewProj * world;
}
"""

    const val LIT_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vWorld;
uniform vec3 uLightDir;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform float uWindows;
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float diff = max(dot(n, uLightDir), 0.0);
    float hemi = 0.5 + 0.5 * n.y;
    vec3 col = vColor * (0.28 + 0.22 * hemi + 0.72 * diff);
    if (uWindows > 0.5) {
        // Window grid on building facades, from the world position.
        float fy = fract(vWorld.y / 3.1);
        float fx = fract((vWorld.x + vWorld.z) / 2.6);
        if (abs(n.y) < 0.3 && fy > 0.25 && fy < 0.75 && fx > 0.2 && fx < 0.65 && vWorld.y > 0.5) col *= 0.35;
    }
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * uFogDensity);
    fragColor = vec4(mix(col, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);
}
"""

    const val ROAD_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec3 vColor;   // x = metres / 10 along, y = across -1..1
in vec3 vWorld;
uniform vec3 uLightDir;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
uniform float uWindows;
out vec4 fragColor;
void main() {
    float u = vColor.x * 10.0;
    float v = vColor.y;
    vec3 asphalt = vec3(0.30, 0.30, 0.31) + 0.02 * fract(sin(u * 12.9898 + v * 78.233) * 43758.5453);
    vec3 col = asphalt;
    if (abs(v) > 0.93) col = vec3(0.92, 0.92, 0.9);                       // edge lines
    if (abs(v) < 0.025 && fract(u / 8.0) < 0.45) col = vec3(0.9, 0.9, 0.88); // centre dashes
    if (abs(v) > 0.985) col = vec3(0.45, 0.42, 0.36);                     // kerb
    vec3 n = normalize(vNormal);
    float diff = max(dot(n, uLightDir), 0.0);
    col *= (0.35 + 0.7 * diff);
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * uFogDensity);
    fragColor = vec4(mix(col, uFogColor, clamp(fog, 0.0, 1.0)), 1.0);
}
"""

    const val SKY_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vUv;
void main() { vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.999, 1.0); }
"""

    const val SKY_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform vec3 uHorizon;
uniform vec3 uZenith;
uniform float uHorizonY;  // screen height fraction of the horizon
out vec4 fragColor;
void main() {
    float t = clamp((vUv.y - uHorizonY) / max(1.0 - uHorizonY, 0.05), 0.0, 1.0);
    vec3 col = mix(uHorizon, uZenith, pow(t, 0.6));
    // Sun glow low on the right.
    float sun = exp(-40.0 * (pow(vUv.x - 0.78, 2.0) + pow(vUv.y - uHorizonY - 0.22, 2.0)));
    col += vec3(1.0, 0.95, 0.8) * sun * 0.35;
    fragColor = vec4(col, 1.0);
}
"""

    const val TEX_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec2 aUv;
uniform mat4 uViewProj;
uniform mat4 uModel;
out vec2 vUv;
out vec3 vWorld;
void main() { vec4 w = uModel * vec4(aPos, 1.0); vWorld = w.xyz; vUv = aUv; gl_Position = uViewProj * w; }
"""

    const val TEX_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
in vec3 vWorld;
uniform sampler2D uTex;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * uFogDensity);
    fragColor = vec4(mix(c.rgb, uFogColor, clamp(fog, 0.0, 1.0)), c.a);
}
"""
}
