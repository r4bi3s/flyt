package no.heimflyt.launcher.ui.tune

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.*
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.ui.browse.BrowseModel
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Space

/** Draft edits never reach settings until Save. App selection uses exact activity/profile identity. */
@Composable
fun GroupEditor(direction: Int, current: HomeAction, tuning: TuningParams, app: HeimflytApplication,
                onSave: (HomeAction.Group, TagRadialMode) -> Unit, onCancel: () -> Unit, tag: String? = null) {
    var draft by remember(direction, current, tag) { mutableStateOf(current as? HomeAction.Group ?: HomeAction.Group(
        if (current is HomeAction.Tag) "#${current.name}" else "Group", List(4) { null }, (current as? HomeAction.Tag)?.name)) }
    var picking by remember { mutableStateOf<Int?>(null) }
    var query by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var mode by remember(tag) { mutableStateOf(tag?.let { app.settings.settings.value.tagMode(it) } ?: TagRadialMode.FIXED) }
    var importing by remember { mutableStateOf(false) }
    var repo by remember { mutableStateOf<AppRepository?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { repo = withContext(Dispatchers.IO) { app.apps }.also { it.refresh() } }
    BackHandler { if (picking != null) picking = null else onCancel() }
    val t = Heimflyt.t
    val source = repo
    val catalog = if (source == null) AppCatalog() else source.catalog.collectAsStateWithLifecycle().value
    val positions = if (tuning.leftHanded) listOf("Outer right", "Inner right", "Inner left", "Outer left")
        else listOf("Outer left", "Inner left", "Inner right", "Outer right")

    Column(Modifier.fillMaxSize().padding(horizontal = Space.gutter)) {
        val cell = picking
        ScreenHeader(if (cell != null) "Choose · ${positions[cell]}" else if (tag != null) "Radial · ${app.tags.label(tag)}" else "Group · direction ${direction + 1}",
            onBack = { if (cell != null) picking = null else onCancel() })
        if (cell != null) {
            Text("Positions are shown with this direction pointing up.", style = t.type.caption)
            HField(query, { query = it }, "Find app or action")
            if (!catalog.loaded) Text("Loading apps…", style = t.type.caption)
            catalog.warning?.let { Text(it, style = t.type.caption) }
            error?.let { Text(it, style = t.type.caption) }
            val matches = catalog.apps.filter { it.label.contains(query.trim(), ignoreCase = true) &&
                (tag == null || tag in app.tags.tags(it.key)) }
            if (catalog.loaded && matches.isEmpty() && SemanticDestination.entries.none {
                    (tag == null || tag in app.tags.tags("semantic:${it.id}")) && it.label.contains(query.trim(), true)
                })
                Text("No matching items${if (tag != null) " in this tag" else ""}.", style = t.type.caption)
            LazyColumn(Modifier.weight(1f)) {
                val actions = SemanticDestination.entries.filter { destination ->
                    (tag == null || tag in app.tags.tags("semantic:${destination.id}")) && destination.label.contains(query.trim(), ignoreCase = true)
                }
                if (actions.isNotEmpty()) item { SectionLabel(if (tag == null) "actions" else "actions in this tag") }
                items(actions, key = { "semantic:${it.id}" }) { destination ->
                    HRow(destination.label, subtitle = destination.description, onClick = {
                        val next = RadialGroups.replace(draft, cell, HomeAction.Semantic(destination))
                        if (next == null) error = "This action is already in another position. Remove it there first."
                        else { draft = next; picking = null; error = null }
                    })
                }
                items(matches, key = { it.key }) { entry ->
                    HRow(entry.label, subtitle = "${entry.info.componentName.flattenToShortString()} · profile ${entry.serial}", onClick = {
                        val child = HomeAction.App(entry.info.componentName.flattenToString(), entry.serial, entry.label)
                        val next = RadialGroups.replace(draft, cell, child)
                        if (next == null) error = "This app is already in another position. Remove it there first."
                        else { draft = next; picking = null; error = null }
                    })
                }
            }
        } else {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                if (tag != null) {
                    SectionLabel("radial mode")
                    Segmented(TagRadialMode.entries.map { it.label to it }, mode, { mode = it; error = null })
                    if (mode != TagRadialMode.FIXED) {
                        Text(if (mode == TagRadialMode.SMALL)
                            "Tags with up to four items show them all automatically, most used from left to right. Larger tags open the full list; choose Most used to show their top four."
                            else "The four most used apps or actions in this tag fill the radial from left to right.", style = t.type.caption)
                        Text("Counts come from successful opens in Flyt. Ties keep their places. Fixed choices are saved for when you switch back.", style = t.type.caption)
                        val saved = app.settings.settings.collectAsStateWithLifecycle().value
                        val members = catalog.apps.filter { tag in app.tags.tags(it.key) }.map {
                            HomeAction.App(it.info.componentName.flattenToString(), it.serial, it.label)
                        } + SemanticDestination.entries.filter { tag in app.tags.tags("semantic:${it.id}") }.map(HomeAction::Semantic)
                        val shown = MostUsed.prepare(tag, app.tags.label(tag), members,
                            saved.copy(tagGroups = saved.tagGroups + (tag to draft), tagModes = saved.tagModes + (tag to mode)),
                            app.launchCounts.counts, catalog.authoritative)
                        (if (tuning.leftHanded) shown?.children?.reversed() else shown?.children)?.filterNotNull()?.forEach { child ->
                            val count = child.tagMemberKey()?.let { app.launchCounts.counts[it] } ?: 0
                            HRow(child.label(), subtitle = "$count opens")
                        }
                        if (shown == null) Text("Waiting for a complete app list.", style = t.type.caption)
                    }
                }
                if (mode == TagRadialMode.FIXED) {
                if (tag == null) HField(draft.name, { draft = draft.copy(name = it.take(60)) }, "Group name", showGlyph = false)
                Text(if (tag == null) "Choose 2–4 apps or actions. Positions stay fixed; removing one leaves its position empty."
                    else "Choose up to four apps or actions from this tag. The rest stay in Browse and Search. Empty positions stay empty.", style = t.type.caption)
                Text("Left and right below are relative to this direction pointing up.", style = t.type.caption)
                for (slot in 0..3) {
                    val child = draft.children[slot]
                    HRow("${positions[slot]} · ${child?.label() ?: "Empty"}",
                        subtitle = (child as? HomeAction.App)?.let { "${it.component.substringBefore('/')} · profile ${it.userSerial}" },
                        onClick = { picking = slot; query = ""; error = null },
                        trailing = child?.let { { QuietButton("Remove") { draft = RadialGroups.replace(draft, slot, null)!! } } })
                }
                val fallback = draft.fallbackTag
                if (tag != null) {
                    Text("These choices are shared by every radial direction bound to this tag. Release outside a child opens the full tag.", style = t.type.caption)
                    Text("Removing an item from the tag clears its radial position. A temporarily unavailable profile keeps its place.", style = t.type.caption)
                } else if (fallback != null) {
                    Text("Release outside a child opens #$fallback. Tag membership does not change these children.", style = t.type.caption)
                    QuietButton("Use group list instead") { draft = draft.copy(fallbackTag = null) }
                    // Import is offered only for an empty draft: never silently replace an edited child's identity or position.
                    if (draft.children.all { it == null } && !importing) {
                        QuietButton("Use current tag apps") {
                            if (!catalog.authoritative) {
                                source?.refresh()
                                error = "Wait for a complete app list; unlock or resume profiles, then retry."
                            } else {
                                importing = true
                                val beforeImport = draft
                                scope.launch {
                                    try {
                                        val imported = withContext(Dispatchers.IO) { importTagApps(app, catalog, fallback) }
                                        when {
                                            imported.count { it != null } < 2 -> error = "Choose apps individually: this tag needs 2–4 unambiguous apps."
                                            draft != beforeImport -> error = "The draft changed; tag import was skipped."
                                            else -> { draft = draft.copy(children = imported); error = null }
                                        }
                                    } finally { importing = false }
                                }
                            }
                        }
                    }
                } else Text("Release outside a child opens a list of this group's items.", style = t.type.caption)
                }
                if (importing) Text("Preparing tag apps…", style = t.type.caption)
                error?.let { Text(it, style = t.type.caption) }
                if (tag == null && !RadialGroups.valid(draft)) Text("A group needs a name and at least two different items.", style = t.type.caption)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.m)) {
                QuietButton("Cancel", onClick = onCancel)
                if ((tag != null || RadialGroups.valid(draft)) && !importing) PrimaryButton(if (tag == null) "Save group" else "Save choices") {
                    when {
                        tag != null && tag !in app.tags.names -> error = "This tag no longer exists."
                        tag != null && mode == TagRadialMode.FIXED && draft.children.filterNotNull().any { it.tagMemberKey()?.let { key -> tag !in app.tags.tags(key) } != false } ->
                            error = "An item was removed from this tag. Clear its position before saving."
                        else -> onSave(draft.copy(name = if (tag == null) draft.name.trim() else app.tags.label(tag)), mode)
                    }
                }
            }
        }
    }
}

/** Explicit draft import only. Never invoked by Home, never writes tag/Browse/settings state. */
private fun importTagApps(app: HeimflytApplication, catalog: AppCatalog, tag: String): List<HomeAction.App?> {
    val members = catalog.apps.filter { tag in app.tags.tags(it.key) }
    val entries = if (tag == H7.PARENT_TAG) H7.resolve(members) { it.label }
    else if (members.size in 2..4) {
        val keys = members.sortedWith(compareBy({ it.label.lowercase() }, { it.key })).map { it.key }
        val cells = BrowseModel.stableCells(app.browse.config.value.tagCells[tag].orEmpty(), keys)
        val byKey = members.associateBy { it.key }
        H7.fanSlots(cells).mapNotNull { (slot, key) -> byKey[key]?.let { slot to it } }.toMap()
    } else emptyMap()
    return List(4) { slot -> entries[slot]?.let { HomeAction.App(it.info.componentName.flattenToString(), it.serial, it.label) } }
}
