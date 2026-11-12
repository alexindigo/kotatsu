package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

class RefreshJobExecutionsTest {
    private val old = RefreshExecutionKey(42, "old")
    private val next = RefreshExecutionKey(42, "next")

    private class Fixture {
        val queued = ArrayDeque<Runnable>()
        val posted = ConcurrentLinkedQueue<() -> Unit>()
        val events = mutableListOf<String>()
        var reject = false
        val owner = RefreshJobExecutions(Executor {
            if (reject) throw RejectedExecutionException("Fixture rejected submission")
            queued.addLast(it)
        }, posted::add)

        fun start(key: RefreshExecutionKey, name: String) = owner.start(key,
            work = { events += "work $name" }, finished = { events += "finish $name" })
        fun runNext() = queued.removeFirst().run()
        fun flush() { while (true) (posted.poll() ?: return).invoke() }
    }

    @Test fun oldStopCannotCancelQueuedReplacementWithTheSameJobId() {
        val f = Fixture()
        assertTrue(f.start(old, "old"))
        assertTrue(f.start(next, "new"))
        // Stop parameters are a different object after the native parcel round trip.
        f.owner.stop(old.copy())
        f.runNext()
        f.runNext()
        f.flush()
        assertEquals(listOf("work new", "finish new"), f.events)
    }

    @Test fun oldFinishAfterNewStartCannotRemoveNewOwnership() {
        val f = Fixture()
        f.start(old, "old")
        f.runNext() // Worker finished, but its main-thread completion has not run.
        f.start(next, "new")
        f.flush()
        f.runNext()
        f.flush()
        assertEquals(listOf("work old", "finish old", "work new", "finish new"), f.events)
    }

    @Test fun stopAfterWorkerReturnSuppressesOnlyThatRunsPostedFinish() {
        val f = Fixture()
        f.start(old, "old")
        f.runNext()
        f.start(next, "new")
        f.owner.stop(old)
        f.flush()
        f.runNext()
        f.flush()
        assertEquals(listOf("work old", "work new", "finish new"), f.events)
    }

    @Test fun oldFinishCannotMakeTheNewExecutionUnstoppable() {
        val f = Fixture()
        f.start(old, "old")
        f.runNext()
        f.start(next, "new")
        f.flush()
        f.owner.stop(next.copy())
        f.runNext()
        f.flush()
        assertEquals(listOf("work old", "finish old"), f.events)
    }

    @Test fun queuedCanceledCompletionCannotRetireALaterRunOfTheSameGeneration() {
        val f = Fixture()
        f.start(old, "canceled")
        f.owner.stop(old.copy())
        f.start(old.copy(), "later")
        f.runNext()
        f.flush()
        f.runNext()
        f.flush()
        assertEquals(listOf("work later", "finish later"), f.events)
    }

    @Test fun queuedCancellationIsRetiredWithoutEnteringTheCallable() {
        val f = Fixture()
        f.start(old, "canceled")
        f.owner.stop(old)
        f.owner.stop(old)
        f.flush()
        f.runNext()
        f.flush()
        assertTrue(f.events.isEmpty())
        // The same periodic generation may execute again after its prior run is retired.
        f.start(old, "later")
        f.runNext()
        f.flush()
        assertEquals(listOf("work later", "finish later"), f.events)
    }

    @Test fun lateStopForAFinishedGenerationCannotStopItsReplacement() {
        val f = Fixture()
        f.start(old, "old")
        f.runNext()
        f.flush()
        f.start(next, "new")
        f.owner.stop(old)
        f.runNext()
        f.flush()
        assertEquals(listOf("work old", "finish old", "work new", "finish new"), f.events)
    }

    @Test fun separateJobIdsDoNotShareOwnershipEvenWithTheSameGeneration() {
        val f = Fixture()
        val other = old.copy(jobId = 43)
        f.start(old, "old")
        f.start(other, "other")
        f.owner.stop(old)
        f.runNext()
        f.runNext()
        f.flush()
        assertEquals(listOf("work other", "finish other"), f.events)
    }

    @Test fun previouslyScheduledJobWithoutGenerationCannotStealNewGeneration() {
        val f = Fixture()
        val legacy = old.copy(generation = null)
        f.start(legacy, "legacy")
        f.start(next, "new")
        f.owner.stop(legacy.copy())
        f.runNext()
        f.runNext()
        f.flush()
        assertEquals(listOf("work new", "finish new"), f.events)
    }

    @Test fun uncheckedWorkerFailureStillFinishesExactlyOnce() {
        val f = Fixture()
        assertTrue(f.owner.start(old, work = { throw AssertionError("Fixture worker failure") },
            finished = { f.events += "finish" }))
        f.runNext()
        f.flush()
        f.flush()
        assertEquals(listOf("finish"), f.events)
    }

    @Test fun rejectedSubmissionReturnsNonAsyncWithoutALeakedFinish() {
        val f = Fixture()
        f.reject = true
        assertFalse(f.start(old, "rejected"))
        f.flush()
        assertTrue(f.events.isEmpty())
        f.reject = false
        f.start(old, "accepted")
        f.runNext()
        f.flush()
        assertEquals(listOf("work accepted", "finish accepted"), f.events)
    }

    @Test fun serviceDestructionRetiresEveryQueuedExecution() {
        val f = Fixture()
        f.start(old, "old")
        f.start(next, "new")
        f.owner.stopAll()
        f.runNext()
        f.runNext()
        f.flush()
        assertTrue(f.events.isEmpty())
    }

    @Test fun runningCancellationAndLateWorkerExitCannotFinishTheReplacement() {
        val f = Fixture()
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val release = CountDownLatch(1)
        f.owner.start(old, work = {
            entered.countDown()
            try { release.await() } catch (_: InterruptedException) {
                interrupted.countDown()
                release.await()
            }
        }, finished = { f.events += "finish old" })
        val worker = Thread(f.queued.removeFirst(), "ownership-test-worker")
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            f.start(next, "new")
            f.owner.stop(old.copy())
            assertTrue(interrupted.await(5, TimeUnit.SECONDS))
            f.flush()
            f.runNext()
            f.flush()
            release.countDown()
            worker.join(5000)
            assertFalse(worker.isAlive)
            f.flush()
            assertEquals(listOf("work new", "finish new"), f.events)
        } finally {
            release.countDown()
            worker.join(5000)
        }
    }
}
