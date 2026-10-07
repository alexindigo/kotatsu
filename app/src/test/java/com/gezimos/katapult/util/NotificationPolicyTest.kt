package com.gezimos.katapult.util

import com.gezimos.katapult.service.BadgeNotification
import com.gezimos.katapult.service.NotificationBadges
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun optOutPreventsEnumerationAndCallbackInspectionClearsCountsAndTimesAndReenablesConnected() {
        var enabled = false
        var revision = 0L
        var enumerations = 0
        var inspections = 0
        var notifications = 0
        val cache = NotificationBadges({ enabled }, { revision }, {
            enumerations++; listOf(BadgeNotification("synthetic-key", "fixture.package", 123L, true, false))
        }, { true }, { fail("Unexpected cancellation") }, { notifications++ })
        cache.posted { inspections++; BadgeNotification("other", "fixture.package", 456L, true, false) }
        assertEquals(0, inspections); assertEquals(0, enumerations)
        enabled = true; revision++; cache.reconcile()
        assertEquals(mapOf("fixture.package" to 1), cache.counts())
        assertEquals(mapOf("fixture.package" to 123L), cache.times())
        enabled = false; revision++; cache.reconcile()
        assertTrue(cache.counts().isEmpty()); assertTrue(cache.times().isEmpty())
        cache.removed { inspections++; "synthetic-key" }
        assertEquals(0, inspections)
        enabled = true; revision++; cache.reconcile()
        assertEquals(2, enumerations)
        assertEquals(mapOf("fixture.package" to 1), cache.counts())
        assertTrue(notifications >= 3)
    }

    @Test fun disabledDismissalAndSourceRoutingNeverCancelOrFabricateData() {
        var allowed = false
        var canceled = 0
        val cache = NotificationBadges({ true }, { 0L }, {
            listOf(BadgeNotification("one", "fixture.package", 1L, true, false),
                BadgeNotification("summary", "fixture.package", 2L, true, true),
                BadgeNotification("ongoing", "fixture.package", 3L, false, false))
        }, { allowed }, { canceled += it.size }, {})
        cache.dismiss("fixture.package")
        assertEquals(0, canceled); assertEquals(1, cache.counts()["fixture.package"])
        allowed = true; cache.dismiss("fixture.package")
        assertEquals(1, canceled)
        assertEquals("Cancellation must wait for source removal", 1, cache.counts()["fixture.package"])
        cache.removed { "one" }
        assertTrue(cache.counts().isEmpty()); assertTrue(cache.times().isEmpty())
    }

    @Test fun lateEnumerationAndCallbackCannotPublishAcrossOptOutAndReenable() {
        var revision = 0L
        val cache = NotificationBadges({ true }, { revision }, {
            revision += 2
            listOf(BadgeNotification("late", "fixture.package", 123L, true, false))
        }, { false }, {}, {})
        cache.reconcile()
        // Exercise a callback whose policy flips while its source object is read.
        cache.posted { revision += 2; BadgeNotification("late-callback", "fixture.package", 456L, true, false) }
        assertTrue(cache.counts().isEmpty())
        assertTrue(cache.times().isEmpty())
    }
}
