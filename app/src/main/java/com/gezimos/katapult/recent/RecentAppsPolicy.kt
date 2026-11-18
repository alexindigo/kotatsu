package com.gezimos.katapult.recent

/** Display recency and proof of a post-close opening are separate signals. */
data class RecentApp(val packageName: String, val label: String, val lastUsed: Long, val lastOpened: Long = lastUsed)

fun recentApps(history: List<RecentApp>, ownPackage: String, closed: Map<String, Long>): List<RecentApp> =
    history.filter { app -> app.packageName != ownPackage && app.lastUsed > 0 &&
        (closed[app.packageName]?.let { app.lastOpened > it } ?: true) }
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
