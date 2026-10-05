package no.heimflyt.launcher.theme

import no.heimflyt.launcher.theme.create.AccentChoice
import no.heimflyt.launcher.theme.create.ModeChoice
import no.heimflyt.launcher.theme.create.PaletteGenerator
import no.heimflyt.launcher.theme.create.Variant
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.palette.violations
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.DataInputStream
import java.io.File

/**
 * The quality gate's corpus run (CREATE_THEME.md §6.5), using the real Kotlin generator and TokenMapper. Input:
 * HEIMFLYT_CORPUS = a directory of `.argb` files (int width, int height, then ARGB ints, big-endian) prepared from the
 * review images; output: `results.tsv` beside them (framing, mode and each card's tokens) for the contact sheet.
 * Corpus images are never committed. Skipped when HEIMFLYT_CORPUS is unset.
 */
class CreateThemeCorpus {
    @Test fun corpus() {
        val dir = System.getenv("HEIMFLYT_CORPUS")?.let(::File)
        assumeTrue(dir != null && dir.isDirectory)
        val aspect = 1280.0 / 2856
        val out = StringBuilder("file\tfx\tfy\twx\twy\tww\twh\taw\tah\tmode\tachromatic\tcard\tground\traised\tink\tinkStrong\taccent\tonAccent\tscrim\tid0\tid3\n")
        var violations = 0
        for (f in dir!!.listFiles()!!.filter { it.extension == "argb" }.sortedBy { it.name }) {
            val img = DataInputStream(f.inputStream().buffered()).use { s -> val w = s.readInt(); val h = s.readInt(); PaletteGenerator.Image(IntArray(w * h) { s.readInt() }, w, h) }
            val (fx, fy) = PaletteGenerator.autoFrame(img, aspect)
            val win = PaletteGenerator.window(img, aspect, fx, fy)
            val an = PaletteGenerator.analyse(img, win)
            for (v in listOf(Variant.CALM, Variant.NATURAL, Variant.BOLD)) {
                val t = TokenMapper.map(PaletteGenerator.generate(an, v, AccentChoice.Auto, ModeChoice.AUTO))
                violations += t.violations().size
                out.append(listOf(f.nameWithoutExtension, fx, fy, win.x0, win.y0, win.w, win.h, img.w, img.h, if (t.isLight) "light" else "dark", an.achromatic, v.label,
                    t.ground, t.raised, t.ink, t.inkStrong, t.accent, t.onAccent, t.scrim, t.identity[0], t.identity[3]).joinToString("\t") { if (it is Int) "#%06x".format(it) else it.toString() }).append('\n')
            }
        }
        File(dir, "results.tsv").writeText(out.toString())
        assertEquals("rendered-pair violations across the corpus", 0, violations)
    }
}
