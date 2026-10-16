package com.alexindigo.kotatsu.weather

import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt

data class Location(
    val label: String,
    val place: String,
    val latitude: Double,
    val longitude: Double,
    val start: Int = 6,
    val end: Int = 24,
) {
    init {
        require(label.codePointCount(0, label.length) <= 8)
        require(start in 0..23 && end in 1..24 && start < end)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
    }

    val coordinateKey: String get() = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude)
}

fun shortLabel(text: String): String = text.substring(0, text.offsetByCodePoints(0, minOf(8, text.codePointCount(0, text.length))))

data class WidgetConfig(val locations: List<Location>, val fahrenheit: Boolean = true) {
    init { require(locations.size <= 3) }
}

data class Hour(val time: LocalDateTime, val temperature: Double, val code: Int, val gust: Double)
data class Forecast(val hours: List<Hour>, val fetchedAt: Long)

fun isStale(fetchedAt: Long, now: Long): Boolean = now - fetchedAt > 12 * 60 * 60 * 1000L

enum class Condition { CLEAR, CLOUDS, WIND, FOG, RAIN, SNOW, THUNDERSTORM }

fun condition(code: Int, gust: Double, fahrenheit: Boolean): Condition = when {
    code in 95..99 -> Condition.THUNDERSTORM
    code in 71..77 || code in 85..86 -> Condition.SNOW
    code in 51..67 || code in 80..82 -> Condition.RAIN
    code == 45 || code == 48 -> Condition.FOG
    gust >= if (fahrenheit) 25.0 else 40.0 -> Condition.WIND
    code in 2..3 -> Condition.CLOUDS
    else -> Condition.CLEAR
}

data class Axis(val start: Int, val end: Int) {
    fun x(hour: Double, left: Float, right: Float): Float =
        left + ((hour - start) / (end - start) * (right - left)).toFloat()

    fun nowX(hour: Double, left: Float, right: Float): Float? =
        if (hour in start.toDouble()..end.toDouble()) x(hour, left, right) else null
}

fun sharedAxis(locations: List<Location>): Axis =
    Axis(locations.minOfOrNull { it.start } ?: 6, locations.maxOfOrNull { it.end } ?: 24)

enum class NumberKind(val priority: Int) { END(0), START(1), LOW(2), HIGH(3) }
data class TemperatureNumber(val hour: Int, val value: Int, val kind: NumberKind)
data class PlacedNumber(val number: TemperatureNumber, val x: Float, val width: Float)

fun spanHours(location: Location, forecast: Forecast, today: LocalDate): List<Hour> =
    forecast.hours.filter { it.time.toLocalDate() == today && it.time.hour in location.start until location.end }

fun temperatureNumbers(location: Location, forecast: Forecast, today: LocalDate): List<TemperatureNumber> {
    val byTime = forecast.hours.associateBy { it.time }
    val midnight = today.atStartOfDay()
    // 24 is deliberately a timestamp on the next day, never today's 00:00.
    val start = byTime[midnight.plusHours(location.start.toLong())] ?: return emptyList()
    val end = byTime[midnight.plusHours(location.end.toLong())] ?: return emptyList()
    if ((location.start..location.end).any { byTime[midnight.plusHours(it.toLong())] == null }) return emptyList()
    val day = forecast.hours.filter { it.time.toLocalDate() == today }
    val high = day.maxByOrNull { it.temperature }
    val low = day.minByOrNull { it.temperature }
    return buildList {
        add(TemperatureNumber(location.start, start.temperature.roundToInt(), NumberKind.START))
        add(TemperatureNumber(location.end, end.temperature.roundToInt(), NumberKind.END))
        if (high != null && high.time.hour in location.start..location.end) {
            add(TemperatureNumber(high.time.hour, high.temperature.roundToInt(), NumberKind.HIGH))
        }
        if (low != null && low.time.hour > location.start && low.time.hour < location.end) {
            add(TemperatureNumber(low.time.hour, low.temperature.roundToInt(), NumberKind.LOW))
        }
    }
}

fun placeNumbers(
    numbers: List<TemperatureNumber>, axis: Axis, left: Float, right: Float,
    gap: Float, measure: (String) -> Float,
): List<PlacedNumber> {
    val kept = mutableListOf<PlacedNumber>()
    for (number in numbers.sortedByDescending { it.kind.priority }) {
        val candidate = PlacedNumber(number, axis.x(number.hour.toDouble(), left, right), measure(number.value.toString()))
        if (kept.none { kotlin.math.abs(it.x - candidate.x) < (it.width + candidate.width) / 2 + gap }) kept += candidate
    }
    return kept.sortedBy { it.x }
}
