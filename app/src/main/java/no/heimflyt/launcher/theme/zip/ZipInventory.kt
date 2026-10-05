package no.heimflyt.launcher.theme.zip

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/** Budgets of THEME_ARCHITECTURE.md §4.6, counted on actual bytes read (declared sizes are never trusted). */
data class ZipLimits(
    val compressed: Long = 150L shl 20, val expanded: Long = 256L shl 20, val entries: Int = 5000, val nameBytes: Int = 512,
    val accepted: Long = 60L shl 20, val palette: Int = 64 shl 10, val background: Long = 25L shl 20, val preview: Long = 8L shl 20,
    val maxBackgrounds: Int = 12,
)

/** An owner-facing failure; nothing was installed. */
class ZipRejected(message: String) : Exception(message)
class ZipCancelled : Exception()

/**
 * Counts and hashes every raw byte read from the provider stream, including the reader's lookahead, and aborts at the
 * compressed budget. Cancellation is checked on every read.
 */
class CountingHashStream(raw: InputStream, private val cap: Long, private val cancelled: () -> Boolean) : FilterInputStream(raw) {
    private val digest = MessageDigest.getInstance("SHA-256")
    var count = 0L; private set
    private fun counted(n: Int) {
        if (n > 0) { count += n; if (count > cap) throw ZipRejected("This archive is too large to import.") }
    }
    override fun read(): Int {
        if (cancelled()) throw ZipCancelled()
        val b = super.read(); if (b >= 0) { digest.update(b.toByte()); counted(1) }; return b
    }
    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (cancelled()) throw ZipCancelled()
        val n = super.read(b, off, len); if (n > 0) { digest.update(b, off, n); counted(n) }; return n
    }
    override fun skip(n: Long): Long { val buf = ByteArray(8192); var left = n; while (left > 0) { val r = read(buf, 0, minOf(buf.size.toLong(), left).toInt()); if (r < 0) break; left -= r }; return n - left }
    override fun markSupported() = false
    /** Finishes the raw stream to EOF (central directory and trailing bytes) through the same budget and hash. */
    fun drainToEof() { val buf = ByteArray(64 * 1024); while (read(buf, 0, buf.size) >= 0) Unit }
    fun sha256(): String = digest.digest().joinToString("") { "%02x".format(it) }
}

enum class IgnoredKind(val label: String) { CODE("script or code"), CONFIG("desktop app configuration"), IMAGE("other image"), OTHER("other") }

class PlannedFile(val path: String, val name: String, val size: Long)

/** The inspect-pass result: the Report is shown from this (SURFACES.md §9.3). */
class ZipPlan(
    val sha256: String, val root: String, val rootDir: String?,
    val colorsToml: ByteArray?, val alacrittyToml: ByteArray?, val lightMode: Boolean, val licenseFile: String?,
    val backgrounds: List<PlannedFile>, val preview: PlannedFile?, val notes: List<String>,
    val ignored: Map<IgnoredKind, List<String>>, val acceptedBytes: Long, val compressedBytes: Long,
)

object ZipInventory {
    /** Omarchy background names (THEME_ARCHITECTURE.md §5.2); shared with the URL planner. */
    val BACKGROUND = Regex("^[A-Za-z0-9][A-Za-z0-9 ._@+()-]{0,120}\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)
    private val PALETTES = setOf("colors.toml", "alacritty.toml")

    /** Normalised entry name, or null when it must be ignored as unsafe or irrelevant (directories, `__MACOSX/`). */
    fun normalise(raw: String): String? {
        if (raw.contains('\u0000') || raw.contains('\\') || raw.startsWith("/") || raw.contains("..")) return null
        var n = raw
        while (n.startsWith("./")) n = n.substring(2)
        n = n.replace(Regex("/{2,}"), "/")
        if (n.isEmpty() || n.endsWith("/") || n == "__MACOSX" || n.startsWith("__MACOSX/")) return null
        return n
    }

    private class Scan(val names: List<String>, val sizes: Map<String, Long>, val unsafe: List<String>, val retained: Map<String, ByteArray>, val sha: String, val raw: Long)

    /**
     * One bounded pass. Every entry, accepted or ignored, is drained here in 64 KiB chunks before `getNextEntry()`, so the
     * reader's own `closeEntry()` never drains unread data outside the budget. [retain] returns how many bytes of an entry to
     * keep (0 = drain only); [onRetained] receives kept bytes as each entry completes. Abort closes the raw stream first.
     */
    private fun scan(open: () -> InputStream, limits: ZipLimits, cancelled: () -> Boolean,
                     retain: (String) -> Long, onRetained: (String, ByteArray) -> Unit = { _, _ -> }): Scan {
        val raw = open()
        val counting = CountingHashStream(raw, limits.compressed, cancelled)
        val zin = ZipInputStream(counting)
        val names = ArrayList<String>(); val sizes = HashMap<String, Long>(); val unsafe = ArrayList<String>()
        val retained = HashMap<String, ByteArray>()
        var expanded = 0L; var entries = 0
        val buf = ByteArray(64 * 1024)
        var ok = false
        try {
            while (true) {
                if (cancelled()) throw ZipCancelled()
                val entry = zin.nextEntry ?: break
                if (++entries > limits.entries) throw ZipRejected("This archive has too many files to import.")
                if (entry.name.toByteArray().size > limits.nameBytes) throw ZipRejected("This archive contains a file name that is too long.")
                val name = if (entry.isDirectory) null else normalise(entry.name)
                if (name == null && !entry.isDirectory && !entry.name.startsWith("__MACOSX")) unsafe += entry.name.take(120)
                val keep = if (name == null) 0L else retain(name)
                val out = if (keep > 0) ByteArrayOutputStream() else null
                var size = 0L
                while (true) {
                    if (cancelled()) throw ZipCancelled()
                    val n = zin.read(buf, 0, buf.size)
                    if (n < 0) break
                    expanded += n
                    if (expanded > limits.expanded) throw ZipRejected("This archive is too large to import.")
                    size += n
                    if (out != null && size <= keep) out.write(buf, 0, n)
                }
                if (name != null) {
                    names += name; if (name in sizes) sizes[name] = -1 else sizes[name] = size
                    if (out != null && size <= keep) { val bytes = out.toByteArray(); onRetained(name, bytes); if (keepRetained(name)) retained[name] = bytes }
                }
            }
            counting.drainToEof()
            ok = true
            return Scan(names, sizes, unsafe, retained, counting.sha256(), counting.count)
        } catch (e: ZipException) {
            throw ZipRejected("This archive is damaged.")
        } catch (e: EOFException) {
            throw ZipRejected("This archive is damaged.")
        } catch (e: IllegalArgumentException) {
            throw ZipRejected("This archive is damaged.")
        } finally {
            // Abort: close the raw provider stream first so nothing drains it, then the reader.
            if (!ok) runCatching { raw.close() }
            runCatching { zin.close() }
        }
    }

    /** Palette bytes are the only content kept past a pass; images go straight to [onRetained]. */
    private fun keepRetained(name: String) = name.substringAfterLast('/') in PALETTES

    private fun topDir(name: String) = name.substringBefore('/', "").takeIf { name.contains('/') }

    /** Inspect pass: complete inventory, root selection, duplicate rejection, allowlist plan and raw SHA-256. */
    fun inspect(open: () -> InputStream, fileName: String, limits: ZipLimits = ZipLimits(), cancelled: () -> Boolean = { false }): ZipPlan {
        var firstTop: String? = null
        var rootPalettes = 0; var topPalettes = 0
        val scan = scan(open, limits, cancelled, retain = { name ->
            val base = name.substringAfterLast('/')
            if (base !in PALETTES) return@scan 0L
            val top = topDir(name)
            when {
                !name.contains('/') -> if (rootPalettes++ < 2) limits.palette + 1L else 0L
                name.count { it == '/' } == 1 -> {
                    if (firstTop == null) firstTop = top
                    if (top == firstTop && topPalettes++ < 2) limits.palette + 1L else 0L
                }
                else -> 0L
            }
        })
        val names = scan.names
        val hasRootPalette = names.any { it in PALETTES }
        val tops = names.mapNotNull(::topDir).toSet()
        val loose = names.any { !it.contains('/') }
        val root: String; val rootDir: String?
        when {
            hasRootPalette -> { root = ""; rootDir = null }
            !loose && tops.size == 1 && names.any { it == "${tops.single()}/colors.toml" || it == "${tops.single()}/alacritty.toml" } -> { rootDir = tops.single(); root = "$rootDir/" }
            else -> throw ZipRejected("Couldn't find a theme in this archive.")
        }
        return plan(scan, root, rootDir, limits)
    }

    private fun plan(scan: Scan, root: String, rootDir: String?, limits: ZipLimits): ZipPlan {
        val underRoot = scan.names.filter { it.startsWith(root) }.map { it.removePrefix(root) }
        // Duplicates of allowlisted paths under the root reject the import.
        val allow = underRoot.filter { it in PALETTES || it == "preview.png" || it == "light.mode" || (it.startsWith("backgrounds/") && it.count { c -> c == '/' } == 1) }
        if (allow.size != allow.toSet().size || allow.any { scan.sizes[root + it] == -1L }) throw ZipRejected("This archive contains duplicate theme files.")
        val notes = mutableListOf<String>()
        fun palette(name: String): ByteArray? {
            if (root + name !in scan.sizes) return null
            val bytes = scan.retained[root + name]
            if (bytes == null || bytes.size > limits.palette) throw ZipRejected("The theme's colours couldn't be read.")
            return bytes
        }
        val colors = palette("colors.toml")
        val alacritty = if (colors == null) palette("alacritty.toml") else null
        if (colors == null && alacritty == null) throw ZipRejected("This archive doesn't contain an Omarchy theme palette (colors.toml).")
        val license = underRoot.filter { !it.contains('/') }.sorted().firstOrNull { n -> listOf("LICENSE", "LICENCE", "COPYING").any { n.uppercase().startsWith(it) } }
        if (license == null) notes += "No licence file. Fine for personal use."
        var accepted = (colors ?: alacritty)!!.size.toLong()
        val preview = underRoot.firstOrNull { it == "preview.png" }?.let { PlannedFile(root + it, it, scan.sizes[root + it]!!) }
            ?.takeIf { if (it.size > limits.preview) { notes += "preview.png skipped: over 8 MB"; false } else true }
        if (preview != null) accepted += preview.size
        val candidates = underRoot.filter { it.startsWith("backgrounds/") && it.count { c -> c == '/' } == 1 }
            .map { it.removePrefix("backgrounds/") }.sorted()
        val backgrounds = mutableListOf<PlannedFile>()
        for (name in candidates) {
            val path = root + "backgrounds/" + name; val size = scan.sizes[path]!!
            when {
                !BACKGROUND.matches(name) -> notes += "skipped $name: not a JPEG, PNG or WebP background"
                backgrounds.size >= limits.maxBackgrounds -> notes += "skipped $name: only the first ${limits.maxBackgrounds} backgrounds are used"
                size > limits.background -> notes += "skipped $name: ${size shr 20} MB, over the 25 MB limit"
                accepted + size > limits.accepted -> notes += "skipped $name: over the 60 MB total"
                else -> { backgrounds += PlannedFile(path, name, size); accepted += size }
            }
        }
        if (backgrounds.isEmpty()) notes += "No backgrounds. Krets's own grounds will be used."
        val used = setOfNotNull(colors?.let { "colors.toml" }, alacritty?.let { "alacritty.toml" }, preview?.name, "light.mode") + backgrounds.map { "backgrounds/${it.name}" }
        val ignored = (underRoot.filter { it !in used && it != license } + scan.names.filterNot { it.startsWith(root) } + scan.unsafe)
            .groupBy { kindOf(it) }
        return ZipPlan(scan.sha, root, rootDir, colors, alacritty, "light.mode" in underRoot, license, backgrounds, preview, notes, ignored, accepted, scan.raw)
    }

    fun kindOf(name: String): IgnoredKind {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (ext) {
            "sh", "bash", "zsh", "fish", "lua", "py", "js", "ts", "rb", "pl", "vim", "exe", "so", "jar", "ps1", "bat", "desktop" -> IgnoredKind.CODE
            "conf", "toml", "ini", "css", "theme", "kdl", "yml", "yaml", "json", "rasi", "xml", "qss", "tpl", "rgb", "cfg" -> IgnoredKind.CONFIG
            "png", "jpg", "jpeg", "webp", "gif", "svg", "bmp" -> IgnoredKind.IMAGE
            else -> IgnoredKind.OTHER
        }
    }

    /**
     * Install pass: re-reads the same URI under the same budgets, hands each planned entry's bytes to [onFile] as it
     * completes, and requires the same raw SHA-256 at the end (else the staged output must be discarded).
     */
    fun install(open: () -> InputStream, plan: ZipPlan, limits: ZipLimits = ZipLimits(), cancelled: () -> Boolean = { false },
                onFile: (PlannedFile, ByteArray) -> Unit) {
        val wanted = (plan.backgrounds + listOfNotNull(plan.preview)).associateBy { it.path }
        val scan = scan(open, limits, cancelled, retain = { name -> wanted[name]?.let { minOf(it.size, limits.background) } ?: 0L },
            onRetained = { name, bytes -> wanted[name]?.let { onFile(it, bytes) } })
        if (scan.sha != plan.sha256) throw ZipRejected("The file changed. Try again.")
    }

    /** Omarchy's rule (THEME_ARCHITECTURE.md §4.4) on the archive's root directory or file name. */
    fun themeName(rootDir: String?, fileName: String): String {
        var base = (rootDir ?: fileName.substringAfterLast('/').removeSuffix(".zip").removeSuffix(".ZIP")).lowercase()
        base = base.replace(Regex("-(main|master)$"), "").removePrefix("omarchy-").removeSuffix("-theme")
        val words = base.split('-', '_', ' ').filter { it.isNotEmpty() }
        return words.joinToString(" ") { it.replaceFirstChar(Char::uppercaseChar) }.ifEmpty { "Imported theme" }.take(40)
    }
}
