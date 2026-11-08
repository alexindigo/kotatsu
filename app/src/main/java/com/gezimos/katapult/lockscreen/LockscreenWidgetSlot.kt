package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.view.Gravity
import android.widget.FrameLayout

/** One fixed paper rectangle; only its current child is attached, at its own size and at the bottom. */
internal class LockscreenWidgetSlot(context: Context, handler: Handler, changed: () -> Unit,
    schedule: (Long, () -> Unit) -> AutoCloseable = { delay, action ->
        val task = Runnable(action)
        handler.postDelayed(task, delay)
        AutoCloseable { handler.removeCallbacks(task) }
    },
) : FrameLayout(context) {
    private val host = LockscreenWidgetHost.get(context)
    private val views = mutableMapOf<LockscreenWidget, AppWidgetHostView>()
    internal val rotation = WidgetSlotRotation(schedule, changed)
    private var attached: LockscreenWidget? = null
    val current get() = rotation.current

    init {
        setBackgroundColor(Color.WHITE)
    }

    fun show(list: List<LockscreenWidget>, mode: WidgetRotation, visible: Boolean, visibility: LockscreenVisibility) {
        rotation.update(list, mode, visible)
        host.listen(true)
        val shown = listOfNotNull(current).takeIf { visible }.orEmpty()
        LockscreenWidgetHost.pulseVisible(context, visibility, shown, list)
        views.keys.retainAll(list.toSet())
        val target = current.takeIf { visible }
        if (attached == target) return
        removeAllViews()
        attached = target
        if (target != null) {
            val info = AppWidgetManager.getInstance(context).getAppWidgetInfo(target.id) ?: return
            val child = views.getOrPut(target) { host.createWidgetView(context, target, info) }
            addView(child, LayoutParams(LayoutParams.MATCH_PARENT,
                (target.size.heightDp * resources.displayMetrics.density).toInt(), Gravity.BOTTOM))
        }
    }

    fun hide() { rotation.hide(); host.hideWidgets(context) }
    fun stop() { hide(); host.listen(false) }
}
