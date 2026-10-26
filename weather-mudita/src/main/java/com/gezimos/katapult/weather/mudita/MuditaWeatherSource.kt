package com.gezimos.katapult.weather.mudita

import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherSource

/** Reads the Mudita Weather app's widget provider. */
object MuditaWeatherSource : WeatherSource {

    const val PACKAGE = "com.mudita.weather"

    val WidgetUri: Uri = Uri.parse("content://$PACKAGE/widget_weather")

    override fun isAvailable(context: Context): Boolean =
        isMuditaDevice() && isInstalled(context)

    override fun openApp(context: Context) {
        val intent = context.packageManager.getLaunchIntentForPackage(PACKAGE)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) ?: return
        try {
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    override fun read(context: Context): Weather? = try {
        context.contentResolver.query(WidgetUri, null, null, null, null)?.use { cursor ->
            parse(cursor)
        }
    } catch (_: Exception) {
        null
    }

    override fun observe(context: Context, onChange: () -> Unit): AutoCloseable {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) = onChange()
        }
        context.contentResolver.registerContentObserver(WidgetUri, false, observer)
        return AutoCloseable { context.contentResolver.unregisterContentObserver(observer) }
    }

    /** Maps the first row of a widget cursor; null when empty, in error or without a temperature. */
    fun parse(cursor: Cursor): Weather? {
        if (!cursor.moveToFirst()) return null
        if (intOrNull(cursor, "is_error") != 0) return null
        val temperature = intOrNull(cursor, "temperature") ?: return null
        return Weather(
            temperature = temperature,
            unit = strOrNull(cursor, "measurement_unit").orEmpty(),
            condition = MuditaConditions.condition(intOrNull(cursor, "weather_icon_id") ?: 0),
            daytime = MuditaConditions.daytime(strOrNull(cursor, "weather_icon_info")),
        )
    }

    private fun isMuditaDevice(): Boolean =
        Build.BRAND.equals("Mudita", ignoreCase = true) ||
            Build.MODEL.equals("Kompakt", ignoreCase = true) ||
            Build.DEVICE.equals("Kompakt", ignoreCase = true) ||
            Build.PRODUCT.equals("Kompakt", ignoreCase = true)

    private fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(PACKAGE, 0)
        true
    } catch (_: Exception) {
        false
    }

    private fun intOrNull(cursor: Cursor, name: String): Int? {
        val index = cursor.getColumnIndex(name)
        return if (index < 0 || cursor.isNull(index)) null else cursor.getInt(index)
    }

    private fun strOrNull(cursor: Cursor, name: String): String? {
        val index = cursor.getColumnIndex(name)
        return if (index < 0 || cursor.isNull(index)) null else cursor.getString(index)
    }
}
