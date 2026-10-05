package no.heimflyt.launcher

import android.content.ComponentName
import org.json.JSONObject
import org.json.JSONArray

/** Configuration helpers only. Runtime consumes exact, persisted component/profile identities. */
object RadialGroups {
    fun encode(group: HomeAction.Group) = JSONObject().put("name", group.name)
        .put("fallbackTag", group.fallbackTag ?: JSONObject.NULL)
        .put("children", JSONArray().apply { group.children.forEach { child -> put(child?.let {
            when (it) {
                is HomeAction.App -> JSONObject().put("kind", "app").put("component", it.component).put("serial", it.userSerial).put("label", it.label)
                is HomeAction.Semantic -> JSONObject().put("kind", "semantic").put("destination", it.destination.id)
                else -> JSONObject.NULL
            }
        } ?: JSONObject.NULL) } })

    fun forBinding(action: HomeAction, groups: Map<String, HomeAction.Group>): HomeAction.Group? = when (action) {
        is HomeAction.Group -> action
        is HomeAction.Tag -> groups[action.name]
        else -> null
    }

    fun decode(o: JSONObject): HomeAction.Group {
        val array = o.optJSONArray("children")
        val seen = HashSet<String>()
        val children: List<HomeAction?> = List(4) { slot ->
            array?.optJSONObject(slot)?.let { child ->
                val action: HomeAction? = if (child.optString("kind") == "semantic")
                    SemanticDestination.entries.firstOrNull { it.id == child.optString("destination") }?.let { HomeAction.Semantic(it) }
                else {
                    val component = child.optString("component")
                    val serial = child.optLong("serial", -1)
                    if (ComponentName.unflattenFromString(component) == null || serial < 0) null
                    else HomeAction.App(component, serial, child.optString("label", "App").take(100))
                }
                action?.takeIf { seen.add(it.key()) }
            }
        }
        return HomeAction.Group(o.optString("name").trim().take(60).ifEmpty { "Group" }, children,
            if (o.isNull("fallbackTag")) null else normalizeTagName(o.optString("fallbackTag")))
    }

    /** Editing one cell cannot move another. Duplicates are rejected, including same component/profile with a new label. */
    fun replace(group: HomeAction.Group, slot: Int, child: HomeAction?): HomeAction.Group? {
        if (child != null && child !is HomeAction.App && child !is HomeAction.Semantic) return null
        if (slot !in 0..3 || (child != null && group.children.withIndex().any { (i, a) -> i != slot && a?.key() == child.key() })) return null
        return group.copy(children = group.children.toMutableList().also { it[slot] = child })
    }

    fun valid(group: HomeAction.Group) = group.name.isNotBlank() && group.children.count { it != null } in 2..4 &&
        group.children.filterNotNull().map { it.key() }.distinct().size == group.children.count { it != null }

    fun renameTag(action: HomeAction, old: String, new: String): HomeAction = when (action) {
        is HomeAction.Tag -> if (action.name == old) HomeAction.Tag(new) else action
        is HomeAction.Group -> if (action.fallbackTag == old) action.copy(fallbackTag = new) else action
        else -> action
    }

    fun deleteTag(action: HomeAction, name: String): HomeAction = when (action) {
        is HomeAction.Tag -> if (action.name == name) HomeAction.Search else action
        is HomeAction.Group -> if (action.fallbackTag == name) action.copy(fallbackTag = null) else action
        else -> action
    }
}
