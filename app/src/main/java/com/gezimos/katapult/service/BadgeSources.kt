package com.gezimos.katapult.service

import android.content.Context
import com.gezimos.katapult.util.BadgeRoute
import com.gezimos.katapult.util.BadgeRouting
import com.gezimos.katapult.util.BadgeSource
import com.gezimos.katapult.util.DeviceHelper
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.SensitiveChoice

/** The same source selection is used by HOME, overlays, screensaver and actions. */
object BadgeSources {
    fun route(context: Context, packageName: String): BadgeRoute {
        SensitiveAccess.initialize(context)
        val source = if (!DeviceHelper.isMuditaKompakt()) null else when (packageName) {
            DirectBadgeHelper.MUDITA_DIAL -> BadgeSource.DIRECT_CALLS
            DirectBadgeHelper.MUDITA_MESSAGES -> BadgeSource.DIRECT_SMS
            else -> null
        }
        val direct = if (source == null) null else DirectBadgeHelper.getInstance(context).getCounts()[packageName]
        return BadgeRouting.select(source, direct, NotificationListener.getAllCounts()[packageName], SensitiveAccess.notificationBadges())
    }

    fun counts(context: Context): Map<String, Int> {
        val notifications = NotificationListener.getAllCounts()
        val direct = if (DeviceHelper.isMuditaKompakt()) DirectBadgeHelper.getInstance(context).getCounts() else emptyMap()
        return (notifications.keys + direct.keys).associateWith { route(context, it).count }.filterValues { it > 0 }
    }

    fun canClear(context: Context, packageName: String): Boolean = BadgeRouting.canClear(route(context, packageName),
        SensitiveAccess.notificationDismissal(),
        SensitiveAccess.choice(SensitiveChoice.CALL_ACKNOWLEDGMENT) && SensitiveAccess.callBadges())
}

/** An active visual consumer owns its source lease and subscribes to invalidation. */
internal interface BadgeFeed {
    fun acquire(owner: Any): AutoCloseable
    fun subscribe(changed: () -> Unit): AutoCloseable
    fun counts(): Map<String, Int>
    fun times(): Map<String, Long>
}

internal class AndroidBadgeFeed(private val context: Context) : BadgeFeed {
    init { SensitiveAccess.initialize(context) }
    override fun acquire(owner: Any) = SensitiveAccess.acquireBadges(owner)
    override fun counts() = BadgeSources.counts(context)
    override fun times() = NotificationListener.getLastPostTimes()
    override fun subscribe(changed: () -> Unit): AutoCloseable {
        val direct = if (DeviceHelper.isMuditaKompakt()) DirectBadgeHelper.getInstance(context) else null
        direct?.addListener(changed)
        NotificationListener.addListener(changed)
        val policy = SensitiveAccess.subscribe(changed)
        return AutoCloseable {
            policy.close()
            NotificationListener.removeListener(changed)
            direct?.removeListener(changed)
        }
    }
}
