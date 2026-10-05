package no.heimflyt.launcher.ui.home

import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import no.heimflyt.launcher.gesture.Fan
import no.heimflyt.launcher.gesture.GestureFrame
import no.heimflyt.launcher.gesture.Nest
import no.heimflyt.launcher.gesture.NestState
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.ui.theme.ColorTokens

/** H7.1 rehearsal guide. Real installed-app icons are visual only; recognition uses the same fan centres. */
class NestedGuide(private val density: Float) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dst = RectF()
    /** Icon centres in dp, [x, y] per slot, valid after [place] returns true. */
    val centers = FloatArray(8)

    fun place(f: GestureFrame, p: TuningParams, fan: Fan): Boolean {
        if (f.nest == NestState.LEVEL1) return false
        val axis = RadialGeometry.sectorAngle(f.sector ?: return false, p)
        for (slot in 0..3) {
            val (tx, ty) = Nest.target(slot, f.originX, f.originY, axis, p.leftHanded, fan, p.menuRadius)
            centers[slot * 2] = tx; centers[slot * 2 + 1] = ty
        }
        return true
    }

    /** Call only after [place] returned true for this frame. [hitAreas] outlines the touch targets (Debug geometry). */
    fun draw(scope: DrawScope, f: GestureFrame, p: TuningParams, fan: Fan, icons: Map<Int, Bitmap>, slots: Int,
             c: ColorTokens, guide: Boolean, hitAreas: Boolean, alpha: Float) = with(scope) {
        val d = density
        val canvas = drawContext.canvas.nativeCanvas
        val disc = (fan.size / 2f + DISC_PAD) * d
        for (slot in 0..3) {
            val selected = f.child == slot
            if (slots and (1 shl slot) == 0 || (!guide && !selected)) continue
            val center = Offset(centers[slot * 2] * d, centers[slot * 2 + 1] * d)
            if (hitAreas) drawCircle(c.inkMuted.copy(alpha = 0.4f * alpha), Nest.hitRadius(fan, p.menuRadius) * d, center, style = Stroke(d))
            drawCircle(c.raised.copy(alpha = 0.85f * alpha), disc, center)
            if (selected) drawCircle(c.accent.copy(alpha = alpha), disc + 2f * d, center, style = Stroke(3f * d))
            else drawCircle(c.inkMuted.copy(alpha = 0.5f * alpha), disc, center, style = Stroke(d))
            icons[slot]?.let { bitmap ->
                val half = fan.size / 2f * d
                dst.set(center.x - half, center.y - half, center.x + half, center.y + half)
                paint.alpha = (255 * alpha).toInt().coerceIn(0, 255)
                canvas.drawBitmap(bitmap, null, dst, paint)
            }
        }
    }

    private companion object { const val DISC_PAD = 4f }
}
