package com.gezimos.katapult.weather.mudita

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gezimos.katapult.weather.api.Weather
import com.gezimos.katapult.weather.api.WeatherCondition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WeatherSourceInstrumentedTest {

    private val columns = arrayOf(
        "is_error", "temperature", "measurement_unit", "weather_icon_id", "weather_icon_info",
    )

    @Test
    fun parsesFirstRowOfWidgetCursor() {
        val cursor = MatrixCursor(columns).apply {
            addRow(arrayOf<Any?>(0, 21, "C", 801, "02n"))
            addRow(arrayOf<Any?>(0, 5, "C", 500, "10d"))
        }
        assertEquals(Weather(21, "C", WeatherCondition.FEW_CLOUDS, false), MuditaWeatherSource.parse(cursor))
    }

    @Test
    fun emptyCursorIsNull() {
        assertNull(MuditaWeatherSource.parse(MatrixCursor(columns)))
    }

    @Test
    fun errorRowIsNull() {
        val cursor = MatrixCursor(columns).apply { addRow(arrayOf<Any?>(1, 21, "C", 800, "01d")) }
        assertNull(MuditaWeatherSource.parse(cursor))
    }

    @Test
    fun rowWithoutTemperatureIsNull() {
        val cursor = MatrixCursor(columns).apply { addRow(arrayOf<Any?>(0, null, "C", 800, "01d")) }
        assertNull(MuditaWeatherSource.parse(cursor))
    }

    @Test
    fun missingIconColumnsUseDefaults() {
        val cursor = MatrixCursor(arrayOf("is_error", "temperature", "measurement_unit"))
            .apply { addRow(arrayOf<Any?>(0, 3, "F")) }
        assertEquals(Weather(3, "F", WeatherCondition.CLOUDY, true), MuditaWeatherSource.parse(cursor))
    }

    @Test fun everyPreviouslyHandledCodeAndIconNormalizesWithoutChangingItsGlyphClass() {
        // Exhaustive ranges, including the holes the old view also mapped. Independent old-code
        // oracle: together with the launcher's normalized glyph table, this proves the composition.
        for (code in -1..1000) {
            val expected = when {
                code in 200..232 -> WeatherCondition.THUNDER
                code in 300..321 -> WeatherCondition.DRIZZLE
                code == 511 || code in 611..616 -> WeatherCondition.MIX
                code in 500..531 -> WeatherCondition.RAIN
                code in 600..622 -> WeatherCondition.SNOW
                code in 701..781 -> WeatherCondition.FOG
                code == 800 -> WeatherCondition.CLEAR
                code == 801 -> WeatherCondition.FEW_CLOUDS
                code == 802 || code == 803 -> WeatherCondition.PARTLY_CLOUDY
                else -> WeatherCondition.CLOUDY
            }
            for ((icon, day) in listOf(null to true, "01d" to true, "02n" to false, "" to false, "x" to false)) {
                val cursor = MatrixCursor(columns).apply { addRow(arrayOf<Any?>(0, 21, "C", code, icon)) }
                cursor.use { assertEquals("$code/$icon", Weather(21, "C", expected, day), MuditaWeatherSource.parse(it)) }
            }
        }
    }
}
