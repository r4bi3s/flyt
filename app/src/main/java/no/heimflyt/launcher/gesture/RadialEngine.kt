package no.heimflyt.launcher.gesture

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** All distances are dp, times ms, angles clockwise screen degrees. No Android dependencies. */
data class TuningParams(
    val leftHanded: Boolean = false,
    val edgeDistance: Float = 96f,
    val bottomDistance: Float = 180f,
    val affordanceSize: Float = 64f,
    val deadZone: Float = 24f,
    val menuRadius: Float = 100f,
    val arcStart: Float = 135f,
    val arcSpan: Float = 180f,
    val sectorCount: Int = 6,
    val hysteresis: Float = 4f,
    val tapTimeout: Long = 220,
    val minPress: Long = 0,
    val hesitationDelay: Long = 350,
    val dwellTime: Long = 350,
    val dwellSpeed: Float = 35f,
    val initialGuidance: Int = 2,
    val hapticDown: Boolean = false,
    val hapticSelection: Boolean = true,
    val hapticCommit: Boolean = true,
    val debug: Boolean = false,
    /** Fixed: down must land on the affordance. Anywhere: a down on unused Home space becomes the origin. */
    val anywhere: Boolean = false,
    /** Progressive invisibility: per-slot guidance from local familiarity instead of [initialGuidance]. */
    val adaptive: Boolean = true,
    val hintAfter: Int = 5,
    val minimalAfter: Int = 15,
) {
    fun safe(): TuningParams = copy(
        edgeDistance = edgeDistance.bound(48f, 260f, 96f),
        bottomDistance = bottomDistance.bound(64f, 400f, 180f),
        affordanceSize = affordanceSize.bound(48f, 112f, 64f),
        deadZone = deadZone.bound(8f, 72f, 24f),
        menuRadius = menuRadius.bound(60f, 180f, 100f),
        arcStart = arcStart.bound(0f, 359f, 135f),
        arcSpan = arcSpan.bound(60f, 360f, 180f),
        sectorCount = sectorCount.coerceIn(5, 8),
        hysteresis = hysteresis.bound(0f, 12f, 4f),
        tapTimeout = tapTimeout.coerceIn(100, 600), minPress = minPress.coerceIn(0, 80),
        hesitationDelay = hesitationDelay.coerceIn(100, 1500),
        dwellTime = dwellTime.coerceIn(100, 1500),
        dwellSpeed = dwellSpeed.bound(5f, 200f, 35f), initialGuidance = initialGuidance.coerceIn(0, 2),
        hintAfter = hintAfter.coerceIn(1, 50), minimalAfter = minimalAfter.coerceIn(hintAfter.coerceIn(1, 50) + 1, 100),
    )
}

private fun Float.bound(min: Float, max: Float, fallback: Float) =
    if (isFinite()) coerceIn(min, max) else fallback

object RadialGeometry {
    /** Minimum travel beyond the dead zone that Anywhere guarantees in every direction. */
    const val ANYWHERE_EDGE_TRAVEL = 16f
    /** Anywhere ignores touch-downs closer than this to the touch surface edge, so no direction starts off-surface. */
    fun anywhereInset(p: TuningParams): Float = p.safe().deadZone + ANYWHERE_EDGE_TRAVEL
    fun normalize(angle: Float): Float = ((angle % 360f) + 360f) % 360f
    fun angle(dx: Float, dy: Float): Float = normalize(Math.toDegrees(atan2(dy, dx).toDouble()).toFloat())
    fun difference(a: Float, b: Float): Float = abs(normalize(a - b + 180f) - 180f)
    fun sectorAngle(index: Int, p: TuningParams): Float {
        val a = p.arcStart + (index + .5f) * p.arcSpan / p.sectorCount
        return normalize(if (p.leftHanded) 180f - a else a)
    }
    fun selection(dx: Float, dy: Float, p: TuningParams, previous: Int?): Int? {
        if (!dx.isFinite() || !dy.isFinite() || hypot(dx, dy) <= p.deadZone) return null
        val angle = angle(if (p.leftHanded) -dx else dx, dy)
        val relative = normalize(angle - p.arcStart)
        if (p.arcSpan < 360f && relative >= p.arcSpan) return null
        val step = p.arcSpan / p.sectorCount
        if (previous != null && previous in 0 until p.sectorCount) {
            val center = normalize(p.arcStart + (previous + .5f) * step)
            if (difference(angle, center) < step / 2f + p.hysteresis.coerceAtMost(step / 3f)) return previous
        }
        return (relative / step).toInt().coerceIn(0, p.sectorCount - 1)
    }
}

enum class CancelReason { CENTER, OUTSIDE_ARC, OUTSIDE_SURFACE, TOO_SHORT, SYSTEM, SECOND_POINTER, LIFECYCLE, HOME, BACK, INVALID_POINTER,
    /** Anywhere mode: a tap away from the affordance is ignored rather than opening the menu. */
    STRAY_TAP }
sealed interface GestureResult {
    /** [child] is a nested rehearsal target (H7.1 experiment), or -1 for the sector itself. */
    data class Selected(val sector: Int, val child: Int = -1) : GestureResult
    data object Tap : GestureResult
    data class Cancelled(val reason: CancelReason) : GestureResult
}

data class GestureFrame(
    val active: Boolean = false,
    val originX: Float = 0f, val originY: Float = 0f,
    val x: Float = 0f, val y: Float = 0f,
    val sector: Int? = null,
    /** Last sector highlighted during this gesture, kept after returning to center for familiarity attribution. */
    val lastSector: Int? = null,
    val guidance: Int = 2,
    val elapsed: Long = 0,
    val untilHelp: Long = 0,
    val hesitated: Boolean = false,
    // ---- H7.1 experiment: defaults unless begin() was given a nested parent ----
    val nest: NestState = NestState.LEVEL1,
    val child: Int = -1,
    val pivotX: Float = 0f, val pivotY: Float = 0f,
    val dirX: Float = 0f, val dirY: Float = 0f,
    /** A child was selected at some point: rehearsal input, never taught to level-1 familiarity. */
    val childEver: Boolean = false,
    /** Keeps nested cancellation semantics after pulling back to choose another level-one direction. */
    val nestedEver: Boolean = false,
    /** R1 was crossed with the parent shown through hysteresis only, so nested mode refused to arm; the pivot holds the crossing. */
    val armRefused: Boolean = false,
)

/** A canceled sequence cannot commit later. begin() is the only way back into tracking. */
class RadialEngine {
    var frame = GestureFrame(); private set
    var params = TuningParams(); private set
    private var downAt = 0L
    private var lastAt = 0L
    private var stillSince = 0L
    private var everLeftCenter = false
    /** H7 experiment: the nested directions (sector → child slots), and after a lock the locked one; the engine then reports it as the sector. */
    private var nestedParents: Map<Int, Int> = emptyMap()
    private var nestedParent = -1
    private var nestedFan = Fan()
    val targets = NestedTargets()

    fun begin(x: Float, y: Float, time: Long, tuning: TuningParams, helpDelayScale: Float = 1f, nested: NestedConfig? = null) {
        params = tuning.safe()
        val scale = if (helpDelayScale.isFinite()) helpDelayScale.coerceIn(1f, 4f) else 1f
        params = params.copy(hesitationDelay = (params.hesitationDelay * scale).toLong())
        downAt = time; lastAt = time; stillSince = time; everLeftCenter = false
        nestedParents = if (nested == null || params.deadZone + 8f >= Nest.R1) emptyMap()
            else nested.parents.filter { (sector, slots) -> sector in 0 until params.sectorCount && slots != 0 }
        nestedParent = -1
        nestedFan = nested?.fan?.safe() ?: Fan()
        targets.reset()
        frame = GestureFrame(true, x, y, x, y, guidance = params.initialGuidance, untilHelp = params.hesitationDelay)
    }

    /** Returns a result only when a locked stroke re-enters the dead zone. */
    fun move(x: Float, y: Float, time: Long, historical: Boolean = false): GestureResult? {
        if (!frame.active) return null
        if (!x.isFinite() || !y.isFinite()) { cancel(CancelReason.INVALID_POINTER); return null }
        val t = time.coerceAtLeast(lastAt)
        val distance = hypot(x - frame.x, y - frame.y)
        val speed = if (t > lastAt) distance * 1000f / (t - lastAt) else if (distance > 0f) Float.MAX_VALUE else 0f
        if (speed > params.dwellSpeed) stillSince = t
        lastAt = t
        val dx = x - frame.originX; val dy = y - frame.originY
        everLeftCenter = everLeftCenter || hypot(dx, dy) > params.deadZone
        if (nestedParents.isNotEmpty()) {
            // Pull back inside the lock ring (but not into the cancel zone) to choose another direction without lifting.
            // Outside this ring the locked fan still owns the gesture, so its outer children cannot become neighbours.
            val radius = hypot(dx, dy)
            if (frame.nestedEver && radius <= params.deadZone) {
                frame = frame.copy(active = false, x = x, y = y, sector = null, child = -1, elapsed = t - downAt)
                return GestureResult.Cancelled(CancelReason.CENTER)
            }
            if (frame.nest != NestState.LEVEL1 && radius > params.deadZone && radius < Nest.R1) {
                targets.reset()
                nestedParent = -1
                frame = frame.copy(nest = NestState.LEVEL1, child = -1, sector = null, armRefused = false)
            }
            if (frame.nest == NestState.LEVEL1) arm(x, y, historical)
            if (frame.nest != NestState.LEVEL1) return moveLocked(x, y, hypot(dx, dy), t, historical)
        }
        val sector = RadialGeometry.selection(dx, dy, params, frame.sector)
        frame = frame.copy(x = x, y = y, sector = sector, lastSector = sector ?: frame.lastSector)
        tick(t)
        return null
    }

    /**
     * Lock at the first outward R1 crossing, or on a later correction into a group outside R1.
     * Both the visible and raw sector must agree. Later entry projects onto R1 so the same fan and parent lane apply;
     * entering a group sideways cannot immediately hit a child on that incoming line.
     */
    private fun arm(x: Float, y: Float, historical: Boolean) {
        val ox = frame.originX; val oy = frame.originY
        val d1 = hypot(x - ox, y - oy)
        if (targets.exitAngle.isNaN() && d1 > params.deadZone) targets.exitAngle = RadialGeometry.angle(x - ox, y - oy)
        if (d1 < Nest.R1) return
        val crossing = hypot(frame.x - ox, frame.y - oy) < Nest.R1
        val s = if (crossing) Nest.crossing(frame.x - ox, frame.y - oy, x - frame.x, y - frame.y, Nest.R1) else 0f
        val cx = if (crossing) frame.x + s * (x - frame.x) else ox + (x - ox) * Nest.R1 / d1
        val cy = if (crossing) frame.y + s * (y - frame.y) else oy + (y - oy) * Nest.R1 / d1
        val shownSector = RadialGeometry.selection(cx - ox, cy - oy, params, frame.sector)
        val rawSector = RadialGeometry.selection(cx - ox, cy - oy, params, null)
        val shown = shownSector != null && shownSector in nestedParents
        val raw = rawSector != null && rawSector in nestedParents
        if (shown && raw && shownSector == rawSector) {
            nestedParent = shownSector!!
            targets.lock(cx, cy, ox, oy, RadialGeometry.sectorAngle(nestedParent, params), params.leftHanded, nestedFan, params.menuRadius, historical,
                nestedParents.getValue(nestedParent))
            frame = frame.copy(nest = NestState.PARENT, nestedEver = true, pivotX = cx, pivotY = cy,
                dirX = (cx - ox) / Nest.R1, dirY = (cy - oy) / Nest.R1, armRefused = false)
        } else if (shown) {
            targets.disagreement = 1
            frame = frame.copy(armRefused = true, pivotX = cx, pivotY = cy)
        } else if (raw) targets.disagreement = 2
    }

    /** H7.1: after the lock the nested state is the only authority; the level-1 angle is no longer consulted. */
    private fun moveLocked(x: Float, y: Float, fromOrigin: Float, t: Long, historical: Boolean): GestureResult? {
        if (fromOrigin <= params.deadZone) {
            frame = frame.copy(active = false, x = x, y = y, sector = null, child = -1, elapsed = t - downAt)
            return GestureResult.Cancelled(CancelReason.CENTER)
        }
        targets.sample(x, y, frame.originX, frame.originY, historical)
        frame = frame.copy(x = x, y = y, sector = nestedParent, lastSector = nestedParent,
            nest = targets.state, child = targets.child, childEver = frame.childEver || targets.childEver)
        tick(t)
        return null
    }

    /** Called only during a gesture; there is no idle timer. */
    fun tick(time: Long) {
        if (!frame.active) return
        val now = time.coerceAtLeast(lastAt).coerceAtLeast(downAt + frame.elapsed)
        val inside = hypot(frame.x - frame.originX, frame.y - frame.originY) <= params.deadZone
        val threshold = if (inside) params.hesitationDelay else params.dwellTime
        val waited = now - stillSince
        var level = frame.guidance
        var hesitated = frame.hesitated
        if (waited >= threshold) {
            level = (level + 1).coerceAtMost(2)
            // H7.1: hesitation after lock is rehearsal input, not level-1 uncertainty.
            if (frame.nest == NestState.LEVEL1) hesitated = true
            stillSince = now
        }
        frame = frame.copy(guidance = level, hesitated = hesitated, elapsed = now - downAt,
            untilHelp = (threshold - (now - stillSince)).coerceAtLeast(0))
    }

    fun release(x: Float, y: Float, time: Long): GestureResult? {
        if (!frame.active) return null
        val ended = move(x, y, time)
        if (!frame.active) return ended
        val center = hypot(x - frame.originX, y - frame.originY) <= params.deadZone
        val result = when {
            center && !everLeftCenter && frame.elapsed <= params.tapTimeout -> GestureResult.Tap
            center -> GestureResult.Cancelled(CancelReason.CENTER)
            frame.elapsed < params.minPress -> GestureResult.Cancelled(CancelReason.TOO_SHORT)
            frame.nest == NestState.CHILD -> GestureResult.Selected(frame.sector!!, frame.child)
            frame.sector == null -> GestureResult.Cancelled(CancelReason.OUTSIDE_ARC)
            else -> GestureResult.Selected(frame.sector!!)
        }
        frame = frame.copy(active = false)
        return result
    }

    fun cancel(reason: CancelReason): GestureResult? {
        if (!frame.active) return null
        frame = frame.copy(active = false, sector = null)
        return GestureResult.Cancelled(reason)
    }
}
