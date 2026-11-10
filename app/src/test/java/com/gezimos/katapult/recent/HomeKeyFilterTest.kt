package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test

class HomeKeyFilterTest {
    private class Fixture {
        var now = 0L
        var eligible = true
        var recentApps = true
        var screenshot = false
        val results = mutableListOf<String>()
        class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
        val filter = HomeKeyFilter({ now }, { delay, action ->
            val task = Task(now + delay, action)
            tasks += task
            AutoCloseable { task.cancelled = true }
        }, { eligible }, { results += "home" }, { results += "double" }, { recentApps }, { screenshot }, { results += "screenshot" })
        fun advance(at: Long) {
            while (true) {
                val next = tasks.filter { !it.cancelled && it.at <= at }.minByOrNull { it.at } ?: break
                next.cancelled = true; now = next.at; next.run()
            }
            now = at
        }
    }

    @Test fun singleIsConsumedAndSentAtFiveHundredMs() {
        val f = Fixture()
        assertTrue(f.filter.down(0)); assertTrue(f.filter.up())
        f.advance(499); assertTrue(f.results.isEmpty())
        f.advance(500); assertEquals(listOf("home"), f.results)
        f.advance(2000); assertEquals(1, f.results.size)
    }

    @Test fun pairsAtRealKeyTimesNeverSendHomeAndThirdPressStartsNewSequence() {
        for (gap in listOf(140L, 450L)) {
            val f = Fixture()
            assertTrue(f.filter.down(0)); assertTrue(f.filter.up())
            f.advance(gap)
            assertTrue(f.filter.down(gap)); assertTrue(f.filter.up())
            f.advance(1000); assertEquals(listOf("double"), f.results)
            assertTrue(f.filter.down(1000)); assertTrue(f.filter.up())
            f.advance(1500); assertEquals(listOf("double", "home"), f.results)
        }
    }

    @Test fun pairAtFiveTwentyIsTwoSinglesEvenWithDelayedTimerDelivery() {
        val f = Fixture()
        f.filter.down(0); f.filter.up()
        f.now = 520
        f.filter.down(520); f.filter.up()
        assertEquals(listOf("home"), f.results)
        f.advance(1020); assertEquals(listOf("home", "home"), f.results)
    }

    @Test fun heldHomeWaitsForUpAndRepeatsDoNotPair() {
        val f = Fixture()
        assertTrue(f.filter.down(0))
        f.advance(600)
        assertTrue(f.filter.down(600, repeat = true)); assertTrue(f.results.isEmpty())
        assertTrue(f.filter.up()); assertEquals(listOf("home"), f.results)
        assertFalse(f.filter.up())
    }

    @Test fun ineligibleKeyguardScreenOffOnboardingOrReorderPassThroughAndCancelPendingWork() {
        for (reason in listOf("keyguard", "screen off", "onboarding", "reorder")) {
            val f = Fixture()
            f.eligible = false
            assertFalse(reason, f.filter.down(0)); assertFalse(reason, f.filter.up())
            f.eligible = true
            f.filter.down(100); f.filter.up()
            f.eligible = false
            f.advance(600); assertTrue(reason, f.results.isEmpty())
        }
    }

    @Test fun closeCancelsHeldAndPendingSequencesAndLateCallbacks() {
        val f = Fixture()
        f.filter.down(0)
        val stale = f.tasks.single().run
        f.filter.close()
        stale(); f.advance(1000)
        assertTrue(f.results.isEmpty()); assertFalse(f.filter.up())
    }

    @Test fun eligibilityLossCancelsActionButOwnsConsumedDownThroughUp() {
        for (timerRuns in listOf(false, true)) {
            val f = Fixture()
            assertTrue(f.filter.down(0))
            val stale = f.tasks.single().run
            f.eligible = false
            if (timerRuns) f.advance(500)
            assertTrue("Repeats of an owned DOWN must stay consumed", f.filter.down(600, repeat = true))
            assertTrue("A lone UP must not reach Android's Home handler", f.filter.up())
            assertFalse(f.filter.up())
            stale(); f.advance(1000)
            assertTrue(f.results.isEmpty())
            f.eligible = true
            assertTrue(f.filter.down(1000)); assertTrue(f.filter.up())
            f.advance(1500)
            assertEquals(listOf("home"), f.results)
        }
    }

    @Test fun eligibilityLossOnUpConsumesOnlyAnOwnedPair() {
        val f = Fixture()
        assertTrue(f.filter.down(0))
        f.eligible = false
        assertTrue(f.filter.up())
        assertFalse(f.filter.up())
        f.advance(1000)
        assertTrue(f.results.isEmpty())
        assertFalse(f.filter.down(1000)); assertFalse(f.filter.up())
    }

    @Test fun eligibilityLossOnUnownedUpCancelsTheReleasedSingle() {
        val f = Fixture()
        f.filter.down(0); f.filter.up()
        f.eligible = false
        assertFalse(f.filter.up())
        f.eligible = true
        f.advance(1000)
        assertTrue(f.results.isEmpty())
    }

    @Test fun recentAppsOffPassesEveryHomeEventAndCancelsPendingSingle() {
        val f = Fixture()
        f.filter.down(0); f.filter.up()
        f.eligible = false
        assertFalse(f.filter.down(100))
        assertFalse(f.filter.down(110, repeat = true))
        assertFalse(f.filter.up())
        f.advance(1000)
        assertTrue(f.results.isEmpty())
        f.eligible = true
        assertTrue(f.filter.down(1000)); assertTrue(f.filter.up())
        f.advance(1500)
        assertEquals(listOf("home"), f.results)
    }

    @Test fun elapsedEventTimeRatherThanHandlerTimeDefinesPair() {
        val f = Fixture()
        f.now = 300
        f.filter.down(100); f.filter.up()
        f.now = 580
        f.filter.down(550); f.filter.up()
        f.advance(2000)
        assertEquals(listOf("double"), f.results)
    }

    @Test fun activeAndLauncherGateLifetimesDoNotClearNewerOwners() {
        val first = Any()
        val second = Any()
        try {
            HomeKeyFilterState.connected(first)
            assertTrue(HomeKeyFilterState.active)
            HomeKeyFilterState.connected(second)
            HomeKeyFilterState.disconnected(first)
            assertTrue(HomeKeyFilterState.active)
            assertFalse(HomeKeyFilterState.isActive(first))
            assertTrue(HomeKeyFilterState.isActive(second))
            val oldGate = HomeKeyFilterState.observeLauncher { false }
            val currentGate = HomeKeyFilterState.observeLauncher { true }
            oldGate.close()
            assertTrue(HomeKeyFilterState.launcherAllowsFilter())
            currentGate.close()
            assertTrue(HomeKeyFilterState.launcherAllowsFilter())
            val blocked = HomeKeyFilterState.observeLauncher { false }
            assertFalse(HomeKeyFilterState.launcherAllowsFilter())
            blocked.close()
        } finally { HomeKeyFilterState.disconnected(second); HomeKeyFilterState.disconnected(first) }
        assertFalse(HomeKeyFilterState.active)
    }

    @Test fun screenshotOnlySendsHomeOnUpWithoutSchedulingADoublePressDelay() {
        val f = Fixture(); f.recentApps = false; f.screenshot = true
        assertTrue(f.filter.down(0)); assertTrue(f.tasks.isEmpty())
        assertTrue(f.filter.down(20, repeat = true)); assertTrue(f.results.isEmpty())
        assertTrue(f.filter.up()); assertEquals(listOf("home"), f.results)
        assertTrue(f.filter.down(100)); assertTrue(f.filter.up())
        assertEquals(listOf("home", "home"), f.results)
    }

    @Test fun bothFeaturesOffPassEveryHomeAndVolumeEvent() {
        val f = Fixture(); f.recentApps = false; f.screenshot = false
        assertFalse(f.filter.down(0)); assertFalse(f.filter.down(1, repeat = true)); assertFalse(f.filter.up())
        assertFalse(f.filter.volumeDown()); assertFalse(f.filter.volumeDown(true)); assertFalse(f.filter.volumeUp())
        f.advance(1000); assertTrue(f.results.isEmpty()); assertTrue(f.tasks.isEmpty())
    }

    @Test fun chordConsumesBothReleaseOrdersAndRepeatsAndCancelsEvenAnExpiredHome() {
        for (recent in listOf(false, true)) for (homeFirst in listOf(false, true)) for (heldUntil in listOf(100L, 700L)) {
            val f = Fixture(); f.recentApps = recent; f.screenshot = true
            assertFalse(f.filter.volumeDown()); assertFalse(f.filter.volumeUp())
            assertTrue(f.filter.down(0)); f.advance(heldUntil)
            assertTrue(f.filter.volumeDown()); assertTrue(f.filter.volumeDown(true))
            assertTrue(f.filter.down(heldUntil, repeat = true))
            if (homeFirst) { assertTrue(f.filter.up()); assertTrue(f.filter.volumeUp()) }
            else {
                assertTrue(f.filter.volumeUp())
                assertFalse("Only one screenshot per held Home", f.filter.volumeDown())
                assertTrue(f.filter.up())
            }
            f.advance(2000); assertEquals(listOf("screenshot"), f.results)
            assertFalse(f.filter.up()); assertFalse(f.filter.volumeUp())
            // The capture may still be saving. A separate chord must remain accepted.
            assertTrue(f.filter.down(2000)); assertTrue(f.filter.volumeDown()); assertTrue(f.filter.up()); assertTrue(f.filter.volumeUp())
            assertEquals(listOf("screenshot", "screenshot"), f.results)
        }
    }

    @Test fun disabledScreenshotAndUnheldVolumeRepeatsNeverCapture() {
        val f = Fixture()
        assertTrue(f.filter.down(0)); assertFalse(f.filter.volumeDown()); assertFalse(f.filter.volumeUp())
        assertTrue(f.filter.up()); f.advance(500); assertEquals(listOf("home"), f.results)
        f.screenshot = true
        assertTrue(f.filter.down(1000)); assertFalse(f.filter.volumeDown(true))
        f.eligible = false
        assertFalse(f.filter.volumeDown()); assertTrue(f.filter.up()); assertFalse(f.filter.volumeUp())
        f.advance(2000); assertEquals(listOf("home"), f.results)
    }

    @Test fun screenshotOnlyEligibilityLossKeepsTheOwnedHomeReleaseWithoutSendingHome() {
        val f = Fixture(); f.recentApps = false; f.screenshot = true
        assertTrue(f.filter.down(0))
        f.eligible = false
        assertTrue(f.filter.down(100, repeat = true))
        assertTrue(f.filter.up()); assertFalse(f.filter.up())
        f.advance(1000); assertTrue(f.results.isEmpty())
    }

    @Test fun chordKeepsBothOwnedReleasesWhenEligibilityOrFeaturesEnd() {
        for (featuresOff in listOf(false, true)) for (homeFirst in listOf(false, true)) {
            val f = Fixture(); f.screenshot = true
            assertTrue(f.filter.down(0)); assertTrue(f.filter.volumeDown())
            if (featuresOff) { f.recentApps = false; f.screenshot = false }
            else f.eligible = false
            assertTrue(f.filter.down(100, repeat = true)); assertTrue(f.filter.volumeDown(true))
            if (homeFirst) { assertTrue(f.filter.up()); assertTrue(f.filter.volumeUp()) }
            else { assertTrue(f.filter.volumeUp()); assertTrue(f.filter.up()) }
            assertFalse(f.filter.up()); assertFalse(f.filter.volumeUp())
            f.advance(1000); assertEquals(listOf("screenshot"), f.results)
        }
    }

    @Test fun recentAppsKeepsItsExistingPairPolicyWhenScreenshotIsOn() {
        val f = Fixture(); f.screenshot = true
        f.filter.down(0); f.filter.up(); f.advance(140); f.filter.down(140); f.filter.up()
        f.advance(1000); assertEquals(listOf("double"), f.results)
    }
}
