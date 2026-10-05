package no.heimflyt.launcher.ui.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.text.TextUtils
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.core.content.ContextCompat
import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.gesture.Familiarity
import no.heimflyt.launcher.gesture.GestureFrame
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.SlotFamiliarity
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.label
import no.heimflyt.launcher.ui.components.glyphFor
import no.heimflyt.launcher.ui.theme.ColorTokens
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin

/** Pure pill metrics shared by the renderer and the layout tests (dp). */
object LabelMetrics {
    const val PAD = 11f
    const val ICON = 20f
    const val ICON_GAP = 6f
    const val TEXT_SP = 14f
    const val MAX_CHARS = 18
    fun height(fontScale: Float) = max(30f, 12f + TEXT_SP * fontScale * 1.3f)
    fun named(textWidth: Float, hasIcon: Boolean) = PAD + (if (hasIcon) ICON + ICON_GAP else 0f) + textWidth + PAD
    fun iconOnly() = PAD + ICON + PAD
    fun number(textWidth: Float) = 10f + textWidth + 10f
    fun displayText(text: String) = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS - 1) + "…"
}

/**
 * H7 physical finding: labels written along the ring beat the H5 pills (compact, no collisions with the child fan).
 * The pill path stays compiled, unreachable from Home, until H7 closes.
 */
const val ARC_LABELS = true

fun slotLabel(action: HomeAction) = if (action is HomeAction.Probe) "Unassigned" else action.label()

/**
 * Origin-independent presentation data, prepared when bindings, tuning, icons, font scale or bounds change (never on a
 * gesture's first frame). Paths and the halo are built at origin (0, 0) and translated when drawn.
 */
class RadialPrep(
    val key: List<Any?>, val p: TuningParams, val angles: List<Float>, val starts: FloatArray, val step: Float,
    val namedText: List<String>, val selectedText: List<String>, val numberText: List<String>, val numberWidthPx: FloatArray,
    val namedW: List<Float>, val numberW: List<Float>, val height: Float,
    val icons: List<Bitmap?>, val glyphs: List<Drawable?>, val sectorPaths: List<Path>, val halo: List<Brush>,
    /** Divider and tick endpoints relative to the origin, in px: [x0, y0, x1, y1] per sector. */
    val dividers: FloatArray, val ticks: FloatArray,
    val arc: ArcLabels,
)

/**
 * Experiment: labels written along the ring, one per sector, centred in its own arc (so they never collide). The upper
 * half reads clockwise and the lower half counter-clockwise, so no word is upside down. Paths are px at origin (0, 0).
 */
class ArcLabels(val paths: List<android.graphics.Path>, val named: List<String>, val namedOffset: FloatArray,
                val chosen: List<String>, val chosenOffset: FloatArray, val numberOffset: FloatArray) {
    companion object {
        /** Clear space between the drawn ring and the nearest edge of the words, dp. */
        const val GAP = 6f
        /** The upper half (and the exact sides) reads clockwise; the lower half is reversed to stay upright. */
        fun clockwise(angle: Float) = sin(Math.toRadians(angle.toDouble())) <= 1e-6
    }
}

/** Immutable per-gesture state (SURFACES.md §2.4): the frozen origin, stored levels and the precomputed label layouts. */
class RenderSnapshot(val prep: RadialPrep, val originX: Float, val originY: Float, val adaptive: Boolean, val stored: IntArray,
                     private val layouts: Array<List<PlacedLabel?>>) {
    fun level(slot: Int, escalation: Int) = if (adaptive) Familiarity.shown(stored[slot], escalation) else escalation.coerceIn(0, 2)
    fun layout(selected: Int?, escalation: Int) = layouts[((selected ?: -1) + 1) * 3 + escalation.coerceIn(0, 2)]
}

class RadialPainter(context: Context, private val density: Float) {
    private val app = context.applicationContext
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val monoPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val arcPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT }
    private val arcBold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val glyphCache = HashMap<Int, Drawable?>()
    private val levels = IntArray(8)

    private fun glyph(res: Int): Drawable? = glyphCache.getOrPut(res) { ContextCompat.getDrawable(app, res)?.mutate() }

    fun prepare(p0: TuningParams, bindings: List<HomeAction>, icons: Map<Int, Bitmap>, bounds: LRect, fontScale: Float, colors: ColorTokens): RadialPrep {
        val p = p0.safe()
        val n = p.sectorCount
        val sp = density * fontScale
        textPaint.textSize = LabelMetrics.TEXT_SP * sp
        monoPaint.textSize = 12f * sp
        val actions = List(n) { bindings.getOrElse(it) { HomeAction.Probe(it + 1) } }
        val angles = List(n) { RadialGeometry.sectorAngle(it, p) }
        val raw = List(n) { LabelMetrics.displayText(slotLabel(actions[it])) }
        val iconList = List(n) { icons[it] }
        val glyphList = List(n) { i -> if (actions[i] is HomeAction.App && icons[i] != null) null else glyph(glyphFor(actions[i])) }
        val namedW = List(n) { LabelMetrics.named(textPaint.measureText(raw[it]) / density, true) }
        val numbers = List(n) { "${it + 1}" }
        val numberPx = FloatArray(n) { monoPaint.measureText(numbers[it]) }
        val numberW = List(n) { LabelMetrics.number(numberPx[it] / density) }
        val maxSel = minOf(0.6f * bounds.w, 240f)
        val selText = List(n) { i ->
            val avail = (maxSel - LabelMetrics.named(0f, true)) * density
            if (namedW[i] <= maxSel) raw[i] else TextUtils.ellipsize(raw[i], textPaint, avail.coerceAtLeast(0f), TextUtils.TruncateAt.END).toString()
        }
        val step = p.arcSpan / n
        val starts = FloatArray(n) { i -> if (p.leftHanded) 180f - (p.arcStart + (i + 1) * step) else p.arcStart + i * step }
        val rOuter = p.menuRadius * density; val rInner = (p.deadZone + 6f) * density
        val outer = androidx.compose.ui.geometry.Rect(-rOuter, -rOuter, rOuter, rOuter)
        val inner = androidx.compose.ui.geometry.Rect(-rInner, -rInner, rInner, rInner)
        val paths = List(n) { i -> Path().apply { arcTo(outer, starts[i], step, true); arcTo(inner, starts[i] + step, -step, false); close() } }
        val dividers = FloatArray(n * 8)
        for (i in 0 until n) for ((k, edge) in floatArrayOf(starts[i], starts[i] + step).withIndex()) {
            val r = Math.toRadians(edge.toDouble()); val cx = cos(r).toFloat(); val cy = sin(r).toFloat(); val o = i * 8 + k * 4
            dividers[o] = cx * rInner; dividers[o + 1] = cy * rInner; dividers[o + 2] = cx * rOuter; dividers[o + 3] = cy * rOuter
        }
        val ticks = FloatArray(n * 4)
        for (i in 0 until n) {
            val a = Math.toRadians(angles[i].toDouble()); val cx = cos(a).toFloat(); val cy = sin(a).toFloat()
            ticks[i * 4] = cx * (p.menuRadius - 8f) * density; ticks[i * 4 + 1] = cy * (p.menuRadius - 8f) * density
            ticks[i * 4 + 2] = cx * rOuter; ticks[i * 4 + 3] = cy * rOuter
        }
        val haloAlphas = if (colors.isLight) listOf(0.40f, 0.52f, 0.72f) else listOf(0.30f, 0.42f, 0.62f)
        val halo = haloAlphas.map { a ->
            Brush.radialGradient(0f to colors.scrim.copy(alpha = a), 0.7f to colors.scrim.copy(alpha = a * 0.6f), 1f to Color.Transparent,
                center = Offset.Zero, radius = p.menuRadius * 1.9f * density)
        }
        return RadialPrep(prepKey(p0, bindings, icons, bounds, fontScale, colors), p, angles, starts, step, raw, selText, numbers, numberPx,
            namedW, numberW, LabelMetrics.height(fontScale), iconList, glyphList, paths, halo, dividers, ticks,
            arcLabels(p, raw, angles, starts, step, numberPx, sp))
    }

    private fun arcLabels(p: TuningParams, raw: List<String>, angles: List<Float>, starts: FloatArray, step: Float,
                          numberPx: FloatArray, sp: Float): ArcLabels {
        val n = p.sectorCount
        val size = LabelMetrics.TEXT_SP * sp
        arcPaint.textSize = size; arcBold.textSize = size
        // The words occupy roughly [ring + GAP, ring + GAP + size]: baseline low on the upper half, high on the lower half.
        val ring = (p.menuRadius + ArcLabels.GAP) * density
        val paths = List(n) { i ->
            val cw = ArcLabels.clockwise(angles[i])
            val r = ring + if (cw) 0.25f * size else 0.75f * size
            android.graphics.Path().apply {
                val oval = RectF(-r, -r, r, r)
                if (cw) addArc(oval, starts[i], step) else addArc(oval, starts[i] + step, -step)
            }
        }
        fun length(i: Int) = android.graphics.PathMeasure(paths[i], false).length
        fun fit(i: Int, text: String, paint: TextPaint) =
            TextUtils.ellipsize(text, paint, 0.9f * length(i), TextUtils.TruncateAt.END).toString()
        val named = List(n) { fit(it, raw[it], arcPaint) }
        val chosen = List(n) { fit(it, raw[it], arcBold) }
        return ArcLabels(paths, named, FloatArray(n) { (length(it) - arcPaint.measureText(named[it])) / 2 },
            chosen, FloatArray(n) { (length(it) - arcBold.measureText(chosen[it])) / 2 },
            FloatArray(n) { (length(it) - numberPx[it]) / 2 })
    }

    fun prepKey(p: TuningParams, bindings: List<HomeAction>, icons: Map<Int, Bitmap>, bounds: LRect, fontScale: Float, colors: ColorTokens): List<Any?> =
        listOf(p, bindings, icons, bounds, fontScale, colors)

    /** Per-gesture work on the first active frame: arithmetic only (≤ 27 label layouts from pre-measured sizes). */
    fun snapshot(frame: GestureFrame, prep: RadialPrep, adaptive: Boolean, familiarity: List<SlotFamiliarity>, bounds: LRect): RenderSnapshot {
        val p = prep.p; val n = p.sectorCount
        val stored = IntArray(n) { familiarity.getOrNull(it)?.level ?: Familiarity.FULL }
        fun lvl(i: Int, g: Int) = if (adaptive) Familiarity.shown(stored[i], g) else g
        val layouts = Array((n + 1) * 3) { idx ->
            val sel = idx / 3 - 1; val g = idx % 3
            val sizes = List(n) { i -> if (lvl(i, g) >= 2 || i == sel) LabelSize(prep.namedW[i], LabelMetrics.iconOnly(), prep.height) else LabelSize(prep.numberW[i], 0f, prep.height) }
            LabelLayout.layout(frame.originX, frame.originY, bounds, prep.angles, p.menuRadius, p.deadZone, sizes, List(n) { i -> lvl(i, g) >= 1 }, if (sel < 0) null else sel)
        }
        return RenderSnapshot(prep, frame.originX, frame.originY, adaptive, stored, layouts)
    }

    /**
     * Draws one gesture frame. [helpAlpha] fades elements revealed by hesitation (above their stored level); [fade] fades
     * the whole radial after release or cancel (1 = fully visible).
     * [arc] writes the labels along the ring (Home, [ARC_LABELS]); otherwise they are the H5 pills. [others] and [chosen]
     * scale the unselected and the selected label (H7 hands the screen to the child fan). Sector wedges and the selected
     * arc never fade.
     */
    fun draw(scope: DrawScope, s: RenderSnapshot, f: GestureFrame, c: ColorTokens, helpAlpha: Float, fade: Float, commitFlash: Boolean,
             others: Float = 1f, chosen: Float = 1f, arc: Boolean = false) = with(scope) {
        val d = density
        val prep = s.prep; val p = prep.p; val n = p.sectorCount
        val g = f.guidance.coerceIn(0, 2)
        var top = 0
        for (i in 0 until n) { levels[i] = s.level(i, g); if (levels[i] > top) top = levels[i] }
        val sel = f.sector
        val ox = s.originX * d; val oy = s.originY * d
        translate(ox, oy) {
            if (!commitFlash) {
                drawCircle(prep.halo[top.coerceIn(0, 2)], p.menuRadius * 1.9f * d, Offset.Zero, alpha = fade)
                for (i in 0 until n) {
                    val a = fade * (if (s.adaptive && levels[i] > s.stored[i]) helpAlpha else 1f)
                    when (levels[i]) {
                        2 -> {
                            drawPath(prep.sectorPaths[i], c.ink.copy(alpha = 0.10f * a))
                            val o = i * 8
                            drawLine(c.ink.copy(alpha = 0.22f * a), Offset(prep.dividers[o], prep.dividers[o + 1]), Offset(prep.dividers[o + 2], prep.dividers[o + 3]), d)
                            drawLine(c.ink.copy(alpha = 0.22f * a), Offset(prep.dividers[o + 4], prep.dividers[o + 5]), Offset(prep.dividers[o + 6], prep.dividers[o + 7]), d)
                        }
                        1 -> drawLine(c.ink.copy(alpha = 0.55f * a), Offset(prep.ticks[i * 4], prep.ticks[i * 4 + 1]), Offset(prep.ticks[i * 4 + 2], prep.ticks[i * 4 + 3]), 2f * d)
                    }
                }
            }
            if (sel != null && sel in 0 until n) {
                val grow = if (commitFlash) 1f + 0.06f * (1f - fade) else 1f
                scale(grow, Offset.Zero) {
                    drawPath(prep.sectorPaths[sel], c.accent.copy(alpha = 0.38f * fade))
                    val r = (p.menuRadius - 2f) * d
                    drawArc(c.accent.copy(alpha = fade), prep.starts[sel], prep.step, false, Offset(-r, -r), Size(2 * r, 2 * r), style = Stroke(4f * d))
                }
            }
            if (!commitFlash) drawCircle(c.ink.copy(alpha = 0.55f * fade), p.deadZone * d, Offset.Zero, style = Stroke(1.5f * d))
        }
        if (commitFlash) return@with
        val dist = hypot(f.x - s.originX, f.y - s.originY)
        if (f.active && dist > p.deadZone) {
            val ux = (f.x - s.originX) / dist; val uy = (f.y - s.originY) / dist
            drawLine(c.accent.copy(alpha = 0.9f * fade), Offset(ox + ux * p.deadZone * d, oy + uy * p.deadZone * d), Offset(f.x * d, f.y * d), 2f * d)
        }
        if (arc) { drawArcLabels(s, sel, levels, c, helpAlpha, fade, others, chosen); return@with }
        val layout = s.layout(sel, g)
        val canvas = drawContext.canvas.nativeCanvas
        val iconPx = LabelMetrics.ICON * d
        for (i in 0 until n) {
            val placed = layout[i] ?: continue
            val isSel = i == sel
            val alpha = (255 * fade * (if (isSel) chosen else others) * (if (!isSel && s.adaptive && levels[i] > s.stored[i]) helpAlpha else 1f)).toInt()
            if (alpha <= 0) continue
            val fg = (if (isSel) c.onAccent else c.inkStrong).toArgb()
            val r = placed.rect
            rect.set(r.l * d, r.t * d, r.r * d, r.b * d)
            fill.color = (if (isSel) c.accent else c.raised).toArgb(); fill.alpha = alpha
            canvas.drawRoundRect(rect, 10f * d, 10f * d, fill)
            val cy = rect.centerY()
            if (!isSel && levels[i] == 1) {
                monoPaint.color = fg; monoPaint.alpha = alpha
                canvas.drawText(prep.numberText[i], rect.centerX() - prep.numberWidthPx[i] / 2, cy - (monoPaint.ascent() + monoPaint.descent()) / 2, monoPaint)
                continue
            }
            var x = rect.left + LabelMetrics.PAD * d
            val bmp = prep.icons[i]
            if (bmp != null) {
                rect.set(x, cy - iconPx / 2, x + iconPx, cy + iconPx / 2); fill.alpha = alpha
                canvas.drawBitmap(bmp, null, rect, fill)
            } else prep.glyphs[i]?.let { gl ->
                gl.setTint(fg); gl.alpha = alpha
                gl.setBounds(x.toInt(), (cy - iconPx / 2).toInt(), (x + iconPx).toInt(), (cy + iconPx / 2).toInt()); gl.draw(canvas)
            }
            if (placed.kind == LabelKind.NAMED) {
                x += iconPx + LabelMetrics.ICON_GAP * d
                textPaint.color = fg; textPaint.alpha = alpha
                canvas.drawText(if (isSel) prep.selectedText[i] else prep.namedText[i], x, cy - (textPaint.ascent() + textPaint.descent()) / 2, textPaint)
            }
        }
    }

    /** Arc style: words only, no pills or icons. The selected direction is bold in the accent ink. */
    private fun DrawScope.drawArcLabels(s: RenderSnapshot, sel: Int?, levels: IntArray, c: ColorTokens, helpAlpha: Float,
                                        fade: Float, others: Float, chosen: Float) {
        val prep = s.prep; val arc = prep.arc; val n = prep.p.sectorCount
        val canvas = drawContext.canvas.nativeCanvas
        val shadow = c.scrim.toArgb()
        canvas.save(); canvas.translate(s.originX * density, s.originY * density)
        for (i in 0 until n) {
            val isSel = i == sel
            if (!isSel && levels[i] < 1) continue
            val alpha = (255 * fade * (if (isSel) chosen else others) * (if (!isSel && s.adaptive && levels[i] > s.stored[i]) helpAlpha else 1f)).toInt()
            if (alpha <= 0) continue
            val paint = when { isSel -> arcBold; levels[i] == 1 -> monoPaint; else -> arcPaint }
            paint.color = (if (isSel) c.accentInk else c.inkStrong).toArgb(); paint.alpha = alpha
            paint.setShadowLayer(3f * density, 0f, 0f, shadow)
            val (text, offset) = when {
                isSel -> arc.chosen[i] to arc.chosenOffset[i]
                levels[i] == 1 -> prep.numberText[i] to arc.numberOffset[i]
                else -> arc.named[i] to arc.namedOffset[i]
            }
            canvas.drawTextOnPath(text, arc.paths[i], offset, 0f, paint)
            paint.clearShadowLayer()
        }
        canvas.restore()
    }
}
