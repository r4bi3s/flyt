package no.heimflyt.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.core.content.ContextCompat
import no.heimflyt.launcher.ui.components.glyphFor

/** Raster copy of a built-in action glyph for the existing bounded radial icon renderer. */
fun actionIcon(context: Context, action: HomeAction.Semantic): Bitmap? = runCatching {
    val drawable = ContextCompat.getDrawable(context, glyphFor(action)) ?: return null
    val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
    Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bitmap ->
        val margin = size / 6
        drawable.setBounds(margin, margin, size - margin, size - margin)
        drawable.setTint(Color.WHITE)
        drawable.draw(Canvas(bitmap))
    }
}.getOrNull()
