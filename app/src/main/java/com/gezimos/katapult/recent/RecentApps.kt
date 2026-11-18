package com.gezimos.katapult.recent

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SensitiveChoice
import com.gezimos.katapult.util.UsageHelper

/** Android operations are injected at the boundary; the page only forwards intents. */
interface RecentAppsSource {
    fun available(): Boolean
    fun history(): List<RecentApp>
    fun closed(): Map<String, Long>
    fun close(packages: List<String>, at: Long)
    fun launch(packageName: String)
    fun canOpenAccess(): Boolean = true
    fun openAccess(): Boolean
}

class AndroidRecentApps(private val context: Context,
    private val requestStop: (String) -> Unit = { context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(it) },
    private val launching: (String) -> Unit = {}) : RecentAppsSource {
    private val store = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
    private val prefs = PrefsManager(context)
    override fun available() = UsageHelper.collectionAllowed(context)
    override fun history(): List<RecentApp> {
        if (!available()) return emptyList()
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(UsageStatsManager::class.java)
        val since = now - 30L * 86_400_000
        val history = manager.queryAndAggregateUsageStats(since, now).values
        val closed = closed()
        val opened = mutableMapOf<String, Long>()
        if (closed.isNotEmpty()) {
            // Aggregate lastTimeUsed also advances on pause, not just resume. Usage
            // timestamps can lead wall time after a small Android clock correction.
            val through = maxOf(now, history.maxOfOrNull { it.lastTimeUsed } ?: now)
            val events = manager.queryEvents(minOf(now, maxOf(since, closed.values.min())), through + 1)
            val event = UsageEvents.Event()
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                val at = closed[event.packageName] ?: continue
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.timeStamp > at) {
                    opened[event.packageName] = maxOf(opened[event.packageName] ?: 0, event.timeStamp)
                }
            }
            // Retire proven reuse so it survives Android's shorter event retention.
            // A history load must never erase a newer close recorded during its query.
            if (opened.isNotEmpty()) synchronized(store) {
                val edit = store.edit()
                opened.keys.forEach { packageName ->
                    if (store.getLong(packageName, 0) == closed[packageName]) edit.remove(packageName)
                }
                edit.apply()
            }
        }
        return history.mapNotNull { usage ->
            val launch = context.packageManager.getLaunchIntentForPackage(usage.packageName) ?: return@mapNotNull null
            val info = context.packageManager.resolveActivity(launch, 0) ?: return@mapNotNull null
            RecentApp(usage.packageName, info.loadLabel(context.packageManager).toString(), usage.lastTimeUsed,
                if (usage.packageName in closed) opened[usage.packageName] ?: 0 else usage.lastTimeUsed)
        }
    }
    override fun closed() = store.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
    override fun close(packages: List<String>, at: Long) {
        packages.forEach(requestStop)
        synchronized(store) {
            val edit = store.edit()
            packages.forEach { edit.putLong(it, at) }
            edit.apply()
        }
    }
    override fun launch(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            launching(packageName)
            context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
    override fun openAccess(): Boolean {
        if (prefs.sensitiveChoice(SensitiveChoice.USAGE_STATS)) return UsageHelper.openSettings(context)
        context.startActivity(Intent(context, MainActivity::class.java)
            .setAction(MainActivity.ACTION_SENSITIVE_ACCESS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
    override fun canOpenAccess() = !prefs.sensitiveChoice(SensitiveChoice.USAGE_STATS) || UsageHelper.canOpenSettings(context)
}

class RecentApps(private val source: RecentAppsSource, private val ownPackage: String,
    private val dismissed: () -> Unit = {},
    private val load: (() -> Unit) -> Unit = { it() },
    private val deliver: (() -> Unit) -> Unit = { it() },
    private val loaded: () -> Unit = {},
    private val closeForeground: ((() -> Unit) -> Unit) = { it() },
    private val clock: () -> Long = System::currentTimeMillis) {
    private var generation = 0L
    var visible by mutableStateOf(false)
        private set
    var available by mutableStateOf(false)
        private set
    var canOpenAccess by mutableStateOf(false)
        private set
    var apps by mutableStateOf(emptyList<RecentApp>())
        private set
    var now by mutableStateOf(0L)
        private set
    var refreshRevision by mutableIntStateOf(0)
        private set
    fun show() { visible = true; refresh() }
    fun toggle() { if (visible) dismiss() else show() }
    fun refresh() {
        if (!visible) return
        val token = ++generation
        load {
            val access = source.available()
            val destination = source.canOpenAccess()
            val at = clock()
            val history = if (access) recentApps(source.history(), ownPackage, source.closed()) else emptyList()
            deliver {
                if (visible && token == generation) {
                    available = access; canOpenAccess = destination; now = at; apps = history
                    refreshRevision++
                    loaded()
                }
            }
        }
    }
    fun close(packageName: String) {
        if (!visible) return
        // Capture the offered row before dismissal or a deferred Home transition
        // can replace the published snapshot, including with an empty list.
        val lastUsed = apps.firstOrNull { it.packageName == packageName }?.lastUsed ?: return
        val closeBoundary = maxOf(clock(), lastUsed)
        if (apps.lastOrNull()?.packageName == packageName) {
            dismiss()
            closeForeground {
                closePackages(listOf(packageName), closeBoundary)
                if (visible) refresh()
            }
        } else closePackages(listOf(packageName), closeBoundary)
    }
    private fun closePackages(packages: List<String>, closeBoundary: Long) {
        generation++
        // The close must supersede the snapshot that offered the row, including
        // usage timestamps ahead of wall time after Android clock corrections.
        source.close(packages, maxOf(clock(), closeBoundary))
        apps = apps.filterNot { it.packageName in packages }
    }
    fun switchTo(packageName: String) { dismiss(); source.launch(packageName) }
    fun back() = dismiss()
    fun dismiss() {
        generation++
        val wasVisible = visible
        visible = false
        if (wasVisible) dismissed()
    }
    fun openAccess() { if (source.openAccess()) dismiss() else canOpenAccess = false }
}
