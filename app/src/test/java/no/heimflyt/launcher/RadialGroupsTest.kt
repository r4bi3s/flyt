package no.heimflyt.launcher

import android.app.Application
import android.content.Context
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.heimflyt.launcher.gesture.Fan
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RadialGroupsTest {
    private val a = HomeAction.App("a/.Main", 0, "Same name")
    private val b = HomeAction.App("b/.Main", 10, "Same name")
    private val group = HomeAction.Group("Agenter", listOf(a, null, b, null), "agenter")

    @Test fun removingOrAddingNeverMovesAnotherChildAndDuplicatesUseIdentity() {
        val removed = RadialGroups.replace(group, 0, null)!!
        assertEquals(listOf(null, null, b, null), removed.children)
        assertEquals(listOf(null, a, b, null), RadialGroups.replace(removed, 1, a)!!.children)
        assertNull(RadialGroups.replace(group, 1, a.copy(label = "Renamed")))
        assertNotNull(RadialGroups.replace(group, 1, a.copy(userSerial = 10)))
        assertNull(RadialGroups.replace(group, 4, a))
        assertTrue(RadialGroups.valid(group))
        assertFalse(RadialGroups.valid(removed))
    }

    @Test fun tagRenameOrDeletionPreservesChildrenAndGroupName() {
        val renamed = RadialGroups.renameTag(group, "agenter", "assistenter") as HomeAction.Group
        assertEquals(group.children, renamed.children)
        assertEquals(group.name, renamed.name)
        assertEquals("assistenter", renamed.fallbackTag)
        assertEquals(group.copy(fallbackTag = null), RadialGroups.deleteTag(group, "agenter"))
    }

    @Test fun corruptChildrenBecomeHolesWithoutDiscardingValidSiblings() {
        val decoded = RadialGroups.decode(JSONObject("""{"name":"Test","children":[
            {"component":"a/.Main","serial":0,"label":"A"},
            {"component":"broken","serial":0},
            {"component":"b/.Main","serial":10,"label":"B"},
            {"component":"a/.Main","serial":0,"label":"duplicate"}]}"""))
        assertEquals("a/.Main", (decoded.children[0] as? HomeAction.App)?.component)
        assertNull(decoded.children[1])
        assertEquals("b/.Main", (decoded.children[2] as? HomeAction.App)?.component)
        assertNull(decoded.children[3])
        assertNull(decoded.fallbackTag)
    }

    @Test fun semanticChildrenRoundTripAndRejectDuplicates() {
        val torch = HomeAction.Semantic(SemanticDestination.TORCH)
        val mixed = group.copy(children = listOf(a, torch, null, null))
        assertEquals(mixed, RadialGroups.decode(RadialGroups.encode(mixed)))
        assertNull(RadialGroups.replace(mixed, 2, torch))
        assertNull(RadialGroups.replace(mixed, 1, null)!!.children[1])
    }

    @Test fun groupRoundTripKeepsHolesProfilesGeometryAndHiddenDirections() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("hardware1", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = SettingsStore(context)
        withTimeout(3000) { store.loaded.first { it } }
        val bindings = store.settings.value.bindings.toMutableList().also { it[7] = group; it[3] = HomeAction.Tag("some") }
        val ownerFan = Fan(50f, 98f, 32f)
        store.updateHome { it.copy(h7 = true, h7Launch = true, h7Fan = ownerFan) }
        store.update(tuning = store.settings.value.tuning.copy(sectorCount = 5), bindings = bindings)
        withTimeout(3000) { while (prefs.getString("settings", "")?.contains("\"kind\":\"group\"") != true) delay(10) }
        val restored = SettingsStore(context)
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(group, restored.settings.value.bindings[7])
        assertEquals(HomeAction.Tag("some"), restored.settings.value.bindings[3])
        assertEquals(ownerFan, restored.settings.value.home.h7Fan)
        assertTrue(restored.settings.value.home.h7Launch)
        assertEquals(5, restored.settings.value.tuning.sectorCount)
        // Cosmetic name/label edits keep familiarity, but a changed identity/position resets it.
        assertEquals(group.key(), group.copy(name = "New name").key())
        assertNotEquals(group.key(), RadialGroups.replace(group, 0, a.copy(userSerial = 7))!!.key())
    }
}
