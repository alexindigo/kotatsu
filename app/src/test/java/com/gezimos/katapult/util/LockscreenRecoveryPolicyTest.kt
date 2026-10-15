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
            AccessibilitySelection(true, true, true, true, true, true, true, weather = true, panel = true)))
    }

    @Test fun panelAloneIsRecoverableAndInspectsPinWithoutCollectingBadgesOrMedia() {
        val selected = AccessibilitySelection(panel = true)
        assertEquals(listOf(AccessibilityFeature.PANEL), AccessibilityPolicy.serviceFeatures(selected))
        assertEquals(AccessibilityDemand(false, false, true, false, false, false),
            AccessibilityPolicy.demand(true, selected, true, false, false))
        assertFalse(AccessibilityPolicy.demand(false, selected, true, false, false).inspectPin)
        assertFalse(AccessibilityPolicy.demand(true, selected, false, false, false).inspectPin)
        assertTrue(AccessibilityPolicy.serviceFeatures(selected.copy(panel = false)).isEmpty())
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

    @Test fun inspectionFailureHidesOverlaysButSuccessfulNegativeInspectionDoesNot() {
        assertTrue(AccessibilityPolicy.pinShowing { throw SecurityException("focus unavailable") })
        assertTrue(AccessibilityPolicy.pinShowing { throw IllegalStateException("node unavailable") })
        assertFalse(AccessibilityPolicy.pinShowing { false })
        assertTrue(AccessibilityPolicy.pinShowing { true })
    }
}
