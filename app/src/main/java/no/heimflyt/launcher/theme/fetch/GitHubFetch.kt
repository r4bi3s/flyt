package no.heimflyt.launcher.theme.fetch

import no.heimflyt.launcher.theme.InstallReport
import no.heimflyt.launcher.theme.image.ThemeImages
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyPalette
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.PaletteException
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.ThemeFiles
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import no.heimflyt.launcher.theme.zip.IgnoredKind
import no.heimflyt.launcher.theme.zip.ZipInventory
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.HttpsURLConnection

/** One response of the transport seam. Closing it closes the stream and the connection. */
class HttpResponse(val code: Int, private val headers: (String) -> String?, val body: InputStream?, private val onClose: () -> Unit) : Closeable {
    fun header(name: String) = headers(name)
    override fun close() = onClose()
}

/** A single blocking HTTPS GET that never follows redirects. Tests inject fakes and stalled servers. */
fun interface HttpsGet {
    fun get(url: String, headers: Map<String, String>, session: ThemeNetworkSession): HttpResponse
}

/** The platform transport: `HttpsURLConnection`, no caches, no cookies, no auth, fixed timeouts, redirects handled by the caller. */
class PlatformHttps : HttpsGet {
    override fun get(url: String, headers: Map<String, String>, session: ThemeNetworkSession): HttpResponse {
        val c = URL(url).openConnection() as HttpsURLConnection
        c.instanceFollowRedirects = false; c.useCaches = false; c.connectTimeout = 10_000; c.readTimeout = 20_000
        c.requestMethod = "GET"
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        // Registered before connecting, so a cancel during connect/TLS disconnects this connection.
        val reg = session.register(Closeable { c.disconnect() })
        val code = c.responseCode
        val body = if (code in 200..299) c.inputStream else null
        return HttpResponse(code, { c.getHeaderField(it) }, body) { runCatching { body?.close() }; c.disconnect(); session.unregister(reg) }
    }
}

/**
 * R7: a foreground session owned by the Themes route (THEME_ARCHITECTURE.md §4.1). `cancel()` marks it cancelled under the same
 * lock that admits requests, then closes every open connection on an I/O thread, never the main thread. Each blocking request
 * runs on its own thread; the session worker stops waiting within ~50 ms of cancellation even if a platform read stays
 * blocked, and any bytes that thread still receives are discarded. No retry and no automatic restart.
 */
class ThemeNetworkSession {
    companion object {
        private val IO = Executors.newCachedThreadPool { r -> Thread(r, "heimflyt-theme-net").apply { isDaemon = true } }
    }
    private val lock = Any()
    @Volatile var cancelled = false; private set
    private val open = HashSet<Closeable>()

    fun register(c: Closeable): Closeable = synchronized(lock) {
        if (cancelled) { IO.execute { runCatching { c.close() } }; throw FetchCancelled() }
        open += c; c
    }
    fun unregister(c: Closeable) = synchronized(lock) { open -= c }
    fun check() { if (cancelled) throw FetchCancelled() }

    fun cancel() {
        val closing = synchronized(lock) { if (cancelled) return; cancelled = true; open.toList().also { open.clear() } }
        IO.execute { closing.forEach { runCatching { it.close() } } }
    }

    fun <T> call(block: () -> T): T {
        check()
        val f = IO.submit(Callable(block))
        while (true) {
            try { val v = f.get(50, TimeUnit.MILLISECONDS); check(); return v }
            catch (_: TimeoutException) { if (cancelled) { f.cancel(true); throw FetchCancelled() } }
            catch (e: ExecutionException) { if (cancelled) throw FetchCancelled(); throw e.cause ?: e }
        }
    }
}

/** THEME_ARCHITECTURE.md §4.3: four API calls per inspect, raw content per allowlisted file, every hop validated. */
class GitHubSource(private val http: HttpsGet, private val userAgent: String) {
    companion object {
        const val OFFLINE = "Couldn't reach GitHub. Check your connection and try again."
        const val NOT_FOUND = "Couldn't find that repository. It may be private, renamed or deleted."
        private val SHA = Regex("^[0-9a-f]{40}$")
    }
    class RepoInfo(val defaultBranch: String, val archived: Boolean)

    private sealed interface Hop { class Body(val bytes: ByteArray) : Hop; class Redirect(val location: String) : Hop; class Error(val code: Int, val remaining: String?, val reset: String?) : Hop }

    /** One request with at most 3 validated redirects, reading at most [cap] bytes in cancellation-checked 64 KiB chunks. */
    fun fetch(s: ThemeNetworkSession, start: String, accept: String?, cap: Long, notFound: String = NOT_FOUND): ByteArray {
        var url = HopValidator.check(start)
        val headers = buildMap { put("User-Agent", userAgent); if (accept != null) put("Accept", accept); if (url.contains(HopValidator.API)) put("X-GitHub-Api-Version", "2022-11-28") }
        for (hop in 0..3) {
            val result = try {
                s.call {
                    http.get(url, headers, s).use { r ->
                        when (r.code) {
                            in 300..399 -> Hop.Redirect(r.header("Location") ?: throw FetchException(HopValidator.BLOCKED))
                            in 200..299 -> Hop.Body(readBounded(r.body ?: throw FetchException(OFFLINE), cap, s))
                            else -> Hop.Error(r.code, r.header("X-RateLimit-Remaining"), r.header("X-RateLimit-Reset"))
                        }
                    }
                }
            } catch (e: IOException) { if (s.cancelled) throw FetchCancelled(); throw FetchException(OFFLINE) }
            when (result) {
                is Hop.Body -> return result.bytes
                is Hop.Redirect -> { if (hop == 3) throw FetchException(HopValidator.BLOCKED); url = HopValidator.check(result.location, url) }
                is Hop.Error -> throw FetchException(when {
                    (result.code == 403 || result.code == 429) && result.remaining == "0" -> rateLimited(result.reset)
                    result.code == 404 || result.code == 451 -> notFound
                    result.code == 403 -> "GitHub refused the request. Try again later."
                    else -> OFFLINE
                })
            }
        }
        throw FetchException(HopValidator.BLOCKED)
    }

    private fun rateLimited(reset: String?): String {
        val at = reset?.toLongOrNull()?.let { java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it * 1000)) }
        return "GitHub is limiting requests from this network." + (at?.let { " Try again after $it." } ?: " Try again later.")
    }

    private fun readBounded(input: InputStream, cap: Long, s: ThemeNetworkSession): ByteArray {
        val out = ByteArrayOutputStream(); val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            s.check()
            val n = input.read(buf); if (n < 0) break
            total += n
            if (total > cap) throw FetchException("A theme file is larger than Krets allows. Nothing was installed.")
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun api(r: GitHubRepo) = "https://${HopValidator.API}/repos/${r.owner}/${r.repo}"

    fun repo(s: ThemeNetworkSession, r: GitHubRepo): RepoInfo {
        val o = JSONObject(String(fetch(s, api(r), "application/vnd.github+json", 256 * 1024), Charsets.UTF_8))
        if (o.optBoolean("private")) throw FetchException(NOT_FOUND)
        return RepoInfo(o.optString("default_branch").takeIf { it.isNotEmpty() && it.length <= 100 } ?: throw FetchException(NOT_FOUND), o.optBoolean("archived"))
    }

    fun commit(s: ThemeNetworkSession, r: GitHubRepo, ref: String): String {
        val sha = String(fetch(s, "${api(r)}/commits/$ref", "application/vnd.github.sha", 1024,
            notFound = "Couldn't find that branch in the repository."), Charsets.US_ASCII).trim()
        if (!SHA.matches(sha)) throw FetchException(OFFLINE)
        return sha
    }

    fun tree(s: ThemeNetworkSession, r: GitHubRepo, sha: String): List<TreeEntry> {
        val o = JSONObject(String(fetch(s, "${api(r)}/git/trees/$sha", "application/vnd.github+json", 512 * 1024), Charsets.UTF_8))
        val a = o.getJSONArray("tree")
        return List(minOf(a.length(), 5000)) { i -> a.getJSONObject(i).let {
            TreeEntry(it.getString("path"), it.getString("mode"), it.getString("type"), it.getString("sha"), it.optLong("size", 0)) } }
    }

    /** Raw content at the pinned commit, verified against the tree entry's size and git blob SHA-1. */
    fun raw(s: ThemeNetworkSession, r: GitHubRepo, commit: String, path: String, entry: TreeEntry, cap: Long): ByteArray {
        val encoded = path.split('/').joinToString("/") { seg -> seg.toByteArray().joinToString("") { b ->
            val c = b.toInt() and 0xff; val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch in "._-") ch.toString() else "%%%02X".format(c) } }
        val bytes = fetch(s, "https://${HopValidator.RAW}/${r.owner}/${r.repo}/$commit/$encoded", null, cap,
            notFound = "A theme file is missing on GitHub. Try again.")
        if (bytes.size.toLong() != entry.size || GitBlob.sha1(bytes) != entry.sha) throw FetchException("A file changed while downloading. Try again.")
        return bytes
    }
}

/** The inspect result shown in the Report; installs exactly the pinned commit it names. */
class GitHubReport(
    val repo: GitHubRepo, val ref: String, val commit: String, val plan: RemotePlan, val archived: Boolean,
    override val name: String, override val palette: OmarchyPalette, override val source: PaletteSource, override val notes: List<String>,
) : InstallReport {
    override val id get() = repo.id
    override val origin get() = "${repo.display} · ${commit.take(7)}"
    override val backgroundCount get() = plan.backgrounds.size
    override val backgroundBytes get() = plan.backgrounds.sumOf { it.size }
    override val hasPreview get() = plan.preview != null
    override val ignored: Map<IgnoredKind, List<String>> get() = plan.ignored
    override val totalBytes get() = backgroundBytes + (plan.preview?.size ?: 0)
}

/**
 * WP12: GitHub URL install into the same record model as `.zip` import. Palette parsing, image processing, staging and the
 * record commit are the H5.1 pipeline; only the byte source differs. Nothing but allowlisted files is ever requested.
 */
class GitHubImport(private val store: ThemeStore, private val files: ThemeFiles, http: HttpsGet, userAgent: String) {
    private val source = GitHubSource(http, userAgent)

    fun inspect(input: String, s: ThemeNetworkSession): GitHubReport {
        val repo = GitHubUrl.normalise(input)
        val info = source.repo(s, repo)
        val ref = repo.ref ?: info.defaultBranch
        val commit = source.commit(s, repo, ref)
        val root = source.tree(s, repo, commit)
        val bgTree = InstallPlanner.backgroundsTree(root)?.let { source.tree(s, repo, it) }
        val plan = InstallPlanner.plan(root, bgTree)
        val bytes = source.raw(s, repo, commit, plan.palette.path, plan.palette, InstallPlanner.PALETTE_CAP)
        val (result, paletteSource) = try {
            if (plan.paletteIsAlacritty) OmarchyResolver.fromFiles(null, bytes, plan.lightMode) else OmarchyResolver.fromFiles(bytes, null, plan.lightMode)
        } catch (e: PaletteException) { throw FetchException(e.message ?: "The theme's colours couldn't be read.") }
        val notes = plan.notes + result.notes.take(4) + listOfNotNull(if (info.archived) "This repository is archived; it won't receive updates." else null)
        return GitHubReport(repo, ref, commit, plan, info.archived, ZipInventory.themeName(repo.repo, repo.repo), result.palette, paletteSource, notes)
    }

    /** Downloads into staging only; the record commit is entered only while the session is live, then completes locally. */
    fun install(report: GitHubReport, s: ThemeNetworkSession, displayW: Int, displayH: Int, progress: (Long) -> Unit): Outcome {
        val request = store.beginRecordRequest(report.id)
        val (stage, pin) = try { store.newStaging() } catch (_: IOException) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        try {
            val stored = mutableListOf<StoredBackground>(); val notes = report.notes.toMutableList()
            var done = 0L
            val preview = report.plan.preview?.let { e ->
                val bytes = source.raw(s, report.repo, report.commit, e.path, e, InstallPlanner.PREVIEW_CAP)
                done += e.size; progress(done)
                ThemeImages.preview(bytes, stage, files)
            }
            for (e in report.plan.backgrounds) {
                val bytes = source.raw(s, report.repo, report.commit, "backgrounds/${e.path}", e, InstallPlanner.BACKGROUND_CAP)
                s.check()
                val r = ThemeImages.background(bytes, e.path, stored.size, displayW, displayH, stage, files)
                r.background?.let(stored::add); r.note?.let(notes::add)
                done += e.size; progress(done)
            }
            files.write(File(stage, "colors.toml"), ColorsToml.write(report.palette).toByteArray())
            val record = ThemeRecord(report.id, report.name, ThemeOrigin.GitHub(report.repo.owner, report.repo.repo, report.ref, report.commit),
                report.palette.light, report.source, stored, preview, report.plan.licenseFile, notes.distinct().take(24), System.currentTimeMillis())
            files.write(File(stage, "manifest.json"), record.toJson().toString().toByteArray())
            s.check()   // admission: the non-cancellable local commit starts only for a live session
            return store.commitRecord(stage, report.id, request)
        } catch (e: FetchException) { return Outcome.Failed(e.message ?: GitHubSource.OFFLINE) }
        catch (_: FetchCancelled) { return Outcome.Superseded }
        catch (_: IOException) { return Outcome.Failed(ThemeStore.STORAGE_FAILED) }
        finally { pin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
    }
}
