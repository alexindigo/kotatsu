package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class EdgeSwipeTest {
    @Test fun onlyDownInsideTheSharedStripSelectsAnEdge() {
        assertEquals(ScreenEdge.LEFT, EdgeSwipe.startingEdge(0f, 480, 16f))
        assertEquals(ScreenEdge.LEFT, EdgeSwipe.startingEdge(15.99f, 480, 16f))
        assertNull(EdgeSwipe.startingEdge(16f, 480, 16f))
        assertNull(EdgeSwipe.startingEdge(463.99f, 480, 16f))
        assertEquals(ScreenEdge.RIGHT, EdgeSwipe.startingEdge(464f, 480, 16f))
        assertEquals(ScreenEdge.RIGHT, EdgeSwipe.startingEdge(479.99f, 480, 16f))
        assertNull(EdgeSwipe.startingEdge(480f, 480, 16f))
        assertNull(EdgeSwipe.startingEdge(-1f, 480, 16f))
        assertNull(EdgeSwipe.startingEdge(Float.NaN, 480, 16f))
    }
    @Test fun finalDisplacementDecisionTableIsSymmetricInclusiveAndIndependentOfSpeed() {
        ScreenEdge.entries.forEach { edge ->
            val inward = if (edge == ScreenEdge.LEFT) 1 else -1
            fun decide(dx: Float, dy: Float) = EdgeSwipe.decide(edge, dx * inward, dy, 480, 8f)
            assertEquals(EdgeSwipeDecision.DROP, decide(159f, 0f))
            assertEquals(EdgeSwipeDecision.ACTION, decide(160f, 0f))
            assertEquals(EdgeSwipeDecision.ACTION, decide(161f, 0f))
            assertEquals(EdgeSwipeDecision.DROP, decide(-161f, 0f))
            assertEquals(EdgeSwipeDecision.DROP, decide(-10f, 0f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(0f, 0f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(8f, 0f))
            assertEquals(EdgeSwipeDecision.DROP, decide(8f, 7f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(161f, 161f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(161f, -162f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(0f, -400f))
            assertEquals(EdgeSwipeDecision.REPLAY, decide(0f, 400f))
            assertEquals(EdgeSwipeDecision.ACTION, decide(161f, 160f))
        }
        assertEquals(EdgeSwipeDecision.DROP, EdgeSwipe.decide(ScreenEdge.LEFT, Float.NaN, 0f, 480, 8f))
        assertEquals(EdgeSwipeDecision.DROP, EdgeSwipe.decide(ScreenEdge.LEFT, 10f, 0f, 0, 8f))
        assertEquals(EdgeSwipeDecision.DROP, EdgeSwipe.decide(ScreenEdge.LEFT, 160f, 0f, 481, 8f))
    }

    @Test fun replayClampsEveryBoundaryWithoutChangingRawActionCoordinates() {
        val raw = listOf(-20f to 400f, 500f to 400f, 4f to -20f, 475f to 820f, 479f to 799f)
        val expected = listOf(ReplayPoint(0f, 400f), ReplayPoint(479f, 400f), ReplayPoint(4f, 0f),
            ReplayPoint(475f, 799f), ReplayPoint(479f, 799f))
        assertEquals(expected, raw.map { EdgeSwipe.clamp(it.first, it.second, 480, 800) })
        assertEquals(-20f, raw.first().first)
        assertEquals(ReplayPoint(0f, 0f), EdgeSwipe.clamp(100f, -100f, 1, 1))
        assertEquals(80L, EdgeSwipe.duration(0))
        assertEquals(250L, EdgeSwipe.duration(250))
        assertEquals(1500L, EdgeSwipe.duration(3000))
    }

    @Test fun handoffFailsClosedUntilAllCurrentWindowsAreGoneAndDetached() {
        val ids = setOf(12, 13)
        val titles = setOf("left", "right")
        val gone = listOf(1 to "keyguard")
        assertTrue(ReplayBarrier.ready(ids, titles, true, true, gone))
        assertFalse(ReplayBarrier.ready(ids, titles, false, true, gone))
        assertFalse(ReplayBarrier.ready(ids, titles, true, false, gone))
        assertFalse(ReplayBarrier.ready(ids, titles, true, true, emptyList()))
        assertFalse(ReplayBarrier.ready(emptySet(), titles, true, true, gone))
        assertFalse(ReplayBarrier.ready(ids, titles, true, true, gone + (12 to "left")))
        assertFalse(ReplayBarrier.ready(ids, titles, true, true, gone + (13 to "changed")))
        assertFalse(ReplayBarrier.ready(ids, titles, true, true, gone + (42 to "right")))
    }
}
