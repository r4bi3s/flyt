package no.heimflyt.launcher

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import no.heimflyt.launcher.gesture.CancelReason
import no.heimflyt.launcher.gesture.Fan
import no.heimflyt.launcher.gesture.GestureFrame
import no.heimflyt.launcher.gesture.GestureResult
import no.heimflyt.launcher.gesture.Nest
import no.heimflyt.launcher.gesture.NestState
import no.heimflyt.launcher.gesture.NestedConfig
import no.heimflyt.launcher.gesture.NestedTargets
import no.heimflyt.launcher.gesture.RadialGeometry
import no.heimflyt.launcher.gesture.TuningParams
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * H7 instrumentation and prepared explicit groups. Tag import/name resolution is configuration-only.
 * Runtime children are exact persisted destinations, never a live tag query.
 */
object H7 {
    const val PARENT_TAG = "agenter"
    const val LOG = "HeimflytH7"
    private const val KEEP = 200
    private val CYCLE = intArrayOf(Nest.CLAUDE, Nest.CHATGPT, Nest.GROK, Nest.GEMINI,
        Nest.CHATGPT, Nest.CLAUDE, Nest.GEMINI, Nest.GROK)
    var prompts by mutableStateOf(false)
    var recordBaseline by mutableStateOf(false)
    var last by mutableStateOf<String?>(null); private set
    private var step by mutableIntStateOf(0)
    var revision by mutableIntStateOf(0); private set
    private val records = ArrayList<Record>()
    internal fun requested(children: Children?): Int {
        if (!prompts || children == null) return -1
        val cycle = CYCLE.filter { it in children.actions }
        return if (cycle.isEmpty()) -1 else cycle[step % cycle.size]
    }

    class Record(val asked: Int, val outcome: String, val child: Int, val short: String, val line: String, val askedName: String)

    /** One nested direction: its tag and, by fan slot, the app a release opens and its icon. A shown icon is that app. */
    class Children(val group: HomeAction.Group, val actions: Map<Int, HomeAction>, val icons: Map<Int, android.graphics.Bitmap>) {
        val tag: String get() = group.fallbackTag ?: group.name
        val mask: Int get() = actions.keys.fold(0) { m, slot -> m or (1 shl slot) }
        fun name(slot: Int) = actions[slot]?.label() ?: "slot ${slot + 1}"
    }

    /** Explicit groups and prepared tag children nest. No membership lookup while drawing or reading gestures. */
    fun groups(settings: LocalSettings): Map<Int, HomeAction.Group> {
        if (!settings.home.h7) return emptyMap()
        val tags = settings.activeTagGroups()
        return settings.bindings.take(settings.tuning.safe().sectorCount).withIndex()
            .mapNotNull { (i, a) -> RadialGroups.forBinding(a, tags)?.let { i to it } }.toMap()
    }

    /** Nested directions with at least one resolved child, or null (then the radial is exactly level 1). */
    fun config(settings: LocalSettings, children: Map<Int, Children>): NestedConfig? {
        val parents = groups(settings).mapNotNull { (sector, group) ->
            children[sector]?.takeIf { it.group == group && it.mask != 0 }?.let { sector to it.mask }
        }.toMap()
        return if (parents.isEmpty()) null else NestedConfig(parents, settings.home.h7Fan)
    }

    /** Child release opens the app: the owner's switch, never while prompts are scoring rehearsal gestures. */
    fun launches(settings: LocalSettings) = settings.home.h7 && settings.home.h7Launch && !prompts

    /**
     * A small tag's stable Browse cells → fan slots. The first two cells take the inner pair and the next two the outer
     * pair, so a tag of two is symmetric and adding an app never moves one already placed. Holes stay empty.
     */
    private val CELL_SLOTS = intArrayOf(Nest.GROK, Nest.GEMINI, Nest.CLAUDE, Nest.CHATGPT)
    fun <T : Any> fanSlots(cells: List<T?>): Map<Int, T> =
        cells.take(CELL_SLOTS.size).withIndex().mapNotNull { (i, v) -> v?.let { CELL_SLOTS[i] to it } }.toMap()

    /**
     * Slot → the single installed app whose label is that name (ignoring case). A missing or ambiguous name (for example
     * the same app in a work profile) resolves to nothing, so its icon is not shown and it can never open the wrong app.
     */
    fun <T> resolve(apps: List<T>, label: (T) -> String): Map<Int, T> {
        val found = HashMap<Int, T>()
        Nest.NAMES.forEachIndexed { slot, name ->
            apps.filter { label(it).equals(name, ignoreCase = true) }.singleOrNull()?.let { found[slot] = it }
        }
        return found
    }

    fun readout(settings: LocalSettings, children: Map<Int, Children> = emptyMap()): String? {
        if (!settings.tuning.debug) return null
        if (!settings.home.h7 && !recordBaseline) return null
        val prompt = children.filterValues { it.actions.isNotEmpty() }.minByOrNull { it.key }?.value
        val next = requested(prompt).takeIf { it >= 0 }?.let { "Next: ${prompt!!.group.name} → ${prompt.name(it)}" }
        val heading = if (settings.home.h7) "H7 · fan ${label(settings.home.h7Fan)} · ${if (launches(settings)) "launch" else "rehearsal"}" else "H7 baseline"
        return listOfNotNull(heading, last, next).joinToString("\n")
    }

    fun finished(result: GestureResult, f: GestureFrame, targets: NestedTargets?, settings: LocalSettings, children: Map<Int, Children>) {
        val on = config(settings, children) != null
        if (!on && !recordBaseline) return
        if (result == GestureResult.Tap || (result is GestureResult.Cancelled && result.reason == CancelReason.STRAY_TAP)) return
        // Score the first prepared group only. Empty/unavailable cells are never requested.
        val prompt = children.filterValues { it.actions.isNotEmpty() }.minByOrNull { it.key }
        val scored = on && prompts && prompt != null && (f.nest == NestState.LEVEL1 || f.sector == prompt.key)
        val parent = if (scored) prompt?.value else children[f.sector]
        val record = describe(result, f, targets.takeIf { on }, settings.tuning, if (scored) requested(parent) else -1, launches(settings),
            parent?.let { it.group.name to it::name })
        records += record
        if (records.size > KEEP) records.removeAt(0)
        last = record.short
        if (scored) step++
        revision++
        android.util.Log.i(LOG, record.line)
    }

    /** [parent] is the locked tag and its slot names; null means #agenter's fixed names. */
    fun describe(result: GestureResult, f: GestureFrame, targets: NestedTargets?, tuning: TuningParams, asked: Int, launch: Boolean = false,
                 parent: Pair<String, (Int) -> String>? = null): Record {
        val p = tuning.safe()
        val radius = hypot(f.x - f.originX, f.y - f.originY)
        val child = (result as? GestureResult.Selected)?.child ?: -1
        val outcome = when {
            child >= 0 -> "child"
            result is GestureResult.Selected && f.nest != NestState.LEVEL1 -> "parent"
            result is GestureResult.Selected && targets?.disagreement == 1 -> "arming-disagreement"
            result is GestureResult.Selected -> "level1"
            result is GestureResult.Cancelled -> "cancel-${result.reason.name.lowercase()}"
            else -> "other"
        }
        val sector = (result as? GestureResult.Selected)?.sector ?: f.lastSector
        val angle = RadialGeometry.angle(f.x - f.originX, f.y - f.originY)
        val axis = sector?.let { RadialGeometry.sectorAngle(it, p) }
        val axisOffset = axis?.let { Nest.signed(angle - it).roundToInt() }
        val incomingOffset = targets?.takeIf { f.nest != NestState.LEVEL1 }?.let { Nest.signed(angle - it.incoming).roundToInt() }
        val requestedName = if (asked in 0..3) parent?.second?.invoke(asked) ?: Nest.NAMES[asked] else "-"
        val selectedName = if (child !in 0..3) "-" else parent?.second?.invoke(child) ?: Nest.NAMES[child]
        val score = if (asked < 0) "" else if (asked == child) " ✓" else " ✗"
        val corrected = targets?.let { " changes=${it.changes} clears=${it.clears} inwardSwitches=${it.inwardSwitches}" } ?: ""
        val fan = targets?.takeIf { f.nest != NestState.LEVEL1 }?.let { label(it.fan) } ?: "-"
        val line = "tag=${parent?.first ?: "-"} asked=$requestedName got=$selectedName$score $outcome sector=${sector?.plus(1) ?: "-"} " +
            "fan=$fan origin=${f.originX.roundToInt()},${f.originY.roundToInt()} " +
            "release=${radius.roundToInt()}dp angle=${angle.roundToInt()}° axisOffset=${axisOffset ?: "-"}° " +
            "incomingOffset=${incomingOffset ?: "-"}° exit=${targets?.exitAngle?.roundToInt() ?: "-"}° " +
            "hist=L${if (targets?.lockOnHistorical == true) 1 else 0}C${if (targets?.childOnHistorical == true) 1 else 0} " +
            "disagree=${targets?.disagreement ?: 0}$corrected launch=${if (launch && child >= 0) 1 else 0} ${f.elapsed}ms"
        val shown = if (child >= 0) selectedName + score else outcome + score
        val short = "$shown · ${radius.roundToInt()} dp · ${f.elapsed} ms"
        return Record(asked, outcome, child, short, line, requestedName)
    }

    /** Icon size / distance beyond the ring / spread. */
    fun label(fan: Fan) = "${fan.size.roundToInt()}/${fan.beyond.roundToInt()}/${fan.spread.roundToInt()}°"

    fun summary(): String {
        if (records.isEmpty()) return "No gestures recorded in this session."
        val out = StringBuilder()
        records.filter { it.asked >= 0 }.groupBy { it.askedName }.forEach { (name, asked) ->
            out.append("$name: ${asked.count { it.child == it.asked }}/${asked.size} correct; " +
                "${asked.count { it.child >= 0 && it.child != it.asked }} wrong icon; ${asked.count { it.child < 0 }} no icon\n")
        }
        records.groupingBy { it.outcome }.eachCount().toSortedMap().forEach { (k, v) -> out.append("$k $v · ") }
        out.append("\n\n").append(records.takeLast(8).reversed().joinToString("\n\n") { it.line })
        return out.toString()
    }

    fun clear() { records.clear(); last = null; step = 0; revision++ }
}
