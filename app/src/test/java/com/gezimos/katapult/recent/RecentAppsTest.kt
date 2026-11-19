package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test

class RecentAppsTest {
    private val day = 86_400_000L
    // Wall time 1_800_000_000_000 at elapsed 1_000_000 when the app is closed.
    private val o = 1_800_000_000_000L - 1_000_000
    private val month = 1_798_000_000_000L
    private val months = listOf(month - 30 * day, month)
    private val booted = o + 20_000
    private fun resume(at: Long) = UsageMark(UsageKind.RESUMED, at)
    private fun startup(at: Long) = UsageMark(UsageKind.STARTUP, at)
    private fun List<UsageMark>.shifted(by: Long) = map { it.copy(at = it.at + by) }
    private fun legacy(at: Long) = RecentClose("v0.7.0:$at", at, null)
    private val before = listOf(startup(booted), resume(o + 900_000), resume(o + 950_000), resume(o + 990_000))
    private fun closed(position: Long = o + 990_000, boot: Int = 1, offset: Long? = o, month: Long? = this.month,
        startup: Long? = booted, id: String = "close") =
        RecentClose(id, o + 1_000_000, CloseAnchor(UsageClock(boot, offset), position, month, startup))
    /** [shift] is the rebase Android applied to every stored time; [offset] is the clock sample of this view. */
    private fun view(events: List<UsageMark>, offset: Long? = o, shift: Long = 0, boot: Int = 1, months: List<Long> = this.months) =
        UsageView(UsageClock(boot, offset), months.map { it + shift }, events)

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

    @Test fun rebasedClockChangesNeitherRestoreAClosedAppNorHideARealRelaunch() {
        for (shift in listOf(-36 * 3_600_000L, -3_000L, 3_000L, 3 * 3_600_000L)) {
            for (noise in listOf(-2_900L, 0L, 2_900L)) {
                val stream = before.shifted(shift)
                val sampled = o + shift + noise
                assertFalse("A clock change alone is not reuse ($shift, $noise)", closed().reopenedIn(view(stream, sampled, shift)))
                assertTrue("A resume 50 ms after the close is reuse ($shift, $noise)",
                    closed().reopenedIn(view(stream + resume(o + 1_000_050 + shift), sampled, shift)))
            }
        }
    }

    @Test fun cr4624139102UnrebasedDriftAndRepeatedSessionsCannotHideOrInventReuse() {
        // Four identical one-second sessions end just before the close; Android does not rebase drift under 2 s.
        val sessions = listOf(startup(booted)) + (0 until 4).map { resume(o + 996_000 + it * 1_000L) }
        val close = closed(position = o + 999_000)
        for (drift in listOf(1_000L, 1_900L, -1_500L, -1_900L)) {
            assertFalse("Unchanged stream, drift $drift", close.reopenedIn(view(sessions, o + drift)))
            assertTrue("Reopen 500 ms after the close, drift $drift",
                close.reopenedIn(view(sessions + resume(o + 1_000_500) + resume(o + 1_001_500), o + drift)))
        }
        // Android's usage clock can also lag wall time by up to 2 s when the app is closed.
        val lag = -1_500L
        val lagging = closed(position = o + lag + 999_000)
        assertFalse(lagging.reopenedIn(view(sessions.shifted(lag), o)))
        assertTrue("Reopen 450 ms after the close, behind the wall-time close",
            lagging.reopenedIn(view(sessions.shifted(lag) + resume(o + lag + 1_000_450), o)))
    }

    @Test fun cr4624139100SessionsOnBothSidesOfAStartupCannotHideAResumeOneSecondAfterTheClose() {
        val stream = listOf(resume(o - 30_000), startup(booted), resume(o + 997_000))
        val close = closed(position = o + 997_000)
        assertFalse(close.reopenedIn(view(stream)))
        assertTrue(close.reopenedIn(view(stream + resume(o + 1_001_000))))
    }

    @Test fun pruningOlderDaysCannotHideTheFirstResumeAfterARecentClose() {
        // Android deleted the daily files with the boot's startup and older resumes; the latest pre-close resume remains.
        val retained = listOf(resume(o + 990_000))
        assertFalse(closed().reopenedIn(view(retained, months = listOf(month))))
        assertTrue(closed().reopenedIn(view(retained + resume(o + 1_001_000), months = listOf(month))))
        // Every pre-close event is gone; only the reopen remains, also after a rebase.
        assertTrue(closed().reopenedIn(view(listOf(resume(o + 1_001_000 - 7_200_000)), o - 7_200_000, -7_200_000)))
        assertFalse(closed().reopenedIn(view(emptyList(), o - 7_200_000, -7_200_000)))
    }

    @Test fun withoutOneMonthlyStartTheCloseIsBoundedByAndroidsTolerance() {
        val stream = before + resume(o + 999_000 - 9_000 + 2_000)
        for (missing in listOf(closed(month = null), closed().copy(anchor = closed().anchor!!.copy(month = month + 30 * day)))) {
            assertFalse("A resume inside the tolerance may precede the close", missing.reopenedIn(view(stream)))
            assertTrue(missing.reopenedIn(view(before + resume(o + 990_000 + 6_001), o)))
            assertTrue(missing.reopenedIn(view(before + resume(o + 990_000 + 3_000 + 6_001), o + 3_000)))
        }
        val twoStarts = listOf(month - 2_000, month + 2_000)
        assertFalse("Two interval starts within the bound do not decide the shift",
            closed().reopenedIn(view(before + resume(o + 991_000), months = twoStarts)))
        assertFalse("A clock change during the query defers the decision",
            closed().reopenedIn(view(before + resume(o + 1_100_000), offset = null)))
        assertFalse("Unknown clock at close", closed(offset = null).reopenedIn(view(before + resume(o + 1_100_000))))
    }

    @Test fun rebootCountsLaterBootsAndTheCloseBootAfterTheClose() {
        val later = startup(o + 5_000_000)
        val boot2 = 2
        assertFalse(closed().reopenedIn(view(before + later, boot = boot2)))
        assertTrue("Reopen in the next boot", closed().reopenedIn(view(before + later + resume(o + 5_010_000), boot = boot2)))
        assertTrue("Reopen after the close, before the reboot",
            closed().reopenedIn(view(before + resume(o + 1_001_000) + later, boot = boot2)))
        val back = -36 * 3_600_000L
        assertTrue("The close boot's startup carries Android's later rebase",
            closed().reopenedIn(view((before + resume(o + 1_001_000) + later).shifted(back), boot = boot2)))
        assertFalse(closed().reopenedIn(view((before + later).shifted(back), boot = boot2)))
        val third = startup(o + 9_000_000)
        assertTrue("Two boots since the close", closed().reopenedIn(view(before + later + resume(o + 5_010_000) + third, boot = 3)))
        assertFalse(closed().reopenedIn(view(before + later + third, boot = 3)))
        assertTrue("Later startups older than retention", closed().reopenedIn(view(listOf(resume(o + 9_010_000)), boot = 3)))
        assertFalse("Pre-close resumes of earlier boots are not reuse",
            closed().reopenedIn(view(listOf(startup(o - 2 * day), resume(o - day)) + before + later, boot = boot2)))
        assertFalse("An unknown close boot decides nothing", closed(boot = -1).reopenedIn(view(before + resume(o + 1_001_000))))
    }

    @Test fun aCloseWithoutRecentResumesOrAReleasedRecordUsesItsOwnEvidence() {
        val window = o + 1_000_000 - 3_600_000
        val close = closed(position = window)
        assertFalse(close.reopenedIn(view(listOf(startup(booted), resume(window - 60_000)))))
        assertTrue(close.reopenedIn(view(listOf(startup(booted), resume(window - 60_000), resume(o + 1_000_500)))))
        assertFalse(legacy(100).reopenedIn(view(listOf(resume(100)))))
        assertTrue(legacy(100).reopenedIn(view(listOf(resume(101)))))
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
            packages.forEach { closed[it] = RecentClose("close:${++closes}", at, null) }
        }
        override fun launch(packageName: String) { launched += packageName }
        override fun openAccess() = true
    }
}
