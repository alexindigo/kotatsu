package com.gezimos.katapult.recent

/** Display recency, plus the identity of the close record that this history entry proves reopened. */
data class RecentApp(val packageName: String, val label: String, val lastUsed: Long, val reopened: String? = null)

/** Android lifecycle events that place a close in the usage event stream. */
enum class UsageKind { RESUMED, PAUSED, STOPPED, STARTUP }
data class UsageMark(val kind: UsageKind, val className: String?, val at: Long)

/** [offset] is wall minus elapsed time around an events query, or null if a clock change overlapped it. */
data class UsageClock(val boot: Int, val elapsed: Long, val offset: Long?)

/**
 * A close is a position in Android's event stream: the package's latest lifecycle
 * events when it was closed ([trail]). Within one boot Android keeps events in
 * occurrence order and rebases all stored timestamps by the same amount on a clock
 * change, so the trail is found again at one common shift. Only a later resume is
 * reuse; wall-time comparisons and event counts are not stable across corrections,
 * rebasing and pruning. [clock] and [trail] are null for records from v0.7.0.
 */
data class RecentClose(val id: String, val at: Long, val clock: UsageClock?, val trail: List<UsageMark>?) {
    /** [events] holds this package's marks and every device startup, in the order Android returned them;
     *  [earliest] is the oldest event Android still retains for any package. */
    fun reopenedIn(events: List<UsageMark>, now: UsageClock, earliest: Long?): Boolean {
        fun resumes(from: Int = 0) = (from until events.size).any { events[it].kind == UsageKind.RESUMED }
        val trail = trail ?: return events.any { it.kind == UsageKind.RESUMED && it.at > at }
        val last = trail.lastOrNull() ?: return resumes()
        val closed = clock?.takeIf { it.boot >= 0 && now.boot >= it.boot }
        val sameBoot = closed?.boot == now.boot
        val shifts = if (sameBoot && closed?.offset != null && now.offset != null)
            (now.offset - closed.offset).let { it - SHIFT_TOLERANCE..it + SHIFT_TOLERANCE } else null
        // The earliest consistent shift: later resumes cannot replicate the trail's exact gaps.
        val retained = events.toHashSet()
        val anchor = events.indices.firstOrNull { index ->
            val candidate = events[index]
            val shift = candidate.at - last.at
            candidate.kind == last.kind && candidate.className == last.className && (shifts == null || shift in shifts) &&
                trail.all { mark -> (earliest != null && mark.at + shift < earliest) || mark.copy(at = mark.at + shift) in retained }
        }
        if (anchor != null) return resumes(anchor + 1)
        if (sameBoot) {
            // Pruning removes the oldest daily files first, so a fully pruned trail leaves only later resumes.
            if (shifts != null && (earliest == null || last.at + shifts.last < earliest)) return resumes()
            val offset = now.offset ?: return false
            val after = checkNotNull(closed).elapsed + offset + OFFSET_TOLERANCE
            return events.any { it.kind == UsageKind.RESUMED && it.at > after }
        }
        if (closed == null) return false
        // Android adds one startup per boot; the boot after the close begins at the matching one from the end.
        val startups = events.indices.filter { events[it].kind == UsageKind.STARTUP }
        val boots = now.boot - closed.boot
        return resumes(if (startups.size >= boots) startups[startups.size - boots] + 1 else 0)
    }

    companion object {
        /** Accepted wall-minus-elapsed change across one query's clock samples. */
        const val SAMPLE_TOLERANCE = 1_000L
        /** Android 12 UsageStatsService.TIME_CHANGE_THRESHOLD_MILLIS plus sampling around the query. */
        const val OFFSET_TOLERANCE = 2_000L + SAMPLE_TOLERANCE
        const val SHIFT_TOLERANCE = 2 * OFFSET_TOLERANCE
        const val TRAIL = 8
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
