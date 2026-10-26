package com.alexindigo.kotatsu.weather

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Bundle
import android.os.SystemClock
import android.widget.ImageView
import android.widget.RemoteViews
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Actual provider -> RemoteViews -> host ImageView, with all fixture-owned state restored. */
internal class WeatherHostFixture : AutoCloseable {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("weather", 0)
    private val before = prefs.all
    private val previousFetcher = RefreshJobService.fetcher
    val latest = AtomicReference<Bitmap?>()
    val updates = AtomicInteger()
    val manager = AppWidgetManager.getInstance(context)
    private val host = object : AppWidgetHost(context, 0x4b5752) {
        override fun onCreateView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
            object : AppWidgetHostView(context) {
                override fun updateAppWidget(remoteViews: RemoteViews?) {
                    super.updateAppWidget(remoteViews)
                    (findViewById<ImageView>(R.id.timeline)?.drawable as? BitmapDrawable)?.bitmap?.let {
                        latest.set(it); updates.incrementAndGet()
                    }
                }
            }
    }
    private val initialIds = host.appWidgetIds.toSet()
    val id: Int
    val width = (464 * context.resources.displayMetrics.density).toInt()
    val height = (112 * context.resources.displayMetrics.density).toInt()

    init {
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        id = host.allocateAppWidgetId()
        assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
        instrumentation.runOnMainSync { host.createView(context, id, manager.getAppWidgetInfo(id)); host.startListening() }
        await("Initial provider bitmap") { updates.get() > 0 }
    }

    fun configure(config: WidgetConfig) {
        assertTrue(prefs.edit().putString("widget_$id", ConfigJson.encode(config)).commit())
        pulse()
    }

    fun pulse() = instrumentation.runOnMainSync {
        manager.updateAppWidgetOptions(id, Bundle().apply {
            putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 464)
            putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112)
        })
    }

    fun await(description: String, timeout: Long = 15_000, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
        assertTrue(description, ready())
    }

    /** Provider publication and the host's RemoteViews callback are separate asynchronous boundaries. */
    fun awaitPublishedModel(description: String, timeout: Long = 15_000, accepts: (TimelineModel) -> Boolean): TimelineModel {
        var observed: TimelineModel? = null
        await(description, timeout) {
            val model = WidgetReconciler.publishedModel(id)
            if (model != null && accepts(model) && latest.get()?.sameAs(TimelineRenderer.render(context, model, width, height)) == true) {
                observed = model
                true
            } else false
        }
        return checkNotNull(observed)
    }

    override fun close() {
        WeatherRuntime.cancel(context)
        instrumentation.runOnMainSync { host.stopListening(); host.deleteAppWidgetId(id) }
        WeatherStore(context).delete(id)
        RefreshJobService.fetcher = previousFetcher
        instrumentation.uiAutomation.dropShellPermissionIdentity()
        val edit = prefs.edit()
        (prefs.all.keys - before.keys).forEach(edit::remove)
        before.forEach { (key, value) -> if (value is String) edit.putString(key, value) }
        assertTrue(edit.commit())
        assertEquals(before, prefs.all)
        assertEquals(initialIds, host.appWidgetIds.toSet())
    }
}
