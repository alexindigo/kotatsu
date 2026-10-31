package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Context
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import android.widget.RemoteViews

/** One callback owner for both configuration and the accessibility service. */
class LockscreenWidgetHost private constructor(context: Context) : AppWidgetHost(context.applicationContext, 0x4B54) {
    private var listening = false
    internal var pictureClock: () -> Long = SystemClock::elapsedRealtime
    private val views = mutableMapOf<Int, UnpaddedHostView>()
    private val visibility = HostedWidgetVisibility()

    fun listen(enabled: Boolean) {
        if (listening == enabled) return
        if (enabled) startListening() else stopListening()
        listening = enabled
    }

    fun releaseViews() { clearViews(); views.clear(); visibility.clear() }

    internal fun createWidgetView(context: Context, widget: LockscreenWidget, info: AppWidgetProviderInfo): AppWidgetHostView {
        updateSize(context, widget)
        return createView(context, widget.id, info)
    }

    /** Before detaching the box, classify all subsequent callbacks as hidden, then notify providers. */
    internal fun hideWidgets(context: Context) {
        publishVisibility(context, visibility.hide())
    }

    private fun publishVisibility(context: Context, changed: List<LockscreenWidget>) {
        changed.forEach { widget ->
            // Synchronous replay precedes both attaching the box and requesting the provider's fresh picture.
            views[widget.id]?.hostVisibility(visibility.hint(widget.id) == true)
            updateSize(context, widget)
        }
    }

    // The box sizes and spaces widgets itself. Android re-applies its default widget padding whenever it
    // (re)binds a view — including after the provider app updates (onProviderChanged → resetAppWidget →
    // setAppWidget) — so the view type owns zero padding instead of a one-off setPadding by the caller.
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        UnpaddedHostView(context) { pictureClock() }.also {
            it.hostVisibility(visibility.hint(appWidgetId) == true)
            views[appWidgetId] = it
        }

    companion object {
        @Volatile private var instance: LockscreenWidgetHost? = null
        fun get(context: Context): LockscreenWidgetHost = instance ?: synchronized(this) {
            instance ?: LockscreenWidgetHost(context).also { instance = it }
        }

        fun availableDp(context: Context): Int {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            return ((bounds.height() - WIDGETS_MEASURED_DATE_BOTTOM_PX) / context.resources.displayMetrics.density - 8).toInt()
        }

        /** Optional generic host hint. Providers may ignore it; absent means unspecified. */
        internal const val OPTION_HOST_VISIBLE = "kotatsu.host.visible"

        fun updateSize(context: Context, widget: LockscreenWidget) {
            val density = context.resources.displayMetrics.density
            val width = (context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width() / density).toInt() - 16
            AppWidgetManager.getInstance(context).updateAppWidgetOptions(widget.id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, widget.size.heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, widget.size.heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
                get(context).visibility.hint(widget.id)?.let { putBoolean(OPTION_HOST_VISIBLE, it) }
            })
        }

        internal fun pulseVisible(context: Context, visibility: LockscreenVisibility, shown: List<LockscreenWidget>) {
            val host = get(context)
            host.publishVisibility(context, host.visibility.reconcile(visibility, shown))
        }
    }
}

/** A widget view whose padding stays zero through every (re)bind; see [LockscreenWidgetHost.onCreateView]. */
internal class UnpaddedHostView(context: Context, clock: () -> Long = SystemClock::elapsedRealtime) : AppWidgetHostView(context) {
    private val picture = VisibleWidgetPicture<RemoteViews>(clock)

    override fun updateAppWidget(remoteViews: RemoteViews?) {
        super.updateAppWidget(remoteViews)
        picture.receive(remoteViews)
    }

    internal fun hostVisibility(visible: Boolean) {
        // Bypass receipt: replaying must never make the old picture young again.
        picture.visibility(visible)?.let { super.updateAppWidget(it) }
    }

    override fun setAppWidget(appWidgetId: Int, info: AppWidgetProviderInfo?) {
        picture.clear()
        super.setAppWidget(appWidgetId, info)
        setPadding(0, 0, 0, 0)
    }
}
