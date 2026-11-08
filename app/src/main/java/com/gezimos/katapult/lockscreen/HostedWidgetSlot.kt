package com.gezimos.katapult.lockscreen

import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Bundle
import android.view.ContextThemeWrapper
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.AbsListView
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Native child ownership and generic collection driving. Does not know its page/window/consumer. */
internal class HostedWidgetSlot(context: Context, private val host: LockscreenWidgetHost, private val id: Int,
                                private val dispatch: () -> Unit) : FrameLayout(context), AutoCloseable {
    private sealed interface ScrollIntent {
        data class Step(val direction: Int) : ScrollIntent
        data class Position(val value: Int) : ScrollIntent
    }
    private val pending = ArrayDeque<ScrollIntent>()
    var rail by mutableStateOf<ListRail?>(null)
        private set
    private val themed = object : ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault_Light) {
        override fun startIntentSender(sender: IntentSender, fillIn: Intent?, mask: Int, values: Int, extra: Int, options: Bundle?) {
            dispatch()
            // Observe only. RemoteViews owns the original sender, fill-in, flags and options.
            super.startIntentSender(sender, fillIn, mask, values, extra, options)
        }
    }
    private val manager = android.appwidget.AppWidgetManager.getInstance(context)
    private val info = requireNotNull(manager.getAppWidgetInfo(id))
    private val child = host.createView(themed, id, info)
    private val observer = ViewTreeObserver.OnPreDrawListener { applyPending(); true }
    private val afterLayout = Runnable { applyPending() }
    private var closed = false

    init {
        // Chrome defines one clipped content area, including when the rail is absent.
        val (top, bottom) = ScrollRailGeometry.contentInsets(androidx.compose.ui.unit.Density(resources.displayMetrics.density))
        setPadding(0, top, 0, bottom)
        clipChildren = true
        clipToPadding = true
        addView(child, LayoutParams(-1, -1))
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)
        // AndroidView can finish its native layout after the window's pre-draw callbacks.
        // Observe this actual layout too, once View.layout has cleared our own layout flags.
        // This is event-driven/coalesced, not a timer or a request for another frame.
        if (!closed) { removeCallbacks(afterLayout); post(afterLayout) }
    }
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!closed) viewTreeObserver.addOnPreDrawListener(observer)
    }
    override fun onDetachedFromWindow() {
        viewTreeObserver.removeOnPreDrawListener(observer)
        removeCallbacks(afterLayout)
        super.onDetachedFromWindow()
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val density = resources.displayMetrics.density
        host.sideSize(context, id, (w / density).toInt(), ((h - paddingTop - paddingBottom) / density).toInt())
    }

    private fun findLists(view: View): List<AbsListView> = buildList {
        if (view is AbsListView) add(view)
        if (view is ViewGroup) repeat(view.childCount) { addAll(findLists(view.getChildAt(it))) }
    }
    private fun inspect(): Pair<AbsListView, ListRail>? {
        val collections = findLists(child)
        collections.forEach { it.isVerticalScrollBarEnabled = false; it.isHorizontalScrollBarEnabled = false }
        // Populated children can still belong to the previous layout. An adapter update,
        // provider selection or slot resize may replace their positions on the next pass.
        // Keep the existing rail drawing during that transition, and observe again at pre-draw.
        if (isLayoutRequested || child.isLayoutRequested || collections.any { candidate ->
            !candidate.isAttachedToWindow || !candidate.isLaidOut || candidate.isLayoutRequested ||
                candidate.count > 0 && (candidate.childCount == 0 || (0 until candidate.childCount).any {
                    candidate.getChildAt(it).isLayoutRequested || candidate.getChildAt(it).height <= 0
                })
        }) return null
        val selected = collections.firstOrNull { it.canScrollList(-1) || it.canScrollList(1) }
        val first = selected?.getChildAt(0)
        val last = selected?.getChildAt((selected.childCount - 1).coerceAtLeast(0))
        val next = if (selected != null && first != null && last != null)
            listRail(selected.count, selected.firstVisiblePosition, selected.lastVisiblePosition,
                first.top - selected.paddingTop, first.height, last.bottom - selected.paddingTop, last.height,
                selected.height - selected.paddingTop - selected.paddingBottom) else null
        val geometryChanged = (rail == null) != (next == null)
        rail = next
        if (geometryChanged) { requestLayout(); return null }
        if (selected == null || next == null) { pending.clear(); return null }
        return selected to next
    }
    fun step(direction: Int) {
        pending.addLast(ScrollIntent.Step(direction))
        applyPending()
    }
    fun moveTo(position: Int) {
        if (pending.lastOrNull() is ScrollIntent.Position) pending.removeLast()
        pending.addLast(ScrollIntent.Position(position))
        applyPending()
    }
    private fun applyPending() {
        val (target, metrics) = inspect() ?: return
        if (pending.isEmpty()) return
        when (val intent = pending.removeFirst()) {
            is ScrollIntent.Step -> target.scrollListBy(intent.direction * (target.height - target.paddingTop - target.paddingBottom) / 2)
            is ScrollIntent.Position -> {
                val value = intent.value.coerceIn(0, metrics.maximum)
                val row = (value / 1000).coerceIn(0, target.count - 1)
                val measured = target.getChildAt((row - target.firstVisiblePosition).coerceIn(0, target.childCount - 1)).height
                target.setSelectionFromTop(row, -(value % 1000) * measured / 1000)
            }
        }
        inspect()
        if (pending.isNotEmpty()) requestLayout()
    }
    override fun close() {
        closed = true
        removeCallbacks(afterLayout)
        viewTreeObserver.removeOnPreDrawListener(observer)
        pending.clear(); rail = null; removeAllViews()
    }
}
