package com.gezimos.katapult.weather

import com.gezimos.katapult.weather.api.Weather
import kotlin.math.roundToInt

/** Convert the provider's reported unit, keeping unknown values in their original unit. */
internal fun formatWeatherTemperature(weather: Weather, celsius: Boolean = false): String {
    val temperature = weather.temperature
    return when (weather.unit) {
        "C", "°C" -> if (celsius) "$temperature°C" else "${(temperature * 9.0 / 5 + 32).roundToInt()}°F"
        "F", "°F" -> if (celsius) "${((temperature - 32.0) * 5 / 9).roundToInt()}°C" else "$temperature°F"
        else -> "$temperature°${weather.unit}"
    }
}
