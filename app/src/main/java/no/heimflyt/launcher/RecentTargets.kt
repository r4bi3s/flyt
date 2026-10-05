package no.heimflyt.launcher

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject

/** A tiny Search start list. Only successful, explicit launches in Apps or Search are recorded. */
data class RecentTarget(
    val kind: String,
    val key: String,
    val label: String,
    val extra: String = "",
    val serial: Long = 0,
) {
    val identity: String get() = "$kind|$key|$extra|$serial"
}

class RecentTargets(context: Context) {
    private val prefs = context.getSharedPreferences("recent_targets", Context.MODE_PRIVATE)
    var entries by mutableStateOf(read())
        private set
    /** Owner setting: how many recent destinations empty Search shows (0 = off, 3 or 5). */
    var count by mutableStateOf(prefs.getInt("count", 3).let { if (it in ALLOWED) it else 3 })
        private set

    fun changeCount(value: Int) {
        val v = if (value in ALLOWED) value else 3
        count = v; prefs.edit().putInt("count", v).apply()
        if (v == 0) clear()
    }

    fun record(target: RecentTarget) {
        if (count == 0) return
        entries = (listOf(target) + entries.filterNot { it.identity == target.identity }).take(CAP)
        prefs.edit().putString("items", JSONArray().apply {
            entries.forEach { item -> put(JSONObject()
                .put("kind", item.kind).put("key", item.key).put("label", item.label)
                .put("extra", item.extra).put("serial", item.serial)) }
        }.toString()).apply()
    }

    fun clear() {
        entries = emptyList()
        prefs.edit().remove("items").apply()
    }

    private fun read(): List<RecentTarget> = try {
        val array = JSONArray(prefs.getString("items", "[]"))
        (0 until minOf(CAP, array.length())).mapNotNull { index ->
            val item = array.getJSONObject(index)
            val kind = item.getString("kind")
            val key = item.getString("key")
            if (kind !in setOf("app", "contact", "semantic", "shortcut") || key.isBlank()) null
            else RecentTarget(kind, key, item.optString("label").take(100),
                item.optString("extra"), item.optLong("serial"))
        }
    } catch (_: Exception) { emptyList() }

    companion object {
        /** Stored cap; the display takes the first [count] available entries. */
        const val CAP = 5
        val ALLOWED = setOf(0, 3, 5)
    }
}
