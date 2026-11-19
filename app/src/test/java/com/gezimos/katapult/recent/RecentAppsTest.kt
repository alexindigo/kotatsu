package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test

class RecentAppsTest {
    private val resumed = UsageKind.RESUMED
    private val paused = UsageKind.PAUSED
    private val stopped = UsageKind.STOPPED
    private fun mark(kind: UsageKind, at: Long, activity: String = "A") = UsageMark(kind, activity, at)
    private fun startup(at: Long) = UsageMark(UsageKind.STARTUP, null, at)
    private fun List<UsageMark>.shifted(by: Long) = map { it.copy(at = it.at + by) }
    private fun legacy(at: Long) = RecentClose("v0.7.0:$at", at, null, null)

    // Wall time 1_800_000_000_000 at elapsed 1_000_000; Android's usage clock has the same offset unless stated.
    private val offset = 1_800_000_000_000L - 1_000_000
    private val session = listOf(mark(resumed, offset + 990_000), mark(paused, offset + 995_000), mark(stopped, offset + 995_400))
    private fun closed(trail: List<UsageMark> = session, boot: Int = 1, closeOffset: Long? = offset, id: String = "close") =
        RecentClose(id, offset + 1_000_000, UsageClock(boot, 1_000_000, closeOffset), trail)
    private fun now(elapsed: Long, nowOffset: Long? = offset, boot: Int = 1) = UsageClock(boot, elapsed, nowOffset)

    @Test fun overlappingLoadsCannotRetireANewerCloseWithTheSameTimestamp() {
        val first = closed(id = "first")
        val records = mutableMapOf("a" to first)
        val loadA = records.getValue("a")
        val loadB = records.getValue("a")
        retireClose(records, "a", loadA)
        val second = first.copy(id = "second")
        records["a"] = second
        retireClose(records, "a", loadB)
        assertEquals("A stale load must not delete a newer close recorded at the same time", second, records["a"])
        val provedByB = RecentApp("a", "A", first.at, reopened = loadB.id)
        assertTrue("Proof belongs to the close it observed", recentApps(listOf(provedByB), "own", records).isEmpty())
        assertEquals(listOf(provedByB.copy(reopened = "second")),
            recentApps(listOf(provedByB.copy(reopened = "second")), "own", records))
    }

    @Test fun backwardClockRebaseNeitherRestoresAClosedAppNorHidesARealRelaunch() {
        val back = -36L * 3_600_000
        val rebased = session.shifted(back)
        val clock = now(1_010_000, offset + back)
        assertFalse("A clock correction alone is not reuse", closed().reopenedIn(rebased, clock, rebased.first().at))
        val relaunch = rebased + mark(resumed, offset + back + 1_005_000)
        assertTrue("A resume after the close must restore the app behind a future close time",
            closed().reopenedIn(relaunch, clock, relaunch.first().at))
    }

    @Test fun usageClockWithinAndroidsTwoSecondToleranceCannotDecideReuse() {
        // Android rebases only beyond 2 s, so its usage clock can lead or lag wall time unnoticed.
        for (lead in listOf(500L, -1_500L)) {
            val usage = offset + lead
            val trail = listOf(mark(resumed, usage + 998_500))
            val close = closed(trail)
            val closing = trail + mark(paused, usage + 1_000_100) + mark(stopped, usage + 1_000_400)
            assertFalse("The closing pause is not reuse ($lead)", close.reopenedIn(closing, now(1_002_000), trail.first().at))
            val reopened = closing + mark(resumed, usage + 1_000_050 + 400)
            assertTrue("A resume 450 ms after the close is reuse ($lead)", close.reopenedIn(reopened, now(1_002_000), trail.first().at))
        }
    }

    @Test fun pruningOlderDaysCannotHideTheFirstResumeAfterARecentClose() {
        val day = 86_400_000L
        val older = (5 downTo 1).map { mark(if (it % 2 == 0) paused else resumed, offset - it * day) }
        val close = closed(older + session)
        // Android deleted the daily files holding the older trail; the latest session remains.
        val retained = session + mark(resumed, offset + 1_001_000)
        assertTrue(close.reopenedIn(retained, now(1_002_000), session.first().at))
        assertFalse(close.reopenedIn(session, now(1_002_000), session.first().at))
        val rebased = retained.shifted(-7_200_000)
        assertTrue("Pruning and a clock rebase together", close.reopenedIn(rebased, now(1_002_000, offset - 7_200_000), rebased.first().at))
        assertFalse(close.reopenedIn(session.shifted(-7_200_000), now(1_002_000, offset - 7_200_000), offset - 7_200_000 + 990_000))
    }

    @Test fun aFullyPrunedTrailLeavesOnlyLaterResumes() {
        val day = 86_400_000L
        val close = closed()
        val later = listOf(mark(resumed, offset + 11 * day), mark(paused, offset + 11 * day + 5_000))
        assertTrue(close.reopenedIn(later, now(1_000_000 + 12 * day), later.first().at))
        assertFalse(close.reopenedIn(emptyList(), now(1_000_000 + 12 * day), offset + 11 * day))
        // Without both clock samples a lost trail still admits resumes clearly after the close.
        val unbounded = closed(closeOffset = null)
        assertTrue(unbounded.reopenedIn(later, now(1_000_000 + 12 * day), later.first().at))
        assertFalse(unbounded.reopenedIn(later, now(1_000_000 + 12 * day, null), later.first().at))
    }

    @Test fun theTrailIsFoundAtOneCommonShiftNotAtALaterSessionOfTheSameActivity() {
        val close = closed()
        val forward = 3_000L
        val stream = session.shifted(forward) + listOf(mark(resumed, offset + forward + 1_020_000),
            mark(paused, offset + forward + 1_022_000), mark(stopped, offset + forward + 1_022_300))
        assertTrue(close.reopenedIn(stream, now(1_030_000, offset + forward), stream.first().at))
        assertTrue("Unknown clock samples still use the exact gaps",
            close.reopenedIn(stream, now(1_030_000, null), stream.first().at))
        assertFalse(close.reopenedIn(session.shifted(forward), now(1_030_000, null), offset + forward))
    }

    @Test fun rebootKeepsTheTrailOrderAndALostTrailCountsOnlyLaterBoots() {
        val close = closed()
        val boot = startup(offset + 2_000_000)
        val after = mark(resumed, offset + 2_010_000)
        assertFalse(close.reopenedIn(session + boot, now(20_000, offset + 1_990_000, boot = 2), session.first().at))
        assertTrue(close.reopenedIn(session + boot + after, now(20_000, offset + 1_990_000, boot = 2), session.first().at))
        // Android can lose the latest unflushed events, or delete future-dated files after the clock went back.
        val older = listOf(mark(resumed, offset + 500_000), mark(paused, offset + 600_000))
        assertFalse(close.reopenedIn(older + boot, now(20_000, boot = 2), older.first().at))
        assertTrue(close.reopenedIn(older + boot + after, now(20_000, boot = 2), older.first().at))
        val second = startup(offset + 3_000_000)
        assertTrue("Two boots since the close", close.reopenedIn(older + boot + after + second, now(20_000, boot = 3), older.first().at))
        assertTrue("Startups older than retention", close.reopenedIn(listOf(after), now(20_000, boot = 3), after.at))
    }

    @Test fun anEmptyTrailOrAReleasedRecordUsesItsOwnEvidence() {
        val close = closed(emptyList())
        assertFalse(close.reopenedIn(emptyList(), now(1_002_000), null))
        assertTrue(close.reopenedIn(listOf(mark(resumed, offset + 1_000_500)), now(1_002_000), offset + 1_000_500))
        assertFalse(legacy(100).reopenedIn(listOf(mark(resumed, 100), mark(paused, 150)), now(1_002_000), 100))
        assertTrue(legacy(100).reopenedIn(listOf(mark(resumed, 101)), now(1_002_000), 101))
    }

    @Test fun historyIsUniqueLaunchableInputOldestFirstAndClosedUntilReused() {
        val history = listOf(RecentApp("own", "Home", 200), RecentApp("b", "B", 30),
            RecentApp("a", "A", 10), RecentApp("a", "A", 20), RecentApp("never", "Never", 0))
        assertEquals(listOf("a", "b"), recentApps(history, "own", emptyMap()).map { it.packageName })
        assertEquals(listOf("b"), recentApps(history, "own", mapOf("a" to legacy(20))).map { it.packageName })
        assertEquals(listOf("a", "b"), recentApps(history.map { if (it.packageName == "a") it.copy(reopened = "v0.7.0:20") else it },
            "own", mapOf("a" to legacy(20))).map { it.packageName })
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

    @Test fun onlyProofForTheCurrentCloseRestoresAnAppWithoutChangingItsDisplayTime() {
        val paused = RecentApp("a", "A", 200)
        assertTrue(recentApps(listOf(paused), "own", mapOf("a" to legacy(100))).isEmpty())
        val reopened = paused.copy(reopened = "v0.7.0:100")
        assertEquals(listOf(reopened), recentApps(listOf(reopened), "own", mapOf("a" to legacy(100))))
        assertEquals(200L, reopened.lastUsed)
        assertEquals(listOf(paused), recentApps(listOf(paused), "own", emptyMap()))
    }

    @Test fun closeSupersedesThePublishedUsageEvenWhenItLeadsWallTime() {
        for (foreground in listOf(false, true)) {
            val delegate = FakeSource()
            val source = object : RecentAppsSource by delegate {
                override fun history() = delegate.prove(listOfNotNull(RecentApp("b", "B", 200),
                    if (foreground) null else RecentApp("c", "C", 300)))
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
                override fun history() = delegate.prove(history)
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
            history = listOf(offered.copy(lastUsed = 200))
            delegate.opened["b"] = 150
            model.refresh()
            assertTrue("An opening before the captured close boundary must not restore the row", model.apps.isEmpty())
            history = listOf(offered.copy(lastUsed = 201))
            delegate.opened["b"] = 201
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
        private var closes = 0
        private val closed = mutableMapOf<String, RecentClose>()
        /** Opening times; a source proves reuse of the close it observed. */
        val opened = mutableMapOf<String, Long>()
        val launched = mutableListOf<String>()
        val killed = mutableListOf<List<String>>()
        override fun available() = true
        override fun history() = prove(listOf(RecentApp("a", "A", 10), RecentApp("b", "B", 20)))
        fun prove(history: List<RecentApp>) = history.map { app ->
            val close = closed[app.packageName]
            if (close != null && (opened[app.packageName] ?: 0) > close.at) app.copy(reopened = close.id) else app
        }
        override fun closed() = closed.toMap()
        override fun close(packages: List<String>, at: Long) {
            killed += packages; closedAt = at
            packages.forEach { closed[it] = RecentClose("close:${++closes}", at, null, emptyList()) }
        }
        override fun launch(packageName: String) { launched += packageName }
        override fun openAccess() = true
    }
}
