package no.heimflyt.launcher.ui.home

import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import no.heimflyt.launcher.theme.image.Images
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.ui.theme.Heimflyt
import kotlin.math.abs

/** The three reviewed paths use the same 1000 × 1800 coordinates as the visual preview. */
private class SignalRoute(slot: Int) {
    val path = Path().apply {
        when (slot) {
            0 -> { moveTo(-100f, 590f); cubicTo(170f, 610f, 345f, 805f, 475f, 1040f)
                cubicTo(600f, 1270f, 620f, 1215f, 700f, 1040f); cubicTo(815f, 865f, 930f, 790f, 1100f, 870f) }
            1 -> { moveTo(-100f, 760f); cubicTo(190f, 800f, 285f, 1040f, 430f, 1000f)
                cubicTo(575f, 960f, 680f, 795f, 790f, 865f); cubicTo(905f, 930f, 970f, 1060f, 1100f, 1000f) }
            else -> { moveTo(-100f, 990f); cubicTo(180f, 990f, 320f, 1115f, 445f, 1180f)
                cubicTo(590f, 1250f, 700f, 1270f, 800f, 1220f); cubicTo(930f, 1155f, 990f, 1130f, 1100f, 1110f) }
        }
    }
    private val measure = PathMeasure(path, false)
    private val length = measure.length
    private val fractions = floatArrayOf(.23f, .365f, .5f, .635f, .77f)
    val points = fractions.map { at(it, FloatArray(2)) }
    fun at(fraction: Float, out: FloatArray): FloatArray {
        measure.getPosTan(length * fraction, out, null)
        return out
    }
    fun moving(progress: Float, out: FloatArray) = at(fractions.first() + (1f - fractions.first()) * progress, out)
    fun beacon(index: Int, progress: Float): Float {
        val current = fractions.first() + (1f - fractions.first()) * progress
        val near = (1f - abs(current - fractions[index]) / .055f).coerceIn(0f, 1f)
        return near * near * (3f - 2f * near)
    }
}

private fun signalPaint(color: Int, stroke: Float = 0f) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    this.color = color
    if (stroke > 0f) { style = Paint.Style.STROKE; strokeWidth = stroke; strokeCap = Paint.Cap.ROUND }
}

private fun signalGlow(gold: Int, radius: Float, alpha: Int) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    val center = (gold and 0x00ffffff) or (alpha shl 24)
    shader = RadialGradient(0f, 0f, radius, center, gold and 0x00ffffff, Shader.TileMode.CLAMP)
}

/** A short, idle-only animation. The background bitmap is never invalidated by its frames. */
@Composable
fun KretsSignalOverlay(slot: Int, framing: Framing, paused: Boolean, homeEpoch: Int, animationsOff: Boolean) {
    if (slot !in 0..2) return
    val route = remember(slot) { SignalRoute(slot) }
    val progress = remember(slot, homeEpoch) { Animatable(0f) }
    var played by remember(slot, homeEpoch) { mutableStateOf(false) }
    val owner = LocalLifecycleOwner.current
    var resumed by remember(owner) { mutableStateOf(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, _ -> resumed = owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(resumed, paused, slot, homeEpoch, animationsOff) {
        if (!resumed) { played = false; progress.snapTo(0f); return@LaunchedEffect }
        if (animationsOff) { progress.snapTo(0f); return@LaunchedEffect }
        if (paused || played) return@LaunchedEffect
        played = true
        delay(160)
        progress.animateTo(1f, tween(durationMillis = 8556, easing = LinearEasing))
    }

    val gold = Heimflyt.t.color.caution.toArgb()
    fun translucent(alpha: Int) = (gold and 0x00ffffff) or (alpha shl 24)
    val halo = remember(gold) { signalPaint(translucent(14), 12f) }
    val line = remember(gold) { signalPaint(translucent(128), 2.2f) }
    val pointHalo = remember(gold) { signalGlow(gold, 20f, 130) }
    val point = remember(gold) { signalPaint(translucent(190)) }
    val travelerHalo = remember(gold) { signalGlow(gold, 22f, 105) }
    val traveler = remember(gold) { signalPaint(translucent(255)) }
    val moving = remember { FloatArray(2) }
    Canvas(Modifier.fillMaxSize()) {
        // The prepared Home bitmap contains Images.crop(source, output, framing). Reuse that crop to align the path.
        val crop = Images.cropRect(1008, 1792, size.width.toInt(), size.height.toInt(), framing)
        val sourceScaleX = size.width / crop.width()
        val sourceScaleY = size.height / crop.height()
        val canvas = drawContext.canvas.nativeCanvas
        val save = canvas.save()
        canvas.translate(-crop.left * sourceScaleX, -crop.top * sourceScaleY)
        canvas.scale(sourceScaleX * 1008f / 1000f, sourceScaleY * 1792f / 1800f)
        canvas.drawPath(route.path, halo)
        canvas.drawPath(route.path, line)
        route.points.forEachIndexed { index, p ->
            val pulse = if (animationsOff) 0f else route.beacon(index, progress.value)
            pointHalo.alpha = (75f + 180f * pulse).toInt()
            point.alpha = (190f + 65f * pulse).toInt()
            val nodeSave = canvas.save()
            canvas.translate(p[0], p[1])
            canvas.drawCircle(0f, 0f, 20f, pointHalo)
            canvas.drawCircle(0f, 0f, 5f, point)
            canvas.restoreToCount(nodeSave)
        }
        val p = route.moving(progress.value, moving)
        val travelerSave = canvas.save()
        canvas.translate(p[0], p[1])
        canvas.drawCircle(0f, 0f, 22f, travelerHalo)
        canvas.drawCircle(0f, 0f, 7f, traveler)
        canvas.restoreToCount(travelerSave)
        canvas.restoreToCount(save)
    }
}
