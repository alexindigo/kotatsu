package com.gezimos.katapult.ui

import com.gezimos.katapult.R
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentAppsLabelTest {
    @Test fun android13AndEarlierKeepClose() {
        (30..33).forEach { apiLevel ->
            assertEquals("API $apiLevel", R.string.recent_close, recentAppActionLabel(apiLevel))
        }
    }

    @Test fun android14AndLaterSayHide() {
        listOf(34, 35, 36, 100).forEach { apiLevel ->
            assertEquals("API $apiLevel", R.string.recent_hide, recentAppActionLabel(apiLevel))
        }
    }
}
