package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.view.Gravity
import android.widget.FrameLayout

/** One fixed paper rectangle; only its current child is attached, at its own size and at the bottom. */
internal class LockscreenWidgetSlot(context: Context, handler: Handler, private val changed: () -> Unit,
    schedule: (Long, () -> Unit) -> AutoCloseable = { delay, action ->
        val task = Runnable(action)
        handler.postDelayed(task, delay)
        AutoCloseable { handler.removeCallbacks(task) }
    },
) : FrameLayout(context) {
    private val host = LockscreenWidgetHost.get(context)
    private val views = mutableMapOf<LockscreenWidget, UnpaddedHostView>()
    private var content = emptyMap<LockscreenWidget, Boolean>()
    private var stopped = false
    private fun observeContent(changed: () -> Unit) {
        if (stopped) return
        val next = views.mapValues { widgetHasContent(it.value) }
        if (content != next) { content = next; changed() }
    }
    internal val rotation = WidgetSlotRotation(schedule, changed)
    private var attached: LockscreenWidget? = null
    val current get() = rotation.current

    init {
        setBackgroundColor(com.gezimos.katapult.ui.Appearance.palette(context,
            com.gezimos.katapult.ui.Appearance.Surface.LOCK_SCREEN).paperArgb)
    }

    fun show(list: List<LockscreenWidget>, mode: WidgetRotation, visible: Boolean, visibility: LockscreenVisibility) {
        stopped = false
        host.listen(true)
        views.filterKeys { it !in list }.values.forEach { it.contentChanged = null }
        views.keys.retainAll(list.toSet())
        list.forEach { widget ->
            if (widget !in views) {
                val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(widget.id) ?: return@forEach
                views[widget] = host.createWidgetView(context, widget, info) as UnpaddedHostView
            }
            views[widget]?.contentChanged = { observeContent(changed) }
        }
        content = views.mapValues { widgetHasContent(it.value) }
        rotation.update(list.filter { content[it] == true }, mode, visible)
        val shown = listOfNotNull(current).takeIf { visible }.orEmpty()
        LockscreenWidgetHost.pulseVisible(context, visibility, shown, list)
        val target = current.takeIf { visible }
        if (attached == target) return
        removeAllViews()
        attached = target
        if (target != null) {
            val child = views[target] ?: return
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT,
                (target.size.heightDp * resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
        }
    }

    fun hide() { rotation.hide(); host.hideWidgets(context) }
    fun stop() { stopped = true; views.values.forEach { it.contentChanged = null }; hide(); host.listen(false) }
}
