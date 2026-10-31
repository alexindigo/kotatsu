package com.gezimos.katapult.lockscreen

import org.junit.Assert.*
import org.junit.Test

class VisibleWidgetPictureTest {
    @Test fun decisionHasAnExclusiveFourHourBoundaryAndRejectsUnknownOrBackwardsTime() {
        assertFalse(reapplyVisiblePicture(null, 0))
        assertFalse(reapplyVisiblePicture(10, 9))
        assertTrue(reapplyVisiblePicture(10, 10))
        assertTrue(reapplyVisiblePicture(10, 10 + 4 * 3600_000L - 1))
        assertFalse(reapplyVisiblePicture(10, 10 + 4 * 3600_000L))
        assertFalse(reapplyVisiblePicture(10, 10 + 5 * 3600_000L))
    }

    @Test fun hiddenReceiptsNeverReplaceOrRejuvenateTheLastVisiblePicture() {
        var now = 0L
        val cache = VisibleWidgetPicture<String> { now }
        cache.receive("initial hidden")
        assertNull(cache.visibility(true))
        cache.receive("visible")
        cache.visibility(false)
        now = 3600_000
        cache.receive("hidden")
        assertEquals("visible", cache.visibility(true))
        cache.visibility(false)
        now = 4 * 3600_000
        cache.receive("hidden again")
        assertNull(cache.visibility(true))
    }

    @Test fun replayDoesNotCountAsAReceiptAndAnActualVisibleUpdateDoes() {
        var now = 0L
        val cache = VisibleWidgetPicture<String> { now }
        cache.visibility(true); cache.receive("old"); cache.visibility(false)
        now = 3600_000
        assertEquals("old", cache.visibility(true))
        assertNull(cache.visibility(true))
        cache.visibility(false)
        now = 4 * 3600_000
        assertNull(cache.visibility(true))
        cache.receive("fresh"); cache.visibility(false)
        assertEquals("fresh", cache.visibility(true))
        cache.receive(null); cache.visibility(false)
        assertNull(cache.visibility(true))
    }
}
