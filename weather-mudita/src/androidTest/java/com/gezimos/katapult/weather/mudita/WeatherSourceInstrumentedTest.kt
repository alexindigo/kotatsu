package com.gezimos.katapult.weather.mudita

import android.database.MatrixCursor
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.gezimos.katapult.weather.api.Weather
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
        assertEquals(Weather(21, "C", 801, "02n"), MuditaWeatherSource.parse(cursor))
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
        assertEquals(Weather(3, "F", 0, null), MuditaWeatherSource.parse(cursor))
    }
}
