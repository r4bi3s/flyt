package no.heimflyt.launcher.ui.browse

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.AppEntry
import no.heimflyt.launcher.HeimflytApplication
import no.heimflyt.launcher.R
import no.heimflyt.launcher.SemanticDestination
import no.heimflyt.launcher.refreshMostUsed
import no.heimflyt.launcher.tagMemberKey
import no.heimflyt.launcher.ui.apps.AppSheet
import no.heimflyt.launcher.ui.apps.TagItemPicker
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Motion
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space

/** Where Browse is, above its resting renderer. Shared by all renderers. */
private sealed interface BrowseView {
    data class Tag(val name: String) : BrowseView
    data object New : BrowseView
    data object Untagged : BrowseView
    data object All : BrowseView
}

/**
 * H6.0 Browse: one state (the model's results), three TEMPORARY experimental renderers chosen under Tune → Advanced or ⋯.
 * The renderer only arranges shared components; switching it never changes slots, tags or order.
 * [safe] is applied here only for Progressive, which draws its tray over Home's wallpaper outside the screen's padding.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowseSurface(app: HeimflytApplication, leftHanded: Boolean, safe: WindowInsets, onApp: (AppEntry) -> Unit,
    onSemantic: (SemanticDestination) -> Unit, onHome: () -> Unit,
    onSearch: (String) -> Unit, onEditTags: () -> Unit, onTune: () -> Unit, onError: (String) -> Unit) {
    val t = Heimflyt.t; val c = t.color
    val repo = app.apps; val tags = app.tags
    val config by app.browse.config.collectAsStateWithLifecycle()
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val activeTagGroups = remember(settings.tagGroups, settings.tagModes, settings.autoTagGroups) { settings.activeTagGroups() }
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    // Reconcile profiles on entry/resume of Browse, never on Home re-entry (as Apps did).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(repo, lifecycle) {
        repo.refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) repo.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    // Install times cost one package-manager call per app: read off the main thread, only while Browse is open.
    val installTimes by produceState(emptyMap<String, Long>(), catalog.apps) {
        value = withContext(Dispatchers.IO) { catalog.apps.associate { it.key to runCatching { it.info.firstInstallTime }.getOrDefault(0L) } }
    }
    var revision by remember { mutableIntStateOf(0) }
    val names = remember(revision) { tags.names }
    val assignments = remember(revision) { tags.assignments }
    val entries = remember(catalog.apps) { catalog.apps.associateBy { it.key } }
    val items = remember(catalog.apps, installTimes) { BrowseModel.catalogue(catalog.apps.map { BrowseItem(it.key, it.label, installTimes[it.key] ?: 0L) }) }
    val slots = remember(config.slots, names) { BrowseModel.reconcile(config.slots, names) }
    val counts = remember(items, assignments, names) { names.associateWith { tag -> BrowseModel.inTag(items, assignments, tag).size +
        SemanticDestination.entries.count { tag in tags.tags("semantic:${it.id}") } } }
    val others = remember(names, slots) { BrowseModel.otherTags(names, slots) }
    val now = remember(installTimes) { System.currentTimeMillis() }
    val newItems = remember(items, now) { BrowseModel.newItems(items, now) }
    val untagged = remember(items, assignments, names) { BrowseModel.untagged(items, assignments, names) }
    LaunchedEffect(revision, catalog.apps, catalog.authoritative, settings.tagModes) {
        if (catalog.authoritative) app.refreshMostUsed()
    }

    LaunchedEffect(Unit) { withFrameNanos { }; no.heimflyt.launcher.OpenTiming.log("apps", "first frame") }
    LaunchedEffect(catalog.loaded && items.isNotEmpty()) {
        if (catalog.loaded && items.isNotEmpty()) { withFrameNanos { }; no.heimflyt.launcher.OpenTiming.log("apps", "content (${items.size} apps)", last = true) }
    }
    var view by remember { mutableStateOf<BrowseView?>(null) }
    var expanded by remember { mutableStateOf(false) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var editSlot by remember { mutableStateOf<Int?>(null) }
    var addingSlot by remember { mutableStateOf(false) }
    var allTags by remember { mutableStateOf(false) }
    var addingItemsToTag by remember { mutableStateOf<String?>(null) }
    var tagAppQuery by remember { mutableStateOf("") }
    // Back closes the innermost Browse state first (detail, then Progressive's expanded catalogue), then Home.
    BackHandler(enabled = view != null || expanded) { if (view != null) view = null else expanded = false }

    val renderer = config.shownRenderer
    val progressive = renderer == BrowseRenderer.PROGRESSIVE
    val gutter = Modifier.padding(horizontal = Space.gutter - Space.xs)
    val page = if (progressive) Modifier.fillMaxSize().background(c.ground).windowInsetsPadding(safe).then(gutter) else Modifier.fillMaxSize().then(gutter)
    val options: (AppEntry) -> Unit = { sheetApp = it }
    fun radialKeys(tag: String): List<String> {
        val children = activeTagGroups[tag]?.children.orEmpty()
        return (if (settings.tuning.leftHanded) children.reversed() else children)
            .mapNotNull { it?.tagMemberKey() }
    }

    @Composable fun Menu() = Box {
        var open by remember { mutableStateOf(false) }
        IconButtonGlyph(R.drawable.glyph_more, "More") { open = true }
        DropdownMenu(open, { open = false }) {
            if (slots.size < MAX_SLOTS) DropdownMenuItem({ Text("Add tag slot") }, { open = false; addingSlot = true })
            if (names.isNotEmpty()) DropdownMenuItem({ Text("All tags") }, { open = false; allTags = true })
            DropdownMenuItem({ Text("Edit tags") }, { open = false; onEditTags() })
            if (BROWSE_EXPERIMENT_EXPOSED) BrowseRenderer.entries.forEach { r ->
                DropdownMenuItem({ Text("Browse experiment: ${r.label}" + if (r == renderer) " ✓" else "") },
                    { open = false; view = null; expanded = false; app.browse.setRenderer(r) })
            }
            DropdownMenuItem({ Text("Tune") }, { open = false; onTune() })
        }
    }
    @Composable fun Header() = ScreenHeader("Apps", onBack = null, meta = "${items.size} apps" + if (BROWSE_EXPERIMENT_EXPOSED) " · ${renderer.label} (experiment)" else "", trailing = {
        QuietButton("Tags", color = c.ink, onClick = onEditTags)
        Menu(); IconButtonGlyph(R.drawable.glyph_close, "Home", onClick = onHome)
    })
    @Composable fun Status() {
        catalog.warning?.let { StatusPill(it, { repo.refresh() }) }
        if (!catalog.loaded) Text("Loading apps…", Modifier.padding(Space.l), style = t.type.caption)
    }
    @Composable fun Slots(showAdd: Boolean) {
        val count = slots.size + if (showAdd && slots.size < MAX_SLOTS) 1 else 0
        if (count == 0) return
        ThumbGrid(count, leftHanded, Modifier.padding(vertical = Space.xs)) { i, m ->
            val name = slots.getOrNull(i)
            val content = when {
                i >= slots.size -> SlotContent.Add
                name == null -> SlotContent.Hole
                else -> {
                    val members = BrowseModel.inTag(items, assignments, name)
                    val previews: List<BrowseTarget> = members.mapNotNull { entries[it.key]?.let(BrowseTarget::App) } +
                        SemanticDestination.entries.filter { name in tags.tags("semantic:${it.id}") }.map(BrowseTarget::Action)
                    val byKey = previews.associateBy { it.key }
                    val first = radialKeys(name).distinct().mapNotNull(byKey::get)
                    SlotContent.Tag(name, tags.label(name), first + previews.filterNot { it.key in first.map { p -> p.key } })
                }
            }
            SlotTile(i, content, repo, onClick = {
                when (content) { is SlotContent.Tag -> view = BrowseView.Tag(content.name); SlotContent.Hole -> editSlot = i; SlotContent.Add -> addingSlot = true }
            }, onLongClick = if (content == SlotContent.Add) null else ({ editSlot = i }), modifier = m)
        }
    }
    @Composable fun Cloud(maxRows: Int) = TagCloud(others, { view = BrowseView.Tag(it) }, { allTags = true }, maxRows = maxRows, total = names.size,
        label = tags::label)
    @Composable fun Search() = SearchEntry("Search apps and tags", { onSearch("") }, Modifier.padding(vertical = Space.s))
    @Composable fun Catalogue(modifier: Modifier) = CatalogueList(items, entries, repo, leftHanded, onApp, options, modifier)

    Box(Modifier.fillMaxSize()) {
        when (val v = view) {
            is BrowseView.Tag -> Box(page) {
                val tagItems = remember(items, assignments, v.name) { BrowseModel.inTag(items, assignments, v.name) }
                val actionMembers = SemanticDestination.entries.filter { v.name in tags.tags("semantic:${it.id}") }
                val members = tagItems.map { it.key } + actionMembers.map { "semantic:${it.id}" }
                val selectedKeys = radialKeys(v.name)
                val byKey: Map<String, BrowseTarget> = tagItems.mapNotNull { entries[it.key]?.let(BrowseTarget::App) }
                    .plus(actionMembers.map(BrowseTarget::Action)).associateBy { it.key }
                val quick = selectedKeys.distinct().mapNotNull(byKey::get)
                val cells = remember(members, config.tagCells[v.name]) { BrowseModel.stableCells(config.tagCells[v.name].orEmpty(), members) }
                // Only an authoritative catalogue (complete, no warning) may record holes: a cold start, a locked profile or a
                // failed refresh never erases the learned cells.
                LaunchedEffect(v.name, cells, catalog.authoritative) {
                    BrowseModel.cellsToPersist(catalog.authoritative, config.tagCells[v.name].orEmpty(), cells)?.let { app.browse.setCells(v.name, it) }
                }
                BrowseDetail("#${tags.label(v.name)}", "${counts[v.name] ?: 0} items · A–Z", tagItems, entries, repo, leftHanded,
                    spatial = true, lettered = true, onApp = onApp, onOptions = options, onBack = { view = null },
                    searchLabel = "Search in #${tags.label(v.name)}", onSearch = { onSearch("#${v.name} ") },
                    empty = "No items have #${tags.label(v.name)} yet.",
                    cells = cells, quick = quick, actions = actionMembers, onAction = onSemantic,
                    onAddItems = { addingItemsToTag = v.name; tagAppQuery = "" })
            }
            BrowseView.New -> Box(page) {
                BrowseDetail("New", "installed in the last 7 days · newest first", newItems, entries, repo, leftHanded, spatial = true, lettered = false,
                    onApp = onApp, onOptions = options, onBack = { view = null }, searchLabel = "Search apps and tags", onSearch = { onSearch("") },
                    empty = "Nothing installed in the last 7 days.")
            }
            BrowseView.Untagged -> Box(page) {
                BrowseDetail("Untagged", "${untagged.size} apps without a tag · A–Z", untagged, entries, repo, leftHanded, spatial = true, lettered = true,
                    onApp = onApp, onOptions = options, onBack = { view = null }, searchLabel = "Search apps and tags", onSearch = { onSearch("") },
                    empty = "Every app has a tag.")
            }
            BrowseView.All -> Column(page) {
                ScreenHeader("All apps", onBack = { view = null }, meta = "${items.size} apps · A–Z")
                Status()
                Catalogue(Modifier.weight(1f))
                Search()
            }
            null -> when (renderer) {
                // A · Spatial-first: calm space above; slots dominate in the thumb zone; tags and recovery are secondary.
                BrowseRenderer.SPATIAL -> Column(page) {
                    Header(); Status()
                    Spacer(Modifier.weight(1f))
                    if (others.isNotEmpty()) SectionLabel("tags")
                    if (slots.isEmpty()) Text("Tag slots keep chosen tags under your thumb. Long-press a slot to change it.",
                        Modifier.padding(Space.xs), style = t.type.caption)
                    Cloud(maxRows = 2)
                    RecoveryRow(newItems.size, untagged.size, items.size, { view = BrowseView.New }, { view = BrowseView.Untagged }, { view = BrowseView.All })
                    Slots(showAdd = true)
                    Search()
                }
                // B · Catalogue-first: the A–Z list dominates; the slot shelf sits in the thumb zone. With 0 slots: today's Apps.
                BrowseRenderer.CATALOGUE -> Column(page) {
                    Header(); Status()
                    if (newItems.isNotEmpty()) QuietButton("New · " + newItems.take(3).joinToString(", ") { it.label } + if (newItems.size > 3) " …" else "",
                        color = c.ink) { view = BrowseView.New }
                    Catalogue(Modifier.weight(1f))
                    Cloud(maxRows = 1)
                    RecoveryRow(0, untagged.size, null, {}, { view = BrowseView.Untagged }, {})
                    Slots(showAdd = false)
                    Search()
                }
                // C · Progressive: a tray over Home's wallpaper; the whole catalogue is behind a tappable, draggable handle.
                BrowseRenderer.PROGRESSIVE -> AnimatedContent(expanded, transitionSpec = {
                    if (targetState && !t.animationsOff) fadeIn(tween(Motion.STANDARD)) togetherWith slideOutVertically(tween(Motion.STANDARD)) { it }
                    else EnterTransition.None togetherWith ExitTransition.None
                }, label = "tray") { open -> if (!open) Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.weight(1f).fillMaxWidth().semantics { contentDescription = "Back to Home" }
                        .clickable(interactionSource = null, indication = null, onClickLabel = "Home", onClick = onHome))
                    Column(Modifier.fillMaxWidth().clip(Shapes.l).background(c.ground)
                        .windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)).then(gutter)) {
                        TrayHandle(items.size, expanded = false, onToggle = { expanded = true }) { Menu() }
                        Status()
                        RecoveryRow(newItems.size, untagged.size, null, { view = BrowseView.New }, { view = BrowseView.Untagged }, {})
                        Cloud(maxRows = 2)
                        Slots(showAdd = true)
                        Search()
                    }
                } else Column(page) {
                    // Expanded: the slot grid has left as one unit; the catalogue owns the tray. Back or the handle returns at once.
                    TrayHandle(items.size, expanded = true, onToggle = { expanded = false }) { Menu() }
                    if (newItems.isNotEmpty()) QuietButton("New · " + newItems.take(3).joinToString(", ") { it.label } + if (newItems.size > 3) " …" else "",
                        color = c.ink) { view = BrowseView.New }
                    Catalogue(Modifier.weight(1f))
                    Search()
                } }
            }
        }
    }

    sheetApp?.let { entry ->
        AppSheet(entry, repo, tags, revision, onChanged = { revision++ }, onOpen = { sheetApp = null; onApp(entry) },
            onInfo = { if (!repo.details(entry)) onError("App info is unavailable."); sheetApp = null }, onDismiss = { sheetApp = null })
    }
    addingItemsToTag?.let { tag ->
        HSheet({ addingItemsToTag = null }) {
            Text("Add to #${tags.label(tag)}", style = t.type.title)
            TagItemPicker(tag, catalog, repo, tags, tagAppQuery, { tagAppQuery = it }) {
                revision++
                app.refreshMostUsed(tag)
            }
            QuietButton("Done") { addingItemsToTag = null }
        }
    }
    if (allTags) AllTagsSheet(names, counts, onPick = { allTags = false; view = BrowseView.Tag(it) },
        onEditTags = { allTags = false; onEditTags() }, onDismiss = { allTags = false }, label = tags::label)
    if (addingSlot) HSheet({ addingSlot = false }) {
        Text("Tag slot ${slots.size + 1}", style = t.type.title)
        Text("New slots are added after the last one; existing slots never move.", style = t.type.caption)
        if (slots.size >= MAX_SLOTS) Text("All $MAX_SLOTS slots are in use.", style = t.type.body)
        else if (others.isEmpty()) {
            Text(if (names.isEmpty()) "No tags yet. Long-press an app to tag it, or edit tags." else "Every tag already has a slot.", style = t.type.body)
            QuietButton("Edit tags…") { addingSlot = false; onEditTags() }
        } else TagPicker(others, counts, label = tags::label, onPick = { name -> app.browse.updateSlots { BrowseModel.add(BrowseModel.reconcile(it, names), name) }; addingSlot = false })
    }
    editSlot?.let { i ->
        val current = slots.getOrNull(i)
        HSheet({ editSlot = null }) {
            Text("Slot ${i + 1}" + (current?.let { " · #${tags.label(it)}" } ?: " · empty"), style = t.type.title)
            Text("Slot 1 is nearest your thumb. Slots move only when you move them.", style = t.type.caption)
            FlowRow {
                if (i > 0) QuietButton("Move nearer thumb") { app.browse.updateSlots { BrowseModel.swap(BrowseModel.reconcile(it, names), i, i - 1) }; editSlot = i - 1 }
                if (i < slots.size - 1) QuietButton("Move farther") { app.browse.updateSlots { BrowseModel.swap(BrowseModel.reconcile(it, names), i, i + 1) }; editSlot = i + 1 }
                QuietButton("Remove slot", color = c.dangerInk) { app.browse.updateSlots { BrowseModel.remove(BrowseModel.reconcile(it, names), i) }; editSlot = null }
            }
            SectionLabel("show a tag")
            TagPicker(names, counts, current = current, label = tags::label, onPick = { name ->
                app.browse.updateSlots { BrowseModel.assign(BrowseModel.reconcile(it, names), i, name) }; editSlot = null
            })
        }
    }
}

/** Progressive's handle: tap toggles (no drag needed); a vertical drag on the handle only (never the list) does the same. */
@Composable
private fun TrayHandle(count: Int, expanded: Boolean, onToggle: () -> Unit, trailing: @Composable () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    var drag by remember { mutableFloatStateOf(0f) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).heightIn(min = Space.target).clip(Shapes.s)
            .clickable(onClickLabel = if (expanded) "Collapse all apps" else "Show all apps", onClick = onToggle)
            .pointerInput(expanded) {
                val threshold = 40.dp.toPx()
                detectVerticalDragGestures(onDragStart = { drag = 0f }, onDragCancel = { drag = 0f }, onDragEnd = {
                    if ((!expanded && drag < -threshold) || (expanded && drag > threshold)) onToggle(); drag = 0f
                }) { change, dy -> change.consume(); drag += dy }
            }.padding(vertical = Space.xs), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(c.ink.copy(alpha = 0.3f)))
            Spacer(Modifier.height(Space.xs))
            Text("All apps · $count ${if (expanded) "↓" else "↑"}", style = t.type.label.copy(color = c.ink))
        }
        trailing()
    }
}
