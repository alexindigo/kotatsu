package com.gezimos.katapult.recent

import android.app.ActivityManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import org.json.JSONArray
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
            val observed = observe(closed.keys, 0)
            closed.forEach { (packageName, record) ->
                if (record.reopenedIn(observed.events.getValue(packageName), observed.clock, observed.earliest)) {
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
    private class Observation(val clock: UsageClock, val earliest: Long?, val events: Map<String, List<UsageMark>>)
    private fun boot() = Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    /** One query from [since] to the newest retained event, in Android's order; offset only if no clock change overlapped. */
    private fun observe(packages: Set<String>, since: Long): Observation {
        val before = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val stream = context.getSystemService(UsageStatsManager::class.java).queryEvents(since, Long.MAX_VALUE)
        val elapsed = SystemClock.elapsedRealtime()
        val offset = System.currentTimeMillis() - elapsed
        val events = packages.associateWith { mutableListOf<UsageMark>() }
        var earliest: Long? = null
        val event = UsageEvents.Event()
        while (stream.hasNextEvent()) {
            stream.getNextEvent(event)
            if (earliest == null) earliest = event.timeStamp
            val kind = when (event.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> UsageKind.RESUMED
                UsageEvents.Event.ACTIVITY_PAUSED -> UsageKind.PAUSED
                UsageEvents.Event.ACTIVITY_STOPPED -> UsageKind.STOPPED
                UsageEvents.Event.DEVICE_STARTUP -> UsageKind.STARTUP
                else -> continue
            }
            val mark = UsageMark(kind, event.className, event.timeStamp)
            if (kind == UsageKind.STARTUP) events.values.forEach { it += mark.copy(className = null) }
            else events[event.packageName]?.add(mark)
        }
        val stable = kotlin.math.abs(offset - before) <= RecentClose.SAMPLE_TOLERANCE
        return Observation(UsageClock(boot(), elapsed, offset.takeIf { stable }), earliest, events)
    }
    override fun closed(): Map<String, RecentClose> = store.all.mapNotNull { (packageName, value) ->
        when (value) {
            is Long -> RecentClose("v0.7.0:$value", value, null, null) // Released before close records had identity.
            is String -> runCatching { closeRecord(JSONObject(value)) }.getOrNull()
            else -> null
        }?.let { packageName to it }
    }.toMap()
    override fun close(packages: List<String>, at: Long) {
        packages.forEach(requestStop)
        synchronized(store) {
            // The latest events usually lie within a day; a short trail needs the whole retained stream.
            val recent = observe(packages.toSet(), System.currentTimeMillis() - 86_400_000)
            val observed = if (recent.events.values.all { marks -> marks.count { it.kind != UsageKind.STARTUP } >= RecentClose.TRAIL }) recent
                else observe(packages.toSet(), 0)
            val edit = store.edit()
            packages.forEach { packageName ->
                val trail = observed.events.getValue(packageName).filter { it.kind != UsageKind.STARTUP }.takeLast(RecentClose.TRAIL)
                edit.putString(packageName, closeJson(RecentClose(UUID.randomUUID().toString(), at, observed.clock, trail)).toString())
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

private fun closeJson(record: RecentClose) = JSONObject().put("id", record.id).put("at", record.at).apply {
    record.clock?.let { put("boot", it.boot).put("elapsed", it.elapsed).put("offset", it.offset ?: JSONObject.NULL) }
    put("trail", JSONArray().apply { record.trail.orEmpty().forEach { mark ->
        put(JSONObject().put("kind", mark.kind.name).put("class", mark.className ?: JSONObject.NULL).put("at", mark.at))
    } })
}

private fun closeRecord(json: JSONObject): RecentClose {
    val clock = if (json.has("boot")) UsageClock(json.getInt("boot"), json.getLong("elapsed"),
        if (json.isNull("offset")) null else json.getLong("offset")) else null
    val trail = json.getJSONArray("trail").let { array -> (0 until array.length()).map { index ->
        array.getJSONObject(index).let { UsageMark(UsageKind.valueOf(it.getString("kind")),
            if (it.isNull("class")) null else it.getString("class"), it.getLong("at")) }
    } }
    return RecentClose(json.getString("id"), json.getLong("at"), clock, trail)
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
