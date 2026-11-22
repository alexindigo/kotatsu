package com.alexindigo.kotatsu.sync

/** Last finished attempt, not a promise of a DAVx5/server sync. Counts logical
 * event mutations and moved raw contacts, never mapping writes or data rows. */
data class SyncStatus(val lastPassAt: Long? = null, val changes: Int = 0, val stopReason: String? = null)
