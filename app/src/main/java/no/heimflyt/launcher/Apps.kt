package no.heimflyt.launcher

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.LruCache
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.heimflyt.launcher.theme.ThemeRuntime

data class AppEntry(val info: LauncherActivityInfo, val label: String, val serial: Long) {
    val key: String get() = "${info.componentName.flattenToString()}@$serial"
}
data class AppCatalog(val apps: List<AppEntry> = emptyList(), val loaded: Boolean = false, val warning: String? = null,
    /** Every profile Android listed for this refresh was enumerated while available (see [scanProfiles]). */
    val complete: Boolean = false) {
    /**
     * Proof of what is installed: a loaded, complete refresh with no warning. Anything else (a profile removed, locked,
     * paused or failing, or a failed refresh that kept the previous list) may lack destinations: it may be shown, but must
     * never be treated as proof that a destination was removed.
     */
    val authoritative: Boolean get() = loaded && complete && warning == null
}

/** One refresh's profile enumeration: entries with their profile serial, the visible warning, and whether it is complete. */
data class ProfileScan<A>(val entries: List<Pair<A, Long>>, val warning: String?, val complete: Boolean)

/**
 * Enumerates [profiles] (those LauncherApps lists for this launcher). A profile counts as enumerated only if its serial is
 * valid and it is [available] (unlocked and not paused) both before and after listing its activities: LauncherApps
 * answers an unavailable profile with an empty list rather than an error, so an empty list alone proves nothing, but an
 * empty list from a profile that stayed available is a genuine, authoritative empty profile.
 * Unavailable profiles stay silent (no warning, as before); they only make the scan incomplete.
 */
fun <P, A> scanProfiles(profiles: List<P>, serial: (P) -> Long, available: (P) -> Boolean, activities: (P) -> List<A>): ProfileScan<A> {
    val entries = mutableListOf<Pair<A, Long>>()
    var warning: String? = null
    var complete = true
    for (profile in profiles) {
        try {
            val s = serial(profile)
            // -1: Android no longer knows this profile (being removed); nothing it returns can be trusted.
            if (s < 0 || !available(profile)) { complete = false; continue }
            val listed = activities(profile)
            // Locked or paused during the call: its answer may be an empty stand-in.
            if (!available(profile)) { complete = false; continue }
            listed.forEach { entries += it to s }
        } catch (_: SecurityException) { warning = "A profile is unavailable."; complete = false }
        catch (_: IllegalStateException) { warning = "Unlock the profile to see its apps."; complete = false }
    }
    return ProfileScan(entries, warning, complete)
}

class HeimflytApplication : Application() {
    val semanticActions = SemanticActions()
    val apps by lazy { AppRepository(this) }
    val settings by lazy { SettingsStore(this) }
    val tags by lazy { TagStore(this) { tag, key -> settings.removeTagChild(tag, key) } }
    val searchSources by lazy { SearchSources(this) }
    val recents by lazy { RecentTargets(this) }
    val launchCounts by lazy { LaunchCounts(this) }
    /** H6.0 Browse layout (slots, experiment renderer). Read when Apps opens, never on Home. */
    val browse by lazy { no.heimflyt.launcher.ui.browse.BrowseStore(this) }
    val themes by lazy { ThemeRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        // Reads the active generation's tokens and decodes its wallpaper on IO; nothing here runs on the main thread.
        themes.start()
    }

    /** R4: only genuine memory pressure evicts the retained wallpaper; UI_HIDDEN (every app launch) does not. */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        @Suppress("DEPRECATION")
        if (level >= TRIM_MEMORY_BACKGROUND || level == TRIM_MEMORY_RUNNING_CRITICAL) themes.wallpaper.evict()
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() { super.onLowMemory(); themes.wallpaper.evict() }
}

/** One process-owned callback. Work exists only for refresh requests, never a service or poller. */
class AppRepository(private val context: Context) {
    private val launcher = context.getSystemService(LauncherApps::class.java)
    private val users = context.getSystemService(UserManager::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val state = MutableStateFlow(AppCatalog())
    val catalog = state.asStateFlow()
    private val boundChanges = MutableStateFlow(0L)
    val boundRevision = boundChanges.asStateFlow()
    private val icons = object : LruCache<String, Bitmap>(4 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = changed()
        override fun onPackageAdded(packageName: String, user: UserHandle) = changed()
        override fun onPackageChanged(packageName: String, user: UserHandle) = changed()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = changed()
    }
    init {
        launcher.registerCallback(callback, Handler(Looper.getMainLooper()))
        scope.launch {
            for (request in requests) {
                try {
                    val scan = scanProfiles(launcher.profiles, users::getSerialNumberForUser,
                        { users.isUserUnlocked(it) && !users.isQuietModeEnabled(it) }, { launcher.getActivityList(null, it) })
                    val entries = scan.entries.filter { (info, _) -> info.componentName.packageName != context.packageName }
                        .map { (info, serial) -> AppEntry(info, info.label.toString(), serial) }
                    state.value = AppCatalog(entries.sortedBy { it.label.lowercase() }, true, scan.warning, scan.complete)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    state.value = state.value.copy(loaded = true, warning = "Could not refresh apps. Retry or unlock your phone.", complete = false)
                }
            }
        }
    }
    private fun changed() { icons.evictAll(); boundChanges.value++; if(state.value.loaded) refresh() }
    fun refresh() { requests.trySend(Unit) }
    suspend fun icon(app: AppEntry): Bitmap? = withContext(Dispatchers.IO) { icons.get(app.key) ?: render(app.info, app.key) }

    private fun render(info: LauncherActivityInfo, key: String): Bitmap? = try {
        val drawable = info.getBadgedIcon(context.resources.displayMetrics.densityDpi)
        val size = (48 * context.resources.displayMetrics.density).toInt().coerceIn(48, 192)
        Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also {
            drawable.setBounds(0, 0, size, size); drawable.draw(Canvas(it)); icons.put(key, it)
        }
    } catch (_: Exception) { null }

    /**
     * Icon for one explicitly bound component/profile (radial labels). A bounded lookup of that package in that profile:
     * never full catalog enumeration. Missing or unavailable targets return null and the label is drawn without an icon.
     */
    suspend fun boundIcon(component: String, serial: Long): Bitmap? = withContext(Dispatchers.IO) {
        val key = "$component@$serial"
        icons.get(key) ?: try {
            val user = users.getUserForSerialNumber(serial) ?: return@withContext null
            val name = ComponentName.unflattenFromString(component) ?: return@withContext null
            launcher.getActivityList(name.packageName, user).firstOrNull { it.componentName == name }?.let { render(it, key) }
        } catch (_: RuntimeException) { null }
    }
    fun launch(action: HomeAction.App): Boolean = try {
        val user = users.getUserForSerialNumber(action.userSerial)
        val component = ComponentName.unflattenFromString(action.component)
        if (user == null || component == null || !launcher.isActivityEnabled(component, user)) false
        else { launcher.startMainActivity(component, user, null, null); true }
    } catch (_: RuntimeException) { false }

    fun details(app: AppEntry): Boolean = try {
        launcher.startAppDetailsActivity(app.info.componentName, app.info.user, null, null); true
    } catch (_: RuntimeException) { false }

    fun shortcuts(): List<ShortcutEntry> {
        if (!launcher.hasShortcutHostPermission()) return emptyList()
        val result=mutableListOf<ShortcutEntry>()
        for (profile in launcher.profiles) try {
            val serial=users.getSerialNumberForUser(profile)
            val query=LauncherApps.ShortcutQuery().setQueryFlags(
                LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED)
            launcher.getShortcuts(query,profile)?.forEach { shortcut ->
                if (shortcut.isEnabled && serial>=0) result+=ShortcutEntry(shortcut.`package`,shortcut.id,
                    shortcut.shortLabel?.toString() ?: shortcut.longLabel?.toString() ?: shortcut.id,serial)
            }
        } catch (_: SecurityException) { } catch (_: IllegalStateException) { }
        return result.sortedWith(compareBy({it.label.lowercase()},{it.packageName},{it.id}))
    }

    fun hasShortcutAccess()=launcher.hasShortcutHostPermission()

    fun launchShortcut(entry: ShortcutEntry): Boolean = try {
        val user=users.getUserForSerialNumber(entry.userSerial) ?: return false
        launcher.startShortcut(entry.packageName,entry.id,null,null,user); true
    } catch (_: RuntimeException) { false }
}
