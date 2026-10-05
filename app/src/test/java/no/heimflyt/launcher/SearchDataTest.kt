package no.heimflyt.launcher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import android.content.Context
import android.app.Application
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SearchDataTest {
    @Test fun tagParsingKeepsOnlyOneOptionalTextPart() {
        assertEquals(ParsedSearch("agents","cla"),ParsedSearch.from("  #agents cla  "))
        assertEquals(ParsedSearch("social",""),ParsedSearch.from("#SOCIAL"))
        assertEquals(ParsedSearch(null,"Claude"),ParsedSearch.from(" Claude "))
    }
    @Test fun rankingIsExactThenPrefixThenSubstring() {
        assertEquals(0,matchScore("Claude","claude"))
        assertEquals(1,matchScore("Claude","cla"))
        assertEquals(2,matchScore("Claude","aud"))
        assertEquals(Int.MAX_VALUE,matchScore("Claude","xyz"))
    }
    @Test fun uniqueTagPrefixShowsResultsWithoutChangingTypedText() {
        assertEquals("some",resolveTag("so",listOf("agents","some")))
        assertEquals("some",resolveTag("some",listOf("some","something")))
        assertEquals(null,resolveTag("so",listOf("social","some")))
        assertEquals(null,resolveTag("missing",listOf("some")))
    }
    @Test fun flatOverlappingTagsPersistAndRenameAcrossApps() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_tags",Context.MODE_PRIVATE).edit().clear().commit()
        val tags=TagStore(context)
        assertTrue(tags.create("#agents"));assertTrue(tags.create("ai"))
        tags.set("app:a@0","agents",true);tags.set("app:a@0","ai",true)
        tags.set("app:b@0","agents",true)
        assertTrue(tags.rename("agents","people"))
        val restored=TagStore(context)
        assertEquals(setOf("ai","people"),restored.tags("app:a@0"))
        assertEquals(setOf("people"),restored.tags("app:b@0"))
        restored.delete("people")
        assertEquals(setOf("ai"),TagStore(context).tags("app:a@0"))
        assertEquals(emptySet<String>(),TagStore(context).tags("app:b@0"))
    }
    @Test fun contactRowsGroupRepeatedNamesButKeepUnderlyingRecords() {
        val entries=listOf(
            ContactEntry(1,"a","Anette"),
            ContactEntry(2,"b","Anette",number="123"),
            ContactEntry(3,"c","anette",email="a@example.org"),
            ContactEntry(4,"d","Anne"),
        )
        val groups=groupContacts(entries)
        assertEquals(2,groups.size)
        assertEquals("Anette",groups[0].name)
        assertEquals(listOf(1L,2L,3L),groups[0].entries.map { it.id })
        assertEquals("Anne",groups[1].name)
    }
    // H5 (owner decision Q2): the store keeps at most five distinct destinations; the display count (Off/3/5) is separate.
    @Test fun recentTargetsKeepOnlyFiveDistinctSuccessfulDestinations() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recent_targets",Context.MODE_PRIVATE).edit().clear().commit()
        val store=RecentTargets(context)
        store.record(RecentTarget("app","a","First",serial=0))
        store.record(RecentTarget("contact","lookup","Person",serial=9))
        store.record(RecentTarget("semantic","browser","Browser"))
        store.record(RecentTarget("app","a","First renamed",serial=0))
        store.record(RecentTarget("app","b","Fourth",serial=0))
        store.record(RecentTarget("app","c","Fifth",serial=0))
        store.record(RecentTarget("app","d","Sixth",serial=0))
        val restored=RecentTargets(context)
        assertEquals(listOf("Sixth","Fifth","Fourth","First renamed","Browser"),restored.entries.map { it.label })
        assertEquals(3,restored.count)
    }
    @Test fun recentCountOffClearsAndStopsRecording() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recent_targets",Context.MODE_PRIVATE).edit().clear().commit()
        val store=RecentTargets(context)
        store.record(RecentTarget("app","a","First",serial=0))
        store.changeCount(0)
        assertTrue(store.entries.isEmpty())
        store.record(RecentTarget("app","b","Second",serial=0))
        assertTrue(store.entries.isEmpty())
        store.changeCount(5); store.changeCount(7)
        assertEquals(3,RecentTargets(context).count)
        store.changeCount(5)
        store.record(RecentTarget("app","b","Second",serial=0))
        assertEquals(listOf("Second"),RecentTargets(context).entries.map { it.label })
        assertEquals(5,RecentTargets(context).count)
    }
    @Test fun anH42ThreeItemStoreUpgradesUnchanged() {
        val context=RuntimeEnvironment.getApplication()
        context.getSharedPreferences("recent_targets",Context.MODE_PRIVATE).edit().clear().putString("items",
            """[{"kind":"app","key":"a","label":"A","serial":0},{"kind":"semantic","key":"browser","label":"Browser"},{"kind":"contact","key":"l","label":"P","serial":3}]""").commit()
        val store=RecentTargets(context)
        assertEquals(listOf("A","Browser","P"),store.entries.map { it.label })
        assertEquals(3,store.count)
    }
}
