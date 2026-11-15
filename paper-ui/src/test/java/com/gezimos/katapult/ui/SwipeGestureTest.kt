package com.gezimos.katapult.ui

import org.junit.Assert.*
import org.junit.Test

class SwipeGestureTest {
    @Test fun dominanceIsStrictSymmetricAndRejectsNonFiniteTravel() {
        assertNull(dominantSwipeAxis(20f, 20f))
        assertNull(dominantSwipeAxis(-20f, 20f))
        assertNull(dominantSwipeAxis(Float.NaN, 0f))
        assertNull(dominantSwipeAxis(0f, Float.POSITIVE_INFINITY))
        assertEquals(SwipeAxis.HORIZONTAL, dominantSwipeAxis(-20.01f, 20f))
        assertEquals(SwipeAxis.VERTICAL, dominantSwipeAxis(20f, -20.01f))
    }

    @Test fun driftDoesNotChooseTheMinorAxisAndBothSignsUseOneRule() {
        val cases = listOf(
            Triple(-280f, 40f, SwipeDirection.LEFT), Triple(280f, -40f, SwipeDirection.RIGHT),
            Triple(70f, -380f, SwipeDirection.UP), Triple(-70f, 380f, SwipeDirection.DOWN),
        )
        for ((dx, dy, expected) in cases) {
            val gesture = SwipeGesture(8f, 20f)
            gesture.update(dx / 10, dy / 10)
            assertEquals(expected, gesture.finish(dx, dy))
            assertNull("Release emits at most once", gesture.finish(dx, dy))
        }
    }

    @Test fun slopDistanceAndTiesDoNotEmit() {
        assertNull(SwipeGesture(8f, 2f).finish(8f, 0f))
        assertNull(SwipeGesture(8f, 20f).finish(20f, 0f))
        assertNull(SwipeGesture(8f, 20f).finish(100f, 100f))
        val gesture = SwipeGesture(8f, 20f)
        gesture.update(3f, 4f)
        assertNull(gesture.axis)
        gesture.update(30f, 2f)
        assertEquals(SwipeAxis.HORIZONTAL, gesture.axis)
    }

    @Test fun lockedAxisCannotBecomeAnOffAxisNavigationOnRelease() {
        val gesture = SwipeGesture(8f, 20f)
        gesture.update(-30f, 2f)
        gesture.update(-40f, 200f)
        assertEquals(SwipeAxis.HORIZONTAL, gesture.axis)
        assertNull(gesture.finish(-40f, 200f))
    }

    @Test fun cancellationMultiplePointersAndChildConsumptionAreTerminal() {
        for (cancel in listOf<(SwipeGesture) -> Unit>(
            { it.cancel() }, { it.update(40f, 0f, consumed = true) },
            { it.update(40f, 0f, pointers = 2) }, { it.update(Float.NaN, 0f) },
        )) {
            val gesture = SwipeGesture(8f, 20f)
            gesture.update(30f, 0f)
            cancel(gesture)
            assertNull(gesture.finish(200f, 0f))
        }
    }
}
