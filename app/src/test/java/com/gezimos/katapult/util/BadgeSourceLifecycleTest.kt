package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class BadgeSourceLifecycleTest {
    @Test fun disabledReaderIsNeverCalledAndLateResultNeverReachesConsumer() {
        val source = SourceGeneration<Int>()
        val work = SourceRead(source)
        var queries = 0
        assertFalse(work.run(source.epoch, { false }) { queries++; 5 })
        assertEquals(0, queries)
        source.reconcile(true)
        val epoch = source.epoch
        assertFalse(work.run(epoch, { true }) {
            queries++
            source.reconcile(false)
            source.reconcile(true)
            5
        })
        assertEquals(1, queries)
        assertNull(source.value)
        assertTrue(work.run(source.epoch, { true }) { 0 })
        assertEquals(0, source.value)
    }
    @Test fun idempotentOwnerClosesCannotReleaseAnotherConsumer() {
        var changes = 0
        val leases = OwnerLeases { changes++ }
        val home = Any()
        val lockscreen = Any()
        val one = leases.acquire(home)
        assertSame(one, leases.acquire(home))
        val two = leases.acquire(lockscreen)
        assertEquals(2, leases.ownerCount)
        one.close(); one.close()
        assertTrue(leases.demanded)
        assertEquals(1, leases.ownerCount)
        two.close(); two.close()
        assertFalse(leases.demanded)
        assertEquals(4, changes)
    }

    @Test fun disableRevokeAndReenableRejectLatePublication() {
        val source = SourceGeneration<Int>()
        source.reconcile(true)
        val old = source.epoch
        assertTrue(source.publish(old, true, 5))
        source.reconcile(false)
        assertNull(source.value)
        assertFalse(source.publish(old, true, 7))
        source.reconcile(true)
        assertFalse(source.publish(old, true, 7))
        assertFalse(source.publish(source.epoch, false, 7))
        assertTrue(source.publish(source.epoch, true, 0))
        assertEquals(0, source.value)
        source.invalidate()
        assertNull(source.value)
    }

    @Test fun directZeroIsAuthoritativeButUnavailableAllowsFallbackWithoutDoubleCounting() {
        assertEquals(BadgeRoute(BadgeSource.DIRECT_CALLS, 0), BadgeRouting.select(BadgeSource.DIRECT_CALLS, 0, 3, true))
        val fallback = BadgeRouting.select(BadgeSource.DIRECT_CALLS, null, 3, true)
        assertEquals(BadgeRoute(BadgeSource.NOTIFICATIONS, 3), fallback)
        assertFalse(BadgeRouting.canClear(fallback, false, true))
        assertTrue(BadgeRouting.canClear(fallback, true, false))
        assertFalse(BadgeRouting.canClear(BadgeRoute(BadgeSource.DIRECT_SMS, 5), true, true))
        assertEquals(BadgeRoute(BadgeSource.UNAVAILABLE, 0), BadgeRouting.select(null, null, 5, false))
    }

    @Test fun permissionRequestsAreOneShotAndDoNotDependOnCollectionEpochs() {
        val requests = ActionRequests()
        val source = SourceGeneration<Int>()
        source.reconcile(true)
        val request = requests.begin("synthetic", BadgeSource.DIRECT_CALLS)
        source.reconcile(false) // Ordinary permission-dialog pause stops collection only.
        assertEquals(request, requests.consume(request.id))
        assertTrue(requests.current(request.id))
        assertNull(requests.consume(request.id))
        val stale = requests.begin("synthetic", BadgeSource.DIRECT_CALLS)
        val current = requests.begin("synthetic", BadgeSource.DIRECT_CALLS)
        assertNull(requests.consume(stale.id))
        requests.cancel() // Opt-out, denial, cancellation or grant revocation.
        assertFalse(requests.current(current.id))
        assertNull(requests.consume(current.id))
    }
}
