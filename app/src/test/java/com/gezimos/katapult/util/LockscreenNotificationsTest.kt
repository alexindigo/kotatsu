package com.gezimos.katapult.util

import org.junit.Assert.assertEquals
import org.junit.Test

class LockscreenNotificationsTest {
    @Test fun visibleAllowedAppShowsWithoutAnOptIn() {
        assertEquals(mapOf("visible" to 2), filter("visible"))
    }

    @Test fun hiddenAppIsSilentByDefault() {
        assertEquals(emptyMap<String, Int>(), filter("hidden", hidden = setOf("hidden")))
    }

    @Test fun hiddenAppShowsAfterOptIn() {
        assertEquals(mapOf("hidden" to 2), filter("hidden", hidden = setOf("hidden"), notify = setOf("hidden")))
    }

    @Test fun excludedVisibleAppStaysSilent() {
        assertEquals(emptyMap<String, Int>(), filter("visible", excluded = setOf("visible")))
    }

    @Test fun excludedHiddenAppStaysSilentEvenAfterOptIn() {
        assertEquals(emptyMap<String, Int>(), filter("hidden", setOf("hidden"), setOf("hidden"), setOf("hidden")))
    }

    @Test fun nonPositiveCountsAreDroppedWithoutChangingTheInput() {
        val counts = linkedMapOf("zero" to 0, "negative" to -1, "positive" to 3)
        assertEquals(mapOf("positive" to 3), LockscreenNotifications.filter(counts, emptySet(), emptySet(), emptySet()))
        assertEquals(linkedMapOf("zero" to 0, "negative" to -1, "positive" to 3), counts)
    }

    @Test fun notificationOnlyPackageIsNotHiddenByAnUnrelatedLauncherChoice() {
        assertEquals(mapOf("android" to 2), filter("android", hidden = setOf("launcher")))
    }

    @Test fun hiddenByDefaultPackageUsesTheSameOptInAndExclusionRule() {
        val pkg = "org.fdroid.fdroid"
        assertEquals(emptyMap<String, Int>(), filter(pkg, hidden = setOf(pkg)))
        assertEquals(mapOf(pkg to 2), filter(pkg, hidden = setOf(pkg), notify = setOf(pkg)))
        assertEquals(emptyMap<String, Int>(), filter(pkg, setOf(pkg), setOf(pkg), setOf(pkg)))
    }

    private fun filter(
        pkg: String, excluded: Set<String> = emptySet(), hidden: Set<String> = emptySet(), notify: Set<String> = emptySet(),
    ) = LockscreenNotifications.filter(mapOf(pkg to 2), excluded, hidden, notify)
}
