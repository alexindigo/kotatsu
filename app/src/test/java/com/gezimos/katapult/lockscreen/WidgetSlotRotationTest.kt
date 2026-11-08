package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class WidgetSlotRotationTest {
    private val list = (1..3).map { LockscreenWidget(it, LockscreenWidgetSize.M) }
    private class Clock {
        var action: (() -> Unit)? = null
        var delay = 0L
        fun schedule(ms: Long, task: () -> Unit): AutoCloseable {
            check(action == null)
            action = task; delay = ms
            return AutoCloseable { if (action === task) action = null }
        }
        fun tick() { val next = checkNotNull(action); action = null; next() }
    }

    @Test fun decisionsWrapAndWakeResetsOnlyTimedModes() {
        for (mode in WidgetRotation.entries) {
            assertEquals(-1, widgetSlotIndex(0, 0, mode, WidgetSlotEvent.WAKE))
            assertEquals(0, widgetSlotIndex(-1, 3, mode, WidgetSlotEvent.WAKE))
            assertEquals(0, widgetSlotIndex(2, 3, mode, WidgetSlotEvent.NEXT))
            assertEquals(2, widgetSlotIndex(0, 3, mode, WidgetSlotEvent.PREVIOUS))
            assertEquals(if (mode == WidgetRotation.WAKE) 2 else 0, widgetSlotIndex(1, 3, mode, WidgetSlotEvent.WAKE))
            assertEquals(if (mode == WidgetRotation.WAKE) 1 else 2, widgetSlotIndex(1, 3, mode, WidgetSlotEvent.TIMER))
            assertEquals(0, widgetSlotIndex(0, 1, mode, WidgetSlotEvent.PREVIOUS))
        }
    }

    @Test fun wakeAndReorderHaveOneSelectionOwner() {
        val clock = Clock()
        WidgetSlotRotation(clock::schedule, {}).use { slot ->
            slot.update(list, WidgetRotation.WAKE, true)
            assertEquals(1, slot.current!!.id)
            repeat(3) { slot.hide(); slot.wake(); slot.update(list, WidgetRotation.WAKE, true) }
            assertEquals(1, slot.current!!.id)
            slot.swipe(1)
            slot.update(list.reversed(), WidgetRotation.WAKE, true)
            assertEquals(2, slot.current!!.id)
            slot.update(list.filterNot { it.id == 2 }, WidgetRotation.WAKE, true)
            assertEquals(1, slot.current!!.id)
            assertNull(clock.action)
        }
    }

    @Test fun timersRunOnlyWhileShownAndCannotReturnAfterCancellation() {
        val clock = Clock()
        var changes = 0
        WidgetSlotRotation(clock::schedule, { changes++ }).use { slot ->
            for (mode in WidgetRotation.entries.filter { it.intervalMs != null }) {
                slot.update(list, mode, true)
                assertEquals(mode.intervalMs, clock.delay)
                val original = clock.action
                repeat(5) { slot.update(list, mode, true) }
                assertSame(original, clock.action)
                clock.tick(); assertEquals(2, slot.current!!.id)
                clock.tick(); assertEquals(3, slot.current!!.id)
                clock.tick(); assertEquals(1, slot.current!!.id)
                val cancelled = clock.action!!
                slot.hide(); assertNull(clock.action)
                cancelled(); assertEquals(1, slot.current!!.id)
                slot.wake(); slot.update(list, mode, true)
                assertEquals(1, slot.current!!.id)
                slot.swipe(-1); assertEquals(3, slot.current!!.id)
                slot.update(list, mode, false); assertNull(clock.action)
            }
            assertEquals(16, changes)
            slot.update(list.take(1), WidgetRotation.SECONDS_30, true)
            assertNull(clock.action)
            slot.update(emptyList(), WidgetRotation.SECONDS_30, true)
            assertNull(slot.current)
        }
        assertNull(clock.action)
    }

    @Test fun slotSwipesNeedOnlyHorizontalDominanceAndTouchSlop() {
        assertEquals(1, widgetSlotSwipe(-9f, 2f, 8f))
        assertEquals(-1, widgetSlotSwipe(9f, 2f, 8f))
        for ((x, y) in listOf(8f to 0f, 0f to -200f, 9f to -10f, 2f to 2f, 90f to 90f))
            assertEquals(0, widgetSlotSwipe(x, y, 8f))
    }
}
