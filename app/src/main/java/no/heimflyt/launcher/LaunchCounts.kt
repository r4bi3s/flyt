package no.heimflyt.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/** Successful Heimflyt app and action launches only. No Android usage access or reads on the gesture path. */
class LaunchCounts(context: Context) {
    private val prefs = context.getSharedPreferences("launch_counts", Context.MODE_PRIVATE)
    var counts by mutableStateOf(read())
        private set

    fun record(action: HomeAction) {
        val key = action.tagMemberKey() ?: return
        val next = (counts[key] ?: 0).let { if (it < 1_000_000) it + 1 else it }
        counts = (counts + (key to next)).let { all ->
            if (all.size <= MAX_APPS) all else all.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
                .take(MAX_APPS).associate { it.key to it.value }
        }
        prefs.edit().putString("counts", JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } }.toString()).apply()
    }

    fun clear() { counts = emptyMap(); prefs.edit().remove("counts").apply() }

    private fun read(): Map<String, Int> = try {
        val data = JSONObject(prefs.getString("counts", "{}") ?: "{}")
        data.keys().asSequence().mapNotNull { key ->
            val value = data.optInt(key, 0)
            if (key.length > 250 || value <= 0) null else key to value.coerceAtMost(1_000_000)
        }.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first })
            .take(MAX_APPS).toMap()
    } catch (_: Exception) { emptyMap() }

    companion object { const val MAX_APPS = 512 }
}

/** Highest local launch count first; ties keep the previous slots, then the owner's saved fixed order. */
object MostUsed {
    /** A tag's prepared children; null means keep its fixed choices or wait for a complete catalogue. */
    fun prepare(tag: String, label: String, members: List<HomeAction>, settings: LocalSettings,
                counts: Map<String, Int>, authoritative: Boolean): HomeAction.Group? {
        val mode = settings.tagMode(tag)
        if (!authoritative || mode == TagRadialMode.FIXED) return null
        val byKey = members.mapNotNull { member -> member.tagMemberKey()?.let { it to member } }.toMap()
        val old = settings.autoTagGroups[tag]?.children.orEmpty().mapNotNull { it?.tagMemberKey() }
        val fixed = settings.tagGroups[tag]?.children.orEmpty().mapNotNull { it?.tagMemberKey() }
        val left = settings.tuning.leftHanded
        val ranked = if (mode == TagRadialMode.SMALL && byKey.size > 4) emptyList() else
            rank(byKey.map { (key, member) -> key to member.label() }, counts,
                if (left) old.reversed() else old, if (left) fixed.reversed() else fixed).mapNotNull(byKey::get)
        val slots: List<HomeAction?> = if (left) List(4 - ranked.size) { null } + ranked.reversed()
            else ranked + List(4 - ranked.size) { null }
        return HomeAction.Group(label, slots, tag)
    }

    fun rank(members: List<Pair<String, String>>, counts: Map<String, Int>, previous: List<String>, fixed: List<String>): List<String> {
        val old = previous.withIndex().associate { it.value to it.index }
        val saved = fixed.withIndex().associate { it.value to it.index }
        return members.distinctBy { it.first }.sortedWith(compareByDescending<Pair<String, String>> { counts[it.first] ?: 0 }
            .thenBy { old[it.first] ?: Int.MAX_VALUE }.thenBy { saved[it.first] ?: Int.MAX_VALUE }
            .thenBy { it.second.lowercase() }.thenBy { it.first }).take(4).map { it.first }
    }
}

/** Called on configuration, catalogue refresh or after a successful launch, never while a gesture is being read. */
fun HeimflytApplication.refreshMostUsed(tagOnly: String? = null) {
    val current = settings.settings.value
    val catalogue = apps.catalog.value
    if (!catalogue.authoritative) return
    val assignments = tags.assignments
    val counts = launchCounts.counts
    tags.names.filter { tagOnly == null || it == tagOnly }.filter { current.tagMode(it) != TagRadialMode.FIXED }.forEach { tag ->
        val members: List<HomeAction> = catalogue.apps.filter { tag in assignments[it.key].orEmpty() }
            .map { HomeAction.App(it.info.componentName.flattenToString(), it.serial, it.label) } +
            SemanticDestination.entries.filter { tag in assignments["semantic:${it.id}"].orEmpty() }.map(HomeAction::Semantic)
        MostUsed.prepare(tag, tags.label(tag), members, current, counts, catalogue.authoritative)?.let {
            settings.setAutoTagGroup(tag, it)
        }
    }
}
