package no.heimflyt.launcher.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Static checks of THEME_ARCHITECTURE.md §4.1 and §5.1 (spec §10.1). H5.1 has no network code at all (WP12 is H5.2). */
class ThemeBoundaryTest {
    private val main = File("src/main/java/no/heimflyt/launcher")
    private fun sources(dir: File = main) = dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

    @Test fun noNetworkApiAnywhereOutsideThemeFetch() {
        val net = Regex("""java\.net\.|javax\.net\.|HttpURLConnection|HttpsURLConnection|\bSocket\(|\bURL\(""")
        val offenders = sources().filterNot { it.path.contains("/theme/fetch/") }.filter { net.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("Network APIs outside theme/fetch: $offenders", offenders.isEmpty())
    }

    /** Network is only the direct consequence of a tap in Themes: nothing on startup, Home, radial, Search, Apps or Tune reaches it. */
    @Test fun onlyTheThemesRouteUsesThemeFetch() {
        val users = sources().filterNot { it.path.contains("/theme/fetch/") }.filter { it.readText().contains("theme.fetch") }.map { it.path.substringAfter("launcher/") }
        assertEquals(listOf("ui/themes/ThemesSurface.kt"), users)
        for (f in listOf("HeimflytScreen.kt", "MainActivity.kt", "Apps.kt", "theme/ThemeRuntime.kt")) assertTrue(f, "PlatformHttps" !in File(main, f).readText())
        assertTrue("theme/zip stays offline", sources(File(main, "theme/zip")).none { it.readText().contains("theme.fetch") })
    }

    @Test fun themeCodeHasNoExecutionOrIntentPath() {
        val exec = Regex("""Runtime\.getRuntime|ProcessBuilder|DexClassLoader|WebView|startActivity|Intent\(|Uri\.parse|loadLibrary""")
        val offenders = sources(File(main, "theme")).filter { exec.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("Theme data must never become behaviour: $offenders", offenders.isEmpty())
    }

    @Test fun storeWritesOnlyThroughThemeFiles() {
        val direct = Regex("""FileOutputStream|\.writeBytes\(|\.writeText\(|renameTo\(""")
        val offenders = sources(File(main, "theme")).filterNot { it.name == "ThemeFiles.kt" }.filter { direct.containsMatchIn(it.readText()) }.map { it.path }
        assertTrue("Direct file writes bypass fault injection: $offenders", offenders.isEmpty())
    }

    @Test fun homeNeverReadsThemeFiles() {
        val home = File(main, "ui/home").walkTopDown().filter { it.isFile }.joinToString("\n") { it.readText() }
        for (forbidden in listOf("ThemeStore", "BitmapFactory", "ImageDecoder", "ColorsToml", "ZipInventory", "readBytes")) assertTrue(forbidden, forbidden !in home)
    }
}
