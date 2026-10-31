package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class HostedWidgetVisibilityTest {
    private val first = LockscreenWidget(3, LockscreenWidgetSize.M)
    private val second = LockscreenWidget(4, LockscreenWidgetSize.M)

    @Test fun onlyAnObservedVisibleEpochMayAnnounceShownWidgets() {
        val owner = HostedWidgetVisibility()
        val epoch = LockscreenVisibility().apply { beginEpoch() }
        assertNull(owner.hint(first.id))
        assertEquals(emptyList<LockscreenWidget>(), owner.reconcile(epoch, listOf(first)))
        assertNull(owner.hint(first.id))
        epoch.recheck(true) { false }
        assertEquals(listOf(first), owner.reconcile(epoch, listOf(first)))
        assertEquals(true, owner.hint(first.id))
        assertEquals(emptyList<LockscreenWidget>(), owner.reconcile(epoch, listOf(first)))
        epoch.recheck(true) { true }
        assertEquals(listOf(first), owner.reconcile(epoch, listOf(first)))
        assertEquals(false, owner.hint(first.id))
    }

    @Test fun visibleRebuildResizeAndAdditionCannotInventAHiddenTransition() {
        val owner = HostedWidgetVisibility()
        val epoch = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
        owner.reconcile(epoch, listOf(first))
        assertEquals(emptyList<LockscreenWidget>(), owner.reconcile(epoch, listOf(first)))
        val resized = first.copy(size = LockscreenWidgetSize.L)
        assertEquals(listOf(resized, second), owner.reconcile(epoch, listOf(resized, second)))
        assertEquals(true, owner.hint(first.id))
        assertEquals(true, owner.hint(second.id))
        assertEquals(listOf(resized), owner.reconcile(epoch, listOf(second)))
        assertEquals(false, owner.hint(first.id))
        assertEquals(true, owner.hint(second.id))
    }

    @Test fun hideAndNewEpochPreserveFalseUntilAnAuthorizedReveal() {
        val owner = HostedWidgetVisibility()
        val epoch = LockscreenVisibility().apply { beginEpoch(); recheck(true) { false } }
        owner.reconcile(epoch, listOf(first))
        assertEquals(listOf(first), owner.hide())
        assertEquals(false, owner.hint(first.id))
        assertEquals(emptyList<LockscreenWidget>(), owner.hide())
        epoch.beginEpoch()
        assertEquals(emptyList<LockscreenWidget>(), owner.reconcile(epoch, listOf(first)))
        assertEquals(false, owner.hint(first.id))
        epoch.recheck(true) { false }
        assertEquals(listOf(first), owner.reconcile(epoch, listOf(first)))
        assertEquals(true, owner.hint(first.id))
        epoch.beginEpoch(); epoch.recheck(true) { false }
        assertEquals(listOf(first), owner.reconcile(epoch, listOf(first)))
        owner.clear()
        assertNull(owner.hint(first.id))
    }
}
