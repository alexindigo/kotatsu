package com.gezimos.katapult.screenshot

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Date
import java.util.TimeZone

class ScreenshotFileNameTest {
    @Test fun namesUseTheLocalCalendarAndExactPngFormat() {
        val at = Date(0)
        assertEquals("Screenshot_19691231-160000.png", screenshotFileName(at, TimeZone.getTimeZone("America/Los_Angeles")))
        assertEquals("Screenshot_19700101-090000.png", screenshotFileName(at, TimeZone.getTimeZone("Asia/Tokyo")))
    }
}
