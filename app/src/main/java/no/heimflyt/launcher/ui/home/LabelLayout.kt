package no.heimflyt.launcher.ui.home

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/** Pure, deterministic radial label placement (SURFACES.md §2.1). All values are dp in touch-surface coordinates. */
data class LRect(val l: Float, val t: Float, val r: Float, val b: Float) {
    val w get() = r - l
    val h get() = b - t
    fun inside(o: LRect) = l >= o.l - EPS && t >= o.t - EPS && r <= o.r + EPS && b <= o.b + EPS
    fun intersects(o: LRect, gap: Float) = l < o.r + gap && o.l < r + gap && t < o.b + gap && o.t < b + gap
    fun hitsCircle(cx: Float, cy: Float, radius: Float): Boolean {
        val nx = cx.coerceIn(l, r); val ny = cy.coerceIn(t, b)
        return hypot(cx - nx, cy - ny) < radius
    }
    companion object { const val EPS = 0.01f }
}

/** Measured sizes for one label. [iconWidth] 0 means there is no icon-only fallback (for example a Hinted number pill). */
data class LabelSize(val namedWidth: Float, val iconWidth: Float, val height: Float)

enum class LabelKind { NAMED, ICON }
data class PlacedLabel(val kind: LabelKind, val rect: LRect)

object LabelLayout {
    const val GAP = 4f
    const val CANCEL_EXTRA = 4f

    /**
     * @param angles screen-space sector centre angles (0 = right, 90 = down), already mirrored for the hand.
     * @param shown which sectors currently draw a label.
     * @return one entry per sector; null = no label (not shown, or omitted because no valid position exists).
     */
    fun layout(originX: Float, originY: Float, bounds: LRect, angles: List<Float>, menuRadius: Float, deadZone: Float,
               sizes: List<LabelSize>, shown: List<Boolean>, selected: Int?): List<PlacedLabel?> {
        val n = angles.size
        val cancel = deadZone + CANCEL_EXTRA
        // Tuning allows a drawn radius smaller than the dead zone; anchor to the effective outer edge so labels never
        // start inside the cancel zone (the invariant below). Equals menuRadius for every ordinary configuration.
        val ring = max(menuRadius, cancel)
        val placed = arrayOfNulls<PlacedLabel>(n)
        val taken = mutableListOf<LRect>()
        fun valid(r: LRect) = r.inside(bounds) && taken.none { it.intersects(r, GAP) } && !r.hitsCircle(originX, originY, cancel)

        fun candidates(i: Int, width: Float, height: Float): List<LRect> {
            val a = Math.toRadians(angles[i].toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val centred = abs(c) <= 0.35f
            val base = ring + if (centred) 26f else 18f
            return listOf(0f, 24f, 48f).map { extra ->
                val x = originX + c * (base + extra); val y = originY + s * (base + extra)
                val l = when { centred -> x - width / 2; c > 0 -> x; else -> x - width }
                clamp(LRect(l, y - height / 2, l + width, y + height / 2), bounds)
            }
        }

        if (selected != null && selected in 0 until n) {
            val sz = sizes[selected]
            val width = min(sz.namedWidth, min(0.6f * bounds.w, 240f))
            val slots = candidates(selected, width, sz.height) + fallbackSlots(originX, originY, ring, width, sz.height, bounds)
            // The selected label is never omitted. The feasibility tests establish that a valid slot exists on supported screens.
            val rect = slots.firstOrNull(::valid) ?: slots.last()
            placed[selected] = PlacedLabel(LabelKind.NAMED, rect); taken += rect
        }
        val start = if (selected != null && selected in 0 until n) selected + 1 else 0
        for (k in 0 until n) {
            val i = (start + k) % n
            if (i == selected || !shown.getOrElse(i) { false }) continue
            val sz = sizes[i]
            val named = candidates(i, sz.namedWidth, sz.height).firstOrNull(::valid)
            val choice = when {
                named != null -> PlacedLabel(LabelKind.NAMED, named)
                sz.iconWidth > 0f -> candidates(i, sz.iconWidth, sz.height).firstOrNull(::valid)?.let { PlacedLabel(LabelKind.ICON, it) }
                else -> null
            }
            if (choice != null) { placed[i] = choice; taken += choice.rect }
        }
        return placed.toList()
    }

    private fun fallbackSlots(ox: Float, oy: Float, radius: Float, w: Float, h: Float, bounds: LRect): List<LRect> {
        val d = radius + 26f
        return listOf(0f to -d, 0f to d, -d to 0f, d to 0f).map { (dx, dy) ->
            clamp(LRect(ox + dx - w / 2, oy + dy - h / 2, ox + dx + w / 2, oy + dy + h / 2), bounds)
        }
    }

    fun clamp(r: LRect, bounds: LRect): LRect {
        val dx = when { r.l < bounds.l -> bounds.l - r.l; r.r > bounds.r -> bounds.r - r.r; else -> 0f }
        val dy = when { r.t < bounds.t -> bounds.t - r.t; r.b > bounds.b -> bounds.b - r.b; else -> 0f }
        return LRect(r.l + dx, r.t + dy, r.r + dx, r.b + dy)
    }
}

/** Home learning rule (SURFACES.md §1.3): pure and deterministic. */
enum class HomeHints(val id: String) { AUTO("auto"), SHOW("show"), HIDE("hide");
    companion object { fun from(id: String?) = entries.firstOrNull { it.id == id } ?: AUTO } }

fun homeLearning(cleanDispatches: Int, hints: HomeHints): Boolean = when (hints) {
    HomeHints.SHOW -> true
    HomeHints.HIDE -> false
    HomeHints.AUTO -> cleanDispatches < 10
}

/** Upgrade value for the Home hints counter from existing learning (H5_IMPLEMENTATION_SPEC.md §5.2). */
fun migratedCleanDispatches(streaks: List<Int>, anyLearned: Boolean): Int {
    var sum = 0L
    for (s in streaks) sum = min(9999L, sum + max(0, s))
    return if (anyLearned) max(10, sum.toInt()) else sum.toInt()
}
