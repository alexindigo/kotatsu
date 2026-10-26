package com.gezimos.katapult.weather.api

import android.content.Context

/** Provider-independent conditions; adapters own the translation from their native codes. */
enum class WeatherCondition { CLEAR, FEW_CLOUDS, PARTLY_CLOUDY, CLOUDY, DRIZZLE, RAIN, MIX, SNOW, THUNDER, FOG }

/** Current conditions, already normalized at the source boundary. */
data class Weather(
    val temperature: Int,
    val unit: String,
    val condition: WeatherCondition,
    val daytime: Boolean,
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
