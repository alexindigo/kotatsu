package com.alexindigo.kotatsu.sync

/** Stable storage codes, never provider diagnostics or translated display text. */
enum class SyncStopReason(val code: String) {
    CALENDAR_ACCESS_REQUIRED("calendar_access"),
    CONTACTS_ACCESS_REQUIRED("contacts_access"),
    MUDITA_CALENDAR_CHANGED("mudita_calendar_changed"),
    CALENDAR_SETTINGS_REQUIRED("calendar_settings"),
    ADDRESS_BOOK_REQUIRED("address_book"),
    RETRY_REQUIRED("retry"),
    REVIEW_REQUIRED("review"),
    SCHEDULING_FAILED("scheduling"),
    SELECTED_CALENDAR_UNAVAILABLE("selected_calendar_unavailable");

    val accessRequired get() = this == CALENDAR_ACCESS_REQUIRED || this == CONTACTS_ACCESS_REQUIRED

    companion object {
        fun fromCode(code: String): SyncStopReason = entries.firstOrNull { it.code == code } ?: RETRY_REQUIRED

        /** One-way upgrade boundary for the former diagnostic-text state row. */
        fun fromLegacy(text: String): Set<SyncStopReason> = text.split("; ").map { reason -> when {
            reason.contains("calendar permission not granted") -> CALENDAR_ACCESS_REQUIRED
            reason.contains("contacts permission not granted") -> CONTACTS_ACCESS_REQUIRED
            reason.contains("Mudita Calendar missing or incompatible schema") -> MUDITA_CALENDAR_CHANGED
            reason.contains("invalid calendar horizon") -> CALENDAR_SETTINGS_REQUIRED
            reason.contains("contacts address book") -> ADDRESS_BOOK_REQUIRED
            reason.startsWith("bridge scheduling:") -> SCHEDULING_FAILED
            reason.contains("pending writes") && !reason.contains("0 pending writes") -> REVIEW_REQUIRED
            else -> RETRY_REQUIRED
        } }.toSet()
    }
}

/** Last real pass; prerequisite-only attempts never replace it. A scheduling
 * failure is separate so missing access can still show the last real result. */
data class SyncStatus(
    val lastPassAt: Long? = null,
    val changes: Int = 0,
    val stopReasons: Set<SyncStopReason> = emptySet(),
    val availabilityStop: SyncStopReason? = null,
    val held: List<HeldCalendarLoss> = emptyList(),
    val contactsWaiting: Int = 0,
) {
    fun forDisplay(calendarAccess: Boolean, contactsAccess: Boolean): SyncStatus? {
        if (!calendarAccess || !contactsAccess) return if (lastPassAt == null) null else copy(availabilityStop = null)
        return this
    }

    /** Access failure preserves the last real pass. Only a contacts assessment replaces its waiting count. */
    fun afterAttempt(at: Long, changes: Int, reasons: Set<SyncStopReason>, assessedContactsWaiting: Int? = null): SyncStatus {
        val next = if (reasons.any { it.accessRequired }) this else SyncStatus(at, changes, reasons, held = held)
        return next.copy(contactsWaiting = assessedContactsWaiting ?: contactsWaiting)
    }
}
