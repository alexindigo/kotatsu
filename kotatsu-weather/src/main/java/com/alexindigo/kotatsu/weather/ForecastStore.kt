package com.alexindigo.kotatsu.weather

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId

/** Only owner/writer of forecast envelopes and per-key refresh status. */
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

    data class Snapshot(val series: ForecastSeries?, val status: RefreshStatus)

    fun snapshot(location: Location, fahrenheit: Boolean, zone: ZoneId = ZoneId.systemDefault()): Snapshot = synchronized(lock) {
        val series = read(location, fahrenheit, zone)
        val status = prefs.getString(statusKey(location, fahrenheit), null)?.let { raw -> runCatching {
            val json = JSONObject(raw)
            fun instant(key: String) = if (json.isNull(key)) null else Instant.ofEpochMilli(json.getLong(key))
            val outcome = when (val name = json.getString("outcome")) {
                "OK" -> RefreshOutcome.OK
                "OFFLINE" -> RefreshOutcome.OFFLINE
                "MALFORMED" -> RefreshOutcome.MALFORMED
                "INCOMPLETE" -> RefreshOutcome.INCOMPLETE
                "HTTP" -> RefreshOutcome.HTTP(json.getInt("code"))
                else -> error("Unknown refresh outcome $name")
            }
            RefreshStatus(instant("success"), instant("attempt"), outcome, json.getInt("failures"), instant("next"))
        }.getOrNull() } ?: RefreshStatus(lastSuccessAt = series?.fetchedAt)
        Snapshot(series, status)
    }

    fun recordFailure(location: Location, fahrenheit: Boolean, outcome: RefreshOutcome, now: Instant, zone: ZoneId): Boolean = synchronized(lock) {
        require(outcome != RefreshOutcome.OK)
        val previous = snapshot(location, fahrenheit, zone).status
        prefs.edit().putString(statusKey(location, fahrenheit), encodeStatus(RefreshPolicy.after(previous, outcome, now))).commit()
    }

    fun accept(location: Location, fahrenheit: Boolean, parsed: Parsed, now: Instant, zone: ZoneId): Result = synchronized(lock) {
        val previous = snapshot(location, fahrenheit, zone)
        val outcome = RefreshPolicy.outcome(parsed, previous.series, now, zone)
        val edit = prefs.edit().putString(statusKey(location, fahrenheit), encodeStatus(RefreshPolicy.after(previous.status, outcome, now)))
        if (outcome == RefreshOutcome.OK) {
            edit.putString(key(location, fahrenheit), encode((parsed as Parsed.Valid).series.copy(fetchedAt = now)))
            legacyKeys(location, fahrenheit, zone).forEach(edit::remove)
        }
        if (!edit.commit()) Result.WRITE_FAILED else when (outcome) {
            RefreshOutcome.OK -> Result.ACCEPTED
            RefreshOutcome.MALFORMED -> Result.MALFORMED
            else -> Result.INCOMPLETE
        }
    }

    private fun encodeStatus(status: RefreshStatus): String = JSONObject()
        .put("success", status.lastSuccessAt?.toEpochMilli() ?: JSONObject.NULL)
        .put("attempt", status.lastAttemptAt?.toEpochMilli() ?: JSONObject.NULL)
        .put("outcome", when (status.lastOutcome) {
            RefreshOutcome.OK -> "OK"
            RefreshOutcome.OFFLINE -> "OFFLINE"
            RefreshOutcome.MALFORMED -> "MALFORMED"
            RefreshOutcome.INCOMPLETE -> "INCOMPLETE"
            is RefreshOutcome.HTTP -> "HTTP"
            null -> error("Cannot persist an unattempted status")
        }).put("code", (status.lastOutcome as? RefreshOutcome.HTTP)?.code ?: JSONObject.NULL)
        .put("failures", status.failures).put("next", status.nextAttemptAt?.toEpochMilli() ?: JSONObject.NULL).toString()

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
        fun statusKey(location: Location, fahrenheit: Boolean) = "refresh_" + hash("${location.coordinateKey}|$fahrenheit")
        fun legacyKey(location: Location, fahrenheit: Boolean, zone: String) = "cache_" + hash("${location.coordinateKey}|$fahrenheit|$zone")
    }
}
