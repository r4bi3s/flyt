package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.palette.AlacrittyPalette
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.FallbackPalette
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.PaletteException
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.palette.violations
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/** WP9: parser, alacritty extraction and resolver against upstream goldens (THEME_ARCHITECTURE.md §3, spec §8.1). */
class PalettePipelineTest {
    private val fixtures = File("src/test/resources/omarchy").listFiles()!!.filter { it.isDirectory }.sortedBy { it.name }

    private fun expected(dir: File): Map<String, String> = File(dir, "expected.tsv").readLines().filter { it.isNotEmpty() }
        .associate { line -> line.substringBefore('\t') to line.substringAfter('\t', "") }

    private fun raw(dir: File): Map<String, String> {
        val colors = File(dir, "colors.toml")
        return if (colors.isFile) ColorsToml.parse(colors.readBytes()).values
        else AlacrittyPalette.extract(File(dir, "alacritty-palette.toml").readBytes())!!
    }

    @Test fun resolverReproducesEveryUpstreamGolden() {
        assertEquals("22 first-party + 10 community + 7 URL-install fixtures", 39, fixtures.size)
        for (dir in fixtures) {
            val resolved = OmarchyResolver.resolve(raw(dir), File(dir, "light.mode").exists())
            val want = expected(dir)
            for ((k, v) in want) assertEquals("${dir.name}: $k", v, resolved[k] ?: "")
            for (k in OmarchyResolver.CANONICAL + "mode") assertTrue("${dir.name}: canonical $k missing from golden", k in want)
        }
    }

    @Test fun everyFixtureMapsToTokensMeetingEveryRenderedPair() {
        for (dir in fixtures) {
            val (result, source) = OmarchyResolver.fromFiles(File(dir, "colors.toml").takeIf { it.isFile }?.readBytes(),
                File(dir, "alacritty-palette.toml").takeIf { it.isFile }?.readBytes(), File(dir, "light.mode").exists())
            val tokens = TokenMapper.map(result.palette)
            assertEquals("${dir.name}: ${tokens.violations()}", emptyList<String>(), tokens.violations())
            assertEquals(!File(dir, "colors.toml").isFile, source == PaletteSource.ALACRITTY_TOML)
        }
    }

    @Test fun bundledPalettesAreTheUpstreamFilesAndPassEveryPair() {
        val assets = File("src/main/assets/themes")
        val bundled = listOf("tokyo-night", "matte-black", "gruvbox", "everforest", "kanagawa", "flexoki-light", "catppuccin-latte")
        for (name in bundled) {
            val bytes = File(assets, "$name/colors.toml").readBytes()
            assertTrue("$name is copied unmodified", bytes.contentEquals(File("src/test/resources/omarchy/$name/colors.toml").readBytes()))
            val p = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(bytes).values, false)).palette
            assertEquals(name, emptyList<String>(), TokenMapper.map(p).violations())
        }
        // Variety for the owner test: two light themes among seven.
        assertEquals(2, bundled.count { OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(File(assets, "$it/colors.toml").readBytes()).values, false)).palette.light })
        assertTrue(File("src/main/assets/licenses").listFiles()!!.size >= 7)
    }

    @Test fun kretsShippedPaletteMatchesCompiledFallbackAndPassesContrast() {
        val bytes = File("src/main/assets/themes/krets/colors.toml").readBytes()
        val palette = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(bytes).values, false)).palette
        assertEquals(FallbackPalette.krets.colors, FallbackPalette.krets.colors.mapValues { (key, _) -> palette[key] })
        assertEquals(emptyList<String>(), TokenMapper.map(palette).violations())
        assertEquals("bundled:krets", no.heimflyt.launcher.theme.store.ThemeStore.FALLBACK_ID)
    }

    @Test fun blaatimeIsTheDefaultAndItsPaletteAndImagesAreShipped() {
        val bytes = File("src/main/assets/themes/blaatime/colors.toml").readBytes()
        val palette = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(bytes).values, false)).palette
        assertEquals(emptyList<String>(), TokenMapper.map(palette).violations())
        val theme = no.heimflyt.launcher.theme.BundledThemes.find(no.heimflyt.launcher.theme.BundledThemes.DEFAULT_ID)!!
        assertEquals("blaatime", theme.slug)
        assertEquals(4, theme.images.size)
        for (t in no.heimflyt.launcher.theme.BundledThemes.all) for (img in t.images) {
            val file = File("src/main/assets", img.file)
            val sha = java.security.MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
            assertEquals(img.file, img.sha256, sha)
        }
    }

    @Test fun normalisedPaletteRoundTrips() {
        for (dir in fixtures) {
            val p = OmarchyResolver.palette(OmarchyResolver.resolve(raw(dir), File(dir, "light.mode").exists())).palette
            val again = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(ColorsToml.write(p).toByteArray()).values, false)).palette
            assertEquals(dir.name, p, again)
        }
    }

    // ---- parser hostility (spec §8.1) ----

    private fun parse(text: String) = ColorsToml.parse(text.toByteArray())

    @Test fun oversizeNulAndInvalidUtf8RejectTheFile() {
        for (bad in listOf(ByteArray(64 * 1024 + 1) { 'a'.code.toByte() }, "background = \"#000000\"\u0000".toByteArray(), byteArrayOf(0x62, 0xC3.toByte(), 0x28))) {
            try { ColorsToml.parse(bad); fail("accepted") } catch (_: PaletteException) {}
        }
    }

    @Test fun onlyTheFirst512LinesCount() {
        val text = (1..10_000).joinToString("\n") { "#" } + "\nbackground = \"#000000\""
        assertNull(parse(text).values["background"])
        assertEquals("#123456", parse((1..511).joinToString("\n") { "#" } + "\nbackground = \"#123456\"").values["background"])
    }

    @Test fun keysValuesQuotesAndComments() {
        val v = parse("""
            'background' = "#1A1B26" # inline comment
            foreground=#c0caf5
            bad key! = "#ffffff"
            accent = "rgba(1,2,3,0.5)"
            red = "#ff0000"   ; drop table
            selection = "#aabbccdd"
            green = "#00ff00"
            green = "#00aa00"
            multi = ""${'"'}#ffffff
            arr = ["#fff"]
            evil = "${'$'}(rm -rf /)"
        """.trimIndent()).values
        assertEquals("#1A1B26", v["background"]); assertEquals("#c0caf5", v["foreground"]); assertEquals("rgba(1,2,3,0.5)", v["accent"])
        assertEquals("#ff0000", v["red"]); assertEquals("#00aa00", v["green"]) // last wins
        assertFalse("badkey!" in v); assertFalse("multi" in v); assertFalse("arr" in v); assertFalse("evil" in v)
        val p = OmarchyResolver.palette(OmarchyResolver.resolve(v, false))
        assertEquals(0xaabbcc, p.palette["selection"]); assertTrue(p.notes.any { it.contains("alpha") })
        assertNull(p.palette["accent"]) // not a colour: ignored for mapping
    }

    @Test fun crlfBomAndSectionsEndParsing() {
        val v = parse("\uFEFFbackground = \"#101010\"\r\nforeground = #f0f0f0\r\n[extra]\r\nbackground = \"#ffffff\"\r\n").values
        assertEquals("#101010", v["background"]); assertEquals("#f0f0f0", v["foreground"])
    }

    @Test fun unreadablePalettesAreRejectedWithOwnerCopy() {
        for (text in listOf("foreground = \"#ffffff\"", "background = \"#101010\"\nforeground = \"#121212\"", "background = \"blue-ish\"\nforeground = \"#ffffff\"")) {
            try { OmarchyResolver.fromFiles(text.toByteArray(), null, false); fail(text) }
            catch (e: PaletteException) { assertTrue(e.message!!.startsWith("The theme's colours")) }
        }
        try { OmarchyResolver.fromFiles(null, "[font]\nsize = 12".toByteArray(), false); fail() }
        catch (e: PaletteException) { assertTrue(e.message!!.contains("colors.toml")) }
    }

    @Test fun alacrittyReadsOnlyLoneHexUnderColours() {
        val text = """
            [shell]
            program = "/bin/sh"
            [colors.primary]
            background = '0x101010'
            [colors]
            normal.black = "#000000"
            normal.red = "#ff0000"
            [colors.normal]
            red = "#aa0000" # section form wins
            green = "#00aa00"
            yellow = "#aaaa00"
            blue = "#0000aa"
            magenta = "#aa00aa"
            cyan = "#00aaaa"
            white = "#aaaaaa"
            black = "#111111"
            [colors.bright]
            red = "not a colour"
        """.trimIndent()
        val m = AlacrittyPalette.extract(text.toByteArray())!!
        assertEquals("#101010", m["background"]); assertEquals("#aa0000", m["color1"]); assertEquals("#aa0000", m["color9"])
        assertEquals("#0000aa", m["accent"]); assertEquals("#aaaaaa", m["selection"])
        assertFalse(m.values.any { it.contains("sh") })
        assertNull(AlacrittyPalette.extract("[colors.normal]\nblack = \"#000000\"".toByteArray()))
    }

    @Test fun modeFollowsOmarchyPrecedence() {
        fun mode(text: String, file: Boolean) = OmarchyResolver.resolve(ColorsToml.parse(text.toByteArray()).values, file)["mode"]
        assertEquals("dark", mode("theme_type = dark\nbackground = \"#ffffff\"", true))
        assertEquals("light", mode("background = \"#101010\"", true))
        assertEquals("light", mode("background = \"#c0c0c0\"", false))
        assertEquals("dark", mode("background = \"#7f7f7f\"", false))
    }
}
