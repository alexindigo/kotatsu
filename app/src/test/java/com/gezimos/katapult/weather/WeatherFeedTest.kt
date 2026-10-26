package com.gezimos.katapult.weather

import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class WeatherFeedTest {
    private val weather = Weather(21, "C", WeatherCondition.FEW_CLOUDS, false)
    private class MainQueue : Executor {
        private val tasks = ConcurrentLinkedQueue<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (true) (tasks.poll() ?: return).run() }
    }
    private fun await(message: String, ready: () -> Boolean) {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!ready() && System.nanoTime() < end) Thread.sleep(2)
        assertTrue(message, ready())
    }

    private class Fixture(resolve: () -> WeatherFeed.Source?) : AutoCloseable {
        val main = MainQueue()
        val worker = Executors.newSingleThreadExecutor()
        val feed = WeatherFeed(resolve, worker, main) { 123L }
        override fun close() { feed.close(); worker.shutdown(); assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS)) }
    }

    @Test fun blockingReadCannotBlockAcquireObserverCallbackOrMainDelivery() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var providerChanged: (() -> Unit)? = null
        val source = WeatherFeed.Source(Any(), { entered.countDown(); release.await(); weather }, {
            providerChanged = it; AutoCloseable { }
        })
        Fixture { source }.use { f ->
            try {
                val delivered = mutableListOf<WeatherFeed.State>()
                val caller = Thread.currentThread()
                val start = System.nanoTime()
                val lease = f.feed.acquire { assertSame(caller, Thread.currentThread()); delivered.add(it) }
                assertTrue("acquire blocked", System.nanoTime() - start < TimeUnit.MILLISECONDS.toNanos(500))
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                val callbackStart = System.nanoTime()
                providerChanged!!()
                assertTrue("provider callback blocked", System.nanoTime() - callbackStart < TimeUnit.MILLISECONDS.toNanos(500))
                f.main.drain() // Main stays usable while the provider is blocked.
                release.countDown()
                await("weather reaches main consumer") { f.main.drain(); delivered.any { it is WeatherFeed.State.Reading } }
                assertEquals(WeatherFeed.State.Reading(weather, 123), delivered.last())
                lease.close()
            } finally { release.countDown() }
        }
    }

    @Test fun registrationFailureIsFailedAndNeverReadsOrThrowsToTheConsumer() {
        val reads = AtomicInteger()
        val source = WeatherFeed.Source(Any(), { reads.incrementAndGet(); weather }, { throw SecurityException("provider unavailable") })
        Fixture { source }.use { f ->
            var observed: WeatherFeed.State? = null
            f.feed.acquire { observed = it }.use {
                await("Failed callback") { f.main.drain(); observed == WeatherFeed.State.Failed }
                assertEquals(0, reads.get())
            }
        }
    }

    @Test fun switchingSourceDropsABlockedOldResultAndItsLateCallbacks() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var oldCallback: (() -> Unit)? = null
        val closes = AtomicInteger()
        val first = WeatherFeed.Source(Any(), { entered.countDown(); release.await(); weather }, {
            oldCallback = it; AutoCloseable { closes.incrementAndGet() }
        })
        val replacement = weather.copy(temperature = -5)
        val second = WeatherFeed.Source(Any(), { replacement }, { AutoCloseable { } })
        val selection = java.util.concurrent.atomic.AtomicReference(first)
        Fixture { selection.get() }.use { f ->
            try {
                val received = mutableListOf<WeatherFeed.State>()
                f.feed.acquire(received::add).use {
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    selection.set(second)
                    f.feed.refresh(force = true) // Invalidates before the blocked query can return.
                    release.countDown()
                    await("replacement received") { f.main.drain(); received.any { it.weather == replacement } }
                    assertFalse(received.any { it.weather == weather })
                    assertEquals(1, closes.get())
                    val state = f.feed.state
                    oldCallback!!()
                    assertEquals(state, f.feed.state)
                }
            } finally { release.countDown() }
        }
    }

    @Test fun leasesShareOneRegistrationAndOnlyTheLastCloseUnregisters() {
        val registrations = AtomicInteger()
        val closes = AtomicInteger()
        val source = WeatherFeed.Source(Any(), { weather }, {
            registrations.incrementAndGet(); AutoCloseable { closes.incrementAndGet() }
        })
        Fixture { source }.use { f ->
            val first = f.feed.acquire { }
            val second = f.feed.acquire { }
            await("read") { f.feed.state is WeatherFeed.State.Reading }
            assertEquals(1, registrations.get())
            first.close(); first.close()
            assertEquals(0, closes.get())
            second.close()
            await("unregistered") { closes.get() == 1 }
            assertEquals(WeatherFeed.State.Unavailable, f.feed.state)
            assertEquals(1, registrations.get())
        }
    }

    @Test fun closingLastLeaseDropsLateReadAndQueuedMainCallback() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val source = WeatherFeed.Source(Any(), { entered.countDown(); release.await(); weather }, {
            AutoCloseable { closes.incrementAndGet() }
        })
        Fixture { source }.use { f ->
            try {
                val received = mutableListOf<WeatherFeed.State>()
                val lease = f.feed.acquire(received::add)
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                lease.close()
                release.countDown()
                await("detach") { closes.get() == 1 }
                f.main.drain()
                assertTrue(received.isEmpty())
                assertEquals(WeatherFeed.State.Unavailable, f.feed.state)
            } finally { release.countDown() }
        }
    }

    @Test fun closingTheFeedInvalidatesBlockedWorkBeforeDetaching() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closes = AtomicInteger()
        val source = WeatherFeed.Source(Any(), { entered.countDown(); release.await(); weather }, {
            AutoCloseable { closes.incrementAndGet() }
        })
        Fixture { source }.use { f ->
            try {
                val received = mutableListOf<WeatherFeed.State>()
                f.feed.acquire(received::add)
                assertTrue(entered.await(1, TimeUnit.SECONDS))
                f.feed.close()
                release.countDown()
                await("closed feed detached") { closes.get() == 1 }
                f.main.drain()
                assertEquals(WeatherFeed.State.Unavailable, f.feed.state)
                assertTrue(received.isEmpty())
            } finally { release.countDown() }
        }
    }

    @Test fun reactivationRegistersOnceAndRejectsThePreviousSessionsCallback() {
        val registrations = AtomicInteger()
        val reads = AtomicInteger()
        val changes = mutableListOf<() -> Unit>()
        val source = WeatherFeed.Source(Any(), { reads.incrementAndGet(); weather }, {
            synchronized(changes) { changes.add(it) }
            registrations.incrementAndGet()
            AutoCloseable { }
        })
        Fixture { source }.use { f ->
            val first = f.feed.acquire { }
            await("first reading") { f.feed.state is WeatherFeed.State.Reading }
            first.close()
            val second = f.feed.acquire { }
            try {
                await("second session registered") { registrations.get() == 2 && f.feed.state is WeatherFeed.State.Reading }
                val count = reads.get()
                synchronized(changes) { changes.first() }.invoke()
                // A queued barrier observes any work incorrectly enqueued by the obsolete callback.
                f.worker.submit { }.get(1, TimeUnit.SECONDS)
                assertEquals(count, reads.get())
                assertEquals(2, registrations.get())
            } finally { second.close() }
        }
    }

    @Test fun ordinaryClockChecksAndAdditionalConsumersJoinThePendingRead() {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        val source = WeatherFeed.Source(Any(), {
            reads.incrementAndGet(); entered.countDown(); release.await(); weather
        }, { AutoCloseable { } })
        Fixture { source }.use { f ->
            try {
                f.feed.acquire { }.use {
                    assertTrue(entered.await(1, TimeUnit.SECONDS))
                    f.feed.acquire { }.use {
                        repeat(20) { f.feed.refresh() }
                        release.countDown()
                        await("pending read published") { f.feed.state is WeatherFeed.State.Reading }
                        f.worker.submit { }.get(1, TimeUnit.SECONDS)
                        assertEquals("Ordinary checks must not invalidate or repeat pending work", 1, reads.get())
                    }
                }
            } finally { release.countDown() }
        }
    }

    @Test fun completedAttemptsThrottleEveryOutcomeButForceAndNewSessionsBypass() {
        // Resolver absent, empty row, read failure, observe failure, and successful reading.
        for (outcome in 0..4) {
            var time = 0L
            var attempts = 0
            val worker = MainQueue()
            val callbacks = MainQueue()
            val source = WeatherFeed.Source(Any(), {
                when (outcome) {
                    1 -> null
                    2 -> throw IllegalStateException("query failed")
                    else -> weather
                }
            }, { if (outcome == 3) throw SecurityException("observe failed") else AutoCloseable { } })
            val feed = WeatherFeed({ attempts++; source.takeUnless { outcome == 0 } }, worker, callbacks) { time }
            val received = mutableListOf<WeatherFeed.State>()
            fun drain() { worker.drain(); callbacks.drain() }
            try {
                val lease = feed.acquire(received::add)
                drain()
                assertEquals(1, attempts)
                assertEquals(when (outcome) {
                    0, 1 -> WeatherFeed.State.Unavailable
                    2, 3 -> WeatherFeed.State.Failed
                    else -> WeatherFeed.State.Reading(weather, time)
                }, received.last())
                time = 599_999
                repeat(40) { feed.refresh(); drain() }
                assertEquals("Outcome $outcome must share the freshness window", 1, attempts)
                time++
                feed.refresh(); drain()
                assertEquals(2, attempts)
                feed.refresh(force = true); drain()
                assertEquals(3, attempts)
                lease.close(); drain()
                feed.acquire(received::add).use { drain(); assertEquals(4, attempts) }
            } finally { feed.close(); drain() }
        }
    }

    @Test fun delayedInitialDeliveryCannotRollBackAnAlreadyPublishedReading() {
        val reading = CountDownLatch(1)
        val releaseRead = CountDownLatch(1)
        val postingInitial = CountDownLatch(1)
        val releaseInitial = CountDownLatch(1)
        val queue = MainQueue()
        val worker = Executors.newSingleThreadExecutor()
        val acquirer = Executors.newSingleThreadExecutor { Thread(it, "WeatherAcquire") }
        val callbacks = Executor { task ->
            // Model preemption between a new consumer's snapshot and enqueue. The worker can
            // publish and enqueue its newer result first, even though the consumer joined earlier.
            if (Thread.currentThread().name == "WeatherAcquire") {
                postingInitial.countDown(); releaseInitial.await()
            }
            queue.execute(task)
        }
        val source = WeatherFeed.Source(Any(), { reading.countDown(); releaseRead.await(); weather }, { AutoCloseable { } })
        val feed = WeatherFeed({ source }, worker, callbacks) { 123L }
        try {
            feed.acquire { }
            assertTrue(reading.await(1, TimeUnit.SECONDS))
            val received = mutableListOf<WeatherFeed.State>()
            val lease = acquirer.submit<AutoCloseable> { feed.acquire(received::add) }
            assertTrue(postingInitial.await(1, TimeUnit.SECONDS))
            releaseRead.countDown()
            await("worker published") { feed.state is WeatherFeed.State.Reading }
            worker.submit { }.get(1, TimeUnit.SECONDS)
            releaseInitial.countDown()
            lease.get(1, TimeUnit.SECONDS).use {
                queue.drain()
                assertEquals("Initial delivery must not restore an obsolete Unavailable state",
                    WeatherFeed.State.Reading(weather, 123), received.last())
            }
        } finally {
            releaseRead.countDown(); releaseInitial.countDown()
            feed.close(); worker.shutdown(); acquirer.shutdown()
            assertTrue(worker.awaitTermination(3, TimeUnit.SECONDS))
            assertTrue(acquirer.awaitTermination(3, TimeUnit.SECONDS))
        }
    }
}
