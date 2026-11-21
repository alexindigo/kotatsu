package com.alexindigo.kotatsu.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class CalendarDurationTest {
    @Test fun calendarProviderWeeksAndLegacySeconds() {
        assertEquals(604800000L, calendarDurationMillis("P1W"))
        assertEquals(3600000L, calendarDurationMillis("P3600S"))
    }
    @Test fun existingDayAndTimeFormatsRemainSupported() {
        assertEquals(86400000L, calendarDurationMillis("P1D"))
        assertEquals(3600000L, calendarDurationMillis("PT3600S"))
    }
}
