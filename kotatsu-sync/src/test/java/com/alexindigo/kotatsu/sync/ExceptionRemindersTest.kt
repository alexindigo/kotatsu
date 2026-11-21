package com.alexindigo.kotatsu.sync

import org.junit.Assert.assertEquals
import org.junit.Test

class ExceptionRemindersTest {
    @Test fun newExceptionKeepsEveryBaseReminderUnchanged() {
        val base = listOf(CalendarReminder(10, 1), CalendarReminder(60, 2))
        assertEquals(base, exceptionReminders(base))
    }
}
