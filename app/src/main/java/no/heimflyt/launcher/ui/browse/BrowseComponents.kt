package no.heimflyt.launcher.ui.browse

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import no.heimflyt.launcher.AppEntry
import no.heimflyt.launcher.AppIcon
import no.heimflyt.launcher.AppRepository
import no.heimflyt.launcher.R
import no.heimflyt.launcher.HomeAction
import no.heimflyt.launcher.SemanticDestination
import no.heimflyt.launcher.ui.components.*
import no.heimflyt.launcher.ui.theme.Heimflyt
import no.heimflyt.launcher.ui.theme.Shapes
import no.heimflyt.launcher.ui.theme.Space

/**
 * Shared H6.0 Browse components. Every renderer (and Search's tag chips) composes these; none has its own copy.
 */

/**
 * Tags as wrapping chips, capped at [maxRows] rows; what does not fit is behind "All tags (N)" ([onAll]). Replaces the
 * horizontally scrolling tag rows. Order is the caller's (A–Z); chips never reorder to pack better.
 */
@Composable
fun TagCloud(names: List<String>, onTag: (String) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier,
    maxRows: Int = 2, selected: String? = null, total: Int = names.size, label: (String) -> String = { it }) {
    if (names.isEmpty()) return
    Layout(content = {
        names.forEach { name -> TagWord(label(name), selected = name == selected, onClick = { onTag(name) }) }
        QuietButton("All tags ($total)", color = Heimflyt.t.color.ink, onClick = onAll)
    }, modifier = modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val chips = placeables.dropLast(1); val more = placeables.last()
        val gap = Space.m.roundToPx()
        val shown = BrowseModel.capWrap(chips.map { it.width }, more.width, constraints.maxWidth, gap, maxRows)
        val placed = chips.take(shown) + if (shown < chips.size) listOf(more) else emptyList()
        val rows = BrowseModel.wrapRows(placed.map { it.width }, constraints.maxWidth, gap)
        val rowHeights = IntArray((rows.maxOrNull() ?: -1) + 1)
        placed.forEachIndexed { i, p -> rowHeights[rows[i]] = maxOf(rowHeights[rows[i]], p.height) }
        layout(constraints.maxWidth, rowHeights.sum()) {
            var x = 0; var row = -1; var y = 0
            placed.forEachIndexed { i, p ->
                if (rows[i] != row) { if (row >= 0) y += rowHeights[row]; row = rows[i]; x = 0 }
                p.place(x, y + (rowHeights[row] - p.height) / 2); x += p.width + gap
            }
        }
    }
}

/** A searchable, alphabetical tag list (All tags, and the slot picker). */
@Composable
fun TagPicker(names: List<String>, counts: Map<String, Int>, onPick: (String) -> Unit, current: String? = null, label: (String) -> String = { it }) {
    val t = Heimflyt.t
    var filter by remember { mutableStateOf("") }
    val shown = remember(names, filter) {
        val f = filter.trim().removePrefix("#")
        names.sorted().filter { it.contains(f, ignoreCase = true) || label(it).contains(f, ignoreCase = true) }
    }
    if (names.size > 6) HField(filter, { filter = it }, "Find a tag", Modifier.padding(vertical = Space.xs))
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp)) {
        items(shown, key = { it }) { name ->
            val n = counts[name] ?: 0
            HRow("#${label(name)}", compact = true, strong = name == current, onClick = { onPick(name) },
                trailing = { Text("$n ${if (n == 1) "item" else "items"}", style = t.type.meta) })
        }
        if (shown.isEmpty()) item { Text(if (names.isEmpty()) "No tags yet." else "No tag matches.", Modifier.padding(Space.s), style = t.type.caption) }
    }
}

@Composable
fun AllTagsSheet(names: List<String>, counts: Map<String, Int>, onPick: (String) -> Unit, onEditTags: (() -> Unit)?, onDismiss: () -> Unit,
    label: (String) -> String = { it }) {
    val t = Heimflyt.t
    HSheet(onDismiss) {
        Text("All tags · ${names.size}", style = t.type.title)
        TagPicker(names, counts, onPick, label = label)
        if (onEditTags != null) QuietButton("Edit tags…", color = t.color.inkMuted, onClick = onEditTags)
    }
}

/**
 * The additive, thumb-anchored two-column grid (slots, and small tags). Cell positions come only from
 * [BrowseModel.cells]: row 0 is the bottom row, and adding a cell never moves an existing one. Cells keep their size.
 */
@Composable
fun ThumbGrid(count: Int, leftHanded: Boolean, modifier: Modifier = Modifier, cell: @Composable (index: Int, modifier: Modifier) -> Unit) {
    val cells = BrowseModel.cells(count, leftHanded)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        for (row in BrowseModel.rows(count) - 1 downTo 0) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                for (column in 0..1) {
                    val c = cells.firstOrNull { it.row == row && it.column == column }
                    if (c != null) cell(c.slot, Modifier.weight(1f).fillMaxHeight()) else Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/** What a slot cell shows: a tag, a hole left by a deleted tag, or the "add" cell after the last slot. */
sealed interface SlotContent {
    data class Tag(val name: String, val label: String, val previews: List<BrowseTarget>) : SlotContent
    data object Hole : SlotContent
    data object Add : SlotContent
}

sealed interface BrowseTarget {
    val key: String
    val label: String
    data class App(val entry: AppEntry) : BrowseTarget { override val key get() = entry.key; override val label get() = entry.label }
    data class Action(val destination: SemanticDestination) : BrowseTarget {
        override val key get() = "semantic:${destination.id}"
        override val label get() = destination.label
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SlotTile(index: Int, content: SlotContent, repo: AppRepository, onClick: () -> Unit, onLongClick: (() -> Unit)?, modifier: Modifier) {
    val t = Heimflyt.t; val c = t.color
    val description = when (content) {
        is SlotContent.Tag -> "Slot ${index + 1}, ${content.label}, ${content.previews.size} items"
        SlotContent.Hole -> "Slot ${index + 1}, empty. Choose a tag"
        SlotContent.Add -> "Add a tag slot"
    }
    val surface = when (content) {
        is SlotContent.Tag -> Modifier.background(c.raised)
        else -> Modifier.border(1.dp, c.hairline, Shapes.m)
    }
    Column(modifier.heightIn(min = 76.dp).clip(Shapes.m).then(surface)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick, onLongClickLabel = "Edit slot", role = Role.Button)
        .semantics(mergeDescendants = true) { contentDescription = description }
        .padding(horizontal = Space.m, vertical = Space.s), verticalArrangement = Arrangement.Center) {
        when (content) {
            is SlotContent.Tag -> {
                // H6.1: a slot is a place, so its name has no #; ordinary tags keep # (Search syntax).
                Text(content.label, style = t.type.bodyStrong.copy(fontFamily = t.type.tag.fontFamily), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    content.previews.take(4).forEach { preview -> when (preview) {
                        is BrowseTarget.App -> AppIcon(repo, preview.entry, 22.dp)
                        is BrowseTarget.Action -> Glyph(glyphFor(HomeAction.Semantic(preview.destination)), c.ink, 22.dp)
                    } }
                    if (content.previews.isEmpty()) Text("empty", style = t.type.meta)
                    else if (content.previews.size > 4) Text("+${content.previews.size - 4}", style = t.type.meta)
                }
            }
            SlotContent.Hole -> Text("Empty slot", style = t.type.secondary)
            SlotContent.Add -> Text("+ Tag slot", style = t.type.secondary)
        }
    }
}

/** A launch cell for small tags (≤ 4 apps): icon over label, in the same thumb grid as slots. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppTile(entry: AppEntry, repo: AppRepository, onOpen: () -> Unit, onOptions: () -> Unit, modifier: Modifier) =
    DestinationTile(entry.label, { AppIcon(repo, entry, 48.dp) }, modifier,
        Modifier.combinedClickable(onClick = onOpen, onLongClick = onOptions, onLongClickLabel = "App options", role = Role.Button))

@Composable
fun ActionTile(destination: SemanticDestination, onOpen: () -> Unit, modifier: Modifier) =
    DestinationTile(destination.label, { Glyph(glyphFor(HomeAction.Semantic(destination)), Heimflyt.t.color.ink, 48.dp) }, modifier,
        Modifier.clickable(role = Role.Button, onClick = onOpen))

/**
 * The small-tag cell frame, shared by destinations and holes so both have identical geometry at any font scale: a 48 dp
 * icon over a label that always reserves two lines. (A hole must never be shorter than a destination, or its row would
 * collapse and move the destinations above it.)
 */
@Composable
fun DestinationTile(label: String?, icon: @Composable () -> Unit, modifier: Modifier, click: Modifier = Modifier) {
    val t = Heimflyt.t
    Column(modifier.heightIn(min = 104.dp).clip(Shapes.m).then(click).padding(Space.s),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { if (label != null) icon() }
        Spacer(Modifier.height(Space.xs))
        Text(label.orEmpty(), style = t.type.body, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
    }
}

/** A hole in a small tag's grid: the destination frame with nothing in it. */
@Composable
fun HoleTile(modifier: Modifier) = DestinationTile(null, {}, modifier.clearAndSetSemantics { })

/**
 * A small tag's stable grid: [cells] in [BrowseModel.cells] positions, holes kept as empty frames of the same geometry.
 */
@Composable
fun <T> StableTagGrid(cells: List<T?>, leftHanded: Boolean, modifier: Modifier = Modifier, tile: @Composable (T, Modifier) -> Unit) =
    ThumbGrid(cells.size, leftHanded, modifier) { i, m -> cells[i]?.let { tile(it, m) } ?: HoleTile(m) }

/** New · Untagged · All apps: secondary recovery routes, shown only when they have something. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecoveryRow(newCount: Int, untaggedCount: Int, allCount: Int?, onNew: () -> Unit, onUntagged: () -> Unit, onAll: () -> Unit,
    modifier: Modifier = Modifier) {
    val c = Heimflyt.t.color
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
        if (newCount > 0) QuietButton("New · $newCount", color = c.ink, onClick = onNew)
        if (untaggedCount > 0) QuietButton("Untagged · $untaggedCount", color = c.ink, onClick = onUntagged)
        if (allCount != null) QuietButton("All apps · $allCount", color = c.ink, onClick = onAll)
    }
}

/** The bottom entry to the existing Search (same ranking, `#tag` included). Looks like a field; opens Search. */
@Composable
fun SearchEntry(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = Heimflyt.t; val c = t.color
    Row(modifier.fillMaxWidth().heightIn(min = Space.field).clip(Shapes.m).background(c.raised).border(1.dp, c.control, Shapes.m)
        .clickable(role = Role.Button, onClickLabel = "Open Search", onClick = onClick).padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically) {
        Glyph(R.drawable.glyph_search, c.inkMuted, 20.dp); Spacer(Modifier.width(Space.s))
        Text(label, style = t.type.body.copy(color = c.inkMuted), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * Every destination once, A–Z, with sticky letters and a scrubber on the thumb side (left for a left hand). Long-press
 * opens the app sheet (tags, info).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CatalogueList(items: List<BrowseItem>, entries: Map<String, AppEntry>, repo: AppRepository, leftHanded: Boolean,
    onApp: (AppEntry) -> Unit, onOptions: (AppEntry) -> Unit, modifier: Modifier = Modifier, state: LazyListState = rememberLazyListState()) {
    val t = Heimflyt.t; val c = t.color
    val sorted = remember(items) { BrowseModel.catalogue(items) }
    val groups = remember(sorted) { sorted.groupBy { BrowseModel.letter(it.label) } }
    val headerIndex = remember(groups) {
        var i = 0; groups.map { (letter, list) -> (letter to i).also { i += 1 + list.size } }.toMap()
    }
    val scope = rememberCoroutineScope()
    Row(modifier) {
        val jump: (String) -> Unit = { letter -> headerIndex[letter]?.let { scope.launch { state.scrollToItem(it) } } }
        @Composable fun scrubber() { if (groups.size > 1) Scrubber(groups.keys.toList(), jump) }
        if (leftHanded) scrubber()
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), state = state) {
            groups.forEach { (letter, list) ->
                stickyHeader(key = "letter:$letter") {
                    Text(letter, style = t.type.meta, modifier = Modifier.fillMaxWidth().background(c.ground).padding(start = Space.xs, top = Space.s, bottom = Space.xs))
                }
                items(list, key = { it.key }) { item ->
                    val entry = entries[item.key] ?: return@items
                    HRow(entry.label, onClick = { onApp(entry) }, onLongClick = { onOptions(entry) }, onLongClickLabel = "App options",
                        leading = { AppIcon(repo, entry) })
                }
            }
        }
        if (!leftHanded) scrubber()
    }
}

/**
 * A–Z index: tap or drag along it to jump. Labels and touch share one layout: the rail is split into one slot per letter
 * ([BrowseModel.scrubIndex]); each shown label is drawn at its own slot's centre. When the rail is too short for every
 * label (a short list, large fonts), only every n-th letter is labelled (first and last always); the others keep their
 * slots and are reached by dragging, their label appearing while touched. TalkBack users scroll the list itself.
 */
@Composable
private fun Scrubber(letters: List<String>, onLetter: (String) -> Unit) {
    val t = Heimflyt.t; val c = t.color
    var active by remember { mutableStateOf<Int?>(null) }
    fun pick(y: Float, height: Int) {
        val i = BrowseModel.scrubIndex(y, height, letters.size)
        if (i != active) { active = i; onLetter(letters[i]) }
    }
    Layout(content = { letters.forEachIndexed { i, l -> Text(l, style = t.type.meta.copy(color = if (i == active) c.accentInk else c.inkMuted)) } },
        modifier = Modifier.width(28.dp).fillMaxHeight().semantics { contentDescription = "Alphabet index" }
            .pointerInput(letters) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume(); pick(down.position.y, size.height)
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        change.consume(); pick(change.position.y, size.height)
                    }
                    active = null
                }
            }) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0, minHeight = 0)) }
        val height = constraints.maxHeight
        val labelHeight = placeables.maxOfOrNull { it.height } ?: 0
        val labels = BrowseModel.scrubLabels(letters.size, height, labelHeight).toSet()
        layout(constraints.maxWidth, height) {
            placeables.forEachIndexed { i, p ->
                // The active letter is shown while dragging even if unlabelled, so the jump target is always visible.
                if (i in labels || i == active) p.place((constraints.maxWidth - p.width) / 2,
                    (BrowseModel.scrubCenter(i, height, letters.size) - p.height / 2f).toInt())
            }
        }
    }
}

/**
 * The one tag-detail view (also New and Untagged), reached from every renderer. Small (≤ 4): a spatial 2×2 thumb grid.
 * Larger: a readable list — A–Z with letters and the scrubber when [lettered] (tags, Untagged), or in the given order (New,
 * newest first). A tag's saved radial apps may occupy a fixed quick-access grid below the list. Never frequency-sorted.
 * [onSearch] opens the existing Search scoped to the tag.
 */
@Composable
fun BrowseDetail(title: String, meta: String, items: List<BrowseItem>, entries: Map<String, AppEntry>, repo: AppRepository,
    leftHanded: Boolean, spatial: Boolean, lettered: Boolean, onApp: (AppEntry) -> Unit, onOptions: (AppEntry) -> Unit, onBack: () -> Unit,
    searchLabel: String, onSearch: () -> Unit, empty: String, cells: List<String?>? = null,
    quick: List<BrowseTarget> = emptyList(), actions: List<SemanticDestination> = emptyList(), onAction: (SemanticDestination) -> Unit = {},
    onAddItems: (() -> Unit)? = null) {
    val t = Heimflyt.t
    val present = remember(items, entries) { items.mapNotNull { entries[it.key] } }
    val actionTargets = remember(actions) { actions.map { BrowseTarget.Action(it) } }
    val targets = remember(present, actionTargets) { present.map { BrowseTarget.App(it) } + actionTargets }
    @Composable fun tile(target: BrowseTarget, modifier: Modifier) { when (target) {
        is BrowseTarget.App -> AppTile(target.entry, repo, { onApp(target.entry) }, { onOptions(target.entry) }, modifier)
        is BrowseTarget.Action -> ActionTile(target.destination, { onAction(target.destination) }, modifier)
    } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(title, onBack, meta = meta,
            trailing = if (onAddItems != null) ({ QuietButton("Add items", color = t.color.ink, onClick = onAddItems) }) else null)
        if (targets.isEmpty()) { Text(empty, Modifier.padding(Space.l), style = t.type.caption); Spacer(Modifier.weight(1f)) }
        else if (spatial && BrowseModel.isSmall(targets.size)) {
            Spacer(Modifier.weight(1f))
            // Stable cells when given (tags): a hole stays empty so no other destination moves.
            val byKey = targets.associateBy { it.key }
            val grid = cells?.takeIf { it.isNotEmpty() }?.map { key -> key?.let(byKey::get) } ?: targets
            StableTagGrid(grid, leftHanded) { target, m -> tile(target, m) }
        } else if (lettered && quick.isNotEmpty()) {
            val quickKeys = quick.mapTo(HashSet()) { it.key }
            val remaining = items.filterNot { it.key in quickKeys }
            val remainingActions = actionTargets.filterNot { it.key in quickKeys }
            if (remaining.isNotEmpty()) CatalogueList(remaining, entries, repo, leftHanded, onApp, onOptions, Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (remainingActions.isNotEmpty()) {
                Text("Actions", style = t.type.meta, modifier = Modifier.padding(start = Space.xs))
                remainingActions.forEach { target -> HRow(target.label, onClick = { onAction(target.destination) },
                    leading = { Glyph(glyphFor(HomeAction.Semantic(target.destination)), t.color.ink) }) }
            }
            Text("Radial choices", style = t.type.meta, modifier = Modifier.padding(start = Space.xs, top = Space.s))
            StableTagGrid(quick.take(4), leftHanded) { target, m -> tile(target, m) }
        } else if (lettered) {
            if (items.isNotEmpty()) CatalogueList(items, entries, repo, leftHanded, onApp, onOptions, Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            if (actions.isNotEmpty()) { Text("Actions", style = t.type.meta)
                actions.forEach { destination -> HRow(destination.label, onClick = { onAction(destination) },
                    leading = { Glyph(glyphFor(HomeAction.Semantic(destination)), t.color.ink) }) } }
        }
        else LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(present, key = { it.key }) { entry ->
                HRow(entry.label, onClick = { onApp(entry) }, onLongClick = { onOptions(entry) }, onLongClickLabel = "App options",
                    leading = { AppIcon(repo, entry) })
            }
        }
        SearchEntry(searchLabel, onSearch, Modifier.padding(vertical = Space.s))
    }
}
