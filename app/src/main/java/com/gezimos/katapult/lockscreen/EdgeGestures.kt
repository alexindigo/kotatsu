package com.gezimos.katapult.lockscreen

import android.accessibilityservice.AccessibilityService
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** The sole input owner for both edges. Consumers supply eligibility and action callbacks. */
internal class EdgeGestures(
    private val service: AccessibilityService,
    handler: Handler,
    private val store: EdgeActionStore,
    private val eligible: () -> Boolean,
    private val changed: () -> Unit,
    private val runAction: (ScreenEdge, EdgeAction) -> Unit,
    private val preview: (ScreenEdge, Int) -> Unit,
    private val cancelPreview: () -> Unit,
) : AutoCloseable {
    private val wm = service.getSystemService(WindowManager::class.java)
    private val strips = mutableListOf<Pair<View, String>>()
    private val replay = ReplayHandoff(service, handler, eligible, changed)
    private var tracking = false
    private var closed = false
    private var geometry: Triple<Int, Int, Int>? = null

    fun apply(show: Boolean) {
        if (closed) return
        if (!show) { replay.cancel("ineligible"); remove(); return }
        if (replay.busy || tracking) return
        val bounds = wm.maximumWindowMetrics.bounds
        val id = service.resources.getIdentifier("status_bar_height", "dimen", "android")
        val top = if (id > 0) service.resources.getDimensionPixelSize(id) else (24 * service.resources.displayMetrics.density).toInt()
        val next = Triple(bounds.width(), bounds.height(), top)
        if (strips.isNotEmpty() && geometry == next) return
        remove()
        geometry = next
        try {
            ScreenEdge.entries.forEach { edge ->
                val title = "Kotatsu edge ${edge.name}"
                val view = View(service)
                capture(view, edge, bounds.width(), bounds.height())
                val params = WindowManager.LayoutParams().apply {
                    type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    format = PixelFormat.TRANSLUCENT
                    flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    width = (16 * service.resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    height = (bounds.height() - top).coerceAtLeast(1)
                    gravity = Gravity.TOP or if (edge == ScreenEdge.LEFT) Gravity.LEFT else Gravity.RIGHT
                    y = top
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                    setFitInsetsTypes(0)
                    setTitle(title)
                }
                wm.addView(view, params)
                strips += view to title
            }
        } catch (error: RuntimeException) {
            android.util.Log.w("EdgeGestures", "Could not attach edges", error)
            remove()
        }
    }

    private fun capture(view: View, edge: ScreenEdge, width: Int, height: Int) {
        var x = 0f
        var y = 0f
        var started = 0L
        var path = Path()
        var cancelled = false
        fun point(event: MotionEvent, first: Boolean = false) {
            val p = EdgeSwipe.clamp(event.rawX, event.rawY, width, height)
            if (first) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    if (tracking || replay.busy || !eligible()) return@setOnTouchListener true
                    tracking = true; cancelled = false
                    x = event.rawX; y = event.rawY; started = event.eventTime
                    path = Path(); point(event, true)
                }
                MotionEvent.ACTION_POINTER_DOWN -> { cancelled = true; cancelPreview() }
                MotionEvent.ACTION_MOVE -> if (tracking && !cancelled) {
                    point(event)
                    if (store.enabled && store.action(edge) == EdgeAction.TASK_LIST) {
                        preview(edge, EdgeSwipe.previewStep(edge, event.rawX - x, event.rawY - y, width,
                            8 * service.resources.displayMetrics.density))
                    } else cancelPreview()
                }
                MotionEvent.ACTION_UP -> if (tracking) {
                    tracking = false
                    if (!cancelled && eligible()) {
                        point(event)
                        when (EdgeSwipe.decide(edge, event.rawX - x, event.rawY - y, width,
                                8 * service.resources.displayMetrics.density)) {
                            EdgeSwipeDecision.ACTION -> {
                                if (store.action(edge) != EdgeAction.TASK_LIST) cancelPreview()
                                runAction(edge, store.action(edge))
                            }
                            EdgeSwipeDecision.REPLAY -> { cancelPreview(); replay.begin(path, event.eventTime - started, strips.toList(), ::remove) }
                            EdgeSwipeDecision.DROP -> cancelPreview()
                        }
                    } else cancelPreview()
                    changed()
                }
                MotionEvent.ACTION_CANCEL -> { tracking = false; cancelled = true; cancelPreview(); changed() }
            }
            true
        }
    }

    fun event(event: AccessibilityEvent?) = replay.event(event)

    private fun remove() {
        cancelPreview()
        tracking = false
        val owned = strips.toList()
        strips.clear()
        owned.forEach { (view, _) ->
            try { wm.removeViewImmediate(view) }
            catch (error: RuntimeException) { android.util.Log.w("EdgeGestures", "Could not remove edge", error) }
        }
        // Retain ownership if a platform removal failed; never attach a second generation over it.
        strips += owned.filter { it.first.isAttachedToWindow }
    }

    fun interrupt() { replay.cancel("interrupt"); remove() }
    override fun close() { closed = true; interrupt() }
}
