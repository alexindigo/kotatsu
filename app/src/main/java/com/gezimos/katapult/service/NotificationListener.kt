package com.gezimos.katapult.service

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gezimos.katapult.util.AudioWidgetHelper
import com.gezimos.katapult.util.BadgeSource
import com.gezimos.katapult.util.SensitiveAccess
import java.util.concurrent.CopyOnWriteArrayList

class NotificationListener : NotificationListenerService() {
    companion object {
        private val listeners = CopyOnWriteArrayList<() -> Unit>()
        @Volatile private var instance: NotificationListener? = null

        fun addListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }
        fun removeListener(listener: () -> Unit) { listeners.remove(listener) }
        private fun notifyChanged() { listeners.forEach { it() } }
        fun getAllCounts(): Map<String, Int> = instance?.badges?.counts().orEmpty()
        fun getCount(packageName: String): Int = getAllCounts()[packageName] ?: 0
        fun getLastPostTimes(): Map<String, Long> = instance?.badges?.times().orEmpty()

        fun cancelFor(packageName: String) {
            instance?.badges?.dismiss(packageName)
        }
    }

    private var policySubscription: AutoCloseable? = null
    private val badges = NotificationBadges(
        eligible = ::eligible,
        revision = { SensitiveAccess.notificationRevision },
        enumerate = {
            if (!eligible()) null else activeNotifications?.mapNotNull { notification ->
                if (eligible()) badge(notification) else null
            }
        },
        dismissible = { packageName -> SensitiveAccess.notificationDismissal() &&
            BadgeSources.route(this, packageName).source == BadgeSource.NOTIFICATIONS },
        cancel = { keys -> cancelNotifications(keys) },
        changed = ::notifyChanged,
    )
    override fun onCreate() {
        super.onCreate()
        SensitiveAccess.initialize(this)
        policySubscription = SensitiveAccess.subscribe(::reconcile)
    }

    private fun eligible(): Boolean = instance === this && SensitiveAccess.notificationBadges()
    private fun badge(sbn: StatusBarNotification): BadgeNotification = BadgeNotification(sbn.key, sbn.packageName,
        sbn.postTime, sbn.isClearable, sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)

    fun reconcile() {
        badges.reconcile()
    }

    override fun onListenerConnected() {
        instance = this
        SensitiveAccess.notificationConnection(true)
        reconcile()
        AudioWidgetHelper.getInstance(this).initialize(ComponentName(this, NotificationListener::class.java))
    }

    private fun disconnected() {
        if (instance !== this) return
        instance = null
        SensitiveAccess.notificationConnection(false)
        badges.disconnect()
        AudioWidgetHelper.getInstance(this).cleanup()
    }

    override fun onListenerDisconnected() { disconnected(); super.onListenerDisconnected() }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        badges.posted { badge(sbn) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        badges.removed { sbn.key }
    }
    override fun onDestroy() {
        disconnected()
        policySubscription?.close(); policySubscription = null
        super.onDestroy()
    }
}
