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
            AccessibilitySelection(true, true, true, true, true, true, true)))
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
