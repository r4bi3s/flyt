package no.heimflyt.launcher

import android.app.Application
import android.content.Context
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.heimflyt.launcher.gesture.Familiarity
import no.heimflyt.launcher.gesture.Fan
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class SettingsStoreTest {
    @Test fun hardware1GeometryAndAppBindingsSurviveUpgrade() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("hardware1",Context.MODE_PRIVATE).edit().putString("settings",
            """{"version":1,"tuning":{"edgeDistance":190,"arcStart":270,"arcSpan":360,"sectorCount":8},"bindings":[{"kind":"app","component":"example.app/.Main","serial":0,"label":"Example"},{"kind":"semantic","destination":"future-unknown"}]}""").commit()
        val store=SettingsStore(app)
        val value=withTimeout(3000) { store.settings.first { it.tuning.edgeDistance==190f } }
        assertEquals(360f,value.tuning.arcSpan)
        assertEquals(270f,value.tuning.arcStart)
        assertEquals(8,value.tuning.sectorCount)
        assertEquals(HomeAction.App("example.app/.Main",0,"Example"),value.bindings[0])
        assertEquals(HomeAction.Probe(2),value.bindings[1])
    }

    @Test fun searchWordDefaultsOnAndRoundTrips() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("hardware1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // Settings written before H6.1 (no "searchWord") keep the word.
        prefs.edit().putString("settings","""{"version":1,"home":{"searchAtBottom":true}}""").commit()
        val store=SettingsStore(app)
        withTimeout(3000) { store.loaded.first { it } }
        assertTrue(store.settings.value.home.searchWord)
        store.updateHome { it.copy(searchWord=false) }
        withTimeout(3000) { while(prefs.getString("settings","")!!.contains("\"searchWord\":false").not()) delay(10) }
        val restored=SettingsStore(app)
        assertFalse(withTimeout(3000) { restored.settings.first { !it.home.searchWord } }.home.searchWord)
    }

    @Test fun semanticBindingsAndHiddenSlotsRoundTrip() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("hardware1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=SettingsStore(app)
        val bindings=List(8) { i -> HomeAction.Semantic(SemanticDestination.entries[i%3]) }
        store.update(tuning=no.heimflyt.launcher.gesture.TuningParams(sectorCount=5),bindings=bindings)
        withTimeout(3000) { while(prefs.getString("settings",null)==null) delay(10) }
        val restored=SettingsStore(app)
        val value=withTimeout(3000) { restored.settings.first { it.bindings[0] is HomeAction.Semantic } }
        assertEquals(5,value.tuning.sectorCount)
        assertEquals(bindings,value.bindings)
    }

    @Test fun tagSearchBindingRoundTripsAndGetsItsOwnFamiliarityIdentity() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("hardware1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=SettingsStore(app)
        store.update(bindings=store.settings.value.bindings.toMutableList().also { it[0]=HomeAction.Tag("agents") })
        withTimeout(3000) { while(prefs.getString("settings",null)?.contains("\"kind\":\"tag\"")!=true) delay(10) }
        val restored=SettingsStore(app)
        withTimeout(3000) { restored.settings.first { it.bindings[0] is HomeAction.Tag } }
        assertEquals(HomeAction.Tag("agents"),restored.settings.value.bindings[0])
        assertEquals("tag:agents",restored.settings.value.bindings[0].key())
    }

    @Test fun corruptStorageRecoversWithoutCrashingHome() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("hardware1",Context.MODE_PRIVATE).edit().putString("settings","{broken").commit()
        val store=SettingsStore(app)
        val value=withTimeout(3000) { store.settings.first { it.notice!=null } }
        assertEquals(6,value.tuning.sectorCount)
        assertEquals(8,value.bindings.size)
        assertTrue(value.bindings.all { it is HomeAction.Probe })
    }

    @Test fun hardware2SettingsUpgradeToFixedWithProgressiveInvisibility() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("hardware1",Context.MODE_PRIVATE).edit().clear().putString("settings",
            """{"version":1,"tuning":{"edgeDistance":234.7,"arcSpan":360,"sectorCount":8,"initialGuidance":2},"bindings":[]}""").commit()
        val value=withTimeout(3000) { SettingsStore(app).settings.first { it.tuning.edgeDistance>234f } }
        assertFalse(value.tuning.anywhere); assertTrue(value.tuning.adaptive)
        assertEquals(8,value.familiarity.size); assertTrue(value.familiarity.all { it.level==Familiarity.FULL && it.streak==0 })
    }

    @Test fun familiarityPersistsAndResetsOnRebindOrGeometryChange() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("hardware1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store=SettingsStore(app)
        withTimeout(3000) { while(true) { store.recordGesture(0,true); if(store.settings.value.familiarity[0].streak>0) break; delay(10) } }
        repeat(4) { store.recordGesture(0,true); store.recordGesture(1,true) }
        assertEquals(Familiarity.HINTED,store.settings.value.familiarity[0].level)
        withTimeout(3000) { while(prefs.getString("familiarity",null)?.contains("\"streak\":5")!=true) delay(10) }
        val restored=SettingsStore(app)
        withTimeout(3000) { restored.settings.first { it.familiarity[0].streak==5 } }
        // Moving the origin or switching to Anywhere keeps the learned direction.
        restored.update(tuning=restored.settings.value.tuning.copy(anywhere=true,edgeDistance=200f))
        assertEquals(5,restored.settings.value.familiarity[0].streak)
        // H5 WP14: a fresh install binds slot 1 to Phone, so rebind to a different destination to exercise a real rebind.
        restored.update(bindings=restored.settings.value.bindings.toMutableList().also { it[0]=HomeAction.Semantic(SemanticDestination.BROWSER) })
        assertEquals(0,restored.settings.value.familiarity[0].streak)
        assertEquals(4,restored.settings.value.familiarity[1].streak)
        restored.update(tuning=restored.settings.value.tuning.copy(sectorCount=8))
        assertEquals(0,restored.settings.value.familiarity[1].streak)
        restored.recordGesture(2,false); restored.resetFamiliarity()
        assertTrue(restored.settings.value.familiarity.all { it.streak==0 && it.level==Familiarity.FULL })
    }

    @Test fun disabledAdaptationRecordsNothing() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        app.getSharedPreferences("hardware1",Context.MODE_PRIVATE).edit().clear().commit()
        val store=SettingsStore(app)
        store.update(tuning=no.heimflyt.launcher.gesture.TuningParams(adaptive=false))
        delay(200); repeat(10) { store.recordGesture(0,true) }
        assertEquals(0,store.settings.value.familiarity[0].streak)
    }

    @Test fun h7ExperimentDefaultsOffLeavesTheBlobUntouchedAndRoundTrips() = runBlocking {
        val app=RuntimeEnvironment.getApplication()
        val prefs=app.getSharedPreferences("hardware1",Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        // "h7Preset" is the Near/Far key an earlier H7.1 build wrote: ignored, and dropped by the next write.
        prefs.edit().putString("settings","""{"version":1,"tuning":{},"home":{"searchWord":false,"h7Preset":"NEAR"}}""").commit()
        val store=SettingsStore(app)
        withTimeout(3000) { store.loaded.first { it } }
        assertFalse(store.settings.value.home.h7); assertTrue(store.settings.value.home.h7Guide)
        assertEquals(Fan(),store.settings.value.home.h7Fan)
        assertFalse(store.settings.value.home.h7Launch); assertTrue(store.settings.value.home.appsOnThumb)
        // Any other write while the experiment is untouched adds no H7 key.
        store.updateHome { it.copy(searchWord=true) }
        withTimeout(3000) { while(prefs.getString("settings","")!!.contains("\"searchWord\":true").not()) delay(10) }
        assertFalse(prefs.getString("settings","")!!.contains("h7")); assertFalse(prefs.getString("settings","")!!.contains("appsOnThumb"))
        val fan=Fan(size=24f,beyond=95f,spread=40f)
        store.updateHome { it.copy(h7=true,h7Guide=false,h7Fan=fan,h7Launch=true,appsOnThumb=false) }
        withTimeout(3000) { while(prefs.getString("settings","")!!.contains("\"h7\":true").not()) delay(10) }
        val restored=SettingsStore(app)
        val home=withTimeout(3000) { restored.settings.first { it.home.h7 } }.home
        assertFalse(home.h7Guide)
        assertEquals(fan,home.h7Fan)
        assertTrue(home.h7Launch); assertFalse(home.appsOnThumb)
        // A tuning or binding edit keeps the experiment's switches.
        restored.update(tuning=restored.settings.value.tuning.copy(sectorCount=8))
        assertTrue(restored.settings.value.home.h7)
        assertEquals(fan,restored.settings.value.home.h7Fan)
    }
}
