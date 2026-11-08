package com.gezimos.katapult.lockscreen

import android.content.Context
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.FrameLayout

/** Shared Back and stream owner for every edge page; vertical delivery stays with its child. */
internal open class EdgePageView(context: Context, private val back: () -> Unit,
                                private val captureSwipe: (Float, Float) -> Boolean,
                                private val closeSwipe: (Float, Float) -> Boolean) : FrameLayout(context) {
    private var x = 0f
    private var y = 0f
    private var swiping = false
    private var cancelled = false
    init { isFocusableInTouchMode = true }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP) back()
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { x = event.rawX; y = event.rawY; swiping = false; cancelled = false }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelled = true
        }
        val dx = event.rawX - x
        val dy = event.rawY - y
        if (!cancelled && (event.actionMasked == MotionEvent.ACTION_MOVE || event.actionMasked == MotionEvent.ACTION_UP) &&
            captureSwipe(dx, dy)) swiping = true
        if (swiping) super.requestDisallowInterceptTouchEvent(false)
        if (event.actionMasked == MotionEvent.ACTION_UP && swiping) {
            val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
            try { super.dispatchTouchEvent(cancel) } finally { cancel.recycle() }
            if (!cancelled && closeSwipe(dx, dy)) back()
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = swiping
    override fun onTouchEvent(event: MotionEvent): Boolean = true
    open fun settled() {}
    open fun dispose() {}
}
