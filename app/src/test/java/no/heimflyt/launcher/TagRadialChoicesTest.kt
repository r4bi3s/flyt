package no.heimflyt.launcher

import android.app.Application
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class TagRadialChoicesTest {
    private val a = HomeAction.App("a/a.Main", 0, "Same name")
    private val b = HomeAction.App("a/a.Main", 10, "Same name")
    private val group = HomeAction.Group("Home", listOf(a, null, b, null), "home")

    private suspend fun store(): SettingsStore {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("hardware1", Context.MODE_PRIVATE).edit().clear().commit()
        return SettingsStore(context).also { withTimeout(3000) { it.loaded.first { loaded -> loaded } } }
    }

    @Test fun selectedSubsetIsSharedByTagBindingsAndOrdinaryTagsStayFlat() {
        val explicit = group.copy(name = "Separate", fallbackTag = null)
        val s = LocalSettings(home = HomePrefs(h7 = true), tagGroups = mapOf("home" to group),
            bindings = listOf(HomeAction.Tag("home"), HomeAction.Tag("home"), HomeAction.Tag("work"), explicit))
        assertEquals(mapOf(0 to group, 1 to group, 3 to explicit), H7.groups(s))
        assertTrue(H7.groups(s.copy(home = HomePrefs(h7 = false))).isEmpty())
    }

    @Test fun choicesSurviveOtherSettingsEditsAndColdReload() = runBlocking {
        val store = store()
        store.setTagGroup("home", group)
        store.update(bindings = store.settings.value.bindings.toMutableList().also { it[7] = HomeAction.Tag("home") })
        store.updateHome { it.copy(h7 = true) }
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.let { "tagGroups" in it && "\"h7\":true" in it } != true) delay(10)
        }
        val restored = SettingsStore(RuntimeEnvironment.getApplication())
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(group, restored.settings.value.tagGroups["home"])
        assertEquals(HomeAction.Tag("home"), restored.settings.value.bindings[7])
        assertTrue(restored.settings.value.home.h7)
    }

    @Test fun removingMembershipClearsOnlyExactProfileAndKeepsHole() = runBlocking {
        val store = store()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_tags", Context.MODE_PRIVATE).edit().clear().commit()
        val tags = TagStore(context) { tag, key -> store.removeTagChild(tag, key) }
        tags.create("Home")
        // Large tags have no four-member limit; only the explicit radial subset does.
        repeat(29) { tags.set("extra/extra.App@$it", "home", true) }
        tags.set("${a.component}@${a.userSerial}", "home", true)
        tags.set("${b.component}@${b.userSerial}", "home", true)
        store.setTagGroup("home", group)
        tags.set("${a.component}@${a.userSerial}", "home", false)
        assertEquals(listOf(null, null, b, null), store.settings.value.tagGroups.getValue("home").children)
        assertEquals(30, tags.assignments.size)
        // No catalog refresh prunes an unavailable child's configured place.
        assertEquals(b, store.settings.value.tagGroups.getValue("home").children[2])
        tags.set("${b.component}@${b.userSerial}", "home", false)
        assertFalse("home" in store.settings.value.tagGroups)
    }

    @Test fun renameDeleteAndClearPreserveUnrelatedGroups() = runBlocking {
        val store = store()
        val independent = group.copy(name = "Separate")
        store.update(bindings = store.settings.value.bindings.toMutableList().also {
            it[0] = HomeAction.Tag("home"); it[1] = independent
        })
        store.setTagGroup("home", group)
        store.renameTag("home", "home", "HOME")
        assertEquals("HOME", store.settings.value.tagGroups.getValue("home").name)
        store.renameTag("home", "house", "House")
        assertFalse("home" in store.settings.value.tagGroups)
        assertEquals(group.children, store.settings.value.tagGroups.getValue("house").children)
        assertEquals(HomeAction.Tag("house"), store.settings.value.bindings[0])
        store.deleteTag("house")
        assertTrue(store.settings.value.tagGroups.isEmpty())
        assertEquals(HomeAction.Search, store.settings.value.bindings[0])
        assertEquals(independent.copy(fallbackTag = null), store.settings.value.bindings[1])
    }

    @Test fun automaticModeKeepsFixedChoicesAcrossSwitchAndReload() = runBlocking {
        val store = store()
        store.setTagGroup("home", group)
        val automatic = HomeAction.Group("Home", listOf(b, a, null, null), "home")
        store.setTagAutomatic("home", true)
        store.setAutoTagGroup("home", automatic)
        assertEquals(automatic, store.settings.value.activeTagGroups()["home"])
        assertEquals(group, store.settings.value.tagGroups["home"])
        store.setTagAutomatic("home", false)
        assertEquals(group, store.settings.value.activeTagGroups()["home"])
        store.setTagAutomatic("home", true)
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.let { "\"home\":\"most-used\"" in it && "\"autoTagGroups\":{\"home\"" in it } != true) delay(10)
        }
        val restored = SettingsStore(RuntimeEnvironment.getApplication())
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(group, restored.settings.value.tagGroups["home"])
        assertEquals(automatic, restored.settings.value.activeTagGroups()["home"])
        restored.renameTag("home", "house", "House")
        assertTrue("house" in restored.settings.value.autoTags)
        assertEquals(group.children, restored.settings.value.tagGroups["house"]?.children)
        restored.deleteTag("house")
        assertTrue(restored.settings.value.autoTags.isEmpty())
        assertTrue(restored.settings.value.autoTagGroups.isEmpty())
    }

    @Test fun taggedActionPersistsInRadialAndClearsWhenUnassigned() = runBlocking {
        val store = store()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_tags", Context.MODE_PRIVATE).edit().clear().commit()
        val tags = TagStore(context) { tag, key -> store.removeTagChild(tag, key) }
        tags.create("Home")
        val phone = HomeAction.Semantic(SemanticDestination.PHONE)
        val torch = HomeAction.Semantic(SemanticDestination.TORCH)
        tags.set(phone.tagMemberKey()!!, "home", true)
        tags.set(torch.tagMemberKey()!!, "home", true)
        val mixed = HomeAction.Group("Home", listOf(phone, a, torch, null), "home")
        store.setTagGroup("home", mixed)
        val prefs = context.getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.contains("\"tagGroups\":{\"home\"") != true) delay(10)
        }
        val restored = SettingsStore(context)
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(mixed, restored.settings.value.tagGroups["home"])
        tags.set(torch.tagMemberKey()!!, "home", false)
        assertEquals(listOf(phone, a, null, null), store.settings.value.tagGroups["home"]?.children)
    }

    @Test fun bindingAndTuningEditsPreserveAutomaticModeAcrossReload() = runBlocking {
        val store = store()
        val automatic = group.copy(children = listOf(b, a, null, null))
        store.setTagGroup("home", group)
        store.setTagAutomatic("home", true)
        store.setAutoTagGroup("home", automatic)
        store.update(bindings = store.settings.value.bindings.toMutableList().also { it[0] = HomeAction.Tag("home") })
        store.update(tuning = store.settings.value.tuning.copy(leftHanded = true))
        assertEquals(setOf("home"), store.settings.value.autoTags)
        assertEquals(automatic, store.settings.value.activeTagGroups()["home"])
        assertEquals(group, store.settings.value.tagGroups["home"])
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.contains("\"leftHanded\":true") != true) delay(10)
        }
        val restored = SettingsStore(RuntimeEnvironment.getApplication())
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(setOf("home"), restored.settings.value.autoTags)
        assertEquals(automatic, restored.settings.value.activeTagGroups()["home"])
        assertEquals(group, restored.settings.value.tagGroups["home"])
        assertEquals(HomeAction.Tag("home"), restored.settings.value.bindings[0])
    }

    @Test fun unassigningWhileFixedCannotResurrectCachedAutomaticAction() = runBlocking {
        val store = store()
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("app_tags", Context.MODE_PRIVATE).edit().clear().commit()
        val tags = TagStore(context) { tag, key -> store.removeTagChild(tag, key) }
        val phone = HomeAction.Semantic(SemanticDestination.PHONE)
        tags.create("Home")
        tags.set(phone.tagMemberKey()!!, "home", true)
        val mixed = group.copy(children = listOf(phone, a, b, null))
        store.setTagGroup("home", mixed)
        store.setTagAutomatic("home", true)
        store.setAutoTagGroup("home", mixed)
        store.setTagAutomatic("home", false)
        tags.set(phone.tagMemberKey()!!, "home", false)
        // With a paused profile, no complete catalogue may be available to replace the cached ranking.
        store.setTagAutomatic("home", true)
        assertEquals(listOf(null, a, b, null), store.settings.value.activeTagGroups()["home"]?.children)
        assertEquals(listOf(null, a, b, null), store.settings.value.tagGroups["home"]?.children)
        val prefs = context.getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.let {
                    val saved = org.json.JSONObject(it)
                    val cached = saved.optJSONObject("autoTagGroups")?.optJSONObject("home")
                    saved.optJSONObject("tagModes")?.optString("home") == "most-used" &&
                        cached != null && RadialGroups.decode(cached).children == listOf(null, a, b, null)
                } != true) delay(10)
        }
        val restored = SettingsStore(context)
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(listOf(null, a, b, null), restored.settings.value.activeTagGroups()["home"]?.children)
        assertTrue(TagStore(context).tags(phone.tagMemberKey()!!).isEmpty())
    }

    @Test fun mixedLaunchCountsReloadAndRankOnlyCurrentMembers() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE).edit().clear().commit()
        val counts = LaunchCounts(context)
        val phone = HomeAction.Semantic(SemanticDestination.PHONE)
        val torch = HomeAction.Semantic(SemanticDestination.TORCH)
        repeat(4) { counts.record(torch) }
        repeat(3) { counts.record(b) }
        repeat(2) { counts.record(phone) }
        counts.record(a)
        counts.record(HomeAction.Tag("home")) // Opening the tag is not launching a member.
        val reloaded = LaunchCounts(context).counts
        assertEquals(4, reloaded.size)
        val members = listOf(a, b, phone, torch).map { it.tagMemberKey()!! to it.label() }
        val ranked = MostUsed.rank(members, reloaded, emptyList(), emptyList())
        assertEquals(listOf(torch, b, phone, a).map { it.tagMemberKey() }, ranked)
        // Removed/unavailable members cannot be pulled back in by their stored launch count or previous position.
        assertEquals(listOf(b, phone, a).map { it.tagMemberKey() },
            MostUsed.rank(members.filterNot { it.first == torch.tagMemberKey() }, reloaded, ranked, emptyList()))
    }

    @Test fun mostUsedRankingKeepsTiesStableAndUsesSavedChoicesBeforeAlphabet() {
        val members = listOf("a@0" to "Alpha", "b@0" to "Beta", "c@0" to "Charlie", "d@0" to "Delta", "e@0" to "Echo")
        assertEquals(listOf("d@0", "b@0", "a@0", "c@0"),
            MostUsed.rank(members, emptyMap(), emptyList(), listOf("d@0", "b@0")))
        assertEquals(listOf("c@0", "b@0", "d@0", "a@0"),
            MostUsed.rank(members, mapOf("c@0" to 5, "b@0" to 4, "d@0" to 4), listOf("b@0", "d@0"), emptyList()))
    }

    @Test fun smallTagsAutomaticallyRankAllActionsFromTheLeftForEitherHand() {
        val members = SemanticDestination.entries.map(HomeAction::Semantic)
        val counts = members.withIndex().associate { it.value.tagMemberKey()!! to it.index + 1 }
        for (left in listOf(false, true)) for (size in 0..4) {
            val settings = LocalSettings(tuning = no.heimflyt.launcher.gesture.TuningParams(leftHanded = left))
            val prepared = MostUsed.prepare("quick", "Quick", members.take(size), settings, counts, true)!!
            val visual = if (left) prepared.children.reversed() else prepared.children
            assertEquals(members.take(size).reversed(), visual.filterNotNull())
            assertEquals(List(4 - size) { null }, visual.drop(size))
        }
    }

    @Test fun automaticSmallTagsGrowToFlatAndShrinkBackWithoutOptingIntoTopFour() {
        val members = SemanticDestination.entries.map(HomeAction::Semantic) + a
        val settings = LocalSettings()
        val four = MostUsed.prepare("quick", "Quick", members.take(4), settings, emptyMap(), true)!!
        val cached = settings.copy(autoTagGroups = mapOf("quick" to four))
        assertNull(MostUsed.prepare("quick", "Quick", members.take(1), cached, emptyMap(), false))
        val five = MostUsed.prepare("quick", "Quick", members, cached, emptyMap(), true)!!
        assertTrue(five.children.all { it == null })
        val three = MostUsed.prepare("quick", "Quick", members.take(3), cached.copy(autoTagGroups = mapOf("quick" to five)), emptyMap(), true)!!
        assertEquals(members.take(3).toSet(), three.children.filterNotNull().toSet())
        val optedIn = cached.copy(tagModes = mapOf("quick" to TagRadialMode.MOST_USED))
        assertEquals(4, MostUsed.prepare("quick", "Quick", members, optedIn, emptyMap(), true)!!.children.count { it != null })
    }

    @Test fun automaticTiesKeepTheirOrderAndFixedChoicesWin() {
        val members = listOf(a, b, HomeAction.Semantic(SemanticDestination.PHONE))
        val previous = HomeAction.Group("Quick", listOf(b, members[2], a, null), "quick")
        val settings = LocalSettings(autoTagGroups = mapOf("quick" to previous))
        assertEquals(previous, MostUsed.prepare("quick", "Quick", members, settings, emptyMap(), true))
        val fixed = settings.copy(tagGroups = mapOf("quick" to group))
        assertNull(MostUsed.prepare("quick", "Quick", members, fixed, emptyMap(), true))
        assertEquals(group, fixed.activeTagGroups()["quick"])
    }

    @Test fun defaultModeAndPreparedChildrenSurviveReloadAndKeepFixedChoices() = runBlocking {
        val store = store()
        store.setTagGroup("home", group)
        store.setTagMode("home", TagRadialMode.SMALL)
        val prepared = MostUsed.prepare("home", "Home", listOf(a, b), store.settings.value, emptyMap(), true)!!
        store.setAutoTagGroup("home", prepared)
        store.update(bindings = store.settings.value.bindings.toMutableList().also { it[0] = HomeAction.Tag("home") })
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        withTimeout(3000) {
            while (prefs.getString("settings", "")?.let {
                    val saved = org.json.JSONObject(it)
                    saved.optJSONObject("tagModes")?.optString("home") == "small" &&
                        saved.optJSONObject("autoTagGroups")?.has("home") == true &&
                        saved.getJSONArray("bindings").getJSONObject(0).optString("kind") == "tag"
                } != true) delay(10)
        }
        val restored = SettingsStore(RuntimeEnvironment.getApplication())
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(TagRadialMode.SMALL, restored.settings.value.tagMode("home"))
        assertEquals(prepared, H7.groups(restored.settings.value.copy(home = HomePrefs(h7 = true)))[0])
        restored.setTagMode("home", TagRadialMode.FIXED)
        assertEquals(group, restored.settings.value.activeTagGroups()["home"])
        restored.setTagGroup("home", group.copy(children = List(4) { null }))
        assertEquals(TagRadialMode.FIXED, restored.settings.value.tagMode("home"))
        assertFalse("home" in restored.settings.value.activeTagGroups())
    }

    @Test fun olderFixedAndMostUsedModesMigrateWithoutChangingChoices() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val legacy = org.json.JSONObject().put("tuning", org.json.JSONObject()).put("home", org.json.JSONObject())
            .put("tagGroups", org.json.JSONObject()
            .put("home", RadialGroups.encode(group)).put("work", RadialGroups.encode(group)))
            .put("autoTags", org.json.JSONArray().put("work"))
            .put("autoTagGroups", org.json.JSONObject().put("work", RadialGroups.encode(group)))
        context.getSharedPreferences("hardware1", Context.MODE_PRIVATE).edit().clear().putString("settings", legacy.toString()).commit()
        val restored = SettingsStore(context)
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(TagRadialMode.FIXED, restored.settings.value.tagMode("home"))
        assertEquals(TagRadialMode.MOST_USED, restored.settings.value.tagMode("work"))
        assertEquals(TagRadialMode.SMALL, restored.settings.value.tagMode("quick"))
        assertEquals(group, restored.settings.value.activeTagGroups()["home"])
        assertEquals(group.children, restored.settings.value.activeTagGroups()["work"]?.children)
    }

    @Test fun localLaunchCountsKeepExactProfileAndCanBeCleared() {
        val context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE).edit().clear().commit()
        val counts = LaunchCounts(context)
        counts.record(a); counts.record(a); counts.record(b)
        assertEquals(2, LaunchCounts(context).counts["${a.component}@${a.userSerial}"])
        assertEquals(1, LaunchCounts(context).counts["${b.component}@${b.userSerial}"])
        counts.clear()
        assertTrue(LaunchCounts(context).counts.isEmpty())
    }
}
