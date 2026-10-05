package no.heimflyt.launcher

import android.app.Application
import android.view.MotionEvent
import no.heimflyt.launcher.gesture.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],application=Application::class)
class RadialTouchViewTest {
    private lateinit var view: RadialTouchView
    private val results=mutableListOf<GestureResult>()
    private var density=1f
    @Before fun setup() {
        val context=RuntimeEnvironment.getApplication()
        density=context.resources.displayMetrics.density
        view=RadialTouchView(context).apply {
            centerXdp=200f; centerYdp=300f
            tuning=TuningParams(hapticSelection=false,hapticCommit=false)
            onResult={results+=it}
            layout(0,0,(400*density).toInt(),(800*density).toInt())
        }
    }
    private fun event(action:Int,x:Float=200f,y:Float=300f,time:Long=0) {
        val e=MotionEvent.obtain(0,time,action,x*density,y*density,0)
        view.onTouchEvent(e); e.recycle()
    }
    @Test fun genuineUpCommitsExactlyOnce() {
        event(MotionEvent.ACTION_DOWN)
        event(MotionEvent.ACTION_MOVE,120f,260f,40)
        event(MotionEvent.ACTION_UP,120f,260f,80)
        event(MotionEvent.ACTION_UP,120f,260f,90)
        assertEquals(1,results.size); assertTrue(results.single() is GestureResult.Selected)
    }
    @Test fun systemCancelFollowedByUpCannotLaunch() {
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_MOVE,120f,260f,40)
        event(MotionEvent.ACTION_CANCEL,120f,260f,60); event(MotionEvent.ACTION_UP,120f,260f,80)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.SYSTEM)),results)
    }
    @Test fun focusLossFollowedByUpCannotLaunch() {
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_MOVE,120f,260f,40)
        view.onWindowFocusChanged(false); event(MotionEvent.ACTION_UP,120f,260f,80)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.LIFECYCLE)),results)
    }
    @Test fun homeAndPauseCancelBeforeRelease() {
        for(reason in listOf(CancelReason.HOME,CancelReason.LIFECYCLE)) {
            results.clear(); event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_MOVE,120f,260f,40)
            view.cancel(reason); event(MotionEvent.ACTION_UP,120f,260f,80)
            assertEquals(listOf(GestureResult.Cancelled(reason)),results)
        }
    }
    @Test fun secondFingerCancelsFirstFingerUp() {
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_MOVE,120f,260f,40)
        val props=Array(2) { i -> MotionEvent.PointerProperties().apply { id=i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
        val coords=Array(2) { i -> MotionEvent.PointerCoords().apply { x=(120f+i*10)*density; y=260*density; pressure=1f; size=1f } }
        val e=MotionEvent.obtain(0,60,MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),2,props,coords,0,0,1f,1f,0,0,0,0)
        view.onTouchEvent(e); e.recycle(); event(MotionEvent.ACTION_UP,120f,260f,80)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.SECOND_POINTER)),results)
    }
    @Test fun touchesOutsideAffordanceAreNotOwned() {
        event(MotionEvent.ACTION_DOWN,0f,0f); event(MotionEvent.ACTION_UP,120f,260f,80)
        assertTrue(results.isEmpty())
    }
    @Test fun tapAndAccessibleClickBothOpenMenu() {
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_UP,time=50)
        view.performClick()
        assertEquals(listOf(GestureResult.Tap,GestureResult.Tap),results)
    }
    @Test fun leavingSurfaceThenReenteringCannotCommit() {
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_MOVE,-5f,260f,40)
        event(MotionEvent.ACTION_UP,120f,260f,80)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.OUTSIDE_SURFACE)),results)
    }
    @Test fun anywhereUsesTouchPointAsOriginAwayFromAffordance() {
        view.tuning=view.tuning.copy(anywhere=true,arcSpan=360f,sectorCount=8)
        val p=view.tuning
        val a=Math.toRadians(RadialGeometry.sectorAngle(5,p).toDouble())
        event(MotionEvent.ACTION_DOWN,100f,600f)
        event(MotionEvent.ACTION_UP,100f+80f*Math.cos(a).toFloat(),600f+80f*Math.sin(a).toFloat(),80)
        assertEquals(listOf(GestureResult.Selected(5)),results)
    }
    @Test fun fixedModeIgnoresTheSameTouch() {
        event(MotionEvent.ACTION_DOWN,100f,600f); event(MotionEvent.ACTION_UP,20f,600f,80)
        assertTrue(results.isEmpty())
    }
    @Test fun anywhereIgnoresEdgeBand() {
        view.tuning=view.tuning.copy(anywhere=true)
        val inset=RadialGeometry.anywhereInset(view.tuning)
        for((x,y) in listOf(inset-1f to 400f,400f-inset+1f to 400f,200f to inset-1f,200f to 800f-inset+1f)) {
            event(MotionEvent.ACTION_DOWN,x,y); event(MotionEvent.ACTION_UP,200f,400f,80)
        }
        assertTrue(results.isEmpty())
        event(MotionEvent.ACTION_DOWN,inset+1f,400f); event(MotionEvent.ACTION_UP,inset+1f,400f,50)
        assertEquals(1,results.size)
    }
    @Test fun anywhereStrayTapIsIgnoredButAffordanceTapOpensMenu() {
        view.tuning=view.tuning.copy(anywhere=true)
        event(MotionEvent.ACTION_DOWN,100f,600f); event(MotionEvent.ACTION_UP,100f,600f,50)
        event(MotionEvent.ACTION_DOWN); event(MotionEvent.ACTION_UP,time=50)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.STRAY_TAP),GestureResult.Tap),results)
    }

    // ---- H7.1 experiment: ordered historical samples, only while a nested parent is set ----
    private val owner=TuningParams(anywhere=true,arcStart=135f,arcSpan=360f,sectorCount=8,hapticSelection=false,hapticCommit=false)
    private fun at(angle:Float,distance:Float,fromX:Float=200f,fromY:Float=400f):Pair<Float,Float> {
        val r=Math.toRadians(angle.toDouble())
        return fromX+distance*Math.cos(r).toFloat() to fromY+distance*Math.sin(r).toFloat()
    }
    /** One MOVE whose earlier points are delivered as historical samples, oldest first; the last point is the current sample. */
    private fun batch(points:List<Pair<Float,Float>>,start:Long) {
        val e=MotionEvent.obtain(0,start,MotionEvent.ACTION_MOVE,points[0].first*density,points[0].second*density,0)
        points.drop(1).forEachIndexed { i,(x,y) -> e.addBatch(start+(i+1)*4,x*density,y*density,1f,1f,0) }
        assertEquals(points.size-1,e.historySize)
        view.onTouchEvent(e); e.recycle()
    }
    /** Along the parent axis, then to the fixed Claude icon. */
    private fun iconPath():List<Pair<Float,Float>> {
        val icon=Nest.target(Nest.CLAUDE,200f,400f,292.5f,false,Fan(),owner.menuRadius)
        return listOf(at(292.5f,50f),at(292.5f,64f),icon,icon)
    }
    @Test fun nestedOnConsumesHistoricalSamplesInOrder() {
        view.tuning=owner; view.nested=NestedConfig.single(3)
        val frames=mutableListOf<GestureFrame>(); view.onFrame={frames+=it}
        event(MotionEvent.ACTION_DOWN,200f,400f)
        val path=iconPath(); batch(path,10)
        assertEquals(NestState.CHILD,frames.last().nest); assertEquals(Nest.CLAUDE,frames.last().child); assertEquals(3,frames.last().sector)
        assertTrue(view.targets.lockOnHistorical)
        event(MotionEvent.ACTION_UP,path.last().first,path.last().second,60)
        assertEquals(listOf(GestureResult.Selected(3,Nest.CLAUDE)),results)
    }
    @Test fun nestedOffIgnoresHistoricalSamplesExactlyAsBefore() {
        view.tuning=owner
        val frames=mutableListOf<GestureFrame>(); view.onFrame={frames+=it}
        event(MotionEvent.ACTION_DOWN,200f,400f)
        val path=iconPath(); batch(path,10)
        // Only the delivered endpoint is seen: one frame for the MOVE, ordinary level 1, the neighbouring direction.
        assertEquals(2,frames.size); assertEquals(NestState.LEVEL1,frames.last().nest)
        event(MotionEvent.ACTION_UP,path.last().first,path.last().second,60)
        assertEquals(listOf(GestureResult.Selected(frames.last().sector!!)),results)
    }
    @Test fun historicalSamplesCanLockBeforeTheCurrentIconPoint() {
        view.tuning=owner; view.nested=NestedConfig.single(3)
        event(MotionEvent.ACTION_DOWN,200f,400f)
        val path=iconPath()
        batch(path,10)
        event(MotionEvent.ACTION_UP,path.last().first,path.last().second,60)
        assertEquals(listOf(GestureResult.Selected(3,Nest.CLAUDE)),results)
    }
    @Test fun lockedStrokeBackInDeadZoneCancelsOnceAndLaterUpCannotLaunch() {
        view.tuning=owner; view.nested=NestedConfig.single(3)
        event(MotionEvent.ACTION_DOWN,200f,400f)
        val out=iconPath(); batch(out,10)
        batch(listOf(at(292.5f,40f),at(292.5f,10f),at(292.5f,80f)),40)
        assertEquals(listOf(GestureResult.Cancelled(CancelReason.CENTER)),results)
        val (x,y)=at(292.5f,80f); event(MotionEvent.ACTION_MOVE,x,y,70); event(MotionEvent.ACTION_UP,x,y,80)
        assertEquals(1,results.size)
    }

    @Test fun nestedHistoricalExitCancelsEvenWhenCurrentPointReturnsToChild() {
        for (outside in listOf(-1f to 400f, 401f to 400f, 200f to -1f, 200f to 801f)) {
            results.clear()
            view.tuning=owner; view.nested=NestedConfig.single(3)
            event(MotionEvent.ACTION_DOWN,200f,400f)
            val path=iconPath(); batch(path,10)
            batch(listOf(outside,path.last()),40)
            event(MotionEvent.ACTION_UP,path.last().first,path.last().second,80)
            assertEquals("Historical exit at $outside", listOf(GestureResult.Cancelled(CancelReason.OUTSIDE_SURFACE)),results)
        }
    }
}
