package com.gezimos.katapult.recent

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONObject
import java.util.UUID
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
    fun closed(): Map<String, RecentClose>
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
        val history = context.getSystemService(UsageStatsManager::class.java)
            .queryAndAggregateUsageStats(now - 30L * 86_400_000, now).values
        val closed = closed()
        val reopened = mutableMapOf<String, String>()
        if (closed.isNotEmpty()) {
            // Aggregate lastTimeUsed also advances on pause; only a resume after the close is reuse.
            val view = view(closed.keys)
            closed.forEach { (packageName, record) ->
                if (record.reopenedIn(UsageView(view.clock, view.months, view.events.getValue(packageName)))) {
                    reopened[packageName] = record.id
                }
            }
            // Retire proven reuse so it survives Android's shorter event retention.
            // A history load must never erase a newer close recorded during its query.
            if (reopened.isNotEmpty()) synchronized(store) {
                val current = closed().toMutableMap()
                val edit = store.edit()
                reopened.keys.forEach { packageName ->
                    retireClose(current, packageName, closed.getValue(packageName))
                    if (packageName !in current) edit.remove(packageName)
                }
                edit.apply()
            }
        }
        return history.mapNotNull { usage ->
            val launch = context.packageManager.getLaunchIntentForPackage(usage.packageName) ?: return@mapNotNull null
            val info = context.packageManager.resolveActivity(launch, 0) ?: return@mapNotNull null
            RecentApp(usage.packageName, info.loadLabel(context.packageManager).toString(), usage.lastTimeUsed,
                reopened[usage.packageName])
        }
    }
    private class View(val clock: UsageClock, val months: List<Long>, val events: Map<String, List<UsageMark>>)
    private val usage get() = context.getSystemService(UsageStatsManager::class.java)
    private fun offset() = System.currentTimeMillis() - SystemClock.elapsedRealtime()
    /** Runs [queries] between monthly-interval and clock samples, retrying while a clock change or rebase overlaps them. */
    private fun <T> sampled(queries: () -> T): Triple<UsageClock?, List<Long>, T> {
        var last: Triple<UsageClock?, List<Long>, T>? = null
        for (attempt in 1..3) {
            val before = offset()
            val monthsBefore = months()
            val result = queries()
            val months = months()
            val offset = usageOffset(before, offset(), monthsBefore, months)
            val boot = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
            last = Triple(offset?.let { UsageClock(boot, it) }, months, result)
            if (offset != null) break
        }
        return checkNotNull(last)
    }
    private fun events(since: Long, until: Long, each: (UsageEvents.Event) -> Unit) {
        val stream = usage.queryEvents(since, until)
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) { stream.getNextEvent(event); each(event) }
    }
    /** Starts of the retained monthly usage intervals; Android shifts them with every event on a rebase. */
    private fun months() = usage.queryUsageStats(UsageStatsManager.INTERVAL_MONTHLY, 0, Long.MAX_VALUE)
        .map { it.firstTimeStamp }.distinct().sorted()
    private fun view(packages: Set<String>): View {
        val (clock, months, result) = sampled {
            val events = packages.associateWith { mutableListOf<UsageMark>() }
            events(0, Long.MAX_VALUE) { event ->
                when (event.eventType) {
                    UsageEvents.Event.DEVICE_STARTUP -> events.values.forEach { it += UsageMark(UsageKind.STARTUP, event.timeStamp) }
                    UsageEvents.Event.ACTIVITY_RESUMED -> events[event.packageName]?.add(UsageMark(UsageKind.RESUMED, event.timeStamp))
                }
            }
            events
        }
        // An unsettled sample decides nothing: the apps stay hidden until the next load.
        return View(clock ?: UsageClock(-1, null), months, result)
    }
    /** Null when a clock change or rebase overlapped every attempt; the close then keeps only its wall time. */
    private fun anchors(packages: Set<String>, closedAt: Long): Map<String, CloseAnchor>? {
        val (clock, months, result) = sampled {
            // Every pre-close resume Android has recorded is in this window or before its start.
            val since = System.currentTimeMillis() - 3_600_000
            val position = packages.associateWith { since }.toMutableMap()
            events(since, Long.MAX_VALUE) { event ->
                if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED && event.packageName in position) {
                    position[event.packageName] = maxOf(position.getValue(event.packageName), event.timeStamp)
                }
            }
            // Android adds the boot's startup when it unlocks the user, normally soon after booting.
            val booted = System.currentTimeMillis() - SystemClock.elapsedRealtime()
            var startup: Long? = null
            events(booted - 60_000, booted + 6 * 3_600_000) { event ->
                if (event.eventType == UsageEvents.Event.DEVICE_STARTUP) startup = event.timeStamp
            }
            position to startup
        }
        clock ?: return null
        val offset = checkNotNull(clock.offset)
        return result.first.mapValues { (_, position) ->
            CloseAnchor(clock, position, closedAt + offset, months.lastOrNull(), result.second)
        }
    }
    override fun closed(): Map<String, RecentClose> = store.all.mapNotNull { (packageName, value) ->
        when (value) {
            is Long -> RecentClose("v0.7.0:$value", value, null) // Released before close records had identity.
            is String -> runCatching { closeRecord(JSONObject(value)) }.getOrNull()
            else -> null
        }?.let { packageName to it }
    }.toMap()
    override fun close(packages: List<String>, at: Long) {
        val closedAt = SystemClock.elapsedRealtime()
        packages.forEach(requestStop)
        synchronized(store) {
            // The same usage gate as history(): without it, no usage data is read or kept.
            val anchors = if (available()) anchors(packages.toSet(), closedAt) else null
            val edit = store.edit()
            packages.forEach { packageName ->
                edit.putString(packageName, closeJson(RecentClose(UUID.randomUUID().toString(), at, anchors?.get(packageName))).toString())
            }
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

private fun JSONObject.optional(key: String) = if (isNull(key)) null else getLong(key)

/** A close without an anchor stores only its id and wall time. */
private fun closeJson(record: RecentClose) = JSONObject().put("id", record.id).put("at", record.at).apply {
    record.anchor?.let { anchor ->
        put("boot", anchor.clock.boot).put("offset", anchor.clock.offset ?: JSONObject.NULL).put("position", anchor.position)
            .put("boundary", anchor.boundary).put("month", anchor.month ?: JSONObject.NULL).put("startup", anchor.startup ?: JSONObject.NULL)
    }
}

private fun closeRecord(json: JSONObject) = RecentClose(json.getString("id"), json.getLong("at"),
    if (!json.has("position")) null else CloseAnchor(UsageClock(json.getInt("boot"), json.optional("offset")),
        json.getLong("position"), json.getLong("boundary"), json.optional("month"), json.optional("startup")))

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
