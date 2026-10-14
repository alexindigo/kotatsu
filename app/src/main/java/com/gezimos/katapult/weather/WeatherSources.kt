package com.gezimos.katapult.weather

import android.content.Context
import com.gezimos.katapult.weather.api.WeatherSource
import com.gezimos.katapult.weather.mudita.MuditaWeatherSource

/** The only place in :app that names a weather plugin. Everything else uses [WeatherSource]. */
object WeatherSources {

    /** The weather source usable on this device, or null when weather is unavailable. */
    fun active(context: Context): WeatherSource? =
        MuditaWeatherSource.takeIf { it.isAvailable(context) }
}
