package com.gezimos.katapult.util

import android.app.AppOpsManager
import android.app.usage.UsageStats
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings

object UsageHelper {

    private const val TTL_MS = 60_000L
    private const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    private val statsCache = PolicyCache<Map<String, UsageStats>>(TTL_MS)
    private var ignoredCache: Set<String>? = null
    private var ignoredAt = 0L
    private var policySubscription: AutoCloseable? = null
    private var seenRevision = SensitiveAccess.usageRevision

    private fun initialize(context: Context) {
        SensitiveAccess.initialize(context)
        if (policySubscription == null) policySubscription = SensitiveAccess.subscribe {
            if (seenRevision != SensitiveAccess.usageRevision || !available(context)) {
                seenRevision = SensitiveAccess.usageRevision
                invalidate()
            }
        }
    }

    fun available(context: Context): Boolean {
        val prefs = PrefsManager(context)
        return prefs.sensitiveChoice(SensitiveChoice.USAGE_STATS) &&
            prefs.appSortMode != PrefsManager.APP_SORT_MANUAL && hasPermission(context)
    }

    fun hasPermission(context: Context): Boolean {
        val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = appOps.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS,
            Process.myUid(),
            context.packageName,
        )
        return if (mode == AppOpsManager.MODE_DEFAULT) {
            context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            mode == AppOpsManager.MODE_ALLOWED
        }
    }

    fun openSettings(context: Context) {
        try {
            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
        } catch (_: Exception) {}
    }

    @Synchronized fun invalidate() {
        statsCache.invalidate()
        ignoredCache = null
        ignoredAt = 0L
    }

    fun ignoredPackages(context: Context): Set<String> {
        if (!available(context)) { invalidate(); return emptySet() }
        val now = SystemClock.elapsedRealtime()
        ignoredCache?.let { if (now - ignoredAt < TTL_MS) return it }
        val ignored = mutableSetOf<String>()
        try {
            val pm = context.packageManager
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            pm.queryIntentActivities(homeIntent, PackageManager.MATCH_ALL).forEach {
                ignored.add(it.activityInfo.packageName)
            }
            pm.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.let {
                ignored.add(it.activityInfo.packageName)
            }
        } catch (_: Exception) {}
        ignoredCache = ignored
        ignoredAt = now
        return ignored
    }

    fun stats(context: Context): Map<String, UsageStats>? {
        initialize(context)
        val revision = SensitiveAccess.usageRevision
        if (seenRevision != revision) { seenRevision = revision; invalidate() }
        return statsCache.read(SystemClock.elapsedRealtime(), { available(context) && revision == SensitiveAccess.usageRevision }) {
            try {
                val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val end = System.currentTimeMillis()
                if (available(context)) usm.queryAndAggregateUsageStats(end - WINDOW_MS, end) else null
            } catch (_: Exception) { null }
        }
    }
}
