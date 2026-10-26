package com.gezimos.katapult.weather.mudita

import com.gezimos.katapult.weather.api.WeatherCondition

/** Mudita's OpenWeather-compatible code/icon metadata ends here, never in a view. */
object MuditaConditions {
    fun condition(code: Int): WeatherCondition = when (code) {
        in 200..232 -> WeatherCondition.THUNDER
        in 300..321 -> WeatherCondition.DRIZZLE
        511 -> WeatherCondition.MIX
        in 500..531 -> WeatherCondition.RAIN
        in 611..616 -> WeatherCondition.MIX
        in 600..622 -> WeatherCondition.SNOW
        in 701..781 -> WeatherCondition.FOG
        800 -> WeatherCondition.CLEAR
        801 -> WeatherCondition.FEW_CLOUDS
        802, 803 -> WeatherCondition.PARTLY_CLOUDY
        else -> WeatherCondition.CLOUDY
    }

    fun daytime(iconInfo: String?): Boolean = iconInfo == null || iconInfo.endsWith("d")
}
