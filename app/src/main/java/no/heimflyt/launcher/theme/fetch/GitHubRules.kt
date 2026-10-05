package no.heimflyt.launcher.theme.fetch

import no.heimflyt.launcher.theme.zip.IgnoredKind
import no.heimflyt.launcher.theme.zip.ZipInventory
import java.net.URI
import java.security.MessageDigest

/** An owner-facing failure (THEME_ARCHITECTURE.md §7 copy). Nothing was installed. */
class FetchException(message: String) : Exception(message)
class FetchCancelled : Exception()

/** A normalised public GitHub repository (THEME_ARCHITECTURE.md §4.2). The host is never taken from input beyond this check. */
data class GitHubRepo(val owner: String, val repo: String, val ref: String?) {
    val id get() = "gh:${owner.lowercase()}/${repo.lowercase()}"
    val display get() = "github.com/$owner/$repo"
    val url get() = "https://github.com/$owner/$repo"
}

object GitHubUrl {
    private val OWNER = Regex("^[A-Za-z0-9-]{1,39}$")
    private val REPO = Regex("^[A-Za-z0-9._-]{1,100}$")
    const val NOT_GITHUB = "That doesn't look like a GitHub repository link. Try github.com/owner/repo."
    const val SUBPATH = "Links to folders inside a repository aren't supported yet. Use the repository's main link."

    /** Pure: accepts https/http/scheme-less/`git@` forms, `.git`, trailing `/`, `?…`, `#…`, and `/tree/<ref>` without `/` in the ref. */
    fun normalise(input: String): GitHubRepo {
        var s = input.trim()
        if (s.isEmpty() || s.length > 400 || s.any { it.isWhitespace() || it.code < 0x20 }) throw FetchException(NOT_GITHUB)
        s = s.substringBefore('#').substringBefore('?')
        s = if (s.startsWith("git@github.com:", ignoreCase = true)) s.substring("git@github.com:".length) else {
            val rest = when {
                s.startsWith("https://", ignoreCase = true) -> s.substring(8)
                s.startsWith("http://", ignoreCase = true) -> s.substring(7)   // upgraded: requests are always https
                s.contains("://") || s.contains('@') -> throw FetchException(NOT_GITHUB)
                else -> s
            }
            val host = rest.substringBefore('/').lowercase().removePrefix("www.")
            if (host != "github.com" || !rest.contains('/')) throw FetchException(NOT_GITHUB)
            rest.substringAfter('/')
        }
        val parts = s.trimEnd('/').split('/')
        if (parts.size < 2) throw FetchException(NOT_GITHUB)
        val owner = parts[0]; val repo = parts[1].removeSuffix(".git")
        if (!OWNER.matches(owner) || !REPO.matches(repo) || repo == "." || repo == "..") throw FetchException(NOT_GITHUB)
        val ref = when {
            parts.size == 2 -> null
            parts[2] == "tree" && parts.size == 4 && parts[3].isNotEmpty() && Regex("^[A-Za-z0-9._-]{1,100}$").matches(parts[3]) -> parts[3]
            else -> throw FetchException(SUBPATH)
        }
        return GitHubRepo(owner, repo, ref)
    }
}

/**
 * Validates the initial URL and every redirect hop (R7, THEME_ARCHITECTURE.md §4.3): https, a fixed host, port 443 or absent,
 * no userinfo, and a path matching the expected request shape for that host.
 */
object HopValidator {
    const val API = "api.github.com"
    const val RAW = "raw.githubusercontent.com"
    private val API_PATHS = listOf(
        Regex("^/repos/[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}(/commits/[A-Za-z0-9._-]{1,100}|/git/trees/[0-9a-f]{40})?$"),
        // Renamed repositories redirect to the numeric form within the API host.
        Regex("^/repositories/[0-9]{1,20}(/commits/[A-Za-z0-9._-]{1,100}|/git/trees/[0-9a-f]{40})?$"),
    )
    private val RAW_PATH = Regex("^/[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}/[0-9a-f]{40}/[A-Za-z0-9 ._@+()%/-]{1,400}$")

    /** Returns the absolute URL to request, or throws. [location] is resolved against [current] for redirects. */
    fun check(url: String, current: String? = null): String {
        val uri = try { if (current != null) URI(current).resolve(url.replace(" ", "%20")) else URI(url.replace(" ", "%20")) } catch (_: Exception) { throw FetchException(BLOCKED) }
        if (uri.scheme != "https" || uri.rawUserInfo != null || (uri.port != -1 && uri.port != 443)) throw FetchException(BLOCKED)
        val host = uri.host?.lowercase() ?: throw FetchException(BLOCKED)
        val path = uri.rawPath ?: throw FetchException(BLOCKED)
        if (path.contains("..") || path.contains("//")) throw FetchException(BLOCKED)
        val ok = when (host) {
            API -> API_PATHS.any { it.matches(path) } && uri.rawQuery == null
            RAW -> RAW_PATH.matches(path) && uri.rawQuery == null
            else -> false
        }
        if (!ok) throw FetchException(BLOCKED)
        return uri.toASCIIString()
    }
    const val BLOCKED = "GitHub sent an unexpected address, so Flyt stopped. Nothing was installed."
}

object GitBlob {
    /** `sha1("blob " + size + "\0" + content)`: binds downloaded content to the pinned commit's tree. */
    fun sha1(content: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-1")
        md.update("blob ${content.size}".toByteArray()); md.update(0); md.update(content)
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}

class TreeEntry(val path: String, val mode: String, val type: String, val sha: String, val size: Long)

/** What a URL install will fetch; the Report is shown from this before any image is downloaded. */
class RemotePlan(
    val palette: TreeEntry, val paletteIsAlacritty: Boolean, val lightMode: Boolean, val licenseFile: String?,
    val preview: TreeEntry?, val backgrounds: List<TreeEntry>, val backgroundsTree: String?, val notes: List<String>,
    val ignored: Map<IgnoredKind, List<String>>, val acceptedBytes: Long,
)

/**
 * The fetch allowlist of THEME_ARCHITECTURE.md §5.2 over a non-recursive root listing (and the `backgrounds` subtree).
 * Pure; shares the background name rule, caps and ignored categories with the `.zip` importer.
 */
object InstallPlanner {
    const val PALETTE_CAP = 64L * 1024
    const val PREVIEW_CAP = 8L shl 20
    const val BACKGROUND_CAP = 25L shl 20
    const val TOTAL_CAP = 60L shl 20
    const val MAX_BACKGROUNDS = 12
    private val BLOB_MODES = setOf("100644", "100755")

    fun backgroundsTree(root: List<TreeEntry>): String? = root.firstOrNull { it.path == "backgrounds" && it.type == "tree" && it.mode == "040000" }?.sha

    fun plan(root: List<TreeEntry>, backgrounds: List<TreeEntry>?): RemotePlan {
        fun blob(name: String) = root.firstOrNull { it.path == name && it.type == "blob" && it.mode in BLOB_MODES }
        val notes = mutableListOf<String>()
        val colors = blob("colors.toml"); val alacritty = blob("alacritty.toml")
        val palette = colors ?: alacritty ?: throw FetchException("This repository doesn't contain an Omarchy theme palette (colors.toml).")
        if (palette.size > PALETTE_CAP) throw FetchException("The theme's colours couldn't be read.")
        val license = root.filter { it.type == "blob" }.map { it.path }.sorted().firstOrNull { n -> listOf("LICENSE", "LICENCE", "COPYING").any { n.uppercase().startsWith(it) } }
        if (license == null) notes += "No licence file. Fine for personal use."
        var accepted = palette.size
        val preview = blob("preview.png")?.takeIf { if (it.size > PREVIEW_CAP) { notes += "preview.png skipped: over 8 MB"; false } else true }
        if (preview != null) accepted += preview.size
        val chosen = mutableListOf<TreeEntry>()
        for (e in (backgrounds ?: emptyList()).sortedBy { it.path }) {
            when {
                e.type != "blob" || e.mode !in BLOB_MODES -> notes += "skipped ${e.path}: not a regular file"
                !ZipInventory.BACKGROUND.matches(e.path) -> notes += "skipped ${e.path}: not a JPEG, PNG or WebP background"
                chosen.size >= MAX_BACKGROUNDS -> notes += "skipped ${e.path}: only the first $MAX_BACKGROUNDS backgrounds are used"
                e.size > BACKGROUND_CAP -> notes += "skipped ${e.path}: ${e.size shr 20} MB, over the 25 MB limit"
                accepted + e.size > TOTAL_CAP -> notes += "skipped ${e.path}: over the 60 MB total"
                else -> { chosen += e; accepted += e.size }
            }
        }
        if (chosen.isEmpty()) notes += "No backgrounds. Flyt's own grounds will be used."
        val used = setOfNotNull(palette.path, preview?.path, "backgrounds", "light.mode", license)
        // Symlinks (120000), submodules (160000), other trees and every other file are never requested.
        val ignored = root.filter { it.path !in used }.map { if (it.mode == "120000") "${it.path} (link)" else if (it.mode == "160000") "${it.path} (submodule)" else it.path }
            .groupBy { ZipInventory.kindOf(it) }
        return RemotePlan(palette, colors == null, root.any { it.path == "light.mode" }, license, preview, chosen,
            backgroundsTree(root), notes, ignored, accepted)
    }
}
