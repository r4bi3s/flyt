package no.heimflyt.launcher

import android.app.Application
import android.content.Context
import no.heimflyt.launcher.ui.apps.appsFilter
import no.heimflyt.launcher.ui.browse.BrowseModel
import no.heimflyt.launcher.ui.browse.BrowseRenderer
import no.heimflyt.launcher.ui.browse.BrowseStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** H6.0: human-readable tag names ("På farten") over a stable identity ("på-farten") that Search can type. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TagNamesTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun prefs() = context.getSharedPreferences("app_tags", Context.MODE_PRIVATE)
    @Before fun clear() { prefs().edit().clear().commit(); context.getSharedPreferences("browse", 0).edit().clear().commit() }

    @Test fun normalisationIsDeterministic() {
        assertEquals("på-farten", normalizeTagName("På farten"))
        assertEquals("på-farten", normalizeTagName("  på   farten  "))
        assertEquals("på-farten", normalizeTagName("#PÅ FARTEN"))
        assertEquals("på-farten", normalizeTagName("på-farten"))
        assertEquals("ærlig-øl-å", normalizeTagName("Ærlig Øl Å"))
        assertEquals("På farten", tagDisplayName("  På \t farten "))
        assertNull(normalizeTagName("   ")); assertNull(normalizeTagName("#")); assertNull(normalizeTagName("a/b"))
        // Every pre-H6 tag is already its own identity.
        listOf("agenter", "bil", "chat", "google", "handle", "jobb", "some").forEach { assertEquals(it, normalizeTagName(it)); assertEquals(it, normalizeTagName("#$it")) }
    }

    @Test fun multiWordTagPersistsAssignsRenamesAndDeletes() {
        val tags = TagStore(context)
        assertTrue(tags.create("På farten"))
        assertTrue(tags.create("på farten")); assertTrue(tags.create("  På  farten ")) // duplicates: no second tag
        assertEquals(listOf("på-farten"), tags.names)
        assertEquals("På farten", tags.label("på-farten"))
        tags.set("app:a@0", "på-farten", true); tags.set("app:b@0", "på-farten", true)
        val reloaded = TagStore(context)
        assertEquals("På farten", reloaded.label("på-farten"))
        assertEquals(setOf("på-farten"), reloaded.tags("app:a@0"))
        // Changing only the display case keeps identity and assignments.
        assertTrue(reloaded.rename("på-farten", "På Farten"))
        assertEquals(listOf("på-farten"), reloaded.names); assertEquals("På Farten", reloaded.label("på-farten"))
        // A real rename moves assignments to the new identity; the old label goes.
        assertTrue(reloaded.rename("på-farten", "Ute og går"))
        val renamed = TagStore(context)
        assertEquals(listOf("ute-og-går"), renamed.names)
        assertEquals("Ute og går", renamed.label("ute-og-går"))
        assertEquals(setOf("ute-og-går"), renamed.tags("app:b@0"))
        assertEquals("på-farten", renamed.label("på-farten").also { assertFalse(it in renamed.names) })
        renamed.delete("ute-og-går")
        val gone = TagStore(context)
        assertTrue(gone.names.isEmpty()); assertTrue(gone.labels.isEmpty()); assertTrue(gone.assignments.isEmpty())
    }

    @Test fun renameCannotCollideWithAnotherTag() {
        val tags = TagStore(context)
        tags.create("På farten"); tags.create("jobb")
        assertFalse(tags.rename("jobb", "på  FARTEN"))
        assertEquals(listOf("jobb", "på-farten"), tags.names)
    }

    @Test fun legacyDataIsReadAndRewrittenByteForByte() {
        val legacy = """{"names":["agenter","bil","jobb"],"apps":{"com.openai.chatgpt\/.Main@0":["agenter"],"x\/.Car@0":["bil","jobb"]}}"""
        prefs().edit().putString("data", legacy).commit()
        val tags = TagStore(context)
        assertEquals(listOf("agenter", "bil", "jobb"), tags.names)
        assertEquals("agenter", tags.label("agenter"))
        tags.set("tmp@0", "bil", true); tags.set("tmp@0", "bil", false) // forces two rewrites, net no change
        assertEquals(org.json.JSONObject(legacy).toString(), prefs().getString("data", null))
        assertFalse(prefs().getString("data", null)!!.contains("labels"))
    }

    @Test fun searchSyntaxForSingleAndMultiWordTags() {
        val tags = TagStore(context)
        listOf("agenter", "jobb", "På farten").forEach { tags.create(it) }
        // Existing single-word syntax, unchanged.
        assertEquals(ParsedSearch("agenter", ""), ParsedSearch.from("#agenter"))
        assertEquals(ParsedSearch("jobb", "claude"), ParsedSearch.from("#jobb claude"))
        assertEquals("jobb", resolveTag("jobb", tags.names))
        // Multi-word: typed as its identity; the chip inserts exactly "#" + identity (what Search/Browse do on tap).
        val chipQuery = "#" + tags.names.first { tags.label(it) == "På farten" }
        assertEquals("#på-farten", chipQuery)
        assertEquals(ParsedSearch("på-farten", ""), ParsedSearch.from(chipQuery))
        assertEquals("på-farten", resolveTag(ParsedSearch.from("#PÅ-FARTEN kart").tag!!, tags.names))
        assertEquals("kart", ParsedSearch.from("#på-farten kart").text)
        assertEquals("på-farten", resolveTag("på", tags.names)) // unique prefix, as before
        assertEquals("på-farten", appsFilter("#på-farten kart", tags.names).tag)
        // Typing the display name with a space is tag "på" + text "farten": deterministic, no guessing.
        assertEquals(ParsedSearch("på", "farten"), ParsedSearch.from("#På farten"))
    }

    @Test fun browseSlotsHoldTheIdentityAcrossRenderersAndRenames() {
        val tags = TagStore(context); tags.create("På farten"); tags.create("jobb")
        tags.set("app:a@0", "på-farten", true)
        val browse = BrowseStore(context)
        browse.updateSlots { BrowseModel.add(BrowseModel.add(it, "jobb"), "på-farten") }
        BrowseRenderer.entries.forEach { browse.setRenderer(it) }
        assertEquals(listOf("jobb", "på-farten"), BrowseStore(context).config.value.slots)
        assertEquals(setOf("på-farten"), TagStore(context).tags("app:a@0"))
        // Display-only rename: same identity, slot untouched. Identity rename: the screen's hook moves the slot.
        tags.rename("på-farten", "PÅ FARTEN"); assertEquals(listOf("jobb", "på-farten"), BrowseModel.reconcile(browse.config.value.slots, tags.names))
        tags.rename("på-farten", "Ute"); browse.tagRenamed("på-farten", "ute")
        assertEquals(listOf("jobb", "ute"), BrowseModel.reconcile(BrowseStore(context).config.value.slots, TagStore(context).names))
    }
}
