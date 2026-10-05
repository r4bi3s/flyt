package no.heimflyt.launcher

import android.content.Context
import android.graphics.Rect
import android.os.Build
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityNodeInfo
import no.heimflyt.launcher.gesture.*
import no.heimflyt.launcher.ui.home.AccessibleDirections
import kotlin.math.hypot

/** Android is the sole owner of gesture commits. Compose draws, but never infers a release. */
class RadialTouchView(context: Context) : View(context) {
    var tuning = TuningParams()
    var centerXdp = 0f
    var centerYdp = 0f
    var onFrame: (GestureFrame) -> Unit = {}
    var onResult: (GestureResult) -> Unit = {}
    var onBegin: () -> Unit = {}
    /** Accessibility-only dispatch; never passes through the recognizer, familiarity or recents. */
    var onAccessibleDirection: (HomeAction) -> Unit = {}
    /** Current loaded-settings snapshot (SURFACES.md §2.5). Null until settings have loaded: no direction actions then. */
    /** H7.1 experiment: the nested parent, or null (then this view behaves exactly as before). */
    var nested: NestedConfig? = null
    /** H7: a child release opens an app, so it gets the commit haptic too. */
    var childCommits = false
    /** Finished gesture's nested summary (instrumentation only). */
    val targets: NestedTargets get() = engine.targets
    private var nestedGesture = false
    var accessibleDirections: AccessibleDirections? = null
        set(value) {
            if (field === value) return
            field = value
            if (isAttachedToWindow) sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        }
    private val engine = RadialEngine()
    private var pointerId = -1
    private var ownsStream = false
    private var startedOnAffordance = false
    private val density get() = resources.displayMetrics.density
    private val tick = object : Runnable {
        override fun run() {
            if (!engine.frame.active) return
            engine.tick(SystemClock.uptimeMillis())
            onFrame(engine.frame)
            postDelayed(this,32)
        }
    }
    init {
        isClickable = true; isFocusable = true
        contentDescription = "Directions. Double-tap for the list; actions available."
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }
    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Button"
        val r=tuning.affordanceSize*density/2
        info.setBoundsInParent(Rect((centerXdp*density-r).toInt(),(centerYdp*density-r).toInt(),(centerXdp*density+r).toInt(),(centerYdp*density+r).toInt()))
        accessibleDirections?.actions?.forEach { info.addAction(AccessibilityNodeInfo.AccessibilityAction(it.id, it.label)) }
    }
    override fun performAccessibilityAction(action: Int, arguments: android.os.Bundle?): Boolean {
        if (!AccessibleDirections.isOurs(action)) return super.performAccessibilityAction(action, arguments)
        val entry = accessibleDirections?.find(action)
        if (entry == null) {
            // An obsolete node: never map an old id onto a newly bound slot.
            announceForAccessibility("That direction has changed. Open the directions list.")
            return true
        }
        cancel(CancelReason.SYSTEM)
        onAccessibleDirection(entry.action)
        return true
    }
    override fun performClick(): Boolean {
        super.performClick()
        onResult(GestureResult.Tap)
        return true
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            cancel(CancelReason.SYSTEM)
            val x=event.x/density; val y=event.y/density
            val onAffordance=hypot(x-centerXdp,y-centerYdp)<=tuning.affordanceSize/2f
            if ((!onAffordance && !anywhereAccepts(x,y)) || !isEnabled) return false
            ownsStream=true; pointerId=event.getPointerId(0); startedOnAffordance=onAffordance
            onBegin()
            nestedGesture=nested!=null
            engine.begin(x,y,event.eventTime,tuning,ViewConfiguration.getLongPressTimeout()/500f,nested)
            if(tuning.hapticDown) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            onFrame(engine.frame); postDelayed(tick,32)
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if(!ownsStream) return false
        // FLAG_CANCELED also protects palm rejection on Android 13+.
        if (event.actionMasked == MotionEvent.ACTION_CANCEL ||
            (Build.VERSION.SDK_INT >= 33 && event.flags and MotionEvent.FLAG_CANCELED != 0)) {
            cancel(CancelReason.SYSTEM); ownsStream=false; return true
        }
        if(event.actionMasked==MotionEvent.ACTION_POINTER_DOWN || event.pointerCount>1) {
            cancel(CancelReason.SECOND_POINTER); return true
        }
        val index=event.findPointerIndex(pointerId)
        if(index<0) { cancel(CancelReason.INVALID_POINTER); return true }
        val x=event.getX(index)/density; val y=event.getY(index)/density
        if(x<0f || y<0f || x>width/density || y>height/density) {
            cancel(CancelReason.OUTSIDE_SURFACE); return true
        }
        when(event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if(nestedGesture) moveNested(event,index,x,y) else {
                val previous=engine.frame.sector
                engine.move(x,y,event.eventTime)
                if(engine.frame.sector!=null && engine.frame.sector!=previous && engine.params.hapticSelection)
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onFrame(engine.frame)
            }
            MotionEvent.ACTION_UP -> {
                val wasLocked = engine.frame.nest != NestState.LEVEL1
                val oldChild = engine.frame.child
                val result=engine.release(x,y,event.eventTime)
                ownsStream=false; pointerId=-1; removeCallbacks(tick)
                parent?.requestDisallowInterceptTouchEvent(false)
                onFrame(engine.frame)
                if(nestedGesture && engine.params.hapticSelection &&
                    ((!wasLocked && engine.frame.nest != NestState.LEVEL1) ||
                        (engine.frame.child >= 0 && engine.frame.child != oldChild)))
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                if(result is GestureResult.Selected && (result.child < 0 || childCommits) && engine.params.hapticCommit)
                    performHapticFeedback(if(Build.VERSION.SDK_INT>=30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.KEYBOARD_TAP)
                when {
                    result==GestureResult.Tap && startedOnAffordance -> performClick()
                    result==GestureResult.Tap -> onResult(GestureResult.Cancelled(CancelReason.STRAY_TAP))
                    result!=null -> onResult(result)
                }
            }
        }
        return true
    }
    /**
     * H7.1 experiment only: historical samples in timestamp order through the same recognizer, then the current one.
     * The engine's frame is the one source for haptics and the result.
     */
    private fun moveNested(event: MotionEvent, index: Int, x: Float, y: Float) {
        val sector=engine.frame.sector; val child=engine.frame.child; val locked=engine.frame.nest!=NestState.LEVEL1
        var ended: GestureResult?=null
        for(h in 0 until event.historySize) {
            val hx=event.getHistoricalX(index,h)/density
            val hy=event.getHistoricalY(index,h)/density
            // Historical points are real input too: returning in the same batch must not revive an exited gesture.
            if(hx<0f || hy<0f || hx>width/density || hy>height/density) {
                cancel(CancelReason.OUTSIDE_SURFACE)
                return
            }
            ended=engine.move(hx,hy,event.getHistoricalEventTime(h),true)
            if(!engine.frame.active) break
        }
        if(engine.frame.active) ended=engine.move(x,y,event.eventTime)
        val f=engine.frame
        if(engine.params.hapticSelection && ((!locked && f.nest==NestState.LEVEL1 && f.sector!=null && f.sector!=sector) ||
                (!locked && f.nest!=NestState.LEVEL1) || (f.child>=0 && f.child!=child)))
            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        onFrame(f)
        if(ended!=null) {
            removeCallbacks(tick); onResult(ended); pointerId=-1
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }
    /** Anywhere: any down on this surface except the edge band, where some directions would start off-surface. */
    private fun anywhereAccepts(x: Float, y: Float): Boolean {
        if(!tuning.anywhere) return false
        val inset=RadialGeometry.anywhereInset(tuning)
        return x>=inset && y>=inset && x<=width/density-inset && y<=height/density-inset
    }
    fun cancel(reason: CancelReason) {
        removeCallbacks(tick)
        engine.cancel(reason)?.let { onFrame(engine.frame); onResult(it) }
        pointerId=-1
        parent?.requestDisallowInterceptTouchEvent(false)
    }
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        if(!hasWindowFocus) cancel(CancelReason.LIFECYCLE)
        super.onWindowFocusChanged(hasWindowFocus)
    }
    override fun onDetachedFromWindow() {
        cancel(CancelReason.LIFECYCLE)
        super.onDetachedFromWindow()
    }
}
