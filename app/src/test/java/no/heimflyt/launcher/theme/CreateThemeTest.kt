package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.create.AccentChoice
import no.heimflyt.launcher.theme.create.ModeChoice
import no.heimflyt.launcher.theme.create.OmarchyExport
import no.heimflyt.launcher.theme.create.Oklab
import no.heimflyt.launcher.theme.create.PaletteGenerator
import no.heimflyt.launcher.theme.create.ThemeNames
import no.heimflyt.launcher.theme.create.Variant
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.palette.violations
import no.heimflyt.launcher.theme.zip.IgnoredKind
import no.heimflyt.launcher.theme.zip.ZipInventory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/** WP16 (CREATE_THEME.md §5–7, §10, spec §8.7): generator determinism, guarantees, hard photo cases and the portable output. */
class CreateThemeTest {
    private val aspect = 1280.0 / 2856
    private fun argb(rgb: Int) = rgb or (0xff shl 24)
    private fun img(w: Int, h: Int, f: (Int, Int) -> Int) = PaletteGenerator.Image(IntArray(w * h) { argb(f(it % w, it / w)) }, w, h)
    private fun hue(rgb: Int) = Oklab.fromRgb(rgb).let { (Math.toDegrees(atan2(it[2], it[1])) + 360) % 360 }
    private fun chroma(rgb: Int) = Oklab.fromRgb(rgb).let { hypot(it[1], it[2]) }
    private fun hd(a: Double, b: Double) = (abs(a - b) % 360).let { min(it, 360 - it) }

    private fun analyse(i: PaletteGenerator.Image, fx: Double = 0.5, fy: Double = 0.5) = PaletteGenerator.analyse(i, PaletteGenerator.window(i, aspect, fx, fy))

    // Synthetic photos standing in for the hard cases the prototype found (the real corpus runs in CreateThemeCorpus).
    private val sky = img(192, 256) { _, y -> if (y < 150) 0x8fb8e8 else if (y < 175) 0xe8a0b8 else 0x3a4a2a }               // sky-dominant, small pink subject band
    private val fog = img(192, 256) { x, y -> val v = 170 + (x + y) % 20; (v shl 16) or (v shl 8) or (v + 6) }                   // achromatic, faint blue cast
    private val mono = img(192, 256) { x, y -> val v = (x * 255 / 192 + y) % 256; (v shl 16) or (v shl 8) or v }                 // black and white
    private val tiny = img(256, 192) { x, y -> if (x in 180..190 && y in 90..110) 0xd23c28 else 0x2b3a44 }                       // landscape, small red boathouse
    private val bright = img(192, 256) { _, _ -> 0xf4efe6 }
    private val corpus = listOf(sky, fog, mono, tiny, bright)

    @Test fun deterministicAndCompleteInEveryVariantAndMode() {
        for (i in corpus) for (v in listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD)) for (m in ModeChoice.entries) {
            val a = PaletteGenerator.generate(analyse(i), v, AccentChoice.Auto, m)
            assertEquals(a, PaletteGenerator.generate(analyse(i), v, AccentChoice.Auto, m))
            assertEquals(OmarchyResolver.CANONICAL.toSet(), a.colors.keys)
            assertEquals(m == ModeChoice.LIGHT || (m == ModeChoice.AUTO && analyse(i).autoLight), a.light)
            assertEquals("$v $m", emptyList<String>(), TokenMapper.map(a).violations())
        }
    }

    @Test fun generatedPalettesNeedAlmostNoContrastRepair() {
        // The OKLCH targets do the work; TokenMapper's shifts are only a safety net (prototype §6.4).
        for (i in corpus) for (v in listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD)) {
            val t = TokenMapper.map(PaletteGenerator.generate(analyse(i), v, AccentChoice.Auto, ModeChoice.AUTO))
            assertFalse("$v ground shifted", t.adjustments.any { it.contains("ground") })
        }
    }

    @Test fun hueSanityAchromaticAndSmallSubjects() {
        // Accent hue lies within 30° of a retained cluster, except for achromatic fallbacks.
        for (i in listOf(sky, tiny)) {
            val an = analyse(i, fx = if (i === tiny) 0.72 else 0.5); assertFalse(an.achromatic)
            for (v in listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD)) {
                val acc = PaletteGenerator.generate(an, v, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!
                assertTrue("$v accent", an.clusters.any { hd(it.h, hue(acc)) <= 30 })
            }
        }
        // Fog and black-and-white are achromatic: the photo's faint tint, never a foreign saturated hue for the ground.
        for (i in listOf(fog, mono)) {
            val an = analyse(i); assertTrue(an.achromatic)
            val p = PaletteGenerator.generate(an, Variant.CALM, AccentChoice.Auto, ModeChoice.AUTO)
            assertTrue("near-neutral ground", chroma(p["background"]!!) < 0.03)
        }
        assertTrue("fog keeps its blue cast in the accent", hd(hue(PaletteGenerator.generate(analyse(fog), Variant.CALM, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!), 265.0) < 40)
    }

    @Test fun skyDominanceDoesNotStealTheAccent() {
        // Blossom band (pink) is the subject; sky has the largest share. Calm follows the subject, Natural the main colour.
        val an = analyse(sky)
        val calm = PaletteGenerator.generate(an, Variant.CALM, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!
        val natural = PaletteGenerator.generate(an, Variant.NATURAL, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!
        assertTrue("calm = pink subject ${hue(calm)}", hd(hue(calm), hue(0xe8a0b8)) < 30)
        assertTrue("natural = sky ${hue(natural)}", hd(hue(natural), hue(0x8fb8e8)) < 30)
    }

    @Test fun thePaletteFollowsTheCrop() {
        // A landscape half green, half orange: framing each half makes that half's colour the accent.
        val split = img(300, 150) { x, _ -> if (x < 150) 0x3f8f3a else 0xd8742a }
        val left = PaletteGenerator.generate(analyse(split, fx = 0.2), Variant.CALM, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!
        val right = PaletteGenerator.generate(analyse(split, fx = 0.8), Variant.CALM, AccentChoice.Auto, ModeChoice.DARK)["accent"]!!
        assertTrue(hd(hue(left), hue(0x3f8f3a)) < 30); assertTrue(hd(hue(right), hue(0xd8742a)) < 30)
        // A pinned accent survives reframing.
        val pin = AccentChoice.Pinned(0x3f8f3a)
        val pinned = PaletteGenerator.generate(analyse(split, fx = 0.8), Variant.CALM, pin, ModeChoice.DARK)["accent"]!!
        assertTrue(hd(hue(pinned), hue(0x3f8f3a)) < 20)
    }

    /** Multi-image themes: one coherent palette from the set; each image contributes through its own composition. */
    @Test fun oneCoherentPaletteForAnImageSet() {
        val green = img(160, 240) { _, _ -> 0x3f8f3a }; val orange = img(160, 240) { _, _ -> 0xd8742a }
        val w1 = PaletteGenerator.window(green, aspect, 0.5, 0.5); val w2 = PaletteGenerator.window(orange, aspect, 0.5, 0.5)
        val set = PaletteGenerator.analyseSet(listOf(green to w1, orange to w2))
        assertEquals(set.clusters, PaletteGenerator.analyseSet(listOf(green to w1, orange to w2)).clusters)   // deterministic
        // Both images are represented equally, so both hues are accent candidates.
        val hues = PaletteGenerator.candidates(set).map { it.first }
        assertTrue(hues.any { hd(it, hue(0x3f8f3a)) < 30 }); assertTrue(hues.any { hd(it, hue(0xd8742a)) < 30 })
        assertEquals(0.5, set.clusters.first().share, 0.05)
        // A set of one is exactly the single-image analysis.
        assertEquals(PaletteGenerator.analyse(sky, PaletteGenerator.window(sky, aspect, 0.5, 0.5)).clusters,
            PaletteGenerator.analyseSet(listOf(sky to PaletteGenerator.window(sky, aspect, 0.5, 0.5))).clusters)
        for (v in listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD)) assertEquals(emptyList<String>(), TokenMapper.map(PaletteGenerator.generate(set, v, AccentChoice.Auto, ModeChoice.AUTO)).violations())
    }

    @Test fun variantsDiffer() {
        val an = analyse(sky)
        val cards = listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD).map { PaletteGenerator.generate(an, it, AccentChoice.Auto, ModeChoice.DARK) }
        assertEquals(3, cards.map { it["accent"] to it["background"] }.toSet().size)
        assertTrue("bold ground is deeper", Oklab.fromRgb(cards[2]["background"]!!)[0] < Oklab.fromRgb(cards[0]["background"]!!)[0])
    }

    @Test fun autoFramingKeepsBusyDetailOutOfTheClockBand() {
        // A landscape with a busy vertical stripe near the right: the window should include it in its middle band.
        val busy = img(400, 150) { x, y -> if (x in 300..320) (if ((y / 3) % 2 == 0) 0xffffff else 0x000000) else 0x404850 }
        val (fx, _) = PaletteGenerator.autoFrame(busy, aspect)
        val win = PaletteGenerator.window(busy, aspect, fx, 0.5)
        assertTrue("stripe inside the window: ${win.x0}..${win.x0 + win.w}", 310 in win.x0 until win.x0 + win.w)
        // A screenshot-shaped image fits exactly: no sliding.
        assertEquals(0.5 to 0.5, PaletteGenerator.autoFrame(img(128, 286) { _, _ -> 0x222222 }, aspect))
    }

    @Test fun namesAreDeterministicUniqueAndClean() {
        val p = PaletteGenerator.generate(analyse(tiny, fx = 0.72), Variant.BOLD, AccentChoice.Auto, ModeChoice.DARK)
        val n = ThemeNames.suggest(p, false, emptyList())
        assertEquals(n, ThemeNames.suggest(p, false, emptyList()))
        assertEquals("$n 2", ThemeNames.suggest(p, false, listOf(n.uppercase())))
        assertEquals("Skifer", ThemeNames.suggest(PaletteGenerator.generate(analyse(mono), Variant.CALM, AccentChoice.Auto, ModeChoice.DARK), true, emptyList()))
        assertEquals("Blåtime", ThemeNames.clean("  Blåtime "))
        assertEquals(null, ThemeNames.clean("<script>"))
    }

    // ---- portable Omarchy output (owner decision 2026-09-28) ----

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(64)
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(64)

    @Test fun exportRoundTripsThroughTheExistingZipImporter() {
        for (m in listOf(ModeChoice.DARK, ModeChoice.LIGHT)) {
            val palette = PaletteGenerator.generate(analyse(tiny, fx = 0.72), Variant.CALM, AccentChoice.Auto, m)
            val name = "Glør på fjorden"
            // Two images: only the prepared desktop crops, palette, preview, and README are portable.
            val zip = ByteArrayOutputStream().also { OmarchyExport.writeZip(it, OmarchyExport.entries(name, palette, listOf(jpeg, jpeg + byteArrayOf(2)), png)) }.toByteArray()
            val names = ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                buildList { while (true) add(input.nextEntry?.name ?: break) }
            }
            assertEquals(listOf(
                "omarchy-glor-pa-fjorden-theme/colors.toml",
                "omarchy-glor-pa-fjorden-theme/backgrounds/1-glor-pa-fjorden.jpg",
                "omarchy-glor-pa-fjorden-theme/backgrounds/2-glor-pa-fjorden.jpg",
                "omarchy-glor-pa-fjorden-theme/preview.png",
                "omarchy-glor-pa-fjorden-theme/README.md",
            ), names)
            val plan = ZipInventory.inspect({ ByteArrayInputStream(zip) }, "export.zip")
            assertEquals("omarchy-glor-pa-fjorden-theme", plan.rootDir)
            assertEquals("Glor Pa Fjorden", ZipInventory.themeName(plan.rootDir, "export.zip"))
            assertEquals(listOf("1-glor-pa-fjorden.jpg", "2-glor-pa-fjorden.jpg"), plan.backgrounds.map { it.name })
            assertEquals(null, plan.ignored[IgnoredKind.IMAGE])
            assertTrue(plan.preview != null)
            assertEquals(listOf("README.md"), plan.ignored[IgnoredKind.OTHER])
            val back = OmarchyResolver.fromFiles(plan.colorsToml, null, plan.lightMode).first.palette
            assertEquals("the same semantic palette comes back", palette, back)
            assertEquals(TokenMapper.map(palette), TokenMapper.map(back))
        }
    }

    @Test fun physicalPhoneExportReimportsWhenProvided() {
        val path = System.getenv("HEIMFLYT_EXPORT_ZIP") ?: return
        val file = File(path)
        val plan = ZipInventory.inspect({ file.inputStream() }, file.name)
        assertEquals("omarchy-skifer-theme", plan.rootDir)
        assertEquals(4, plan.backgrounds.size)
        assertEquals(null, plan.ignored[IgnoredKind.IMAGE])
        assertTrue(plan.preview != null)
        assertEquals(OmarchyResolver.CANONICAL.toSet(),
            OmarchyResolver.fromFiles(plan.colorsToml, null, plan.lightMode).first.palette.colors.keys)
    }

    @Test fun activeOmarchySnapshotReimportsWhenProvided() {
        val path = System.getenv("HEIMFLYT_ACTIVE_SNAPSHOT_ZIP") ?: return
        val file = File(path)
        val plan = ZipInventory.inspect({ file.inputStream() }, file.name)
        assertEquals(1, plan.backgrounds.size)
        assertEquals(null, plan.ignored[IgnoredKind.IMAGE])
        val root = requireNotNull(plan.rootDir)
        assertTrue(root.startsWith("omarchy-") && root.endsWith("-theme"))
        assertEquals(OmarchyResolver.CANONICAL.toSet(),
            OmarchyResolver.fromFiles(plan.colorsToml, null, plan.lightMode).first.palette.colors.keys)
    }

    @Test fun exportedColorsTomlIsOmarchysOwnFormat() {
        val palette = PaletteGenerator.generate(analyse(sky), Variant.NATURAL, AccentChoice.Auto, ModeChoice.LIGHT)
        val text = ColorsToml.write(palette)
        // Every line is `key = "#rrggbb"` (or mode), all canonical keys present, parseable by the strict subset parser.
        assertTrue(text.lines().filter { it.isNotBlank() }.all { Regex("""^[a-z_]+ = "(#[0-9a-f]{6}|light|dark)"$""").matches(it) })
        assertEquals((OmarchyResolver.CANONICAL + "mode").toSet(), ColorsToml.parse(text.toByteArray()).values.keys)
        // Optional host check against upstream: set HEIMFLYT_EXPORT_DIR to write a sample for `omarchy-theme-color --all`.
        System.getenv("HEIMFLYT_EXPORT_DIR")?.let { File(it, "colors.toml").writeText(text) }
    }

    @Test fun createCodeNeverReachesTheNetwork() {
        val dir = File("src/main/java/no/heimflyt/launcher/theme/create")
        val net = Regex("""theme\.fetch|java\.net|javax\.net|HttpsURLConnection|URL\(|Socket""")
        assertEquals(emptyList<String>(), dir.walkTopDown().filter { it.isFile && net.containsMatchIn(it.readText()) }.map { it.name }.toList())
        val manifest = File("src/main/AndroidManifest.xml").readText()
        for (p in listOf("READ_MEDIA_IMAGES", "READ_EXTERNAL_STORAGE", "READ_MEDIA_VISUAL_USER_SELECTED")) assertFalse(p, p in manifest)
        assertNotEquals(-1, manifest.indexOf("INTERNET"))   // present for URL install only (see ThemeBoundaryTest)
    }
}
