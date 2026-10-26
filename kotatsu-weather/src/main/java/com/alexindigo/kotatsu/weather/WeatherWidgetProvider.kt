package com.alexindigo.kotatsu.weather

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import java.time.Instant
import java.time.Clock
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) { WidgetReconciler.reconcile(context, ReconcileReason.UPDATE) }
    override fun onDisabled(context: Context) { WeatherRuntime.cancel(context) }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WeatherStore(context)
        appWidgetIds.forEach(store::delete)
        WidgetReconciler.reconcile(context, ReconcileReason.DELETED)
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = WeatherStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.restore(old, new) }
        WidgetReconciler.reconcile(context, ReconcileReason.RESTORED)
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        WidgetReconciler.reconcile(context, ReconcileReason.UPDATE)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WidgetReconciler.reconcile(context, ReconcileReason.OPTIONS_CHANGED)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            WeatherRuntime.REFRESH -> WidgetReconciler.reconcile(context, ReconcileReason.DEADLINE)
            Intent.ACTION_TIME_CHANGED -> WidgetReconciler.reconcile(context, ReconcileReason.TIME_SET)
            Intent.ACTION_TIMEZONE_CHANGED -> WidgetReconciler.reconcile(context, ReconcileReason.TIMEZONE_CHANGED)
        }
    }
}

object WeatherRuntime {
    const val REFRESH = "com.alexindigo.kotatsu.weather.REFRESH"
    private const val PERIODIC = 0x4b5701
    internal const val IMMEDIATE = 0x4b5702
    internal const val RETRY = 0x4b5703
    fun ids(context: Context): IntArray = AppWidgetManager.getInstance(context)
        .getAppWidgetIds(ComponentName(context, WeatherWidgetProvider::class.java))

    internal fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, WeatherWidgetProvider::class.java).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    internal fun ensurePeriodic(context: Context) {
        if (ids(context).isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC,
            ComponentName(context, RefreshJobService::class.java)).setPeriodic(60 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build())
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC); cancel(IMMEDIATE); cancel(RETRY) }
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
        WidgetReconciler.forget()
    }

    fun refresh(context: Context, force: Boolean = false, now: Instant = WidgetReconciler.currentClock().instant(), clockChanged: Boolean = false) {
        if (ids(context).isEmpty()) return
        val store = WeatherStore(context)
        val forecasts = ForecastStore(context)
        val next = ids(context).flatMap { id ->
            val config = store.config(id) ?: return@flatMap emptyList<Instant>()
            config.locations.mapNotNull { location ->
                val snapshot = forecasts.snapshot(location, config.fahrenheit, WidgetReconciler.currentClock().zone)
                RefreshPolicy.nextAttempt(snapshot.series, snapshot.status, now)
            }
        }.minOrNull()
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (force || next != null) {
            val extras = android.os.PersistableBundle().apply { putBoolean("force", force) }
            val jobId = if (force) IMMEDIATE else RETRY
            val latency = if (force) 0 else java.time.Duration.between(now, next).toMillis().coerceAtLeast(0)
            val pending = scheduler.getPendingJob(jobId)
            // Repeated broadcasts must not postpone an already-enqueued retry.
            if (!force && !clockChanged && pending != null && pending.extras.getLong("at") == next!!.toEpochMilli()) return
            if (!force && !clockChanged && pending != null && pending.extras.getLong("at") <= now.toEpochMilli() && !next!!.isAfter(now)) return
            extras.putLong("at", (if (force) now else next!!).toEpochMilli())
            scheduler.schedule(JobInfo.Builder(jobId,
                ComponentName(context, RefreshJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(latency).setExtras(extras).build())
        } else scheduler.cancel(RETRY)
    }

}

class RefreshJobService : JobService() {
    companion object {
        /** Ingestion seam shared by real jobs and deterministic cache-to-widget tests. */
        @Volatile internal var fetcher: (Location, Boolean, String) -> String = OpenMeteo::forecast
    }
    private val executor = Executors.newSingleThreadExecutor()
    private val tasks = mutableMapOf<Int, Future<*>>()
    override fun onStartJob(params: JobParameters): Boolean {
        tasks[params.jobId] = executor.submit {
            try {
                ForecastRefresher.refresh(this, params.extras.getBoolean("force"), fetcher, WidgetReconciler.currentClock())
            } catch (error: RuntimeException) {
                Log.w("KotatsuWeather", "Forecast refresh failed; keeping cached data", error)
            } finally {
                Handler(Looper.getMainLooper()).post {
                    tasks.remove(params.jobId)
                    jobFinished(params, false)
                    WidgetReconciler.reconcile(this, ReconcileReason.JOB_DONE)
                }
            }
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel(true)
        return WeatherRuntime.ids(this).isNotEmpty()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}

object ForecastRefresher {
    fun refresh(context: Context, force: Boolean, fetch: (Location, Boolean, String) -> String, clock: Clock = Clock.systemDefaultZone()) {
        val store = WeatherStore(context)
        val forecasts = ForecastStore(context)
        val zone = clock.zone
        val visited = mutableSetOf<String>()
        for (id in WeatherRuntime.ids(context)) {
            val config = store.config(id) ?: continue
            for (location in config.locations) {
                if (Thread.currentThread().isInterrupted || WeatherRuntime.ids(context).isEmpty()) return
                if (!visited.add("${location.coordinateKey}|${config.fahrenheit}")) continue
                val snapshot = forecasts.snapshot(location, config.fahrenheit, zone)
                if (!force && !RefreshPolicy.due(snapshot.series, snapshot.status, clock.instant())) continue
                try {
                    val json = fetch(location, config.fahrenheit, zone.id)
                    forecasts.accept(location, config.fahrenheit, OpenMeteoParser.parse(json), clock.instant(), zone)
                } catch (error: Exception) {
                    forecasts.recordFailure(location, config.fahrenheit, RefreshPolicy.failure(error), clock.instant(), zone)
                    Log.w("KotatsuWeather", "Forecast unavailable; keeping cached data", error)
                }
            }
        }
    }
}
