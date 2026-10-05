package no.heimflyt.launcher.ui.home

import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelLayoutTest {
    private val labels = listOf("Phone", "Messages", "Browser", "Search", "Apps", "#work", "Ålesund Kommunebibliotek", "Ž")

    /** Approximate text width in dp for a sans label at [fontScale] (the renderer measures real text; this bounds it). */
    private fun textWidth(text: String, fontScale: Float) = text.length * 8.2f * fontScale

    private fun sizes(n: Int, fontScale: Float, full: Boolean) = List(n) { i ->
        val text = LabelMetrics.displayText(labels[i % labels.size])
        if (full) LabelSize(LabelMetrics.named(textWidth(text, fontScale), true), LabelMetrics.iconOnly(), LabelMetrics.height(fontScale))
        else LabelSize(LabelMetrics.number(9f * fontScale), 0f, LabelMetrics.height(fontScale))
    }

    private fun check(width: Float, height: Float, p: TuningParams, fontScale: Float) {
        val safe = p.safe()
        val n = safe.sectorCount
        val bounds = LRect(12f, 12f, width - 12f, height - 12f)
        val inset = RadialGeometry.anywhereInset(safe)
        val xs = listOf(inset, width / 2, width - inset); val ys = listOf(inset, height / 2, height - inset)
        val angles = List(n) { RadialGeometry.sectorAngle(it, safe) }
        for (ox in xs) for (oy in ys) for (full in listOf(true, false)) for (sel in listOf<Int?>(null) + (0 until n)) {
            val sz = sizes(n, fontScale, full)
            val a = LabelLayout.layout(ox, oy, bounds, angles, safe.menuRadius, safe.deadZone, sz, List(n) { true }, sel)
            val b = LabelLayout.layout(ox, oy, bounds, angles, safe.menuRadius, safe.deadZone, sz, List(n) { true }, sel)
            val ctx = "screen ${width}x$height origin ($ox,$oy) n=$n span=${safe.arcSpan} left=${safe.leftHanded} r=${safe.menuRadius} dz=${safe.deadZone} fs=$fontScale sel=$sel"
            assertEquals("deterministic $ctx", a, b)
            val kept = a.filterNotNull()
            kept.forEach { l ->
                assertTrue("in bounds $ctx ${l.rect}", l.rect.inside(bounds))
                assertTrue("outside cancel zone $ctx ${l.rect}", !l.rect.hitsCircle(ox, oy, safe.deadZone + LabelLayout.CANCEL_EXTRA))
            }
            for (i in kept.indices) for (j in i + 1 until kept.size)
                assertTrue("disjoint $ctx ${kept[i].rect} ${kept[j].rect}", !kept[i].rect.intersects(kept[j].rect, 0f))
            if (sel != null) {
                val s = a[sel]
                assertNotNull("selected placed $ctx", s)
                assertEquals("selected named $ctx", LabelKind.NAMED, s!!.kind)
            }
        }
    }

    @Test fun invariantsHoldAcrossTheSupportedMatrix() {
        for (n in 5..8) for (span in listOf(180f, 360f)) for (left in listOf(false, true))
            for ((radius, dead) in listOf(60f to 8f, 100f to 24f, 180f to 72f, 180f to 8f, 60f to 72f))
                for (fs in listOf(1.0f, 1.3f, 2.0f)) {
                    val p = TuningParams(sectorCount = n, arcSpan = span, leftHanded = left, menuRadius = radius, deadZone = dead, anywhere = true)
                    check(412f, 860f, p, fs)
                }
    }

    @Test fun selectedLabelIsPlaceableOnTheSmallestSupportedScreen() {
        for (n in 5..8) for (span in listOf(180f, 360f)) for (fs in listOf(1.0f, 1.3f, 2.0f))
            check(320f, 480f, TuningParams(sectorCount = n, arcSpan = span, menuRadius = 180f, deadZone = 72f, anywhere = true), fs)
    }

    @Test fun crowdedLabelsDegradeToIconsOrOmissionNeverOverlap() {
        val p = TuningParams(sectorCount = 8, arcSpan = 180f, menuRadius = 60f, deadZone = 24f).safe()
        val angles = List(8) { RadialGeometry.sectorAngle(it, p) }
        val sz = List(8) { LabelSize(LabelMetrics.named(160f, true), LabelMetrics.iconOnly(), LabelMetrics.height(2f)) }
        val placed = LabelLayout.layout(200f, 400f, LRect(12f, 12f, 400f, 848f), angles, p.menuRadius, p.deadZone, sz, List(8) { true }, 3)
        assertEquals(LabelKind.NAMED, placed[3]!!.kind)
        assertTrue("something had to degrade", placed.any { it == null || it.kind == LabelKind.ICON })
    }

    @Test fun displayTextEllipsizesLongUnicodeLabels() {
        val long = "Ålesund Kommunebibliotek og Kulturhus"
        val shown = LabelMetrics.displayText(long)
        assertEquals(LabelMetrics.MAX_CHARS, shown.length)
        assertTrue(shown.endsWith("…"))
        assertEquals("Ž", LabelMetrics.displayText("Ž"))
    }

    @Test fun homeLearningRuleAndMigration() {
        assertTrue(homeLearning(0, HomeHints.AUTO)); assertTrue(homeLearning(9, HomeHints.AUTO))
        assertTrue(!homeLearning(10, HomeHints.AUTO)); assertTrue(homeLearning(500, HomeHints.SHOW)); assertTrue(!homeLearning(0, HomeHints.HIDE))
        assertEquals(0, migratedCleanDispatches(listOf(0, 0), false))
        assertEquals(7, migratedCleanDispatches(listOf(3, 4), false))
        assertEquals(10, migratedCleanDispatches(listOf(1), true))
        assertEquals(9999, migratedCleanDispatches(listOf(9999, 9999), false))
    }
}
