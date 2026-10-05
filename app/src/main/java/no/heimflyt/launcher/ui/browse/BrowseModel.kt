package no.heimflyt.launcher.ui.browse

/**
 * H6.0 Browse: ONE model for every experimental renderer (Spatial, Catalogue,
 * Progressive). Renderers only compose these results; none owns tags, ordering, slots or destination logic. Pure Kotlin.
 */

/** A launchable app as Browse sees it: its profile-qualified key, label and first install time (for New). */
data class BrowseItem(val key: String, val label: String, val installedAt: Long)

/** Up to six Browse slots, each referencing an existing tag (or a hole left by a deleted tag). Layout state, not a tag type. */
const val MAX_SLOTS = 6
const val NEW_WINDOW_MS = 7L * 24 * 60 * 60 * 1000

/** A slot's fixed cell: [row] counted from the bottom (thumb) row, [column] 0 = left, 1 = right. */
data class SlotCell(val slot: Int, val row: Int, val column: Int)

object BrowseModel {
    /**
     * Additive, thumb-anchored geometry: slots fill 1-2 (bottom), 3-4, 5-6 (top); a new slot never moves an existing one.
     * Slot 1 is nearest the thumb: bottom-right for a right hand, bottom-left for a left hand. Cells never grow to fill space.
     */
    fun cells(count: Int, leftHanded: Boolean): List<SlotCell> = List(count.coerceIn(0, MAX_SLOTS)) { i ->
        val nearThumb = i % 2 == 0
        SlotCell(i, i / 2, if (nearThumb == leftHanded) 0 else 1)
    }

    /** The number of grid rows for [count] slots (0 → no grid). */
    fun rows(count: Int) = (count.coerceIn(0, MAX_SLOTS) + 1) / 2

    /** Every launchable destination exactly once, deterministic A–Z (then by key for identical labels). */
    fun catalogue(items: List<BrowseItem>): List<BrowseItem> =
        items.distinctBy { it.key }.sortedWith(compareBy<BrowseItem>({ it.label.lowercase() }, { it.key }))

    /** Section letter for the catalogue and its scrubber; non-letters group under '#'. */
    fun letter(label: String): String = label.firstOrNull()?.uppercaseChar()?.takeIf { it.isLetter() }?.toString() ?: "#"

    /** The catalogue's section letters in order (for the scrubber). */
    fun letters(items: List<BrowseItem>): List<String> = catalogue(items).map { letter(it.label) }.distinct()

    /** Apps installed within the last seven days, newest first. */
    fun newItems(items: List<BrowseItem>, now: Long): List<BrowseItem> =
        catalogue(items).filter { it.installedAt in (now - NEW_WINDOW_MS)..now }.sortedByDescending { it.installedAt }

    /** Launchable apps with no existing tag. */
    fun untagged(items: List<BrowseItem>, assignments: Map<String, Set<String>>, tagNames: Collection<String>): List<BrowseItem> {
        val names = tagNames.toSet()
        return catalogue(items).filter { item -> assignments[item.key].orEmpty().none { it in names } }
    }

    /** One tag's destinations, A–Z. Overlapping tags: an app appears in every tag it has. */
    fun inTag(items: List<BrowseItem>, assignments: Map<String, Set<String>>, tag: String): List<BrowseItem> =
        catalogue(items).filter { tag in assignments[it.key].orEmpty() }

    /** Saved radial choices come first in a tag preview; unavailable or no-longer-member apps are ignored. */
    fun radialFirst(members: List<BrowseItem>, radialKeys: List<String>): List<BrowseItem> {
        val byKey = members.associateBy { it.key }
        val chosen = radialKeys.distinct().mapNotNull(byKey::get)
        val chosenKeys = chosen.mapTo(HashSet()) { it.key }
        return chosen + members.filterNot { it.key in chosenKeys }
    }

    /** Small tags stay spatial (a 2×2 grid); larger ones become a readable A–Z list. One rule, not one layout per size. */
    const val SMALL_TAG = 4
    fun isSmall(count: Int) = count <= SMALL_TAG

    /**
     * Stable cells for a small tag's 2×2 grid (H6.1), the same principle as slots: positions are layout state. Members that
     * stay keep their cell; a removed member leaves a hole; new members (A–Z) fill the first holes, then append. Nothing
     * already placed moves. A tag larger than [SMALL_TAG] is a list and keeps no cells (they start A–Z if it shrinks again).
     */
    fun stableCells(previous: List<String?>, members: List<String>): List<String?> {
        if (members.size > SMALL_TAG) return emptyList()
        val present = members.toSet()
        val next = previous.map { it?.takeIf { key -> key in present } }.toMutableList()
        members.filter { it !in next }.forEach { key ->
            val hole = next.indexOf(null)
            if (hole >= 0) next[hole] = key else next += key
        }
        val cells = next.dropLastWhile { it == null }
        return if (cells.size > SMALL_TAG) cells.filterNotNull() else cells
    }

    /**
     * What to persist for a small tag's cells, or null for nothing: only an [authoritative] catalogue may record holes or
     * new members. An incomplete one is shown as is, but the stored positions stay untouched.
     */
    fun cellsToPersist(authoritative: Boolean, stored: List<String?>, shown: List<String?>): List<String?>? =
        if (!authoritative || stored == shown) null else shown

    /** Tags that are not in a slot, A–Z: the capped, wrapping "other tags". */
    fun otherTags(tagNames: Collection<String>, slots: List<String?>): List<String> = tagNames.filter { it !in slots }.sorted()

    // ---- slot editing: owner-driven only; never reordered or padded automatically ----

    /** Keeps only slots that still reference a tag; a deleted tag leaves a hole so later slots keep their positions. */
    fun reconcile(slots: List<String?>, tagNames: Collection<String>): List<String?> =
        slots.take(MAX_SLOTS).map { it?.takeIf { name -> name in tagNames } }.dropLastWhile { it == null }

    fun add(slots: List<String?>, tag: String): List<String?> =
        if (slots.size >= MAX_SLOTS || tag in slots) slots else slots + tag

    /** Replaces slot [index] with [tag] (or a hole with null); a tag already in another slot moves by swapping places. */
    fun assign(slots: List<String?>, index: Int, tag: String?): List<String?> {
        if (index !in slots.indices) return slots
        val next = slots.toMutableList()
        val existing = if (tag == null) -1 else next.indexOf(tag)
        if (existing >= 0 && existing != index) next[existing] = next[index]
        next[index] = tag
        return next.dropLastWhile { it == null }
    }

    /** Removes slot [index]: the last slot disappears; an earlier one becomes a hole so no other slot moves. */
    fun remove(slots: List<String?>, index: Int): List<String?> =
        if (index !in slots.indices) slots else slots.toMutableList().also { it[index] = null }.dropLastWhile { it == null }

    /** Swaps two slots (the owner's explicit reorder). */
    fun swap(slots: List<String?>, a: Int, b: Int): List<String?> {
        if (a !in slots.indices || b !in slots.indices) return slots
        return slots.toMutableList().also { val x = it[a]; it[a] = it[b]; it[b] = x }.dropLastWhile { it == null }
    }

    fun renamed(slots: List<String?>, old: String, new: String): List<String?> = slots.map { if (it == old) new else it }
    fun deleted(slots: List<String?>, name: String): List<String?> = slots.map { if (it == name) null else it }.dropLastWhile { it == null }

    // ---- alphabet scrubber: one layout for drawing and touch ----

    /** The rail is split into [count] equal slots; y (0 until [height]) belongs to exactly one letter. */
    fun scrubIndex(y: Float, height: Int, count: Int): Int =
        if (count <= 0 || height <= 0) 0 else (y / height * count).toInt().coerceIn(0, count - 1)

    /** The centre of letter [index]'s slot: where its label is drawn, so a touch on a label resolves to that letter. */
    fun scrubCenter(index: Int, height: Int, count: Int): Float = (index + 0.5f) * height / count

    /**
     * Which letters get a visible label when [count] labels of [labelHeight] do not fit in [height] (short list, large
     * font): every step-th letter, always the first and the last, never overlapping. Unlabelled letters stay reachable
     * by dragging, since every letter keeps its own slot.
     */
    fun scrubLabels(count: Int, height: Int, labelHeight: Int): List<Int> {
        if (count <= 0) return emptyList()
        val step = if (height <= 0) count else maxOf(1, Math.ceil(labelHeight.toDouble() * count / height).toInt())
        val labels = (0 until count step step).toMutableList()
        val last = count - 1
        if (labels.last() != last) { if (labels.size > 1 && last - labels.last() < step) labels[labels.lastIndex] = last else if (last >= step) labels += last }
        return labels
    }

    // ---- wrapped, capped tag chips (shared by Browse and Search; replaces horizontally scrolling rows) ----

    /** Row index for each item when [widths] wrap into lines of [maxWidth] with [gap] between neighbours. */
    fun wrapRows(widths: List<Int>, maxWidth: Int, gap: Int): List<Int> {
        var row = 0; var x = 0
        return widths.mapIndexed { i, w ->
            if (i > 0 && x + gap + w > maxWidth) { row++; x = w } else x = if (i == 0) w else x + gap + w
            row
        }
    }

    /**
     * How many chips fit in [maxRows] wrapped rows. If not all do, the trailing "All tags (N)" chip ([moreWidth]) must fit on
     * the last row, so chips are dropped from the end until it does. Order is never changed.
     */
    fun capWrap(widths: List<Int>, moreWidth: Int, maxWidth: Int, gap: Int, maxRows: Int): Int {
        if (widths.isEmpty() || (wrapRows(widths, maxWidth, gap).last() < maxRows)) return widths.size
        for (k in widths.size - 1 downTo 0) if (wrapRows(widths.take(k) + moreWidth, maxWidth, gap).last() < maxRows) return k
        return 0
    }
}

/**
 * The H6.0 experiment renderers. TEMPORARY: a harness for the owner's physical A/B/C comparison, not three product modes.
 * The winner (or a recommended default) is decided after testing.
 */
enum class BrowseRenderer(val label: String) { SPATIAL("Spatial"), CATALOGUE("Catalogue"), PROGRESSIVE("Progressive") }

/**
 * H6.1: the owner chose Spatial as the Apps surface. Catalogue and Progressive stay compiled but unreachable (no switch in
 * Tune or ⋯, and a stored choice is ignored) until Spatial has been the daily driver for a while; then they are removed.
 */
const val BROWSE_EXPERIMENT_EXPOSED = false
val BrowseConfig.shownRenderer: BrowseRenderer get() = if (BROWSE_EXPERIMENT_EXPOSED) renderer else BrowseRenderer.SPATIAL
