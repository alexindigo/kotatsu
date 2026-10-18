package com.gezimos.katapult.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfLaunchTest {
    private val self = "com.alexindigo.kotatsu"
    private val main = "com.gezimos.katapult.MainActivity"

    @Test fun kotatsusOwnIconInsideKotatsuOpensSettings() {
        assertTrue(SelfLaunch.opensSettings(self, main, self, main))
        assertTrue("Package-only targets (home slots, shortcut fallback)", SelfLaunch.opensSettings(self, main, self, ""))
    }

    @Test fun otherKotatsuEntriesAndOtherAppsLaunchNormally() {
        assertFalse(SelfLaunch.opensSettings(self, main, self, "com.gezimos.katapult.ScreensaverAlias"))
        assertFalse("Another Kotatsu build is another app", SelfLaunch.opensSettings(self, main, "$self.debug", main))
        assertFalse(SelfLaunch.opensSettings(self, main, "com.mudita.calendar", ""))
    }
}
