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

    @Test fun onlyANewerOpeningCanRestoreAClosedAppWithoutChangingItsDisplayTime() {
        val paused = RecentApp("a", "A", 200, lastOpened = 50)
        assertTrue(recentApps(listOf(paused), "own", mapOf("a" to 100)).isEmpty())
        val reopened = paused.copy(lastOpened = 101)
        assertEquals(listOf(reopened), recentApps(listOf(reopened), "own", mapOf("a" to 100)))
        assertEquals(200L, reopened.lastUsed)
        assertEquals(listOf(paused.copy(lastOpened = 0)), recentApps(listOf(paused.copy(lastOpened = 0)), "own", emptyMap()))
    }

    @Test fun closeSupersedesThePublishedUsageEvenWhenItLeadsWallTime() {
        for (foreground in listOf(false, true)) {
            val delegate = FakeSource()
            val source = object : RecentAppsSource by delegate {
                override fun history() = listOfNotNull(RecentApp("b", "B", 200),
                    if (foreground) null else RecentApp("c", "C", 300))
            }
            val model = RecentApps(source, "own") { 100 }
            model.show(); model.close("b"); model.show()
            assertEquals(200L, delegate.closedAt)
            assertFalse(model.apps.any { it.packageName == "b" })
        }
    }

    @Test fun deferredCloseKeepsTheOfferedBoundaryAfterAnEmptyReopenAndClockCorrection() {
        for ((lastUsed, tappedAt) in listOf(200L to 100L, 100L to 200L)) {
            val delegate = FakeSource()
            val offered = RecentApp("b", "B", lastUsed)
            var history = listOf(offered)
            var at = tappedAt
            var transition: (() -> Unit)? = null
            val source = object : RecentAppsSource by delegate {
                override fun history() = history
            }
            val model = RecentApps(source, "own", closeForeground = { transition = it }) { at }
            model.show(); model.close("b")
            assertFalse(model.visible)
            assertTrue(delegate.killed.isEmpty())
            history = emptyList()
            model.show()
            assertTrue(model.apps.isEmpty())
            at = 90
            checkNotNull(transition).invoke()
            assertEquals(200L, delegate.closedAt)
            history = listOf(offered.copy(lastUsed = 200, lastOpened = 150))
            model.refresh()
            assertTrue("An opening before the captured close boundary must not restore the row", model.apps.isEmpty())
            history = listOf(offered.copy(lastUsed = 201, lastOpened = 201))
            model.refresh()
            assertEquals(listOf("b"), model.apps.map { it.packageName })
        }
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

    @Test fun foregroundCloseCompletionInvalidatesAReopenedSnapshotBeforeOrAfterDelivery() {
        for (deliverBeforeClose in listOf(false, true)) {
            val source = FakeSource()
            val loads = mutableListOf<() -> Unit>()
            val deliveries = mutableListOf<() -> Unit>()
            var transition: (() -> Unit)? = null
            val model = RecentApps(source, "own", load = { loads += it }, deliver = { deliveries += it },
                closeForeground = { transition = it }) { 100 }
            model.show()
            loads.removeAt(0)(); deliveries.removeAt(0)()
            model.close("b")
            model.show()
            loads.removeAt(0)()
            if (deliverBeforeClose) deliveries.removeAt(0)()
            checkNotNull(transition).invoke()
            if (!deliverBeforeClose) deliveries.removeAt(0)()
            assertFalse(model.apps.any { it.packageName == "b" })
            loads.removeAt(0)(); deliveries.removeAt(0)()
            assertEquals(listOf("a"), model.apps.map { it.packageName })
            assertTrue(model.visible)
        }
    }

    private class FakeSource : RecentAppsSource {
        var closedAt = 0L
        private val closed = mutableMapOf<String, Long>()
        val launched = mutableListOf<String>()
        val killed = mutableListOf<List<String>>()
        override fun available() = true
        override fun history() = listOf(RecentApp("a", "A", 10), RecentApp("b", "B", 20))
        override fun closed() = closed.toMap()
        override fun close(packages: List<String>, at: Long) {
            killed += packages; closedAt = at
            packages.forEach { closed[it] = at }
        }
        override fun launch(packageName: String) { launched += packageName }
        override fun openAccess() = true
    }
}
