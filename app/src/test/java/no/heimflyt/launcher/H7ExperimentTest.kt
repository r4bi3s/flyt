package no.heimflyt.launcher

import no.heimflyt.launcher.gesture.*
import org.junit.Assert.*
import org.junit.Test

class H7ExperimentTest {
    private val tuning = TuningParams(arcStart = 135f, arcSpan = 360f, sectorCount = 8)
    private val fan = Fan(size = 28f, beyond = 90f, spread = 24f)
    private fun group(name: String = "agenter") = HomeAction.Group(name, List(4) { HomeAction.App("p/.A$it", 0, "A$it") }, name)
    private fun settings(on: Boolean, slot: Int = 3, count: Int = 8) = LocalSettings(tuning.copy(sectorCount = count),
        List(8) { if (it == slot) group() else HomeAction.Probe(it + 1) },
        home = HomePrefs(h7 = on, h7Fan = fan))

    private fun kids(name: String, vararg slots: Int): H7.Children {
        val g = group(name)
        return H7.Children(g, slots.associateWith { g.children[it]!! }, emptyMap())
    }

    @Test fun onlyVisibleExplicitGroupsWithPreparedChildrenNest() {
        val children = mapOf(3 to kids("agenter", 0, 1, 2, 3))
        assertNull(H7.config(settings(false), children))
        assertEquals(NestedConfig(mapOf(3 to 0b1111), fan), H7.config(settings(true), children))
        assertNull(H7.config(settings(true, slot = 6, count = 5), mapOf(6 to kids("agenter", 0))))
        assertNull(H7.config(settings(true), emptyMap()))
        assertNull(H7.config(settings(true), mapOf(3 to kids("some", 1))))
        val two = settings(true).let { s -> s.copy(bindings = s.bindings.toMutableList().also { it[5] = group("some") }) }
        assertEquals(mapOf(3 to 0b1111, 5 to 0b0110), H7.config(two, children + (5 to kids("some", 1, 2)))!!.parents)
        // A small tag must never become a group implicitly, even if old prepared children still exist.
        val tag = settings(true).let { s -> s.copy(bindings = s.bindings.toMutableList().also { it[3] = HomeAction.Tag("agenter") }) }
        assertNull(H7.config(tag, children))
        // Replacing a child with the same display label but another component/profile invalidates the old preparation.
        val rebound = settings(true).let { s -> s.copy(bindings = s.bindings.toMutableList().also {
            it[3] = group().copy(children = group().children.toMutableList().also { c -> c[0] = HomeAction.App("other/.A", 10, "A0") })
        }) }
        assertNull(H7.config(rebound, children))
    }

    @Test fun smallTagCellsFillTheInnerPairFirstAndKeepTheirPlace() {
        assertEquals(mapOf(Nest.GROK to "a", Nest.GEMINI to "b"), H7.fanSlots(listOf("a", "b")))
        assertEquals(mapOf(Nest.GROK to "a", Nest.GEMINI to "b", Nest.CLAUDE to "c", Nest.CHATGPT to "d"), H7.fanSlots(listOf("a", "b", "c", "d")))
        // A hole stays empty: nobody moves into it.
        assertEquals(mapOf(Nest.GROK to "a", Nest.CLAUDE to "c"), H7.fanSlots(listOf("a", null, "c")))
    }

    @Test fun onlyASingleExactMatchCanOpen() {
        val apps = listOf("Claude", "claude", "Grok", "Gemini Live", "ChatGPT", "Maps")
        val found = H7.resolve(apps) { it }
        // Claude is ambiguous (two profiles), Gemini has no exact match: neither is shown or opened.
        assertEquals(mapOf(Nest.GROK to "Grok", Nest.CHATGPT to "ChatGPT"), found)
    }

    @Test fun childLaunchNeedsTheSwitchAndNeverScoresPrompts() {
        val on = settings(true)
        assertFalse(H7.launches(on))
        val launch = on.copy(home = on.home.copy(h7Launch = true))
        assertTrue(H7.launches(launch))
        assertFalse(H7.launches(on.copy(home = on.home.copy(h7 = false, h7Launch = true))))
        assertFalse(H7.launches(launch.copy(home = launch.home.copy(h7 = false))))
        H7.prompts = true
        try { assertFalse(H7.launches(launch)) } finally { H7.prompts = false }
    }

    @Test fun promptsOnlyRequestPreparedCellsAndUseConfiguredNames() {
        val children = kids("custom", 2)
        H7.prompts = true
        try {
            assertEquals(2, H7.requested(children))
            assertEquals(-1, H7.requested(kids("empty")))
            val debug = settings(true).copy(tuning = tuning.copy(debug = true))
            assertNull(H7.readout(settings(true), mapOf(3 to children)))
            assertTrue(H7.readout(debug, mapOf(3 to children))!!.contains("custom → A2"))
        } finally { H7.prompts = false }
    }

    @Test fun rehearsalRecordNamesTheIconAndCapturesCorrections() {
        val e = RadialEngine(); e.begin(0f, 0f, 0, tuning, nested = NestedConfig.single(3))
        fun point(angle: Double, r: Double) = (r * Math.cos(Math.toRadians(angle))).toFloat() to
            (r * Math.sin(Math.toRadians(angle))).toFloat()
        val a = point(292.5, 50.0); val b = point(292.5, 62.0)
        e.move(a.first, a.second, 8); e.move(b.first, b.second, 16)
        val icon = Nest.target(Nest.CHATGPT, 0f, 0f, 292.5f, false, Fan(), tuning.menuRadius)
        val result = e.release(icon.first, icon.second, 24)!!
        assertEquals(GestureResult.Selected(3, Nest.CHATGPT), result)
        val record = H7.describe(result, e.frame, e.targets, tuning, Nest.CHATGPT)
        assertEquals("child", record.outcome)
        assertTrue(record.short.startsWith("ChatGPT ✓"))
        assertTrue(record.line.contains("axisOffset="))
        assertTrue(record.line.contains("fan=32/85/30°"))
        assertTrue(record.line.contains("launch=0"))
        assertTrue(H7.describe(result, e.frame, e.targets, tuning, -1, launch = true).line.contains("launch=1"))
        assertTrue(record.line.contains("inwardSwitches="))
    }

    @Test fun recordToleratesUnsetAngles() {
        // Regression: a nested gesture released before any exit angle was set crashed Home ("Cannot round NaN value").
        val e = RadialEngine(); e.begin(0f, 0f, 0, tuning, nested = NestedConfig.single(3))
        val result = e.release(0f, 0f, 24) ?: GestureResult.Tap
        val frame = e.frame.copy(x = Float.NaN, originX = Float.NaN)
        val record = H7.describe(result, frame, e.targets, tuning, Nest.CHATGPT)
        assertTrue(record.line.contains("exit=-°"))
        assertTrue(record.line.contains("origin=-,"))
    }
}
