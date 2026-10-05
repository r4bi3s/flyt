package no.heimflyt.launcher.theme.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import no.heimflyt.launcher.theme.palette.Contrast
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.store.BackgroundKind
import kotlin.math.floor
import kotlin.math.max

/**
 * Heimflyt Grounds (VISUAL_SYSTEM.md §8.3): original, palette-derived static backgrounds, rendered once into a software
 * bitmap off the main thread at apply time (or at thumbnail size for previews).
 */
object Grounds {
    private fun opaque(rgb: Int) = rgb or (0xff shl 24)

    fun render(kind: BackgroundKind, t: ResolvedColors, w: Int, h: Int, seed: Int, density: Float): Bitmap {
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(opaque(t.ground))
        when (kind) {
            BackgroundKind.DUSK -> dusk(canvas, t, w, h)
            BackgroundKind.CONTOUR -> contour(canvas, t, w, h, seed, density)
            BackgroundKind.KRETS -> krets(canvas, t, w, h, density)
            else -> Unit
        }
        return bmp
    }

    /** Quiet concentric orbits below Home's clock. Rendered once, then checked by the normal zone solver. */
    private fun krets(c: Canvas, t: ResolvedColors, w: Int, h: Int, density: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(opaque(t.groundDeep), opaque(t.ground), opaque(Contrast.mix(t.ground, t.accent, 0.09))),
            null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.strokeWidth = max(1f, density * 0.8f)
        val cx = w * 0.52f
        val cy = h * 0.79f
        val unit = w * 0.19f
        for (i in 1..7) {
            p.color = opaque(if (i == 3) t.accent else t.hairline)
            p.alpha = if (i == 3) 95 else 45 - i * 3
            c.drawCircle(cx, cy, unit * i, p)
        }
        p.style = Paint.Style.FILL
        p.color = opaque(t.accent)
        p.alpha = 145
        c.drawCircle(cx + unit * 3f, cy, max(2f, density * 2.4f), p)
    }

    /** `groundDeep` (top) → `ground` (65%), plus a soft elliptical `mix(ground, accent, 0.10)` glow at the bottom (120% × 30%). */
    private fun dusk(c: Canvas, t: ResolvedColors, w: Int, h: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), intArrayOf(opaque(t.groundDeep), opaque(t.ground), opaque(t.ground)),
            floatArrayOf(0f, 0.65f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        val glow = Contrast.mix(t.ground, t.accent, 0.10)
        val rw = w * 0.6f; val rh = h * 0.3f
        val g = RadialGradient(0f, 0f, 1f, opaque(glow), glow and 0xffffff, Shader.TileMode.CLAMP)
        g.setLocalMatrix(Matrix().apply { setScale(rw, rh); postTranslate(w / 2f, h.toFloat()) })
        p.shader = g
        c.drawRect(0f, h - rh, w.toFloat(), h.toFloat(), p)
    }

    /**
     * Topographic iso-lines of a deterministic value-noise field (seed = theme id): marching squares over a coarse grid,
     * `hairline @ 0.55`, 1 dp, fading to nothing across the top 34 % so the clock and date sit on clean ground.
     */
    private fun contour(c: Canvas, t: ResolvedColors, w: Int, h: Int, seed: Int, density: Float) {
        val gx = 72; val gy = 144
        val field = Array(gy + 1) { y -> FloatArray(gx + 1) { x -> noise(x.toFloat() / gx * 3.2f, y.toFloat() / gy * 6.4f, seed) } }
        val levels = 14
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = max(1f, density); strokeCap = Paint.Cap.ROUND; color = opaque(t.hairline)
        }
        val cw = w.toFloat() / gx; val ch = h.toFloat() / gy
        val fadeEnd = 0.34f * h
        val pts = FloatArray(4)
        for (level in 1..levels) {
            val iso = level / (levels + 1f)
            for (y in 0 until gy) {
                val cy = (y + 0.5f) * ch
                val a = 0.55f * smooth(((cy - fadeEnd * 0.5f) / (fadeEnd * 0.5f)).coerceIn(0f, 1f))
                if (a <= 0f) continue
                paint.alpha = (a * 255).toInt()
                for (x in 0 until gx) {
                    val v0 = field[y][x]; val v1 = field[y][x + 1]; val v2 = field[y + 1][x + 1]; val v3 = field[y + 1][x]
                    val idx = (if (v0 > iso) 1 else 0) or (if (v1 > iso) 2 else 0) or (if (v2 > iso) 4 else 0) or (if (v3 > iso) 8 else 0)
                    if (idx == 0 || idx == 15) continue
                    fun edge(e: Int, o: Int) {
                        val (fx, fy) = when (e) {
                            0 -> Pair(x + lerp(v0, v1, iso), y.toFloat())
                            1 -> Pair(x + 1f, y + lerp(v1, v2, iso))
                            2 -> Pair(x + lerp(v3, v2, iso), y + 1f)
                            else -> Pair(x.toFloat(), y + lerp(v0, v3, iso))
                        }
                        pts[o] = fx * cw; pts[o + 1] = fy * ch
                    }
                    for ((e1, e2) in SEGMENTS[idx]) { edge(e1, 0); edge(e2, 2); c.drawLine(pts[0], pts[1], pts[2], pts[3], paint) }
                }
            }
        }
    }

    private val SEGMENTS: Array<List<Pair<Int, Int>>> = arrayOf(
        emptyList(), listOf(3 to 0), listOf(0 to 1), listOf(3 to 1), listOf(1 to 2), listOf(3 to 0, 1 to 2), listOf(0 to 2), listOf(3 to 2),
        listOf(2 to 3), listOf(2 to 0), listOf(0 to 1, 2 to 3), listOf(2 to 1), listOf(1 to 3), listOf(1 to 0), listOf(0 to 3), emptyList(),
    )

    private fun lerp(a: Float, b: Float, iso: Float) = if (b == a) 0.5f else ((iso - a) / (b - a)).coerceIn(0f, 1f)
    private fun smooth(x: Float) = x * x * (3 - 2 * x)

    /** Two octaves of smooth value noise, normalised to about 0..1. */
    private fun noise(x: Float, y: Float, seed: Int): Float = (valueNoise(x, y, seed) * 0.7f + valueNoise(x * 2.1f, y * 2.1f, seed * 31 + 7) * 0.3f)
    private fun valueNoise(x: Float, y: Float, seed: Int): Float {
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val fx = smooth(x - x0); val fy = smooth(y - y0)
        fun r(i: Int, j: Int): Float { var n = i * 374761393 + j * 668265263 + seed * 1442695041; n = (n xor (n ushr 13)) * 1274126177; return ((n xor (n ushr 16)) and 0xffff) / 65535f }
        val a = r(x0, y0) + (r(x0 + 1, y0) - r(x0, y0)) * fx
        val b = r(x0, y0 + 1) + (r(x0 + 1, y0 + 1) - r(x0, y0 + 1)) * fx
        return a + (b - a) * fy
    }
}
