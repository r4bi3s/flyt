package no.heimflyt.launcher.gesture

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Disposable H7.1 rehearsal. All coordinates are dp; children fan out beyond the drawn radial, like a fountain. */
enum class NestState { LEVEL1, PARENT, CHILD }

/**
 * Owner-tunable fan. [size] is the drawn icon diameter, [beyond] how far past the drawn menu radius the fan's apex sits on
 * the parent axis, and [spread] the degrees between neighbouring icons as seen from the nozzle.
 */
data class Fan(val size: Float = 32f, val beyond: Float = 85f, val spread: Float = 30f) {
    fun safe() = Fan().let { d -> Fan(size.within(SIZE, d.size), beyond.within(BEYOND, d.beyond), spread.within(SPREAD, d.spread)) }
    companion object {
        val SIZE = 20f..56f
        val BEYOND = 30f..160f
        val SPREAD = 12f..56f
        private fun Float.within(range: ClosedFloatingPointRange<Float>, fallback: Float) =
            if (isFinite()) coerceIn(range.start, range.endInclusive) else fallback
    }
}

/** Nested directions: sector → bit mask of the fan slots that hold a child (bit n = slot n). */
data class NestedConfig(val parents: Map<Int, Int>, val fan: Fan = Fan()) {
    companion object {
        const val ALL = 0b1111
        fun single(parent: Int, fan: Fan = Fan()) = NestedConfig(mapOf(parent to ALL), fan)
    }
}

object Nest {
    /** Lock ring. The point where the fixed parent axis crosses it is the nozzle the fan opens from. */
    const val R1 = 60f
    /** Half-width of the parent lane along the incoming stroke: going straight on stays the parent. */
    const val LANE = 14f
    const val MAX_HIT = 36f
    const val CLAUDE = 0
    const val GROK = 1
    const val GEMINI = 2
    const val CHATGPT = 3
    val NAMES = arrayOf("Claude", "Grok", "Gemini", "ChatGPT")
    /** Screen-clockwise steps of [Fan.spread] from the fixed parent sector axis; mirror these for left-handed geometry. */
    private val STEPS = floatArrayOf(-1.5f, -0.5f, 0.5f, 1.5f)

    /** Nozzle-to-icon distance: every icon is equally far from the lock point. */
    fun reach(fan: Fan, menuRadius: Float) = menuRadius + fan.beyond - R1
    /** Distance between neighbouring icon centres. */
    fun spacing(fan: Fan, menuRadius: Float) =
        2f * reach(fan, menuRadius) * sin(Math.toRadians(fan.spread / 2.0)).toFloat()
    /** A comfortable touch target that does not shrink with the drawn icon: half the spacing, capped. Nearest centre wins. */
    fun hitRadius(fan: Fan, menuRadius: Float) = max(fan.size / 2f + 6f, min(spacing(fan, menuRadius) / 2f, MAX_HIT))
    /** The lane never reaches an inner icon's centre, however tight the fan. */
    fun lane(fan: Fan, menuRadius: Float) = min(LANE, spacing(fan, menuRadius) / 4f)
    /** Radius from the origin inside which no target can be hit. */
    fun gate(fan: Fan, menuRadius: Float): Float {
        val (x, y) = target(CLAUDE, 0f, 0f, 0f, false, fan, menuRadius)
        return hypot(x, y) - hitRadius(fan, menuRadius)
    }

    /**
     * Visual hand-over from level 1 to the fan: 0 at the lock ring, 1 where the first target begins, following the thumb's
     * distance from the origin both ways. Presentation only.
     */
    fun reveal(distance: Float, fan: Fan, menuRadius: Float) =
        ((distance - R1) / (gate(fan, menuRadius) - R1).coerceAtLeast(1f)).coerceIn(0f, 1f)

    fun target(slot: Int, originX: Float, originY: Float, axis: Float, leftHanded: Boolean, fan: Fan, menuRadius: Float): Pair<Float, Float> {
        val nozzle = Math.toRadians(axis.toDouble())
        val ray = Math.toRadians((axis + (if (leftHanded) -STEPS[slot] else STEPS[slot]) * fan.spread).toDouble())
        val reach = reach(fan, menuRadius)
        return originX + R1 * cos(nozzle).toFloat() + reach * cos(ray).toFloat() to
            originY + R1 * sin(nozzle).toFloat() + reach * sin(ray).toFloat()
    }

    fun signed(a: Float) = RadialGeometry.normalize(a + 180f) - 180f

    /** A finite circle hit, never a whole angular wedge. The parent lane wins even where it crosses a circle. Only slots in [mask] exist. */
    fun hit(x: Float, y: Float, ox: Float, oy: Float, axis: Float, incoming: Float, leftHanded: Boolean, fan: Fan, menuRadius: Float,
            mask: Int = NestedConfig.ALL): Int {
        val dx = x - ox; val dy = y - oy
        if (!dx.isFinite() || !dy.isFinite()) return -1
        val i = Math.toRadians(incoming.toDouble()); val ix = cos(i).toFloat(); val iy = sin(i).toFloat()
        if (dx * ix + dy * iy > 0f && abs(dx * iy - dy * ix) <= lane(fan, menuRadius)) return -1
        val radius = hitRadius(fan, menuRadius)
        var found = -1; var best = radius * radius
        for (slot in 0..3) {
            if (mask and (1 shl slot) == 0) continue
            val (tx, ty) = target(slot, ox, oy, axis, leftHanded, fan, menuRadius)
            val sq = (x - tx) * (x - tx) + (y - ty) * (y - ty)
            if (sq <= best) { best = sq; found = slot }
        }
        return found
    }

    /** Segment/ring crossing fraction; caller has an inside start and outside end. */
    fun crossing(ux: Float, uy: Float, wx: Float, wy: Float, r: Float): Float {
        val a = wx * wx + wy * wy
        if (a <= 0f) return 1f
        val b = ux * wx + uy * wy
        val c = ux * ux + uy * uy - r * r
        return ((-b + sqrt((b * b - a * c).coerceAtLeast(0f))) / a).coerceIn(0f, 1f)
    }
}

/** Primitive per-gesture state. The engine remains the only authority for release and visible selection. */
class NestedTargets {
    var state = NestState.LEVEL1; private set
    var child = -1; private set
    var incoming = 0f; private set
    var axis = 0f; private set
    var fan = Fan(); private set
    var menuRadius = 100f; private set
    var leftHanded = false; private set
    var mask = NestedConfig.ALL; private set
    var changes = 0; private set
    var inwardSwitches = 0; private set
    var clears = 0; private set
    var childEver = false; private set
    var lockOnHistorical = false; private set
    var childOnHistorical = false; private set
    var disagreement = 0
    var exitAngle = Float.NaN
    private var gate = 0f
    private var previousRadius = 0f
    private var hasSample = false
    private var correctingOutsideGate = false

    fun reset() {
        state = NestState.LEVEL1; child = -1; incoming = 0f; axis = 0f; fan = Fan(); menuRadius = 100f; leftHanded = false; mask = NestedConfig.ALL
        changes = 0; inwardSwitches = 0; clears = 0; childEver = false; lockOnHistorical = false
        childOnHistorical = false; disagreement = 0; exitAngle = Float.NaN; gate = 0f; previousRadius = 0f; hasSample = false
        correctingOutsideGate = false
    }

    fun lock(px: Float, py: Float, ox: Float, oy: Float, sectorAxis: Float, mirrored: Boolean, geometry: Fan, radius: Float, historical: Boolean,
             slots: Int = NestedConfig.ALL) {
        state = NestState.PARENT; child = -1; incoming = RadialGeometry.angle(px - ox, py - oy)
        axis = sectorAxis; leftHanded = mirrored; fan = geometry; menuRadius = radius; lockOnHistorical = historical; mask = slots
        gate = Nest.gate(geometry, radius)
        previousRadius = Nest.R1; hasSample = true
    }

    fun sample(x: Float, y: Float, ox: Float, oy: Float, historical: Boolean) {
        if (state == NestState.LEVEL1) return
        val radius = hypot(x - ox, y - oy)
        if (radius < gate) correctingOutsideGate = false
        else if (childEver && hasSample && radius < previousRadius - 2f) correctingOutsideGate = true
        val next = Nest.hit(x, y, ox, oy, axis, incoming, leftHanded, fan, menuRadius, mask)
        if (next != child) {
            if (child >= 0 && next < 0) clears++
            if (next >= 0) {
                if (childEver && correctingOutsideGate) inwardSwitches++
                changes++; childEver = true; childOnHistorical = historical
            }
            child = next
        }
        state = if (child >= 0) NestState.CHILD else NestState.PARENT
        previousRadius = radius; hasSample = true
    }
}
