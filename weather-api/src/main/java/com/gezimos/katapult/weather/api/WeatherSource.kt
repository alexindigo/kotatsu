package com.gezimos.katapult.weather.api

import android.content.Context

/** Current conditions as a weather source reports them. */
data class Weather(
    val temperature: Int,
    val unit: String,
    val conditionId: Int,
    val iconInfo: String?,
)

/** A provider of current weather. Implementations live in their own modules. */
interface WeatherSource {
    /** True when this source can supply weather on this device right now. */
    fun isAvailable(context: Context): Boolean

    /** The current weather, or null when there is none. */
    fun read(context: Context): Weather?

    /** Calls [onChange] when the provider's data changes until the returned handle is closed. */
    fun observe(context: Context, onChange: () -> Unit): AutoCloseable

    /** Opens the provider's own app. */
    fun openApp(context: Context)
}
