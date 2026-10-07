package com.gezimos.katapult.util

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import androidx.core.app.NotificationManagerCompat
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Public own-UID Android adapters. Every sensitive operation also re-reads access. */
object SensitiveAccess {
    private lateinit var context: Context
    private lateinit var prefs: PrefsManager
    private var preferencesSubscription: AutoCloseable? = null
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())
    private val badgeOwners = OwnerLeases { changed() }
    private val mediaOwners = OwnerLeases { changed() }
    private val callChanges = AtomicLong()
    private val smsChanges = AtomicLong()
    private val notificationChanges = AtomicLong()
    private val mediaChanges = AtomicLong()
    private val usageChanges = AtomicLong()
    private val callActionChanges = AtomicLong()
    private val mediaActionChanges = AtomicLong()
    val callRevision: Long get() = callChanges.get()
    val smsRevision: Long get() = smsChanges.get()
    val notificationRevision: Long get() = notificationChanges.get()
    val mediaRevision: Long get() = mediaChanges.get()
    val usageRevision: Long get() = usageChanges.get()
    val callActionRevision: Long get() = callActionChanges.get()
    val mediaActionRevision: Long get() = mediaActionChanges.get()
    @Volatile var notificationConnected = false
        private set
    @Volatile var accessibilityConnected = false
        private set

    private val appOpsListener = AppOpsManager.OnOpChangedListener { op, packageName ->
        if (::context.isInitialized && packageName == context.packageName) {
            when (op) {
                AppOpsManager.OPSTR_READ_CALL_LOG -> { callChanges.incrementAndGet(); callActionChanges.incrementAndGet() }
                AppOpsManager.OPSTR_READ_SMS -> smsChanges.incrementAndGet()
                AppOpsManager.OPSTR_GET_USAGE_STATS -> usageChanges.incrementAndGet()
            }
            main.post { changed() }
        }
    }

    @Synchronized fun initialize(value: Context) {
        if (::context.isInitialized) return
        context = value.applicationContext
        prefs = PrefsManager(context)
        preferencesSubscription = prefs.subscribeChanges { key ->
            if (key == null || key == SensitiveChoice.CALL_BADGES.key) { callChanges.incrementAndGet(); callActionChanges.incrementAndGet() }
            if (key == null || key == SensitiveChoice.SMS_BADGES.key) smsChanges.incrementAndGet()
            if (key == null || key == SensitiveChoice.NOTIFICATION_BADGES.key) notificationChanges.incrementAndGet()
            if (key == null || key == SensitiveChoice.MEDIA_INFO.key) { mediaChanges.incrementAndGet(); mediaActionChanges.incrementAndGet() }
            if (key == null || key == SensitiveChoice.USAGE_STATS.key || key == "app_sort_mode") usageChanges.incrementAndGet()
            if (key == SensitiveChoice.CALL_ACKNOWLEDGMENT.key) callActionChanges.incrementAndGet()
            if (key == SensitiveChoice.MEDIA_CONTROLS.key) mediaActionChanges.incrementAndGet()
            changed()
        }
        val appOps = context.getSystemService(AppOpsManager::class.java)
        for (op in listOf(AppOpsManager.OPSTR_READ_SMS, AppOpsManager.OPSTR_READ_CALL_LOG,
                          AppOpsManager.OPSTR_WRITE_CALL_LOG, AppOpsManager.OPSTR_GET_USAGE_STATS)) {
            // Some platform builds restrict watching; lifecycle + operation-time
            // checks remain in force even if this public own-UID watcher is unavailable.
            try { appOps.startWatchingMode(op, context.packageName, appOpsListener) }
            catch (_: SecurityException) {}
        }
    }

    fun subscribe(listener: () -> Unit): AutoCloseable {
        listeners.add(listener)
        val closed = AtomicBoolean(false)
        return AutoCloseable { if (closed.compareAndSet(false, true)) listeners.remove(listener) }
    }

    private fun changed() { listeners.forEach { it() } }
    fun refresh() { changed() }
    fun acquireBadges(owner: Any): AutoCloseable = badgeOwners.acquire(owner)
    fun acquireMedia(owner: Any): AutoCloseable = mediaOwners.acquire(owner)
    val badgeDemand: Boolean get() = badgeOwners.demanded
    val mediaDemand: Boolean get() = mediaOwners.demanded
    fun choice(choice: SensitiveChoice): Boolean = ::prefs.isInitialized && prefs.sensitiveChoice(choice)

    fun notificationConnection(connected: Boolean) {
        notificationConnected = connected
        notificationChanges.incrementAndGet()
        mediaChanges.incrementAndGet()
        mediaActionChanges.incrementAndGet()
        changed()
    }

    fun accessibilityConnection(connected: Boolean) {
        accessibilityConnected = connected
        changed()
    }

    private fun granted(permission: String, op: String): Boolean {
        if (!::context.isInitialized || context.checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) return false
        val mode = context.getSystemService(AppOpsManager::class.java)
            .unsafeCheckOpNoThrow(op, Process.myUid(), context.packageName)
        return mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
    }

    fun callBadges(): Boolean = SensitivePolicy.eligible(choice(SensitiveChoice.CALL_BADGES),
        DeviceHelper.isMuditaKompakt(), granted(Manifest.permission.READ_CALL_LOG, AppOpsManager.OPSTR_READ_CALL_LOG), badgeDemand)
    fun smsBadges(): Boolean = SensitivePolicy.eligible(choice(SensitiveChoice.SMS_BADGES),
        DeviceHelper.isMuditaKompakt(), granted(Manifest.permission.READ_SMS, AppOpsManager.OPSTR_READ_SMS), badgeDemand)
    fun canWriteCallLog(): Boolean = granted(Manifest.permission.WRITE_CALL_LOG, AppOpsManager.OPSTR_WRITE_CALL_LOG)
    fun callAcknowledgment(): Boolean = choice(SensitiveChoice.CALL_ACKNOWLEDGMENT) && callBadges() && canWriteCallLog()
    fun notificationAccess(): Boolean = ::context.isInitialized && notificationConnected &&
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    fun notificationBadges(): Boolean = choice(SensitiveChoice.NOTIFICATION_BADGES) && badgeDemand && notificationAccess()
    fun notificationDismissal(): Boolean = choice(SensitiveChoice.NOTIFICATION_DISMISSAL) && notificationBadges()
    fun mediaInfo(): Boolean = MediaPolicy.metadata(choice(SensitiveChoice.MEDIA_INFO), notificationAccess(), mediaDemand)
    fun mediaControls(): Boolean = MediaPolicy.controls(mediaInfo(), choice(SensitiveChoice.MEDIA_CONTROLS))
    fun accessibility(): Boolean = ::context.isInitialized && choice(SensitiveChoice.ACCESSIBILITY_FEATURES) &&
        accessibilityConnected && LockscreenWidgetService.isEnabled(context)
}
