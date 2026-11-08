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
    enum class Surface { BOX, SIDE }
    private val listeners = mutableSetOf<Surface>()
    private val surfaces = mutableMapOf<Surface, List<LockscreenWidget>>()
    private data class SideSize(val width: Int, val height: Int)
    private val sideSizes = mutableMapOf<Int, SideSize>()

    fun listen(enabled: Boolean) {
        listen(Surface.BOX, enabled)
    }

    internal fun listen(surface: Surface, enabled: Boolean) {
        if (enabled) listeners.add(surface) else listeners.remove(surface)
        val wanted = listeners.isNotEmpty()
        if (listening == wanted) return
        if (wanted) startListening() else stopListening()
        listening = wanted
    }

    fun releaseViews() { clearViews(); views.clear(); visibility.clear(); surfaces.clear(); sideSizes.clear() }

    internal fun createWidgetView(context: Context, widget: LockscreenWidget, info: AppWidgetProviderInfo): AppWidgetHostView {
        updateSize(context, widget)
        return createView(context, widget.id, info)
    }

    /** Before detaching the box, classify all subsequent callbacks as hidden, then notify providers. */
    internal fun hideWidgets(context: Context) {
        showSurface(context, Surface.BOX, emptyList())
    }

    internal fun showSurface(context: Context, surface: Surface, widgets: List<LockscreenWidget>) {
        surfaces[surface] = widgets
        publishVisibility(context, visibility.reconcileShown(surfaces.values.flatten()))
    }

    internal fun sideSize(context: Context, id: Int, width: Int, height: Int) {
        val size = SideSize(width.coerceAtLeast(1), height.coerceAtLeast(1))
        if (sideSizes.put(id, size) != size) updateSize(context, LockscreenWidget(id, LockscreenWidgetSize.L))
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
            val density = context.resources.displayMetrics.density
            return (LockscreenWidgetBoxGeometry.from(bounds, density).content.height() / density).toInt()
        }

        /** Optional generic host hint. Providers may ignore it; absent means unspecified. */
        internal const val OPTION_HOST_VISIBLE = "kotatsu.host.visible"
        internal const val OPTION_HOST_TITLE = "kotatsu.host.title"
        internal const val OPTION_HOST_WAKE = "kotatsu.host.wake"

        fun updateSize(context: Context, widget: LockscreenWidget) {
            val density = context.resources.displayMetrics.density
            val host = get(context)
            val side = host.sideSizes[widget.id]
            val width = side?.width ?: (LockscreenWidgetBoxGeometry.from(
                context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds, density).content.width() / density).toInt()
            val height = side?.height ?: widget.size.heightDp
            AppWidgetManager.getInstance(context).updateAppWidgetOptions(widget.id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, height)
                putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, if (side != null) AppWidgetProviderInfo.WIDGET_CATEGORY_HOME_SCREEN else AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
                putBoolean(OPTION_HOST_TITLE, side != null)
                val visible = host.visibility.hint(widget.id)
                // Options are merged by AppWidgetManager. A hide/resize keeps the last shown wake identity.
                if (visible != false) putLong(OPTION_HOST_WAKE, HostedWidgetWake(context).current())
                visible?.let { putBoolean(OPTION_HOST_VISIBLE, it) }
            })
        }

        internal fun pulseVisible(context: Context, visibility: LockscreenVisibility, shown: List<LockscreenWidget>,
                                  selected: List<LockscreenWidget> = shown) {
            val host = get(context)
            host.surfaces[Surface.BOX] = shown
            val hidden = host.visibility.includeHidden(selected)
            host.publishVisibility(context, (host.visibility.reconcile(visibility, host.surfaces.values.flatten()) + hidden)
                .distinctBy { it.id })
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
