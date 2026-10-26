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
import android.widget.RemoteViews
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import android.os.Handler
import android.os.Looper
import java.util.concurrent.Executors
import java.util.concurrent.Future

class WeatherWidgetProvider : AppWidgetProvider() {
    override fun onEnabled(context: Context) { WeatherRuntime.schedule(context) }
    override fun onDisabled(context: Context) { WeatherRuntime.cancel(context) }
    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        val store = WeatherStore(context)
        appWidgetIds.forEach(store::delete)
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = WeatherStore(context)
        oldWidgetIds.zip(newWidgetIds).forEach { (old, new) -> store.restore(old, new) }
    }
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        if (WeatherRuntime.ids(context).isEmpty()) return
        WeatherRuntime.schedule(context)
        ids.forEach { WeatherRuntime.redraw(context, it) }
        WeatherRuntime.refresh(context)
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        WeatherRuntime.redraw(context, id)
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WeatherRuntime.REFRESH && WeatherRuntime.ids(context).isNotEmpty()) {
            WeatherRuntime.ids(context).forEach { WeatherRuntime.redraw(context, it) }
            WeatherRuntime.refresh(context)
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

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(context, 0,
        Intent(context, WeatherWidgetProvider::class.java).setAction(REFRESH), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun schedule(context: Context) {
        if (ids(context).isEmpty()) return
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (scheduler.getPendingJob(PERIODIC) == null) scheduler.schedule(JobInfo.Builder(PERIODIC,
            ComponentName(context, RefreshJobService::class.java)).setPeriodic(60 * 60 * 1000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(false).build())
        context.getSystemService(AlarmManager::class.java).setInexactRepeating(AlarmManager.RTC,
            System.currentTimeMillis() + 15 * 60 * 1000L, 15 * 60 * 1000L, alarmIntent(context))
    }

    fun cancel(context: Context) {
        context.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC); cancel(IMMEDIATE); cancel(RETRY) }
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    fun refresh(context: Context, force: Boolean = false) {
        if (ids(context).isEmpty()) return
        val store = WeatherStore(context)
        val forecasts = ForecastStore(context)
        val now = Instant.now()
        val next = ids(context).flatMap { id ->
            val config = store.config(id) ?: return@flatMap emptyList<Instant>()
            config.locations.mapNotNull { location ->
                val snapshot = forecasts.snapshot(location, config.fahrenheit)
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
            if (!force && pending != null && pending.extras.getLong("at") == next!!.toEpochMilli()) return
            extras.putLong("at", (if (force) now else next!!).toEpochMilli())
            scheduler.schedule(JobInfo.Builder(jobId,
                ComponentName(context, RefreshJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(latency).setExtras(extras).build())
        } else scheduler.cancel(RETRY)
    }

    fun redraw(context: Context, id: Int) {
        if (id !in ids(context)) return
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(1)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112).coerceAtLeast(1)
        val config = WeatherStore(context).config(id) ?: return
        val snapshots = config.locations.map { ForecastStore(context).snapshot(it, config.fahrenheit) }
        val model = buildTimeline(config, snapshots.map { it.series }, snapshots.map { it.status }, ZonedDateTime.now())
        val bitmap = TimelineRenderer.render(context, model,
            (width * density).toInt(), (height * density).toInt())
        manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_timeline).apply {
            setImageViewBitmap(R.id.timeline, bitmap)
        })
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
                ForecastRefresher.refresh(this, params.extras.getBoolean("force"), fetcher)
            } catch (error: RuntimeException) {
                Log.w("KotatsuWeather", "Forecast refresh failed; keeping cached data", error)
            } finally {
                Handler(Looper.getMainLooper()).post {
                    tasks.remove(params.jobId)
                    jobFinished(params, false)
                    WeatherRuntime.ids(this).forEach { WeatherRuntime.redraw(this, it) }
                    WeatherRuntime.refresh(this)
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
    fun refresh(context: Context, force: Boolean, fetch: (Location, Boolean, String) -> String) {
        val store = WeatherStore(context)
        val forecasts = ForecastStore(context)
        val zone = ZoneId.systemDefault()
        val visited = mutableSetOf<String>()
        for (id in WeatherRuntime.ids(context)) {
            val config = store.config(id) ?: continue
            for (location in config.locations) {
                if (Thread.currentThread().isInterrupted || WeatherRuntime.ids(context).isEmpty()) return
                if (!visited.add("${location.coordinateKey}|${config.fahrenheit}")) continue
                val snapshot = forecasts.snapshot(location, config.fahrenheit, zone)
                if (!force && !RefreshPolicy.due(snapshot.series, snapshot.status, Instant.now())) continue
                try {
                    val json = fetch(location, config.fahrenheit, zone.id)
                    forecasts.accept(location, config.fahrenheit, OpenMeteoParser.parse(json), Instant.now(), zone)
                } catch (error: Exception) {
                    forecasts.recordFailure(location, config.fahrenheit, RefreshPolicy.failure(error), Instant.now(), zone)
                    Log.w("KotatsuWeather", "Forecast unavailable; keeping cached data", error)
                }
            }
        }
    }
}
