package no.heimflyt.launcher.ui.browse

import android.app.Application
import no.heimflyt.launcher.ParsedSearch
import no.heimflyt.launcher.TagStore
import no.heimflyt.launcher.resolveTag
import no.heimflyt.launcher.ui.apps.appsFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** H6.0: one Browse model for every renderer. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class BrowseModelTest {
    private val day = 24L * 60 * 60 * 1000
    private val now = 100 * day
    private fun item(key: String, label: String = key, age: Long = 30 * day) = BrowseItem(key, label, now - age)

    @Test fun zeroToSixSlotsAreAdditiveAndThumbAnchored() {
        assertTrue(BrowseModel.cells(0, false).isEmpty())
        assertEquals(0, BrowseModel.rows(0))
        var previous = emptyList<SlotCell>()
        for (n in 1..MAX_SLOTS) {
            val cells = BrowseModel.cells(n, leftHanded = false)
            assertEquals(n, cells.size)
            assertEquals("adding slot $n never moves an existing one", previous, cells.take(n - 1))
            assertEquals((n + 1) / 2, BrowseModel.rows(n))
            previous = cells
        }
        // Fill order: 1-2 bottom, 3-4, 5-6 top; slot 1 nearest a right thumb (bottom right).
        assertEquals(listOf(SlotCell(0, 0, 1), SlotCell(1, 0, 0), SlotCell(2, 1, 1), SlotCell(3, 1, 0), SlotCell(4, 2, 1), SlotCell(5, 2, 0)), previous)
        assertEquals(MAX_SLOTS, BrowseModel.cells(9, false).size)
    }

    @Test fun leftHandMirrorsColumnsOnly() {
        val right = BrowseModel.cells(6, false); val left = BrowseModel.cells(6, true)
        right.zip(left).forEach { (r, l) -> assertEquals(r.row, l.row); assertEquals(1 - r.column, l.column) }
        assertEquals(SlotCell(0, 0, 0), left.first())
    }

    @Test fun slotsAreOwnerOrderedAndNeverPaddedOrReordered() {
        var s: List<String?> = emptyList()
        listOf("work", "social", "work", "media").forEach { s = BrowseModel.add(s, it) }
        assertEquals(listOf("work", "social", "media"), s) // no duplicate; order kept
        repeat(5) { s = BrowseModel.add(s, "t$it") }
        assertEquals(MAX_SLOTS, s.size)
        assertEquals(listOf("work", "social", "media"), s.take(3))
        assertEquals(listOf("social", "work", "media"), BrowseModel.swap(listOf("work", "social", "media"), 0, 1))
        // Assigning a tag held by another slot swaps them; nothing else moves.
        assertEquals(listOf("media", "social", "work"), BrowseModel.assign(listOf("work", "social", "media"), 0, "media"))
        // Removing an earlier slot leaves a hole so later slots keep their cells; removing the last shrinks.
        assertEquals(listOf("work", null, "media"), BrowseModel.remove(listOf("work", "social", "media"), 1))
        assertEquals(listOf("work", "social"), BrowseModel.remove(listOf("work", "social", "media"), 2))
    }

    @Test fun tagRenameAndDeleteKeepSlotPositions() {
        val s = listOf("work", "social", "media")
        assertEquals(listOf("job", "social", "media"), BrowseModel.renamed(s, "work", "job"))
        assertEquals(listOf("work", null, "media"), BrowseModel.deleted(s, "social"))
        assertEquals(listOf("work", "social"), BrowseModel.deleted(s, "media"))
        assertEquals(listOf("work", null, "media"), BrowseModel.reconcile(s, listOf("work", "media")))
        assertEquals(emptyList<String?>(), BrowseModel.reconcile(s, emptyList()))
    }

    @Test fun catalogueHasEveryDestinationOnceAToZ() {
        val items = listOf(item("b", "beta"), item("a2", "Alpha"), item("b", "beta"), item("a1", "Alpha"), item("n", "9gag"))
        val c = BrowseModel.catalogue(items)
        assertEquals(listOf("n", "a1", "a2", "b"), c.map { it.key })
        assertEquals(listOf("#", "A", "B"), BrowseModel.letters(items))
    }

    @Test fun radialChoicesLeadTagWithoutChangingTheAlphabeticRemainder() {
        val members = BrowseModel.catalogue(listOf(item("a@0", "Alpha"), item("b@0", "Beta"), item("c@0", "Gamma"),
            item("b@10", "Beta"), item("d@0", "Delta")))
        val ordered = BrowseModel.radialFirst(members, listOf("b@10", "missing@0", "d@0", "b@10"))
        assertEquals(listOf("b@10", "d@0", "a@0", "b@0", "c@0"), ordered.map { it.key })
    }

    @Test fun newIsSevenDaysNewestFirstAndUntaggedIgnoresStaleTags() {
        val items = listOf(item("old", age = 8 * day), item("x", age = 2 * day), item("y", age = 1 * day), item("future", age = -day))
        assertEquals(listOf("y", "x"), BrowseModel.newItems(items, now).map { it.key })
        assertTrue(BrowseModel.newItems(listOf(item("old", age = 8 * day)), now).isEmpty())
        val assignments = mapOf("x" to setOf("work"), "y" to setOf("deleted-tag"))
        assertEquals(listOf("future", "old", "y"), BrowseModel.untagged(items, assignments, listOf("work")).map { it.key })
    }

    @Test fun overlappingTagsListAnAppInEachTagAtoZ() {
        val items = listOf(item("s", "Slack"), item("m", "Mail"), item("c", "Calendar"))
        val a = mapOf("s" to setOf("work", "chat"), "m" to setOf("work"), "c" to setOf("work"))
        assertEquals(listOf("c", "m", "s"), BrowseModel.inTag(items, a, "work").map { it.key })
        assertEquals(listOf("s"), BrowseModel.inTag(items, a, "chat").map { it.key })
        assertEquals(listOf("chat", "media"), BrowseModel.otherTags(listOf("work", "media", "chat"), listOf("work", null)))
    }

    @Test fun smallTagsAreSpatialLargerAreLists() {
        assertTrue(BrowseModel.isSmall(1)); assertTrue(BrowseModel.isSmall(4)); assertFalse(BrowseModel.isSmall(5))
        assertEquals(2, BrowseModel.rows(4))
    }

    @Test fun tagChipsWrapAndCapWithAllTags() {
        assertEquals(listOf(0, 0, 1, 1, 2), BrowseModel.wrapRows(listOf(40, 40, 40, 40, 40), 100, 10))
        assertEquals(5, BrowseModel.capWrap(List(5) { 40 }, 50, 100, 10, maxRows = 3)) // all fit: no "All tags"
        // Two rows: the second row gives up its last chip so "All tags" fits after it.
        assertEquals(3, BrowseModel.capWrap(List(5) { 40 }, 50, 100, 10, maxRows = 2))
        assertEquals(0, BrowseModel.capWrap(List(5) { 40 }, 100, 100, 10, maxRows = 1))
        assertEquals(0, BrowseModel.capWrap(emptyList(), 50, 100, 10, 2))
    }

    @Test fun scrubberLabelsAndTouchShareOneLayout() {
        // count letters, rail height, label height (px): roomy, the phone's 6-slot Catalogue (A–Å ≈ 29 letters in 936 px),
        // large font, tiny rail, and a single letter.
        val cases = listOf(Triple(26, 2000, 48), Triple(29, 936, 48), Triple(29, 936, 96), Triple(26, 120, 48), Triple(29, 40, 48), Triple(1, 500, 48))
        for ((count, height, label) in cases) {
            val labels = BrowseModel.scrubLabels(count, height, label)
            assertEquals(0, labels.first())
            if (height >= 2 * label) assertEquals(count - 1, labels.last()) // first and last, whenever two labels fit
            // A touch at a drawn label's centre resolves to that label's letter.
            labels.forEach { i -> assertEquals("$count/$height/$label", i, BrowseModel.scrubIndex(BrowseModel.scrubCenter(i, height, count), height, count)) }
            // Drawn labels never overlap, whenever the rail can hold at least two.
            if (height >= 2 * label) labels.zipWithNext().forEach { (a, b) ->
                assertTrue("$count/$height/$label overlap $a,$b", BrowseModel.scrubCenter(b, height, count) - BrowseModel.scrubCenter(a, height, count) >= label)
            }
            // Every letter stays reachable by dragging along the rail when the rail has a pixel per letter.
            if (height >= count) assertEquals((0 until count).toSet(), (0 until height).map { BrowseModel.scrubIndex(it + 0.5f, height, count) }.toSet())
        }
        assertEquals((0 until 26).toList(), BrowseModel.scrubLabels(26, 2000, 48)) // room: every letter labelled
        assertTrue(BrowseModel.scrubLabels(29, 936, 48).size < 29)              // constrained: a subset, first and last kept
        assertEquals(0, BrowseModel.scrubIndex(-10f, 936, 29)); assertEquals(28, BrowseModel.scrubIndex(5000f, 936, 29))
    }

    @Test fun smallTagCellsAreStableAsMembershipChanges() {
        // First view: A–Z.
        var cells = BrowseModel.stableCells(emptyList(), listOf("chatgpt", "claude", "gemini"))
        assertEquals(listOf("chatgpt", "claude", "gemini"), cells)
        // A new member that sorts first does not push the others: it takes the next free cell.
        cells = BrowseModel.stableCells(cells, listOf("aria", "chatgpt", "claude", "gemini"))
        assertEquals(listOf("chatgpt", "claude", "gemini", "aria"), cells)
        // A removed member leaves a hole; nobody moves.
        cells = BrowseModel.stableCells(cells, listOf("aria", "chatgpt", "gemini"))
        assertEquals(listOf("chatgpt", null, "gemini", "aria"), cells)
        // The next new member fills that hole.
        cells = BrowseModel.stableCells(cells, listOf("aria", "chatgpt", "gemini", "grok"))
        assertEquals(listOf("chatgpt", "grok", "gemini", "aria"), cells)
        // Growing past four: a list, no cells kept; shrinking again starts A–Z.
        assertEquals(emptyList<String?>(), BrowseModel.stableCells(cells, listOf("a", "b", "c", "d", "e")))
        assertEquals(listOf("a", "b"), BrowseModel.stableCells(emptyList(), listOf("b", "a").sorted()))
        // Trailing holes are dropped; an empty tag keeps nothing.
        assertEquals(listOf("x"), BrowseModel.stableCells(listOf("x", "y"), listOf("x")))
        assertEquals(emptyList<String?>(), BrowseModel.stableCells(listOf("x"), emptyList()))
        // Cell positions are still the slot geometry: never more than 2×2.
        assertTrue(BrowseModel.stableCells(listOf("a", null, null, "d"), listOf("a", "d", "e", "f")).size <= BrowseModel.SMALL_TAG)
    }

    @Test fun tagCellsPersistFollowRenamesAndKeepH60FormWhenUnused() {
        val app = RuntimeEnvironment.getApplication()
        val prefs = app.getSharedPreferences("browse", 0)
        prefs.edit().clear().commit()
        val store = BrowseStore(app)
        store.updateSlots { listOf("agenter") }
        assertFalse(prefs.getString("config", "")!!.contains("cells")) // no cells yet: the H6.0 form
        store.setCells("agenter", listOf("claude", null, "grok"))
        assertEquals(listOf("claude", null, "grok"), BrowseStore(app).config.value.tagCells["agenter"])
        store.tagRenamed("agenter", "ai")
        assertEquals(listOf("claude", null, "grok"), BrowseStore(app).config.value.tagCells["ai"])
        assertEquals(listOf("ai"), BrowseStore(app).config.value.slots)
        store.tagDeleted("ai")
        assertTrue(BrowseStore(app).config.value.tagCells.isEmpty())
    }

    @Test fun onlyAnAuthoritativeCatalogueMayRecordCellChanges() {
        // What counts as authoritative: loaded, complete (every listed profile enumerated while available) and no warning.
        assertFalse(no.heimflyt.launcher.AppCatalog().authoritative)
        assertTrue(no.heimflyt.launcher.AppCatalog(emptyList(), loaded = true, complete = true).authoritative)
        assertFalse(no.heimflyt.launcher.AppCatalog(emptyList(), loaded = true).authoritative)
        assertFalse(no.heimflyt.launcher.AppCatalog(emptyList(), loaded = true, warning = "A profile is unavailable.", complete = true).authoritative)

        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("browse", 0).edit().clear().commit()
        val store = BrowseStore(app)
        fun view(authoritative: Boolean, members: List<String>) {
            val stored = store.config.value.tagCells["agenter"].orEmpty()
            val shown = BrowseModel.stableCells(stored, members)
            BrowseModel.cellsToPersist(authoritative, stored, shown)?.let { store.setCells("agenter", it) }
        }
        // 1. A complete catalogue: positions are recorded.
        view(true, listOf("chatgpt", "claude", "gemini", "grok"))
        assertEquals(listOf("chatgpt", "claude", "gemini", "grok"), BrowseStore(app).config.value.tagCells["agenter"])
        // 2. Loaded with a warning and missing two destinations: shown with holes, but nothing stored changes.
        val stored = store.config.value.tagCells["agenter"]!!
        assertEquals(listOf("chatgpt", null, "gemini"), BrowseModel.stableCells(stored, listOf("chatgpt", "gemini")))
        view(false, listOf("chatgpt", "gemini"))
        assertEquals(listOf("chatgpt", "claude", "gemini", "grok"), BrowseStore(app).config.value.tagCells["agenter"])
        // 3. The next complete catalogue records a genuine removal normally.
        view(true, listOf("chatgpt", "gemini", "grok"))
        assertEquals(listOf("chatgpt", null, "gemini", "grok"), BrowseStore(app).config.value.tagCells["agenter"])
        assertNull(BrowseModel.cellsToPersist(true, listOf("a"), listOf("a"))) // unchanged: no write
    }

    @Test fun onlySpatialIsShownWhateverWasStored() {
        assertFalse(BROWSE_EXPERIMENT_EXPOSED)
        BrowseRenderer.entries.forEach { assertEquals(BrowseRenderer.SPATIAL, BrowseConfig(renderer = it).shownRenderer) }
    }

    @Test fun rendererSwitchPreservesOrganisationAndRenamesFollow() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("browse", 0).edit().clear().commit()
        val store = BrowseStore(app)
        store.updateSlots { listOf("work", "social", "media") }
        BrowseRenderer.entries.forEach { r ->
            store.setRenderer(r)
            val reread = BrowseStore(app).config.value
            assertEquals(r, reread.renderer)
            assertEquals(listOf("work", "social", "media"), reread.slots)
        }
        store.tagDeleted("social"); store.tagRenamed("media", "watch")
        assertEquals(listOf("work", null, "watch"), BrowseStore(app).config.value.slots)
    }

    @Test fun searchTagQueriesStillWork() {
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("app_tags", 0).edit().clear().commit()
        val tags = TagStore(app)
        tags.create("work"); tags.create("media")
        assertEquals(ParsedSearch("work", ""), ParsedSearch.from("#work "))
        assertEquals("work", resolveTag("wo", tags.names))
        assertEquals("media", appsFilter("#media vid", tags.names).tag)
        assertEquals("vid", appsFilter("#media vid", tags.names).text)
    }
}
