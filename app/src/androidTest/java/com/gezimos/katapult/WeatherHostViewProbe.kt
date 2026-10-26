package com.gezimos.katapult

import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.ImageView
import androidx.test.platform.app.InstrumentationRegistry

/** A host's initial drawable can predate its options callback. Observe the current-size picture
 * before using it as an oracle for a later pulse; every host-delivery test shares this barrier. */
internal class WeatherHostViewProbe(private val context: Context, private val view: AppWidgetHostView, private val imageId: Int) {
    fun awaitSizedBitmap(): Bitmap {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val deadline = SystemClock.elapsedRealtime() + 15_000
        var observed: Bitmap? = null
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(view.appWidgetId)
                val density = context.resources.displayMetrics.density
                val width = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) * density).toInt()
                val height = (options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) * density).toInt()
                val bitmap = (view.findViewById<ImageView>(imageId)?.drawable as? BitmapDrawable)?.bitmap
                if (bitmap != null && bitmap.width == width && bitmap.height == height) {
                    observed = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
            }
            observed?.let { return it }
            Thread.sleep(25)
        }
        throw AssertionError("Host did not receive a bitmap for its current size options")
    }
}
