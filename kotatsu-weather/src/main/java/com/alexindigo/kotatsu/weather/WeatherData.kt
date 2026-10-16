package com.alexindigo.kotatsu.weather

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.ZoneId

data class Place(val name: String, val display: String, val latitude: Double, val longitude: Double)

object WeatherJson {
    fun forecast(json: String, fetchedAt: Long): Forecast {
        val hourly = JSONObject(json).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val temperatures = hourly.getJSONArray("temperature_2m")
        val codes = hourly.getJSONArray("weather_code")
        val gusts = hourly.getJSONArray("wind_gusts_10m")
        return Forecast((0 until times.length()).mapNotNull {
            if (temperatures.isNull(it) || codes.isNull(it) || gusts.isNull(it)) null
            else Hour(LocalDateTime.parse(times.getString(it)), temperatures.getDouble(it), codes.getInt(it), gusts.getDouble(it))
        }, fetchedAt)
    }

    fun places(json: String): List<Place> {
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        return (0 until results.length()).map {
            val row = results.getJSONObject(it)
            val name = row.getString("name")
            Place(name, listOf(name, row.optString("admin1"), row.optString("country"))
                .filter { part -> part.isNotBlank() }.joinToString(", "), row.getDouble("latitude"), row.getDouble("longitude"))
        }
    }
}

class WeatherStore(context: Context) {
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    fun config(id: Int): WidgetConfig = prefs.getString("widget_$id", null)?.let {
        runCatching { ConfigJson.decode(it) }.getOrNull()
    } ?: WidgetConfig(emptyList())
    fun save(id: Int, config: WidgetConfig) { prefs.edit().putString("widget_$id", ConfigJson.encode(config)).apply() }
    fun delete(id: Int) { prefs.edit().remove("widget_$id").apply() }

    private fun key(location: Location, fahrenheit: Boolean, zone: String): String {
        val input = "${location.coordinateKey}|$fahrenheit|$zone"
        return "cache_" + MessageDigest.getInstance("SHA-256").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    fun cached(location: Location, fahrenheit: Boolean, zone: String = ZoneId.systemDefault().id): Forecast? =
        prefs.getString(key(location, fahrenheit, zone), null)?.let {
            runCatching {
                val envelope = JSONObject(it)
                WeatherJson.forecast(envelope.getString("data"), envelope.getLong("fetchedAt"))
            }.getOrNull()
        }

    fun cache(location: Location, fahrenheit: Boolean, zone: String, json: String, now: Long) {
        // Parse first: a failed/partial response must never replace a usable forecast.
        WeatherJson.forecast(json, now)
        prefs.edit().putString(key(location, fahrenheit, zone), JSONObject()
            .put("fetchedAt", now).put("data", json).toString()).apply()
    }
}

object OpenMeteo {
    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.instanceFollowRedirects = false
            check(connection.responseCode == 200) { "Weather server returned ${connection.responseCode}" }
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally { connection.disconnect() }
    }

    fun forecast(location: Location, fahrenheit: Boolean, zone: String): String = get(
        "https://api.open-meteo.com/v1/forecast?latitude=${location.coordinateKey.substringBefore(',')}" +
            "&longitude=${location.coordinateKey.substringAfter(',')}" +
            "&hourly=temperature_2m,weather_code,wind_gusts_10m" +
            "&temperature_unit=${if (fahrenheit) "fahrenheit" else "celsius"}" +
            "&wind_speed_unit=${if (fahrenheit) "mph" else "kmh"}&timezone=${encode(zone)}&forecast_days=2")

    fun search(query: String): List<Place> = WeatherJson.places(get(
        "https://geocoding-api.open-meteo.com/v1/search?name=${encode(query)}&count=10&language=en&format=json"))
}
