package com.gezimos.katapult.util

import org.junit.Assert.*
import org.junit.Test

class LockscreenRecoveryPolicyTest {
    @Test fun recoveryListIncludesMusicAndMessageAndPreservesFeatureGates() {
        assertEquals(listOf(AccessibilityFeature.MEDIA),
            AccessibilityPolicy.serviceFeatures(AccessibilitySelection(media = true)))
        assertEquals(listOf(AccessibilityFeature.MESSAGE),
            AccessibilityPolicy.serviceFeatures(AccessibilitySelection(message = true)))
        assertTrue(AccessibilityPolicy.serviceFeatures(AccessibilitySelection()).isEmpty())
        assertTrue(AccessibilityPolicy.serviceFeatures(AccessibilitySelection(screensaver = true)).isEmpty())
        assertTrue(AccessibilityPolicy.serviceFeatures(AccessibilitySelection(powerScreensaver = true)).isEmpty())
        assertEquals(AccessibilityFeature.entries.toList(), AccessibilityPolicy.serviceFeatures(
            AccessibilitySelection(true, true, true, true, true, true, true, weather = true, widgets = true)))
    }

    @Test fun widgetsAloneAreRecoverableAndInspectPinWithoutCollectingBadgesOrMedia() {
        val selected = AccessibilitySelection(widgets = true)
        assertEquals(listOf(AccessibilityFeature.LOCKSCREEN_WIDGETS), AccessibilityPolicy.serviceFeatures(selected))
        assertEquals(AccessibilityDemand(false, false, true, false, false, false),
            AccessibilityPolicy.demand(true, selected, true, false, false))
        assertFalse(AccessibilityPolicy.demand(false, selected, true, false, false).inspectPin)
        assertFalse(AccessibilityPolicy.demand(true, selected, false, false, false).inspectPin)
        assertTrue(AccessibilityPolicy.serviceFeatures(selected.copy(widgets = false)).isEmpty())
    }

    @Test fun weatherAloneIsNamedForRecoveryAndInspectsPinWithoutBadgeOrMediaDemand() {
        val selected = AccessibilitySelection(weather = true)
        assertEquals(listOf(AccessibilityFeature.WEATHER), AccessibilityPolicy.serviceFeatures(selected))
        val demand = AccessibilityPolicy.demand(true, selected, true, false, false)
        assertTrue(demand.inspectPin)
        assertFalse(demand.badges)
        assertFalse(demand.media)
        assertFalse(demand.clockCover)
        assertFalse(demand.lockAction)
        assertFalse(demand.powerEntry)
        assertFalse(AccessibilityPolicy.demand(false, selected, true, false, false).inspectPin)
        assertFalse(AccessibilityPolicy.demand(true, selected, false, false, false).inspectPin)
    }

    @Test fun onlyVisibleSystemUiEditTextFocusIsAPinField() {
        assertTrue(AccessibilityPolicy.isPinField(StringBuilder("com.android.systemui"), true, "android.widget.EditText"))
        for (pkg in listOf(null, "com.android.settings", "other.app", "com.android.systemui.extra")) {
            assertFalse(AccessibilityPolicy.isPinField(pkg, true, "android.widget.EditText"))
        }
        assertFalse(AccessibilityPolicy.isPinField("com.android.systemui", false, "android.widget.EditText"))
        assertFalse(AccessibilityPolicy.isPinField("com.android.systemui", true, "android.widget.TextView"))
        assertFalse(AccessibilityPolicy.isPinField("com.android.systemui", true, null))
    }

    @Test fun onlyTheBareLockscreenWindowLeavesTheLockscreenUncovered() {
        val sysui = "com.android.systemui"
        assertFalse(AccessibilityPolicy.lockscreenCovered(StringBuilder(sysui), activeHasLockscreenPanel = true, pinFieldFocused = false))
        // The PIN pad lives inside the lock-screen window.
        assertTrue(AccessibilityPolicy.lockscreenCovered(sysui, activeHasLockscreenPanel = true, pinFieldFocused = true))
        // Staging phone 2026-10-16: the fingerprint lockout notice is its own System UI window without the panel.
        assertTrue(AccessibilityPolicy.lockscreenCovered(sysui, activeHasLockscreenPanel = false, pinFieldFocused = false))
        // An app shown over the lock screen (alarm, call) or no active window at all.
        for (pkg in listOf("com.mudita.alarm", null)) {
            assertTrue(AccessibilityPolicy.lockscreenCovered(pkg, activeHasLockscreenPanel = true, pinFieldFocused = false))
        }
    }

    @Test fun inspectionFailureHidesOverlaysButSuccessfulNegativeInspectionDoesNot() {
        assertTrue(AccessibilityPolicy.coveredOrUnknown { throw SecurityException("focus unavailable") })
        assertTrue(AccessibilityPolicy.coveredOrUnknown { throw IllegalStateException("node unavailable") })
        assertFalse(AccessibilityPolicy.coveredOrUnknown { false })
        assertTrue(AccessibilityPolicy.coveredOrUnknown { true })
    }
}
