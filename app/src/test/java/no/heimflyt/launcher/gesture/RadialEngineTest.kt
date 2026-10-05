package no.heimflyt.launcher.gesture

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class RadialEngineTest {
    @Test fun shapeAndCountAreIndependentAndEveryDirectionCommitsFromAnyOrigin() {
        val base=TuningParams(arcStart=333f,deadZone=31f,menuRadius=120f,hesitationDelay=700,initialGuidance=0)
        for(span in listOf(180f,360f)) for(count in 5..8) for(left in listOf(false,true))
            for((ox,oy) in listOf(190f to 280f,60f to 700f,330f to 90f)) {
                val p=base.copy(arcSpan=span,sectorCount=count,leftHanded=left)
                repeat(count) { i ->
                    val engine=RadialEngine(); engine.begin(ox,oy,0,p)
                    val (x,y)=vector(RadialGeometry.sectorAngle(i,p))
                    assertEquals(GestureResult.Selected(i),engine.release(ox+x,oy+y,60))
                }
            }
    }
    @Test fun returningToCenterKeepsLastSectorForAttribution() {
        val e=RadialEngine(); e.begin(0f,0f,0,TuningParams(arcSpan=360f,sectorCount=8))
        val (x,y)=vector(RadialGeometry.sectorAngle(3,TuningParams(arcSpan=360f,sectorCount=8)))
        e.move(x,y,40)
        assertEquals(GestureResult.Cancelled(CancelReason.CENTER),e.release(0f,0f,90))
        assertEquals(3,e.frame.lastSector); assertNull(e.frame.sector)
    }

    @Test fun fullCircleSeamHasHysteresisInBothDirections() {
        for(count in listOf(6,8)) for(left in listOf(false,true)) {
            val p=TuningParams(arcStart=330f,arcSpan=360f,sectorCount=count,leftHanded=left)
            fun select(offset:Float,previous:Int):Int? {
                val a=p.arcStart+offset
                val (x,y)=vector(if(left) 180f-a else a)
                return RadialGeometry.selection(x,y,p,previous)
            }
            assertEquals(count-1,select(2f,count-1))
            assertEquals(0,select(6f,count-1))
            assertEquals(0,select(-2f,0))
            assertEquals(count-1,select(-6f,0))
        }
    }

    private fun vector(angle: Float, distance: Float = 90f): Pair<Float, Float> {
        val r = Math.toRadians(angle.toDouble())
        return (cos(r) * distance).toFloat() to (sin(r) * distance).toFloat()
    }
    @Test fun everySlotReachableAndMirrored() {
        for (count in 5..8) for (left in listOf(false, true)) for (span in listOf(90f, 180f, 360f)) {
            val p = TuningParams(sectorCount = count, leftHanded = left, arcSpan = span)
            repeat(count) { i ->
                val (x,y) = vector(RadialGeometry.sectorAngle(i,p))
                assertEquals(i, RadialGeometry.selection(x,y,p,null))
            }
        }
    }
    @Test fun fastFlickCommitsWithoutHoldOrDisclosure() {
        val e=RadialEngine(); e.begin(0f,0f,0,TuningParams(initialGuidance=0))
        val (x,y)=vector(210f)
        assertEquals(GestureResult.Selected(2),e.release(x,y,70))
        assertEquals(0,e.frame.guidance)
        assertNull(e.release(x,y,80))
    }
    @Test fun everyCancellationInvalidatesLaterUp() {
        for (reason in CancelReason.entries) {
            val e=RadialEngine(); e.begin(0f,0f,0,TuningParams()); e.move(-90f,-20f,60)
            assertEquals(GestureResult.Cancelled(reason),e.cancel(reason))
            assertNull(e.release(-90f,-20f,100))
            assertNull(e.cancel(reason))
        }
    }
    @Test fun tapVersusReturnToCenter() {
        val e=RadialEngine(); e.begin(0f,0f,0,TuningParams())
        assertEquals(GestureResult.Tap,e.release(3f,1f,100))
        e.begin(0f,0f,200,TuningParams()); e.move(-80f,-40f,250)
        assertEquals(GestureResult.Cancelled(CancelReason.CENTER),e.release(0f,0f,300))
        e.begin(0f,0f,400,TuningParams())
        assertEquals(GestureResult.Cancelled(CancelReason.CENTER),e.release(0f,0f,900))
    }
    @Test fun outsideArcNeverRetainsHighlightedSector() {
        val p=TuningParams(); val e=RadialEngine(); e.begin(0f,0f,0,p); e.move(-90f,-30f,50)
        assertNotNull(e.frame.sector)
        assertEquals(GestureResult.Cancelled(CancelReason.OUTSIDE_ARC),e.release(80f,0f,100))
    }
    @Test fun boundaryHysteresisAndDeadZone() {
        val p=TuningParams(arcStart=0f)
        val (x,y)=vector(31f)
        assertEquals(0,RadialGeometry.selection(x,y,p,0))
        assertEquals(1,RadialGeometry.selection(x,y,p,null))
        val (a,b)=vector(36f)
        assertEquals(1,RadialGeometry.selection(a,b,p,0))
        assertNull(RadialGeometry.selection(1f,1f,p,0))
    }
    @Test fun helpEscalatesWithoutPointerMovement() {
        val e=RadialEngine(); e.begin(0f,0f,0,TuningParams(initialGuidance=0,hesitationDelay=300))
        e.tick(299); assertEquals(0,e.frame.guidance)
        e.tick(300); assertEquals(1,e.frame.guidance)
        e.tick(600); assertEquals(2,e.frame.guidance); assertTrue(e.frame.hesitated)
    }
    @Test fun outsideDwellRevealsHelpWhileMovingFastDoesNot() {
        val e=RadialEngine(); e.begin(0f,0f,0,TuningParams(initialGuidance=0,dwellTime=300))
        e.move(-80f,-20f,50); e.tick(349); assertEquals(0,e.frame.guidance)
        e.tick(350); assertEquals(1,e.frame.guidance)
    }
    @Test fun fullCircleWrapAndMinimumPress() {
        val p=TuningParams(arcStart=330f,arcSpan=360f,minPress=80)
        val e=RadialEngine(); e.begin(0f,0f,0,p)
        assertEquals(GestureResult.Cancelled(CancelReason.TOO_SHORT),e.release(90f,0f,50))
        assertEquals(0,RadialGeometry.selection(90f,0f,p,null))
    }
    @Test fun malformedParamsAreBounded() {
        val p=TuningParams(deadZone=Float.NaN,arcSpan=Float.POSITIVE_INFINITY,sectorCount=999,minPress=-4).safe()
        assertEquals(24f,p.deadZone); assertEquals(180f,p.arcSpan); assertEquals(8,p.sectorCount); assertEquals(0L,p.minPress)
    }
    @Test fun accessibilityHelpScaleDoesNotSlowInput() {
        val p=TuningParams(initialGuidance=0,hesitationDelay=1000)
        val e=RadialEngine(); e.begin(0f,0f,0,p,2f)
        e.tick(1999); assertEquals(0,e.frame.guidance)
        e.tick(2000); assertEquals(1,e.frame.guidance)
        e.begin(0f,0f,3000,p,2f)
        assertTrue(e.release(-90f,-20f,3050) is GestureResult.Selected)
    }
}
