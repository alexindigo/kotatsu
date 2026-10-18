package com.alexindigo.kotatsu.weather

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTargetTest {
    @Test fun appIconOpensWhatTheWidgetCountAllows() {
        assertEquals(SettingsTarget.NoWidget, settingsTarget(intArrayOf()))
        assertEquals(SettingsTarget.One(7), settingsTarget(intArrayOf(7)))
        assertEquals(SettingsTarget.Choose(listOf(3, 7, 12)), settingsTarget(intArrayOf(12, 3, 7)))
    }
}
