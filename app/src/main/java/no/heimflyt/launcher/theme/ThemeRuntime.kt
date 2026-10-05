package no.heimflyt.launcher.theme

import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.heimflyt.launcher.theme.image.BackgroundProcessor
import no.heimflyt.launcher.theme.image.ZoneSignature
import no.heimflyt.launcher.theme.palette.ColorsToml
import no.heimflyt.launcher.theme.palette.OmarchyResolver
import no.heimflyt.launcher.theme.palette.PaletteSource
import no.heimflyt.launcher.theme.store.AndroidThemeFiles
import no.heimflyt.launcher.theme.store.BackgroundChoice
import no.heimflyt.launcher.theme.store.BackgroundKind
import no.heimflyt.launcher.theme.store.BundledSource
import no.heimflyt.launcher.theme.store.Framing
import no.heimflyt.launcher.theme.store.LoadedRecord
import no.heimflyt.launcher.theme.store.Outcome
import no.heimflyt.launcher.theme.store.Strength
import no.heimflyt.launcher.theme.store.StoredBackground
import no.heimflyt.launcher.theme.store.ThemeOrigin
import no.heimflyt.launcher.theme.store.ThemeRecord
import no.heimflyt.launcher.theme.store.ThemeStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** An included theme: an Omarchy first-party palette (MIT) with a Heimflyt Ground; no third-party images (THEME_ARCHITECTURE.md §9). */
class BundledTheme(val slug: String, val name: String, val ground: BackgroundKind, val credit: String) { val id get() = "bundled:$slug" }

object BundledThemes {
    val all = listOf(
        BundledTheme("krets", "Krets", BackgroundKind.KRETS, "Original Krets palette and owner-created images"),
        BundledTheme("tokyo-night", "Tokyo Night", BackgroundKind.DUSK, "Tokyo Night by enkia (MIT)"),
        BundledTheme("matte-black", "Matte Black", BackgroundKind.PLAIN, "Matte Black from Omarchy (MIT)"),
        BundledTheme("gruvbox", "Gruvbox", BackgroundKind.CONTOUR, "Gruvbox Material by sainnhe (MIT)"),
        BundledTheme("everforest", "Everforest", BackgroundKind.DUSK, "Everforest by sainnhe (MIT)"),
        BundledTheme("kanagawa", "Kanagawa", BackgroundKind.CONTOUR, "Kanagawa by Tommaso Laurenzi (MIT)"),
        BundledTheme("flexoki-light", "Flexoki Light", BackgroundKind.DUSK, "Flexoki by Steph Ango (MIT)"),
        BundledTheme("catppuccin-latte", "Catppuccin Latte", BackgroundKind.CONTOUR, "Catppuccin by the Catppuccin org (MIT)"),
    )
    fun find(id: String) = all.firstOrNull { it.id == id }
}

/** Reads bundled palettes from `assets/themes/<slug>/colors.toml` on first use (Themes, apply), never on Home. */
class AssetBundledSource(private val assets: AssetManager) : BundledSource {
    private val cache = ConcurrentHashMap<String, LoadedRecord>()
    override val ids get() = BundledThemes.all.map { it.id }
    override fun load(id: String): LoadedRecord? {
        val theme = BundledThemes.find(id) ?: return null
        return cache[id] ?: runCatching {
            val bytes = assets.open("themes/${theme.slug}/colors.toml").use { it.readBytes() }
            val palette = OmarchyResolver.palette(OmarchyResolver.resolve(ColorsToml.parse(bytes).values, false)).palette
            val backgrounds = if (theme.slug == "krets") listOf(
                StoredBackground("themes/krets/blue.jpg", "", "Blue", 1008, 1792, "4c17da0366fe71087463565beda6502af28a6d24f92f37a402669ecab3d7cfb6", false),
                StoredBackground("themes/krets/plum.jpg", "", "Plum", 1008, 1792, "1650d13ceb065b1b1a0fd3b191c409bd9bbad6291d836474c4e344bbcf5804a5", false),
                StoredBackground("themes/krets/jade.jpg", "", "Jade", 1008, 1792, "8b386b7b6fd48fe3bb74542af740fb5f6a8ea4e78515ac516fde0bfb4e8f996c", false),
            ) else emptyList()
            LoadedRecord(ThemeRecord(id, theme.name, ThemeOrigin.Bundled, palette.light, PaletteSource.BUNDLED, backgrounds, null, null, emptyList(), null, theme.ground),
                ThemeStore.BUNDLED_REV, palette, null)
        }.getOrNull()?.also { cache[id] = it }
    }
}

/**
 * R4: one retained, bounded wallpaper bitmap for the active generation. Decodes only at process start, after apply, on a
 * cache miss after genuine eviction, and (multi-image themes) at the rotation boundary: the screen turning off while
 * Heimflyt is in front. Every decode is tagged with its generation and slot and published only if still current.
 */
class WallpaperHolder(private val store: ThemeStore, private val scope: CoroutineScope, private val prefs: android.content.SharedPreferences) {
    /**
     * Android wallpaper follow: when set, a rotation is handed to it and the new slot is shown only after Android has set
     * the same image (via [commit]), so Heimflyt and the lock screen change together. Returns false when not following.
     */
    var onRotated: (gen: String, slot: Int) -> Boolean = { _, _ -> false }
    class Prepared(val gen: String, val slot: Int, val bitmap: Bitmap)
    private val _state = MutableStateFlow<Prepared?>(null)
    val state: StateFlow<Prepared?> = _state.asStateFlow()
    private var pending: Job? = null
    private var pendingKey: String? = null

    /** The slot to show for the active generation: persisted across process death, 0 for a new generation. */
    private fun slotFor(active: no.heimflyt.launcher.theme.store.ActiveGeneration): Int =
        if (prefs.getString("gen", null) == active.gen) prefs.getInt("slot", 0).coerceIn(0, maxOf(0, active.backgrounds.size - 1)) else 0

    /** Called at process start, after apply and when Home becomes visible. A retained bitmap for the current slot is a no-op. */
    @Synchronized fun ensure() {
        val active = store.active.value
        if (active == null || active.backgrounds.isEmpty()) { cancelPending(); _state.value = null; return }
        val slot = slotFor(active)
        val file = active.backgrounds[slot]
        if (_state.value?.let { it.gen == active.gen && it.slot == slot } == true) return
        val key = "${active.gen}:$slot"
        if (pendingKey == key && pending?.isActive == true) return
        cancelPending()
        // A different generation's or slot's bitmap is never drawn; drop it rather than hold two.
        if (_state.value != null) _state.value = null
        val gen = active.gen
        pendingKey = key
        pending = scope.launch(Dispatchers.IO) {
            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
            val bmp = store.pin(file.parentFile).use { runCatching { decode(file) }.getOrNull() } ?: return@launch
            synchronized(this@WallpaperHolder) {
                val current = store.active.value?.gen == gen && store.active.value?.let { slotFor(it) } == slot
                if (current) _state.value = Prepared(gen, slot, bmp)
                android.util.Log.i("HeimflytTheme", "wallpaper decode slot $slot ${(android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000} ms, published=$current")
            }
        }
    }

    /**
     * The rotation boundary (canonical decision 2026-09-29): Rotate advances to the next image, Random picks another one,
     * Fixed does nothing. Called only when the screen turns off with Heimflyt in front; the next image is decoded then, off
     * the main thread, so the next Home shows it with no work.
     */
    @Synchronized fun advance() {
        val active = store.active.value ?: return
        val n = active.backgrounds.size
        if (n < 2 || active.choice.mode == no.heimflyt.launcher.theme.store.BackgroundMode.FIXED) return
        val slot = slotFor(active)
        val next = if (active.choice.mode == no.heimflyt.launcher.theme.store.BackgroundMode.ROTATE) (slot + 1) % n
                   else (slot + 1 + kotlin.random.Random.nextInt(n - 1)) % n
        android.util.Log.i("HeimflytTheme", "background rotation ${active.choice.mode} $slot -> $next")
        if (!onRotated(active.gen, next)) commit(active.gen, next)
    }

    /** Shows [slot] of [gen] from now on (persisted), decoding it off the main thread; ignored if the generation changed. */
    @Synchronized fun commit(gen: String, slot: Int) {
        if (store.active.value?.gen != gen) return
        prefs.edit().putString("gen", gen).putInt("slot", slot).apply()
        ensure()
    }

    /** Genuine memory pressure only: drop the reference (never recycle a bitmap Compose may still draw). */
    @Synchronized fun evict() { cancelPending(); if (_state.value != null) android.util.Log.i("HeimflytTheme", "wallpaper evicted"); _state.value = null }

    private fun cancelPending() { pending?.cancel(); pending = null; pendingKey = null }

    private fun decode(file: File): Bitmap? =
        if (Build.VERSION.SDK_INT >= 28) ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.allocator = ImageDecoder.ALLOCATOR_HARDWARE }
        else BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 })
}

/** Application-scope theme state: the store, the published generation and the retained wallpaper. No service, worker or poller. */
class ThemeRuntime(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val bundled = AssetBundledSource(context.assets)
    val processor = BackgroundProcessor(AndroidThemeFiles, context.assets)
    val store = ThemeStore(File(context.filesDir, "themes"), AndroidThemeFiles, bundled, processor)
    val wallpaper = WallpaperHolder(store, scope, context.getSharedPreferences("theme_rotation", Context.MODE_PRIVATE))
    /** Set once when a committed generation couldn't be read at startup (the compiled fallback is used meanwhile). */
    val loadFailed = MutableStateFlow(false)
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()
    private val _busy = MutableStateFlow(false)
    /** An apply or re-preparation is running (Themes shows progress; Home never waits on it). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()
    @Volatile private var repreparedFor: ZoneSignature? = null
    /** A theme .zip shared to or opened with Heimflyt, waiting for the owner's Report (never installed without it). */
    val pendingImport = MutableStateFlow<android.net.Uri?>(null)

    private val appContext = context.applicationContext

    init {
        // Opt-in only: when Heimflyt's image rotates (screen off), Android's wallpaper follows with the same image.
        wallpaper.onRotated = { gen, slot ->
            if (AndroidWallpaper.follow(appContext) == AndroidWallpaper.NONE) false
            else { followRequests.trySend(FollowRequest(gen, slot)); true }
        }
    }

    /**
     * Follow requests are conflated and handled by one worker, so quick screen off/on cycles never run two wallpaper sets
     * at once and the newest image always wins (a set takes ~1.5 s with the screen on, 6–8 s while it is off).
     */
    private class FollowRequest(val gen: String, val slot: Int, val commit: Boolean = true)
    private val followRequests = kotlinx.coroutines.channels.Channel<FollowRequest>(kotlinx.coroutines.channels.Channel.CONFLATED)
    init {
        scope.launch {
            for (r in followRequests) {
                val which = AndroidWallpaper.follow(appContext)
                val active = store.active.value
                val s = active?.takeIf { it.gen == r.gen }?.choice?.slots?.getOrNull(r.slot)
                if (which != AndroidWallpaper.NONE && s != null) store.loadRecord(active.choice.recordId)?.let { AndroidWallpaper.set(appContext, store, it, s.index, s.framing, which) }
                // Heimflyt shows the new image only now, so it and the lock screen change together (even if the set failed).
                if (r.commit) wallpaper.commit(r.gen, r.slot)
            }
        }
    }

    /** Sets Android's wallpaper to the active generation's image [slot] if the owner opted in to "follow Heimflyt". */
    fun followAndroidWallpaper(slot: Int) {
        val gen = store.active.value?.gen ?: return
        if (AndroidWallpaper.follow(appContext) != AndroidWallpaper.NONE) followRequests.trySend(FollowRequest(gen, slot, commit = false))
    }

    /** Process start: read the active generation and decode its wallpaper, both off the main thread. */
    fun start() {
        scope.launch {
            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
            val state = store.loadActive()
            android.util.Log.i("HeimflytTheme", "tokens.json load ${(android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1000} us, $state")
            when (state) {
                ThemeStore.LoadState.FAILED -> loadFailed.value = true
                ThemeStore.LoadState.LOADED -> wallpaper.ensure()
                ThemeStore.LoadState.NONE -> Unit
            }
            _loaded.value = true
        }
    }

    suspend fun apply(recordId: String, background: BackgroundChoice, framing: Framing, strength: Strength, signature: ZoneSignature?,
                      extras: List<no.heimflyt.launcher.theme.store.ImageSlot> = emptyList(),
                      mode: no.heimflyt.launcher.theme.store.BackgroundMode = no.heimflyt.launcher.theme.store.BackgroundMode.FIXED): Outcome =
        withContext(Dispatchers.IO) {
            _busy.value = true
            val t0 = android.os.SystemClock.elapsedRealtimeNanos()
            try { store.apply(recordId, background, framing, strength, signature, extras = extras, mode = mode).also {
                if (it is Outcome.Applied || it is Outcome.Uncertain) { loadFailed.value = false; wallpaper.ensure() }
                if (it is Outcome.Applied && background.kind == no.heimflyt.launcher.theme.store.BackgroundKind.IMAGE) followAndroidWallpaper(0)
            } }
            finally { _busy.value = false; android.util.Log.i("HeimflytTheme", "apply ${background.encode()} ${(android.os.SystemClock.elapsedRealtimeNanos() - t0) / 1_000_000} ms") }
        }

    suspend fun remove(id: String, name: String, signature: ZoneSignature?): Outcome =
        withContext(Dispatchers.IO) { store.remove(id, name, signature).also { wallpaper.ensure() } }

    /**
     * THEME_ARCHITECTURE.md §8.5: a real configuration change made the active image's zones stale. One re-preparation
     * apply with the same choice; Home draws the safe backdrops until it commits.
     */
    fun reprepare(signature: ZoneSignature) {
        val active = store.active.value ?: return
        if (!active.choice.background.hasImage || active.signature == signature || repreparedFor == signature) return
        repreparedFor = signature
        scope.launch {
            val c = active.choice
            apply(c.recordId, c.background, c.framing, c.strength, signature, c.extras, c.mode)
        }
    }
}

/** The pre-content window subset of `active.json`, read synchronously in `MainActivity.onCreate` (< 1 KiB). */
fun ThemeRuntime.startup() = store.readActivePointer()?.startup
