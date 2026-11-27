package com.alexindigo.kotatsu.sync

/** Presentation owns complete localized phrases; permissions have their own rows. */
internal fun SyncStopReason.textResource(): Int? = when (this) {
    SyncStopReason.CALENDAR_ACCESS_REQUIRED, SyncStopReason.CONTACTS_ACCESS_REQUIRED -> null
    SyncStopReason.MUDITA_CALENDAR_CHANGED -> R.string.stopped_calendar_changed
    SyncStopReason.CALENDAR_SETTINGS_REQUIRED -> R.string.stopped_calendar_settings
    SyncStopReason.SELECTED_CALENDAR_UNAVAILABLE -> R.string.chosen_calendar_unavailable
    SyncStopReason.ADDRESS_BOOK_REQUIRED -> R.string.stopped_address_book
    SyncStopReason.RETRY_REQUIRED -> R.string.stopped_retry
    SyncStopReason.REVIEW_REQUIRED -> R.string.stopped_review
    SyncStopReason.SCHEDULING_FAILED -> R.string.stopped_scheduling
}
