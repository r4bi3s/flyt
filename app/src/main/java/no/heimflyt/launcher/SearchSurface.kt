package no.heimflyt.launcher

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.ui.browse.AllTagsSheet
import no.heimflyt.launcher.ui.browse.TagCloud
import no.heimflyt.launcher.ui.apps.AppSheet
import no.heimflyt.launcher.ui.apps.ActionSheet
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Space

/** OPEN_QUESTIONS.md Q3, resolved by the owner as a choice: Tune → Search → Search field (Bottom default, or Top). */
const val SEARCH_BOTTOM_ANCHORED_DEFAULT = true

/** Profile-qualified package key (guardrail 7): a Work and a Personal app with the same package never share a label. */
fun packageProfileKey(packageName: String, serial: Long) = "$packageName@$serial"

@Composable
fun AppIcon(repo: AppRepository, entry: AppEntry?, size: androidx.compose.ui.unit.Dp = 40.dp) {
    val bitmap by produceState<Bitmap?>(null, entry?.key) { value = entry?.let { repo.icon(it) } }
    bitmap?.let { Image(it.asImageBitmap(), null, Modifier.size(size)) }
        ?: Monogram(entry?.label ?: "?", size)
}

@Composable
fun SearchSurface(app: HeimflytApplication, query: String, onQuery: (String) -> Unit, onApp: (AppEntry) -> Unit,
    onAction: (HomeAction) -> Unit, onBack: () -> Unit, onTune: () -> Unit, onError: (String) -> Unit,
    bottomAnchored: Boolean = SEARCH_BOTTOM_ANCHORED_DEFAULT) {
    val t = Heimflyt.t; val c = t.color
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus(); withFrameNanos { }; OpenTiming.log("search", "first frame (field focused)", last = true) }
    val contactSearch = remember { ContactSearch(context) }
    val sources = remember { app.searchSources.read() }
    val catalog by app.apps.catalog.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(app.apps, lifecycle) {
        app.apps.refresh()
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) app.apps.refresh() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    var permissionEpoch by remember { mutableIntStateOf(0) }
    var tagRevision by remember { mutableIntStateOf(0) }
    var sheetApp by remember { mutableStateOf<AppEntry?>(null) }
    var sheetAction by remember { mutableStateOf<SemanticDestination?>(null) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { permissionEpoch++ }
    val parsed = remember(query) { ParsedSearch.from(query) }
    val hasQuery = query.isNotBlank()
    val searchActions = parsed.tag == null && parsed.text.isNotBlank()
    var contacts by remember { mutableStateOf<List<ContactEntry>>(emptyList()) }
    var shortcuts by remember { mutableStateOf<List<ShortcutEntry>>(emptyList()) }
    var shortcutAccess by remember { mutableStateOf<Boolean?>(null) }
    var selectedContact by remember { mutableStateOf<ContactGroup?>(null) }
    var contactOptions by remember { mutableStateOf(ContactOptions()) }
    var optionsLoading by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }
    var allTags by remember { mutableStateOf(false) }
    LaunchedEffect(sources.actions, searchActions) {
        if (sources.actions && searchActions) {
            shortcutAccess = withContext(Dispatchers.IO) { runCatching { app.apps.hasShortcutAccess() }.getOrDefault(false) }
            if (shortcutAccess == true) shortcuts = withContext(Dispatchers.IO) { runCatching { app.apps.shortcuts() }.getOrDefault(emptyList()) }
        }
    }
    LaunchedEffect(query, sources.contacts, permissionEpoch) {
        contacts = emptyList()
        if (sources.contacts && parsed.tag == null && parsed.text.isNotBlank() && contactSearch.permitted())
            contacts = contactSearch.find(parsed.text)
    }
    LaunchedEffect(selectedContact) {
        contactOptions = ContactOptions()
        optionsLoading = selectedContact != null
        selectedContact?.let { contactOptions = contactSearch.details(it.entries) }
        optionsLoading = false
    }
    val tagCandidates = if (parsed.tag != null) app.tags.names.filter { it.startsWith(parsed.tag) } else emptyList()
    val tag = parsed.tag?.let { resolveTag(it, app.tags.names) }
    val appsByPackage = remember(catalog.apps) { catalog.apps.associateBy { packageProfileKey(it.info.componentName.packageName, it.serial) } }
    val apps = remember(catalog.apps, query, tagRevision) {
        if (parsed.tag != null && tag == null) emptyList() else catalog.apps.asSequence()
            .filter { parsed.tag == null || tag in app.tags.tags(it.key) }
            .map { it to matchScore(it.label, parsed.text) }
            .filter { it.second < Int.MAX_VALUE }
            .sortedWith(compareBy<Pair<AppEntry, Int>>({ it.second }, { it.first.label.lowercase() }))
            .map { it.first }.take(if (parsed.tag != null) Int.MAX_VALUE else if (parsed.text.isBlank()) 0 else 8).toList()
    }
    val semantic = remember(query, tag, tagRevision) { if (parsed.tag == null && !searchActions || parsed.tag != null && tag == null) emptyList() else SemanticDestination.entries
        .filter { parsed.tag == null || tag in app.tags.tags("semantic:${it.id}") }
        .map { it to matchScore(it.label, parsed.text) }.filter { it.second < Int.MAX_VALUE }
        .sortedWith(compareBy({ it.second }, { it.first.label })).map { it.first } }
    val foundShortcuts = remember(shortcuts, query) { if (!searchActions) emptyList() else shortcuts
        .map { it to matchScore(it.label, parsed.text) }.filter { it.second < Int.MAX_VALUE }
        .sortedWith(compareBy({ it.second }, { it.first.label.lowercase() })).map { it.first }.take(8) }
    val groupedContacts = remember(contacts) { groupContacts(contacts).take(8) }
    val recents = app.recents.entries
    val availableRecents = recents.filter { target -> when (target.kind) {
        "app" -> sources.apps && catalog.apps.any { it.key == "${target.key}@${target.serial}" }
        "contact" -> sources.contacts && contactSearch.permitted()
        "semantic" -> sources.actions && SemanticDestination.entries.any { it.id == target.key }
        "shortcut" -> sources.actions
        else -> false
    } }.take(app.recents.count)
    fun error(message: String) { notice = message; onError(message) }
    fun start(intent: Intent): Boolean = try { context.startActivity(intent); true }
        catch (_: ActivityNotFoundException) { error("No app can open this contact action."); false }
        catch (_: SecurityException) { error("Contact action is unavailable."); false }
    fun recordContact(entry: ContactEntry) { app.recents.record(RecentTarget("contact", entry.lookup, entry.name, serial = entry.id)) }
    fun representative(group: ContactGroup) = group.entries.firstOrNull { it.detail != null } ?: group.entries.first()
    fun viewContact(entry: ContactEntry) {
        if (start(Intent(Intent.ACTION_VIEW, entry.uri))) recordContact(entry)
        selectedContact = null
    }
    fun launchShortcut(s: ShortcutEntry, target: RecentTarget = RecentTarget("shortcut", s.packageName, s.label, s.id, s.userSerial)) {
        if (app.apps.launchShortcut(s)) app.recents.record(target) else error("Shortcut unavailable. Try searching again.")
    }
    fun openRecent(target: RecentTarget) = when (target.kind) {
        "app" -> onAction(HomeAction.App(target.key, target.serial, target.label))
        "contact" -> viewContact(ContactEntry(target.serial, target.key, target.label))
        "semantic" -> SemanticDestination.entries.find { it.id == target.key }?.let { onAction(HomeAction.Semantic(it)) } ?: Unit
        "shortcut" -> launchShortcut(ShortcutEntry(target.key, target.extra, target.label, target.serial), target)
        else -> Unit
    }

    @Composable fun ContactRow(group: ContactGroup) {
        HRow(group.name, singleLine = true, subtitle = if (group.entries.size > 1) "${group.entries.size} records" else null,
            onClick = { if (group.entries.size == 1) viewContact(group.entries.first()) else selectedContact = group },
            onLongClick = { selectedContact = group }, onLongClickLabel = "Contact actions",
            leading = { Monogram(group.name) },
            trailing = { IconButtonGlyph(R.drawable.glyph_more, "Actions for ${group.name}") { selectedContact = group } })
    }

    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter - Space.xs)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            IconButtonGlyph(R.drawable.glyph_tune, "Tune", onClick = onTune)
            IconButtonGlyph(R.drawable.glyph_close, "Close Search", onClick = onBack)
        }
        @Composable fun Controls() {
        notice?.let { StatusPill(it, { notice = null }, Modifier.padding(vertical = Space.xs)) }
        if (parsed.tag != null && parsed.tag != tag) {
            if (tagCandidates.isEmpty()) Text("No tag named #${parsed.tag}", Modifier.padding(Space.s), style = t.type.caption)
            else TagCloud(tagCandidates, { name -> onQuery("#$name${if (parsed.text.isBlank()) "" else " ${parsed.text}"}") },
                { allTags = true }, selected = tag, total = app.tags.names.size, label = app.tags::label)
        } else if (tag != null) Row(verticalAlignment = Alignment.CenterVertically) {
            TagWord(app.tags.label(tag), selected = true); Spacer(Modifier.width(Space.s)); Text("showing", style = t.type.meta)
        }
        HField(query, onQuery, "App, person, action or #tag", Modifier.padding(vertical = Space.s), focusRequester = focus,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search))
        }
        if (!bottomAnchored) Controls()
        val listState = rememberLazyListState()
        // Bottom: reverse layout, so the first emitted item sits next to the field; each section's label is emitted after its
        // rows (it then appears above them). Top: an ordinary list with labels before rows. Order and ranking are identical.
        val labelsFirst = !bottomAnchored
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = listState, reverseLayout = bottomAnchored) {
            if (!hasQuery) {
                if (availableRecents.isNotEmpty()) {
                if (labelsFirst) item(key = "recent-label") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionLabel("recent") }
                            QuietButton("Clear") { app.recents.clear() }
                        }
                    }
                    items(availableRecents, key = { "recent:${it.identity}" }) { target ->
                        val entry = if (target.kind == "app") catalog.apps.firstOrNull { it.key == "${target.key}@${target.serial}" } else null
                        val action = if (target.kind == "semantic") SemanticDestination.entries.find { it.id == target.key } else null
                        HRow(target.label, compact = true, singleLine = true, onClick = { openRecent(target) },
                            onLongClick = entry?.let { { keyboard?.hide(); sheetApp = it } }
                                ?: action?.let { { keyboard?.hide(); sheetAction = it } },
                            onLongClickLabel = if (entry != null) "App options" else if (action != null) "Action options" else null, leading = {
                            when (target.kind) {
                                "app" -> AppIcon(app.apps, entry, 32.dp)
                                "contact" -> Monogram(target.label, 32.dp)
                                "semantic" -> Glyph(SemanticDestination.entries.find { it.id == target.key }?.let { glyphFor(HomeAction.Semantic(it)) } ?: R.drawable.glyph_direction, c.ink)
                                else -> AppIcon(app.apps, appsByPackage[packageProfileKey(target.key, target.serial)], 32.dp)
                            }
                        })
                    }
                    if (!labelsFirst) item(key = "recent-label") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { SectionLabel("recent") }
                            QuietButton("Clear") { app.recents.clear() }
                        }
                    }
                }
                if (app.tags.names.isNotEmpty() && sources.apps) item(key = "tags") {
                    TagCloud(app.tags.names, { name -> onQuery("#$name") }, { allTags = true }, label = app.tags::label)
                }
                if (availableRecents.isEmpty() && app.tags.names.isEmpty()) item(key = "hint") {
                    Text("Type a name. Start with # for a tag.", Modifier.padding(Space.s), style = t.type.caption)
                }
            }
            if (sources.apps && apps.isNotEmpty()) {
                if (labelsFirst) item(key = "label:apps") { SectionLabel("apps") }
                items(apps, key = { "app:${it.key}" }) { entry ->
                    HRow(entry.label, onClick = { onApp(entry) }, onLongClick = { keyboard?.hide(); sheetApp = entry },
                        onLongClickLabel = "App options", leading = { AppIcon(app.apps, entry) })
                }
                if (!labelsFirst) item(key = "label:apps") { SectionLabel("apps") }
            }
            if (hasQuery && sources.contacts && parsed.tag == null && !contactSearch.permitted()) {
                if (labelsFirst) item(key = "label:people") { SectionLabel("people") }
                item(key = "contacts-permission") {
                    HRow("Show people from your contacts", subtitle = "Android will ask for contacts access",
                        onClick = { permission.launch(Manifest.permission.READ_CONTACTS) }, leading = { Glyph(R.drawable.glyph_person, c.ink) })
                }
                if (!labelsFirst) item(key = "label:people") { SectionLabel("people") }
            } else if (sources.contacts && groupedContacts.isNotEmpty()) {
                if (labelsFirst) item(key = "label:people") { SectionLabel("people") }
                items(groupedContacts, key = { "contact:${it.name.lowercase()}" }) { group -> ContactRow(group) }
                if (!labelsFirst) item(key = "label:people") { SectionLabel("people") }
            }
            if (sources.actions && (semantic.isNotEmpty() || foundShortcuts.isNotEmpty())) {
                if (labelsFirst) item(key = "label:actions") { SectionLabel("actions") }
                items(semantic, key = { "semantic:${it.id}" }) { action ->
                    HRow(action.label, onClick = { onAction(HomeAction.Semantic(action)) },
                        onLongClick = { keyboard?.hide(); sheetAction = action }, onLongClickLabel = "Action options",
                        leading = { Glyph(glyphFor(HomeAction.Semantic(action)), c.ink) })
                }
                items(foundShortcuts, key = { "shortcut:${it.packageName}:${it.id}:${it.userSerial}" }) { shortcut ->
                    val owner = appsByPackage[packageProfileKey(shortcut.packageName, shortcut.userSerial)]
                    HRow(shortcut.label, singleLine = true, subtitle = owner?.label ?: "Shortcut", onClick = { launchShortcut(shortcut) },
                        leading = { if (owner != null) AppIcon(app.apps, owner) else Glyph(R.drawable.glyph_shortcut, c.ink) })
                }
                if (!labelsFirst) item(key = "label:actions") { SectionLabel("actions") }
            }
            if (parsed.tag != null && tag != null && sources.apps && apps.isEmpty() && semantic.isEmpty()) item(key = "no-tagged") {
                Text("No tagged items match.", Modifier.padding(Space.s), style = t.type.caption)
            }
            if (hasQuery && parsed.tag == null && !catalog.loaded && sources.apps) item(key = "loading") {
                Text("Loading apps…", Modifier.padding(Space.s), style = t.type.caption)
            }
        }
        // Keep the viewport anchored at the field when async results arrive above.
        LaunchedEffect(query) { if (listState.firstVisibleItemIndex > 0) listState.scrollToItem(0) }
        if (bottomAnchored) Controls()
    }
    if (allTags) {
        val present = catalog.apps.mapTo(HashSet()) { it.key }.apply {
            addAll(SemanticDestination.entries.map { "semantic:${it.id}" })
        }
        val counts = app.tags.names.associateWith { name -> app.tags.assignments.count { (key, set) -> name in set && key in present } }
        AllTagsSheet(app.tags.names, counts, onPick = { allTags = false; onQuery("#$it ") }, onEditTags = null, onDismiss = { allTags = false },
            label = app.tags::label)
    }
    sheetApp?.let { entry ->
        AppSheet(entry, app.apps, app.tags, tagRevision,
            onChanged = { tagRevision++; app.refreshMostUsed() },
            onOpen = { sheetApp = null; onApp(entry) },
            onInfo = { if (!app.apps.details(entry)) onError("App info is unavailable."); sheetApp = null },
            onDismiss = { sheetApp = null })
    }
    sheetAction?.let { destination ->
        ActionSheet(destination, app.tags, tagRevision,
            onChanged = { tagRevision++; app.refreshMostUsed() },
            onOpen = { sheetAction = null; onAction(HomeAction.Semantic(destination)) },
            onDismiss = { sheetAction = null })
    }
    selectedContact?.let { group ->
        HSheet(onDismiss = { selectedContact = null }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Monogram(group.name); Spacer(Modifier.width(Space.m))
                Column { Text(group.name, style = t.type.title); Text("${group.entries.size} ${if (group.entries.size == 1) "record" else "records"}", style = t.type.meta) }
            }
            Spacer(Modifier.height(Space.s))
            group.entries.forEachIndexed { index, entry ->
                HRow(if (group.entries.size == 1) "View contact" else "View contact ${index + 1}", subtitle = entry.detail,
                    onClick = { viewContact(entry) }, leading = { Glyph(R.drawable.glyph_person, c.ink) })
            }
            if (optionsLoading) Text("Loading actions…", Modifier.padding(Space.s), style = t.type.caption)
            if (contactOptions.numbers.isNotEmpty() || contactOptions.emails.isNotEmpty()) HorizontalDivider(Modifier.padding(vertical = Space.s), color = c.hairline)
            contactOptions.numbers.forEach { number ->
                HRow("Call…", compact = true, subtitle = number, onClick = {
                    if (start(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null)))) recordContact(representative(group)); selectedContact = null
                }, leading = { Glyph(R.drawable.glyph_phone, c.ink) })
                HRow("Message…", compact = true, subtitle = number, onClick = {
                    if (start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null)))) recordContact(representative(group)); selectedContact = null
                }, leading = { Glyph(R.drawable.glyph_message, c.ink) })
            }
            contactOptions.emails.forEach { email ->
                HRow("Email…", compact = true, subtitle = email, onClick = {
                    if (start(Intent(Intent.ACTION_SENDTO, Uri.fromParts("mailto", email, null)))) recordContact(representative(group)); selectedContact = null
                }, leading = { Glyph(R.drawable.glyph_mail, c.ink) })
            }
        }
    }
}
