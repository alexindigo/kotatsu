package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class ReplayDeliveryTest {
    private data class Info(val packages: List<String>?, val timeout: Long, val flags: Int = 71)
    private class Port : ReplayServiceInfoPort<Info> {
        val static = Info(listOf("com.android.systemui"), 100)
        var value = static
        val writes = mutableListOf<Info>()
        var failNarrow = false
        var failWide = false
        override fun staticInfo() = static
        override fun currentInfo() = value.copy(packages = value.packages?.toList())
        override fun widened(info: Info) = info.copy(packages = null, timeout = 0)
        override fun apply(info: Info) {
            writes += info
            if (failNarrow && info.packages != null) throw IllegalStateException("restore failed")
            value = info
            if (failWide && info.packages == null) throw IllegalStateException("partial widen failed")
        }
    }

    @Test fun successfulDispatchRestoresBeforeInjectionAndCompletionDoesNotWidenAgain() {
        val port = Port()
        val lease = ReplayDelivery(port) { throw AssertionError(it) }
        // Capture the actual original, not a hardcoded narrow replacement.
        val original = Info(listOf("com.android.systemui", "another.system.package"), 250, 93)
        port.value = original
        val token = lease.begin()
        assertEquals(Info(null, 0, 93), port.value)
        assertTrue(lease.dispatched(token))
        assertEquals(original, port.value)
        assertTrue(lease.busy)
        lease.end(token)
        assertFalse(lease.busy)
        assertEquals(listOf(port.static, Info(null, 0, 93), original), port.writes)
        // Home confirmation, widget launch and replay share one connection owner.
        val home = lease.begin()
        val widget = lease.begin()
        val replay = lease.begin()
        lease.end(home)
        assertEquals(Info(null, 0, 93), port.value)
        assertTrue(lease.dispatched(replay))
        assertEquals(Info(null, 0, 93), port.value)
        lease.end(widget)
        assertEquals(original, port.value)
        assertTrue(lease.current(replay))
        val nextHome = lease.begin()
        lease.end(replay)
        lease.end(home) // A stale observer must not release the new Home lease.
        assertEquals(Info(null, 0, 93), port.value)
        lease.end(nextHome)
        assertEquals(original, port.value)
        assertFalse(lease.busy)
    }

    @Test fun rejectedDispatchAndCancelledInjectionRestoreAndReleaseTheirGeneration() {
        for (accepted in listOf(false, true)) {
            val port = Port()
            val lease = ReplayDelivery(port) { throw AssertionError(it) }
            val token = lease.begin()
            assertTrue(lease.dispatched(token))
            assertEquals(port.static, port.value)
            // Rejection ends synchronously; cancellation ends through the callback.
            if (accepted) lease.cancel() else lease.end(token)
            assertFalse(lease.busy)
            assertFalse(lease.current(token))
            assertEquals(listOf(port.static, Info(null, 0), port.static), port.writes)
        }
    }

    @Test fun timeoutScreenOffIneligibleInterruptAndDestroyRestoreWithoutDispatch() {
        val port = Port()
        val lease = ReplayDelivery(port) { throw AssertionError(it) }
        repeat(5) {
            val token = lease.begin()
            assertEquals(Info(null, 0), port.value)
            lease.cancel() // Shared exit used by all five ReplayHandoff cancellation paths.
            assertEquals(port.static, port.value)
            assertFalse(lease.busy)
            lease.end(token) // A queued callback cannot alter the restored state.
        }
        assertEquals(listOf(port.static) + List(5) { listOf(Info(null, 0), port.static) }.flatten(), port.writes)
    }

    @Test fun staleCallbacksCannotRestoreOrReleaseAnotherHandoff() {
        val port = Port()
        val lease = ReplayDelivery(port) { throw AssertionError(it) }
        val first = lease.begin()
        lease.cancel()
        val second = lease.begin()
        lease.end(first)
        assertFalse(lease.dispatched(first))
        assertTrue(lease.busy)
        assertEquals(Info(null, 0), port.value)
        lease.end(second)
        assertEquals(port.static, port.value)
    }

    @Test fun partialWidenFailureRollsBackBeforeAnotherHandoff() {
        val port = Port()
        val lease = ReplayDelivery(port) { throw AssertionError(it) }
        port.failWide = true
        assertThrows(IllegalStateException::class.java) { lease.begin() }
        assertFalse(lease.busy)
        assertEquals(port.static, port.value)
        assertEquals(listOf(port.static, Info(null, 0), port.static), port.writes)
        port.failWide = false
        lease.end(lease.begin())
        assertEquals(port.static, port.value)
    }

    @Test fun failedRestoreStopsNewReplaysAndReconnectReestablishesStaticDelivery() {
        for (dispatched in listOf(false, true)) {
            val port = Port()
            val errors = mutableListOf<RuntimeException>()
            val lease = ReplayDelivery(port, errors::add)
            val token = lease.begin()
            port.failNarrow = true
            if (dispatched) assertFalse(lease.dispatched(token))
            lease.cancel()
            assertEquals(1, errors.size)
            assertFalse(lease.busy)
            assertThrows(IllegalStateException::class.java) { lease.begin() }
            assertEquals(Info(null, 0), port.value)
            port.failNarrow = false
            val reconnected = ReplayDelivery(port) { throw AssertionError(it) }
            assertEquals(port.static, port.value)
            reconnected.end(reconnected.begin())
            assertEquals(port.static, port.value)
        }
    }

    @Test fun failedConnectResetDoesNotAuthorizeWidening() {
        val port = Port().apply { failNarrow = true }
        val errors = mutableListOf<RuntimeException>()
        val lease = ReplayDelivery(port, errors::add)
        assertEquals(1, errors.size)
        assertThrows(IllegalStateException::class.java) { lease.begin() }
        assertEquals(listOf(port.static), port.writes)
    }
}
