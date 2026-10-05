package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.palette.Contrast
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.ui.theme.HomeGround
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TokenMapperTest {
    private fun r(a: Int, b: Int) = Contrast.ratio(a, b)
    private fun hex(c: Int) = "#%06x".format(c)

    /** Every rendered pair of VISUAL_SYSTEM.md §2.3 that does not involve imagery, checked on the stored (quantised) values. */
    private fun assertPairs(t: ResolvedColors, name: String) {
        fun at(min: Double, fg: Int, bg: Int, what: String) =
            assertTrue("$name: $what ${hex(fg)} on ${hex(bg)} = ${"%.2f".format(r(fg, bg))} < $min", r(fg, bg) >= min - 1e-9)
        assertTrue("$name: invariant G", r(t.ground, t.endpoint) >= 7.0)
        for (b in listOf(t.raised, t.selection, t.accentVeil)) assertTrue("$name: backdrop step ${hex(b)}", r(b, t.ground) <= TokenMapper.STEP_CAP + 1e-9)
        for (bg in t.textBackdrops) {
            at(4.5, t.ink, bg, "ink"); at(4.5, t.inkStrong, bg, "inkStrong"); at(4.5, t.inkMuted, bg, "inkMuted")
            at(4.5, t.accentInk, bg, "accentInk"); at(4.5, t.dangerInk, bg, "dangerInk"); at(3.0, t.control, bg, "control")
        }
        for (bg in listOf(t.ground, t.raised)) {
            at(3.0, t.accent, bg, "accent"); at(3.0, t.danger, bg, "danger"); at(3.0, t.positive, bg, "positive"); at(3.0, t.caution, bg, "caution")
            t.identity.forEachIndexed { i, id -> at(3.0, id, bg, "identity$i") }
            at(3.0, t.dangerInk, bg, "status glyph")
        }
        at(4.5, t.onAccent, t.accent, "onAccent")
        at(4.5, t.onDanger, t.danger, "onDanger")
        t.identity.forEachIndexed { i, id -> at(4.5, t.onIdentity[i], id, "onIdentity$i") }
        val all = listOf(t.ground, t.raised, t.selection, t.ink, t.inkStrong, t.inkMuted, t.accent, t.accentInk, t.onAccent, t.accentVeil) + t.identity
        assertTrue(all.all { it in 0..0xffffff })
    }

    @Test fun fallbackTokyoNightSatisfiesEveryRenderedPair() {
        val t = TokenMapper.map(FallbackPalette.tokyoNight)
        assertPairs(t, "tokyo-night")
        // The prototype palette survives unchanged where it already passes.
        assertEquals(0x1a1b26, t.ground); assertEquals(0x7aa2f7, t.accent); assertEquals(0xc0caf5, t.inkStrong)
        assertTrue(t.adjustments.isEmpty())
    }

    @Test fun reviewCounterexampleMidGreyIdentityGetsReadableInitials() {
        val p = FallbackPalette.tokyoNight.let { it.copy(colors = it.colors + ("red" to 0x777777)) }
        val t = TokenMapper.map(p)
        assertPairs(t, "mid-grey identity")
        assertTrue(r(t.onIdentity[0], t.identity[0]) >= 4.5)
    }

    @Test fun adversarialPalettesStillMeetEveryGuarantee() {
        val base = FallbackPalette.tokyoNight.colors
        val cases = mapOf(
            "fg 1.5:1" to OmarchyPalette(base + mapOf("background" to 0x333333, "foreground" to 0x4f4f4f, "bright_foreground" to 0x4f4f4f), false),
            "selection == accent" to OmarchyPalette(base + ("selection" to 0x7aa2f7), false),
            "white-like harsh raised" to OmarchyPalette(mapOf("background" to 0xffffff, "foreground" to 0x000000, "lighter_background" to 0xc0c0c0,
                "accent" to 0x6e6e6e, "selection" to 0xc0c0c0, "red" to 0x2a2a2a, "green" to 0x3a3a3a, "yellow" to 0x4a4a4a), true),
            "pale accent light" to OmarchyPalette(mapOf("background" to 0xfaf4ed, "foreground" to 0x575279, "lighter_background" to 0xf2e9e1,
                "accent" to 0xd7e3e8, "selection" to 0xdfdad9), true),
            "no optional keys" to OmarchyPalette(mapOf("background" to 0x202020, "foreground" to 0x909090), false),
        )
        cases.forEach { (name, p) -> assertPairs(TokenMapper.map(p), name) }
    }

    @Test fun groundCorrectionTerminatesForEveryGreyInBothModes() {
        for (g in 0..255) for (light in listOf(false, true)) {
            val grey = (g shl 16) or (g shl 8) or g
            val t = TokenMapper.map(OmarchyPalette(mapOf("background" to grey, "foreground" to if (light) 0x202020 else 0xe0e0e0,
                "accent" to 0x7f7f7f, "red" to 0x808080), light))
            assertPairs(t, "grey $g light=$light")
        }
    }

    /** The compiled Dusk ground: every Home text and bar-icon pair over every point of its gradients. */
    @Test fun homeDuskGroundKeepsHomeTextAndBarsReadable() {
        val t = TokenMapper.map(FallbackPalette.tokyoNight)
        val top = HomeGround.top(t); val glow = HomeGround.glow(t)
        val clockZone = (0..100).map { Contrast.mix(top, t.ground, it / 100.0) }
        val bottomZone = (0..100).map { Contrast.mix(t.ground, glow, it / 100.0) }
        clockZone.forEach {
            assertTrue("clock", r(t.inkStrong, it) >= 3.0); assertTrue("date", r(t.inkMuted, it) >= 4.5)
            assertTrue("status icons", r(Contrast.WHITE, it) >= 3.0)
        }
        bottomZone.forEach {
            assertTrue("corner words", r(t.inkStrong, it) >= 4.5); assertTrue("learning caption", r(t.inkMuted, it) >= 4.5)
            assertTrue("nav icons", r(Contrast.WHITE, it) >= 3.0)
        }
        // The compiled fallback is dark on every route, so every route uses light bar icons.
        assertTrue(!t.isLight)
    }

    @Test fun noLiteralColoursOutsideTheThemeLayer() {
        val root = File("src/main/java/no/heimflyt/launcher")
        val offenders = root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/ui/theme/") || it.path.contains("/theme/palette/") }
            .filter { f -> Regex("""Color\(0x|0x[fF]{2}[0-9a-fA-F]{6}\.toInt\(\)|android\.graphics\.Color\.WHITE""").containsMatchIn(f.readText()) }
            .map { it.path }.toList()
        assertTrue("Literal colours outside the theme layer: $offenders", offenders.isEmpty())
    }
}
