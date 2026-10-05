package no.heimflyt.launcher

import no.heimflyt.launcher.ui.browse.BrowseModel
import org.junit.Assert.*
import org.junit.Test

/**
 * H6.1 review: repository authority. A refresh is authoritative only if every profile LauncherApps listed was enumerated
 * while available; the persisted Browse cells may change only then. Profiles are faked as strings: `serial` is the
 * UserManager serial (-1 = unknown profile), `available` is "unlocked and not paused", `activities` is getActivityList.
 */
class AppRepositoryScanTest {
    private class Fake(val serials: Map<String, Long>, val apps: Map<String, List<String>>, var unavailable: Set<String> = emptySet(),
        val failing: Map<String, RuntimeException> = emptyMap(), val lockDuringCall: Set<String> = emptySet()) {
        fun scan(profiles: List<String>) = scanProfiles(profiles, { serials.getValue(it) }, { it !in unavailable }, { p ->
            failing[p]?.let { throw it }
            if (p in lockDuringCall) unavailable = unavailable + p
            apps[p].orEmpty()
        })
    }
    private fun catalogue(scan: ProfileScan<String>) = AppCatalog(emptyList(), loaded = true, warning = scan.warning, complete = scan.complete)

    private val personalAndWork = Fake(mapOf("personal" to 0L, "work" to 10L),
        mapOf("personal" to listOf("claude", "chatgpt"), "work" to listOf("teams")))

    @Test fun normalEnumerationIsAuthoritative() {
        val scan = personalAndWork.scan(listOf("personal", "work"))
        assertTrue(scan.complete); assertNull(scan.warning)
        assertEquals(listOf("claude" to 0L, "chatgpt" to 0L, "teams" to 10L), scan.entries)
        assertTrue(catalogue(scan).authoritative)
    }

    @Test fun invalidSerialIsNotAuthoritative() {
        val fake = Fake(mapOf("personal" to 0L, "gone" to -1L), mapOf("personal" to listOf("claude"), "gone" to listOf("x")))
        val scan = fake.scan(listOf("personal", "gone"))
        assertFalse(scan.complete); assertNull(scan.warning) // silent, as before, but not proof
        assertEquals(listOf("claude" to 0L), scan.entries)
        assertFalse(catalogue(scan).authoritative)
    }

    @Test fun unavailableOrFailingProfilesAreNotAuthoritative() {
        // Paused (quiet mode) or locked: LauncherApps would answer an empty list; the scan must not accept it.
        val paused = Fake(personalAndWork.serials, personalAndWork.apps, unavailable = setOf("work"))
        assertFalse(paused.scan(listOf("personal", "work")).let { catalogue(it).authoritative })
        // Locked while being listed.
        val racing = Fake(personalAndWork.serials, personalAndWork.apps, lockDuringCall = setOf("work"))
        racing.scan(listOf("personal", "work")).let { assertFalse(it.complete); assertEquals(listOf("claude" to 0L, "chatgpt" to 0L), it.entries) }
        // Enumeration failures keep their visible warnings and are not authoritative.
        val denied = Fake(personalAndWork.serials, personalAndWork.apps, failing = mapOf("work" to SecurityException()))
        denied.scan(listOf("personal", "work")).let { assertEquals("A profile is unavailable.", it.warning); assertFalse(catalogue(it).authoritative) }
        val locked = Fake(personalAndWork.serials, personalAndWork.apps, failing = mapOf("work" to IllegalStateException()))
        locked.scan(listOf("personal", "work")).let { assertEquals("Unlock the profile to see its apps.", it.warning); assertFalse(catalogue(it).authoritative) }
    }

    @Test fun aLaterFullRefreshIsAuthoritativeAgain() {
        val fake = Fake(personalAndWork.serials, personalAndWork.apps, unavailable = setOf("work"))
        assertFalse(catalogue(fake.scan(listOf("personal", "work"))).authoritative)
        fake.unavailable = emptySet()
        assertTrue(catalogue(fake.scan(listOf("personal", "work"))).authoritative)
    }

    @Test fun anAvailableEmptyProfileIsAGenuineEmptyResult() {
        val fake = Fake(mapOf("personal" to 0L, "work" to 10L), mapOf("personal" to listOf("claude"), "work" to emptyList()))
        val scan = fake.scan(listOf("personal", "work"))
        assertTrue(scan.complete)
        assertTrue(catalogue(scan).authoritative)
    }

    @Test fun authorityGatesPersistedCells() {
        val stored = listOf("claude@0", "chatgpt@0", "teams@10")
        // Work paused: its app is shown as a hole, but nothing is stored.
        val paused = Fake(personalAndWork.serials, personalAndWork.apps, unavailable = setOf("work")).scan(listOf("personal", "work"))
        val shown = BrowseModel.stableCells(stored, paused.entries.map { (a, s) -> "$a@$s" })
        assertEquals(listOf("claude@0", "chatgpt@0"), shown)
        assertNull(BrowseModel.cellsToPersist(catalogue(paused).authoritative, stored, shown))
        // A full refresh where Teams really is gone: the removal is stored.
        val gone = Fake(personalAndWork.serials, mapOf("personal" to listOf("claude", "chatgpt"), "work" to emptyList())).scan(listOf("personal", "work"))
        val next = BrowseModel.stableCells(stored, gone.entries.map { (a, s) -> "$a@$s" })
        assertEquals(listOf("claude@0", "chatgpt@0"), BrowseModel.cellsToPersist(catalogue(gone).authoritative, stored, next))
    }
}
