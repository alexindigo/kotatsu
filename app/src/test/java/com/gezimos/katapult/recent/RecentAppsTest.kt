package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test

class RecentAppsTest {
    @Test fun historyIsUniqueLaunchableInputOldestFirstAndClosedUntilReused() {
        val history = listOf(RecentApp("own", "Home", 200), RecentApp("b", "B", 30),
            RecentApp("a", "A", 10), RecentApp("a", "A", 20), RecentApp("never", "Never", 0))
        assertEquals(listOf("a", "b"), recentApps(history, "own", emptyMap()).map { it.packageName })
        assertEquals(listOf("b"), recentApps(history, "own", mapOf("a" to 20)).map { it.packageName })
        assertEquals(listOf("a", "b"), recentApps(history, "own", mapOf("a" to 19)).map { it.packageName })
    }

    @Test fun relativeLabelsRespectCalendarYesterdayAndElapsedBoundaries() {
        val today = 10 * 86_400_000L
        val now = today + 12 * 3_600_000
        fun age(last: Long) = relativeUse(last, now, today, today - 86_400_000)
        assertEquals(RelativeUse(RecentAge.NOW), age(now + 100))
        assertEquals(RelativeUse(RecentAge.NOW), age(now - 59_999))
        assertEquals(RelativeUse(RecentAge.MINUTES, 5), age(now - 300_000))
        assertEquals(RelativeUse(RecentAge.HOURS, 1), age(now - 3_600_000))
        assertEquals(RelativeUse(RecentAge.YESTERDAY), age(today - 1))
        assertEquals(RelativeUse(RecentAge.DAYS, 3), age(now - 3 * 86_400_000))
    }

    @Test fun dismissalOnlyRemovesTheOverlayAndRowsCloseIndependently() {
        val source = FakeSource()
        var removed = 0
        val model = RecentApps(source, "own", { removed++ }) { 100 }
        model.show()
        assertTrue(model.visible)
        model.back()
        assertEquals(1, removed)
        assertTrue(source.launched.isEmpty())
        model.toggle(); model.toggle()
        assertFalse(model.visible)
        assertTrue(source.launched.isEmpty())
        model.show()
        model.close("a"); model.close("b")
        assertEquals(listOf(listOf("a"), listOf("b")), source.killed)
        assertTrue(model.apps.isEmpty())
        model.back()
        assertTrue(source.launched.isEmpty())
        assertEquals(3, removed)
        model.dismiss()
        assertEquals(3, removed)
    }

    @Test fun switchingAndUsageHandoffDismissWithoutRestoringAnything() {
        val source = FakeSource()
        val model = RecentApps(source, "own") { 100 }
        model.show()
        assertTrue(model.visible)
        model.switchTo("a")
        assertFalse(model.visible)
        assertEquals(listOf("a"), source.launched)
        model.show(); model.openAccess()
        assertFalse(model.visible)
        assertEquals(listOf("a"), source.launched)
    }

    @Test fun asynchronousLoadsCannotPublishAfterDismissOrOvertakeANewerRequest() {
        val loads = mutableListOf<() -> Unit>()
        val deliveries = mutableListOf<() -> Unit>()
        var presented = 0
        val model = RecentApps(FakeSource(), "own", load = { loads += it }, deliver = { deliveries += it }, loaded = { presented++ }) { 100 }
        model.show()
        assertTrue(model.visible)
        assertTrue(model.apps.isEmpty())
        loads.removeAt(0)()
        model.dismiss()
        deliveries.removeAt(0)()
        assertTrue(model.apps.isEmpty())
        assertEquals(0, presented)
        model.show(); model.refresh()
        loads.removeAt(0)(); loads.removeAt(0)()
        deliveries.removeAt(0)()
        assertEquals(0, presented)
        deliveries.removeAt(0)()
        assertEquals(listOf("a", "b"), model.apps.map { it.packageName })
        assertEquals(1, presented)
        model.refresh(); loads.removeAt(0)()
        model.close("a")
        deliveries.removeAt(0)()
        assertEquals(listOf("b"), model.apps.map { it.packageName })
    }

    @Test fun bottomCloseDismissesBeforeHomeAndRecordsOnlyAfterTransition() {
        val source = FakeSource()
        var at = 100L
        var transition: (() -> Unit)? = null
        var removed = false
        val model = RecentApps(source, "own", dismissed = { removed = true }, closeForeground = {
            assertTrue(removed)
            assertTrue(source.killed.isEmpty())
            transition = it
        }) { at }
        model.show()
        model.close("b")
        assertFalse(model.visible)
        assertTrue(source.killed.isEmpty())
        at = 200
        checkNotNull(transition).invoke()
        assertEquals(listOf(listOf("b")), source.killed)
        assertEquals(200L, source.closedAt)
        assertEquals(listOf("a"), model.apps.map { it.packageName })
    }

    private class FakeSource : RecentAppsSource {
        var closedAt = 0L
        val launched = mutableListOf<String>()
        val killed = mutableListOf<List<String>>()
        override fun available() = true
        override fun history() = listOf(RecentApp("a", "A", 10), RecentApp("b", "B", 20))
        override fun closed() = emptyMap<String, Long>()
        override fun close(packages: List<String>, at: Long) { killed += packages; closedAt = at }
        override fun launch(packageName: String) { launched += packageName }
        override fun openAccess() = true
    }
}
