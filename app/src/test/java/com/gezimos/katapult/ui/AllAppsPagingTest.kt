package com.gezimos.katapult.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class AllAppsPagingTest {
    @Test fun arrowsImplyHorizontalPages() {
        assertEquals(SwipeAxis.HORIZONTAL, allAppsSwipeAxis(hideArrowButtons = false))
    }

    @Test fun hiddenArrowsImplyVerticalPages() {
        assertEquals(SwipeAxis.VERTICAL, allAppsSwipeAxis(hideArrowButtons = true))
    }
}
