package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.zip.IgnoredKind
import no.heimflyt.launcher.theme.zip.ZipCancelled
import no.heimflyt.launcher.theme.zip.ZipInventory
import no.heimflyt.launcher.theme.zip.ZipLimits
import no.heimflyt.launcher.theme.zip.ZipRejected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** R2 (THEME_ARCHITECTURE.md §4.6): budgets on actual bytes, bounded drains, root rule, duplicates, abort without draining. */
class ZipInventoryTest {
    private val palette = "background = \"#1a1b26\"\nforeground = \"#c0caf5\"\naccent = \"#7aa2f7\"\n".toByteArray()
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte()) + ByteArray(100)

    private fun zip(vararg entries: Pair<String, ByteArray>, stored: Boolean = false): ByteArray = ByteArrayOutputStream().also { out ->
        ZipOutputStream(out).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                if (stored) { e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = CRC32().apply { update(data) }.value }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
    }.toByteArray()

    /** Counts bytes actually pulled from the provider, and records closes and reads after close. */
    private class Probe(bytes: ByteArray) : InputStream() {
        private val inner = ByteArrayInputStream(bytes)
        var read = 0L; var closed = false; var readAfterClose = false
        override fun read(): Int { if (closed) readAfterClose = true; return inner.read().also { if (it >= 0) read++ } }
        override fun read(b: ByteArray, off: Int, len: Int): Int { if (closed) readAfterClose = true; return inner.read(b, off, len).also { if (it > 0) read += it } }
        override fun close() { closed = true }
    }

    private fun rejected(bytes: ByteArray, limits: ZipLimits = ZipLimits(), cancelled: () -> Boolean = { false }): String {
        try { ZipInventory.inspect({ ByteArrayInputStream(bytes) }, "t.zip", limits, cancelled); fail("accepted"); error("") }
        catch (e: ZipRejected) { return e.message!! }
    }

    @Test fun githubStyleArchiveSelectsItsTopDirectoryAndPlansTheAllowlist() {
        val bytes = zip("omarchy-dracula-theme-main/colors.toml" to palette, "omarchy-dracula-theme-main/backgrounds/b.png" to png,
            "omarchy-dracula-theme-main/backgrounds/a.jpg" to byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()),
            "omarchy-dracula-theme-main/neovim.lua" to "os.execute('x')".toByteArray(), "omarchy-dracula-theme-main/hyprland.conf" to ByteArray(10),
            "omarchy-dracula-theme-main/preview.png" to png, "omarchy-dracula-theme-main/LICENSE" to "MIT".toByteArray(),
            "omarchy-dracula-theme-main/backgrounds/nested/c.png" to png)
        val plan = ZipInventory.inspect({ ByteArrayInputStream(bytes) }, "dl.zip")
        assertEquals("omarchy-dracula-theme-main", plan.rootDir)
        assertEquals(listOf("a.jpg", "b.png"), plan.backgrounds.map { it.name })   // lexical, like Omarchy
        assertNotNull(plan.preview); assertEquals("LICENSE", plan.licenseFile)
        assertTrue(plan.colorsToml!!.contentEquals(palette))
        assertEquals(listOf("neovim.lua"), plan.ignored[IgnoredKind.CODE])   // root-relative
        assertEquals(listOf("backgrounds/nested/c.png"), plan.ignored[IgnoredKind.IMAGE])
        assertEquals(1, plan.ignored[IgnoredKind.CONFIG]!!.size)
        assertEquals("Dracula", ZipInventory.themeName(plan.rootDir, "dl.zip"))
    }

    @Test fun largeIgnoredEntryAheadOfThePaletteStopsAtTheExpandedBudget() {
        // 40 MiB of zeros compresses to ~40 KiB: the compressed cap alone would never stop it.
        val bytes = zip("huge.bin" to ByteArray(40 shl 20), "colors.toml" to palette)
        val limits = ZipLimits(expanded = 8L shl 20)
        val probe = Probe(bytes)
        try { ZipInventory.inspect({ probe }, "t.zip", limits); fail() } catch (e: ZipRejected) { assertEquals("This archive is too large to import.", e.message) }
        assertTrue("closed on abort", probe.closed)
        assertFalse("no reads after the abort close", probe.readAfterClose)
        // Well within budget, the same archive is fine.
        assertEquals("", ZipInventory.inspect({ ByteArrayInputStream(bytes) }, "t.zip").root)
    }

    @Test fun compressedBudgetCountsActualProviderBytes() {
        val noise = kotlin.random.Random(1).nextBytes(2 shl 20)
        val bytes = zip("colors.toml" to palette, "noise.bin" to noise)
        val probe = Probe(bytes)
        try { ZipInventory.inspect({ probe }, "t.zip", ZipLimits(compressed = 1L shl 20)); fail() } catch (_: ZipRejected) {}
        assertTrue("stopped near the cap: ${probe.read}", probe.read <= (1L shl 20) + 128 * 1024)
    }

    @Test fun unknownSizeDataDescriptorEntriesAreDrainedAndCounted() {
        // ZipOutputStream writes DEFLATED entries with data descriptors (sizes unknown in the local header).
        val plan = ZipInventory.inspect({ ByteArrayInputStream(zip("colors.toml" to palette, "backgrounds/x.png" to png)) }, "t.zip")
        assertEquals(png.size.toLong(), plan.backgrounds.single().size)
        val stored = ZipInventory.inspect({ ByteArrayInputStream(zip("colors.toml" to palette, "backgrounds/x.png" to png, stored = true)) }, "t.zip")
        assertEquals(64L + palette.size - 64, stored.acceptedBytes - png.size)
    }

    @Test fun duplicatesMixedRootsAndMissingPalettesAreRejected() {
        assertEquals("This archive contains duplicate theme files.", rejected(zip("colors.toml" to palette, "./colors.toml" to palette)))
        assertEquals("This archive contains duplicate theme files.", rejected(zip("t/colors.toml" to palette, "t/backgrounds/a.png" to png, "t//backgrounds/a.png" to png)))
        assertEquals("Couldn't find a theme in this archive.", rejected(zip("a/colors.toml" to palette, "b/colors.toml" to palette)))
        assertEquals("Couldn't find a theme in this archive.", rejected(zip("a/colors.toml" to palette, "README.md" to ByteArray(3))))
        assertEquals("Couldn't find a theme in this archive.", rejected(zip("a/b/colors.toml" to palette)))
        // An archive-root palette makes the root unambiguous.
        assertEquals("", ZipInventory.inspect({ ByteArrayInputStream(zip("colors.toml" to palette, "a/colors.toml" to palette, "b/colors.toml" to palette)) }, "t.zip").root)
        assertEquals("The theme's colours couldn't be read.", rejected(zip("colors.toml" to ByteArray(70 * 1024) { 'a'.code.toByte() })))
    }

    @Test fun malformedAndTruncatedArchivesAreDamaged() {
        val good = zip("colors.toml" to palette, "backgrounds/a.png" to ByteArray(200_000) { (it % 251).toByte() })
        assertEquals("This archive is damaged.", rejected(good.copyOf(good.size / 2)))
        val corrupt = good.copyOf().also { for (i in 60 until 400) it[i] = 0x55 }
        assertTrue(rejected(corrupt) in setOf("This archive is damaged.", "Couldn't find a theme in this archive."))
        assertEquals("Couldn't find a theme in this archive.", rejected("not a zip at all".toByteArray()))
    }

    @Test fun cancellationDuringAnIgnoredEntryDrainStopsTheImport() {
        val bytes = zip("big.bin" to ByteArray(20 shl 20), "colors.toml" to palette)
        var polls = 0
        val probe = Probe(bytes)
        try { ZipInventory.inspect({ probe }, "t.zip", cancelled = { ++polls > 20 }); fail() } catch (_: ZipCancelled) {}
        assertTrue(probe.closed); assertFalse(probe.readAfterClose)
        assertTrue("stopped early: ${probe.read} of ${bytes.size}", probe.read < bytes.size)
    }

    @Test fun acceptedContentOverflowSkipsBackgroundsWithNotes() {
        val bg = ByteArray(6 shl 20) { (it % 13).toByte() }
        val bytes = zip("colors.toml" to palette, *Array(12) { "backgrounds/b$it.png" to bg }, "backgrounds/huge.png" to ByteArray(26 shl 20))
        val plan = ZipInventory.inspect({ ByteArrayInputStream(bytes) }, "t.zip")
        assertTrue(plan.acceptedBytes <= 60L shl 20)
        assertEquals(9, plan.backgrounds.size)
        assertTrue(plan.notes.any { it.contains("over the 60 MB total") }); assertTrue(plan.notes.any { it.contains("over the 25 MB limit") })
    }

    @Test fun entryCountAndUnsafeNamesAreBounded() {
        val many = zip("colors.toml" to palette, *Array(6000) { "f$it.txt" to ByteArray(1) })
        assertEquals("This archive has too many files to import.", rejected(many))
        for (bad in listOf("../colors.toml", "/colors.toml", "a\\colors.toml", "x/../../y")) assertNull(bad, ZipInventory.normalise(bad))
        assertEquals("a/b.png", ZipInventory.normalise("./a//b.png")); assertNull(ZipInventory.normalise("__MACOSX/._x"))
        val plan = ZipInventory.inspect({ ByteArrayInputStream(zip("colors.toml" to palette, "../evil.sh" to ByteArray(3))) }, "t.zip")
        assertEquals(1, plan.ignored.values.sumOf { it.size })
    }

    @Test fun installPassRequiresTheSameBytes() {
        val a = zip("colors.toml" to palette, "backgrounds/a.png" to png)
        val plan = ZipInventory.inspect({ ByteArrayInputStream(a) }, "t.zip")
        val got = mutableListOf<String>()
        ZipInventory.install({ ByteArrayInputStream(a) }, plan) { f, bytes -> got += f.name; assertTrue(bytes.contentEquals(png)) }
        assertEquals(listOf("a.png"), got)
        val b = zip("colors.toml" to palette, "backgrounds/a.png" to png + byteArrayOf(1))
        try { ZipInventory.install({ ByteArrayInputStream(b) }, plan) { _, _ -> }; fail() } catch (e: ZipRejected) { assertEquals("The file changed. Try again.", e.message) }
    }

    @Test fun namesFollowOmarchysRule() {
        assertEquals("Rose Pine Dark", ZipInventory.themeName("omarchy-rose-pine-dark-master", "x.zip"))
        assertEquals("Aetheria", ZipInventory.themeName(null, "aetheria.zip"))
    }
}
