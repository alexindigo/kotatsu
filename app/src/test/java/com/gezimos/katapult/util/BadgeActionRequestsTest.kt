package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class BadgeActionRequestsTest {
    @Test fun firstPermissionGrantRevisionDoesNotCancelPendingDialogAndResultIsOneShot() {
        val f = Fixture()
        val id = f.actions.request("synthetic")!!
        f.writeGrant = true
        f.revision++ // WRITE_CALL_LOG callback arrives before the dialog result.
        f.actions.reconcile()
        assertTrue("Permission request lost its demand before result", f.leases.demanded)
        f.actions.result(id, true)
        f.actions.result(id, true)
        assertEquals(1, f.queued.size)
        f.drain()
        assertEquals(1, f.writes)
        assertFalse(f.leases.demanded)
    }

    @Test fun writeAccessLossAndRegainCancelQueuedAcknowledgmentBeforeDrain() {
        val f = Fixture()
        f.writeGrant = true
        f.actions.request("synthetic")
        f.writeGrant = false
        f.revision++
        f.actions.reconcile()
        assertFalse(f.leases.demanded)
        f.writeGrant = true
        f.revision++
        f.actions.reconcile()
        f.drain()
        assertEquals(0, f.writes)
    }

    @Test fun pendingDialogStillCancelsOnOptOutOrSourceChange() {
        for (reason in listOf("choice", "source")) {
            val f = Fixture()
            val id = f.actions.request("synthetic")!!
            if (reason == "choice") f.actionChoice = false
            else f.source = BadgeRoute(BadgeSource.NOTIFICATIONS, 2)
            f.revision++
            f.actions.reconcile()
            assertFalse(reason, f.leases.demanded)
            f.writeGrant = true
            f.actionChoice = true
            f.source = BadgeRoute(BadgeSource.DIRECT_CALLS, 2)
            f.actions.result(id, true)
            f.drain()
            assertEquals(reason, 0, f.writes)
        }
    }

    private class Fixture {
        var source = BadgeRoute(BadgeSource.DIRECT_CALLS, 2)
        var collectionChoice = true
        var actionChoice = true
        var writeGrant = false
        var dismissal = false
        var revision = 0L
        var writes = 0
        var cancels = 0
        val leases = OwnerLeases {}
        val queued = mutableListOf<Pair<() -> Boolean, () -> Unit>>()
        val actions = BadgeActions({ source }, { dismissal }, { collectionChoice && actionChoice },
            { writeGrant }, { revision }, { leases.acquire(Any()) }, { cancels++ }, { check, finish -> queued.add(check to finish) })
        fun drain() {
            val work = queued.toList(); queued.clear()
            work.forEach { (check, finish) -> if (check()) writes++; finish() }
        }
    }

    @Test fun ordinaryPermissionPauseResumeRetainsRequestButDenialAndDuplicateResultDoNotWrite() {
        val f = Fixture()
        val id = f.actions.request("synthetic")!!
        // Independent HOME collection can stop; the deliberate request still owns demand.
        assertTrue(f.leases.demanded)
        f.writeGrant = true
        f.actions.result(id, true); f.actions.result(id, true)
        assertEquals(1, f.queued.size)
        f.drain(); assertEquals(1, f.writes); assertFalse(f.leases.demanded)
        f.writeGrant = false
        val denied = f.actions.request("synthetic")!!
        f.actions.result(denied, false)
        f.writeGrant = true; f.actions.result(denied, true); f.drain()
        assertEquals(1, f.writes); assertFalse(f.leases.demanded)
    }

    @Test fun optOutRevocationSourceChangeAndSupersessionInvalidateAtActualQueuedWrite() {
        for (reason in listOf("source", "action", "grant", "revision", "fallback", "cancel")) {
            val f = Fixture(); f.writeGrant = true
            f.actions.request("synthetic")
            when (reason) {
                "source" -> f.collectionChoice = false
                "action" -> f.actionChoice = false
                "grant" -> f.writeGrant = false
                "revision" -> f.revision++
                "fallback" -> f.source = BadgeRoute(BadgeSource.NOTIFICATIONS, 3)
                "cancel" -> f.actions.close()
            }
            f.drain(); assertEquals(reason, 0, f.writes); assertFalse(f.leases.demanded)
        }
        val f = Fixture(); f.writeGrant = true
        f.actions.request("old")
        f.actions.request("new")
        assertEquals(1, f.leases.ownerCount)
        val old = f.queued.removeAt(0)
        assertFalse(old.first()); old.second()
        assertTrue("Old completion closed new request's owner", f.leases.demanded)
        f.drain(); assertEquals(1, f.writes); assertFalse(f.leases.demanded)
    }

    @Test fun smsUnavailableAndDisabledNotificationActionsHaveNoWriteOrCosmeticClear() {
        val f = Fixture()
        for (source in listOf(BadgeSource.DIRECT_SMS, BadgeSource.UNAVAILABLE, BadgeSource.NOTIFICATIONS)) {
            f.source = BadgeRoute(source, 2)
            f.actions.request("synthetic"); f.drain()
            assertEquals(0, f.writes); assertEquals(0, f.cancels)
        }
        f.dismissal = true; f.actions.request("synthetic")
        assertEquals(1, f.cancels)
        assertEquals(BadgeRoute(BadgeSource.NOTIFICATIONS, 2), f.source)
    }
}
