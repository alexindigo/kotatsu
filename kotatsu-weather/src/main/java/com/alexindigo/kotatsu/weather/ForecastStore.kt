package com.alexindigo.kotatsu.weather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Only owner/writer of forecast envelopes. All instances serialize read/migrate/compare/write together. */
class ForecastStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("weather", Context.MODE_PRIVATE)

    fun read(location: Location, fahrenheit: Boolean, zone: ZoneId = ZoneId.systemDefault()): ForecastSeries? = synchronized(lock) {
        val key = key(location, fahrenheit)
        val current = prefs.getString(key, null)?.let { runCatching { decode(it) }.getOrNull() }
        if (current != null) return@synchronized current
        val raw = prefs.getString(legacyKey(location, fahrenheit, zone.id), null) ?: return@synchronized null
        val legacyKeys = legacyKeys(location, fahrenheit, zone)
        val migrated = runCatching {
            val envelope = JSONObject(raw)
            val parsed = OpenMeteoParser.parseLegacy(envelope.getString("data")) as? Parsed.Valid
            parsed?.series?.copy(fetchedAt = Instant.ofEpochMilli(envelope.getLong("fetchedAt")))
        }.getOrNull()
        val edit = prefs.edit()
        if (migrated != null) edit.putString(key, encode(migrated)) else edit.remove(key)
        legacyKeys.forEach(edit::remove)
        if (!edit.commit()) return@synchronized null
        migrated
    }

    enum class Result { ACCEPTED, MALFORMED, INCOMPLETE, WRITE_FAILED }

    fun accept(location: Location, fahrenheit: Boolean, parsed: Parsed, now: Instant, zone: ZoneId): Result = synchronized(lock) {
        if (parsed !is Parsed.Valid) return@synchronized Result.MALFORMED
        val old = read(location, fahrenheit, zone)
        val next = parsed.series.copy(fetchedAt = now)
        if (!shouldReplaceForecast(old, next, DayWindow.of(now.atZone(zone).toLocalDate(), zone))) return@synchronized Result.INCOMPLETE
        val edit = prefs.edit().putString(key(location, fahrenheit), encode(next))
        legacyKeys(location, fahrenheit, zone).forEach(edit::remove)
        if (edit.commit()) Result.ACCEPTED else Result.WRITE_FAILED
    }

    private fun decode(raw: String): ForecastSeries {
        val root = JSONObject(raw)
        require(root.getInt("version") == 1)
        val rows = root.getJSONArray("samples")
        val samples = (0 until rows.length()).map { index ->
            val row = rows.getJSONArray(index)
            HourSample(Instant.ofEpochSecond(row.getLong(0)), row.getDouble(1),
                if (row.isNull(2)) null else row.getInt(2), if (row.isNull(3)) null else row.getDouble(3))
        }
        require(samples.all { it.temperature.isFinite() && (it.gust == null || it.gust.isFinite()) })
        require(samples.zipWithNext().all { (a, b) -> a.instant < b.instant })
        return ForecastSeries(samples, Instant.ofEpochMilli(root.getLong("fetchedAt")))
    }

    private fun encode(series: ForecastSeries): String = JSONObject().put("version", 1)
        .put("fetchedAt", series.fetchedAt.toEpochMilli()).put("samples", JSONArray().apply {
            series.samples.forEach { sample -> put(JSONArray().put(sample.instant.epochSecond).put(sample.temperature)
                .put(sample.code ?: JSONObject.NULL).put(sample.gust ?: JSONObject.NULL)) }
        }).toString()

    private fun legacyKeys(location: Location, fahrenheit: Boolean, zone: ZoneId): Set<String> =
        (ZoneId.getAvailableZoneIds() + zone.id).mapTo(HashSet()) { legacyKey(location, fahrenheit, it) }
            .filterTo(HashSet()) { prefs.contains(it) }

    private companion object {
        val lock = Any()
        fun hash(input: String) = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        fun key(location: Location, fahrenheit: Boolean) = "forecast_" + hash("${location.coordinateKey}|$fahrenheit")
        fun legacyKey(location: Location, fahrenheit: Boolean, zone: String) = "cache_" + hash("${location.coordinateKey}|$fahrenheit|$zone")
    }
}
