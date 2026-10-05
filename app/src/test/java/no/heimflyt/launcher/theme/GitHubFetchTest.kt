package no.heimflyt.launcher.theme

import android.app.Application
import no.heimflyt.launcher.theme.fetch.FetchCancelled
import no.heimflyt.launcher.theme.fetch.FetchException
import no.heimflyt.launcher.theme.fetch.GitBlob
import no.heimflyt.launcher.theme.fetch.GitHubImport
import no.heimflyt.launcher.theme.fetch.GitHubRepo
import no.heimflyt.launcher.theme.fetch.GitHubSource
import no.heimflyt.launcher.theme.fetch.GitHubUrl
import no.heimflyt.launcher.theme.fetch.HopValidator
import no.heimflyt.launcher.theme.fetch.HttpResponse
import no.heimflyt.launcher.theme.fetch.HttpsGet
import no.heimflyt.launcher.theme.fetch.InstallPlanner
import no.heimflyt.launcher.theme.fetch.ThemeNetworkSession
import no.heimflyt.launcher.theme.fetch.TreeEntry
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.store.BundledSource
import no.heimflyt.launcher.theme.store.GenerationPreparer
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeStore
import no.heimflyt.launcher.theme.zip.IgnoredKind
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.Closeable
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.HttpsURLConnection
import kotlin.concurrent.thread

/** WP12 (THEME_ARCHITECTURE.md §4.1–4.5, spec §8.6): URL rules, hop validation, planner, fake-transport flows and R7 cancellation. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitHubFetchTest {
    // ---- URL normalisation (§4.2) ----

    @Test fun normalisationTable() {
        val ok = mapOf(
            "https://github.com/catlee/omarchy-dracula-theme" to GitHubRepo("catlee", "omarchy-dracula-theme", null),
            "https://www.GitHub.com/catlee/omarchy-dracula-theme.git/" to GitHubRepo("catlee", "omarchy-dracula-theme", null),
            "http://github.com/a/b?tab=readme#x" to GitHubRepo("a", "b", null),
            "github.com/a/b" to GitHubRepo("a", "b", null),
            "  github.com/a/b  " to GitHubRepo("a", "b", null),
            "git@github.com:a/b.git" to GitHubRepo("a", "b", null),
            "https://github.com/a/b/tree/dev" to GitHubRepo("a", "b", "dev"),
        )
        ok.forEach { (input, want) -> assertEquals(input, want, GitHubUrl.normalise(input)) }
        assertEquals("gh:catlee/omarchy-dracula-theme", GitHubUrl.normalise("github.com/Catlee/Omarchy-Dracula-Theme").id)
        for (bad in listOf("", "gitlab.com/a/b", "https://evil.com/github.com/a/b", "https://github.com.evil.com/a/b", "ftp://github.com/a/b",
                "https://user@github.com/a/b", "https://github.com/a", "https://github.com/../b", "https://github.com/a/..", "github.com/a b/c",
                "javascript:alert(1)", "https://github.com/-bad_/x!")) {
            try { GitHubUrl.normalise(bad); fail(bad) } catch (e: FetchException) { assertEquals(bad, GitHubUrl.NOT_GITHUB, e.message) }
        }
        for (sub in listOf("https://github.com/a/b/tree/feature/x", "https://github.com/a/b/blob/main/colors.toml", "https://github.com/a/b/tree/main/themes/x"))
            try { GitHubUrl.normalise(sub); fail(sub) } catch (e: FetchException) { assertEquals(sub, GitHubUrl.SUBPATH, e.message) }
    }

    // ---- hop validation (R7) ----

    @Test fun everyHopIsValidated() {
        val sha = "a".repeat(40)
        for (good in listOf("https://api.github.com/repos/a/b", "https://api.github.com/repos/a/b/commits/main", "https://api.github.com/repos/a/b/git/trees/$sha",
                "https://api.github.com:443/repositories/123/commits/main", "https://raw.githubusercontent.com/a/b/$sha/backgrounds/My%20Wall.png"))
            HopValidator.check(good)
        for (bad in listOf("http://api.github.com/repos/a/b", "https://evil.com/repos/a/b", "https://api.github.com:8443/repos/a/b",
                "https://x@api.github.com/repos/a/b", "https://api.github.com/user", "https://api.github.com/repos/a/b/contents/x",
                "https://raw.githubusercontent.com/a/b/main/colors.toml", "https://raw.githubusercontent.com/a/b/$sha/../../x",
                "https://api.github.com/repos/a/b?access_token=1", "https://gist.githubusercontent.com/a/b/$sha/x"))
            try { HopValidator.check(bad); fail(bad) } catch (_: FetchException) {}
        // Redirects resolve against the current URL and are re-validated.
        assertEquals("https://api.github.com/repositories/42", HopValidator.check("/repositories/42", "https://api.github.com/repos/a/b"))
        try { HopValidator.check("https://example.com/", "https://api.github.com/repos/a/b"); fail() } catch (_: FetchException) {}
    }

    @Test fun gitBlobHashesMatchGit() {
        assertEquals("e69de29bb2d1d6434b8b29ae775ad8c2e48c5391", GitBlob.sha1(ByteArray(0)))
        assertEquals("ce013625030ba8dba906f756967f9e9ca394464a", GitBlob.sha1("hello\n".toByteArray()))
    }

    // ---- planner (§5.2) ----

    private fun e(path: String, size: Long = 10, mode: String = "100644", type: String = "blob", sha: String = "0".repeat(40)) = TreeEntry(path, mode, type, sha, size)

    @Test fun plannerFetchesOnlyTheAllowlist() {
        val root = listOf(e("colors.toml"), e("alacritty.toml"), e("neovim.lua"), e("hyprland.conf"), e("install.sh"), e("preview.png", 1000),
            e("backgrounds", type = "tree", mode = "040000"), e("evil", mode = "120000"), e("sub", type = "commit", mode = "160000"), e("LICENSE"), e("light.mode"))
        val bgs = listOf(e("b.png", 5L shl 20), e("a.jpg", 5L shl 20), e("huge.png", 26L shl 20), e("link.png", mode = "120000"), e("notes.txt"), e("nested", type = "tree", mode = "040000"))
        val p = InstallPlanner.plan(root, bgs)
        assertEquals("colors.toml", p.palette.path); assertFalse(p.paletteIsAlacritty)
        assertEquals(listOf("a.jpg", "b.png"), p.backgrounds.map { it.path })
        assertTrue(p.lightMode); assertEquals("LICENSE", p.licenseFile); assertEquals("preview.png", p.preview?.path)
        assertTrue(p.notes.any { it.contains("25 MB") }); assertTrue(p.notes.any { it.contains("link.png") })
        assertEquals(listOf("install.sh", "neovim.lua"), p.ignored[IgnoredKind.CODE]!!.sorted())
        assertTrue(p.ignored.values.flatten().containsAll(listOf("evil (link)", "sub (submodule)", "alacritty.toml")))
        // alacritty-only and palette-less repositories.
        assertTrue(InstallPlanner.plan(listOf(e("alacritty.toml")), null).paletteIsAlacritty)
        try { InstallPlanner.plan(listOf(e("README.md"), e("colors.toml", mode = "120000")), null); fail() } catch (_: FetchException) {}
        try { InstallPlanner.plan(listOf(e("colors.toml", 70_000)), null); fail() } catch (_: FetchException) {}
        assertTrue(InstallPlanner.plan(listOf(e("colors.toml")), null).notes.any { it.startsWith("No backgrounds") })
    }

    // ---- fake GitHub (fake transport) ----

    private val palette = "mode = \"dark\"\nbackground = \"#0b0b0b\"\nforeground = \"#e6d9b5\"\naccent = \"#d4a845\"\n".toByteArray()
    private val commit = "c".repeat(40)

    private class Fake(val routes: MutableMap<String, () -> HttpResponse>) : HttpsGet {
        val calls = mutableListOf<String>()
        override fun get(url: String, headers: Map<String, String>, session: ThemeNetworkSession): HttpResponse {
            calls += url
            return (routes[url] ?: { resp(404, "") })()
        }
        companion object {
            fun resp(code: Int, body: String, headers: Map<String, String> = emptyMap()) = resp(code, body.toByteArray(), headers)
            fun resp(code: Int, body: ByteArray, headers: Map<String, String> = emptyMap()) = HttpResponse(code, { headers[it] }, ByteArrayInputStream(body)) {}
        }
    }

    private fun github(extraRoot: List<JSONObject> = emptyList()): MutableMap<String, () -> HttpResponse> {
        val tree = JSONArray(listOf(JSONObject().put("path", "colors.toml").put("mode", "100644").put("type", "blob").put("sha", GitBlob.sha1(palette)).put("size", palette.size),
            JSONObject().put("path", "neovim.lua").put("mode", "100644").put("type", "blob").put("sha", "1".repeat(40)).put("size", 5)) + extraRoot)
        return mutableMapOf(
            "https://api.github.com/repos/o/omarchy-gold-theme" to { Fake.resp(200, JSONObject().put("default_branch", "main").put("archived", false).toString()) },
            "https://api.github.com/repos/o/omarchy-gold-theme/commits/main" to { Fake.resp(200, commit) },
            "https://api.github.com/repos/o/omarchy-gold-theme/git/trees/$commit" to { Fake.resp(200, JSONObject().put("tree", tree).toString()) },
            "https://raw.githubusercontent.com/o/omarchy-gold-theme/$commit/colors.toml" to { Fake.resp(200, palette) },
        )
    }

    private fun store(): ThemeStore {
        val root = Files.createTempDirectory("gh").toFile()
        val none = object : BundledSource { override val ids = emptyList<String>(); override fun load(id: String): LoadedRecord? = null }
        val prep = object : GenerationPreparer {
            override fun prepare(record: LoadedRecord, choice: no.heimflyt.launcher.theme.store.ThemeChoice, tokens: no.heimflyt.launcher.theme.palette.ResolvedColors,
                signature: no.heimflyt.launcher.theme.image.ZoneSignature, dir: File, cancelled: () -> Boolean, out: String) = error("unused")
            override fun check(file: File, signature: no.heimflyt.launcher.theme.image.ZoneSignature) = true
        }
        return ThemeStore(root, ThemeStoreTest.JvmFiles(), none, prep)
    }

    @Test fun inspectPinsTheCommitAndInstallBecomesAnOrdinaryRecord() {
        val fake = Fake(github())
        val s = store()
        val gh = GitHubImport(s, ThemeStoreTest.JvmFiles(), fake, "Heimflyt/test")
        val report = gh.inspect("https://github.com/o/omarchy-gold-theme", ThemeNetworkSession())
        assertEquals("Gold", report.name); assertEquals(commit, report.commit); assertEquals(PaletteSource.COLORS_TOML, report.source)
        assertEquals(0, report.backgroundCount); assertTrue(report.origin.startsWith("github.com/o/omarchy-gold-theme"))
        assertEquals("four API calls at most, plus the palette", 4, fake.calls.size)
        assertFalse("never fetches non-allowlisted files", fake.calls.any { it.contains("neovim") })
        val o = gh.install(report, ThemeNetworkSession(), 1080, 2424) {}
        assertTrue(o.toString(), o is Outcome.Installed)
        val rec = s.loadRecord("gh:o/omarchy-gold-theme")!!
        assertEquals(ThemeOrigin.GitHub("o", "omarchy-gold-theme", "main", commit), rec.record.origin)
        assertEquals(0xd4a845, rec.palette["accent"])
        assertFalse("installed themes need no network", fake.calls.drop(4).isNotEmpty() && fake.calls.size > 4)
    }

    @Test fun failuresUseOwnerCopyAndInstallNothing() {
        fun inspect(routes: MutableMap<String, () -> HttpResponse>) = try {
            GitHubImport(store(), ThemeStoreTest.JvmFiles(), Fake(routes), "t").inspect("github.com/o/omarchy-gold-theme", ThemeNetworkSession()); "ok"
        } catch (e: FetchException) { e.message!! }
        assertEquals(GitHubSource.NOT_FOUND, inspect(mutableMapOf()))
        assertTrue(inspect(github().apply { put("https://api.github.com/repos/o/omarchy-gold-theme") {
            Fake.resp(403, "", mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1790000000")) } }).startsWith("GitHub is limiting requests"))
        // A redirect to another host aborts the session.
        assertEquals(HopValidator.BLOCKED, inspect(github().apply { put("https://api.github.com/repos/o/omarchy-gold-theme") { Fake.resp(301, "", mapOf("Location" to "https://evil.example/x")) } }))
        // A redirect within the API host (renamed repository) is followed.
        assertEquals("ok", inspect(github().apply {
            val real = get("https://api.github.com/repos/o/omarchy-gold-theme")!!
            put("https://api.github.com/repos/o/omarchy-gold-theme") { Fake.resp(301, "", mapOf("Location" to "/repositories/7")) }
            put("https://api.github.com/repositories/7", real) }))
        // Content that does not match the pinned tree is rejected.
        assertEquals("A file changed while downloading. Try again.", inspect(github().apply {
            put("https://raw.githubusercontent.com/o/omarchy-gold-theme/$commit/colors.toml") { Fake.resp(200, palette + "x".toByteArray()) } }))
        // Caps abort mid-stream (an endless body stops at the cap).
        assertTrue(inspect(github().apply { put("https://api.github.com/repos/o/omarchy-gold-theme/commits/main") {
            HttpResponse(200, { null }, object : java.io.InputStream() { override fun read() = 'a'.code }) {} } }).contains("larger than Krets allows"))
        assertEquals("This repository doesn't contain an Omarchy theme palette (colors.toml).", inspect(github().apply {
            put("https://api.github.com/repos/o/omarchy-gold-theme/git/trees/$commit") { Fake.resp(200, JSONObject().put("tree", JSONArray()).toString()) } }))
        assertTrue(inspect(github().apply { put("https://raw.githubusercontent.com/o/omarchy-gold-theme/$commit/colors.toml") {
            Fake.resp(200, "background = \"#101010\"\nforeground = \"#121212\"") } }).startsWith("A file changed"))  // size/sha bind first
    }

    // ---- R7 cancellation: stalled connect, TLS and read; the session worker stops within 1 s, nothing new starts ----

    private fun stalledServer(): ServerSocket {
        val server = ServerSocket(); server.bind(InetSocketAddress("127.0.0.1", 0))
        thread(isDaemon = true) { val held = mutableListOf<Socket>(); try { while (true) held += server.accept() } catch (_: Exception) {} }
        return server
    }

    private fun assertCancelledWithin1s(transport: HttpsGet) {
        val s = ThemeNetworkSession()
        val source = GitHubSource(transport, "t")
        var result: Any? = null
        val t = thread { result = try { source.repo(s, GitHubRepo("o", "r", null)) } catch (e: Throwable) { e } }
        Thread.sleep(400)
        val t0 = System.nanoTime(); s.cancel(); t.join(2000)
        val ms = (System.nanoTime() - t0) / 1_000_000
        assertFalse("worker still running", t.isAlive)
        assertTrue("stopped in $ms ms", ms < 1000)
        assertTrue("$result", result is FetchCancelled)
        try { source.repo(s, GitHubRepo("o", "r", null)); fail() } catch (_: FetchCancelled) {}
    }

    @Test fun cancellationDuringStalledReadTlsAndConnect() {
        val server = stalledServer()
        val calls = AtomicInteger()
        // Plain TCP read stall: the server accepts and never answers.
        assertCancelledWithin1s { _, _, s ->
            calls.incrementAndGet()
            val c = java.net.URL("http://127.0.0.1:${server.localPort}/").openConnection() as java.net.HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            s.register(Closeable { c.disconnect() })
            HttpResponse(c.responseCode, { null }, c.inputStream) {}
        }
        // TLS handshake stall with the platform-style HttpsURLConnection.
        assertCancelledWithin1s { _, _, s ->
            calls.incrementAndGet()
            val c = java.net.URL("https://127.0.0.1:${server.localPort}/").openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000
            s.register(Closeable { c.disconnect() })
            HttpResponse(c.responseCode, { null }, c.inputStream) {}
        }
        // Connect stall (non-routable address).
        assertCancelledWithin1s { _, _, s ->
            calls.incrementAndGet()
            val sock = Socket(); s.register(sock)
            sock.connect(InetSocketAddress("10.255.255.1", 443), 10_000)
            HttpResponse(599, { null }, null) {}
        }
        assertEquals("no request after cancellation", 3, calls.get())
        server.close()
    }

    @Test fun cancelledSessionNeverReachesTheCommit() {
        val fake = Fake(github())
        val s = store()
        val gh = GitHubImport(s, ThemeStoreTest.JvmFiles(), fake, "t")
        val report = gh.inspect("github.com/o/omarchy-gold-theme", ThemeNetworkSession())
        val session = ThemeNetworkSession().also { it.cancel() }
        assertEquals(Outcome.Superseded, gh.install(report, session, 1080, 2424) {})
        assertNull(s.loadRecord("gh:o/omarchy-gold-theme"))
        assertTrue(s.readCatalog().records.isEmpty())
    }
}
