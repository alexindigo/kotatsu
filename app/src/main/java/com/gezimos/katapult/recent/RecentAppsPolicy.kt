package com.gezimos.katapult.recent

/** Display recency, plus the identity of the close record that this history entry proves reopened. */
data class RecentApp(val packageName: String, val label: String, val lastUsed: Long, val reopened: String? = null)

/** Android events that place a close: the package's resumes and every device startup. */
enum class UsageKind { RESUMED, STARTUP }
data class UsageMark(val kind: UsageKind, val at: Long)

/** [offset] is wall minus elapsed time around the queries, or null if a clock change overlapped them. */
data class UsageClock(val boot: Int, val offset: Long?)

/**
 * Where a close sits in Android's usage stream. Every pre-close resume of the package is at or
 * before [position]; every later event is after it. Android rebases all stored event times and
 * usage-interval starts by one common shift, so the monthly interval start [month] gives the exact
 * shift within a boot, and the boot's DEVICE_STARTUP time [startup] gives it after a reboot.
 */
data class CloseAnchor(val clock: UsageClock, val position: Long, val month: Long?, val startup: Long?)

/** One query of Android's retained usage data, with this package's resumes and all startups in Android's order. */
data class UsageView(val clock: UsageClock, val months: List<Long>, val events: List<UsageMark>)

/** [anchor] is null for records from v0.7.0, which hold only the close time. */
data class RecentClose(val id: String, val at: Long, val anchor: CloseAnchor?) {
    fun reopenedIn(view: UsageView): Boolean {
        val events = view.events
        fun resumedAfter(position: Long, from: Int = 0, until: Int = events.size) =
            (from until until).any { events[it].kind == UsageKind.RESUMED && events[it].at > position }
        val anchor = anchor ?: return resumedAfter(at)
        val boots = view.clock.boot - anchor.clock.boot
        if (anchor.clock.boot < 0 || boots < 0) return false
        if (boots == 0) {
            val change = (view.clock.offset ?: return false) - (anchor.clock.offset ?: return false)
            // Android's shift is within this bound of the clock-sample change; interval starts are weeks apart.
            val shift = anchor.month?.let { month ->
                view.months.singleOrNull { kotlin.math.abs(it - month - change) <= SHIFT_TOLERANCE }?.minus(month)
            }
            // Without a unique interval start, only a resume beyond the whole bound is certainly later.
            return resumedAfter(anchor.position + (shift ?: change + SHIFT_TOLERANCE))
        }
        // Android adds one startup per boot: the boot after the close begins at the matching one from the end.
        val startups = events.indices.filter { events[it].kind == UsageKind.STARTUP }
        fun startupFromEnd(count: Int) = startups.getOrElse(startups.size - count) { -1 }
        val nextBoot = startupFromEnd(boots)
        if (resumedAfter(Long.MIN_VALUE, nextBoot + 1)) return true
        val closeBoot = startupFromEnd(boots + 1)
        val startup = anchor.startup ?: return false
        if (closeBoot < 0) return false
        return resumedAfter(anchor.position + events[closeBoot].at - startup, closeBoot + 1, nextBoot)
    }

    companion object {
        /** Accepted wall-minus-elapsed change across one set of queries. */
        const val SAMPLE_TOLERANCE = 1_000L
        /** Android 12 UsageStatsService.TIME_CHANGE_THRESHOLD_MILLIS plus sampling, at close and now. */
        const val SHIFT_TOLERANCE = 2 * (2_000L + SAMPLE_TOLERANCE)
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
