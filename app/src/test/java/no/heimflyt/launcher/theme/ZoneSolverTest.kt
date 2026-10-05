package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.image.HomeZones
import no.heimflyt.launcher.theme.image.ZoneKind
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.image.ZoneSolver
import no.heimflyt.launcher.theme.palette.Contrast
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.store.Strength
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** R3 composite tests: the zone solver on hostile images, both modes, all strengths, font scales 1.0 and 2.0 (spec §10.1). */
class ZoneSolverTest {
    private val dark = TokenMapper.map(FallbackPalette.tokyoNight)
    private val light = TokenMapper.map(OmarchyPalette(mapOf("background" to 0xfffcf0, "foreground" to 0x100f0f, "lighter_background" to 0xf2f0e5,
        "accent" to 0x205ea6, "selection" to 0xe6e4d9, "red" to 0xaf3029, "green" to 0x66800b, "yellow" to 0xad8301), true))
    private val w = 216; private val h = 485   // a 1080×2424 phone at 0.2× (the solver is size-agnostic)

    private fun sig(fontScale: Float) = ZoneSignature(fontScale, 2.625f, 1080, 2424, 0, 142, 0, 126, 142, 63)

    private fun images(): Map<String, IntArray> {
        val rnd = Random(7)
        return mapOf(
            "uniform white" to IntArray(w * h) { 0xffffff },
            "uniform black" to IntArray(w * h) { 0x000000 },
            "uniform mid" to IntArray(w * h) { 0x777777 },
            "checkerboard" to IntArray(w * h) { i -> if ((i % w + i / w) % 2 == 0) 0xffffff else 0x000000 },
            "tiny bright patches" to IntArray(w * h) { i -> if (i % 97 == 0) 0xffffff else 0x101010 },
            "tiny dark patches" to IntArray(w * h) { i -> if (i % 89 == 0) 0x000000 else 0xf4f4f4 },
            "mixed photo" to IntArray(w * h) { i -> val y = i / w; val base = if (y < h / 3) 0xd8e6f5 else if (y < 2 * h / 3) 0x4a7a3a else 0x6b4a2e
                val n = rnd.nextInt(-40, 40)
                val r = ((base shr 16 and 0xff) + n).coerceIn(0, 255); val g = ((base shr 8 and 0xff) + n).coerceIn(0, 255); val b = ((base and 0xff) + n).coerceIn(0, 255)
                (r shl 16) or (g shl 8) or b },
            "noise" to IntArray(w * h) { rnd.nextInt(0x1000000) },
        )
    }

    private fun run(t: ResolvedColors, img: IntArray, fontScale: Float, strength: Strength?) {
        val px = img.copyOf()
        if (strength != null) for (i in px.indices) px[i] = ZoneSolver.composite(px[i], t.scrim, strength.alpha)
        val zones = HomeZones.compute(sig(fontScale), t, w, h)
        val solution = ZoneSolver.solve(px, w, h, zones, t.scrim)
        ZoneSolver.bake(px, solution, t.scrim)
        val p = ZoneSolver.protection(px, w, solution, t.isLight)
        // Solver v5 (owner decision): Android's bar zones are verified or banded; Heimflyt's own text zones are never shaded
        // (their words carry a glyph shadow on Home), so no box and no shading appears around them.
        for (b in solution.bands) {
            val z = b.zone
            val ok = b.alpha != null && ZoneSolver.passes(px, w, z, b.fg, 0, 0.0, z.min)
            when (z.kind) {
                ZoneKind.STATUS -> assertTrue("status band missing", ok || p.statusBand)
                ZoneKind.NAV -> assertTrue("nav band missing", ok || p.navBand)
                else -> Unit
            }
            if (ok && (z.kind == ZoneKind.STATUS || z.kind == ZoneKind.NAV)) for (y in z.top until z.bottom) for (x in z.left until z.right)
                assertTrue(Contrast.ratio(b.fg, px[y * w + x]) >= z.min - 1e-6)
        }
        assertFalse(p.clockBackdrop || p.cornerBackdrop)
        // The middle of the screen (between the bars' feathered zones) is untouched.
        val mid = h / 2
        for (x in 0 until w) assertEquals(0f, solution.mask[mid * w + x])
        // A bar appearance is only claimed for what was verified; otherwise it follows ground.
        if (p.statusBand) assertEquals(t.isLight, p.statusLight)
    }

    @Test fun everyZoneIsVerifiedOrProtectedOnHostileImages() {
        for (t in listOf(dark, light)) for ((_, img) in images()) for (fs in listOf(1.0f, 2.0f)) for (s in listOf(null) + Strength.entries) run(t, img, fs, s)
    }

    /** Owner feedback: no opaque boxes behind the clock or corner words, and (v5) no shading behind them either. */
    @Test fun hostileImagesGetBandsNotBoxes() {
        for (t in listOf(dark, light)) for ((name, img) in images()) for (fs in listOf(1.0f, 2.0f)) {
            val px = img.copyOf()
            val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(sig(fs), t, w, h), t.scrim)
            ZoneSolver.bake(px, solution, t.scrim)
            val p = ZoneSolver.protection(px, w, solution, t.isLight)
            assertFalse("$name fs=$fs light=${t.isLight}: clock box", p.clockBackdrop)
            assertFalse("$name fs=$fs light=${t.isLight}: corner box", p.cornerBackdrop)
            for (b in solution.bands.filter { it.zone.kind == ZoneKind.CLOCK || it.zone.kind == ZoneKind.CORNER })
                assertEquals("no shading inside ${b.zone.kind}", 0f, solution.mask[((b.zone.top + b.zone.bottom) / 2) * w + (b.zone.left + b.zone.right) / 2])
        }
    }

    @Test fun calmImagesNeedNoBackdrops() {
        val px = IntArray(w * h) { dark.ground }
        val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(sig(1f), dark, w, h), dark.scrim)
        assertTrue(solution.bands.all { it.alpha == 0.0 }); assertTrue(solution.mask.all { it == 0f })
        val p = ZoneSolver.protection(px, w, solution, false)
        assertFalse(p.clockBackdrop || p.cornerBackdrop || p.statusBand || p.navBand || p.statusTextBackdrop)
        assertFalse("dark ground → white icons", p.statusLight)
    }

    @Test fun brightSkyBehindTheClockIsMeasuredButNeverShaded() {
        val px = IntArray(w * h) { i -> if (i / w < h / 3) 0xe8f0ff else 0x203020 }
        val solution = ZoneSolver.solve(px, w, h, HomeZones.compute(sig(1f), dark, w, h), dark.scrim)
        val clock = solution.bands.first { it.zone.kind == ZoneKind.CLOCK }
        assertTrue("measured need ${clock.alpha}", clock.alpha != null && clock.alpha!! > 0.0)
        assertEquals(0f, solution.mask[((clock.zone.top + clock.zone.bottom) / 2) * w + (clock.zone.left + clock.zone.right) / 2])
        ZoneSolver.bake(px, solution, dark.scrim)
        assertFalse(ZoneSolver.protection(px, w, solution, false).clockBackdrop)
    }

    @Test fun zonesGrowWithFontScaleAndStayInsideTheImage() {
        val small = HomeZones.compute(sig(1f), dark, w, h); val big = HomeZones.compute(sig(2f), dark, w, h)
        fun height(z: List<no.heimflyt.launcher.theme.image.Zone>, k: ZoneKind) = z.filter { it.kind == k }.sumOf { it.bottom - it.top }
        assertTrue(height(big, ZoneKind.CLOCK) > height(small, ZoneKind.CLOCK))
        for (z in big) assertTrue(z.left >= 0 && z.top >= 0 && z.right <= w && z.bottom <= h)
        assertEquals(setOf(ZoneKind.STATUS, ZoneKind.STATUS_TEXT, ZoneKind.CLOCK, ZoneKind.DATE, ZoneKind.CORNER, ZoneKind.NAV), big.map { it.kind }.toSet())
    }

    @Test fun signatureAndProtectionRoundTrip() {
        val s = sig(1.3f)
        assertEquals(s, ZoneSignature.decode(s.encode()))
        val p = no.heimflyt.launcher.theme.image.Protection(true, false, true, false, false, true, false)
        assertEquals(p, no.heimflyt.launcher.theme.image.Protection.decode(p.encode()))
        // H5.1 generations (six fields) still decode; their status text keeps a backdrop.
        assertEquals(p.copy(statusTextBackdrop = true), no.heimflyt.launcher.theme.image.Protection.decode("true,false,true,false,false,true"))
    }
}
