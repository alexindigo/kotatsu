package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class LockscreenVisibilityTest {
    @Test fun epochRequiresItsOwnBareObservation() {
        val state = LockscreenVisibility()
        state.beginEpoch()
        assertFalse(state.visible)
        assertTrue(state.recheck(true) { false })
        assertEquals(state.epoch, state.observedBareAt)
        state.beginEpoch()
        assertFalse(state.visible)
        assertNull(state.observedBareAt)
    }

    @Test fun idleRecheckDiscoversCoverWithoutAnEvent() {
        val state = LockscreenVisibility()
        state.beginEpoch()
        var inspections = 0
        assertTrue(state.recheck(true) { inspections++; false })
        assertFalse(state.recheck(true) { inspections++; true })
        assertEquals(2, inspections)
    }

    @Test fun unknownAndFailedInspectionHidePreviouslyVisibleContent() {
        val state = LockscreenVisibility()
        assertTrue(state.recheck(true) { false })
        assertFalse(state.recheck(true) { null })
        assertTrue(state.recheck(true) { false })
        assertFalse(state.recheck(true) { error("inspection failed") })
    }

    @Test fun screenOffOnAndRelockInvalidateThePreviousObservation() {
        val state = LockscreenVisibility()
        state.beginEpoch()
        state.keyguard(true)
        assertTrue(state.recheck(true) { false })
        assertFalse(state.recheck(false) { error("must not inspect while off") })
        state.beginEpoch() // SCREEN_ON
        assertFalse(state.visible)
        assertTrue(state.recheck(true) { false })
        state.keyguard(false)
        val previous = state.epoch
        state.keyguard(true)
        assertEquals(previous + 1, state.epoch)
        assertFalse(state.visible)
    }

    @Test fun everyShownWidgetGetsExactlyOnePulseInEachVisibleEpoch() {
        val state = LockscreenVisibility()
        state.beginEpoch()
        assertEquals(emptyList<Int>(), state.widgetsToPulse(listOf(3, 4)))
        state.recheck(true) { false }
        assertEquals(listOf(3, 4), state.widgetsToPulse(listOf(3, 4)))
        assertEquals(emptyList<Int>(), state.widgetsToPulse(listOf(3, 4)))
        state.recheck(true) { true }
        assertEquals(emptyList<Int>(), state.widgetsToPulse(listOf(5)))
        state.recheck(true) { false }
        assertEquals(listOf(5), state.widgetsToPulse(listOf(3, 4, 5)))
        state.beginEpoch()
        assertEquals(emptyList<Int>(), state.widgetsToPulse(listOf(3, 4, 5)))
        state.recheck(true) { false }
        assertEquals(listOf(3, 4, 5), state.widgetsToPulse(listOf(3, 4, 5)))
    }
}
