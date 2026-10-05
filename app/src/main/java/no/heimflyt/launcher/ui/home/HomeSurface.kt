package no.heimflyt.launcher.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.LocalSettings
import no.heimflyt.launcher.RadialTouchView
import no.heimflyt.launcher.gesture.*
import no.heimflyt.launcher.theme.image.HomeLayout
import no.heimflyt.launcher.theme.image.Protection
import no.heimflyt.launcher.ui.components.StatusPill
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Motion
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import java.util.Date
import java.util.Locale


@Composable
fun HomeSurface(
    settings: LocalSettings,
    settingsLoaded: Boolean,
    status: String?,
    /** H7.1 rehearsal readout under the clock; null when off. */
    h7Readout: String?,
    h7Children: Map<Int, no.heimflyt.launcher.H7.Children>,
    boundIcons: Map<Int, Bitmap>,
    /** Opaque local backdrops for text whose zone the active image did not verify (VISUAL_SYSTEM.md §8.2); null over ground. */
    protection: Protection?,
    onDismissStatus: () -> Unit,
    onTouch: (RadialTouchView) -> Unit,
    onBegin: () -> Unit,
    onResult: (GestureResult, GestureFrame) -> Unit,
    onAccessibleDirection: (HomeAction) -> Unit,
    onApps: () -> Unit, onSearch: () -> Unit, onTune: () -> Unit,
) {
    val t = Heimflyt.t; val c = t.color
    val p = settings.tuning
    val context = LocalContext.current
    val density = LocalDensity.current
    val frame = remember { mutableStateOf(GestureFrame()) }
    val painter = remember(density.density) { RadialPainter(context, density.density) }
    // Presentation snapshot of the current gesture (not state: it is replaced before the frame that uses it is published).
    val snapshot = remember { arrayOfNulls<RenderSnapshot>(1) }
    // Origin-independent radial data, prepared after Home's first frame and whenever its inputs change.
    val prepared = remember { arrayOfNulls<RadialPrep>(1) }
    // H7.1 experiment: created only if a nested gesture is ever drawn.
    val nestedGuide = remember(density.density) { lazy { NestedGuide(density.density) } }
    val gestureChildren = remember { arrayOf(emptyMap<Int, no.heimflyt.launcher.H7.Children>()) }
    val nested = remember(settings.home.h7, settings.home.h7Fan, settings.bindings, settings.tagGroups, settings.tagModes,
        settings.autoTagGroups, p.sectorCount, h7Children) { no.heimflyt.launcher.H7.config(settings, h7Children) }
    val help = remember { Animatable(1f) }
    val fade = remember { Animatable(0f) }
    var commitFlash by remember { mutableStateOf(false) }
    var lastEscalation by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    val learning = homeLearning(settings.home.cleanDispatches, settings.home.hints)
    val directions = remember(settings.bindings, p, settingsLoaded) {
        if (settingsLoaded) AccessibleDirections.from(settings.bindings, p) else null
    }
    val latestSettings by rememberUpdatedState(settings)
    val latestIcons by rememberUpdatedState(boundIcons)
    val latestColors by rememberUpdatedState(c)
    val animationsOff = t.animationsOff

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val minMargin = p.affordanceSize / 2 + p.deadZone + 8f
        val margin = minOf(minMargin, maxWidth.value / 2)
        val side = p.edgeDistance.coerceIn(margin, maxOf(margin, maxWidth.value - margin))
        val cx = if (p.leftHanded) side else maxWidth.value - side
        val verticalMargin = minOf(minMargin, maxHeight.value / 2)
        val cy = (maxHeight.value - p.bottomDistance).coerceIn(verticalMargin, maxOf(verticalMargin, maxHeight.value - verticalMargin))
        val bounds = LRect(12f, 12f, maxWidth.value - 12f, maxHeight.value - 12f)
        val latestBounds by rememberUpdatedState(bounds)
        LaunchedEffect(p, settings.bindings, boundIcons, bounds, density.fontScale, c) {
            withFrameNanos { }
            prepared[0] = painter.prepare(p, settings.bindings, boundIcons, bounds, density.fontScale, c)
        }

        AndroidView(factory = { ctx -> RadialTouchView(ctx).also(onTouch) }, modifier = Modifier.fillMaxSize(), update = { view ->
            view.tuning = if (p.adaptive) p.copy(initialGuidance = 0) else p
            view.centerXdp = cx; view.centerYdp = cy
            view.nested = nested
            view.childCommits = no.heimflyt.launcher.H7.launches(settings)
            view.accessibleDirections = directions
            view.onAccessibleDirection = onAccessibleDirection
            view.onBegin = { gestureChildren[0] = h7Children; onBegin() }
            view.onFrame = { f ->
                if (f.active && !frame.value.active) {
                    // R5: the first active frame carries the frozen origin; build the presentation snapshot before drawing it.
                    val s = latestSettings
                    val key = painter.prepKey(s.tuning, s.bindings, latestIcons, latestBounds, density.fontScale, latestColors)
                    val prep = prepared[0]?.takeIf { it.key == key }
                        ?: painter.prepare(s.tuning, s.bindings, latestIcons, latestBounds, density.fontScale, latestColors).also { prepared[0] = it }
                    snapshot[0] = painter.snapshot(f, prep, s.tuning.adaptive, s.familiarity, latestBounds)
                    lastEscalation = f.guidance
                    commitFlash = false
                    scope.launch { fade.snapTo(1f); help.snapTo(1f) }
                }
                if (f.active && f.guidance > lastEscalation) {
                    lastEscalation = f.guidance
                    scope.launch { if (animationsOff) help.snapTo(1f) else { help.snapTo(0f); help.animateTo(1f, tween(Motion.QUICK)) } }
                }
                frame.value = f
            }
            view.onResult = { result ->
                val f = frame.value
                onResult(result, f)
                // Dispatch has already happened; the flash/fade is paint only and never delays it.
                commitFlash = result is GestureResult.Selected
                scope.launch { if (animationsOff) fade.snapTo(0f) else fade.animateTo(0f, tween(Motion.QUICK)) }
            }
        })

        Canvas(Modifier.fillMaxSize()) {
            val d = density.density
            val f = frame.value
            val mark = Offset(cx * d, cy * d)
            drawCircle(c.scrim.copy(alpha = 0.45f), 9f * d, mark)
            drawCircle(c.accent, 5f * d, mark)
            if (learning || !p.anywhere) drawCircle(c.ink.copy(alpha = 0.45f), p.affordanceSize * d / 2, mark, style = Stroke(1.5f * d))
            val s = snapshot[0]
            if (s != null) {
                val alpha = if (f.active) 1f else fade.value
                // H7.1: after the lock, level-1 labels hand over to the fan as the thumb travels out (and back as it returns).
                val fan = settings.home.h7Fan
                val reveal = if (f.nest == NestState.LEVEL1) 0f
                    else Nest.reveal(kotlin.math.hypot(f.x - f.originX, f.y - f.originY), fan, p.menuRadius)
                if (alpha > 0f) painter.draw(this, s, f, c, help.value, alpha, commitFlash && !f.active,
                    arc = ARC_LABELS, others = 1f - 0.8f * reveal, chosen = 1f - reveal)
                if (alpha > 0f && reveal > 0f && nestedGuide.value.place(f, p, fan))
                    nestedGuide.value.draw(this, f, p, fan, gestureChildren[0][f.sector]?.icons.orEmpty(), gestureChildren[0][f.sector]?.mask ?: 0,
                        c, settings.home.h7Guide, p.debug, alpha * reveal)
            }
            if (p.debug) drawDebug(p, f, settings, d, c.inkMuted, c.hairline)
        }

        // Home's layout constants are shared with the zone solver (HomeLayout), so the solved zones are these positions.
        Column(Modifier.align(Alignment.TopStart).padding(start = HomeLayout.GUTTER.dp, top = HomeLayout.CLOCK_TOP.dp, end = HomeLayout.GUTTER.dp)
            .then(if (protection?.clockBackdrop == true) Modifier.background(c.raised, Shapes.m).padding(horizontal = Space.m, vertical = Space.xs) else Modifier)) {
            HomeClock(settings.home.weekNumber, overImage = protection != null)
            if (h7Readout != null) Text(h7Readout + if (settings.home.h7) "\n" + (h7Children.values.joinToString(" · ") { "${it.group.name} ${it.icons.size}" }
                    .ifEmpty { "No explicit group configured" }) else "",
                style = t.type.caption.copy(color = c.inkStrong),
                modifier = Modifier.padding(top = Space.xs).background(c.raised, Shapes.s).padding(horizontal = Space.s, vertical = Space.xxs))
        }
        status?.let {
            StatusPill(it, onDismissStatus, Modifier.align(Alignment.BottomCenter).padding(bottom = 76.dp, start = Space.gutter, end = Space.gutter))
        }
        if (learning) {
            Text(if (p.anywhere) "Touch anywhere, slide, release\nTap ● for your directions" else "Touch the circle, slide, release\nTap ● for your directions",
                style = t.type.caption, textAlign = if (p.leftHanded) TextAlign.Start else TextAlign.End,
                modifier = Modifier.align(if (p.leftHanded) Alignment.TopStart else Alignment.TopEnd)
                    .offset(y = (cy + p.affordanceSize / 2 + 12f).dp).padding(horizontal = Space.gutter)
                    // The caption is not a solved zone: over any image it sits on an opaque backdrop.
                    .then(if (protection != null) Modifier.background(c.raised, Shapes.s).padding(horizontal = Space.s, vertical = Space.xxs) else Modifier))
        }
        CornerWords(learning, p.leftHanded, settings.home.appsOnThumb, protection?.cornerBackdrop == true, protection != null, protection != null, onApps,
            onSearch.takeIf { settings.home.searchWord }, onTune, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun CornerWords(learning: Boolean, leftHanded: Boolean, appsOnThumb: Boolean, wordsBackdrop: Boolean, tuneBackdrop: Boolean, overImage: Boolean, onApps: () -> Unit, onSearch: (() -> Unit)?, onTune: () -> Unit, modifier: Modifier) {
    val t = Heimflyt.t
    @Composable fun word(text: String, strong: Boolean, onClick: () -> Unit, backdrop: Boolean = wordsBackdrop) {
        Box(Modifier.widthIn(min = HomeLayout.CORNER_MIN_W.dp).heightIn(min = HomeLayout.CORNER_MIN_H.dp).clip(Shapes.s)
            .then(if (backdrop) Modifier.background(t.color.raised) else Modifier).clickable(role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center) {
            Text(text, style = t.type.label.copy(color = if (strong) t.color.inkStrong else t.color.inkMuted,
                shadow = if (overImage && !backdrop) glyphShadow(t.color.scrim, 6f) else null))
        }
    }
    Row(modifier.fillMaxWidth().padding(horizontal = HomeLayout.CORNER_PAD.dp).padding(bottom = HomeLayout.CORNER_PAD.dp), horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        // The thumb corner holds Apps (default) or Search. Search hidden (H6.1), its place stays empty so Apps never moves.
        @Composable fun search() = if (onSearch != null) word("Search", true, onSearch) else Spacer(Modifier.widthIn(min = HomeLayout.CORNER_MIN_W.dp))
        @Composable fun apps() = word("Apps", true, onApps)
        @Composable fun thumb() = if (appsOnThumb) apps() else search()
        @Composable fun far() = if (appsOnThumb) search() else apps()
        if (leftHanded) thumb() else far()
        if (learning) word("Tune", false, onTune, backdrop = tuneBackdrop)
        if (leftHanded) far() else thumb()
    }
}

@Composable
private fun HomeClock(weekPref: Boolean?, overImage: Boolean) {
    val t = Heimflyt.t
    val now = rememberClock()
    val locale = Locale.getDefault()
    val showWeek = weekPref ?: weekNumberDefault(locale)
    val time = remember(now, locale) { HomeDate.time(now, locale) }
    val date = remember(now, locale, showWeek) { HomeDate.date(now, locale, showWeek) }
    // Over a photo the words carry their own soft shadow (owner decision), and the date uses the stronger ink.
    Text(time, style = if (overImage) t.type.display.copy(shadow = glyphShadow(t.color.scrim, 10f)) else t.type.display)
    Text(date, style = if (overImage) t.type.dateline.copy(color = t.color.inkStrong, shadow = glyphShadow(t.color.scrim, 6f)) else t.type.dateline)
}

@Composable
internal fun rememberClock(): Date {
    val context = LocalContext.current; val lifecycle = LocalLifecycleOwner.current.lifecycle
    var now by remember { mutableStateOf(Date()) }
    DisposableEffect(context, lifecycle) {
        var registered = false
        val receiver = object : BroadcastReceiver() { override fun onReceive(c: Context?, i: Intent?) { now = Date() } }
        fun update() {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && !registered) {
                now = Date()
                val filter = IntentFilter().apply { addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED) }
                if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
                else context.registerReceiver(receiver, filter)
                registered = true
            } else if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && registered) {
                context.unregisterReceiver(receiver); registered = false
            }
        }
        val observer = LifecycleEventObserver { _, _ -> update() }
        lifecycle.addObserver(observer); update()
        onDispose { lifecycle.removeObserver(observer); if (registered) context.unregisterReceiver(receiver) }
    }
    return now
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawDebug(p: TuningParams, f: GestureFrame, settings: LocalSettings, d: Float,
    text: androidx.compose.ui.graphics.Color, line: androidx.compose.ui.graphics.Color) {
    if (p.anywhere) {
        val inset = RadialGeometry.anywhereInset(p) * d
        drawRect(line, Offset(inset, inset), androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset), style = Stroke(d))
    }
    // Debug only (off by default): allocation here is acceptable.
    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = text.toArgb(); textSize = 12 * d }
    val canvas = drawContext.canvas.nativeCanvas
    val learned = if (p.adaptive) settings.familiarity.take(p.sectorCount).mapIndexed { i, e -> "${i + 1}${Familiarity.name(e.level).first()}${e.streak}" }.joinToString(" ") else "adaptation off"
    canvas.drawText("Learned: $learned · home ${settings.home.cleanDispatches}", 16 * d, size.height - 124 * d, paint)
    if (f.active) {
        val angle = RadialGeometry.angle(f.x - f.originX, f.y - f.originY).toInt()
        canvas.drawText("${f.elapsed} ms · ${angle}° · slot ${f.sector?.plus(1) ?: "–"} · help ${f.guidance}${if (f.hesitated) " hesitated" else ""}", 16 * d, size.height - 104 * d, paint)
    }
}

/** A soft shadow hugging the glyphs, in the theme's scrim colour, so words read over a photo without shading the photo. */
@Composable
internal fun glyphShadow(scrim: androidx.compose.ui.graphics.Color, blurDp: Float): androidx.compose.ui.graphics.Shadow {
    val d = LocalDensity.current.density
    return androidx.compose.ui.graphics.Shadow(scrim.copy(alpha = 0.9f), Offset(0f, d), blurDp * d)
}
