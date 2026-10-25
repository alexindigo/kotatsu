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
import java.time.ZoneId
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
    private const val IMMEDIATE = 0x4b5702
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
        context.getSystemService(JobScheduler::class.java).apply { cancel(PERIODIC); cancel(IMMEDIATE) }
        context.getSystemService(AlarmManager::class.java).cancel(alarmIntent(context))
    }

    fun refresh(context: Context, force: Boolean = false) {
        if (ids(context).isEmpty()) return
        val store = WeatherStore(context)
        val now = System.currentTimeMillis()
        val needed = force || ids(context).any { id ->
            val config = store.config(id) ?: return@any false
            config.locations.any { location ->
                val cached = store.cached(location, config.fahrenheit)
                cached == null || now - cached.fetchedAt > 10 * 60 * 1000L
            }
        }
        if (needed) {
            val extras = android.os.PersistableBundle().apply { putBoolean("force", force) }
            context.getSystemService(JobScheduler::class.java).schedule(JobInfo.Builder(IMMEDIATE,
                ComponentName(context, RefreshJobService::class.java)).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                .setMinimumLatency(0).setExtras(extras).build())
        }
    }

    fun redraw(context: Context, id: Int) {
        if (id !in ids(context)) return
        val manager = AppWidgetManager.getInstance(context)
        val options = manager.getAppWidgetOptions(id)
        val density = context.resources.displayMetrics.density
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(1)
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112).coerceAtLeast(1)
        val config = WeatherStore(context).config(id) ?: return
        val forecasts = config.locations.map { WeatherStore(context).cached(it, config.fahrenheit) }
        val bitmap = TimelineRenderer.render(context, config, forecasts,
            (width * density).toInt(), (height * density).toInt())
        manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_timeline).apply {
            setImageViewBitmap(R.id.timeline, bitmap)
        })
    }
}

class RefreshJobService : JobService() {
    private val executor = Executors.newSingleThreadExecutor()
    private val tasks = mutableMapOf<Int, Future<*>>()
    override fun onStartJob(params: JobParameters): Boolean {
        tasks[params.jobId] = executor.submit {
            val store = WeatherStore(this)
            val zone = ZoneId.systemDefault().id
            val visited = mutableSetOf<String>()
            for (id in WeatherRuntime.ids(this)) {
                val config = store.config(id) ?: continue
                for (location in config.locations) {
                    if (Thread.currentThread().isInterrupted || WeatherRuntime.ids(this).isEmpty()) return@submit
                    if (!visited.add("${location.coordinateKey}|${config.fahrenheit}")) continue
                    val cached = store.cached(location, config.fahrenheit, zone)
                    if (params.extras.getBoolean("force") || params.isPeriodic() || cached == null ||
                        System.currentTimeMillis() - cached.fetchedAt > 10 * 60 * 1000L) {
                        try {
                            val json = OpenMeteo.forecast(location, config.fahrenheit, zone)
                            store.cache(location, config.fahrenheit, zone, json, System.currentTimeMillis())
                        } catch (error: Exception) {
                            Log.w("KotatsuWeather", "Forecast unavailable; keeping cached data", error)
                        }
                    }
                }
            }
            WeatherRuntime.ids(this).forEach { WeatherRuntime.redraw(this, it) }
            jobFinished(params, false)
        }
        return true
    }
    // JobParameters has no periodic flag; identify our one periodic job by its stable ID.
    private fun JobParameters.isPeriodic(): Boolean = jobId == 0x4b5701
    override fun onStopJob(params: JobParameters): Boolean {
        tasks.remove(params.jobId)?.cancel(true)
        return WeatherRuntime.ids(this).isNotEmpty()
    }
    override fun onDestroy() { executor.shutdownNow(); super.onDestroy() }
}
