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

    /**
     * Compiles and links a program. Errors are logged; with [strict] a compile or link error throws
     * (IllegalStateException with the info log) instead of returning the broken program, so a caller
     * with a fallback can take it.
     */
    fun program(vertex: String, fragment: String, strict: Boolean = false): Int {
        val vs = shader(GLES30.GL_VERTEX_SHADER, vertex, strict)
        val fs = try {
            shader(GLES30.GL_FRAGMENT_SHADER, fragment, strict)
        } catch (e: IllegalStateException) {
            GLES30.glDeleteShader(vs)
            throw e
        }
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        val status = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, status, 0)
        val linkLog = if (status[0] == 0) GLES30.glGetProgramInfoLog(p) else null
        if (linkLog != null) Log.e(TAG, "link: $linkLog")
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (linkLog != null && strict) throw IllegalStateException("shader link failed: $linkLog")
        return p
    }

    private fun shader(type: Int, src: String, strict: Boolean): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val status = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, status, 0)
        if (status[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(s)
            Log.e(TAG, "compile: $log")
            if (strict) {
                GLES30.glDeleteShader(s)
                throw IllegalStateException("shader compile failed: $log")
            }
        }
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
        // Opaque everywhere: the corners take the border colour, the post samples the left edge.
        c.drawColor(Color.rgb(40, 40, 40))
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

/**
 * A linked shader program with its uniform locations cached by name (no lookups per frame). [strict]: a
 * compile or link error throws (see [Gl.program]); the procedural renderer keeps the old lenient default.
 */
class Program(vertex: String, fragment: String, strict: Boolean = false) {
    val id = Gl.program(vertex, fragment, strict)
    private val locations = HashMap<String, Int>()

    fun use() = GLES30.glUseProgram(id)
    fun loc(name: String): Int = locations.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    fun set(name: String, v: Float) = GLES30.glUniform1f(loc(name), v)
    fun set(name: String, v: Int) = GLES30.glUniform1i(loc(name), v)
    fun set3(name: String, v: FloatArray) = GLES30.glUniform3fv(loc(name), 1, v, 0)
    fun set3(name: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(loc(name), x, y, z)
    fun setMat4(name: String, m: FloatArray) = GLES30.glUniformMatrix4fv(loc(name), 1, false, m, 0)
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

/**
 * GLSL ES 3.00 shaders. All materials are procedural: value noise from the world position, no texture
 * files. Shared uniform names: uViewProj, uModel, uSunDir (unit), uSunColor, uSkyColor, uCamPos,
 * uFogColor, uFogDensity; the lit program adds uTintMode and uWindows, the terrain uTreeLine, the sky
 * uInvViewProj, uHorizon, uZenith and uTime.
 */
object Shaders {
    /** Value noise in 0..1 (hash without sin, so large world coordinates stay stable). */
    private const val NOISE = """
float hash12(vec2 p) {
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}
float vnoise(vec2 p) {
    vec2 i = floor(p);
    vec2 f = fract(p);
    vec2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash12(i), hash12(i + vec2(1.0, 0.0)), u.x),
               mix(hash12(i + vec2(0.0, 1.0)), hash12(i + vec2(1.0, 1.0)), u.x), u.y);
}
float fbm2(vec2 p) {
    return 0.667 * vnoise(p) + 0.333 * vnoise(p * 2.03 + vec2(17.1, 9.7));
}
float fbm3(vec2 p) {
    return 0.571 * vnoise(p) + 0.286 * vnoise(p * 2.03 + vec2(17.1, 9.7)) + 0.143 * vnoise(p * 4.11 + vec2(31.3, 7.9));
}
"""

    /** Warm directional sun, sky-coloured ambient from above, a little ground bounce, Blinn-Phong specular, fog. */
    private const val LIGHT = """
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uSkyColor;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
vec3 shade(vec3 albedo, vec3 n, vec3 viewDir, float specStrength, float shininess) {
    float diff = max(dot(n, uSunDir), 0.0);
    float hemi = 0.5 + 0.5 * n.y;
    vec3 ambient = uSkyColor * (0.30 + 0.40 * hemi) + vec3(0.12, 0.10, 0.08) * (1.0 - hemi);
    vec3 col = albedo * (ambient + uSunColor * diff);
    vec3 h = normalize(uSunDir + viewDir);
    float spec = pow(max(dot(n, h), 0.0), shininess) * specStrength * (0.2 + 0.8 * diff);
    return col + uSunColor * spec;
}
vec3 applyFog(vec3 col, float dist) {
    float f = 1.0 - exp(-dist * uFogDensity);
    return mix(col, uFogColor, clamp(f, 0.0, 1.0));
}
"""

    /** Props, riders and the far mountains; instanced with position/scale and rotation/tint. */
    const val LIT_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
layout(location = 3) in vec4 aInst;     // x y z scale
layout(location = 4) in vec4 aInstRot;  // rotY r g b
uniform mat4 uViewProj;
uniform mat4 uModel;
uniform float uTintMode;   // 0: the tint multiplies everything, 1: only pure white parts take it
out vec3 vNormal;
out vec3 vColor;
out vec3 vWorld;
out vec4 vLocal;           // scaled local position; w = 1 on vertical faces
out float vAcross;         // local coordinate along a vertical face, for window columns
void main() {
    float c = cos(aInstRot.x);
    float s = sin(aInstRot.x);
    vec3 p = aPos * aInst.w;
    vec3 rp = vec3(p.x * c + p.z * s, p.y, -p.x * s + p.z * c) + aInst.xyz;
    vec3 rn = vec3(aNormal.x * c + aNormal.z * s, aNormal.y, -aNormal.x * s + aNormal.z * c);
    vec4 world = uModel * vec4(rp, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uModel) * rn);
    float white = step(2.97, aColor.r + aColor.g + aColor.b);
    vec3 tint = mix(aInstRot.yzw, mix(vec3(1.0), aInstRot.yzw, white), step(0.5, uTintMode));
    vColor = aColor * tint;
    vLocal = vec4(p, step(abs(aNormal.y), 0.3));
    vAcross = abs(aNormal.x) > 0.5 ? p.z : p.x;
    gl_Position = uViewProj * world;
}
"""

    const val LIT_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vWorld;
in vec4 vLocal;
in float vAcross;
uniform float uWindows;
$NOISE
$LIGHT
out vec4 fragColor;
void main() {
    vec3 n = normalize(vNormal);
    float dist = length(vWorld - uCamPos);
    vec3 viewDir = (uCamPos - vWorld) / max(dist, 0.001);
    vec3 albedo = vColor;
    float specS = 0.08;
    float shin = 16.0;
    if (uWindows > 0.5 && vLocal.w > 0.5) {
        // Window rows on the facades, in the building's own coordinates so they stay put when it is turned.
        float fy = vLocal.y / 3.1;
        float fx = vAcross / 2.6;
        vec2 cell = floor(vec2(fx, fy));
        vec2 f = fract(vec2(fx, fy));
        bool ground = vLocal.y < 4.2;
        bool win = ground ? (f.x > 0.12 && f.x < 0.88 && vLocal.y > 0.3 && vLocal.y < 3.6)
                          : (f.x > 0.22 && f.x < 0.68 && f.y > 0.2 && f.y < 0.78 && vLocal.y > 4.6 && vLocal.y < 18.6);
        if (win) {
            float h = hash12(cell + floor(vWorld.xz * 0.03));
            vec3 glass = vec3(0.14, 0.17, 0.22) + 0.3 * uSkyColor * (0.5 + 0.5 * n.y);
            vec3 warm = vec3(0.95, 0.78, 0.45);
            albedo = ground ? vec3(0.10, 0.12, 0.14) : mix(glass, warm, step(0.72, h) * 0.85);
            // Mullion down the middle of each window.
            float mid = abs(f.x - 0.45);
            albedo = mix(vColor * 0.9, albedo, step(0.025, mid));
            specS = 0.6;
            shin = 40.0;
        } else if (ground) {
            albedo *= vec3(0.78, 0.74, 0.70);
        } else {
            // Stone courses.
            albedo *= 0.96 + 0.06 * step(0.5, fract(fy * 2.0));
        }
    }
    vec3 col = shade(albedo, n, viewDir, specS, shin);
    fragColor = vec4(applyFog(col, dist), 1.0);
}
"""

    /** Terrain: the colour channel carries (material, variation, road distance / 100). */
    const val TERRAIN_VS = """#version 300 es
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aNormal;
layout(location = 2) in vec3 aColor;
uniform mat4 uViewProj;
uniform mat4 uModel;
out vec3 vNormal;
out vec3 vWorld;
out vec4 vW0;      // grass, forest floor, rock, snow
out vec3 vW1;      // scree, plaza, shoulder
out vec2 vExtra;   // variation, road distance / 100
void main() {
    vec4 world = uModel * vec4(aPos, 1.0);
    vWorld = world.xyz;
    vNormal = normalize(mat3(uModel) * aNormal);
    int m = int(aColor.x + 0.5);
    vW0 = vec4(m == 0 ? 1.0 : 0.0, m == 1 ? 1.0 : 0.0, m == 2 ? 1.0 : 0.0, m == 3 ? 1.0 : 0.0);
    vW1 = vec3(m == 4 ? 1.0 : 0.0, m == 5 ? 1.0 : 0.0, m == 6 ? 1.0 : 0.0);
    vExtra = aColor.yz;
    gl_Position = uViewProj * world;
}
"""

    const val TERRAIN_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vWorld;
in vec4 vW0;
in vec3 vW1;
in vec2 vExtra;
uniform float uTreeLine;
$NOISE
$LIGHT
out vec4 fragColor;
void main() {
    vec3 gn = normalize(vNormal);
    vec2 p = vWorld.xz;
    float dist = length(vWorld - uCamPos);
    vec3 viewDir = (uCamPos - vWorld) / max(dist, 0.001);
    // Fine texture and bump fade out with distance, which also keeps the far ground from shimmering.
    float detail = clamp(1.0 - dist / 450.0, 0.0, 1.0);
    float vari = vExtra.x;
    float roadD = vExtra.y * 100.0;

    // Shared noise fields.
    float nLo = fbm2(p * 0.013);        // colour patches over tens of metres
    float nMid = vnoise(p * 0.11);      // metre-scale clumps
    float nHi = 0.5;                    // blades and grains
    vec3 bump = vec3(0.0);
    if (detail > 0.0) {
        nHi = fbm3(vec2(p.x * 2.6, p.y * 1.3));
        float e = 0.07;
        float h0 = fbm2(p * 0.9);
        float hx = fbm2((p + vec2(e, 0.0)) * 0.9);
        float hz = fbm2((p + vec2(0.0, e)) * 0.9);
        bump = vec3(-(hx - h0), 0.0, -(hz - h0)) / e;
    }

    // Material weights from the vertices, then blended by slope, height and the distance to the road.
    vec4 w0 = vW0;
    vec3 w1 = vW1;
    float slope = 1.0 - gn.y;
    float rocky = smoothstep(0.40, 0.68, slope + 0.12 * (nLo - 0.5));
    float soft = w0.x + w0.y + w0.w;
    w0.z += soft * rocky;
    w0.x *= 1.0 - rocky; w0.y *= 1.0 - rocky; w0.w *= 1.0 - rocky;
    float high = smoothstep(uTreeLine - 150.0, uTreeLine + 350.0, vWorld.y + 120.0 * (nLo - 0.5));
    w1.x += w0.x * high * 0.7;
    w0.z += w0.x * high * 0.3;
    w0.x *= 1.0 - high;
    float shoulderBand = 1.0 - smoothstep(4.3, 6.5, roadD);
    w1.z += shoulderBand * (1.0 - w1.y);
    float total = w0.x + w0.y + w0.z + w0.w + w1.x + w1.y + w1.z;
    w0 /= total;
    w1 /= total;

    // Grass: blade streaks from the stretched high-frequency noise, colour patches from the low one.
    vec3 grass = mix(vec3(0.24, 0.44, 0.15), vec3(0.50, 0.58, 0.22), nLo * 0.75 + 0.25 * vari);
    grass *= 0.72 + 0.55 * nHi * detail + 0.18 * nMid;
    // Forest floor: needles and moss.
    vec3 forest = mix(vec3(0.30, 0.22, 0.12), vec3(0.19, 0.33, 0.13), nMid * 0.7 + 0.3 * nLo) * (0.8 + 0.4 * nHi * detail);
    // Rock: strata bands following the height, cracks from the high noise.
    float strata = fract(vWorld.y * 0.45 + nLo * 2.0 + nMid * 0.6);
    float band = smoothstep(0.0, 0.3, strata) * (1.0 - smoothstep(0.6, 1.0, strata));
    vec3 rock = mix(vec3(0.40, 0.38, 0.36), vec3(0.58, 0.54, 0.49), band) * (0.85 + 0.3 * nHi * detail);
    rock = mix(rock, rock * 0.55, smoothstep(0.78, 0.9, nHi) * detail);
    // Snow: bluish in the hollows, sparkles close by.
    float sparkle = step(0.986, hash12(floor(p * 28.0))) * detail;
    vec3 snow = mix(vec3(0.80, 0.85, 0.94), vec3(0.97, 0.98, 1.0), nMid) + vec3(sparkle * 0.6);
    // Scree: pale limestone rubble.
    vec3 scree = mix(vec3(0.76, 0.74, 0.68), vec3(0.90, 0.88, 0.82), nMid) * (0.85 + 0.3 * nHi * detail);
    // Plaza: paving slabs with joints.
    vec2 slab = fract(p * 0.6);
    float edge = min(min(slab.x, 1.0 - slab.x), min(slab.y, 1.0 - slab.y));
    float joint = mix(1.0, 0.7 + 0.3 * smoothstep(0.0, 0.05, edge), detail);
    vec3 plaza = vec3(0.56, 0.55, 0.52) * (0.9 + 0.2 * hash12(floor(p * 0.6))) * joint;
    // Shoulder: packed gravel and dirt.
    vec3 shoulder = mix(vec3(0.50, 0.44, 0.34), vec3(0.64, 0.60, 0.50), nMid) * (0.8 + 0.4 * nHi * detail);

    vec3 albedo = grass * w0.x + forest * w0.y + rock * w0.z + snow * w0.w + scree * w1.x + plaza * w1.y + shoulder * w1.z;
    float rough = 0.30 * w0.x + 0.28 * w0.y + 0.70 * w0.z + 0.10 * w0.w + 0.50 * w1.x + 0.03 * w1.y + 0.40 * w1.z;
    vec3 n = normalize(gn + bump * rough * detail);
    float specS = 0.04 * w0.x + 0.35 * w0.w + 0.15 * w1.y + 0.05 * w0.z;
    vec3 col = shade(albedo, n, viewDir, specS, 24.0);
    fragColor = vec4(applyFog(col, dist), 1.0);
}
"""

    /** Road: vColor = (metres along / 10, across -1..1, 0); |across| > 1 is the stone skirt. */
    const val ROAD_FS = """#version 300 es
precision highp float;
in vec3 vNormal;
in vec3 vColor;
in vec3 vWorld;
in vec4 vLocal;
in float vAcross;
$NOISE
$LIGHT
out vec4 fragColor;
void main() {
    float u = vColor.x * 10.0;
    float v = vColor.y;
    float av = abs(v);
    float dist = length(vWorld - uCamPos);
    vec3 viewDir = (uCamPos - vWorld) / max(dist, 0.001);
    float detail = clamp(1.0 - dist / 300.0, 0.0, 1.0);
    vec2 p = vWorld.xz;
    float grain = 0.5;
    if (detail > 0.0) grain = mix(0.5, fbm2(p * 6.0), detail);
    float patches = fbm2(vec2(u * 0.03, v * 1.5));
    vec3 asphalt = vec3(0.28, 0.28, 0.29) * (0.78 + 0.25 * patches) * (0.85 + 0.3 * grain);
    // Tyre tracks: two darker, smoother bands per lane.
    float t1 = (av - 0.33) / 0.08;
    float t2 = (av - 0.72) / 0.08;
    float tracks = exp(-t1 * t1) + exp(-t2 * t2);
    asphalt *= 1.0 - 0.22 * tracks * (0.6 + 0.4 * patches);
    vec3 col = asphalt;
    float wear = 0.6 + 0.4 * smoothstep(0.3, 0.7, patches);
    vec3 paint = vec3(0.90, 0.90, 0.86) * wear;
    if (av > 0.93 && av <= 0.985) col = mix(col, paint, 0.9);                        // edge lines
    if (av < 0.025 && fract(u / 8.0) < 0.45) col = mix(col, paint, 0.9);            // centre dashes
    if (av > 0.985 && av <= 1.0) col = vec3(0.50, 0.47, 0.42) * (0.85 + 0.3 * grain); // kerb stone
    float specS = 0.22 * (1.0 + 0.6 * tracks);
    float shin = 20.0;
    if (av > 1.0) {
        // Stone wall of the skirt: blocks in offset rows with mortar joints.
        vec2 blk = vec2(u * 1.1, vWorld.y * 1.6);
        blk.x += 0.5 * step(0.5, fract(floor(blk.y) * 0.5));
        vec2 f = fract(blk);
        float mortar = smoothstep(0.0, 0.08, min(min(f.x, 1.0 - f.x), min(f.y, 1.0 - f.y) * 2.0));
        float stone = 0.75 + 0.3 * hash12(floor(blk));
        col = mix(vec3(0.40, 0.38, 0.35), vec3(0.56, 0.53, 0.48) * stone, mortar) * (0.85 + 0.3 * grain);
        specS = 0.0;
    }
    vec3 n = normalize(vNormal);
    vec3 lit = shade(col, n, viewDir, specS, shin);
    fragColor = vec4(applyFog(lit, dist), 1.0);
}
"""

    const val SKY_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
out vec2 vNdc;
void main() { vNdc = aPos; gl_Position = vec4(aPos, 0.999, 1.0); }
"""

    /** Sky from the view ray: gradient with horizon haze, sun disc and halo, soft cloud bands. */
    const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvViewProj;
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uHorizon;
uniform vec3 uZenith;
uniform float uTime;
$NOISE
out vec4 fragColor;
void main() {
    vec4 a = uInvViewProj * vec4(vNdc, -1.0, 1.0);
    vec4 b = uInvViewProj * vec4(vNdc, 1.0, 1.0);
    vec3 dir = normalize(b.xyz / b.w - a.xyz / a.w);
    float up = dir.y;
    float t = pow(clamp(up, 0.0, 1.0), 0.5);
    vec3 col = mix(uHorizon, uZenith, t);
    // Thicker haze right at the horizon, continuing below it (the ground covers that part anyway).
    col = mix(col, uHorizon * 1.04, 1.0 - smoothstep(-0.05, 0.08, up));
    float cosSun = dot(dir, uSunDir);
    float halo = pow(max(cosSun, 0.0), 24.0) * 0.25 + pow(max(cosSun, 0.0), 180.0) * 0.5;
    float disc = smoothstep(0.9993, 0.9996, cosSun);
    col += uSunColor * (halo + disc * 1.5);
    if (up > 0.01) {
        // Clouds on a plane overhead, stretched into bands, drifting slowly.
        vec2 cp = dir.xz / (up + 0.12) * 1.8 + vec2(uTime * 0.004, 0.0);
        float cloud = fbm3(vec2(cp.x * 0.35, cp.y * 1.1));
        float band = 0.5 + 0.5 * sin(cp.y * 1.3 + 1.7 * vnoise(cp * 0.3));
        float cover = smoothstep(0.52, 0.70, cloud * 0.75 + band * 0.35);
        float fadeH = smoothstep(0.02, 0.2, up);
        vec3 cloudCol = mix(vec3(0.80, 0.82, 0.86), vec3(1.0, 0.98, 0.95), smoothstep(0.5, 0.9, cloud));
        cloudCol += uSunColor * 0.15 * pow(max(cosSun, 0.0), 6.0);
        col = mix(col, cloudCol, cover * fadeH * 0.9);
    }
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
precision highp float;
in vec2 vUv;
in vec3 vWorld;
uniform sampler2D uTex;
uniform vec3 uCamPos;
uniform vec3 uFogColor;
uniform float uFogDensity;
out vec4 fragColor;
void main() {
    vec4 c = texture(uTex, vUv);
    if (c.a < 0.5) discard;
    float dist = length(vWorld - uCamPos);
    float fog = 1.0 - exp(-dist * uFogDensity);
    fragColor = vec4(mix(c.rgb, uFogColor, clamp(fog, 0.0, 1.0)), c.a);
}
"""
}
