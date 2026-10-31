package com.alexindigo.kotatsu.weather

import android.app.AlarmManager
import android.appwidget.AppWidgetManager
import android.content.Context
import android.widget.RemoteViews
import java.time.Clock
import java.time.ZonedDateTime

/** One transaction for every trigger: picture, deadline and independent network recovery. */
object WidgetReconciler {
    @Volatile internal var clock: Clock? = null
    private val published = mutableMapOf<Int, PublishedTimeline>()

    internal fun currentClock(): Clock = clock ?: Clock.systemDefaultZone()
    @Synchronized internal fun publishedModel(id: Int): TimelineModel? = published[id]?.model
    @Synchronized internal fun forget() { published.clear() }

    @Synchronized fun reconcile(context: Context, reason: ReconcileReason) {
        val ids = WeatherRuntime.ids(context)
        published.keys.retainAll(ids.toSet())
        if (ids.isEmpty()) { WeatherRuntime.cancel(context); return }
        val now = ZonedDateTime.now(currentClock())
        val manager = AppWidgetManager.getInstance(context)
        val configs = WeatherStore(context)
        val forecasts = ForecastStore(context)
        val density = context.resources.displayMetrics.density
        val models = ids.toList().mapNotNull { id ->
            val config = configs.config(id) ?: return@mapNotNull null
            val snapshots = config.locations.map { forecasts.snapshot(it, config.fahrenheit, now.zone) }
            val model = buildTimeline(config, snapshots.map { it.series }, snapshots.map { it.status }, now)
            val options = manager.getAppWidgetOptions(id)
            val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 300).coerceAtLeast(1) * density).toInt()
            val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112).coerceAtLeast(1) * density).toInt()
            val next = PublishedTimeline(model, width, height,
                model.lastUpdatedAt?.let { WeatherPresentation.lastUpdated(context, it, model.zone) })
            if (shouldPublish(published[id], next, reason)) {
                val bitmap = TimelineRenderer.render(context, model, width, height)
                manager.updateAppWidget(id, RemoteViews(context.packageName, R.layout.widget_timeline).apply {
                    setImageViewBitmap(R.id.timeline, bitmap)
                })
                published[id] = next
            }
            model
        }
        val alarms = context.getSystemService(AlarmManager::class.java)
        val deadline = models.minOfOrNull { it.validUntil }
        if (deadline == null) alarms.cancel(WeatherRuntime.alarmIntent(context))
        else alarms.setWindow(AlarmManager.RTC, deadline.toEpochMilli(), 60_000, WeatherRuntime.alarmIntent(context))
        WeatherRuntime.ensurePeriodic(context)
        WeatherRuntime.refresh(context, force = reason == ReconcileReason.CONFIG_CHANGED, now = now.toInstant(),
            clockChanged = reason == ReconcileReason.TIME_SET)
    }
}
