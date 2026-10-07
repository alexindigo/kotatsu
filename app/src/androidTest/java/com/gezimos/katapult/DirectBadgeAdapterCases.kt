package com.gezimos.katapult

import android.database.ContentObserver
import android.os.CancellationSignal
import android.os.Looper
import android.os.SystemClock
import com.gezimos.katapult.service.DirectBadgeHelper
import com.gezimos.katapult.service.DirectBadgePort
import org.junit.Assert.*
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Synthetic counts/observers only: no real ContentResolver or granted OS access. */
internal object DirectBadgeAdapterCases {
    class Port : DirectBadgePort {
        @Volatile var call = false
        @Volatile var sms = false
        @Volatile var write = true
        @Volatile var action = true
        @Volatile var callEpoch = 0L
        @Volatile var smsEpoch = 0L
        @Volatile var callReader: (CancellationSignal) -> Int? = { 0 }
        @Volatile var smsReader: (CancellationSignal) -> Int? = { 0 }
        val calls = AtomicInteger()
        val messages = AtomicInteger()
        val writes = AtomicInteger()
        val active = CopyOnWriteArrayList<Pair<Boolean, ContentObserver>>()
        val subscribers = CopyOnWriteArrayList<() -> Unit>()
        override fun callEligible() = call
        override fun smsEligible() = sms
        override fun callRevision() = callEpoch
        override fun smsRevision() = smsEpoch
        override fun writeGranted() = write
        override fun acknowledgmentEligible() = call && write && action
        override fun callCount(signal: CancellationSignal): Int? {
            assertNotSame("Provider work ran on acquiring UI thread", Looper.getMainLooper(), Looper.myLooper())
            calls.incrementAndGet()
            return callReader(signal)
        }
        override fun smsCount(signal: CancellationSignal): Int? {
            assertNotSame(Looper.getMainLooper(), Looper.myLooper())
            messages.incrementAndGet()
            return smsReader(signal)
        }
        override fun observe(calls: Boolean, observer: ContentObserver): AutoCloseable {
            val item = calls to observer
            active.add(item)
            return AutoCloseable { active.remove(item) }
        }
        override fun acknowledgeCalls() { writes.incrementAndGet() }
        override fun subscribe(changed: () -> Unit): AutoCloseable {
            subscribers.add(changed)
            return AutoCloseable { subscribers.remove(changed) }
        }
        fun changed() { subscribers.forEach { it() } }
    }

    private fun await(message: String, condition: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + 8000
        while (SystemClock.uptimeMillis() < end) {
            if (condition()) return
            SystemClock.sleep(20)
        }
        fail(message)
    }

    fun independentSourcesAndOffThreadReads() {
        val port = Port()
        val helper = DirectBadgeHelper(port)
        try {
            helper.reconcile()
            assertTrue(helper.getCounts().isEmpty())
            assertEquals(0, port.calls.get()); assertEquals(0, port.messages.get())
            port.callReader = { 4 }; port.smsReader = { 2 }
            port.call = true; port.callEpoch++; port.changed()
            await("Call count did not reach actual helper consumer") { helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL] == 4 }
            assertEquals(0, port.messages.get())
            port.sms = true; port.smsEpoch++; port.changed()
            await("SMS count did not reach actual helper consumer") { helper.getCounts()[DirectBadgeHelper.MUDITA_MESSAGES] == 2 }
            port.call = false; port.callEpoch++; port.changed()
            assertNull(helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL])
            assertEquals(2, helper.getCounts()[DirectBadgeHelper.MUDITA_MESSAGES])
            assertEquals(1, port.active.size)
            port.sms = false; port.smsEpoch++; port.changed()
            assertTrue(helper.getCounts().isEmpty()); assertTrue(port.active.isEmpty())
            helper.close(); helper.close()
            assertTrue(port.subscribers.isEmpty())
        } finally { helper.close() }
    }

    fun cancelDebounceAndRejectLateAndDetachedCallbacks() {
        val port = Port()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        var signal: CancellationSignal? = null
        port.callReader = { cancellation ->
            signal = cancellation
            entered.countDown()
            assertTrue(release.await(10, TimeUnit.SECONDS))
            7 // Deliberately ignores cancellation, like a slow provider returning late.
        }
        val helper = DirectBadgeHelper(port)
        try {
            port.call = true; port.changed()
            assertTrue(entered.await(8, TimeUnit.SECONDS))
            val detached = port.active.single().second
            port.call = false; port.callEpoch++; port.changed()
            assertTrue(signal!!.isCanceled)
            assertTrue(port.active.isEmpty()); assertTrue(helper.getCounts().isEmpty())
            port.callReader = { 0 }
            port.call = true; port.callEpoch++; port.changed()
            await("Fresh direct zero not exposed") { helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL] == 0 }
            release.countDown()
            SystemClock.sleep(100)
            assertEquals(0, helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL])
            val queries = port.calls.get()
            detached.onChange(false)
            SystemClock.sleep(450)
            assertEquals("Detached observer queried a new activation", queries, port.calls.get())
            port.active.single().second.onChange(false)
            port.call = false; port.callEpoch++; port.changed()
            val stoppedQueries = port.calls.get()
            SystemClock.sleep(450)
            assertEquals("Debounce survived opt-out", stoppedQueries, port.calls.get())
            assertTrue(helper.getCounts().isEmpty())
        } finally { release.countDown(); helper.close() }
    }

    fun unavailableIsNotZeroAndDisabledQueuedActionsNeverWrite() {
        val port = Port()
        val helper = DirectBadgeHelper(port)
        val first = CountDownLatch(1)
        port.callReader = { first.countDown(); null }
        try {
            port.call = true; port.changed()
            assertTrue(first.await(8, TimeUnit.SECONDS))
            assertNull(helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL])
            val errored = CountDownLatch(1)
            port.callReader = { errored.countDown(); throw IllegalStateException("Synthetic provider unavailable") }
            port.active.single().second.onChange(false)
            assertTrue(errored.await(8, TimeUnit.SECONDS))
            assertNull(helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL])
            val blocked = CountDownLatch(1)
            val unblock = CountDownLatch(1)
            port.callReader = { blocked.countDown(); assertTrue(unblock.await(10, TimeUnit.SECONDS)); 3 }
            port.active.single().second.onChange(false)
            assertTrue(blocked.await(8, TimeUnit.SECONDS))
            val finished = CountDownLatch(1)
            helper.clearMissedCalls({ true }) { finished.countDown() }
            port.action = false
            unblock.countDown()
            assertTrue(finished.await(8, TimeUnit.SECONDS))
            assertEquals(0, port.writes.get())
            port.action = true
            val canceled = CountDownLatch(1)
            helper.clearMissedCalls({ false }) { canceled.countDown() }
            assertTrue(canceled.await(8, TimeUnit.SECONDS)); assertEquals(0, port.writes.get())
            val acknowledged = CountDownLatch(1)
            helper.clearMissedCalls({ true }) { acknowledged.countDown() }
            assertTrue(acknowledged.await(8, TimeUnit.SECONDS)); assertEquals(1, port.writes.get())
            port.write = false
            helper.clearMissedCalls({ true }) {}
            assertEquals(1, port.writes.get())
        } finally { helper.close() }
    }

    fun reentrantConsumersCanReadAndRevokeWithoutNotificationUnderWorkerLocks() {
        val port = Port()
        lateinit var helper: DirectBadgeHelper
        val observed = CountDownLatch(1)
        val listener: () -> Unit = {
            val result = helper.getCounts()[DirectBadgeHelper.MUDITA_DIAL]
            if (result == 5) {
                port.call = false; port.callEpoch++; port.changed()
                assertTrue(helper.getCounts().isEmpty())
                observed.countDown()
            }
        }
        helper = DirectBadgeHelper(port)
        try {
            helper.addListener(listener)
            port.callReader = { 5 }
            port.call = true; port.changed()
            assertTrue("Listener could not observe/revoke the actual worker output", observed.await(8, TimeUnit.SECONDS))
            assertTrue(port.active.isEmpty())
        } finally { helper.removeListener(listener); helper.close() }
    }
}
