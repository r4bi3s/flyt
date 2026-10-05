package no.heimflyt.launcher.ui.themes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.HeimflytApplication
import no.heimflyt.launcher.R
import no.heimflyt.launcher.theme.BundledThemes
import no.heimflyt.launcher.theme.image.HomeLayout
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.Strength
import no.heimflyt.launcher.theme.store.ThemeChoice
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.InstallReport
import no.heimflyt.launcher.theme.fetch.FetchCancelled
import no.heimflyt.launcher.theme.fetch.FetchException
import no.heimflyt.launcher.theme.fetch.GitHubImport
import no.heimflyt.launcher.theme.fetch.GitHubReport
import no.heimflyt.launcher.theme.fetch.GitHubSource
import no.heimflyt.launcher.theme.fetch.GitHubUrl
import no.heimflyt.launcher.theme.fetch.PlatformHttps
import no.heimflyt.launcher.theme.fetch.ThemeNetworkSession
import no.heimflyt.launcher.theme.store.AndroidThemeFiles
import no.heimflyt.launcher.theme.zip.IgnoredKind
import no.heimflyt.launcher.theme.zip.ZipCancelled
import no.heimflyt.launcher.theme.zip.ZipImport
import no.heimflyt.launcher.theme.zip.ZipRejected
import no.heimflyt.launcher.theme.zip.ZipReport
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space
import no.heimflyt.launcher.ui.theme.ThemedPreview
import no.heimflyt.launcher.ui.theme.rgb
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.sin

/**
 * The window's zone signature (VISUAL_SYSTEM.md §8.2): the same insets Home pads its content with, minus the IME.
 * Null until the window size and insets are known, so a first frame never looks like a configuration change.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun rememberZoneSignature(): ZoneSignature? {
    val density = LocalDensity.current
    val size = LocalWindowInfo.current.containerSize
    val dir = LocalLayoutDirection.current
    // Ignoring visibility: hiding Android's status bar on Home (status spike) must not look like a configuration change.
    val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.systemGestures)
    // The clock/date zones are measured text, so the locale and the week-number setting are part of the signature.
    val app = LocalContext.current.applicationContext as HeimflytApplication
    val settings by app.settings.settings.collectAsState()
    val locale = java.util.Locale.getDefault()
    val week = settings.home.weekNumber ?: no.heimflyt.launcher.ui.home.weekNumberDefault(locale)
    val sig = ZoneSignature(density.fontScale, density.density, size.width, size.height, safe.getLeft(density, dir), safe.getTop(density),
        safe.getRight(density, dir), safe.getBottom(density), WindowInsets.statusBarsIgnoringVisibility.getTop(density),
        WindowInsets.navigationBarsIgnoringVisibility.getBottom(density), "${locale.toLanguageTag()}|${if (week) "week" else "noweek"}")
    return sig.takeIf { size.width > 0 && size.height > 0 && (sig.statusPx > 0 || sig.safeTop > 0) }
}

/** A theme as the gallery shows it: resolved tokens and its default background (installed records may be damaged). */
class ThemeEntry(val id: String, val name: String, val light: Boolean, val tokens: ResolvedColors?, val record: LoadedRecord?,
                 val installed: Boolean, val defaultBackground: BackgroundChoice, val source: String) {
    val created get() = record?.record?.origin is ThemeOrigin.Created
    /** A created theme's own framing (its recipe), used until the owner reframes it in detail. */
    val createdFraming get() = (record?.record?.origin as? ThemeOrigin.Created)?.recipe?.framing
}

/** Session cache for preview bitmaps (small, software). Cleared when the owner leaves Themes, so Home keeps nothing extra. */
object ThemePreviews {
    private val map = LinkedHashMap<String, Bitmap>()
    @Synchronized fun get(key: String) = map[key]
    @Synchronized fun put(key: String, b: Bitmap) { map[key] = b; while (map.size > 24) map.remove(map.keys.first()) }
    @Synchronized fun clear() = map.clear()
}

private fun loadEntries(app: HeimflytApplication): List<ThemeEntry> {
    val store = app.themes.store
    store.gc()
    val catalog = store.refreshCatalog()
    val installed = catalog?.records?.keys?.sorted()?.map { id ->
        val r = store.loadRecord(id)
        if (r == null) ThemeEntry(id, "Damaged theme", false, null, null, true, BackgroundChoice(BackgroundKind.DUSK), "")
        else ThemeEntry(id, r.record.name, r.record.light, TokenMapper.map(r.palette), r, true,
            if (r.record.backgrounds.isNotEmpty()) BackgroundChoice(BackgroundKind.IMAGE, 0) else BackgroundChoice(BackgroundKind.DUSK),
            when (val o = r.record.origin) { is ThemeOrigin.FileImport -> o.displayName; is ThemeOrigin.GitHub -> o.display; else -> "" })
    }.orEmpty()
    val bundled = BundledThemes.all.mapNotNull { b ->
        val r = app.themes.bundled.load(b.id) ?: return@mapNotNull null
        ThemeEntry(b.id, b.name, r.record.light, TokenMapper.map(r.palette), r, false,
            if (b.slug == "krets") BackgroundChoice(BackgroundKind.IMAGE, 0) else BackgroundChoice(b.ground), "included")
    }
    return installed + bundled
}

@Composable
private fun rememberEntries(app: HeimflytApplication, refresh: Int): List<ThemeEntry>? {
    var entries by remember { mutableStateOf<List<ThemeEntry>?>(null) }
    val catalog by app.themes.store.catalog.collectAsState()
    LaunchedEffect(refresh, catalog?.seq) { entries = withContext(Dispatchers.IO) { loadEntries(app) } }
    return entries
}

/** Bands-applied preview of [choice] for [entry] at [width] px (null for Plain or while rendering). */
@Composable
private fun rememberPreview(app: HeimflytApplication, entry: ThemeEntry, choice: BackgroundChoice, framing: Framing, strength: Strength,
                            sig: ZoneSignature?, width: Int, thumbOnly: Boolean): Bitmap? {
    val key = "${entry.id}|${choice.encode()}|${framing}|${strength}|$width|${sig?.encode()}|$thumbOnly"
    var bmp by remember(key) { mutableStateOf(ThemePreviews.get(key)) }
    LaunchedEffect(key) {
        if (bmp != null || !choice.hasImage || sig == null) return@LaunchedEffect
        val record = entry.record ?: return@LaunchedEffect
        val tokens = entry.tokens ?: return@LaunchedEffect
        bmp = withContext(Dispatchers.Default) {
            runCatching {
                if (thumbOnly && choice.kind == BackgroundKind.IMAGE) {
                    // Gallery cards use the install-time thumbnail; they never decode full backgrounds.
                    val stored = record.record.backgrounds.getOrNull(choice.index)
                    if (entry.id == "bundled:krets" && stored != null)
                        app.assets.open(stored.file).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 4 }) }
                    else record.dir?.let { d -> stored?.thumb?.let { BitmapFactory.decodeFile(File(d, it).path) } }
                } else app.themes.processor.preview(record, ThemeChoice(entry.id, record.rev, choice, framing, strength), tokens, sig, width)
            }.getOrNull()
        }?.also { ThemePreviews.put(key, it) }
    }
    return bmp
}

// ---- miniature Home (SURFACES.md §9.1): Home's own layout constants at a smaller density, static, no touch ----

@Composable
fun MiniHome(sig: ZoneSignature, background: Bitmap?, mode: PreviewMode, modifier: Modifier = Modifier,
             imageOffset: androidx.compose.ui.unit.IntOffset = androidx.compose.ui.unit.IntOffset.Zero, guides: Boolean = false, imageScale: Float = 1f) {
    val t = Heimflyt.t; val c = t.color
    BoxWithConstraints(modifier.aspectRatio(sig.widthPx.toFloat() / sig.heightPx).clip(Shapes.m).background(c.ground).border(1.dp, c.hairline, Shapes.m)) {
        val scale = constraints.maxWidth.toFloat() / sig.widthPx
        if (mode == PreviewMode.SEARCH) { MiniSearch(Density(sig.density * scale, sig.fontScale), sig); return@BoxWithConstraints }
        // Live pan/zoom is a paint-only transform of the prepared preview; the committed composition re-renders it exactly.
        background?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize().graphicsLayer {
            translationX = imageOffset.x.toFloat(); translationY = imageOffset.y.toFloat(); scaleX = imageScale; scaleY = imageScale
        }, contentScale = ContentScale.Crop) }
        if (guides) {
            // Zone guides, only while reframing (CREATE_THEME.md §3.3): where the clock and the thumb zone sit.
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().weight(0.34f).background(c.scrim.copy(alpha = 0.35f)), contentAlignment = Alignment.BottomStart) {
                    Text("clock", style = t.type.meta.copy(color = c.inkStrong), modifier = Modifier.padding(4.dp))
                }
                Spacer(Modifier.weight(0.52f))
                Box(Modifier.fillMaxWidth().weight(0.14f).background(c.scrim.copy(alpha = 0.35f)), contentAlignment = Alignment.TopStart) {
                    Text("thumb zone", style = t.type.meta.copy(color = c.inkStrong), modifier = Modifier.padding(4.dp))
                }
            }
        }
        CompositionLocalProvider(LocalDensity provides Density(sig.density * scale, sig.fontScale)) {
            val d = sig.density
            Box(Modifier.fillMaxSize().padding(start = (sig.safeLeft / d).dp, top = (sig.safeTop / d).dp, end = (sig.safeRight / d).dp, bottom = (sig.safeBottom / d).dp)) {
                Column(Modifier.padding(start = HomeLayout.GUTTER.dp, top = HomeLayout.CLOCK_TOP.dp)) {
                    Text("23:14", style = t.type.display)
                    Text("Sunday 28 September", style = t.type.dateline, maxLines = 1)
                }
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val mx = maxWidth - 72.dp; val my = maxHeight - 150.dp
                    val menu = 110.dp; val dead = 26.dp
                    Canvas(Modifier.fillMaxSize()) {
                        val o = Offset(mx.toPx(), my.toPx())
                        if (mode == PreviewMode.RADIAL) {
                            val r = menu.toPx(); val ri = dead.toPx() + 6.dp.toPx()
                            drawCircle(Brush.radialGradient(listOf(c.scrim.copy(alpha = if (c.isLight) 0.72f else 0.62f), c.scrim.copy(alpha = 0f)), o, r * 1.9f), r * 1.9f, o)
                            for (i in 0 until 6) {
                                val start = 180f + i * 30f
                                drawArc(if (i == 2) c.accent.copy(alpha = 0.38f) else c.ink.copy(alpha = 0.10f), start, 30f, true,
                                    Offset(o.x - r, o.y - r), Size(2 * r, 2 * r))
                                val a = Math.toRadians(start.toDouble())
                                drawLine(c.ink.copy(alpha = 0.22f), Offset(o.x + cos(a).toFloat() * ri, o.y + sin(a).toFloat() * ri),
                                    Offset(o.x + cos(a).toFloat() * r, o.y + sin(a).toFloat() * r), 1.dp.toPx())
                            }
                            drawCircle(c.ground, ri, o)
                            drawArc(c.accent, 240f, 30f, false, Offset(o.x - r + 2.dp.toPx(), o.y - r + 2.dp.toPx()), Size(2 * r - 4.dp.toPx(), 2 * r - 4.dp.toPx()), style = Stroke(4.dp.toPx()))
                            drawCircle(c.ink.copy(alpha = 0.55f), dead.toPx(), o, style = Stroke(1.5.dp.toPx()))
                        }
                        drawCircle(c.scrim.copy(alpha = 0.45f), 9.dp.toPx(), o)
                        drawCircle(c.accent, 5.dp.toPx(), o)
                    }
                    if (mode == PreviewMode.RADIAL) {
                        val a = Math.toRadians(255.0)
                        Row(Modifier.offset(mx + (menu + 30.dp) * cos(a).toFloat() - 50.dp, my + (menu + 30.dp) * sin(a).toFloat() - 15.dp)
                            .clip(Shapes.s).background(c.accent).padding(horizontal = 11.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                            Glyph(R.drawable.glyph_message, c.onAccent, 18.dp); Spacer(Modifier.width(6.dp))
                            Text("Messages", style = t.type.label.copy(color = c.onAccent))
                        }
                    }
                }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = HomeLayout.CORNER_PAD.dp).padding(bottom = HomeLayout.CORNER_PAD.dp),
                    horizontalArrangement = Arrangement.SpaceBetween) {
                    for (w in listOf("Apps", "Search")) Box(Modifier.widthIn(min = HomeLayout.CORNER_MIN_W.dp).heightIn(min = HomeLayout.CORNER_MIN_H.dp), contentAlignment = Alignment.Center) {
                        Text(w, style = t.type.label.copy(color = c.inkStrong))
                    }
                }
            }
        }
    }
}

@Composable
private fun MiniSearch(density: Density, sig: ZoneSignature) {
    val t = Heimflyt.t; val c = t.color
    CompositionLocalProvider(LocalDensity provides density) {
        val d = sig.density
        Column(Modifier.fillMaxSize().padding(top = (sig.safeTop / d).dp, bottom = (sig.safeBottom / d).dp).padding(horizontal = Space.gutter - Space.xs),
            verticalArrangement = Arrangement.Bottom) {
            SectionLabel("actions"); HRow("Messages", subtitle = "Alex Example", compact = true, leading = { Glyph(R.drawable.glyph_message, c.inkMuted) })
            SectionLabel("people"); HRow("Alex Example", subtitle = "2 records", compact = true, leading = { Monogram("Alex Example", 36.dp) })
            SectionLabel("apps"); HRow("Atlas", compact = true, leading = { Glyph(R.drawable.glyph_apps, c.accent) })
            HRow("Alpha Notes", compact = true, strong = true, leading = { Glyph(R.drawable.glyph_apps, c.accent) })
            Row(Modifier.padding(vertical = Space.s)) { TagWord("work") }
            HField("a", {}, "Search", showGlyph = true)
        }
    }
}

enum class PreviewMode(val label: String) { HOME("Home"), RADIAL("Radial"), SEARCH("Search") }

// ---- gallery (SURFACES.md §9.1) ----

@Composable
fun ThemesGallery(app: HeimflytApplication, onOpen: (String) -> Unit, onCreate: (Uri) -> Unit,
                  pendingImport: Uri? = null, onImportTaken: () -> Unit = {}, onBack: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val sig = rememberZoneSignature()
    val active by app.themes.store.active.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val entries = rememberEntries(app, refresh)
    var install by remember { mutableStateOf<InstallState?>(null) }
    val scope = rememberCoroutineScope()
    // Local (.zip) jobs stop through a flag; network work through its ThemeNetworkSession (R7). Both are owned by this route.
    val cancelFlag = remember { mutableStateOf(AtomicBoolean(false)) }
    val session = remember { mutableStateOf<ThemeNetworkSession?>(null) }
    fun stopAll() { cancelFlag.value.set(true); session.value?.cancel(); session.value = null }
    val zip = remember { ZipImport(app.themes.store, context.contentResolver) }
    val github = remember { GitHubImport(app.themes.store, AndroidThemeFiles, PlatformHttps(), "Flyt/${versionOf(context)}") }
    // Leaving Themes, HOME (which leaves the route) and onStop stop any work; nothing restarts on resume.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP) {
                stopAll()
                if (install is InstallState.Inspecting || install is InstallState.Installing) install = InstallState.Failed("Stopped. Try again.")
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); stopAll() }
    }
    fun inspectZip(uri: Uri) {
        val name = runCatching { context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null } }.getOrNull() ?: "theme.zip"
        stopAll()
        val flag = AtomicBoolean(false); cancelFlag.value = flag
        install = InstallState.Inspecting(name)
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                try { InstallState.Report(zip.inspect(uri, name) { flag.get() }) }
                catch (e: ZipRejected) { InstallState.Failed(e.message ?: "This archive is damaged.") }
                catch (_: ZipCancelled) { null }
                catch (_: Exception) { InstallState.Failed("Couldn't read this file.") }
            }
            if (!flag.get()) install = next
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? -> if (uri != null) inspectZip(uri) }
    // A .zip shared to or opened with Heimflyt goes to the same Report; nothing installs without the owner's tap.
    LaunchedEffect(pendingImport) { pendingImport?.let { onImportTaken(); inspectZip(it) } }
    fun inspectUrl(text: String) {
        stopAll()
        val repo = try { GitHubUrl.normalise(text) } catch (e: FetchException) { install = InstallState.Enter(text, e.message); return }
        val s = ThemeNetworkSession(); session.value = s
        install = InstallState.Inspecting("${repo.owner}/${repo.repo}")
        scope.launch {
            val next = withContext(Dispatchers.IO) {
                try { InstallState.Report(github.inspect(text, s)) }
                catch (e: FetchException) { InstallState.Enter(text, e.message) }
                catch (_: FetchCancelled) { null }
                catch (_: Exception) { InstallState.Enter(text, GitHubSource.OFFLINE) }
            }
            if (!s.cancelled) install = next
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        ScreenHeader("Themes", onBack = onBack, meta = active?.name?.let { "active: $it" } ?: "active: Krets")
        if (entries == null) { LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = Space.l), color = c.accent, trackColor = c.raised); Spacer(Modifier.weight(1f)) }
        else LazyVerticalGrid(GridCells.Fixed(2), Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(Space.m), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            val mine = entries.filter { it.created }.sortedByDescending { it.record?.record?.installedAt ?: 0 }
            if (mine.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) { SectionLabel("mine") }
                items(mine, key = { it.id }) { ThemeCard(app, it, active?.choice?.recordId, active?.choice, sig) { onOpen(it.id) } }
            }
            val installed = entries.filter { it.installed && !it.created }
            if (installed.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) { SectionLabel("installed") }
                items(installed, key = { it.id }) { ThemeCard(app, it, active?.choice?.recordId, active?.choice, sig) { onOpen(it.id) } }
            }
            item(span = { GridItemSpan(2) }) { SectionLabel("included") }
            items(entries.filterNot { it.installed }, key = { it.id }) { ThemeCard(app, it, active?.choice?.recordId ?: "bundled:krets", active?.choice, sig) { onOpen(it.id) } }
            item(span = { GridItemSpan(2) }) { Spacer(Modifier.height(Space.l)) }
        }
        // Create is primary: Android's Photo Picker, no media permission; nothing leaves the phone.
        val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) onCreate(uri) }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            Row(Modifier.weight(1f).heightIn(min = Space.target).clip(Shapes.s).background(c.accent)
                .clickable(role = Role.Button) { photoPicker.launch(androidx.activity.result.PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                .padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Text("＋  Create", style = t.type.label.copy(color = c.onAccent))
            }
            Row(Modifier.weight(1f).heightIn(min = Space.target).clip(Shapes.s).background(c.raised)
                .clickable(role = Role.Button) { install = InstallState.Enter("", null) }
                .padding(horizontal = Space.l), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Glyph(R.drawable.glyph_download, c.accentInk, 20.dp); Spacer(Modifier.width(Space.s))
                Text("Install", style = t.type.label.copy(color = c.inkStrong))
            }
        }
    }
    install?.let { state ->
        InstallSheet(state, sig,
            onCancel = { stopAll(); install = null },
            onInspect = ::inspectUrl,
            onZip = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream")) },
            onInstall = { report, apply ->
                stopAll()
                val flag = AtomicBoolean(false); cancelFlag.value = flag
                val s = if (report is GitHubReport) ThemeNetworkSession().also { session.value = it } else null
                install = InstallState.Installing(report, 0)
                scope.launch {
                    val w = sig?.widthPx ?: 1080; val h = sig?.heightPx ?: 2400
                    val outcome = withContext(Dispatchers.IO) {
                        val progress: (Long) -> Unit = { done -> install = InstallState.Installing(report, done) }
                        when (report) {
                            is GitHubReport -> github.install(report, s!!, w, h, progress)
                            is ZipReport -> zip.install(report, w, h, { flag.get() }, progress)
                            else -> Outcome.Failed("Unknown source.")
                        }
                    }
                    if (flag.get() || s?.cancelled == true) { refresh++; return@launch }
                    install = when (outcome) {
                        is Outcome.Installed -> {
                            if (apply) {
                                val r = app.themes.store.loadRecord(outcome.id)
                                val bg = if (r?.record?.backgrounds?.isNotEmpty() == true) BackgroundChoice(BackgroundKind.IMAGE, 0) else BackgroundChoice(BackgroundKind.DUSK)
                                when (val a = app.themes.apply(outcome.id, bg, Framing(), Strength.BALANCED, sig)) {
                                    is Outcome.Applied -> InstallState.Done(report.name, true, null)
                                    is Outcome.Failed -> InstallState.Done(report.name, false, "Saved, but couldn't be applied. Try again.")
                                    is Outcome.Uncertain -> InstallState.Done(report.name, false, a.message)
                                    else -> InstallState.Done(report.name, false, null)
                                }
                            } else InstallState.Done(report.name, false, null)
                        }
                        is Outcome.Failed -> InstallState.Failed(outcome.message)
                        is Outcome.Uncertain -> InstallState.Failed(outcome.message)
                        else -> null
                    }
                    session.value = null
                    refresh++
                }
            },
            onClose = { install = null })
    }
}

private fun versionOf(context: android.content.Context) = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "0"

@Composable
private fun ThemeCard(app: HeimflytApplication, entry: ThemeEntry, activeId: String?, activeChoice: ThemeChoice?, sig: ZoneSignature?, onClick: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val isActive = entry.id == activeId
    Column(Modifier.clip(Shapes.m).clickable(role = Role.Button, onClickLabel = "Open ${entry.name}", onClick = onClick).padding(Space.xs)) {
        val tokens = entry.tokens
        if (tokens != null && sig != null) {
            val bg = if (isActive && activeChoice != null) activeChoice.background else entry.defaultBackground
            val framing = if (isActive && activeChoice != null) activeChoice.framing else Framing()
            val strength = if (isActive && activeChoice != null) activeChoice.strength else Strength.BALANCED
            ThemedPreview(tokens) {
                val bmp = rememberPreview(app, entry, bg, framing, strength, sig, 360, thumbOnly = true)
                MiniHome(sig, bmp, PreviewMode.HOME, Modifier.fillMaxWidth()
                    .then(if (isActive) Modifier.border(2.dp, c.accent, Shapes.m) else Modifier).semantics { contentDescription = "${entry.name} preview" })
            }
        } else Box(Modifier.fillMaxWidth().aspectRatio(0.45f).clip(Shapes.m).background(c.raised), contentAlignment = Alignment.Center) {
            Text("Damaged", style = t.type.meta)
        }
        Spacer(Modifier.height(Space.xs))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(entry.name, style = t.type.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (isActive) { Spacer(Modifier.width(Space.xs)); Glyph(R.drawable.glyph_check, c.accent, 16.dp); Text("Active", style = t.type.meta.copy(color = c.accentInk)) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(if (entry.light) R.drawable.glyph_sun else R.drawable.glyph_moon, c.inkMuted, 14.dp)
            Text(" ${if (entry.light) "light" else "dark"} ", style = t.type.meta)
            entry.tokens?.let { r -> Swatches(listOf(r.ground, r.inkStrong, r.accent, r.identity[0], r.identity[3])) }
        }
    }
}

@Composable
private fun Swatches(colors: List<Int>) {
    val c = Heimflyt.t.color
    Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        colors.forEach { Box(Modifier.size(10.dp).clip(CircleShape).background(rgb(it)).border(1.dp, c.hairline, CircleShape)) }
    }
}

// ---- detail (SURFACES.md §9.2) ----

@Composable
fun ThemeDetail(app: HeimflytApplication, id: String, onEdit: (String) -> Unit, onBack: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val sig = rememberZoneSignature()
    val active by app.themes.store.active.collectAsState()
    val entries = rememberEntries(app, 0)
    val entry = entries?.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        ScreenHeader(entry?.name ?: "Theme", onBack = onBack, meta = entry?.let { if (it.installed) it.source else "included" })
        if (entry == null) { Spacer(Modifier.weight(1f)); return@Column }
        val tokens = entry.tokens
        val record = entry.record
        if (tokens == null || record == null) {
            Text("This theme's files couldn't be read.", style = t.type.body, modifier = Modifier.padding(Space.xs))
            Spacer(Modifier.weight(1f))
            RemoveAction(app, entry, sig, onBack); return@Column
        }
        val isActive = active?.choice?.recordId == id || (active == null && id == "bundled:krets")
        val start = active?.choice?.takeIf { it.recordId == id }
        var background by remember(id, start) { mutableStateOf(start?.background ?: entry.defaultBackground) }
        var framing by remember(id, start) { mutableStateOf(start?.framing ?: entry.createdFraming ?: Framing()) }
        var strength by remember(id, start) { mutableStateOf(start?.strength ?: Strength.BALANCED) }
        val recipe = (record.record.origin as? ThemeOrigin.Created)?.recipe
        var bgMode by remember(id, start) { mutableStateOf(start?.mode ?: recipe?.backgroundMode ?:
            if (id == "bundled:krets") no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE else no.heimflyt.launcher.theme.store.BackgroundMode.FIXED) }
        val imageCount = record.record.backgrounds.size
        // Rotate/Random use the chosen image first, then the following ones (up to four), each with its own composition.
        fun extrasFor(bg: BackgroundChoice): List<no.heimflyt.launcher.theme.store.ImageSlot> =
            if (bg.kind != BackgroundKind.IMAGE || bgMode == no.heimflyt.launcher.theme.store.BackgroundMode.FIXED) emptyList()
            else (1 until minOf(imageCount, no.heimflyt.launcher.theme.store.MAX_THEME_IMAGES)).map { k -> (bg.index + k) % imageCount }
                .map { i -> no.heimflyt.launcher.theme.store.ImageSlot(i, recipe?.framings?.getOrNull(i) ?: Framing()) }
        var mode by remember { mutableStateOf(PreviewMode.HOME) }
        var status by remember { mutableStateOf<String?>(null) }
        val busy by app.themes.busy.collectAsState()
        val changed = !isActive || start == null || start.background != background || start.framing != framing || start.strength != strength ||
            start.mode != bgMode || start.extras != extrasFor(background)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            if (sig != null) {
                ThemedPreview(tokens) {
                    val bmp = rememberPreview(app, entry, background, framing, strength, sig, 540, thumbOnly = false)
                    val image = background.kind == BackgroundKind.IMAGE
                    val stored = record.record.backgrounds.getOrNull(background.index)
                    MiniHome(sig, bmp, mode, Modifier.fillMaxWidth(0.62f).align(Alignment.CenterHorizontally)
                        .then(if (image && stored != null && mode != PreviewMode.SEARCH) Modifier.pointerInput(background, stored) {
                            // Reframe: drag moves the crop window over the stored image; released framing is used on Apply.
                            var fx = framing.fx; var fy = framing.fy
                            detectDragGestures(onDragEnd = { framing = framing.copy(fx = fx, fy = fy) }) { _, drag ->
                                fx = (fx - drag.x / size.width * 0.5f).coerceIn(0f, 1f); fy = (fy - drag.y / size.height * 0.5f).coerceIn(0f, 1f)
                            }
                        } else Modifier)
                        .semantics { contentDescription = "${entry.name} ${mode.label} preview" })
                }
            }
            Spacer(Modifier.height(Space.s))
            Segmented(PreviewMode.entries.map { it.label to it }, mode, { mode = it })
            if (background.kind == BackgroundKind.IMAGE) Text("Drag the preview to reframe the photo.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
            SectionLabel("background")
            val options = record.record.backgrounds.indices.map { BackgroundChoice(BackgroundKind.IMAGE, it) } +
                listOf(BackgroundKind.KRETS, BackgroundKind.DUSK, BackgroundKind.CONTOUR, BackgroundKind.PLAIN).map { BackgroundChoice(it) }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                items(options, key = { it.encode() }) { option ->
                    val selected = option == background
                    Column(Modifier.width(72.dp).clip(Shapes.s).clickable(role = Role.RadioButton) { background = option; framing = Framing() }.padding(Space.xxs),
                        horizontalAlignment = Alignment.CenterHorizontally) {
                        val thumb = if (sig != null) rememberPreview(app, entry, option, Framing(), strength, sig, 144, thumbOnly = true) else null
                        Box(Modifier.size(64.dp, 110.dp).clip(Shapes.xs).background(rgb(tokens.ground))
                            .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.hairline, Shapes.xs)) {
                            thumb?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                        }
                        Text(if (option.kind == BackgroundKind.IMAGE) record.record.backgrounds[option.index].label else option.kind.label,
                            style = t.type.meta.copy(color = if (selected) c.accentInk else c.inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (background.kind == BackgroundKind.IMAGE && imageCount > 1) {
                SectionLabel("show images")
                Segmented(no.heimflyt.launcher.theme.store.BackgroundMode.entries.map { it.label to it }, bgMode, { bgMode = it })
                Text(when (bgMode) {
                    no.heimflyt.launcher.theme.store.BackgroundMode.FIXED -> "Always this image."
                    no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE -> "This image, then the next ${minOf(imageCount, 4) - 1}: the next one each time the screen turns off on Home."
                    no.heimflyt.launcher.theme.store.BackgroundMode.RANDOM -> "This image and ${minOf(imageCount, 4) - 1} more, in random order, changing when the screen turns off on Home."
                }, style = t.type.caption, modifier = Modifier.padding(Space.xs))
            }
            if (background.kind == BackgroundKind.IMAGE) {
                SectionLabel("strength")
                Segmented(Strength.entries.map { it.label to it }, strength, { strength = it })
                // Computed from the actual source and the current framing, not a stored origin-based flag.
                record.record.backgrounds.getOrNull(background.index)?.takeIf { b ->
                    sig != null && no.heimflyt.launcher.theme.image.Resolution.low(no.heimflyt.launcher.theme.image.Images.cropRect(b.width, b.height, sig.widthPx, sig.heightPx, framing).height(), sig)
                }?.let {
                    Text("Low resolution for this framing — may look soft on this screen.", style = t.type.caption, modifier = Modifier.padding(Space.xs))
                }
            }
            Colophon(entry, record, tokens)
            if (entry.created || background.kind == BackgroundKind.IMAGE) SectionLabel("more")
            if (entry.created) {
                HRow("Edit", subtitle = "colours, framing and name", compact = true, onClick = { onEdit(id) },
                    trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
                if (background.kind == BackgroundKind.IMAGE) DesktopVariantAction(app, record, background.index) { status = it }
                ExportAction(app, entry, record) { status = it }
            }
            if (background.kind == BackgroundKind.IMAGE) AndroidWallpaperAction(app, record, background, framing) { status = it }
            status?.let { StatusPill(it, { status = null }, Modifier.padding(vertical = Space.s)) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (entry.installed) RemoveAction(app, entry, sig, onBack)
            (record.record.origin as? ThemeOrigin.GitHub)?.let { o ->
                val ctx = LocalContext.current
                QuietButton("Source") { runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://github.com/${o.owner}/${o.repo}"))) } }
            }
            Spacer(Modifier.weight(1f))
            if (busy) Text("Applying…", style = t.type.meta, modifier = Modifier.padding(end = Space.m))
            if (changed) PrimaryButton("Apply") {
                scope.launch {
                    status = when (val o = app.themes.apply(id, background, framing, strength, sig, extrasFor(background), bgMode)) {
                        is Outcome.Applied, Outcome.Superseded -> null
                        is Outcome.Failed -> o.message
                        is Outcome.Uncertain -> o.message
                        else -> null
                    }
                }
            } else Row(verticalAlignment = Alignment.CenterVertically) {
                Glyph(R.drawable.glyph_check, c.accent, 18.dp); Spacer(Modifier.width(Space.xs)); Text("Active", style = t.type.label.copy(color = c.accentInk))
            }
        }
    }
}

@Composable
private fun RemoveAction(app: HeimflytApplication, entry: ThemeEntry, sig: ZoneSignature?, onRemoved: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    var confirm by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val activeGen by app.themes.store.active.collectAsState()
    val scope = rememberCoroutineScope()
    val verb = if (entry.created) "Delete" else "Remove"
    QuietButton(verb, color = c.dangerInk) { confirm = true }
    if (confirm) HSheet({ confirm = false }) {
        val active = activeGen?.choice?.recordId == entry.id
        Text("$verb ${entry.name}?", style = t.type.title)
        Spacer(Modifier.height(Space.s))
        Text((if (active) "Flyt will switch to the default Krets theme. " else "") +
            (if (entry.created) "Your photo in the gallery is not affected." else "The theme's files will be deleted from Flyt."), style = t.type.body)
        message?.let { Spacer(Modifier.height(Space.s)); Text(it, style = t.type.body.copy(color = c.dangerInk)) }
        Spacer(Modifier.height(Space.l))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            QuietButton("Keep") { confirm = false }
            Spacer(Modifier.width(Space.s))
            PrimaryButton(verb) {
                scope.launch {
                    when (val o = app.themes.remove(entry.id, entry.name, sig)) {
                        Outcome.Removed -> { confirm = false; onRemoved() }
                        is Outcome.Failed -> message = o.message
                        is Outcome.Uncertain -> message = o.message
                        else -> confirm = false
                    }
                }
            }
        }
    }
}

@Composable
private fun Colophon(entry: ThemeEntry, record: LoadedRecord, tokens: ResolvedColors) {
    val t = Heimflyt.t; val c = t.color
    var open by remember { mutableStateOf(false) }
    HRow("Colophon", subtitle = if (open) null else "palette, source and adjustments", compact = true, onClick = { open = !open },
        trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp, modifier = Modifier.then(if (open) Modifier.padding(0.dp) else Modifier)) })
    if (!open) return
    val p = record.palette
    Row(Modifier.fillMaxWidth().height(28.dp).clip(Shapes.xs)) {
        listOf("background", "lighter_background", "dark_foreground", "foreground", "bright_foreground").mapNotNull { p[it] }.forEach {
            Box(Modifier.weight(1f).fillMaxHeight().background(rgb(it)))
        }
    }
    Spacer(Modifier.height(Space.s))
    val named = listOf("accent", "red", "orange", "yellow", "green", "cyan", "blue", "magenta")
    named.chunked(4).forEach { row ->
        Row(Modifier.fillMaxWidth()) {
            row.forEach { k ->
                Row(Modifier.weight(1f).padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(12.dp).clip(CircleShape).background(rgb(p[k] ?: tokens.ground)).border(1.dp, c.hairline, CircleShape))
                    Text(" " + (p[k]?.let { "#%06x".format(it) } ?: "—"), style = t.type.meta)
                }
            }
        }
    }
    val source = when (record.record.paletteSource) { PaletteSource.ALACRITTY_TOML -> "from alacritty.toml"; PaletteSource.COLORS_TOML -> "from colors.toml"; PaletteSource.BUNDLED -> "Omarchy palette"; PaletteSource.GENERATED -> "generated from your photo" }
    Text("mode ${if (entry.light) "light" else "dark"} · $source" + when (val o = record.record.origin) {
        is ThemeOrigin.FileImport -> " · file ${o.sha256.take(7)}"
        is ThemeOrigin.GitHub -> " · ${o.display} · ${o.ref} @ ${o.commit.take(7)}"
        else -> ""
    },
        style = t.type.meta, modifier = Modifier.padding(vertical = Space.xs))
    BundledThemes.find(entry.id)?.let { Text("${it.credit}; packaged by Omarchy (MIT).", style = t.type.meta) }
    (tokens.adjustments + record.record.notes).forEach { Text("· $it", style = t.type.meta) }
}

// ---- install a theme: GitHub URL or .zip (SURFACES.md §9.3) ----

sealed interface InstallState {
    data class Enter(val text: String, val error: String?) : InstallState
    data class Inspecting(val name: String) : InstallState
    data class Report(val report: InstallReport) : InstallState
    data class Installing(val report: InstallReport, val done: Long) : InstallState
    data class Done(val name: String, val applied: Boolean, val note: String?) : InstallState
    data class Failed(val message: String) : InstallState
}

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_048_576.0)

@Composable
private fun InstallSheet(state: InstallState, sig: ZoneSignature?, onCancel: () -> Unit, onInspect: (String) -> Unit, onZip: () -> Unit,
                         onInstall: (InstallReport, Boolean) -> Unit, onClose: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    HSheet(onDismiss = if (state is InstallState.Installing) ({}) else onCancel) {
        when (state) {
            is InstallState.Enter -> {
                var text by remember(state) { mutableStateOf(state.text) }
                val focus = remember { androidx.compose.ui.focus.FocusRequester() }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                Text("Install a theme", style = t.type.title)
                Spacer(Modifier.height(Space.m))
                HField(text, { text = it }, "GitHub URL", focusRequester = focus, showGlyph = false,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Go),
                    modifier = Modifier.semantics { contentDescription = "GitHub URL" })
                state.error?.let { Text(it, style = t.type.secondary.copy(color = c.dangerInk), modifier = Modifier.padding(top = Space.s)) }
                Text("Works with Omarchy themes on GitHub, e.g. github.com/owner/omarchy-name-theme.", style = t.type.caption, modifier = Modifier.padding(vertical = Space.s))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    // The clipboard is read only when the owner taps Paste.
                    QuietButton("Paste") {
                        val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()?.take(400)?.let { text = it.trim() }
                    }
                    QuietButton("From a .zip file…", onClick = onZip)
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Inspect") { if (text.isNotBlank()) onInspect(text) }
                }
            }
            is InstallState.Inspecting -> {
                Text("Install a theme", style = t.type.title)
                Spacer(Modifier.height(Space.m)); LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.accent, trackColor = c.selection)
                Text("Looking at ${state.name}…", style = t.type.secondary, modifier = Modifier.padding(vertical = Space.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { QuietButton("Cancel", onClick = onCancel) }
            }
            is InstallState.Report -> {
                val r = state.report
                val tokens = remember(r) { TokenMapper.map(r.palette) }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.name, style = t.type.title)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Glyph(if (r.palette.light) R.drawable.glyph_sun else R.drawable.glyph_moon, c.inkMuted, 14.dp)
                            Text(" ${if (r.palette.light) "light" else "dark"} · ${r.origin}", style = t.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    if (sig != null) ThemedPreview(tokens) { MiniHome(sig, null, PreviewMode.RADIAL, Modifier.width(72.dp)) }
                }
                SectionLabel("Flyt will use")
                Text(if (r.source == PaletteSource.ALACRITTY_TOML) "palette from alacritty.toml" else "colors.toml", style = t.type.meta)
                Text("${r.backgroundCount} background${if (r.backgroundCount == 1) "" else "s"} · ${mb(r.backgroundBytes)}", style = t.type.meta)
                if (r.hasPreview) Text("preview.png", style = t.type.meta)
                val ignoredCount = r.ignored.values.sumOf { it.size }
                if (ignoredCount > 0) {
                    var open by remember { mutableStateOf(false) }
                    SectionLabel("Ignored — never downloaded or read")
                    Text("$ignoredCount file${if (ignoredCount == 1) "" else "s"}: " + IgnoredKind.entries.mapNotNull { k -> r.ignored[k]?.size?.let { "$it ${k.label}" } }.joinToString(" · "),
                        style = t.type.meta, modifier = Modifier.clickable(role = Role.Button) { open = !open })
                    if (open) r.ignored.values.flatten().take(40).forEach { Text("  $it", style = t.type.meta, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
                if (r.notes.isNotEmpty()) {
                    SectionLabel("notes")
                    r.notes.take(8).forEach { n -> Row(verticalAlignment = Alignment.Top) { Glyph(R.drawable.glyph_info, c.caution, 14.dp); Text(" $n", style = t.type.secondary) } }
                }
                Spacer(Modifier.height(Space.l))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    QuietButton("Cancel", onClick = onCancel); Spacer(Modifier.width(Space.s))
                    QuietButton("Install") { onInstall(r, false) }; Spacer(Modifier.width(Space.s))
                    PrimaryButton("Install & apply") { onInstall(r, true) }
                }
            }
            is InstallState.Installing -> {
                val total = state.report.totalBytes
                Text("Installing ${state.report.name}", style = t.type.title)
                Spacer(Modifier.height(Space.m))
                LinearProgressIndicator({ if (total > 0) (state.done.toFloat() / total).coerceIn(0f, 1f) else 1f }, Modifier.fillMaxWidth(), color = c.accent, trackColor = c.selection)
                Text("${mb(state.done)} of ${mb(total)}", style = t.type.meta, modifier = Modifier.padding(vertical = Space.s))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { QuietButton("Cancel", onClick = onCancel) }
            }
            is InstallState.Done -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Glyph(R.drawable.glyph_check, c.positive, 22.dp); Text("  ${state.name} is ready.", style = t.type.bodyStrong) }
                state.note?.let { Text(it, style = t.type.secondary, modifier = Modifier.padding(top = Space.s)) }
                Spacer(Modifier.height(Space.l))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PrimaryButton("Close", onClick = onClose) }
            }
            is InstallState.Failed -> {
                Row(verticalAlignment = Alignment.CenterVertically) { Glyph(R.drawable.glyph_warning, c.dangerInk, 22.dp); Text("  ${state.message}", style = t.type.body) }
                Spacer(Modifier.height(Space.l))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { PrimaryButton("Close", onClick = onClose) }
            }
        }
    }
}
