package com.alexindigo.kotatsu.weather

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ForecastValidationInstrumentedTest {
    @Test fun extraConditionElementIsMalformed() {
        val json = InstrumentationRegistry.getInstrumentation().context.assets.open("forecast.json")
            .bufferedReader().use { JSONObject(it.readText()) }
        json.getJSONObject("hourly").getJSONArray("weather_code").put(0)
        assertTrue(OpenMeteoParser.parseLegacy(json.toString()) is Parsed.Malformed)
    }
}
