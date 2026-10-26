package io.embodit.bianzhong.ui.chime.scene

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import androidx.compose.ui.graphics.toArgb
import io.embodit.bianzhong.R
import io.embodit.bianzhong.domain.model.BellSpec
import io.embodit.bianzhong.ui.theme.ChimeColors
import kotlin.math.acos
import kotlin.math.PI
import kotlin.random.Random

/**
 * Bitmaps drawn at run time: the bronze base-colour texture of the bells (bands, bosses fields, the
 * gold-inlaid inscription outlines on the drums, verdigris) and the twelve name plates.
 */
internal object BellArt {
    private const val TEX = 512

    /** Texture u of a point at x / a on the front face (phi in 0..pi): u = acos(x / a) / 2pi. */
    private fun uFront(xa: Float): Float = (acos(xa.coerceIn(-1f, 1f).toDouble()) / (2 * PI)).toFloat()

    private fun vOf(t: Float): Float = 0.15f + 0.85f * t

    /**
     * Bronze texture (u = around, v = 0 shank strip .. 1 mouth): the zheng panel and the three bosses bands
     * framed in dark bronze on both faces, the gold-inlaid inscription outlines on the front and side
     * drums (design: "baked into the base colour"), soft verdigris near the mouth and the shoulder.
     */
    fun bronzeTexture(): Bitmap {
        val bmp = Bitmap.createBitmap(TEX, TEX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val bronze = ChimeColors.Bronze.toArgb()
        val dark = ChimeColors.BronzeDark.toArgb()
        val gold = ChimeColors.GoldInlay.toArgb()
        val patina = ChimeColors.Patina.toArgb()
        c.drawColor(bronze)
        val rnd = Random(7)
        // verdigris mottling
        val blob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = patina }
        repeat(140) {
            val y = if (rnd.nextFloat() < 0.6f) TEX * (0.80f + rnd.nextFloat() * 0.20f) else TEX * (0.12f + rnd.nextFloat() * 0.12f)
            blob.alpha = 30 + rnd.nextInt(50)
            c.drawCircle(rnd.nextFloat() * TEX, y, 3f + rnd.nextFloat() * 10f, blob)
        }
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = dark
            style = Paint.Style.STROKE
            strokeWidth = 3f
        }
        val band = Paint().apply { color = dark }
        for (face in 0..1) {
            val shift = face * 0.5f
            fun x(u: Float) = (u + shift) * TEX
            // zheng: the centre panel between the bosses fields, t 0.04 .. 0.52
            val zl = uFront(0.30f)
            val zr = uFront(-0.30f)
            val top = vOf(0.04f) * TEX
            val bottom = vOf(0.52f) * TEX
            c.drawRect(x(zl), top, x(zr), bottom, line)
            // bosses fields on both sides with the zhuan bands between the rows
            for ((a, b) in listOf(uFront(0.92f) to uFront(0.34f), uFront(-0.34f) to uFront(-0.92f))) {
                c.drawRect(x(a), top, x(b), bottom, line)
                for (t in floatArrayOf(0.19f, 0.33f)) {
                    val y = vOf(t) * TEX
                    c.drawRect(x(a), y - 3f, x(b), y + 3f, band)
                }
            }
            // the drum zone border
            c.drawLine(x(uFront(0.98f)), vOf(0.58f) * TEX, x(uFront(-0.98f)), vOf(0.58f) * TEX, line)
        }
        // gold-inlaid inscription outlines: front drum (centre) and the two side drums, front face only
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = gold
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            strokeCap = Paint.Cap.ROUND
        }
        fun glyphs(u0: Float, u1: Float, t0: Float, t1: Float, cols: Int, rows: Int, seed: Int) {
            val r = Random(seed)
            val cw = (u1 - u0) * TEX / cols
            val rh = (vOf(t1) - vOf(t0)) * TEX / rows
            for (i in 0 until cols) for (j in 0 until rows) {
                val cx = u0 * TEX + cw * (i + 0.5f)
                val cy = vOf(t0) * TEX + rh * (j + 0.5f)
                val s = minOf(cw, rh) * 0.38f
                repeat(3 + r.nextInt(2)) {
                    val k = r.nextInt(4)
                    when (k) {
                        0 -> c.drawLine(cx - s, cy - s + r.nextFloat() * 2 * s, cx + s, cy - s + r.nextFloat() * 2 * s, ink)
                        1 -> c.drawLine(cx - s + r.nextFloat() * 2 * s, cy - s, cx - s + r.nextFloat() * 2 * s, cy + s, ink)
                        2 -> c.drawArc(RectF(cx - s, cy - s, cx + s, cy + s), r.nextFloat() * 360f, 120f + r.nextFloat() * 140f, false, ink)
                        else -> c.drawRect(cx - s * 0.6f, cy - s * 0.6f, cx + s * 0.6f, cy + s * 0.6f, ink)
                    }
                }
            }
        }
        // front drum: a cloud-scroll frame and two columns of signs
        val f0 = uFront(0.42f)
        val f1 = uFront(-0.42f)
        c.drawRoundRect(RectF(f0 * TEX, vOf(0.64f) * TEX, f1 * TEX, vOf(0.92f) * TEX), 10f, 10f, ink)
        glyphs(f0 + 0.01f, f1 - 0.01f, 0.66f, 0.90f, 3, 3, 11)
        // side drums near the corners
        glyphs(uFront(0.97f), uFront(0.62f), 0.66f, 0.90f, 1, 3, 23)
        glyphs(uFront(-0.62f), uFront(-0.97f), 0.66f, 0.90f, 1, 3, 29)
        // the shank strip (v < 0.12): plain bronze with a dark ring line
        c.drawRect(0f, 0.11f * TEX, TEX.toFloat(), 0.12f * TEX, band)
        // drawn with v = 0 at the top; the physically based material samples v from the bottom (seen on the
        // emulator: the inscriptions landed under the shoulder), so the bitmap goes in upside down
        val flipped = Bitmap.createBitmap(bmp, 0, 0, TEX, TEX, Matrix().apply { preScale(1f, -1f) }, false)
        bmp.recycle()
        return flipped
    }

    // ---- plates

    @Volatile private var plateFace: Typeface? = null

    private fun face(resources: Resources): Typeface {
        plateFace?.let { return it }
        val made =
            runCatching {
                    val latin = FontFamily.Builder(Font.Builder(resources, R.font.noto_sans_semibold).setWeight(600).build()).build()
                    val chinese = FontFamily.Builder(Font.Builder(resources, R.font.noto_sans_sc).setFontVariationSettings("'wght' 500").setWeight(500).build()).build()
                    Typeface.CustomFallbackBuilder(latin).addCustomFallback(chinese).setSystemFallback("sans-serif").build()
                }
                .getOrElse { Typeface.DEFAULT_BOLD }
        plateFace = made
        return made
    }

    const val PLATE_W = 512
    const val PLATE_H = 144

    /**
     * A name plate (design `bells.plates`): plate ink ground, tape-ink text, left half = front drum,
     * right half = side drum: "宫 C4 ｜ 角 E4" (Chinese) or "C4 ｜ E4" (English).
     */
    fun plate(resources: Resources, spec: BellSpec, english: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(PLATE_W, PLATE_H, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(ChimeColors.PlateInk.toArgb())
        val tf = face(resources)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChimeColors.TapeInk.toArgb()
            typeface = tf
            textAlign = Paint.Align.CENTER
            fontFeatureSettings = "tnum, lnum"
        }
        val rule = Paint().apply { color = ChimeColors.GoldInlay.toArgb() }
        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ChimeColors.GoldInlay.toArgb()
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }
        c.drawRect(4f, 4f, PLATE_W - 4f, PLATE_H - 4f, edge)
        c.drawRect(PLATE_W / 2f - 2f, 26f, PLATE_W / 2f + 2f, PLATE_H - 26f, rule)
        fun half(cx: Float, lv: String, note: String) {
            if (english) {
                text.textSize = 72f
                c.drawText(note, cx, PLATE_H / 2f + 26f, text)
            } else {
                val lvSize = if (lv.length > 1) 50f else 64f
                text.textSize = lvSize
                val lvW = text.measureText(lv)
                text.textSize = 60f
                val nW = text.measureText(note)
                val gap = 18f
                val total = lvW + gap + nW
                val start = cx - total / 2f
                text.textAlign = Paint.Align.LEFT
                text.textSize = lvSize
                c.drawText(lv, start, PLATE_H / 2f + 22f, text)
                text.textSize = 60f
                c.drawText(note, start + lvW + gap, PLATE_H / 2f + 22f, text)
                text.textAlign = Paint.Align.CENTER
            }
        }
        half(PLATE_W * 0.25f, spec.zheng.lvZh, spec.zheng.name)
        half(PLATE_W * 0.75f, spec.ce.lvZh, spec.ce.name)
        return bmp
    }
}
