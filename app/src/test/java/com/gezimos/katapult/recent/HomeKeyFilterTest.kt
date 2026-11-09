package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test

class HomeKeyFilterTest {
    private class Fixture {
        var now = 0L
        var eligible = true
        val results = mutableListOf<String>()
        class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
        val filter = HomeKeyFilter({ now }, { delay, action ->
            val task = Task(now + delay, action)
            tasks += task
            AutoCloseable { task.cancelled = true }
        }, { eligible }, { results += "home" }, { results += "double" })
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
}
