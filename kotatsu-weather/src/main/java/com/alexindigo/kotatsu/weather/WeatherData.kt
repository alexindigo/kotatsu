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
    private val context = context.applicationContext
    private val prefs = context.getSharedPreferences("weather", Context.MODE_PRIVATE)
    /** null means unreadable, never an empty editable configuration. Raw bytes stay untouched. */
    fun config(id: Int): WidgetConfig? = synchronized(configLock) {
        val raw = prefs.getString("widget_$id", null) ?: return@synchronized WidgetConfig(emptyList())
        runCatching { ConfigJson.decode(raw) }.getOrElse {
            android.util.Log.w("KotatsuWeather", "Widget configuration unreadable; retaining original data")
            null
        }
    }

    enum class Result { APPLIED, GONE, UNREADABLE, FULL, MISSING, WRITE_FAILED }

    /** No caller can replace a list. All read/modify/write operations share this process-wide lock. */
    private fun change(id: Int, operation: (WidgetConfig) -> WidgetConfig?): Result = synchronized(configLock) {
        if (id !in WeatherRuntime.ids(context)) return@synchronized Result.GONE
        val current = config(id) ?: return@synchronized Result.UNREADABLE
        val next = operation(current) ?: return@synchronized Result.MISSING
        check(next.locations.map { it.id }.distinct().size == next.locations.size)
        if (prefs.edit().putString("widget_$id", ConfigJson.encode(next)).commit()) Result.APPLIED else Result.WRITE_FAILED
    }

    fun add(id: Int, location: Location): Result = synchronized(configLock) {
        if (id !in WeatherRuntime.ids(context)) return@synchronized Result.GONE
        val current = config(id) ?: return@synchronized Result.UNREADABLE
        if (current.locations.size >= MAX_LOCATIONS) return@synchronized Result.FULL
        change(id) { it.copy(locations = it.locations + location.copy(id = java.util.UUID.randomUUID().toString())) }
    }

    fun remove(id: Int, placeId: String): Result = change(id) { current ->
        if (current.locations.none { it.id == placeId }) null
        else current.copy(locations = current.locations.filterNot { it.id == placeId })
    }

    fun edit(id: Int, placeId: String, label: String, start: Int, end: Int): Result = change(id) { current ->
        if (current.locations.none { it.id == placeId }) null
        else current.copy(locations = current.locations.map {
            if (it.id == placeId) it.copy(label = shortLabel(label), start = start, end = end) else it
        })
    }

    fun move(id: Int, placeId: String, delta: Int): Result = change(id) { current ->
        val rows = current.locations.toMutableList()
        val from = rows.indexOfFirst { it.id == placeId }
        val to = from + delta
        if (from !in rows.indices || to !in rows.indices) null else {
            java.util.Collections.swap(rows, from, to)
            current.copy(locations = rows)
        }
    }

    fun toggleUnits(id: Int): Result = change(id) { it.copy(fahrenheit = !it.fahrenheit) }

    fun delete(id: Int) { synchronized(configLock) { prefs.edit().remove("widget_$id").commit() } }

    /** Android remaps widget IDs on restore. Never replace a destination or discard unreadable data. */
    fun restore(oldId: Int, newId: Int) { synchronized(configLock) {
        if (oldId == newId || newId !in WeatherRuntime.ids(context)) return
        val raw = prefs.getString("widget_$oldId", null) ?: return
        if (prefs.contains("widget_$newId")) return
        // Copy exactly; decoding is a read concern, not permission to discard old data.
        prefs.edit().putString("widget_$newId", raw).commit()
    } }

    private companion object { val configLock = Any() }

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
