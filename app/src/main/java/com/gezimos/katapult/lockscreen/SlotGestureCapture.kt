package com.gezimos.katapult.lockscreen

import android.graphics.Path
import android.view.MotionEvent
import android.view.View

/** Native stream capture shared by the router and its consumer tests; replay starts only after lift. */
internal class SlotGestureCapture(
    private val width: Int,
    private val height: Int,
    private val slop: Float,
    private val eligible: () -> Boolean,
    private val switch: (Int) -> Unit,
    private val replay: (Path, Long) -> Unit,
    private val trackingChanged: (Boolean) -> Unit,
) : View.OnTouchListener {
    private var tracking = false
    private var cancelled = false
    private var x = 0f
    private var y = 0f
    private var started = 0L
    private var path = Path()

    private fun point(event: MotionEvent, first: Boolean = false) {
        val p = EdgeSwipe.clamp(event.rawX, event.rawY, width, height)
        if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
    }

    override fun onTouch(view: View, event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (!eligible()) return true
                tracking = true; cancelled = false; trackingChanged(true)
                x = event.rawX; y = event.rawY; started = event.eventTime
                path = Path(); point(event, true)
            }
            MotionEvent.ACTION_POINTER_DOWN -> cancelled = true
            MotionEvent.ACTION_MOVE -> if (tracking && !cancelled) point(event)
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false; trackingChanged(false)
                if (!cancelled && eligible()) {
                    point(event)
                    val delta = widgetSlotSwipe(event.rawX - x, event.rawY - y, slop)
                    if (delta != 0) switch(delta) else replay(Path(path), event.eventTime - started)
                }
            }
            MotionEvent.ACTION_CANCEL -> { tracking = false; cancelled = true; trackingChanged(false) }
        }
        return true
    }
}
