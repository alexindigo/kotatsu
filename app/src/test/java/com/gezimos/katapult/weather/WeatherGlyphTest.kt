package com.gezimos.katapult.weather

import com.gezimos.katapult.R
import com.gezimos.katapult.ui.weatherIconResource
import com.gezimos.katapult.weather.api.WeatherCondition
import org.junit.Assert.*
import org.junit.Test

class WeatherGlyphTest {
    @Test fun normalizedConditionsKeepEveryHistoricalDayAndNightGlyph() {
        val table = mapOf(
            WeatherCondition.CLEAR to (R.drawable.ic_weather_clear_day to R.drawable.ic_weather_clear_night),
            // D4 is not applied: Mudita's 801 still draws the clear sun/moon.
            WeatherCondition.FEW_CLOUDS to (R.drawable.ic_weather_clear_day to R.drawable.ic_weather_clear_night),
            WeatherCondition.PARTLY_CLOUDY to (R.drawable.ic_weather_partly_cloudy_day to R.drawable.ic_weather_partly_cloudy_night),
            WeatherCondition.CLOUDY to (R.drawable.ic_weather_cloudy to R.drawable.ic_weather_cloudy),
            WeatherCondition.DRIZZLE to (R.drawable.ic_weather_rainy_light to R.drawable.ic_weather_rainy_light),
            WeatherCondition.RAIN to (R.drawable.ic_weather_rainy to R.drawable.ic_weather_rainy),
            WeatherCondition.MIX to (R.drawable.ic_weather_mix to R.drawable.ic_weather_mix),
            WeatherCondition.SNOW to (R.drawable.ic_weather_snowy to R.drawable.ic_weather_snowy),
            WeatherCondition.THUNDER to (R.drawable.ic_weather_thunderstorm to R.drawable.ic_weather_thunderstorm),
            WeatherCondition.FOG to (R.drawable.ic_weather_foggy to R.drawable.ic_weather_foggy),
        )
        assertEquals(WeatherCondition.entries.toSet(), table.keys)
        table.forEach { (condition, glyphs) ->
            assertEquals("$condition/day", glyphs.first, weatherIconResource(condition, true))
            assertEquals("$condition/night", glyphs.second, weatherIconResource(condition, false))
        }
    }
}
