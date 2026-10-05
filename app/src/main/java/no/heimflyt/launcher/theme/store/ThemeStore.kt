package no.heimflyt.launcher.theme.store

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.heimflyt.launcher.theme.image.Protection
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.ResolvedColors
import no.heimflyt.launcher.theme.palette.TokenMapper
import no.heimflyt.launcher.theme.palette.violations
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID

/** Bundled records come from assets and are never written to `records/` (THEME_ARCHITECTURE.md §2.1). */
interface BundledSource {
    val ids: List<String>
    fun load(id: String): LoadedRecord?
}

/** The image half of a generation: implemented with platform bitmaps, faked in the transaction tests. */
interface GenerationPreparer {
    /** Renders, protects, encodes and re-verifies one Home image named [out] into [dir] through [ThemeFiles]; returns its protection. */
    fun prepare(record: LoadedRecord, choice: ThemeChoice, tokens: ResolvedColors, signature: ZoneSignature, dir: File, cancelled: () -> Boolean,
                out: String = "home_bg.webp"): Protection
    /** Header-only validation of a staged background. */
    fun check(file: File, signature: ZoneSignature): Boolean
}

sealed interface Outcome {
    data class Applied(val active: ActiveGeneration) : Outcome
    data class Installed(val id: String, val rev: String) : Outcome
    data object Removed : Outcome
    data object Superseded : Outcome
    data class Failed(val message: String) : Outcome
    /** The pointer was renamed but its directory sync failed: both generations are kept (THEME_ARCHITECTURE.md §8.3). */
    data class Uncertain(val message: String) : Outcome
}

class CancelledException : Exception()

/**
 * The only writer of `themes/` (THEME_ARCHITECTURE.md §2.1 and §8, R1): immutable record revisions and generations, selected
 * by two small pointer files replaced atomically under one commit lock. Blocking; callers run it off the main thread.
 */
class ThemeStore(
    val root: File,
    private val files: ThemeFiles,
    private val bundled: BundledSource,
    private val preparer: GenerationPreparer,
) {
    companion object {
        const val FALLBACK_ID = "bundled:krets"
        const val BUNDLED_REV = "bundled"
        private const val MAX_JSON = 256 * 1024
        private const val MAX_IMAGE = 64 * 1024 * 1024
        const val STORAGE_FAILED = "Couldn't save the theme. Check free space on this phone and try again."
        fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        fun recordDir(id: String) = sha256(id.toByteArray()).take(32)
        /** Slot 0 keeps H5.1's name, so earlier generations stay readable. */
        fun homeImageName(slot: Int) = if (slot == 0) "home_bg.webp" else "home_bg-$slot.webp"
    }

    private val records = File(root, "records")
    private val generations = File(root, "generations")
    private val staging = File(root, "staging")
    private val activeFile = File(root, "active.json")
    private val catalogFile = File(root, "catalog.json")

    /** ThemeCommitLock: serialises every commit, admission check and garbage collection. */
    private val lock = Any()
    private var counter = -1L
    @Volatile private var latestApply = 0L
    private val recordRequests = HashMap<String, Long>()
    private val pins = HashMap<String, Int>()

    private val _active = MutableStateFlow<ActiveGeneration?>(null)
    val active: StateFlow<ActiveGeneration?> = _active.asStateFlow()
    private val _catalog = MutableStateFlow<Catalog?>(null)
    /** Loaded when Themes opens, never at Home. */
    val catalog: StateFlow<Catalog?> = _catalog.asStateFlow()

    data class Catalog(val seq: Long, val records: Map<String, String>)
    class ActivePointer(val seq: Long, val gen: String, val startup: StartupSubset)

    // ---- pins (THEME_ARCHITECTURE.md §2.1: jobs pin what they read; GC never deletes a pinned directory) ----

    inner class Pin internal constructor(private val path: String?) : AutoCloseable {
        override fun close() { if (path != null) synchronized(pins) { val n = (pins[path] ?: 1) - 1; if (n <= 0) pins.remove(path) else pins[path] = n } }
    }
    fun pin(dir: File?): Pin {
        val path = dir?.canonicalPath ?: return Pin(null)
        synchronized(pins) { pins[path] = (pins[path] ?: 0) + 1 }
        return Pin(path)
    }
    private fun pinned(dir: File) = synchronized(pins) { dir.canonicalPath in pins }

    // ---- reading ----

    private fun readJson(file: File): JSONObject = JSONObject(String(files.read(file, MAX_JSON), Charsets.UTF_8))

    fun readActivePointer(): ActivePointer? = runCatching {
        if (!activeFile.isFile) return null
        val o = readJson(activeFile); require(o.getInt("schema") == 1)
        val s = o.getJSONObject("startup")
        ActivePointer(o.getLong("seq"), o.getString("gen"),
            StartupSubset(s.getString("ground").removePrefix("#").toInt(16), s.getBoolean("light"), s.getBoolean("statusLight"), s.getBoolean("navLight")))
    }.getOrNull()

    /** Throws if a catalog exists but cannot be read: installed themes are then reported damaged, never silently dropped. */
    fun readCatalog(): Catalog {
        if (!catalogFile.isFile) return Catalog(0, emptyMap())
        val o = readJson(catalogFile); require(o.getInt("schema") == 1)
        val r = o.getJSONObject("records")
        return Catalog(o.getLong("seq"), r.keys().asSequence().associateWith { r.getString(it) })
    }

    fun refreshCatalog(): Catalog? = runCatching { readCatalog() }.getOrNull().also { _catalog.value = it }

    enum class LoadState { NONE, LOADED, FAILED }

    /** Startup: reads the generation named by `active.json`, verifying every file hash. A mismatch is a failure, never a mix. */
    fun loadActive(): LoadState {
        val pointer = readActivePointer() ?: return if (activeFile.exists()) LoadState.FAILED else LoadState.NONE
        val dir = File(generations, pointer.gen)
        pin(dir).use {
            val gen = runCatching { readGeneration(dir, pointer.seq) }.getOrNull() ?: return LoadState.FAILED
            _active.value = gen
            return LoadState.LOADED
        }
    }

    private fun readGeneration(dir: File, seq: Long): ActiveGeneration {
        val g = readJson(File(dir, "gen.json"))
        require(g.getInt("schema") == 1)
        val hashes = g.getJSONObject("files")
        for (name in hashes.keys()) require(sha256(files.read(File(dir, name), if (name.endsWith(".webp")) MAX_IMAGE else MAX_JSON)) == hashes.getString(name)) { "hash $name" }
        require(hashes.has("tokens.json"))
        val tokens = TokensJson.decode(readJson(File(dir, "tokens.json")))
        val choice = ThemeChoice.fromJson(g.getJSONObject("choice"))
        val bg = if (hashes.has("home_bg.webp")) File(dir, "home_bg.webp") else null
        require((bg != null) == choice.background.hasImage)
        val count = if (choice.background.kind == BackgroundKind.IMAGE) choice.slots.size else if (bg != null) 1 else 0
        val all = (0 until count).map { File(dir, homeImageName(it)) }
        all.forEach { require(hashes.has(it.name)) { "missing ${it.name}" } }
        val first = Protection.decode(g.optString("protection"))
        val prots = g.optJSONArray("protections")?.let { a -> List(a.length()) { Protection.decode(a.getString(it)) } } ?: listOf(first)
        return ActiveGeneration(g.getString("gen"), seq, g.getString("name"), choice, tokens,
            ZoneSignature.decode(g.optString("signature")), first, bg, all, prots)
    }

    fun loadRecord(id: String): LoadedRecord? {
        if (id.startsWith("bundled:")) return bundled.load(id)
        val target = runCatching { readCatalog() }.getOrNull()?.records?.get(id) ?: return null
        val dir = File(records, target)
        return runCatching {
            val record = ThemeRecord.fromJson(readJson(File(dir, "manifest.json")))
            require(record.id == id)
            val parsed = ColorsToml.parse(files.read(File(dir, "colors.toml"), ColorsToml.MAX_BYTES))
            LoadedRecord(record, target.substringAfter('/'), OmarchyResolver.palette(OmarchyResolver.resolve(parsed.values, false)).palette, dir)
        }.getOrNull()
    }

    // ---- sequencing ----

    private fun nextSeq(): Long = synchronized(lock) {
        if (counter < 0) counter = maxOf(readActivePointer()?.seq ?: 0, runCatching { readCatalog().seq }.getOrDefault(0))
        ++counter
    }

    private fun recordValid(id: String, rev: String): Boolean =
        if (id.startsWith("bundled:")) rev == BUNDLED_REV && id in bundled.ids
        else runCatching { readCatalog() }.getOrNull()?.records?.get(id)?.substringAfter('/') == rev

    // ---- apply (THEME_ARCHITECTURE.md §8.1) ----

    /** A fully written and validated staged generation, not yet committed. */
    inner class Prepared internal constructor(val stage: File, val gen: ActiveGeneration, val startup: StartupSubset, private val pin: Pin) : AutoCloseable {
        override fun close() { pin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
    }

    fun apply(recordId: String, background: BackgroundChoice, framing: Framing, strength: Strength, signature: ZoneSignature?,
              cancelled: () -> Boolean = { false }, extras: List<ImageSlot> = emptyList(), mode: BackgroundMode = BackgroundMode.FIXED): Outcome {
        val seq = nextSeq()
        latestApply = seq
        val admitted = { seq == latestApply && !cancelled() }
        val record = loadRecord(recordId) ?: return Outcome.Failed("That theme is no longer installed.")
        pin(record.dir).use {
            val valid = if (background.kind == BackgroundKind.IMAGE) extras.filter { it.index in record.record.backgrounds.indices && it.index != background.index }
                .distinctBy { it.index }.take(MAX_THEME_IMAGES - 1) else emptyList()
            val choice = ThemeChoice(recordId, record.rev, background, framing, strength, valid, if (valid.isEmpty()) BackgroundMode.FIXED else mode)
            val prepared = when (val p = prepare(record, choice, signature, seq, admitted)) { is Prepared -> p; is Outcome -> return p; else -> error("") }
            prepared.use {
                val outcome = synchronized(lock) {
                    if (!admitted()) return Outcome.Superseded
                    if (!recordValid(recordId, record.rev)) return Outcome.Failed("That theme was removed.")
                    commitGenerationLocked(prepared)
                }
                if (outcome is Outcome.Applied) gc()
                return outcome
            }
        }
    }

    /** Prepare + validate into staging (outside the lock). Returns a [Prepared] or a terminal [Outcome]. */
    private fun prepare(record: LoadedRecord, choice: ThemeChoice, signature: ZoneSignature?, seq: Long, admitted: () -> Boolean): Any {
        val stage = File(staging, UUID.randomUUID().toString())
        val pin = pin(stage)
        var done = false
        try {
            ensureDir(staging); files.mkdir(stage)
            val tokens = TokenMapper.map(record.palette)
            if (tokens.violations().isNotEmpty()) return Outcome.Failed("The theme's colours couldn't be used.")
            if (!admitted()) return Outcome.Superseded
            val image = choice.background.hasImage
            if (image && signature == null) return Outcome.Failed("The theme couldn't be prepared.")
            // One prepared Home image per slot (multi-image themes, canonical decision 2026-09-29); grounds have one.
            val slots = if (choice.background.kind == BackgroundKind.IMAGE) choice.slots.take(MAX_THEME_IMAGES) else if (image) listOf(null) else emptyList()
            val protections = slots.mapIndexed { i, slot ->
                val one = if (slot == null) choice else choice.copy(background = BackgroundChoice(BackgroundKind.IMAGE, slot.index), framing = slot.framing, extras = emptyList())
                if (!admitted()) return Outcome.Superseded
                preparer.prepare(record, one, tokens, signature!!, stage, { !admitted() }, homeImageName(i))
            }
            val protection = protections.firstOrNull()
            if (!admitted()) return Outcome.Superseded
            val tokenBytes = TokensJson.encode(tokens).toString().toByteArray()
            files.write(File(stage, "tokens.json"), tokenBytes)
            val hashes = JSONObject().put("tokens.json", sha256(tokenBytes))
            for (i in slots.indices) hashes.put(homeImageName(i), sha256(files.read(File(stage, homeImageName(i)), MAX_IMAGE)))
            val gen = UUID.randomUUID().toString()
            val genJson = JSONObject().put("schema", 1).put("gen", gen).put("seq", seq).put("recordId", record.record.id).put("recordRev", record.rev)
                .put("name", record.record.name).put("choice", choice.toJson()).put("files", hashes)
            if (image) genJson.put("signature", signature!!.encode()).put("protection", protection!!.encode())
                .put("protections", org.json.JSONArray(protections.map { it.encode() }))
            files.write(File(stage, "gen.json"), genJson.toString().toByteArray())
            // Validate by reading the staged generation back, exactly as startup will.
            val back = runCatching { readGeneration(stage, seq) }.getOrNull() ?: return Outcome.Failed("The theme couldn't be prepared.")
            if (slots.indices.any { !preparer.check(File(stage, homeImageName(it)), signature!!) }) return Outcome.Failed("The theme couldn't be prepared.")
            val startup = StartupSubset(tokens.ground, tokens.isLight, protection?.statusLight ?: tokens.isLight, protection?.navLight ?: tokens.isLight)
            done = true
            return Prepared(stage, back, startup, pin)
        } catch (_: CancelledException) {
            return Outcome.Superseded
        } catch (_: IOException) {
            return Outcome.Failed(STORAGE_FAILED)
        } finally {
            if (!done) { pin.close(); if (stage.exists()) runCatching { files.deleteTree(stage) } }
        }
    }

    /** Must hold [lock]. Rename the generation in, then replace the pointer: the pointer rename is the commit point. */
    private fun commitGenerationLocked(p: Prepared): Outcome {
        try {
            ensureDir(generations)
            val target = File(generations, p.gen.gen)
            files.rename(p.stage, target)
            files.syncDir(generations)
            val s = p.startup
            val pointer = JSONObject().put("schema", 1).put("seq", p.gen.seq).put("gen", p.gen.gen)
                .put("startup", JSONObject().put("ground", "#%06x".format(s.ground)).put("light", s.light).put("statusLight", s.statusLight).put("navLight", s.navLight))
            val tmp = File(root, "active.json.tmp")
            files.write(tmp, pointer.toString().toByteArray())
            files.rename(tmp, activeFile)
        } catch (_: IOException) {
            return Outcome.Failed(STORAGE_FAILED)
        }
        val genDir = File(generations, p.gen.gen)
        val published = p.gen.copy(background = p.gen.background?.let { File(genDir, it.name) }, backgrounds = p.gen.backgrounds.map { File(genDir, it.name) })
        try {
            files.syncDir(root)
        } catch (_: IOException) {
            // Uncertain outcome: keep both generations, reconcile with the readable pointer, never claim a rollback.
            loadActive()
            return Outcome.Uncertain("Theme saved, but durability could not be confirmed.")
        }
        _active.value = published
        return Outcome.Applied(published)
    }

    // ---- records (THEME_ARCHITECTURE.md §8.2) ----

    /** A pinned staging directory for an importer; the importer writes only through [files]. */
    fun newStaging(): Pair<File, Pin> {
        ensureDir(staging)
        val dir = File(staging, UUID.randomUUID().toString())
        val pin = pin(dir)
        files.mkdir(dir)
        return dir to pin
    }

    fun beginRecordRequest(id: String): Long { val seq = nextSeq(); synchronized(lock) { recordRequests[id] = seq }; return seq }

    /** Commits a staged record revision (manifest.json, colors.toml, images) and publishes the catalog delta. */
    fun commitRecord(stage: File, id: String, request: Long, expectedRev: String? = null): Outcome {
        val manifest = runCatching { ThemeRecord.fromJson(readJson(File(stage, "manifest.json"))) }.getOrNull()
        if (manifest == null || manifest.id != id) return Outcome.Failed("The theme couldn't be prepared.")
        val validPalette = runCatching { OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(files.read(File(stage, "colors.toml"), ColorsToml.MAX_BYTES)).values, false)) }.isSuccess
        val validImages = manifest.backgrounds.all { b -> runCatching { sha256(files.read(File(stage, b.file), MAX_IMAGE)) == b.sha256 && File(stage, b.thumb).isFile }.getOrDefault(false) }
        val validDesktop = manifest.desktopVariants.let { variants ->
            variants.size <= manifest.backgrounds.size && variants.map { it.index }.distinct().size == variants.size &&
                variants.all { v -> v.index in manifest.backgrounds.indices && v.file == "desktop-${v.index}.jpg" &&
                    v.width in 1..4096 && v.height in 1..4096 &&
                    runCatching { sha256(files.read(File(stage, v.file), MAX_IMAGE)) == v.sha256 }.getOrDefault(false) }
        }
        if (!validPalette || !validImages || !validDesktop) return Outcome.Failed("The theme couldn't be prepared.")
        val rev = UUID.randomUUID().toString()
        val outcome = synchronized(lock) {
            if (recordRequests[id] != request) return Outcome.Superseded
            try {
                val catalog = readCatalog()
                if (expectedRev != null && catalog.records[id]?.substringAfter('/') != expectedRev) return Outcome.Superseded
                ensureDir(records)
                val recDir = File(records, recordDir(id))
                ensureDir(recDir)
                files.rename(stage, File(recDir, rev))
                files.syncDir(recDir)
                commitCatalogLocked(catalog, catalog.records + (id to "${recordDir(id)}/$rev"))
            } catch (_: IOException) { return Outcome.Failed(STORAGE_FAILED) }
            catch (_: Exception) { return Outcome.Failed("Installed themes couldn't be read, so nothing was changed.") }
        }
        if (outcome is Outcome.Uncertain) return outcome
        gc()
        return Outcome.Installed(id, rev)
    }

    /** Must hold [lock]. Applies only this job's delta to the catalog read inside the lock. */
    private fun commitCatalogLocked(current: Catalog, next: Map<String, String>): Outcome {
        val json = JSONObject().put("schema", 1).put("seq", current.seq + 1).put("records", JSONObject(next as Map<*, *>))
        val tmp = File(root, "catalog.json.tmp")
        files.write(tmp, json.toString().toByteArray())
        files.rename(tmp, catalogFile)
        try { files.syncDir(root) } catch (_: IOException) {
            refreshCatalog(); return Outcome.Uncertain("Theme saved, but durability could not be confirmed.")
        }
        _catalog.value = Catalog(current.seq + 1, next)
        return Outcome.Removed
    }

    /**
     * Remove (THEME_ARCHITECTURE.md §4.8): if the theme is active, commit the fallback generation first; only if that
     * succeeded, commit the catalog without the record, with no other commit admitted between them.
     */
    fun remove(id: String, name: String, signature: ZoneSignature?): Outcome {
        if (id.startsWith("bundled:")) return Outcome.Failed("Included themes can't be removed.")
        val request = beginRecordRequest(id)
        val couldNotSwitch = Outcome.Failed("Couldn't switch themes, so $name was not removed.")
        val prepared: Prepared? = if (_active.value?.choice?.recordId == id) {
            latestApply = request
            val fallback = bundled.load(FALLBACK_ID) ?: return couldNotSwitch
            val image = signature != null
            val bg = if (image) BackgroundChoice(BackgroundKind.IMAGE, 0) else BackgroundChoice(BackgroundKind.PLAIN)
            val choice = ThemeChoice(FALLBACK_ID, BUNDLED_REV, bg,
                extras = if (image) listOf(ImageSlot(1, Framing()), ImageSlot(2, Framing())) else emptyList(),
                mode = if (image) BackgroundMode.ROTATE else BackgroundMode.FIXED)
            when (val p = prepare(fallback, choice, signature, request) { latestApply == request }) {
                is Prepared -> p; else -> return couldNotSwitch
            }
        } else null
        try {
            synchronized(lock) {
                if (recordRequests[id] != request) return Outcome.Superseded
                val catalog = try { readCatalog() } catch (_: Exception) { return Outcome.Failed("Installed themes couldn't be read, so nothing was changed.") }
                if (id !in catalog.records) return Outcome.Removed
                if (_active.value?.choice?.recordId == id) {
                    if (prepared == null || latestApply != request) return couldNotSwitch
                    when (commitGenerationLocked(prepared)) { is Outcome.Applied -> Unit; else -> return couldNotSwitch }
                }
                try {
                    val r = commitCatalogLocked(catalog, catalog.records - id)
                    if (r is Outcome.Uncertain) return r
                } catch (_: IOException) { return Outcome.Failed(STORAGE_FAILED) }
            }
        } finally { prepared?.close() }
        gc()
        return Outcome.Removed
    }

    // ---- directories and garbage collection ----

    /** Creates [dir] if needed and syncs its parent, so a new entry is durable before anything inside it is published. */
    private fun ensureDir(dir: File) {
        if (dir.isDirectory) return
        dir.parentFile?.let { if (it != root.parentFile) ensureDir(it) }
        files.mkdir(dir)
        dir.parentFile?.let { files.syncDir(it) }
    }

    /**
     * Deletes unreferenced, unpinned app-owned outputs: after a committed job and when Themes opens, never at startup or Home.
     * If a pointer cannot be read, the directories it could reference are left alone.
     */
    fun gc() = synchronized(lock) {
        val pointer = readActivePointer()
        if (pointer != null || !activeFile.exists()) {
            val keep = setOfNotNull(pointer?.gen, _active.value?.gen)
            generations.listFiles()?.forEach { if (it.name !in keep && !pinned(it)) runCatching { files.deleteTree(it) } }
        }
        staging.listFiles()?.forEach { if (!pinned(it)) runCatching { files.deleteTree(it) } }
        val catalog = runCatching { readCatalog() }.getOrNull() ?: return@synchronized
        val keep = catalog.records.values.toSet()
        records.listFiles()?.forEach { recDir ->
            recDir.listFiles()?.forEach { rev -> if ("${recDir.name}/${rev.name}" !in keep && !pinned(rev)) runCatching { files.deleteTree(rev) } }
            if (recDir.listFiles().isNullOrEmpty()) runCatching { files.deleteTree(recDir) }
        }
        File(root, "active.json.tmp").delete(); File(root, "catalog.json.tmp").delete()
    }
}
