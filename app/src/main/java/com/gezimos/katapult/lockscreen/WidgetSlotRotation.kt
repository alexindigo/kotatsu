package com.gezimos.katapult.lockscreen

enum class WidgetRotation(val intervalMs: Long?) {
    WAKE(null), SECONDS_30(30_000), MINUTE_1(60_000), MINUTES_5(300_000), MINUTES_15(900_000);

    companion object {
        fun fromId(id: String?) = entries.firstOrNull { it.name == id } ?: WAKE
    }
}

internal enum class WidgetSlotEvent { WAKE, TIMER, NEXT, PREVIOUS }

/** Selection decisions have no clock, view or preference dependency. */
internal fun widgetSlotIndex(index: Int, count: Int, mode: WidgetRotation, event: WidgetSlotEvent): Int {
    if (count <= 0) return -1
    if (index !in 0 until count) return 0
    return when (event) {
        WidgetSlotEvent.WAKE -> if (mode == WidgetRotation.WAKE) (index + 1) % count else 0
        WidgetSlotEvent.TIMER -> if (mode.intervalMs != null) (index + 1) % count else index
        WidgetSlotEvent.NEXT -> (index + 1) % count
        WidgetSlotEvent.PREVIOUS -> (index + count - 1) % count
    }
}

/** Sole owner of current selection and the cancellable awake timer. All calls use the UI thread. */
internal class WidgetSlotRotation(
    private val schedule: (Long, () -> Unit) -> AutoCloseable,
    private val changed: () -> Unit,
) : AutoCloseable {
    private var widgets = emptyList<LockscreenWidget>()
    private var mode = WidgetRotation.WAKE
    private var index = -1
    private var shown = false
    private var timer: AutoCloseable? = null
    private var generation = 0L
    val current get() = widgets.getOrNull(index)

    fun update(list: List<LockscreenWidget>, rotation: WidgetRotation, visible: Boolean) {
        val old = current
        if (list != widgets || rotation != mode) {
            cancelTimer()
            index = if (rotation != mode) 0 else list.indexOfFirst { it.id == old?.id }.coerceAtLeast(0)
            widgets = list
            mode = rotation
        }
        shown = visible
        if (!shown || list.size < 2 || mode.intervalMs == null) cancelTimer() else arm()
    }

    fun wake() {
        cancelTimer()
        index = widgetSlotIndex(index, widgets.size, mode, WidgetSlotEvent.WAKE)
        // Visibility is re-observed after the screen-on broadcast, before arming.
        shown = false
    }

    fun swipe(delta: Int) {
        if (!shown || widgets.size < 2) return
        index = widgetSlotIndex(index, widgets.size, mode,
            if (delta < 0) WidgetSlotEvent.PREVIOUS else WidgetSlotEvent.NEXT)
        cancelTimer(); arm(); changed()
    }

    fun hide() { shown = false; cancelTimer() }

    private fun arm() {
        val delay = mode.intervalMs ?: return
        if (timer != null || !shown || widgets.size < 2) return
        val token = generation
        timer = schedule(delay) {
            if (token == generation && shown) {
                timer = null
                index = widgetSlotIndex(index, widgets.size, mode, WidgetSlotEvent.TIMER)
                arm(); changed()
            }
        }
    }

    private fun cancelTimer() { generation++; timer?.close(); timer = null }
    override fun close() = hide()
}

/** Zero means replay after lift; leftward is next, rightward previous. No edge-distance threshold. */
internal fun widgetSlotSwipe(dx: Float, dy: Float, slop: Float): Int =
    if (kotlin.math.abs(dx) > slop && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
        if (dx < 0) 1 else -1
    } else 0
