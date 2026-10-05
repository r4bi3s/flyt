package no.heimflyt.launcher

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import no.heimflyt.launcher.gesture.Familiarity
import no.heimflyt.launcher.gesture.SlotFamiliarity
import no.heimflyt.launcher.gesture.TuningParams
import no.heimflyt.launcher.gesture.Fan
import org.json.JSONArray
import org.json.JSONObject

sealed interface HomeAction {
    data object Apps : HomeAction
    data object Search : HomeAction
    data class Tag(val name:String) : HomeAction
    data class App(val component: String, val userSerial: Long, val label: String) : HomeAction
    /** Explicit app or action destinations in four fixed fan positions; null is a hole. */
    data class Group(val name: String, val children: List<HomeAction?>, val fallbackTag: String? = null) : HomeAction {
        init { require(children.size == 4 && children.all { it == null || it is App || it is Semantic }) }
    }
    data class Semantic(val destination: SemanticDestination) : HomeAction
    /** Safe rehearsal slots for hardware checkpoint. No arbitrary command execution. */
    data class Probe(val number: Int) : HomeAction
}
fun HomeAction.label(): String = when (this) {
    HomeAction.Apps -> "Apps"
    HomeAction.Search -> "Search"
    is HomeAction.Tag -> "#$name"
    is HomeAction.App -> label
    is HomeAction.Group -> name
    is HomeAction.Semantic -> destination.label
    is HomeAction.Probe -> "Direction $number"
}
/** Familiarity identity of a binding; a different target at the same position learns from scratch. */
fun HomeAction.key(): String = when (this) {
    HomeAction.Apps -> "apps"
    HomeAction.Search -> "search"
    is HomeAction.Tag -> "tag:$name"
    is HomeAction.App -> "app:$component:$userSerial"
    is HomeAction.Group -> "group:${fallbackTag.orEmpty()}:${children.joinToString("|") { it?.key() ?: "-" }}"
    is HomeAction.Semantic -> "semantic:${destination.id}"
    is HomeAction.Probe -> "probe:$number"
}
fun HomeAction.tagMemberKey(): String? = when (this) {
    is HomeAction.App -> "$component@$userSerial"
    is HomeAction.Semantic -> "semantic:${destination.id}"
    else -> null
}
/** Home presentation preferences (H5). None of these affect recognition or dispatch. */
data class HomePrefs(
    val cleanDispatches: Int = 0,
    val hints: no.heimflyt.launcher.ui.home.HomeHints = no.heimflyt.launcher.ui.home.HomeHints.AUTO,
    /** null = locale default. */
    val weekNumber: Boolean? = null,
    val appsHintSeen: Boolean = false,
    /** Owner choice (OPEN_QUESTIONS Q3): Search field above the keyboard with results bottom-anchored, or at the top. */
    val searchAtBottom: Boolean = true,
    /** Status spike (H5.2): Android's status bar, hidden on Home, or a Heimflyt theme-aware row on Home. Other routes keep Android's. */
    val statusMode: HomeStatusMode = HomeStatusMode.ANDROID,
    /** H6.1 owner experiment: Home's "Search" word. Off leaves Search on the radial, in Directions and at the bottom of Apps. */
    val searchWord: Boolean = true,
    /** Owner finding (after H7): Apps has no other short path from Home, so it takes the thumb corner; Search moves across. */
    val appsOnThumb: Boolean = true,
    /** H7.1 disposable Agenter rehearsal. Off by default. */
    val h7: Boolean = false,
    val h7Guide: Boolean = true,
    /** H7 experiment: releasing on a child icon opens that app. Off = rehearsal, nothing opens. */
    val h7Launch: Boolean = false,
    /** Owner-tuned fan geometry; persisted only where it differs from the default. */
    val h7Fan: Fan = Fan(),
)

enum class HomeStatusMode(val id: String, val label: String) {
    ANDROID("android", "Android"), HIDDEN("hidden", "Hidden"), HEIMFLYT("heimflyt", "Flyt");
    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: ANDROID }
}

enum class TagRadialMode(val id: String, val label: String) {
    SMALL("small", "Automatic"), FIXED("fixed", "Fixed"), MOST_USED("most-used", "Most used")
}

data class LocalSettings(
    val tuning: TuningParams = TuningParams(),
    val bindings: List<HomeAction> = List(8) { HomeAction.Probe(it + 1) },
    val notice: String? = null,
    val familiarity: List<SlotFamiliarity> = emptyList(),
    val home: HomePrefs = HomePrefs(),
    /** Explicit tag choices, prepared at configuration time. Home never enumerates tag membership. */
    val tagGroups: Map<String, HomeAction.Group> = emptyMap(),
    /** Automatic mode keeps manual choices intact and publishes prepared children separately. */
    val tagModes: Map<String, TagRadialMode> = emptyMap(),
    val autoTagGroups: Map<String, HomeAction.Group> = emptyMap(),
) {
    fun tagMode(tag: String) = tagModes[tag] ?: if (tag in tagGroups) TagRadialMode.FIXED else TagRadialMode.SMALL
    val autoTags: Set<String> get() = tagModes.filterValues { it == TagRadialMode.MOST_USED }.keys
    fun activeTagGroups(): Map<String, HomeAction.Group> =
        tagGroups.filterKeys { tagMode(it) == TagRadialMode.FIXED } +
            autoTagGroups.filterKeys { tagMode(it) != TagRadialMode.FIXED }
    fun familiarityKeys() = List(8) { "${bindings[it].key()}@${Familiarity.directionKey(it, tuning)}" }
    /** Rebinding or changing a slot's direction resets only that slot's learning. */
    fun reconciled() = copy(familiarity = Familiarity.reconcile(familiarity, familiarityKeys()))
}

/** WP14: used only when a completed load confirms that no settings blob exists (never for the placeholder or corrupt storage). */
fun freshInstallSettings(): LocalSettings = LocalSettings(
    tuning = TuningParams().copy(anywhere = true),
    bindings = listOf(
        HomeAction.Semantic(SemanticDestination.PHONE), HomeAction.Semantic(SemanticDestination.MESSAGES),
        HomeAction.Semantic(SemanticDestination.BROWSER), HomeAction.Search, HomeAction.Apps,
        HomeAction.Probe(6), HomeAction.Probe(7), HomeAction.Probe(8)),
).reconciled()

/** Checkpoint persistence: tuning, bindings and per-position familiarity. No gesture log yet. */
class SettingsStore(context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val state = MutableStateFlow(LocalSettings().reconciled())
    val settings = state.asStateFlow()
    private val loadedState = MutableStateFlow(false)
    /** True once saved settings (or their confirmed absence) have been applied. Accessibility actions wait for this. */
    val loaded = loadedState.asStateFlow()
    private val writes = Channel<LocalSettings>(Channel.CONFLATED)
    private val editLock = Any()
    private var edited = false
    private var loadComplete = false
    private val prefs by lazy { context.getSharedPreferences("hardware1", Context.MODE_PRIVATE) }
    init {
        scope.launch {
            try {
                val saved = prefs.getString("settings", null)
                if (saved == null) {
                    // Confirmed absence: a fresh install gets working directions and Anywhere (WP14). Held in memory only:
                    // with no edits the next launch derives the same defaults, and the first edit or gesture persists them.
                    synchronized(editLock) { if(!edited) state.value=freshInstallSettings() }
                } else {
                    val root=JSONObject(saved)
                    val familiarity=try { decodeFamiliarity(prefs.getString("familiarity",null)) } catch (_: Exception) { emptyList() }
                    var value=decode(root).copy(familiarity=familiarity).reconciled()
                    val migrate=!root.has("home")
                    if (migrate) value=value.copy(home=value.home.copy(cleanDispatches=no.heimflyt.launcher.ui.home.migratedCleanDispatches(
                        value.familiarity.map { it.streak }, value.familiarity.any { it.level < Familiarity.FULL })))
                    synchronized(editLock) { if(!edited) { state.value=value; if(migrate) writes.trySend(value) } }
                }
            } catch (_: Exception) {
                synchronized(editLock) {
                    if(!edited) state.value = LocalSettings(notice = "Saved settings were unreadable. Safe defaults restored.").reconciled()
                }
            }
            synchronized(editLock) { loadComplete=true }
            loadedState.value = true
            for (value in writes) {
                try { prefs.edit().putString("settings", encode(value).toString()).putString("familiarity", encodeFamiliarity(value)).apply() }
                catch (_: Exception) { state.value = state.value.copy(notice = "Settings could not be saved.") }
            }
        }
    }
    fun update(tuning: TuningParams = state.value.tuning, bindings: List<HomeAction> = state.value.bindings) {
        synchronized(editLock) {
            edited=true
            val value = state.value.copy(tuning = tuning.safe(),
                bindings = List(8) { bindings.getOrElse(it) { HomeAction.Probe(it+1) } }, notice = null).reconciled()
            state.value = value; writes.trySend(value)
        }
    }
    /** Called once per finished gesture that is attributable to a slot. Ignored before saved learning has loaded. */
    fun recordGesture(slot: Int, clean: Boolean) {
        synchronized(editLock) {
            var s=state.value
            if(!loadComplete || slot !in 0 until s.tuning.sectorCount) return
            // The Home hints counter is about fluency, not guidance, so it also counts when adaptation is off.
            if(clean && s.home.cleanDispatches < 9999) { s=s.copy(home=s.home.copy(cleanDispatches=s.home.cleanDispatches+1)); state.value=s; writes.trySend(s) }
            if(!s.tuning.adaptive) return
            val value=s.copy(familiarity=s.familiarity.toMutableList().also { it[slot]=Familiarity.record(it[slot],clean,s.tuning) })
            state.value = value; writes.trySend(value)
        }
    }
    /** Home presentation preferences only; recognition, bindings and learning are untouched. */
    fun updateHome(transform: (HomePrefs) -> HomePrefs) {
        synchronized(editLock) {
            val value=state.value.copy(home=transform(state.value.home))
            state.value = value; writes.trySend(value)
        }
    }
    fun resetFamiliarity() {
        synchronized(editLock) {
            edited=true
            val value=state.value.copy(familiarity=emptyList()).reconciled()
            state.value = value; writes.trySend(value)
        }
    }

    fun setTagGroup(tag: String, group: HomeAction.Group) {
        require(normalizeTagName(tag) == tag)
        require(group.children.filterNotNull().map { it.key() }.distinct().size == group.children.count { it != null })
        synchronized(editLock) {
            if (!loadComplete) return
            val groups = if (group.children.all { it == null }) state.value.tagGroups - tag
                else state.value.tagGroups + (tag to group.copy(fallbackTag = tag))
            val modes = if (tag in state.value.tagModes) state.value.tagModes else state.value.tagModes + (tag to TagRadialMode.FIXED)
            val value = state.value.copy(tagGroups = groups, tagModes = modes)
            state.value = value; writes.trySend(value)
        }
    }

    fun setTagAutomatic(tag: String, enabled: Boolean) {
        setTagMode(tag, if (enabled) TagRadialMode.MOST_USED else TagRadialMode.FIXED)
    }

    fun setTagMode(tag: String, mode: TagRadialMode) {
        require(normalizeTagName(tag) == tag)
        synchronized(editLock) {
            if (!loadComplete) return
            if (state.value.tagModes[tag] == mode) return
            val value = state.value.copy(tagModes = state.value.tagModes + (tag to mode),
                // A previous Most used result may have come from a large tag; wait for preparation in Automatic mode.
                autoTagGroups = if (mode == TagRadialMode.SMALL) state.value.autoTagGroups - tag else state.value.autoTagGroups)
            state.value = value; writes.trySend(value)
        }
    }

    /** A complete, precomputed four-slot result; never read tag membership while drawing Home. */
    fun setAutoTagGroup(tag: String, group: HomeAction.Group) {
        synchronized(editLock) {
            if (!loadComplete || state.value.tagMode(tag) == TagRadialMode.FIXED) return
            val prepared = group.copy(fallbackTag = tag)
            if (state.value.autoTagGroups[tag] == prepared) return
            val value = state.value.copy(autoTagGroups = state.value.autoTagGroups + (tag to prepared))
            state.value = value; writes.trySend(value)
        }
    }

    /** Explicit membership removal leaves a hole. Incomplete profile scans never call this. */
    fun removeTagChild(tag: String, appKey: String) {
        synchronized(editLock) {
            if (!loadComplete) return
            state.value.tagGroups[tag]?.let { group ->
                val children = group.children.map { child -> child?.takeUnless { it.tagMemberKey() == appKey } }
                if (children != group.children) setTagGroup(tag, group.copy(children = children))
            }
            state.value.autoTagGroups[tag]?.let { auto ->
                val active = auto.children.map { child -> child?.takeUnless { it.tagMemberKey() == appKey } }
                // Clean the cached ranking even in Fixed mode: it may be reused before a complete scan.
                if (active != auto.children) {
                    val value = state.value.copy(autoTagGroups = state.value.autoTagGroups + (tag to auto.copy(children = active)))
                    state.value = value; writes.trySend(value)
                }
            }
        }
    }

    fun renameTag(old: String, new: String, displayName: String) {
        synchronized(editLock) {
            if (!loadComplete) return
            val group = state.value.tagGroups[old]
            val groups = (state.value.tagGroups - old).let { rest ->
                if (group == null) rest else rest + (new to group.copy(name = displayName, fallbackTag = new))
            }
            val auto = state.value.autoTagGroups[old]
            val autoGroups = (state.value.autoTagGroups - old).let { rest ->
                if (auto == null) rest else rest + (new to auto.copy(name = displayName, fallbackTag = new))
            }
            val value = state.value.copy(tagGroups = groups,
                tagModes = (state.value.tagModes - old).let { rest -> state.value.tagModes[old]?.let { rest + (new to it) } ?: rest },
                autoTagGroups = autoGroups,
                bindings = state.value.bindings.map { RadialGroups.renameTag(it, old, new) }).reconciled()
            state.value = value; writes.trySend(value)
        }
    }

    fun deleteTag(tag: String) {
        synchronized(editLock) {
            if (!loadComplete) return
            val value = state.value.copy(tagGroups = state.value.tagGroups - tag,
                tagModes = state.value.tagModes - tag, autoTagGroups = state.value.autoTagGroups - tag,
                bindings = state.value.bindings.map { RadialGroups.deleteTag(it, tag) }).reconciled()
            state.value = value; writes.trySend(value)
        }
    }

    private fun encodeFamiliarity(s: LocalSettings) = JSONArray().apply {
        s.familiarity.forEach { put(JSONObject().put("key",it.key).put("level",it.level).put("streak",it.streak)) }
    }.toString()
    private fun decodeFamiliarity(raw: String?): List<SlotFamiliarity> {
        val array=JSONArray(raw ?: return emptyList())
        return List(array.length()) { i ->
            val o=array.getJSONObject(i)
            SlotFamiliarity(o.getString("key"),o.optInt("level",Familiarity.FULL).coerceIn(Familiarity.MINIMAL,Familiarity.FULL),o.optInt("streak",0).coerceIn(0,9999))
        }
    }

    private fun encode(s: LocalSettings): JSONObject {
        val p=s.tuning
        return JSONObject().put("version",1).put("tuning",JSONObject().apply {
            put("leftHanded",p.leftHanded); put("edgeDistance",p.edgeDistance); put("bottomDistance",p.bottomDistance)
            put("affordanceSize",p.affordanceSize); put("deadZone",p.deadZone); put("menuRadius",p.menuRadius)
            put("arcStart",p.arcStart); put("arcSpan",p.arcSpan); put("sectorCount",p.sectorCount); put("hysteresis",p.hysteresis)
            put("tapTimeout",p.tapTimeout); put("minPress",p.minPress); put("hesitationDelay",p.hesitationDelay)
            put("dwellTime",p.dwellTime); put("dwellSpeed",p.dwellSpeed); put("initialGuidance",p.initialGuidance)
            put("hapticDown",p.hapticDown); put("hapticSelection",p.hapticSelection); put("hapticCommit",p.hapticCommit); put("debug",p.debug)
            put("anywhere",p.anywhere); put("adaptive",p.adaptive); put("hintAfter",p.hintAfter); put("minimalAfter",p.minimalAfter)
        }).put("home",JSONObject().apply {
            put("cleanDispatches",s.home.cleanDispatches); put("hints",s.home.hints.id); put("appsHintSeen",s.home.appsHintSeen); put("searchAtBottom",s.home.searchAtBottom); put("statusMode",s.home.statusMode.id); put("searchWord",s.home.searchWord)
            if(!s.home.appsOnThumb) put("appsOnThumb",false)
            s.home.weekNumber?.let { put("weekNumber",it) }
            if(s.home.h7) put("h7",true); if(!s.home.h7Guide) put("h7Guide",false); if(s.home.h7Launch) put("h7Launch",true)
            val fan=s.home.h7Fan; val d=Fan()
            if(fan.size != d.size) put("h7Size",fan.size); if(fan.beyond != d.beyond) put("h7Beyond",fan.beyond)
            if(fan.spread != d.spread) put("h7Spread",fan.spread)
        }).put("tagGroups", JSONObject().apply {
            s.tagGroups.forEach { (tag, group) -> put(tag, RadialGroups.encode(group)) }
        }).put("tagModes", JSONObject().apply { s.tagModes.forEach { (tag, mode) -> put(tag, mode.id) } })
        .put("autoTagGroups", JSONObject().apply {
            s.autoTagGroups.forEach { (tag, group) -> put(tag, RadialGroups.encode(group)) }
        }).put("bindings",JSONArray().apply {
            s.bindings.forEach { action -> put(JSONObject().apply {
                when(action) {
                    HomeAction.Apps -> put("kind","apps")
                    HomeAction.Search -> put("kind","search")
                    is HomeAction.Tag -> { put("kind","tag"); put("name",action.name) }
                    is HomeAction.Probe -> { put("kind","probe"); put("number",action.number) }
                    is HomeAction.App -> { put("kind","app"); put("component",action.component); put("serial",action.userSerial); put("label",action.label) }
                    is HomeAction.Group -> {
                        put("kind", "group"); put("name", action.name); put("fallbackTag", action.fallbackTag ?: JSONObject.NULL)
                        put("children", JSONArray().apply { action.children.forEach { child -> put(child?.let {
                            when (it) {
                                is HomeAction.App -> JSONObject().put("kind", "app").put("component", it.component).put("serial", it.userSerial).put("label", it.label)
                                is HomeAction.Semantic -> JSONObject().put("kind", "semantic").put("destination", it.destination.id)
                                else -> JSONObject.NULL
                            }
                        } ?: JSONObject.NULL) } })
                    }
                    is HomeAction.Semantic -> { put("kind","semantic"); put("destination",action.destination.id) }
                }
            }) }
        })
    }
    private fun decode(root: JSONObject): LocalSettings {
        require(root.optInt("version",1)==1)
        val p=root.getJSONObject("tuning"); val d=TuningParams()
        fun f(key:String,default:Float)=p.optDouble(key,default.toDouble()).toFloat()
        val tuning=TuningParams(
            leftHanded=p.optBoolean("leftHanded",false), edgeDistance=f("edgeDistance",d.edgeDistance), bottomDistance=f("bottomDistance",d.bottomDistance),
            affordanceSize=f("affordanceSize",d.affordanceSize), deadZone=f("deadZone",d.deadZone), menuRadius=f("menuRadius",d.menuRadius),
            arcStart=f("arcStart",d.arcStart), arcSpan=f("arcSpan",d.arcSpan), sectorCount=p.optInt("sectorCount",6), hysteresis=f("hysteresis",d.hysteresis),
            tapTimeout=p.optLong("tapTimeout",d.tapTimeout), minPress=p.optLong("minPress",0), hesitationDelay=p.optLong("hesitationDelay",d.hesitationDelay),
            dwellTime=p.optLong("dwellTime",d.dwellTime), dwellSpeed=f("dwellSpeed",d.dwellSpeed), initialGuidance=p.optInt("initialGuidance",2),
            hapticDown=p.optBoolean("hapticDown",false), hapticSelection=p.optBoolean("hapticSelection",true), hapticCommit=p.optBoolean("hapticCommit",true), debug=p.optBoolean("debug",d.debug),
            anywhere=p.optBoolean("anywhere",false), adaptive=p.optBoolean("adaptive",true),
            hintAfter=p.optInt("hintAfter",d.hintAfter), minimalAfter=p.optInt("minimalAfter",d.minimalAfter),
        ).safe()
        val bindings=root.optJSONArray("bindings")
        val h=root.optJSONObject("home")
        val home=if(h==null) HomePrefs() else HomePrefs(h.optInt("cleanDispatches",0).coerceIn(0,9999),
            no.heimflyt.launcher.ui.home.HomeHints.from(h.optString("hints")),
            if(h.has("weekNumber")) h.optBoolean("weekNumber") else null, h.optBoolean("appsHintSeen",false),
            h.optBoolean("searchAtBottom",true), HomeStatusMode.from(h.optString("statusMode")), h.optBoolean("searchWord",true), h.optBoolean("appsOnThumb",true),
            h.optBoolean("h7",false), h.optBoolean("h7Guide",true), h.optBoolean("h7Launch",false),
            Fan().let { d -> Fan(h.optDouble("h7Size",d.size.toDouble()).toFloat(), h.optDouble("h7Beyond",d.beyond.toDouble()).toFloat(),
                h.optDouble("h7Spread",d.spread.toDouble()).toFloat()).safe() })
        val tagGroups = root.optJSONObject("tagGroups")?.let { groups ->
            groups.keys().asSequence().mapNotNull { tag ->
                if (normalizeTagName(tag) != tag) null else groups.optJSONObject(tag)?.let {
                    tag to RadialGroups.decode(it).copy(fallbackTag = tag)
                }
            }.toMap()
        }.orEmpty()
        val autoTags = root.optJSONArray("autoTags")?.let { a ->
            (0 until a.length()).mapNotNull { normalizeTagName(a.optString(it)) }.toSet()
        }.orEmpty()
        // Older builds stored only an opt-in Most used set. Preserve that choice and all fixed groups.
        val tagModes = root.optJSONObject("tagModes")?.let { modes ->
            modes.keys().asSequence().mapNotNull { tag ->
                if (normalizeTagName(tag) != tag) null else TagRadialMode.entries.find { it.id == modes.optString(tag) }?.let { tag to it }
            }.toMap()
        } ?: (tagGroups.keys.associateWith { TagRadialMode.FIXED } + autoTags.associateWith { TagRadialMode.MOST_USED })
        val autoTagGroups = root.optJSONObject("autoTagGroups")?.let { groups ->
            groups.keys().asSequence().mapNotNull { tag ->
                if (normalizeTagName(tag) != tag) null else groups.optJSONObject(tag)?.let {
                    tag to RadialGroups.decode(it).copy(fallbackTag = tag)
                }
            }.toMap()
        }.orEmpty()
        return LocalSettings(tuning,home=home,tagGroups=tagGroups,tagModes=tagModes,autoTagGroups=autoTagGroups,bindings=List(8) { i ->
            val b=bindings?.optJSONObject(i)
            when(b?.optString("kind")) {
                "apps" -> HomeAction.Apps
                "search" -> HomeAction.Search
                "tag" -> normalizeTagName(b.optString("name"))?.let { HomeAction.Tag(it) } ?: HomeAction.Probe(i+1)
                "group" -> RadialGroups.decode(b)
                "semantic" -> SemanticDestination.entries.find { it.id==b.optString("destination") }
                    ?.let { HomeAction.Semantic(it) } ?: HomeAction.Probe(i+1)
                "app" -> if (android.content.ComponentName.unflattenFromString(b.optString("component"))!=null && b.optLong("serial",-1)>=0)
                    HomeAction.App(b.getString("component"),b.getLong("serial"),b.optString("label","App").take(100)) else HomeAction.Probe(i+1)
                else -> HomeAction.Probe(i+1)
            }
        })
    }
}
