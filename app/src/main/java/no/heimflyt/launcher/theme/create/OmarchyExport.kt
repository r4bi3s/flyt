package no.heimflyt.launcher.theme.create

import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import java.io.OutputStream
import java.text.Normalizer
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Portable output (owner decision 2026-09-28): a created theme as a normal Omarchy theme directory. Only what Omarchy
 * consumes: `colors.toml` (the same canonical palette Heimflyt uses), `backgrounds/`, `preview.png`, plus a README.
 * Omarchy derives every desktop config from `colors.toml`, so none is written. Pure: the tests re-import it through the
 * existing `.zip` importer.
 */
object OmarchyExport {
    /** `omarchy-<slug>-theme`, so Omarchy's naming rule (strip `omarchy-` and `-theme`) gives back the name. */
    fun directory(name: String): String = "omarchy-${slug(name)}-theme"

    fun slug(name: String): String {
        val folded = name.lowercase().replace("æ", "ae").replace("ø", "o").replace("å", "a")
        val ascii = Normalizer.normalize(folded, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return ascii.replace(Regex("[^a-z0-9]+"), "-").trim('-').take(40).ifEmpty { "photo" }
    }

    fun readme(name: String, palette: OmarchyPalette, selectedDesktopImages: Set<Int> = emptySet()): String = """
        # $name

        An Omarchy theme exported with Flyt.

        - `colors.toml`: the semantic palette (${if (palette.light) "light" else "dark"}). Omarchy generates every app's colours from it.
        - `backgrounds/`: ${if (selectedDesktopImages.isEmpty()) "a desktop crop of each photo around the part you chose" else "desktop backgrounds, including owner-selected wide images for slots ${selectedDesktopImages.sorted().map { it + 1 }.joinToString()}"} (Omarchy rotates through them).
        - `preview.png`: a preview card.

        The exported backgrounds are re-encoded pixels without camera metadata. Keep your editable theme in Flyt;
        this Omarchy package does not contain the source photos.
        ${if (selectedDesktopImages.isNotEmpty()) "Selected desktop images may have been made outside Flyt. Check their provenance and rights before publishing this theme." else ""}

        Install on Omarchy: put this folder in `~/.config/omarchy/themes/` (or publish it as a Git repository and use
        Omarchy's theme install with its URL), then select "${name}" in Omarchy's theme picker.

        Install on Flyt: Tune → Look → Themes → Install a theme, from this .zip or its GitHub URL.
    """.trimIndent() + "\n"

    /**
     * [landscapes] are each image's desktop crop around its focal point, in theme order (`backgrounds/1-…`, `2-…`: Omarchy's
     * rotation, the first is its default). Editing masters stay in Heimflyt and are not part of this target.
     */
    fun entries(name: String, palette: OmarchyPalette, landscapes: List<ByteArray>, previewPng: ByteArray,
                selectedDesktopImages: Set<Int> = emptySet()): List<Pair<String, ByteArray>> {
        require(landscapes.size in 1..4)
        val dir = directory(name); val slug = slug(name)
        return listOf("$dir/colors.toml" to ColorsToml.write(palette).toByteArray()) +
            landscapes.mapIndexed { i, b -> "$dir/backgrounds/${i + 1}-$slug.jpg" to b } +
            listOf("$dir/preview.png" to previewPng, "$dir/README.md" to readme(name, palette, selectedDesktopImages).toByteArray())
    }

    fun writeZip(out: OutputStream, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(out).use { z ->
            for ((path, bytes) in entries) { z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
        }
    }
}
