package com.alexindigo.kotatsu.weather

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** Gust at h is Open-Meteo's maximum for the preceding hour. */
data class HourSample(val instant: Instant, val temperature: Double, val code: Int?, val gust: Double?)
data class ForecastSeries(val samples: List<HourSample>, val fetchedAt: Instant)

sealed interface Parsed {
    data class Valid(val series: ForecastSeries) : Parsed
    data class Malformed(val reason: String) : Parsed
}

/** Format validity only; the store owns acceptance and the successful-fetch timestamp. */
object OpenMeteoParser {
    fun parse(json: String): Parsed = parse(json, legacy = false)
    fun parseLegacy(json: String): Parsed = parse(json, legacy = true)

    private fun parse(json: String, legacy: Boolean): Parsed = try {
        val root = JsonReader(json).read() as Map<*, *>
        val hourly = root["hourly"] as? Map<*, *> ?: error("Missing hourly object")
        fun array(key: String) = hourly[key] as? List<*> ?: error("Missing $key array")
        val times = array("time")
        val temperatures = array("temperature_2m")
        val codes = array("weather_code")
        val gusts = array("wind_gusts_10m")
        require(listOf(temperatures, codes, gusts).all { it.size == times.size }) { "Hourly array lengths differ" }
        fun number(value: Any?): Double = (value as? Number)?.toDouble()?.also {
            require(it.isFinite()) { "Non-finite number" }
        } ?: error("Expected number")
        fun integer(value: Any?): Long = number(value).let {
            require(it == it.toLong().toDouble()) { "Expected integer" }; it.toLong()
        }
        val offset = if (legacy) ZoneOffset.ofTotalSeconds(Math.toIntExact(integer(root["utc_offset_seconds"]))) else null
        // Fractional-offset zones have local whole hours at :15/:30/:45 UTC. Validate in the response offset.
        val offsetSeconds = if (root.containsKey("utc_offset_seconds")) integer(root["utc_offset_seconds"]) else 0L
        val instants = times.map { value ->
            if (legacy) LocalDateTime.parse(value as String).also {
                require(it.minute == 0 && it.second == 0 && it.nano == 0) { "Non-hourly local time" }
            }.toInstant(offset!!)
            else Instant.ofEpochSecond(integer(value)).also {
                require(Math.floorMod(it.epochSecond + offsetSeconds, 3600L) == 0L) { "Non-hourly unix time" }
            }
        }
        require(instants.zipWithNext().all { (a, b) -> b.epochSecond - a.epochSecond == 3600L }) {
            "Times must be strictly increasing hourly instants"
        }
        val samples = instants.indices.mapNotNull { index ->
            // Validate optional fields even when temperature is absent.
            val code = codes[index]?.let { Math.toIntExact(integer(it)) }
            val gust = gusts[index]?.let(::number)
            temperatures[index]?.let { HourSample(instants[index], number(it), code, gust) }
        }
        Parsed.Valid(ForecastSeries(samples, Instant.EPOCH))
    } catch (error: Exception) {
        Parsed.Malformed(error.message ?: "Invalid forecast JSON")
    }
}

/** Wall hours 0…24, including both offsets of a repeated hour and no invented spring-gap hour. */
class DayWindow private constructor(val date: LocalDate, val zone: ZoneId, private val hours: Map<Int, List<Instant>>) {
    val start: Instant = date.atStartOfDay(zone).toInstant()
    val instants: List<Instant> = hours.values.flatten().sorted()
    fun block(mark: Int): MarkBlock {
        require(mark in MARK_HOURS)
        return MarkBlock(mark, (mark - 1..mark + 1).flatMap { hours.getValue(it) })
    }
    /** The labelled interval includes both endpoint samples, without the temperature averaging halo. */
    fun conditionInstants(start: Int, end: Int): Set<Instant> = (start..end).flatMap { hours.getValue(it) }.toSet()

    companion object {
        private val WALL_HOURS = MARK_HOURS.first - 1..MARK_HOURS.last + 1
        // Keep tomorrow usable after an evening download. A window includes wall hour 24, which is in day 3.
        private const val DAYS_TO_KEEP_USABLE = 2
        val forecastDays: Int = (DAYS_TO_KEEP_USABLE - 1) + WALL_HOURS.last / 24 + 1
        fun of(date: LocalDate, zone: ZoneId): DayWindow = DayWindow(date, zone, WALL_HOURS.associateWith { hour ->
            val local = date.atStartOfDay().plusHours(hour.toLong())
            zone.rules.getValidOffsets(local).map { local.toInstant(it) }
        })
    }
}

data class MarkBlock(val hour: Int, val instants: List<Instant>) {
    fun temperature(byInstant: Map<Instant, HourSample>): Double? {
        if (instants.isEmpty()) return null
        // Average the actual DST hours, but a missing temperature still makes the whole block unavailable.
        return instants.map { byInstant[it]?.temperature ?: return null }.average()
    }
}

/** 0.3.1 usefulness: temperature samples from today's coverage window onward, not from fetch time. */
fun shouldReplaceForecast(old: ForecastSeries?, new: ForecastSeries, today: DayWindow): Boolean {
    fun usefulness(series: ForecastSeries) = series.samples.count { !it.instant.isBefore(today.start) }
    val useful = usefulness(new)
    return old == null || (useful > 0 && useful >= usefulness(old))
}
