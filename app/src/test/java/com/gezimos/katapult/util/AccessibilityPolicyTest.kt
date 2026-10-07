package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class AccessibilityPolicyTest {
    @Test fun actualConsumerCoordinatorReleasesItsOwnSourcesSubscriptionsAndInspectionOnDisableAndInactivity() {
        val badgeOwners = OwnerLeases {}
        val mediaOwners = OwnerLeases {}
        val independentHome = badgeOwners.acquire(Any())
        var badgeListener = false
        var mediaListener = false
        var overlay = false
        var pendingInspection = false
        val consumer = AccessibilityConsumers({ badgeOwners.acquire(Any()) }, { mediaOwners.acquire(Any()) },
            { badges, media -> badgeListener = badges; mediaListener = media },
            { overlay = false }, { pendingInspection = false })
        val selection = AccessibilitySelection(widget = true, media = true)
        val on = AccessibilityPolicy.demand(true, selection, true, false, false)
        consumer.reconcile(on)
        overlay = true; pendingInspection = true
        consumer.reconcile(on) // Repeated service events cannot acquire twice.
        assertEquals(2, badgeOwners.ownerCount); assertEquals(1, mediaOwners.ownerCount)
        consumer.reconcile(AccessibilityPolicy.demand(false, selection, true, false, false))
        assertFalse(overlay); assertFalse(pendingInspection)
        assertFalse(badgeListener); assertFalse(mediaListener)
        assertEquals(1, badgeOwners.ownerCount); assertFalse(mediaOwners.demanded)
        consumer.reconcile(on)
        overlay = true; pendingInspection = true
        consumer.reconcile(AccessibilityPolicy.demand(true, selection, false, false, false))
        assertFalse(overlay); assertFalse(pendingInspection)
        assertFalse(consumer.badgesActive); assertFalse(consumer.mediaActive)
        assertFalse(consumer.inspectionActive)
        consumer.close(); consumer.close()
        assertTrue("Service double-close released HOME's owner", badgeOwners.demanded)
        independentHome.close()
    }
    @Test fun pinOcclusionClearsCollectorsWithoutForgettingSelectedInspectionUntilUnlockOrOptOut() {
        var pin = true
        var reads = 0
        val consumer = AccessibilityConsumers({ AutoCloseable {} }, { AutoCloseable {} }, { _, _ -> }, {}, { pin = false })
        val selected = AccessibilitySelection(widget = true)
        val occluded = AccessibilityPolicy.demand(true, selected, false, false, false)
        val inspection = AccessibilityPolicy.demand(true, selected, true, false, false).inspectPin
        consumer.reconcile(occluded.copy(inspectPin = inspection))
        assertTrue(pin); assertTrue(consumer.inspectionActive)
        if (consumer.inspectionActive) reads++
        assertEquals(1, reads)
        consumer.reconcile(AccessibilityPolicy.demand(false, selected, true, false, false))
        assertFalse(pin); assertFalse(consumer.inspectionActive)
        if (consumer.inspectionActive) reads++
        assertEquals(1, reads)
    }
    @Test fun connectedServiceAndClockCoverAloneNeverAuthorizeBadgesMediaOrPinInspection() {
        val selected = AccessibilitySelection(clockCover = true)
        val demand = AccessibilityPolicy.demand(true, selected, true, true, false)
        assertTrue(demand.clockCover)
        assertFalse(demand.badges); assertFalse(demand.media); assertFalse(demand.inspectPin)
        assertFalse(demand.lockAction); assertFalse(demand.powerEntry)
        val empty = AccessibilityPolicy.demand(true, AccessibilitySelection(), true, true, true)
        assertFalse(empty.badges); assertFalse(empty.media); assertFalse(empty.inspectPin)
    }

    @Test fun optOutOrDisconnectMakesEveryActionAndDemandIneligibleWithoutChangingSelection() {
        val selected = AccessibilitySelection(true, true, true, true, true, true, true)
        val active = AccessibilityPolicy.demand(true, selected, true, true, true)
        assertTrue(active.badges); assertTrue(active.media); assertTrue(active.inspectPin)
        assertTrue(active.clockCover); assertTrue(active.lockAction); assertTrue(active.powerEntry)
        val disabled = AccessibilityPolicy.demand(false, selected, true, true, true)
        assertEquals(AccessibilityDemand(false, false, false, false, false, false), disabled)
        assertEquals(selected, selected.copy())
        assertEquals(active, AccessibilityPolicy.demand(true, selected, true, true, true))
    }

    @Test fun inactiveLockscreenReleasesCollectorsAndInspectionButRetainsDeliberateSelectedLauncherAction() {
        val selected = AccessibilitySelection(widget = true, media = true, doubleTapLock = true)
        val foreground = AccessibilityPolicy.demand(true, selected, false, true, false)
        assertFalse(foreground.badges); assertFalse(foreground.media); assertFalse(foreground.inspectPin)
        assertTrue(foreground.lockAction)
        assertFalse(AccessibilityPolicy.demand(true, selected, false, false, false).lockAction)
        val saver = AccessibilitySelection(screensaver = true, powerScreensaver = true)
        assertFalse(AccessibilityPolicy.demand(true, saver, false, true, false).lockAction)
        assertTrue(AccessibilityPolicy.demand(true, saver, false, false, true).lockAction)
    }
}
