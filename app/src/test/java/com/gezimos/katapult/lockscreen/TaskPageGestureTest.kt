package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class TaskPageGestureTest {
    @Test fun sixStepPreviewUsesTheSameInclusiveLineOnBothEdges() {
        ScreenEdge.entries.forEach { edge ->
            val sign = if (edge == ScreenEdge.LEFT) 1 else -1
            listOf(0 to 0, 26 to 0, 27 to 1, 80 to 3, 159 to 5, 160 to 6, 161 to 6, 500 to 6).forEach { (dx, step) ->
                assertEquals("$edge $dx", step, EdgeSwipe.previewStep(edge, sign * dx.toFloat(), 0f, 480, 8f))
            }
            assertEquals(0, EdgeSwipe.previewStep(edge, -sign * 160f, 0f, 480, 8f))
            assertEquals(0, EdgeSwipe.previewStep(edge, sign * 160f, 161f, 480, 8f))
            assertEquals(0, EdgeSwipe.previewStep(edge, Float.NaN, 0f, 480, 8f))
            assertEquals(0, EdgeSwipe.previewStep(edge, sign * 160f, 0f, 0, 8f))
        }
    }

    @Test fun closeSwipesReturnTowardTheirOpeningEdgeUsingFinalDominance() {
        ScreenEdge.entries.forEach { edge ->
            val sign = if (edge == ScreenEdge.LEFT) -1 else 1
            assertFalse(EdgeSwipe.closesPage(edge, sign * 159f, 0f, 480, 8f))
            assertTrue(EdgeSwipe.closesPage(edge, sign * 160f, 0f, 480, 8f))
            assertTrue(EdgeSwipe.closesPage(edge, sign * 161f, 0f, 480, 8f))
            assertFalse(EdgeSwipe.closesPage(edge, -sign * 161f, 0f, 480, 8f))
            assertFalse(EdgeSwipe.closesPage(edge, sign * 161f, 162f, 480, 8f))
        }
    }

    @Test fun stableActionIdsAndTaskSelectionImportsAreStrict() {
        assertEquals(EdgeAction.TASK_LIST, EdgeActionStore.defaultAction(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, EdgeActionStore.defaultAction(ScreenEdge.RIGHT))
        assertEquals(EdgeAction.TASK_LIST, EdgeAction.fromId("task_list"))
        assertTrue(EdgeActionStore.validImport(EdgeActionStore.TASK_LIST, Long.MAX_VALUE.toString()))
        listOf(null, 1, "-1", "01", "+1", "1.0", "9223372036854775808").forEach {
            assertFalse(EdgeActionStore.validImport(EdgeActionStore.TASK_LIST, it))
        }
    }

    @Test fun pageClaimsOnlyCloseDirectionDragsBeyondSlopBeforeTheActionLine() {
        ScreenEdge.entries.forEach { edge ->
            val sign = if (edge == ScreenEdge.LEFT) -1f else 1f
            assertFalse(EdgeSwipe.capturesPageClose(edge, sign * 8, 0f, 8f))
            assertTrue(EdgeSwipe.capturesPageClose(edge, sign * 9, 0f, 8f))
            assertTrue(EdgeSwipe.capturesPageClose(edge, sign * 80, 0f, 8f))
            assertTrue(EdgeSwipe.capturesPageClose(edge, sign * 159, 0f, 8f))
            assertFalse(EdgeSwipe.capturesPageClose(edge, -sign * 80, 0f, 8f))
            assertFalse(EdgeSwipe.capturesPageClose(edge, sign * 80, 80f, 8f))
            assertFalse(EdgeSwipe.capturesPageClose(edge, sign * 80, 81f, 8f))
            assertFalse(EdgeSwipe.capturesPageClose(edge, Float.NaN, 0f, 8f))
            assertFalse(EdgeSwipe.capturesPageClose(edge, sign * 80, Float.POSITIVE_INFINITY, 8f))
        }
    }
}
