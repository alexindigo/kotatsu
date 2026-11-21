package com.alexindigo.kotatsu.sync

internal data class CalendarReminder(val minutes: Int, val method: Int)

/** Only new occurrence exceptions inherit reminders; phone uploads and existing rows do not. */
internal fun exceptionReminders(base: List<CalendarReminder>): List<CalendarReminder> = base.toList()
