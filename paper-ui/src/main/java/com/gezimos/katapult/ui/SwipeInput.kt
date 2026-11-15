package com.gezimos.katapult.ui

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerInputScope

/** The screen/page owns only its axis; children retain taps and already-consumed streams. */
suspend fun PointerInputScope.detectSwipeGestures(
    axis: SwipeAxis,
    minimumDistance: Float,
    onSwipe: (SwipeDirection) -> Unit,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val gesture = SwipeGesture(viewConfiguration.touchSlop, minimumDistance)
        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.singleOrNull { it.id == down.id }
            if (event.changes.size != 1 || change == null) { gesture.cancel(); break }
            val travel = change.position - down.position
            gesture.update(travel.x, travel.y, change.isConsumed)
            val winner = gesture.axis
            if (change.isConsumed || winner != null && winner != axis) break
            if (winner == axis) change.consume()
            if (!change.pressed) {
                gesture.finish(travel.x, travel.y)?.let(onSwipe)
                break
            }
        }
    }
}
