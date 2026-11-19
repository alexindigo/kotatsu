package com.gezimos.katapult.recent

/** Display recency, plus the identity of the close record that this history entry proves reopened. */
data class RecentApp(val packageName: String, val label: String, val lastUsed: Long, val reopened: String? = null)

/** Android events that place a close: the package's resumes and every device startup. */
enum class UsageKind { RESUMED, STARTUP }
data class UsageMark(val kind: UsageKind, val at: Long)

/** [offset] is wall minus elapsed time around the queries, or null if a clock change overlapped them. */
data class UsageClock(val boot: Int, val offset: Long?)

/**
 * Where a close sits in Android's usage stream. Every pre-close resume of the package Android had
 * recorded is at or before [position]; [boundary] is the close time in Android's usage clock within
 * its 2 s tolerance. Android rebases all stored event times and usage-interval starts by one common
 * shift, so the monthly interval start [month] gives the exact shift within a boot, and together
 * with the boot's DEVICE_STARTUP time [startup] it locates the close's boot after a reboot.
 */
data class CloseAnchor(val clock: UsageClock, val position: Long, val boundary: Long, val month: Long?, val startup: Long?)

/** One query of Android's retained usage data: this package's resumes and all startups in Android's order. */
data class UsageView(val clock: UsageClock, val months: List<Long>, val events: List<UsageMark>)

/** A sample is usable only if neither a clock change nor an Android rebase overlapped its queries. */
fun usageOffset(before: Long, after: Long, monthsBefore: List<Long>, monthsAfter: List<Long>): Long? =
    after.takeIf { kotlin.math.abs(after - before) <= RecentClose.SAMPLE_TOLERANCE && monthsBefore == monthsAfter }

/** [anchor] is null for records from v0.7.0, and for closes recorded while usage access was not allowed. */
data class RecentClose(val id: String, val at: Long, val anchor: CloseAnchor?) {
    /** Unprovable evidence keeps the app hidden: only a resume certainly after the close is reuse. */
    fun reopenedIn(view: UsageView): Boolean {
        val events = view.events
        fun resumedAfter(after: Long, from: Int = 0) =
            (from until events.size).any { events[it].kind == UsageKind.RESUMED && events[it].at > after }
        // Android's usage clock is within its tolerance of the wall clock that set [at].
        val anchor = anchor ?: return resumedAfter(at + OFFSET_TOLERANCE)
        // Android records events on a handler, so a resume stamped before the close can appear only after the
        // anchor query. [boundary] is the close in Android's usage clock only within its 2 s tolerance, so such a
        // resume is rejected unless it was stamped within OFFSET_TOLERANCE of the close. Inside that window it is
        // indistinguishable from a genuine quick reopen; the window is kept so that quick reopens restore the app.
        fun closed(shift: Long) = maxOf(anchor.position, anchor.boundary - OFFSET_TOLERANCE) + shift
        if (anchor.clock.boot < 0 || view.clock.boot < anchor.clock.boot) return false
        val now = view.clock.offset ?: return false
        if (view.clock.boot == anchor.clock.boot) {
            val change = now - (anchor.clock.offset ?: return false)
            // Android's shift is within this bound of the clock-sample change; interval starts are weeks apart.
            val shift = anchor.month?.let { month ->
                view.months.singleOrNull { kotlin.math.abs(it - month - change) <= SHIFT_TOLERANCE }?.minus(month)
            }
            // Without a unique interval start, only a resume beyond the whole bound is certainly later.
            return resumedAfter(closed(shift ?: change + SHIFT_TOLERANCE))
        }
        // A boot only gets a startup once the user unlocks it, so boot counts cannot index startups.
        val startups = events.indices.filter { events[it].kind == UsageKind.STARTUP }
        // This boot's startup is stamped after it began; every later event belongs to this boot.
        val current = startups.lastOrNull()?.takeIf { events[it].at >= now - OFFSET_TOLERANCE }
        if (current != null && resumedAfter(Long.MIN_VALUE, current + 1)) return true
        // Android 12 can drop a boot's startup: UserUsageStatsService.init adds it without marking the stats
        // changed (L154-166), and when the first event after boot rolls the day over, persistActiveStats skips
        // unchanged stats (L609-611, rolloverStats L625-670). Without a verified startup, a resume stamped after
        // this boot began ([now] is wall minus elapsed time) is reuse. That assumes the wall clock did not move
        // backward while the phone was off, as Android does: UsageStatsDatabase.init deletes future-dated files
        // instead of ordering them (L177-193).
        if (current == null && resumedAfter(now + OFFSET_TOLERANCE)) return true
        // The close's boot begins at the startup whose shift also moves the close's monthly start.
        val month = anchor.month ?: return false
        val startup = anchor.startup ?: return false
        val (index, shift) = startups.map { it to events[it].at - startup }
            .singleOrNull { (_, shift) -> month + shift in view.months } ?: return false
        return resumedAfter(closed(shift), index + 1)
    }

    companion object {
        /** Accepted wall-minus-elapsed change across one set of queries. */
        const val SAMPLE_TOLERANCE = 1_000L
        /** Android 12 UsageStatsService.TIME_CHANGE_THRESHOLD_MILLIS plus sampling. */
        const val OFFSET_TOLERANCE = 2_000L + SAMPLE_TOLERANCE
        /** The offset tolerance at close and now. */
        const val SHIFT_TOLERANCE = 2 * OFFSET_TOLERANCE
    }
}

/** Compare-and-delete: a load may retire only the exact record it observed. */
fun retireClose(records: MutableMap<String, RecentClose>, packageName: String, observed: RecentClose) {
    if (records[packageName]?.id == observed.id) records.remove(packageName)
}

fun recentApps(history: List<RecentApp>, ownPackage: String, closed: Map<String, RecentClose>): List<RecentApp> =
    history.filter { app -> app.packageName != ownPackage && app.lastUsed > 0 &&
        (closed[app.packageName]?.let { app.reopened == it.id } ?: true) }
        .groupBy { it.packageName }.map { (_, uses) -> uses.maxBy { it.lastUsed } }
        .sortedWith(compareBy<RecentApp> { it.lastUsed }.thenBy { it.packageName })

enum class RecentAge { NOW, MINUTES, HOURS, YESTERDAY, DAYS }
data class RelativeUse(val age: RecentAge, val count: Int = 0)

fun relativeUse(lastUsed: Long, now: Long, todayStart: Long, yesterdayStart: Long): RelativeUse {
    val elapsed = (now - lastUsed).coerceAtLeast(0)
    return when {
        elapsed < 60_000 -> RelativeUse(RecentAge.NOW)
        lastUsed in yesterdayStart until todayStart -> RelativeUse(RecentAge.YESTERDAY)
        elapsed < 3_600_000 -> RelativeUse(RecentAge.MINUTES, (elapsed / 60_000).toInt())
        elapsed < 86_400_000 -> RelativeUse(RecentAge.HOURS, (elapsed / 3_600_000).toInt())
        else -> RelativeUse(RecentAge.DAYS, (elapsed / 86_400_000).toInt())
    }
}
