package no.heimflyt.launcher

import android.graphics.Bitmap
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.gesture.*
import no.heimflyt.launcher.ui.apps.AppsSurface
import no.heimflyt.launcher.ui.apps.TagsSurface
import no.heimflyt.launcher.ui.browse.BrowseRenderer
import no.heimflyt.launcher.ui.browse.BrowseSurface
import no.heimflyt.launcher.ui.browse.shownRenderer
import no.heimflyt.launcher.ui.home.DirectionsSheet
import no.heimflyt.launcher.ui.home.HomeHints
import no.heimflyt.launcher.ui.home.HomeSurface
import no.heimflyt.launcher.ui.home.KretsSignalOverlay
import no.heimflyt.launcher.ui.home.homeLearning
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.HeimflytTheme
import no.heimflyt.launcher.ui.theme.FallbackTokens
import no.heimflyt.launcher.ui.themes.ThemeDetail
import no.heimflyt.launcher.ui.themes.CreateSource
import no.heimflyt.launcher.ui.themes.CreateThemeScreen
import no.heimflyt.launcher.ui.themes.ThemePreviews
import no.heimflyt.launcher.ui.themes.ThemesGallery
import no.heimflyt.launcher.ui.themes.rememberZoneSignature
import no.heimflyt.launcher.theme.image.Protection
import no.heimflyt.launcher.ui.home.HomeStatusRow
import androidx.compose.foundation.Image
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import no.heimflyt.launcher.ui.tune.GroupEditor
import no.heimflyt.launcher.ui.components.*
import androidx.compose.material3.Text
import no.heimflyt.launcher.ui.tune.TuneActions
import no.heimflyt.launcher.ui.tune.TunePage
import no.heimflyt.launcher.ui.tune.TuneSurface

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun HeimflytScreen(app: HeimflytApplication, homeEpoch: Int, registerTouch: (RadialTouchView?) -> Unit, openHomeSettings: () -> Unit) {
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val settingsLoaded by app.settings.loaded.collectAsStateWithLifecycle()
    val active by app.themes.store.active.collectAsStateWithLifecycle()
    val wallpaper by app.themes.wallpaper.state.collectAsStateWithLifecycle()
    val themeLoadFailed by app.themes.loadFailed.collectAsStateWithLifecycle()
    val themesLoaded by app.themes.loaded.collectAsStateWithLifecycle()
    val pendingImport by app.themes.pendingImport.collectAsStateWithLifecycle()
    // Measured per THEME_ARCHITECTURE.md §8.4: were the generation's tokens ready for the first composition?
    val tokensAtFirstComposition = remember { app.themes.store.active.value != null }
    LaunchedEffect(Unit) { android.util.Log.i("HeimflytTheme", "first composition: tokens ready=$tokensAtFirstComposition") }
    var page by remember { mutableStateOf("home") }
    var query by remember { mutableStateOf("") }
    var pickerSlot by remember { mutableStateOf<Int?>(null) }
    var groupSlot by remember { mutableStateOf<Int?>(null) }
    var radialTag by remember { mutableStateOf<String?>(null) }
    var tagReturn by remember { mutableStateOf("apps") }
    var radialTagReturn by remember { mutableStateOf("tags") }
    var openedGroup by remember { mutableStateOf<HomeAction.Group?>(null) }
    var gestureChildren by remember { mutableStateOf<Map<Int, H7.Children>>(emptyMap()) }
    var showDirections by remember { mutableStateOf(false) }
    var createSource by remember { mutableStateOf<CreateSource?>(null) }
    // Home shows failures only; ordinary selections and cancels produce no text.
    var homeStatus by remember { mutableStateOf<String?>(null) }
    var noticeDismissed by remember { mutableStateOf(false) }
    var touch by remember { mutableStateOf<RadialTouchView?>(null) }
    var signalPaused by remember { mutableStateOf(false) }
    var gestureBindings by remember { mutableStateOf(settings.bindings) }
    val keyboard = LocalSoftwareKeyboardController.current
    val context = LocalContext.current
    val torchPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            if (app.semanticActions.launch(context, SemanticDestination.TORCH)) {
                app.launchCounts.record(HomeAction.Semantic(SemanticDestination.TORCH))
                app.refreshMostUsed()
            } else homeStatus = "Flashlight isn't available right now."
        } else homeStatus = "Camera permission is needed to toggle the flashlight."
    }
    fun home() { page = "home"; query = ""; pickerSlot = null; groupSlot = null; openedGroup = null; showDirections = false; keyboard?.hide() }
    fun dispatch(action: HomeAction, fromBrowse: Boolean = false) {
        when (action) {
            HomeAction.Apps -> { OpenTiming.mark("apps"); page = "apps"; query = "" }
            HomeAction.Search -> { OpenTiming.mark("search"); page = "search"; query = "" }
            is HomeAction.Tag -> { page = "search"; query = "#${action.name}" }
            is HomeAction.Group -> {
                if (action.fallbackTag != null) { page = "search"; query = "#${action.fallbackTag}" }
                else openedGroup = action
            }
            is HomeAction.Probe -> home()
            is HomeAction.App -> {
                home()
                if (app.apps.launch(action)) {
                    app.launchCounts.record(action)
                    app.refreshMostUsed()
                    if (settings.bindings.any { it is HomeAction.Tag && settings.tagMode(it.name) != TagRadialMode.FIXED }) app.apps.refresh()
                    if (fromBrowse) app.recents.record(RecentTarget("app", action.component, action.label, serial = action.userSerial))
                } else homeStatus = "Couldn't open ${action.label}. It may have been removed, or its profile is paused."
            }
            is HomeAction.Semantic -> {
                home()
                if (action.destination == SemanticDestination.TORCH &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    torchPermission.launch(Manifest.permission.CAMERA)
                    return
                }
                if (app.semanticActions.launch(context, action.destination)) {
                    app.launchCounts.record(action)
                    app.refreshMostUsed()
                    if (fromBrowse) app.recents.record(RecentTarget("semantic", action.destination.id, action.destination.label))
                } else homeStatus = if (action.destination == SemanticDestination.TORCH) "Flashlight isn't available right now."
                    else "${action.destination.label} isn't available. Check Android's default apps."
            }
        }
    }
    fun bind(slot: Int, action: HomeAction) {
        app.settings.update(bindings = settings.bindings.toMutableList().also { it[slot] = action })
        pickerSlot = null; page = TunePage.DIRECTIONS.route
    }
    fun renameTagBindings(old: String, new: String) {
        app.browse.tagRenamed(old, new)
        app.settings.renameTag(old, new, app.tags.label(new))
    }
    fun deleteTagBindings(name: String) {
        app.browse.tagDeleted(name)
        app.settings.deleteTag(name)
    }
    LaunchedEffect(homeEpoch) { home() }
    LaunchedEffect(pendingImport) { if (pendingImport != null) { touch?.cancel(CancelReason.SYSTEM); page = "themes" } }
    BackHandler {
        touch?.cancel(CancelReason.BACK)
        when {
            pickerSlot != null -> { pickerSlot = null; page = TunePage.DIRECTIONS.route }
            page == TunePage.SEARCH.route -> page = TunePage.APPS.route
            page.startsWith("settings/") -> page = TunePage.INDEX.route
            page.startsWith("themes/") -> page = "themes"
            page == "create" -> { createSource = null; page = "themes" }
            page == "themes" -> page = TunePage.LOOK.route
            page == "tags" -> page = tagReturn
            else -> home()
        }
    }
    DisposableEffect(Unit) { onDispose { registerTouch(null) } }
    // Bounded icon lookup for bound app directions only (never catalog enumeration on Home).
    var boundIcons by remember { mutableStateOf<Map<Int, Bitmap>>(emptyMap()) }
    LaunchedEffect(settings.bindings) {
        if (settings.bindings.none { it is HomeAction.App }) { boundIcons = emptyMap(); return@LaunchedEffect }
        withFrameNanos { } // after Home's first frame; the repository is created off the main thread
        val repo = withContext(Dispatchers.IO) { app.apps }
        val next = HashMap<Int, Bitmap>()
        settings.bindings.forEachIndexed { i, a -> if (a is HomeAction.App) repo.boundIcon(a.component, a.userSerial)?.let { next[i] = it } }
        boundIcons = next
    }
    // Drawing and gestures consume prepared children. Automatic tags refresh after the first frame or catalogue changes.
    // Bounded package/profile icon lookups run after the first frame, on configuration or package changes.
    var h7Children by remember { mutableStateOf<Map<Int, H7.Children>>(emptyMap()) }
    val h7Groups = H7.groups(settings)
    val automaticBindings = settings.bindings.filterIsInstance<HomeAction.Tag>().map { it.name }
        .filter { settings.tagMode(it) != TagRadialMode.FIXED }.toSet()
    LaunchedEffect(automaticBindings, settings.tagModes, settings.tuning.leftHanded) {
        if (automaticBindings.isNotEmpty() || settings.autoTags.isNotEmpty()) {
            withFrameNanos { }
            val repo = withContext(Dispatchers.IO) { app.apps }
            repo.refresh()
            repo.catalog.collect { if (it.authoritative) app.refreshMostUsed() }
        }
    }
    LaunchedEffect(h7Groups) {
        h7Children = emptyMap()
        if (h7Groups.isEmpty()) return@LaunchedEffect
        withFrameNanos { }
        val repo = withContext(Dispatchers.IO) { app.apps }
        repo.boundRevision.collect {
            val next = HashMap<Int, H7.Children>()
            h7Groups.forEach { (sector, group) ->
                val actions = HashMap<Int, HomeAction>()
                val icons = HashMap<Int, Bitmap>()
                group.children.forEachIndexed { slot, child ->
                    val icon = when (child) {
                        is HomeAction.App -> repo.boundIcon(child.component, child.userSerial)
                        is HomeAction.Semantic -> actionIcon(context, child)
                        else -> null
                    }
                    if (child != null && icon != null) { actions[slot] = child; icons[slot] = icon }
                }
                next[sector] = H7.Children(group, actions.toMap(), icons.toMap())
            }
            h7Children = next
        }
    }
    // H6.0 Browse experiment. Browse's store is read only on the Apps page (never on Home). Progressive draws its tray over
    // Home's wallpaper, so that page keeps the full-bleed image layer.
    val browseRenderer = if (page == "apps" && pickerSlot == null) app.browse.config.collectAsStateWithLifecycle().value.shownRenderer else null
    val overWallpaper = browseRenderer == BrowseRenderer.PROGRESSIVE
    // Home's wallpaper is drawn only when its generation equals the tokens' generation (R4); otherwise ground.
    val imageShown = (page == "home" || overWallpaper) && active?.background != null && wallpaper?.gen == active?.gen
    val signature = rememberZoneSignature()
    LaunchedEffect(themesLoaded, signature, active?.gen, themeLoadFailed) {
        if (themesLoaded && signature != null && active == null && !themeLoadFailed) {
            app.themes.apply("bundled:krets", no.heimflyt.launcher.theme.store.BackgroundChoice(no.heimflyt.launcher.theme.store.BackgroundKind.IMAGE, 0),
                no.heimflyt.launcher.theme.store.Framing(), no.heimflyt.launcher.theme.store.Strength.BALANCED, signature,
                extras = listOf(1, 2).map { no.heimflyt.launcher.theme.store.ImageSlot(it, no.heimflyt.launcher.theme.store.Framing()) },
                mode = no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE)
        }
    }
    // A real configuration change makes the solved zones stale: every text zone and bar gets its opaque protection (§8.5).
    val protection = when {
        !imageShown -> null
        signature == null || active?.signature == signature -> active?.protectionFor(wallpaper?.slot ?: 0) ?: Protection.conservative(active!!.tokens.isLight)
        else -> Protection.conservative(active!!.tokens.isLight)
    }
    LaunchedEffect(signature, active?.gen, page == "home") {
        val a = active ?: return@LaunchedEffect
        if (page != "home" || signature == null || a.background == null || a.signature == signature) return@LaunchedEffect
        kotlinx.coroutines.delay(1000) // settle: insets and size arrive in steps after a change
        app.themes.reprepare(signature)
    }
    // Cache-miss reload after genuine eviction happens when Home becomes visible; a retained bitmap makes this a no-op.
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val wallpaperPage = page == "home" || overWallpaper
    DisposableEffect(lifecycle, wallpaperPage) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_START && wallpaperPage) app.themes.wallpaper.ensure() }
        lifecycle.addObserver(observer)
        if (wallpaperPage) app.themes.wallpaper.ensure()
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(page.startsWith("themes") || page == "create") { if (!page.startsWith("themes") && page != "create") ThemePreviews.clear() }
    val tokens = active?.tokens ?: FallbackTokens.resolved
    val activity = context as? MainActivity
    LaunchedEffect(tokens) { activity?.applyWindowGround(tokens.ground) }
    LaunchedEffect(page == "home", protection, tokens, overWallpaper) {
        // Bars follow the resolved surface beneath them: Home's solved image appearance, or ground everywhere else.
        // Progressive Browse: the image under the status bar, the tray's ground under the navigation bar.
        if (protection != null && overWallpaper) activity?.applyBarAppearance(protection.statusLight, tokens.isLight)
        else if (protection != null) activity?.applyBarAppearance(protection.statusLight, protection.navLight)
        else activity?.applyBarAppearance(tokens.isLight, tokens.isLight)
    }
    LaunchedEffect(protection) { activity?.setNavContrastEnforced(protection == null || protection.navBand) }
    // Status spike: Android's status bar hidden on Home only; a swipe from the top edge shows it transiently (then the shade).
    val statusMode = settings.home.statusMode
    val hideStatus = page == "home" && statusMode != HomeStatusMode.ANDROID && !showDirections
    LaunchedEffect(hideStatus) { activity?.setHomeStatusHidden(hideStatus) }
    HeimflytTheme(tokens) {
        val c = Heimflyt.t.color
        // On Home the ground/wallpaper layer covers the window (whose background is already ground), so skip a redundant full-screen fill.
        Box(Modifier.fillMaxSize().then(if (wallpaperPage) Modifier else Modifier.background(c.ground))) {
            // Full-bleed layer outside the inset padding; content and the touch surface keep today's safe bounds.
            if (wallpaperPage) {
                val w = wallpaper
                when {
                    active == null -> DuskGround(Modifier.fillMaxSize().graphicsLayer())
                    imageShown && w != null -> Image(w.bitmap.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    else -> Unit // Plain, or awaiting the decode: the window background is ground.
                }
                val signalChoice = active?.choice
                if (page == "home" && imageShown && signalChoice?.recordId == "bundled:krets" &&
                    signalChoice.background.kind == no.heimflyt.launcher.theme.store.BackgroundKind.IMAGE) {
                    val slot = w?.slot ?: 0
                    KretsSignalOverlay(slot, signalChoice.slots.getOrNull(slot)?.framing ?: signalChoice.framing,
                        signalPaused, homeEpoch, Heimflyt.t.animationsOff)
                }
                if (protection?.statusBand == true && !hideStatus) Box(Modifier.fillMaxWidth().windowInsetsTopHeight(WindowInsets.statusBars).background(c.ground))
            }
            if (page == "home") {
                if (hideStatus && statusMode == HomeStatusMode.HEIMFLYT) HomeStatusRow(protection?.statusTextBackdrop == true,
                    Modifier.windowInsetsTopHeight(WindowInsets.statusBarsIgnoringVisibility))
                if (protection?.navBand == true) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().windowInsetsBottomHeight(WindowInsets.navigationBars).background(c.ground))
            }
            // Home keeps its layout when the status bar is hidden (spike): pad by the status band regardless of visibility.
            // Stable insets: the bars *ignoring visibility* (plus cutout, IME and gestures), so hiding Android's status bar on Home
            // (spike) never moves Heimflyt's layout. (The spike's ~44 frames per Home return come from the platform insets
            // animation itself, not from this padding.)
            val safe = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.ime).union(WindowInsets.systemGestures)
            @Composable fun Browse(insets: WindowInsets) = BrowseSurface(app, settings.tuning.leftHanded, insets,
                onApp = { entry -> dispatch(HomeAction.App(entry.info.componentName.flattenToString(), entry.serial, entry.label), true) },
                onSemantic = { dispatch(HomeAction.Semantic(it), true) },
                onHome = { home() }, onSearch = { page = "search"; query = it }, onEditTags = { tagReturn = "apps"; page = "tags" },
                onTune = { page = TunePage.INDEX.route }, onError = { homeStatus = it })
            Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
                when {
                    page == "home" -> HomeSurface(settings, settingsLoaded,
                        status = homeStatus ?: settings.notice?.takeIf { !noticeDismissed }
                            ?: "Theme couldn't be loaded. Using the Krets theme.".takeIf { themeLoadFailed && !noticeDismissed },
                        h7Readout = H7.readout(settings, h7Children),
                        h7Children = h7Children,
                        boundIcons = boundIcons, protection = protection,
                        onDismissStatus = { homeStatus = null; noticeDismissed = true },
                        onTouch = { touch = it; registerTouch(it) },
                        onBegin = { signalPaused = true; gestureBindings = settings.bindings.toList(); gestureChildren = h7Children; homeStatus = null },
                        onResult = { result, frame ->
                            signalPaused = false
                            when (result) {
                                // H7: a child release opens its app only with "Launch child apps" on; it never teaches level-1 learning.
                                is GestureResult.Selected -> if (result.child < 0) {
                                    // Learning is a consequence of the finished gesture only; dispatch is the success proxy.
                                    // Do not teach level-1 familiarity from a gesture that touched a child.
                                    if (!frame.childEver) app.settings.recordGesture(result.sector, clean = !frame.hesitated)
                                    dispatch(gestureBindings.getOrElse(result.sector) { HomeAction.Probe(result.sector + 1) })
                                } else if (H7.launches(settings)) {
                                    gestureChildren[result.sector]?.actions?.get(result.child)?.let { dispatch(it) }
                                        ?: run { homeStatus = "That item isn't available, so nothing opened." }
                                }
                                GestureResult.Tap -> showDirections = true
                                is GestureResult.Cancelled -> {
                                    // Leaving toward a direction and returning to center is uncertainty about that direction.
                                    // A correction after nested lock is rehearsal input, not level-1 uncertainty.
                                    if (result.reason == CancelReason.CENTER && !frame.nestedEver) frame.lastSector?.let { app.settings.recordGesture(it, clean = false) }
                                }
                            }
                            H7.finished(result, frame, touch?.targets, settings, gestureChildren)
                        },
                        onAccessibleDirection = { if (it is HomeAction.Group) openedGroup = it else dispatch(it) },
                        onApps = { OpenTiming.mark("apps"); page = "apps"; query = "" },
                        onSearch = { OpenTiming.mark("search"); page = "search"; query = "" }, onTune = { page = TunePage.INDEX.route })
                    page == "apps" && overWallpaper -> Unit // drawn full-bleed below
                    page == "apps" && pickerSlot == null -> Browse(WindowInsets(0))
                    page == "apps" -> AppsSurface(app.apps, app.tags, query, { query = it }, pickerSlot, settings.tuning,
                        hintSeen = settings.home.appsHintSeen, onHintSeen = { if (!settings.home.appsHintSeen) app.settings.updateHome { it.copy(appsHintSeen = true) } },
                        onApp = { entry ->
                            val action = HomeAction.App(entry.info.componentName.flattenToString(), entry.serial, entry.label)
                            pickerSlot?.let { bind(it, action) } ?: dispatch(action, true)
                        },
                        onBack = { if (pickerSlot != null) { pickerSlot = null; page = TunePage.DIRECTIONS.route } else home() },
                        onEditTags = { tagReturn = "apps"; page = "tags" }, onTune = { page = TunePage.INDEX.route }, onError = { homeStatus = it })
                    page == "tag-radial" && radialTag != null -> {
                        val tag = radialTag!!
                        GroupEditor(-1, settings.tagGroups[tag] ?: HomeAction.Group(app.tags.label(tag), List(4) { null }, tag),
                            settings.tuning, app, tag = tag,
                            onSave = { group, mode ->
                                app.settings.setTagGroup(tag, group)
                                app.settings.setTagMode(tag, mode)
                                if (mode != TagRadialMode.FIXED) { app.apps.refresh(); app.refreshMostUsed(tag) }
                                radialTag = null; page = radialTagReturn
                            },
                            onCancel = { radialTag = null; page = radialTagReturn })
                    }
                    page == "group" && groupSlot != null -> GroupEditor(groupSlot!!, settings.bindings[groupSlot!!], settings.tuning, app,
                        onSave = { group, _ -> bind(groupSlot!!, group); groupSlot = null },
                        onCancel = { groupSlot = null; page = TunePage.DIRECTIONS.route })
                    page == "tags" -> TagsSurface(app.tags, app.apps, ::renameTagBindings, ::deleteTagBindings, onBack = { page = tagReturn },
                        onEditRadial = { radialTag = it; radialTagReturn = "tags"; page = "tag-radial" },
                        onMembershipChanged = { app.refreshMostUsed(it) })
                    page == "search" -> SearchSurface(app, query, { query = it },
                        onApp = { entry -> dispatch(HomeAction.App(entry.info.componentName.flattenToString(), entry.serial, entry.label), true) },
                        onAction = { dispatch(it, true) }, onBack = { home() }, onTune = { page = TunePage.INDEX.route }, onError = { homeStatus = it },
                        bottomAnchored = settings.home.searchAtBottom)
                    page == "themes" -> ThemesGallery(app, onOpen = { page = "themes/$it" }, onCreate = { createSource = CreateSource.Picked(it); page = "create" },
                        pendingImport = pendingImport, onImportTaken = { app.themes.pendingImport.value = null },
                        onBack = { page = TunePage.LOOK.route })
                    page.startsWith("themes/") -> ThemeDetail(app, page.removePrefix("themes/"), onEdit = { createSource = CreateSource.Edit(it); page = "create" },
                        onBack = { page = "themes" })
                    page == "create" && createSource != null -> CreateThemeScreen(app, createSource!!,
                        onDone = { applied -> createSource = null; if (applied) home() else page = "themes" },
                        onCancel = { createSource = null; page = "themes" })
                    TunePage.from(page) != null -> TuneSurface(TunePage.from(page)!!, settings, app, TuneActions(
                        onTuning = { app.settings.update(tuning = it) }, onBind = ::bind,
                        onPickApp = { slot -> pickerSlot = slot; query = ""; page = "apps" },
                        onEditGroup = { slot -> groupSlot = slot; page = "group" },
                        onEditTag = { radialTag = it; radialTagReturn = TunePage.DIRECTIONS.route; page = "tag-radial" },
                        onTags = { tagReturn = TunePage.APPS.route; page = "tags" },
                        onResetLearning = { app.settings.resetFamiliarity() }, onHome = { app.settings.updateHome(it) },
                        onOpenHomeSettings = openHomeSettings, onShowDirections = { home(); showDirections = true },
                        onNavigate = { page = it.route }, onThemes = { page = "themes" }, onTheme = { page = "themes/$it" },
                        onBack = { if (page == TunePage.INDEX.route) home()
                            else page = if (page == TunePage.SEARCH.route) TunePage.APPS.route else TunePage.INDEX.route }))
                }
            }
            if (page == "apps" && overWallpaper) Browse(safe)
            openedGroup?.let { group -> HSheet({ openedGroup = null }) {
                Text(group.name, style = Heimflyt.t.type.title)
                group.children.forEach { child -> if (child != null) HRow(child.label(), onClick = { openedGroup = null; dispatch(child) }) }
            } }
            if (showDirections) DirectionsSheet(settings.bindings, settings.tuning, homeLearning(settings.home.cleanDispatches, settings.home.hints),
                onDispatch = { showDirections = false; if (it is HomeAction.Group) openedGroup = it else dispatch(it) },
                onApps = { showDirections = false; page = "apps"; query = "" },
                onSearch = { showDirections = false; page = "search"; query = "" },
                onTune = { showDirections = false; page = TunePage.INDEX.route },
                onHideHints = { app.settings.updateHome { it.copy(hints = HomeHints.HIDE) } },
                onDismiss = { showDirections = false })
        }
    }
}

/**
 * H6.1 measurement (log only): Home → Apps/Search open latency, from the tap handler to the first drawn frame of the
 * surface and to its content. Marked only on Home's words and radial dispatch; read once, then cleared.
 */
object OpenTiming {
    private var start = 0L
    private var target = ""
    fun mark(surface: String) { start = android.os.SystemClock.uptimeMillis(); target = surface }
    /** Logs [stage] for [surface] if an open of it is being timed; [last] ends the measurement. */
    fun log(surface: String, stage: String, last: Boolean = false) {
        if (start == 0L || target != surface) return
        android.util.Log.i("HeimflytPerf", "open $surface $stage ${android.os.SystemClock.uptimeMillis() - start} ms")
        if (last) start = 0L
    }
}

/** The compiled fallback's static Dusk ground (Tokyo Night, before any generation exists): drawn in its own layer, so gesture frames never redraw it. */
@Composable
private fun DuskGround(modifier: Modifier) {
    val c = Heimflyt.t.color
    Box(modifier.drawBehind {
        drawRect(Brush.verticalGradient(0f to c.duskTop, 0.65f to c.ground, 1f to c.ground))
        val w = size.width * 1.2f
        val h = size.height * 0.3f
        // An elliptical glow: a circle scaled vertically about the bottom centre.
        scale(1f, h / w, Offset(size.width / 2, size.height)) {
            drawCircle(Brush.radialGradient(listOf(c.duskGlow, c.duskGlow.copy(alpha = 0f)), center = Offset(size.width / 2, size.height), radius = w / 2),
                radius = w / 2, center = Offset(size.width / 2, size.height))
        }
    })
}
