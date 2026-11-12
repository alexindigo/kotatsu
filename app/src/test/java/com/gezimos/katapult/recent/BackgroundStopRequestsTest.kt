package com.gezimos.katapult.recent

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BackgroundStopRequestsTest {
    private class Fixture {
        var now = 0L
        class Task(val at: Long, val run: () -> Unit) { var cancelled = false }
        val tasks = mutableListOf<Task>()
        val requests = mutableListOf<Pair<String, Long>>()
        val stops = BackgroundStopRequests({ delay, action ->
            val task = Task(now + delay, action)
            tasks += task
            AutoCloseable { task.cancelled = true }
        }, { requests += it to now })
        fun advance(at: Long) {
            while (true) {
                val task = tasks.firstOrNull { !it.cancelled && it.at <= at } ?: break
                task.cancelled = true; now = task.at; task.run()
            }
            now = at
        }
    }

    @Test fun requestsAreDeferredBoundedAndNeverClaimSuccess() {
        val f = Fixture()
        f.stops.request("app")
        assertTrue(f.requests.isEmpty())
        f.advance(0); assertEquals(listOf("app" to 0L), f.requests)
        f.advance(299); assertEquals(1, f.requests.size)
        f.advance(300); f.advance(900); f.advance(1500); f.advance(100_000)
        assertEquals(listOf(0L, 300L, 900L, 1500L), f.requests.map { it.second })
    }

    @Test fun teardownCancelsEvenCallbacksAlreadyDequeuedAndCannotRestart() {
        val f = Fixture(); f.stops.request("app"); f.advance(0)
        f.stops.close()
        f.tasks.forEach { it.run() }
        f.stops.request("other"); f.advance(10_000)
        assertEquals(listOf("app" to 0L), f.requests)
    }

    @Test fun relaunchCancelsOnlyThatPackage() {
        val f = Fixture(); f.stops.request("app"); f.stops.request("other"); f.advance(0)
        f.stops.cancel("app"); f.advance(10_000)
        assertEquals(1, f.requests.count { it.first == "app" })
        assertEquals(4, f.requests.count { it.first == "other" })
    }

    @Test fun repeatedCloseSupersedesItsOldSchedule() {
        val f = Fixture(); f.stops.request("app"); f.advance(0)
        f.now = 100; f.stops.request("app")
        f.advance(100); f.advance(400); f.advance(1000); f.advance(1600); f.advance(10_000)
        assertEquals(listOf(0L, 100L, 400L, 1000L, 1600L), f.requests.map { it.second })
    }

    @Test fun interruptionCancelsPendingWorkWithoutEndingTheOwner() {
        val f = Fixture(); f.stops.request("app"); f.stops.cancelAll()
        f.tasks.forEach { it.run() }; assertTrue(f.requests.isEmpty())
        f.now = 2000; f.stops.request("other"); f.advance(2000)
        assertEquals(listOf("other" to 2000L), f.requests)
    }

    @Test fun stoppingProtectionCanIgnoreEarlyRequestsWithoutLosingLaterAttempts() {
        val tasks = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L
        var alive = true
        val stops = BackgroundStopRequests({ delay, action ->
            tasks += delay to action
            AutoCloseable {}
        }, {
            // UID state 15 does not make the initial perceptible adjustment killable.
            val nativeAdjustment = if (now < 500) 200 else 700
            if (nativeAdjustment >= 500) alive = false
        })
        stops.request("app")
        for ((at, action) in tasks) {
            now = at; action()
            if (at < 500) assertTrue(alive)
        }
        assertFalse(alive)
    }

    @Test fun teardownNeverWaitsForAnInFlightNativeStop() {
        val callbacks = mutableListOf<() -> Unit>()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val stops = BackgroundStopRequests({ _, action -> callbacks += action; AutoCloseable {} }, {
            entered.countDown(); release.await()
        })
        stops.request("app")
        val worker = thread { callbacks.first()() }
        var teardown: Thread? = null
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS))
            teardown = thread { stops.close(); closed.countDown() }
            assertTrue("Teardown must not wait on the native binder call", closed.await(1, TimeUnit.SECONDS))
        } finally {
            release.countDown(); worker.join(); teardown?.join()
        }
    }
}
