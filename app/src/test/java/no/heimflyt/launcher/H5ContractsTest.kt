package no.heimflyt.launcher

import android.app.Application
import android.content.Context
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.heimflyt.launcher.gesture.*
import no.heimflyt.launcher.ui.apps.appsFilter
import no.heimflyt.launcher.ui.apps.sectionLetter
import no.heimflyt.launcher.ui.home.AccessibleDirections
import no.heimflyt.launcher.ui.home.HomeHints
import no.heimflyt.launcher.ui.home.weekLabel
import no.heimflyt.launcher.ui.home.weekNumber
import no.heimflyt.launcher.ui.home.weekNumberDefault
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class H5ContractsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private fun view(): Pair<RadialTouchView, MutableList<Any>> {
        val d = context.resources.displayMetrics.density
        val events = mutableListOf<Any>()
        val v = RadialTouchView(context).apply {
            centerXdp = 200f; centerYdp = 300f
            tuning = TuningParams(hapticSelection = false, hapticCommit = false)
            onResult = { events += it }
            onAccessibleDirection = { events += it }
            layout(0, 0, (400 * d).toInt(), (800 * d).toInt())
        }
        return v to events
    }
    private fun ours(v: RadialTouchView): List<AccessibilityNodeInfo.AccessibilityAction> {
        val info = AccessibilityNodeInfo.obtain(); v.onInitializeAccessibilityNodeInfo(info)
        return info.actionList.filter { AccessibleDirections.isOurs(it.id) }
    }

    // ---- R5: accessibility snapshot ----
    @Test fun noDirectionActionsBeforeSettingsHaveLoaded() {
        val (v, _) = view()
        assertTrue(ours(v).isEmpty())
    }

    @Test fun actionsCarryLabelsAndDirectionsFromTheCurrentSnapshot() {
        val (v, events) = view()
        val tuning = TuningParams(sectorCount = 5)
        val bindings = listOf<HomeAction>(HomeAction.Semantic(SemanticDestination.PHONE), HomeAction.Search, HomeAction.Apps, HomeAction.Probe(4), HomeAction.Tag("work"))
        v.accessibleDirections = AccessibleDirections.from(bindings, tuning)
        val actions = ours(v)
        assertEquals(5, actions.size)
        assertTrue(actions[0].label.startsWith("Phone, "))
        assertTrue(actions[3].label.startsWith("Unassigned direction 4"))
        assertTrue(v.performAccessibilityAction(actions[1].id, null))
        assertEquals(listOf<Any>(HomeAction.Search), events)
    }

    @Test fun rebindAllocatesFreshIdsAndAStaleIdNeverDispatchesTheNewBinding() {
        val (v, events) = view()
        val tuning = TuningParams(sectorCount = 5)
        val before = AccessibleDirections.from(List(5) { HomeAction.Search }, tuning)
        v.accessibleDirections = before
        val staleId = ours(v)[0].id
        v.accessibleDirections = AccessibleDirections.from(List(5) { HomeAction.Semantic(SemanticDestination.MESSAGES) }, tuning)
        assertTrue(ours(v).none { it.id == staleId })
        assertTrue(v.performAccessibilityAction(staleId, null))
        assertTrue("stale id must not dispatch", events.isEmpty())
    }

    @Test fun accessibleInvocationCancelsAnActivePhysicalGestureFirst() {
        val (v, events) = view()
        val d = context.resources.displayMetrics.density
        v.accessibleDirections = AccessibleDirections.from(List(6) { HomeAction.Apps }, TuningParams())
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 200f * d, 300f * d, 0)
        v.onTouchEvent(down); down.recycle()
        val move = MotionEvent.obtain(0, 40, MotionEvent.ACTION_MOVE, 120f * d, 260f * d, 0)
        v.onTouchEvent(move); move.recycle()
        v.performAccessibilityAction(ours(v)[0].id, null)
        assertEquals(listOf<Any>(GestureResult.Cancelled(CancelReason.SYSTEM), HomeAction.Apps), events)
        val up = MotionEvent.obtain(0, 80, MotionEvent.ACTION_UP, 120f * d, 260f * d, 0)
        v.onTouchEvent(up); up.recycle()
        assertEquals("the cancelled gesture cannot commit afterwards", 2, events.size)
    }

    // ---- persistence (spec §5.2, §5.5, §5.6) ----
    private fun prefs() = context.getSharedPreferences("hardware1", Context.MODE_PRIVATE)

    @Test fun confirmedFreshInstallGetsWorkingDirectionsAndAnywhere() = runBlocking {
        prefs().edit().clear().commit()
        val store = SettingsStore(context)
        withTimeout(3000) { store.loaded.first { it } }
        val s = store.settings.value
        assertEquals(HomeAction.Semantic(SemanticDestination.PHONE), s.bindings[0])
        assertEquals(HomeAction.Semantic(SemanticDestination.MESSAGES), s.bindings[1])
        assertEquals(HomeAction.Semantic(SemanticDestination.BROWSER), s.bindings[2])
        assertEquals(HomeAction.Search, s.bindings[3]); assertEquals(HomeAction.Apps, s.bindings[4])
        assertEquals(HomeAction.Probe(6), s.bindings[5])
        assertTrue(s.tuning.anywhere); assertFalse(s.tuning.debug); assertEquals(6, s.tuning.sectorCount)
        // Defaults are not written until the owner edits or gestures, so an untouched fresh install stays a fresh install.
        store.recordGesture(0, true)
        withTimeout(3000) { while (prefs().getString("settings", null)?.contains("\"semantic\"") != true) delay(10) }
    }

    @Test fun h42SettingsKeepDebugAndMigrateTheHomeCounterOnce() = runBlocking {
        prefs().edit().clear()
            .putString("settings", """{"version":1,"tuning":{"edgeDistance":225.8,"sectorCount":8,"arcSpan":360,"debug":true,"anywhere":true},"bindings":[{"kind":"apps"},{"kind":"search"}]}""")
            .commit()
        val first = SettingsStore(context)
        withTimeout(3000) { first.loaded.first { it } }
        val s = first.settings.value
        assertTrue(s.tuning.debug); assertTrue(s.tuning.anywhere); assertEquals(HomeAction.Apps, s.bindings[0])
        assertEquals(0, s.home.cleanDispatches)
        withTimeout(3000) { while (prefs().getString("settings", null)?.contains("\"home\"") != true) delay(10) }
    }

    @Test fun fluentOwnerIsMigratedPastTheBeginnerHints() = runBlocking {
        val tuning = TuningParams(sectorCount = 6)
        val keys = LocalSettings(tuning = tuning, bindings = List(8) { HomeAction.Search }).familiarityKeys()
        val fam = org.json.JSONArray().apply { keys.forEachIndexed { i, k -> put(org.json.JSONObject().put("key", k).put("level", if (i == 0) 0 else 2).put("streak", if (i == 0) 20 else 0)) } }
        prefs().edit().clear()
            .putString("settings", """{"version":1,"tuning":{"sectorCount":6},"bindings":[${List(8) { """{"kind":"search"}""" }.joinToString(",")}]}""")
            .putString("familiarity", fam.toString()).commit()
        val store = SettingsStore(context)
        withTimeout(3000) { store.loaded.first { it } }
        assertEquals(20, store.settings.value.home.cleanDispatches)
    }

    @Test fun missingDebugKeyNowDefaultsOff() = runBlocking {
        prefs().edit().clear().putString("settings", """{"version":1,"tuning":{"edgeDistance":190},"bindings":[]}""").commit()
        val store = SettingsStore(context)
        withTimeout(3000) { store.loaded.first { it } }
        assertFalse(store.settings.value.tuning.debug)
    }

    @Test fun updateCarriesHomePrefsAndTheCounterCountsWithAdaptationOff() = runBlocking {
        prefs().edit().clear().commit()
        val store = SettingsStore(context)
        withTimeout(3000) { store.loaded.first { it } }
        store.updateHome { it.copy(hints = HomeHints.HIDE, weekNumber = false, appsHintSeen = true, searchAtBottom = false) }
        store.update(tuning = store.settings.value.tuning.copy(adaptive = false))
        store.recordGesture(0, true); store.recordGesture(0, false)
        val s = store.settings.value
        assertEquals(HomeHints.HIDE, s.home.hints); assertEquals(false, s.home.weekNumber); assertTrue(s.home.appsHintSeen)
        assertEquals(1, s.home.cleanDispatches)
        assertEquals(0, s.familiarity[0].streak)
        withTimeout(3000) { while (prefs().getString("settings", null)?.let { it.contains("\"hide\"") && it.contains("\"cleanDispatches\":1") } != true) delay(10) }
        val restored = SettingsStore(context)
        withTimeout(3000) { restored.loaded.first { it } }
        assertEquals(HomeHints.HIDE, restored.settings.value.home.hints); assertEquals(1, restored.settings.value.home.cleanDispatches)
        assertFalse(restored.settings.value.home.searchAtBottom)
    }

    // ---- profile identity, Apps filter, week number ----
    @Test fun packageLabelsAreProfileQualified() {
        assertNotEquals(packageProfileKey("com.example.mail", 0), packageProfileKey("com.example.mail", 10))
    }

    @Test fun appsFilterReusesSearchTagRules() {
        val names = listOf("work", "worship", "read")
        assertEquals("work", appsFilter("#work ", names).tag)
        assertEquals("read", appsFilter("#re notes", names).tag)
        assertEquals("notes", appsFilter("#re notes", names).text)
        assertEquals(listOf("work", "worship"), appsFilter("#wor", names).ambiguous)
        assertNull(appsFilter("mail", names).tag)
        assertEquals("Å", sectionLetter("ålesund")); assertEquals("#", sectionLetter("1Password"))
    }

    @Test fun homeDateDropsTheYearSafely() {
        assertEquals("EEEE d. MMMM", no.heimflyt.launcher.ui.home.withoutYear("EEEE d. MMMM y"))
        assertEquals("EEEE, MMMM d", no.heimflyt.launcher.ui.home.withoutYear("EEEE, MMMM d, y"))
        assertEquals("EEEE, d. MMMM", no.heimflyt.launcher.ui.home.withoutYear("EEEE, d. MMMM y"))
        assertEquals("EEEE d MMMM", no.heimflyt.launcher.ui.home.withoutYear("EEEE d MMMM y"))
        assertEquals("MMMM d, EEEE", no.heimflyt.launcher.ui.home.withoutYear("y MMMM d, EEEE"))
        assertEquals("EEEE 'y' d MMMM", no.heimflyt.launcher.ui.home.withoutYear("EEEE 'y' d MMMM yyyy"))
        assertNull(no.heimflyt.launcher.ui.home.withoutYear("y年M月d日EEEE"))
        val nb = Locale("nb", "NO")
        val text = no.heimflyt.launcher.ui.home.HomeDate.date(java.util.GregorianCalendar(2026, 8, 28).time, nb, true)
        assertFalse(text, text.contains("2026"))
        assertTrue(text, text.contains("28") && text.endsWith("uke 40"))
    }

    @Test fun weekNumberFollowsTheLocale() {
        val nb = Locale("nb", "NO")
        assertTrue(weekNumberDefault(nb)); assertFalse(weekNumberDefault(Locale.US))
        assertEquals(39, weekNumber(java.util.GregorianCalendar(2026, 8, 27).time, nb))
        assertEquals(40, weekNumber(java.util.GregorianCalendar(2026, 8, 28).time, nb))
        assertEquals("uke 39", weekLabel(39, nb)); assertEquals("wk 39", weekLabel(39, Locale.UK))
    }
}
