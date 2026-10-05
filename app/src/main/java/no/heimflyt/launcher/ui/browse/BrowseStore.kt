package no.heimflyt.launcher.ui.browse

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** Browse layout state: the owner's ordered slots (0–6, tag references or holes) and the experiment's renderer choice. */
data class BrowseConfig(val slots: List<String?> = emptyList(), val renderer: BrowseRenderer = BrowseRenderer.SPATIAL,
    /** Small tags' stable 2×2 cells (H6.1): tag → destination keys or holes, see [BrowseModel.stableCells]. */
    val tagCells: Map<String, List<String?>> = emptyMap())

/**
 * Local Browse configuration. Tags stay in TagStore; Browse stores only which tags sit in which slot. Switching the
 * experiment renderer never touches the slots. Read on first use of Apps, never on Home.
 */
class BrowseStore(context: Context) {
    private val prefs = context.getSharedPreferences("browse", Context.MODE_PRIVATE)
    private val _config = MutableStateFlow(read())
    val config: StateFlow<BrowseConfig> = _config.asStateFlow()

    private fun read(): BrowseConfig = try {
        val o = JSONObject(prefs.getString("config", null) ?: "{}")
        val a = o.optJSONArray("slots") ?: JSONArray()
        val cells = o.optJSONObject("cells")
        BrowseConfig(List(minOf(a.length(), MAX_SLOTS)) { if (a.isNull(it)) null else a.getString(it) },
            runCatching { BrowseRenderer.valueOf(o.optString("renderer", "SPATIAL")) }.getOrDefault(BrowseRenderer.SPATIAL),
            cells?.keys()?.asSequence()?.associateWith { tag -> cells.getJSONArray(tag).let { c -> List(c.length()) { if (c.isNull(it)) null else c.getString(it) } } }
                ?: emptyMap())
    } catch (_: Exception) { BrowseConfig() }

    private fun write(c: BrowseConfig) {
        _config.value = c
        val o = JSONObject().put("slots", JSONArray(c.slots.map { it ?: JSONObject.NULL })).put("renderer", c.renderer.name)
        // Written only once a small tag has been shown, so a config without cells keeps its exact H6.0 form.
        if (c.tagCells.isNotEmpty()) o.put("cells", JSONObject().also { j -> c.tagCells.forEach { (tag, keys) -> j.put(tag, JSONArray(keys.map { it ?: JSONObject.NULL })) } })
        prefs.edit().putString("config", o.toString()).apply()
    }

    fun setRenderer(r: BrowseRenderer) = write(_config.value.copy(renderer = r))
    fun updateSlots(transform: (List<String?>) -> List<String?>) = write(_config.value.copy(slots = transform(_config.value.slots)))

    /** Keeps slot references valid when tags are renamed or deleted elsewhere (Tags surface, App sheet). */
    fun setCells(tag: String, cells: List<String?>) {
        val c = _config.value
        if ((c.tagCells[tag] ?: emptyList()) == cells) return
        write(c.copy(tagCells = if (cells.isEmpty()) c.tagCells - tag else c.tagCells + (tag to cells)))
    }
    fun tagRenamed(old: String, new: String) = _config.value.let { c ->
        write(c.copy(slots = BrowseModel.renamed(c.slots, old, new),
            tagCells = c.tagCells[old]?.let { cells -> c.tagCells - old + (new to cells) } ?: c.tagCells))
    }
    fun tagDeleted(name: String) = _config.value.let { c -> write(c.copy(slots = BrowseModel.deleted(c.slots, name), tagCells = c.tagCells - name)) }
}
