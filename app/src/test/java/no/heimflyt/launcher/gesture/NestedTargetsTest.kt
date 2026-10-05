package no.heimflyt.launcher.gesture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class NestedTargetsTest {
    private val p = TuningParams(arcStart = 135f, arcSpan = 360f, sectorCount = 8, initialGuidance = 0)
    private val parent = 3
    private val axis = RadialGeometry.sectorAngle(parent, p)
    private val r = p.menuRadius
    /** Default, the smallest and tightest fan, and the largest and widest. */
    private val fans = listOf(Fan(), Fan(20f, 30f, 12f), Fan(56f, 160f, 56f))

    private fun point(angle: Float, radius: Float): Pair<Float, Float> {
        val a = Math.toRadians(angle.toDouble())
        return radius * cos(a).toFloat() to radius * sin(a).toFloat()
    }
    /** Along the axis and across it (screen-clockwise positive), for a right-handed fan. */
    private fun local(x: Float, y: Float): Pair<Float, Float> {
        val a = Math.toRadians(axis.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        return x * c + y * s to -x * s + y * c
    }
    private fun engine(fan: Fan = Fan(), offset: Float = 0f, tuning: TuningParams = p): RadialEngine {
        val e = RadialEngine(); e.begin(0f, 0f, 0, tuning, nested = NestedConfig.single(parent, fan))
        val (x, y) = point(RadialGeometry.sectorAngle(parent, tuning) + offset, 50f); e.move(x, y, 8)
        val (lx, ly) = point(RadialGeometry.sectorAngle(parent, tuning) + offset, 62f); e.move(lx, ly, 16)
        assertEquals(NestState.PARENT, e.frame.nest)
        return e
    }

    @Test fun everyIconCentreSelectsAcrossTheSliderRangesAndBothHands() {
        for (fan in fans) for (left in listOf(false, true)) for (slot in 0..3) {
            val tuning = p.copy(leftHanded = left)
            val e = engine(fan, tuning = tuning)
            val (x, y) = Nest.target(slot, 0f, 0f, RadialGeometry.sectorAngle(parent, tuning), left, fan, r)
            e.move(x, y, 24)
            assertEquals("$fan $left $slot", slot, e.frame.child)
            assertEquals(GestureResult.Selected(parent, slot), e.release(x, y, 32))
        }
    }

    @Test fun fanOpensBeyondTheRadialSymmetricallyAboutTheAxis() {
        for (fan in fans) {
            val at = List(4) { Nest.target(it, 0f, 0f, axis, false, fan, r).let { (x, y) -> local(x, y) } }
            // Every icon is past the lock ring along the axis and equally far from the nozzle on it.
            for ((along, across) in at) {
                assertTrue("$fan", along > Nest.R1)
                assertEquals(Nest.reach(fan, r), hypot(along - Nest.R1, across), 0.01f)
            }
            // Claude and Grok on one side, Gemini and ChatGPT mirrored on the other; the outer pair sits lower.
            assertEquals(at[Nest.CLAUDE].first, at[Nest.CHATGPT].first, 0.01f); assertEquals(-at[Nest.CLAUDE].second, at[Nest.CHATGPT].second, 0.01f)
            assertEquals(at[Nest.GROK].first, at[Nest.GEMINI].first, 0.01f); assertEquals(-at[Nest.GROK].second, at[Nest.GEMINI].second, 0.01f)
            assertTrue(at[Nest.CLAUDE].second < at[Nest.GROK].second && at[Nest.GROK].second < 0f)
            assertTrue(at[Nest.CLAUDE].first < at[Nest.GROK].first)
            assertEquals(Nest.spacing(fan, r), hypot(at[1].first - at[0].first, at[1].second - at[0].second), 0.01f)
        }
        // The default fan's icons, drawn disc included, all clear the drawn radial and the axis label beyond it
        // (a #agenter pill at the owner's font scale: about 116 x 33 dp centred 126 dp out).
        val fan = Fan()
        for (slot in 0..3) {
            val (along, across) = Nest.target(slot, 0f, 0f, axis, false, fan, r).let { (x, y) -> local(x, y) }
            assertTrue(hypot(along, across) - fan.size / 2f > r)
            assertTrue(along - fan.size / 2f - 8f > r + 26f + 17f || kotlin.math.abs(across) - fan.size / 2f - 8f > 58f)
        }
    }

    @Test fun touchTargetStaysComfortableWhenTheIconShrinks() {
        assertEquals(Nest.hitRadius(Fan(size = 32f), r), Nest.hitRadius(Fan(size = 20f), r), 0f)
        assertTrue(Nest.hitRadius(Fan(size = 20f), r) > 24f)
        for (fan in fans) {
            assertTrue(Nest.hitRadius(fan, r) >= fan.size / 2f + 6f)
            assertTrue(Nest.lane(fan, r) < Nest.spacing(fan, r) / 2f)
        }
        assertEquals(Fan(), Fan(Float.NaN, Float.NaN, Float.NaN).safe())
        assertEquals(Fan(20f, 160f, 56f), Fan(1f, 999f, 999f).safe())
    }

    @Test fun finiteCirclesLeaveParentEverywhereElse() {
        for (fan in fans) {
            val e = engine(fan)
            // Short of the fan on the way to Grok, then far out to the side: neither is an icon.
            val (tx, ty) = Nest.target(Nest.GROK, 0f, 0f, axis, false, fan, r)
            val scale = (Nest.gate(fan, r) - 1f) / hypot(tx, ty)
            e.move(tx * scale, ty * scale, 24)
            assertEquals("$fan", -1, e.frame.child)
            val (x, y) = point(axis + 100f, 160f)
            e.move(x, y, 32)
            assertEquals(-1, e.frame.child)
            assertEquals(GestureResult.Selected(parent), e.release(x, y, 40))
        }
    }

    @Test fun incomingLaneProtectsStraightStrokesOffTheAxis() {
        val e = engine(offset = 10f)
        for (radius in listOf(130f, 170f, 220f)) {
            val (x, y) = point(axis + 10f, radius); e.move(x, y, radius.toLong())
            assertEquals(-1, e.frame.child)
        }
        assertEquals(GestureResult.Selected(parent), e.release(e.frame.x, e.frame.y, 240))
        // The same point is inside the fixed Gemini circle; the protection is doing real work.
        val (x, y) = point(axis + 10f, 170f)
        assertEquals(Nest.GEMINI, Nest.hit(x, y, 0f, 0f, axis, axis, false, Fan(), r))
    }

    @Test fun pullingBackOutOfTheFanClearsAndCorrectionCanSwitchInsideIt() {
        val e = engine()
        val a = Nest.target(Nest.CLAUDE, 0f, 0f, axis, false, Fan(), r)
        val b = Nest.target(Nest.GROK, 0f, 0f, axis, false, Fan(), r)
        e.move(a.first, a.second, 24); assertEquals(Nest.CLAUDE, e.frame.child)
        e.move(b.first, b.second, 32); assertEquals(Nest.GROK, e.frame.child)
        assertEquals(2, e.targets.changes)
        val scale = 110f / hypot(b.first, b.second)
        e.move(b.first * scale, b.second * scale, 40)
        assertEquals(-1, e.frame.child)
        assertTrue(e.targets.clears > 0)
        assertEquals(GestureResult.Selected(parent), e.release(e.frame.x, e.frame.y, 48))
    }

    @Test fun handOverFollowsTheThumbOutAndBackWithoutTime() {
        for (fan in fans) {
            val gate = Nest.gate(fan, r)
            assertEquals(0f, Nest.reveal(Nest.R1, fan, r), 0f)
            assertEquals(0f, Nest.reveal(30f, fan, r), 0f)
            assertEquals(1f, Nest.reveal(maxOf(gate, Nest.R1 + 1f), fan, r), 0.001f)
            assertEquals(1f, Nest.reveal(400f, fan, r), 0f)
            // Monotonic outward, so the same distance on the way back gives the same picture.
            var last = 0f
            for (d in 60..250 step 5) { val v = Nest.reveal(d.toFloat(), fan, r); assertTrue(v >= last); last = v }
        }
        // Default fan: fully shown at the first target's edge, about 140 dp out.
        assertEquals(140f, Nest.gate(Fan(), r), 2f)
    }

    @Test fun eachNestedDirectionLocksItselfAndEmptySlotsAreParent() {
        val other = 6
        val config = NestedConfig(mapOf(parent to NestedConfig.ALL, other to 0b0110))
        val e = RadialEngine(); e.begin(0f, 0f, 0, p, nested = config)
        val otherAxis = RadialGeometry.sectorAngle(other, p)
        point(otherAxis, 50f).let { (x, y) -> e.move(x, y, 8) }
        point(otherAxis, 62f).let { (x, y) -> e.move(x, y, 16) }
        assertEquals(NestState.PARENT, e.frame.nest); assertEquals(other, e.frame.sector)
        // Claude's slot is a hole here: its centre is plain parent.
        val (cx, cy) = Nest.target(Nest.CLAUDE, 0f, 0f, otherAxis, false, Fan(), r)
        e.move(cx, cy, 24); assertEquals(-1, e.frame.child)
        val (gx, gy) = Nest.target(Nest.GEMINI, 0f, 0f, otherAxis, false, Fan(), r)
        e.move(gx, gy, 32); assertEquals(Nest.GEMINI, e.frame.child)
        assertEquals(GestureResult.Selected(other, Nest.GEMINI), e.release(gx, gy, 40))
        // A level-1 direction that is not nested behaves exactly as before.
        val plain = RadialEngine(); plain.begin(0f, 0f, 0, p, nested = config)
        val (vx, vy) = point(RadialGeometry.sectorAngle(1, p), 90f)
        assertEquals(GestureResult.Selected(1), plain.release(vx, vy, 80))
        assertEquals(NestState.LEVEL1, plain.frame.nest)
    }

    @Test fun historicalLockAndOffParity() {
        val e = RadialEngine(); e.begin(0f, 0f, 0, p, nested = NestedConfig.single(parent))
        val a = point(axis, 50f); val b = point(axis, 62f)
        e.move(a.first, a.second, 8); e.move(b.first, b.second, 16, historical = true)
        assertTrue(e.targets.lockOnHistorical)
        val off = RadialEngine(); off.begin(0f, 0f, 0, p)
        for (slot in 0 until 8) {
            val x = RadialEngine(); x.begin(0f, 0f, 0, p)
            val (vx, vy) = point(RadialGeometry.sectorAngle(slot, p), 90f)
            assertEquals(GestureResult.Selected(slot), x.release(vx, vy, 80))
        }
        assertEquals(NestState.LEVEL1, off.frame.nest)
    }

    @Test fun correctingFromAnOrdinarySectorIntoAGroupOpensItsChildrenWithoutLifting() {
        for (left in listOf(false, true)) for (slot in 0..3) {
            val tuning = p.copy(leftHanded = left)
            val e = RadialEngine()
            e.begin(0f, 0f, 0, tuning, nested = NestedConfig.single(parent))
            point(RadialGeometry.sectorAngle(1, tuning), 95f).let { (x, y) -> e.move(x, y, 20) }
            assertEquals(NestState.LEVEL1, e.frame.nest)
            val correctedAxis = RadialGeometry.sectorAngle(parent, tuning)
            point(correctedAxis, 95f).let { (x, y) -> e.move(x, y, 40) }
            assertEquals(NestState.PARENT, e.frame.nest)
            assertEquals(parent, e.frame.sector)
            val (x, y) = Nest.target(slot, 0f, 0f, correctedAxis, left, Fan(), r)
            assertEquals(GestureResult.Selected(parent, slot), e.release(x, y, 80))
        }
    }

    @Test fun enteringAGroupLateAtAnIconDoesNotAccidentallyLaunchIt() {
        val e = RadialEngine()
        e.begin(0f, 0f, 0, p, nested = NestedConfig.single(parent))
        point(RadialGeometry.sectorAngle(1, p), 190f).let { (x, y) -> e.move(x, y, 20) }
        val (x, y) = Nest.target(Nest.GEMINI, 0f, 0f, axis, false, Fan(), r)
        e.move(x, y, 40)
        assertEquals(NestState.PARENT, e.frame.nest)
        assertEquals(-1, e.frame.child)
        assertEquals(GestureResult.Selected(parent), e.release(x, y, 60))
    }

    @Test fun pullingInsideLockRingAllowsAnotherGroupButTheDeadZoneStillCancels() {
        val other = 4
        val e = RadialEngine()
        e.begin(0f, 0f, 0, p, nested = NestedConfig(mapOf(parent to 15, other to 15)))
        point(axis, 80f).let { (x, y) -> e.move(x, y, 20) }
        val first = Nest.target(Nest.CLAUDE, 0f, 0f, axis, false, Fan(), r)
        e.move(first.first, first.second, 40)
        assertTrue(e.frame.childEver)
        point(axis, 45f).let { (x, y) -> e.move(x, y, 60) }
        assertTrue(e.frame.active)
        assertEquals(NestState.LEVEL1, e.frame.nest)
        val otherAxis = RadialGeometry.sectorAngle(other, p)
        point(otherAxis, 90f).let { (x, y) -> e.move(x, y, 80) }
        assertEquals(other, e.frame.sector)
        assertEquals(NestState.PARENT, e.frame.nest)
        assertTrue(e.frame.childEver) // Switching groups must not teach level-one familiarity.
        assertEquals(GestureResult.Cancelled(CancelReason.CENTER), e.move(0f, 0f, 100))
        assertNull(e.release(first.first, first.second, 120))
    }
}
