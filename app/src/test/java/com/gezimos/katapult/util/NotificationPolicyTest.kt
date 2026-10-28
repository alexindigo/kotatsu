package com.gezimos.katapult.util

import com.gezimos.katapult.service.BadgeNotification
import com.gezimos.katapult.service.NotificationBadges
import org.junit.Assert.*
import org.junit.Test

class NotificationPolicyTest {
    @Test fun unreadTotalsUseEachChatNumberAndKeepUnnumberedNotificationsAndPackagesIndependent() {
        val cache = NotificationBadges({ true }, { 0L }, {
            listOf(BadgeNotification("chat-one", "chat.app", 1L, true, false, number = 3),
                BadgeNotification("chat-two", "chat.app", 2L, true, false, number = 2),
                BadgeNotification("ordinary", "other.app", 3L, true, false),
                BadgeNotification("negative", "negative.app", 4L, true, false, number = -3),
                BadgeNotification("summary", "chat.app", 5L, true, true, number = 100),
                BadgeNotification("ongoing", "chat.app", 6L, false, false, number = 200),
                BadgeNotification("noise", "android", 7L, true, false, number = 300))
        }, { true }, {}, {})
        assertEquals(mapOf("chat.app" to 5, "other.app" to 1, "negative.app" to 1), cache.counts())
        assertEquals(mapOf("chat.app" to 2L, "other.app" to 3L, "negative.app" to 4L), cache.times())
        cache.posted { BadgeNotification("chat-one", "chat.app", 8L, true, false, number = 7) }
        assertEquals(9, cache.counts()["chat.app"])
        cache.posted { BadgeNotification("chat-one", "chat.app", 9L, true, false) }
        assertEquals(3, cache.counts()["chat.app"])
        cache.removed { "chat-two" }
        assertEquals(1, cache.counts()["chat.app"])
        cache.removed { "chat-one" }
        assertFalse("Removing the last chat left a badge", "chat.app" in cache.counts())
    }

    @Test fun updatedSummaryOrNonClearableNotificationCannotRetainItsOldUnreadContribution() {
        val cache = NotificationBadges({ true }, { 0L }, { emptyList() }, { true }, {}, {})
        val notification = BadgeNotification("changing", "chat.app", 1L, true, false, number = 3)
        cache.posted { notification }
        assertEquals(3, cache.counts()["chat.app"])
        cache.posted { notification.copy(summary = true, number = 100) }
        assertTrue(cache.counts().isEmpty())
        assertTrue(cache.times().isEmpty())
        cache.posted { notification }
        cache.posted { notification.copy(clearable = false, number = 100) }
        assertTrue(cache.counts().isEmpty())
        assertTrue(cache.times().isEmpty())
    }

    @Test fun untrustedUnreadNumbersCannotOverflowToANegativeBadge() {
        val cache = NotificationBadges({ true }, { 0L }, {
            listOf(BadgeNotification("one", "chat.app", 1L, true, false, number = Int.MAX_VALUE),
                BadgeNotification("two", "chat.app", 2L, true, false, number = 2))
        }, { true }, {}, {})
        assertEquals(Int.MAX_VALUE, cache.counts()["chat.app"])
    }

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
