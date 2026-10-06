package no.heimflyt.launcher.ui.apps

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import no.heimflyt.launcher.*
import no.heimflyt.launcher.R
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Space

/** Apps' tag filter reuses Search's parser: exact tag, then unique prefix; ambiguous prefixes filter nothing. */
data class AppsFilter(val tag: String?, val text: String, val ambiguous: List<String>)
fun appsFilter(query: String, names: List<String>): AppsFilter {
    val parsed = ParsedSearch.from(query)
    if (parsed.tag == null) return AppsFilter(null, query, emptyList())
    val tag = resolveTag(parsed.tag, names)
    return AppsFilter(tag, parsed.text, if (tag == null) names.filter { it.startsWith(parsed.tag) } else emptyList())
}
fun sectionLetter(label: String): String = label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun AppsSurface(
    repo: AppRepository, tags: TagStore, query: String, onQuery: (String) -> Unit,
    pickerSlot: Int?, pickerTuning: TuningParams, hintSeen: Boolean, onHintSeen: () -> Unit,
    onApp: (AppEntry) -> Unit, onBack: () -> Unit, onEditTags: () -> Unit, onTune: () -> Unit, onError: (String) -> Unit,
) {
    val t = Heimflyt.t; val c = t.color
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    // Reconcile profiles on entry/resume of this surface, never on Home re-entry.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(repo, lifecycle) {
        repo.refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) repo.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var tagRevision by remember { mutableIntStateOf(0) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var trayOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val filter = remember(query, tagRevision, tags.names) { appsFilter(query, tags.names) }
    val apps = remember(catalog.apps, filter, tagRevision) {
        catalog.apps.filter { it.label.contains(filter.text.trim(), ignoreCase = true) && (filter.tag == null || filter.tag in tags.tags(it.key)) }
            .let { if (filter.ambiguous.isNotEmpty()) emptyList() else it }
    }
    val grouped = remember(apps) { apps.groupBy { sectionLetter(it.label) } }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        if (pickerSlot != null) {
            val angle = RadialGeometry.sectorAngle(pickerSlot, pickerTuning.safe())
            Row(Modifier.fillMaxWidth().heightIn(min = Space.target), verticalAlignment = Alignment.CenterVertically) {
                DirectionArrow(angle, c.accent); Spacer(Modifier.width(Space.s))
                Text("Choose an app for direction ${pickerSlot + 1}", style = t.type.bodyStrong, modifier = Modifier.weight(1f))
                QuietButton("Cancel", onClick = onBack)
            }
        } else ScreenHeader("Apps", onBack = null,
            meta = "${apps.size} apps" + (filter.tag?.let { " · #${tags.label(it)}" } ?: ""),
            trailing = {
                Box {
                    IconButtonGlyph(R.drawable.glyph_more, "More") { menuOpen = true }
                    DropdownMenu(menuOpen, { menuOpen = false }) {
                        DropdownMenuItem({ Text("Edit tags") }, { menuOpen = false; onEditTags() })
                    }
                }
                IconButtonGlyph(R.drawable.glyph_tune, "Tune", onClick = onTune)
                IconButtonGlyph(R.drawable.glyph_close, "Home", onClick = onBack)
            })
        if (pickerSlot == null && !hintSeen && catalog.loaded) Text("Long-press an app for tags and info.", style = t.type.caption,
            modifier = Modifier.padding(horizontal = Space.xs, vertical = Space.xs))
        catalog.warning?.let { StatusPill(it, { repo.refresh() }) }
        if (!catalog.loaded) Text("Loading apps…", Modifier.padding(Space.l), style = t.type.caption)
        if (catalog.loaded && apps.isEmpty()) Text("No apps match.", Modifier.padding(Space.l), style = t.type.caption)
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            grouped.forEach { (letter, entries) ->
                stickyHeader(key = "letter:$letter") {
                    Text(letter, style = t.type.meta, modifier = Modifier.fillMaxWidth().background(c.ground).padding(start = Space.xs, top = Space.s, bottom = Space.xs))
                }
                items(entries, key = { it.key }) { entry ->
                    val options = if (pickerSlot == null) listOf(CustomAccessibilityAction("App options") { sheetApp = entry; onHintSeen(); true }) else emptyList()
                    HRow(entry.label, modifier = Modifier.semantics { customActions = options }, onClick = { onApp(entry) },
                        onLongClick = if (pickerSlot == null) ({ sheetApp = entry; onHintSeen() }) else null, onLongClickLabel = "App options",
                        leading = { AppIcon(repo, entry) })
                }
            }
        }
        if (pickerSlot == null && trayOpen) {
            HorizontalDivider(color = c.hairline)
            FlowRow(Modifier.fillMaxWidth().padding(vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                tags.names.forEach { name -> TagWord(tags.label(name), selected = name == filter.tag, onClick = { onQuery(if (name == filter.tag) "" else "#$name "); trayOpen = false }) }
                QuietButton("Edit tags…", color = c.inkMuted) { trayOpen = false; onEditTags() }
            }
        }
        if (filter.ambiguous.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            filter.ambiguous.take(4).forEach { name -> TagWord(tags.label(name), onClick = { onQuery("#$name ") }) }
        }
        Row(Modifier.fillMaxWidth().padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            if (pickerSlot == null) {
                Box(Modifier.size(Space.field).background(c.raised, no.heimflyt.launcher.ui.theme.Shapes.m), contentAlignment = Alignment.Center) {
                    QuietButton("#") { trayOpen = !trayOpen }
                }
                Spacer(Modifier.width(Space.s))
            }
            // A resolved tag is shown as a removable token; the field edits only the name part. One source of truth: the query.
            val token = filter.tag
            HField(if (token != null) filter.text else query, { text -> onQuery(if (token != null) "#$token $text" else text) },
                "Find an app", Modifier.weight(1f), token = token?.let { "#${tags.label(it)}" }, onClearToken = { onQuery(filter.text) })
        }
    }
    sheetApp?.let { entry ->
        AppSheet(entry, repo, tags, tagRevision, onChanged = { tagRevision++ }, onOpen = { sheetApp = null; onApp(entry) },
            onInfo = { if (!repo.details(entry)) onError("App info is unavailable."); sheetApp = null }, onDismiss = { sheetApp = null })
    }
}

@Composable
fun AppSheet(entry: AppEntry, repo: AppRepository, tags: TagStore, revision: Int, onChanged: () -> Unit,
    onOpen: () -> Unit, onInfo: () -> Unit, onDismiss: () -> Unit) {
    val t = Heimflyt.t; val c = t.color
    HSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(repo, entry); Spacer(Modifier.width(Space.m)); Text(entry.label, style = t.type.title)
        }
        Spacer(Modifier.height(Space.s))
        HRow("Open", compact = true, onClick = onOpen, leading = { Glyph(R.drawable.glyph_direction, c.ink) })
        HRow("App info", compact = true, onClick = onInfo, leading = { Glyph(R.drawable.glyph_info, c.ink) })
        TagAssignments(entry.key, tags, revision, onChanged)
    }
}

@Composable
fun ActionSheet(destination: SemanticDestination, tags: TagStore, revision: Int, onChanged: () -> Unit,
                onOpen: () -> Unit, onDismiss: () -> Unit) {
    val t = Heimflyt.t
    HSheet(onDismiss) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Glyph(glyphFor(HomeAction.Semantic(destination)), t.color.ink)
            Spacer(Modifier.width(Space.m)); Text(destination.label, style = t.type.title)
        }
        Text(destination.description, style = t.type.caption)
        HRow(if (destination == SemanticDestination.TORCH) "Toggle flashlight" else "Open", onClick = onOpen)
        TagAssignments("semantic:${destination.id}", tags, revision, onChanged)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagAssignments(memberKey: String, tags: TagStore, revision: Int, onChanged: () -> Unit) {
    val t = Heimflyt.t
    var newTag by remember(memberKey) { mutableStateOf("") }
    SectionLabel("tags")
    key(revision) {
        val assigned = tags.tags(memberKey)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.m)) {
            tags.names.forEach { name ->
                val on = name in assigned
                TagWord(if (on) "${tags.label(name)} ✓" else tags.label(name), selected = on, onClick = { tags.set(memberKey, name, !on); onChanged() })
            }
        }
        if (tags.names.isEmpty()) Text("No tags yet.", style = t.type.caption)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        HField(newTag, { newTag = it }, "New tag", Modifier.weight(1f), showGlyph = false)
        QuietButton("Add") {
            val n = tags.normalize(newTag)
            if (n != null && tags.create(newTag)) { tags.set(memberKey, n, true); newTag = ""; onChanged() }
        }
    }
}

@Composable
fun TagsSurface(tags: TagStore, repo: AppRepository, onRenamed: (String, String) -> Unit, onDeleted: (String) -> Unit, onBack: () -> Unit,
                onEditRadial: (String) -> Unit = {}, onMembershipChanged: (String) -> Unit = {}) {
    val t = Heimflyt.t; val c = t.color
    val catalog by repo.catalog.collectAsStateWithLifecycle()
    LaunchedEffect(repo) { repo.refresh() }
    var revision by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<String?>(null) }
    var addingItems by remember { mutableStateOf(false) }
    var appQuery by remember { mutableStateOf("") }
    var renameText by remember { mutableStateOf("") }
    var confirmDelete by remember { mutableStateOf(false) }
    var newTag by remember { mutableStateOf("") }
    val present = remember(catalog.apps) { catalog.apps.map { it.key }.toSet() }
    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        ScreenHeader("Tags", onBack, meta = "tap a tag to add apps, actions or radial choices")
        LazyColumn(Modifier.weight(1f)) {
            items(tags.names.toList(), key = { "$it@$revision" }) { name ->
                val count = tags.assignments.count { (key, set) -> name in set &&
                    (key in present || SemanticDestination.entries.any { key == "semantic:${it.id}" }) }
                HRow("#${tags.label(name)}", onClick = { editing = name; renameText = tags.label(name); confirmDelete = false },
                    trailing = { Text("$count items", style = t.type.meta) })
            }
            if (tags.names.isEmpty()) item { Text("No tags yet. Tags filter Apps and Search with #name.", Modifier.padding(Space.s), style = t.type.caption) }
        }
        Row(Modifier.padding(vertical = Space.s), verticalAlignment = Alignment.CenterVertically) {
            HField(newTag, { newTag = it }, "New tag", Modifier.weight(1f), showGlyph = false)
            QuietButton("Add") { if (tags.create(newTag)) { newTag = ""; revision++ } }
        }
    }
    editing?.let { old ->
        HSheet({ editing = null; addingItems = false }) {
            Text("#${tags.label(old)}", style = t.type.title.copy(fontFamily = t.type.tag.fontFamily))
            Spacer(Modifier.height(Space.s))
            if (addingItems) {
                TagItemPicker(old, catalog, repo, tags, appQuery, { appQuery = it }) { revision++; onMembershipChanged(old) }
                QuietButton("Done") { addingItems = false }
            } else if (!confirmDelete) {
                HRow("Add apps or actions…", subtitle = "Choose items for this tag.", strong = true,
                    onClick = { addingItems = true; appQuery = "" },
                    leading = { Glyph(R.drawable.glyph_apps, c.accentInk) },
                    trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
                HRow("Choose radial items…", subtitle = "Place up to four apps or actions from this tag.", strong = true,
                    onClick = { editing = null; onEditRadial(old) },
                    leading = { Glyph(R.drawable.glyph_apps, c.accentInk) },
                    trailing = { Glyph(R.drawable.glyph_chevron, c.inkMuted, 18.dp) })
                SectionLabel("actions")
                SemanticDestination.entries.forEach { destination ->
                    val key = "semantic:${destination.id}"
                    val selected = old in tags.tags(key)
                    HRow(destination.label, subtitle = destination.description,
                        onClick = { tags.set(key, old, !selected); revision++; onMembershipChanged(old) },
                        leading = { Glyph(glyphFor(HomeAction.Semantic(destination)), c.ink) },
                        trailing = if (selected) ({ Glyph(R.drawable.glyph_check, c.accentInk, 18.dp) }) else null)
                }
                SectionLabel("tag name")
                HField(renameText, { renameText = it }, "Name", showGlyph = false)
                Row { QuietButton("Rename") {
                    if (tags.rename(old, renameText)) { onRenamed(old, tags.normalize(renameText)!!); revision++; editing = null }
                }; QuietButton("Delete…", color = c.dangerInk) { confirmDelete = true } }
            } else {
                val count = tags.assignments.count { (_, set) -> old in set }
                Text("Remove #${tags.label(old)} from $count ${if (count == 1) "item" else "items"}? Directions that open #${tags.label(old)} will open Search instead.", style = t.type.body)
                Row { QuietButton("Cancel") { confirmDelete = false }
                    QuietButton("Delete", color = c.dangerInk) { tags.delete(old); onDeleted(old); revision++; editing = null } }
            }
        }
    }
}

/** The same direct tag membership picker is used from a tag's Browse page and from tag management. */
@Composable
fun TagItemPicker(tag: String, catalog: AppCatalog, repo: AppRepository, tags: TagStore,
                 query: String, onQuery: (String) -> Unit, onChanged: () -> Unit) {
    val c = Heimflyt.t.color
    var revision by remember(tag) { mutableIntStateOf(0) }
    val assignments = remember(tag, tags, revision) { tags.assignments }
    HField(query, onQuery, "Find app or action")
    Text("Tap an item to add or remove it from this tag.", style = Heimflyt.t.type.caption)
    if (!catalog.loaded) Text("Loading apps…", style = Heimflyt.t.type.caption)
    catalog.warning?.let { Text(it, style = Heimflyt.t.type.caption) }
    val matches = remember(catalog.apps, query) { catalog.apps.filter { it.label.contains(query.trim(), ignoreCase = true) }
        .sortedWith(compareBy({ it.label.lowercase() }, { it.key })) }
    val actions = remember(query) { SemanticDestination.entries.filter { it.label.contains(query.trim(), ignoreCase = true) } }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 400.dp)) {
        if (actions.isNotEmpty()) item(key = "actions") { SectionLabel("actions") }
        items(actions, key = { "semantic:${it.id}" }) { destination ->
            val memberKey = "semantic:${destination.id}"
            val selected = tag in assignments[memberKey].orEmpty()
            HRow(destination.label, subtitle = destination.description,
                onClick = { tags.set(memberKey, tag, !selected); revision++; onChanged() },
                leading = { Glyph(glyphFor(HomeAction.Semantic(destination)), c.ink) },
                trailing = if (selected) ({ Glyph(R.drawable.glyph_check, c.accentInk, 18.dp) }) else null)
        }
        if (matches.isNotEmpty()) item(key = "apps") { SectionLabel("apps") }
        items(matches, key = { it.key }) { entry ->
            val selected = tag in assignments[entry.key].orEmpty()
            HRow(entry.label, onClick = { tags.set(entry.key, tag, !selected); revision++; onChanged() },
                leading = { AppIcon(repo, entry) },
                trailing = if (selected) ({ Glyph(R.drawable.glyph_check, c.accentInk, 18.dp) }) else null)
        }
        if (catalog.loaded && matches.isEmpty() && actions.isEmpty()) item(key = "empty") {
            Text("No apps or actions match.", style = Heimflyt.t.type.caption)
        }
    }
}
