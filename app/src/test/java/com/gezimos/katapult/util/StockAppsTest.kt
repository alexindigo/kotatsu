package com.gezimos.katapult.util

import com.gezimos.katapult.model.AppModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StockAppsTest {
    @Test fun muditasRuleOnTheKompakt() {
        assertTrue(StockApps.isStock(true, true, "com.android.settings"))
        assertTrue(StockApps.isStock(true, true, "org.chromium.webview_shell"))
        assertFalse("Mudita's own apps", StockApps.isStock(true, true, "com.mudita.calendar"))
        assertFalse("User-installed apps", StockApps.isStock(true, false, "com.b44t.messenger"))
    }

    @Test fun noStockAppsOffTheKompakt() {
        assertFalse(StockApps.isStock(false, true, "com.android.settings"))
    }

    @Test fun stockAppsStartHiddenAndShowOnlyWhenChosen() {
        val settings = AppModel("com.android.settings", "Settings", ".Settings", stock = true)
        val delta = AppModel("com.b44t.messenger", "Delta Chat", ".Main")
        assertTrue(AppVisibility.isHidden(settings, emptySet(), emptySet()))
        assertFalse(AppVisibility.isHidden(settings, emptySet(), setOf("com.android.settings")))
        assertFalse(AppVisibility.isHidden(delta, emptySet(), emptySet()))
        assertTrue(AppVisibility.isHidden(delta, setOf("com.b44t.messenger"), emptySet()))
    }

    @Test fun storeKeyboardAndUpdaterStartHidden() {
        for (pkg in listOf("org.fdroid.basic", "org.fdroid.fdroid", "org.futo.inputmethod.latin", "com.mudita.system.updater")) {
            val app = AppModel(pkg, pkg, ".Main")
            assertTrue(pkg, AppVisibility.hiddenByDefault(app))
            assertTrue(pkg, AppVisibility.isHidden(app, emptySet(), emptySet()))
            assertFalse(pkg, AppVisibility.isHidden(app, emptySet(), setOf(pkg)))
        }
        val shortcut = AppModel("org.fdroid.basic", "Search", "", shortcutId = "search")
        assertFalse("A pinned shortcut is the user's own choice", AppVisibility.hiddenByDefault(shortcut))
        assertFalse(AppVisibility.hiddenByDefault(AppModel("com.mudita.calc", "Calculator", ".Main")))
    }
}
