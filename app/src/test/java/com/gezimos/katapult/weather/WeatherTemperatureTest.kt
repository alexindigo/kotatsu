package com.gezimos.katapult.weather

import com.gezimos.katapult.weather.api.Weather
import org.junit.Assert.assertEquals
import org.junit.Test

class WeatherTemperatureTest {
    private fun weather(temperature: Int, unit: String) = Weather(temperature, unit,
        com.gezimos.katapult.weather.api.WeatherCondition.CLEAR, true)

    @Test fun celsiusConvertsToFahrenheitByDefault() {
        assertEquals("52°F", formatWeatherTemperature(weather(11, "C")))
        assertEquals("32°F", formatWeatherTemperature(weather(0, "C")))
        assertEquals("212°F", formatWeatherTemperature(weather(100, "°C")))
    }

    @Test fun fahrenheitConvertsToCelsiusWhenSelected() {
        assertEquals("11°C", formatWeatherTemperature(weather(52, "F"), celsius = true))
        assertEquals("0°C", formatWeatherTemperature(weather(32, "F"), celsius = true))
        assertEquals("100°C", formatWeatherTemperature(weather(212, "°F"), celsius = true))
    }

    @Test fun alreadyInTargetUnitKeepsTemperature() {
        assertEquals("11°C", formatWeatherTemperature(weather(11, "C"), celsius = true))
        assertEquals("52°F", formatWeatherTemperature(weather(52, "F")))
    }

    @Test fun unknownUnitKeepsProviderValueAndUnit() {
        for (celsius in listOf(false, true)) {
            assertEquals("7°K", formatWeatherTemperature(weather(7, "K"), celsius))
            assertEquals("-3°", formatWeatherTemperature(weather(-3, ""), celsius))
        }
    }

    @Test fun negativeTemperaturesRoundToNearestInteger() {
        assertEquals("-2°F", formatWeatherTemperature(weather(-19, "C")))
        assertEquals("-26°F", formatWeatherTemperature(weather(-32, "C")))
        assertEquals("-18°C", formatWeatherTemperature(weather(-1, "F"), celsius = true))
        assertEquals("-21°C", formatWeatherTemperature(weather(-5, "F"), celsius = true))
        assertEquals("-40°F", formatWeatherTemperature(weather(-40, "C")))
    }
}
