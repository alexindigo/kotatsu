package com.gezimos.katapult.lockscreen

import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetManager
import android.content.Context
import android.os.Bundle
import android.view.WindowManager

/** One callback owner for both configuration and the accessibility service. */
class LockscreenWidgetHost private constructor(context: Context) : AppWidgetHost(context.applicationContext, 0x4B54) {
    private var listening = false

    fun listen(enabled: Boolean) {
        if (listening == enabled) return
        if (enabled) startListening() else stopListening()
        listening = enabled
    }

    fun releaseViews() { clearViews() }

    companion object {
        @Volatile private var instance: LockscreenWidgetHost? = null
        fun get(context: Context): LockscreenWidgetHost = instance ?: synchronized(this) {
            instance ?: LockscreenWidgetHost(context).also { instance = it }
        }

        fun availableDp(context: Context): Int {
            val bounds = context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds
            return ((bounds.height() - WIDGETS_MEASURED_DATE_BOTTOM_PX) / context.resources.displayMetrics.density - 8).toInt()
        }

        fun updateSize(context: Context, widget: LockscreenWidget) {
            val density = context.resources.displayMetrics.density
            val width = (context.getSystemService(WindowManager::class.java).currentWindowMetrics.bounds.width() / density).toInt() - 16
            AppWidgetManager.getInstance(context).updateAppWidgetOptions(widget.id, Bundle().apply {
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, width)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, widget.size.heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, widget.size.heightDp)
                putInt(AppWidgetManager.OPTION_APPWIDGET_HOST_CATEGORY, android.appwidget.AppWidgetProviderInfo.WIDGET_CATEGORY_KEYGUARD)
            })
        }
    }
}
