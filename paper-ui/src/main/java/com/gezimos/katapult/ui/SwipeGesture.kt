package com.gezimos.katapult.ui

import kotlin.math.abs
import kotlin.math.hypot

enum class SwipeAxis { HORIZONTAL, VERTICAL }
enum class SwipeDirection(val axis: SwipeAxis, val forward: Boolean) {
    LEFT(SwipeAxis.HORIZONTAL, true), RIGHT(SwipeAxis.HORIZONTAL, false),
    UP(SwipeAxis.VERTICAL, true), DOWN(SwipeAxis.VERTICAL, false),
}

/** The same strict dominance rule for pages, row actions and native edges. Ties have no owner. */
fun dominantSwipeAxis(dx: Float, dy: Float): SwipeAxis? = when {
    !dx.isFinite() || !dy.isFinite() -> null
    abs(dx) > abs(dy) -> SwipeAxis.HORIZONTAL
    abs(dy) > abs(dx) -> SwipeAxis.VERTICAL
    else -> null
}

/** One DOWN-to-UP stream, measured from DOWN; an axis can be claimed only once. */
class SwipeGesture(private val touchSlop: Float, private val minimumDistance: Float) {
    var axis: SwipeAxis? = null
        private set
    private var cancelled = false
    private var finished = false

    fun cancel() { cancelled = true }

    fun update(dx: Float, dy: Float, consumed: Boolean = false, pointers: Int = 1) {
        if (consumed || pointers != 1 || !dx.isFinite() || !dy.isFinite()) cancel()
        if (cancelled || finished) return
        if (axis == null && hypot(dx, dy) > touchSlop) axis = dominantSwipeAxis(dx, dy)
    }

    fun finish(dx: Float, dy: Float): SwipeDirection? {
        if (cancelled || finished) return null
        update(dx, dy)
        finished = true
        // A turn cannot transfer a stream already claimed by another axis.
        if (axis == null || dominantSwipeAxis(dx, dy) != axis) return null
        return when (axis) {
            SwipeAxis.HORIZONTAL -> if (abs(dx) > minimumDistance) {
                if (dx < 0) SwipeDirection.LEFT else SwipeDirection.RIGHT
            } else null
            SwipeAxis.VERTICAL -> if (abs(dy) > minimumDistance) {
                if (dy < 0) SwipeDirection.UP else SwipeDirection.DOWN
            } else null
            null -> null
        }
    }
}
