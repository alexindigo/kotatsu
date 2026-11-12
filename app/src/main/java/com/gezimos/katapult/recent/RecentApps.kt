package com.gezimos.katapult.recent

import android.app.ActivityManager
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

class AndroidRecentApps(private val context: Context) : RecentAppsSource {
    private val store = context.getSharedPreferences("recent_apps", Context.MODE_PRIVATE)
    private val prefs = PrefsManager(context)
    override fun available() = UsageHelper.collectionAllowed(context)
    override fun history(): List<RecentApp> {
        if (!available()) return emptyList()
        val now = System.currentTimeMillis()
        val manager = context.getSystemService(UsageStatsManager::class.java)
        return manager.queryAndAggregateUsageStats(now - 30L * 86_400_000, now).values.mapNotNull { usage ->
            val launch = context.packageManager.getLaunchIntentForPackage(usage.packageName) ?: return@mapNotNull null
            val info = context.packageManager.resolveActivity(launch, 0) ?: return@mapNotNull null
            RecentApp(usage.packageName, info.loadLabel(context.packageManager).toString(), usage.lastTimeUsed)
        }
    }
    override fun closed() = store.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
    override fun close(packages: List<String>, at: Long) {
        val manager = context.getSystemService(ActivityManager::class.java)
        val edit = store.edit()
        packages.forEach { manager.killBackgroundProcesses(it); edit.putLong(it, at) }
        edit.apply()
    }
    override fun launch(packageName: String) {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
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
        if (!visible || apps.none { it.packageName == packageName }) return
        if (apps.lastOrNull()?.packageName == packageName) {
            dismiss()
            closeForeground {
                closePackages(listOf(packageName))
                if (visible) refresh()
            }
        } else closePackages(listOf(packageName))
    }
    private fun closePackages(packages: List<String>) {
        generation++
        source.close(packages, clock())
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
