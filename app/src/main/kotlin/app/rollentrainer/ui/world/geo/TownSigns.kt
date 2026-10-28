package app.rollentrainer.ui.world.geo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Faces of the town entry and exit signs. W2 places the panels with the material key
 * "sign_town:<style>:<in|out>:<name>" and no texture, because every town needs its own lettering;
 * this draws it on the streaming worker with the platform's Canvas (no font assets needed).
 *
 * Styles: "fr" is the French EB10 (white, red border, black capitals) and EB20 (the same, struck
 * through by a red bar); "es" is the Spanish S-500 / S-510 (white, blue border and lettering).
 * The UV square (0,0)–(1,1) covers the face, whose real aspect is [ASPECT]; the texture is 2:1 (power
 * of two for mipmaps), so the drawing is squeezed horizontally to come out right on the panel.
 */
internal object TownSigns {
    const val PREFIX = "sign_town:"
    /** The panel's width / height (W2's Mats.TOWN_SIGN_ASPECT). */
    private const val ASPECT = 2.6f

    fun isSign(key: String) = key.startsWith(PREFIX)

    /** Texture of the sign [key], [width] pixels wide (height width / 2). Worker thread. */
    fun render(key: String, width: Int): Materials.ObjectTex? {
        val parts = key.removePrefix(PREFIX).split(':', limit = 3)
        if (parts.size < 3) return null
        val kind = parts[0]
        val exit = parts[1] == "out"
        val name = parts[2]
        val w = width
        val h = width / 2
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        // Draw in panel units (H tall, H * ASPECT wide), squeezed into the 2:1 texture.
        val ph = h.toFloat()
        val pw = ph * ASPECT
        c.scale(w / pw, 1f)
        val ink = if (kind == "es") Color.rgb(22, 52, 128) else Color.rgb(18, 18, 18)
        val border = if (kind == "es") ink else Color.rgb(196, 28, 32)
        // Opaque everywhere so the mip chain never mixes in transparent black at the edge.
        if (kind == "es") {
            // The S-500: a thin blue line inside a white edge.
            c.drawColor(Color.rgb(236, 236, 230))
            val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink; style = Paint.Style.STROKE; strokeWidth = ph * 0.045f }
            c.drawRoundRect(ph * 0.07f, ph * 0.07f, pw - ph * 0.07f, ph - ph * 0.07f, ph * 0.08f, ph * 0.08f, line)
        } else {
            // The EB10: a broad red border with rounded inner corners.
            c.drawColor(border)
            val face = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(236, 236, 230) }
            val bw = ph * 0.085f
            c.drawRoundRect(bw, bw, pw - bw, ph - bw, ph * 0.06f, ph * 0.06f, face)
        }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ink
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
        }
        val label = name.uppercase()
        val maxW = pw - ph * 0.42f
        // Long names go on two lines, split at the hyphen or space nearest the middle.
        val lines = if (label.length > 13) splitName(label) else listOf(label)
        var size = ph * (if (lines.size == 1) 0.46f else 0.32f)
        text.textSize = size
        while (lines.maxOf { text.measureText(it) } > maxW && size > ph * 0.12f) {
            // Condense before shrinking, as the sign makers do.
            if (text.textScaleX > 0.8f) text.textScaleX = text.textScaleX - 0.05f else { size *= 0.94f; text.textSize = size }
        }
        val lineH = size * 1.08f
        val top = ph / 2f - lineH * lines.size / 2f
        for ((i, l) in lines.withIndex()) {
            val y = top + lineH * (i + 0.5f) - (text.descent() + text.ascent()) / 2f
            c.drawText(l, pw / 2f, y, text)
        }
        if (exit) {
            // EB20 / S-510: a red bar from the lower left to the upper right corner.
            val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.rgb(196, 28, 32); strokeWidth = ph * 0.11f; strokeCap = Paint.Cap.BUTT
            }
            c.drawLine(ph * 0.22f, ph * 0.86f, pw - ph * 0.22f, ph * 0.14f, bar)
        }
        val count = w * h
        val px = IntArray(count)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val albedo = ByteBuffer.allocateDirect(count * 4).order(ByteOrder.nativeOrder())
        for (p in px) {
            albedo.put(((p shr 16) and 0xFF).toByte()); albedo.put(((p shr 8) and 0xFF).toByte())
            albedo.put((p and 0xFF).toByte()); albedo.put(-1)
        }
        albedo.position(0)
        return Materials.ObjectTex(key, w, h, albedo, null)
    }

    private fun splitName(s: String): List<String> {
        val mid = s.length / 2
        var best = -1
        for (i in s.indices) if ((s[i] == '-' || s[i] == ' ') && (best < 0 || kotlin.math.abs(i - mid) < kotlin.math.abs(best - mid))) best = i
        if (best <= 0) return listOf(s)
        // Keep the hyphen on the first line, as on the real signs.
        val first = if (s[best] == '-') s.substring(0, best + 1) else s.substring(0, best)
        return listOf(first, s.substring(best + 1).trim())
    }
}
