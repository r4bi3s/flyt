package no.heimflyt.launcher.ui.themes

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.HeimflytApplication
import no.heimflyt.launcher.R
import no.heimflyt.launcher.theme.create.AccentChoice
import no.heimflyt.launcher.theme.create.Analysis
import no.heimflyt.launcher.theme.create.CreatedThemes
import no.heimflyt.launcher.theme.create.ModeChoice
import no.heimflyt.launcher.theme.create.Oklab
import no.heimflyt.launcher.theme.create.PaletteGenerator
import no.heimflyt.launcher.theme.create.Photo
import no.heimflyt.launcher.theme.create.PhotoException
import no.heimflyt.launcher.theme.create.PhotoPrep
import no.heimflyt.launcher.theme.create.ThemeNames
import no.heimflyt.launcher.theme.create.Variant
import no.heimflyt.launcher.theme.image.Images
import no.heimflyt.launcher.theme.image.Resolution
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.store.AndroidThemeFiles
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.theme.store.BackgroundMode
import no.heimflyt.launcher.theme.store.CreateRecipe
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.ImageSlot
import no.heimflyt.launcher.theme.store.MAX_THEME_IMAGES
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.Strength
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import no.heimflyt.launcher.ui.theme.ThemedPreview
import no.heimflyt.launcher.ui.theme.rgb
import java.io.File

/** Maximum composition zoom (the crop stays ≥ 1/4 of the frame's shorter side). */
private const val MAX_ZOOM = 4f

/** What the Create screen starts from: a freshly picked photo, or an existing created theme (Edit). */
sealed interface CreateSource {
    data class Picked(val uri: Uri) : CreateSource
    data class Edit(val id: String) : CreateSource
}

private class Loaded(val photos: List<Photo>, val editId: String?, val name: String?, val recipe: CreateRecipe?, val saved: OmarchyPalette?,
                     val pin: AutoCloseable?)

/** The "Keep" card: the active theme's resolved canonical palette, copied by value (CREATE_THEME.md §6.3). */
private fun keepPalette(app: HeimflytApplication): Pair<String, OmarchyPalette> {
    val active = app.themes.store.active.value
    val rec = active?.let { app.themes.store.loadRecord(it.choice.recordId) }
    val p = rec?.palette ?: app.themes.bundled.load("bundled:krets")?.palette ?: FallbackPalette.krets
    val full = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(ColorsToml.write(p).toByteArray()).values, false)).palette
    return (active?.name ?: "Krets") to full
}

/**
 * The Create screen (CREATE_THEME.md §3.3, §5.3a, §5.5): 1–4 images, each with its own composition (pan + pinch), one
 * palette for the set, four cards, a small Customize, the background mode, a name, Save & apply. Local only.
 */
@Composable
fun CreateThemeScreen(app: HeimflytApplication, source: CreateSource, onDone: (applied: Boolean) -> Unit, onCancel: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val sig = rememberZoneSignature()
    // Originals picked in this session are re-encoded into app-private cache and removed when the screen closes.
    val scratch = remember { File(context.cacheDir, "create") }
    var loaded by remember(source) { mutableStateOf<Loaded?>(null) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        withContext(Dispatchers.IO) {
            try {
                loaded = when (source) {
                    is CreateSource.Picked -> { scratch.deleteRecursively()
                        Loaded(listOf(PhotoPrep.load(context.contentResolver, source.uri, PhotoPrep.masterLimit(context), scratch, AndroidThemeFiles)), null, null, null, null, null) }
                    is CreateSource.Edit -> {
                        val rec = app.themes.store.loadRecord(source.id) ?: throw PhotoException("That theme is no longer installed.")
                        val origin = rec.record.origin as? ThemeOrigin.Created ?: throw PhotoException("Only created themes can be edited.")
                        val pin = app.themes.store.pin(rec.dir)   // the record's masters stay readable while editing
                        Loaded(rec.record.backgrounds.map { PhotoPrep.fromFile(File(rec.dir, it.file)) }, source.id, rec.record.name, origin.recipe, rec.palette, pin)
                    }
                }
            } catch (e: PhotoException) { error = e.message } catch (_: Exception) { error = "Couldn't read this image." }
        }
    }
    DisposableEffect(source) { onDispose { runCatching { loaded?.pin?.close() }; scratch.deleteRecursively() } }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        ScreenHeader(if (source is CreateSource.Edit) "Edit theme" else "New theme", onBack = onCancel)
        val l = loaded
        when {
            error != null -> { Text(error!!, style = t.type.body.copy(color = c.dangerInk), modifier = Modifier.padding(Space.xs)); Spacer(Modifier.weight(1f)) }
            l == null || sig == null -> { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Space.l), color = c.accent, trackColor = c.raised); Spacer(Modifier.weight(1f)) }
            else -> CreateEditor(app, l, sig, scratch, onDone)
        }
    }
}

@Composable
private fun ColumnScope.CreateEditor(app: HeimflytApplication, l: Loaded, sig: ZoneSignature, scratch: File, onDone: (Boolean) -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val aspect = sig.widthPx.toDouble() / sig.heightPx
    val r = l.recipe
    val photos = remember { mutableStateListOf<Photo>().apply { addAll(l.photos) } }
    // One composition per image; null until its automatic framing has been computed (off the main thread).
    val framings = remember { mutableStateListOf<Framing?>().apply { l.photos.indices.forEach { add(r?.framings?.getOrNull(it) ?: (if (it == 0) r?.framing else null)) } } }
    var selected by remember { mutableIntStateOf(0) }
    var variant by remember { mutableStateOf(r?.variant?.let { v -> Variant.entries.firstOrNull { it.id == v } } ?: Variant.CALM) }
    var accent by remember { mutableStateOf<AccentChoice>(r?.accentPinned?.let { AccentChoice.Pinned(it) } ?: AccentChoice.Auto) }
    var mode by remember { mutableStateOf(r?.mode?.let { m -> ModeChoice.entries.firstOrNull { it.name == m } } ?: ModeChoice.AUTO) }
    var strength by remember { mutableStateOf(r?.strength ?: Strength.BALANCED) }
    var bgMode by remember { mutableStateOf(r?.backgroundMode ?: BackgroundMode.FIXED) }
    // Edit shows the saved colors.toml unchanged until the owner changes a setting (CREATE_THEME.md §7.3).
    var dirty by remember { mutableStateOf(l.recipe == null) }
    var adding by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }

    // Automatic framing for any image that has none yet.
    LaunchedEffect(photos.size) {
        photos.indices.forEach { i ->
            if (framings.getOrNull(i) == null) {
                val img = photos[i].analysis
                val f = withContext(Dispatchers.Default) { PaletteGenerator.autoFrame(img, aspect).let { Framing(it.first.toFloat(), it.second.toFloat()) } }
                if (i < framings.size) framings[i] = f
            }
        }
    }
    // One palette for the whole set, each image through its own composition (canonical decision 2026-09-29).
    var analysis by remember { mutableStateOf<Analysis?>(null) }
    val framingKey = framings.toList()
    LaunchedEffect(framingKey, photos.size) {
        if (framingKey.any { it == null } || framingKey.size != photos.size) return@LaunchedEffect
        val parts = photos.mapIndexed { i, p -> p.analysis to framingKey[i]!! }
        analysis = withContext(Dispatchers.Default) {
            PaletteGenerator.analyseSet(parts.map { (img, f) -> img to PaletteGenerator.window(img, aspect, f.fx.toDouble(), f.fy.toDouble(), f.zoom.toDouble()) })
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        adding = true
        scope.launch {
            val photo = withContext(Dispatchers.IO) {
                try { PhotoPrep.load(context.contentResolver, uri, PhotoPrep.masterLimit(context), scratch, AndroidThemeFiles) }
                catch (e: PhotoException) { status = e.message; null } catch (_: Exception) { status = "Couldn't read this image."; null }
            }
            if (photo != null && photos.size < MAX_THEME_IMAGES) {
                photos.add(photo); framings.add(null); selected = photos.lastIndex; dirty = true
                if (photos.size == 2 && bgMode == BackgroundMode.FIXED) bgMode = BackgroundMode.ROTATE
            }
            adding = false
        }
    }
    val an = analysis
    val sel = selected.coerceIn(0, photos.lastIndex)
    val framing = framings.getOrNull(sel)
    if (an == null || framing == null) {
        LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Space.l), color = c.accent, trackColor = c.raised); Spacer(Modifier.weight(1f)); return
    }
    val keep = remember { keepPalette(app) }
    fun paletteFor(v: Variant): OmarchyPalette = if (v == Variant.KEEP) keep.second else PaletteGenerator.generate(an, v, accent, mode)
    val cards = remember(an, accent, mode) { Variant.entries.associateWith { paletteFor(it) } }
    val palette = if (!dirty && l.saved != null) l.saved else cards.getValue(variant)
    val tokens = remember(palette) { TokenMapper.map(palette) }
    val taken = remember { app.themes.store.readCatalog().records.keys.mapNotNull { id -> if (id.startsWith("created:") && id != l.editId) app.themes.store.loadRecord(id)?.record?.name else null } }
    var name by remember { mutableStateOf(l.name ?: ThemeNames.suggest(cards.getValue(Variant.CALM), an.achromatic, taken)) }
    var previewMode by remember { mutableStateOf(PreviewMode.HOME) }
    var drag by remember { mutableStateOf(IntOffset.Zero) }
    var pinch by remember { mutableStateOf(1f) }
    var dragging by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var customize by remember { mutableStateOf(false) }
    val photo = photos[sel]
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(sel, framing, strength, tokens, photos.size) {
        // WYSIWYG: the same crop → strength → shading pipeline as apply, from the image's own composition.
        preview = withContext(Dispatchers.Default) { runCatching { app.themes.processor.previewFrom(photo.preview, framing, strength, tokens, sig, 540) }.getOrNull() }
    }
    val crop = remember(sel, framing, photos.size) { Images.cropRect(photo.masterW, photo.masterH, sig.widthPx, sig.heightPx, framing) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        ThemedPreview(tokens) {
            MiniHome(sig, preview, previewMode, Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally)
                .pointerInput(sel, framing) {
                    // Pan with one finger, pinch with two; the composition (focal point + zoom) commits on release and the
                    // palette, preview and Home all derive from it (CREATE_THEME.md §5.3a).
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        dragging = true
                        do {
                            val event = awaitPointerEvent()
                            val pan = event.calculatePan(); val z = event.calculateZoom()
                            pinch = (pinch * z).coerceIn(1f / framing.zoom, MAX_ZOOM / framing.zoom)
                            drag = IntOffset(drag.x + pan.x.toInt(), drag.y + pan.y.toInt())
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        } while (event.changes.any { it.pressed })
                        val k = crop.width().toFloat() / size.width
                        val cx = crop.exactCenterX() - drag.x * k / pinch
                        val cy = crop.exactCenterY() - drag.y * k / pinch
                        val candidate = Framing(cx / photo.masterW, cy / photo.masterH, (framing.zoom * pinch).coerceIn(1f, MAX_ZOOM))
                        val clamped = Images.cropRect(photo.masterW, photo.masterH, sig.widthPx, sig.heightPx, candidate)
                        if (sel < framings.size) framings[sel] = candidate.copy(fx = clamped.exactCenterX() / photo.masterW, fy = clamped.exactCenterY() / photo.masterH)
                        drag = IntOffset.Zero; pinch = 1f; dragging = false; dirty = true
                    }
                }.semantics { contentDescription = "Preview of image ${sel + 1}. Drag to move, pinch to zoom." },
                imageOffset = drag, guides = dragging, imageScale = pinch)
        }
        Spacer(Modifier.height(Space.s))
        Segmented(PreviewMode.entries.map { it.label to it }, previewMode, { previewMode = it })
        Text("Drag to move and pinch to zoom each image. The colours follow what you show.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
        if (Resolution.low(crop.height(), sig)) Text("Low resolution for this framing — may look soft on this screen.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
        SectionLabel("images")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
            photos.forEachIndexed { i, p ->
                val on = i == sel
                Box(Modifier.size(56.dp, 96.dp).clip(Shapes.xs).border(if (on) 2.dp else 1.dp, if (on) c.accent else c.hairline, Shapes.xs)
                    .clickable(role = Role.RadioButton, onClickLabel = "Edit image ${i + 1}") { selected = i }) {
                    Image(p.preview.asImageBitmap(), "Image ${i + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    Text("${i + 1}", style = t.type.meta.copy(color = c.inkStrong), modifier = Modifier.align(Alignment.TopStart).background(c.raised).padding(horizontal = 4.dp))
                }
            }
            if (photos.size < MAX_THEME_IMAGES) Box(Modifier.size(56.dp, 96.dp).clip(Shapes.xs).border(1.dp, c.hairline, Shapes.xs)
                .clickable(role = Role.Button, onClickLabel = "Add an image") { if (!adding) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                contentAlignment = Alignment.Center) { Text(if (adding) "…" else "＋", style = t.type.title) }
        }
        if (photos.size > 1) Row(verticalAlignment = Alignment.CenterVertically) {
            QuietButton("◀ Move") { if (sel > 0) { photos.add(sel - 1, photos.removeAt(sel)); framings.add(sel - 1, framings.removeAt(sel)); selected = sel - 1; dirty = true } }
            QuietButton("Move ▶") { if (sel < photos.lastIndex) { photos.add(sel + 1, photos.removeAt(sel)); framings.add(sel + 1, framings.removeAt(sel)); selected = sel + 1; dirty = true } }
            Spacer(Modifier.weight(1f))
            QuietButton("Remove", color = c.dangerInk) {
                photos.removeAt(sel); framings.removeAt(sel); selected = (sel - 1).coerceAtLeast(0); dirty = true
                if (photos.size == 1) bgMode = BackgroundMode.FIXED
            }
        }
        if (photos.size > 1) {
            SectionLabel("show images")
            Segmented(BackgroundMode.entries.map { it.label to it }, bgMode, { bgMode = it })
            Text(when (bgMode) {
                BackgroundMode.FIXED -> "Always the first image."
                BackgroundMode.ROTATE -> "The next image each time the screen turns off on Home."
                BackgroundMode.RANDOM -> "Another image each time the screen turns off on Home."
            }, style = t.type.caption, modifier = Modifier.padding(Space.xs))
        }
        SectionLabel("colours")
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            for (v in Variant.entries) {
                val p = cards.getValue(v); val tk = remember(p) { TokenMapper.map(p) }
                val on = v == variant
                Column(Modifier.weight(1f).clip(Shapes.s).border(if (on) 2.dp else 1.dp, if (on) c.accent else c.hairline, Shapes.s)
                    .clickable(role = Role.RadioButton, onClickLabel = v.label) { variant = v; dirty = true }.padding(Space.xs)) {
                    Row(Modifier.fillMaxWidth().height(28.dp).clip(Shapes.xs)) {
                        listOf(tk.ground, tk.raised, tk.ink, tk.accent).forEach { Box(Modifier.weight(1f).fillMaxHeight().background(rgb(it))) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (on) { Glyph(R.drawable.glyph_check, c.accentInk, 14.dp); Spacer(Modifier.width(2.dp)) }
                        Text(v.label, style = t.type.label.copy(color = if (on) c.accentInk else c.ink), maxLines = 1)
                    }
                    Glyph(if (p.light) R.drawable.glyph_sun else R.drawable.glyph_moon, c.inkMuted, 12.dp)
                }
            }
        }
        if (variant == Variant.KEEP) Text("Keep ${keep.first} colours with these images.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
        HRow("Customize", subtitle = if (customize) null else "accent · light/dark · strength", compact = true, onClick = { customize = !customize },
            trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
        if (customize) {
            SectionLabel("accent from the images")
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s), verticalAlignment = Alignment.CenterVertically) {
                val cands = remember(an) { PaletteGenerator.candidates(an) }
                Box(Modifier.size(36.dp).clip(CircleShape).border(if (accent == AccentChoice.Auto) 2.dp else 1.dp, if (accent == AccentChoice.Auto) c.accent else c.hairline, CircleShape)
                    .clickable(role = Role.RadioButton, onClickLabel = "Automatic accent") { accent = AccentChoice.Auto; dirty = true }, contentAlignment = Alignment.Center) {
                    Text("A", style = t.type.label)
                }
                for ((h, ch) in cands) {
                    val swatch = Oklab.lch(if (palette.light) 0.52 else 0.76, ch.coerceIn(0.06, 0.16), h)
                    val on = (accent as? AccentChoice.Pinned)?.rgb == swatch
                    Box(Modifier.size(36.dp).clip(CircleShape).background(rgb(swatch)).border(if (on) 3.dp else 1.dp, if (on) c.inkStrong else c.hairline, CircleShape)
                        .clickable(role = Role.RadioButton, onClickLabel = "Accent #%06x".format(swatch)) { accent = AccentChoice.Pinned(swatch); dirty = true })
                }
            }
            SectionLabel("light or dark")
            Segmented(listOf("Auto" to ModeChoice.AUTO, "Dark" to ModeChoice.DARK, "Light" to ModeChoice.LIGHT), mode, { mode = it; dirty = true })
            SectionLabel("wallpaper strength")
            Segmented(Strength.entries.map { it.label to it }, strength, { strength = it })
        }
        SectionLabel("name")
        HField(name, { name = it.take(40) }, "Name", showGlyph = false, modifier = Modifier.semantics { contentDescription = "Theme name" })
        status?.let { StatusPill(it, { status = null }, Modifier.padding(vertical = Space.s)) }
        Spacer(Modifier.height(Space.l))
    }
    Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
        if (busy) { Text("Saving…", style = t.type.meta); Spacer(Modifier.weight(1f)) } else {
            fun go(apply: Boolean) {
                val clean = ThemeNames.clean(name) ?: run { status = "Use 1–40 letters, digits, spaces or - ' ."; return }
                val fs = framings.map { it ?: Framing() }
                busy = true
                scope.launch {
                    val recipe = CreateRecipe(PaletteGenerator.VERSION, variant.id, if (variant == Variant.KEEP) keep.first else null, (accent as? AccentChoice.Pinned)?.rgb,
                        mode.name, fs.first(), strength, an.clusters.take(10).map { it.rgb to it.share }, PaletteGenerator.candidates(an).map { Oklab.lch(0.70, it.second, it.first) },
                        fs, if (photos.size > 1) bgMode else BackgroundMode.FIXED)
                    val saved = withContext(Dispatchers.IO) { CreatedThemes(app.themes.store, AndroidThemeFiles).save(l.editId, clean, palette, recipe, photos.toList(), sig.widthPx, sig.heightPx) }
                    if (saved !is Outcome.Installed) { busy = false; status = (saved as? Outcome.Failed)?.message ?: (saved as? Outcome.Uncertain)?.message ?: "Couldn't save the theme."; return@launch }
                    val active = app.themes.store.active.value?.choice?.recordId == saved.id
                    if (apply || active) {
                        val extras = fs.drop(1).mapIndexed { i, f -> ImageSlot(i + 1, f) }
                        when (val a = app.themes.apply(saved.id, BackgroundChoice(BackgroundKind.IMAGE, 0), fs.first(), strength, sig, extras, recipe.backgroundMode)) {
                            is Outcome.Failed -> { busy = false; status = "Saved, but couldn't be applied. Try again."; return@launch }
                            is Outcome.Uncertain -> { busy = false; status = a.message; return@launch }
                            else -> Unit
                        }
                    }
                    busy = false; onDone(apply)
                }
            }
            QuietButton("Save") { go(false) }
            Spacer(Modifier.weight(1f))
            PrimaryButton("Save & apply") { go(true) }
        }
    }
}
