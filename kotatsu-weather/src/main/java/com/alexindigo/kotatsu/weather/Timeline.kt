package com.alexindigo.kotatsu.weather

import java.util.Locale
import kotlin.math.roundToInt

data class Location(
    val label: String,
    val place: String,
    val latitude: Double,
    val longitude: Double,
    val start: Int = 7,
    val end: Int = 23,
    /** Stable identity within a widget, independent of row position, label and coordinates. */
    val id: String = java.util.UUID.randomUUID().toString(),
) {
    init {
        require(label.codePointCount(0, label.length) <= 8)
        // Spans start and end on marks, which are odd hours within the day (see MARK_HOURS).
        require(start in MARK_HOURS && end in MARK_HOURS && start < end)
        require(latitude.isFinite() && latitude in -90.0..90.0)
        require(longitude.isFinite() && longitude in -180.0..180.0)
        require(id.isNotBlank())
    }

    val coordinateKey: String get() = String.format(Locale.ROOT, "%.4f,%.4f", latitude, longitude)
}

/**
 * The phone's own position as a place. Coordinates are rounded to 3 decimals (about 100 m): finer than the
 * forecast's 2.5–3 km grid, but they don't pinpoint a house. Android has no reverse geocoder without Google
 * services, so the label is "Here" (editable) and the place line names the rounded coordinates.
 */
fun currentLocation(latitude: Double, longitude: Double): Location {
    fun round3(value: Double) = Math.round(value * 1000) / 1000.0
    val lat = round3(latitude)
    val lon = round3(longitude)
    return Location("Here", String.format(Locale.ROOT, "Current location, %.3f, %.3f", lat, lon), lat, lon)
}

fun shortLabel(text: String): String = text.substring(0, text.offsetByCodePoints(0, minOf(8, text.codePointCount(0, text.length))))

/** Most places one widget shows; all rows share one deterministic layout plan. */
const val MAX_LOCATIONS = 4

data class WidgetConfig(val locations: List<Location>, val fahrenheit: Boolean = true) {
    init { require(locations.size <= MAX_LOCATIONS) }
}

fun isStale(fetchedAt: Long, now: Long): Boolean = now - fetchedAt > 12 * 60 * 60 * 1000L

enum class Condition { CLEAR, CLOUDS, WIND, FOG, RAIN, SNOW, THUNDERSTORM }

fun condition(code: Int?, gust: Double?, fahrenheit: Boolean): Condition? = when {
    code == null -> null
    code in 95..99 -> Condition.THUNDERSTORM
    code in 71..77 || code in 85..86 -> Condition.SNOW
    code in 51..67 || code in 80..82 -> Condition.RAIN
    code == 45 || code == 48 -> Condition.FOG
    gust != null && gust >= if (fahrenheit) 25.0 else 40.0 -> Condition.WIND
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

fun spanHours(location: Location, forecast: ForecastSeries, day: DayWindow): List<HourSample> {
    val instants = day.conditionInstants(location.start, location.end)
    return forecast.samples.filter { it.instant in instants }
}

/**
 * A mark's temperature: the mean of the hour before, the mark's own hour and the hour after, so neighbouring
 * marks share their boundary hour. DayWindow resolves the actual hours across DST changes.
 */
fun markTemperature(byTime: Map<java.time.Instant, HourSample>, day: DayWindow, mark: Int): Double? =
    day.block(mark).temperature(byTime)

fun temperatureNumbers(location: Location, forecast: ForecastSeries, dayWindow: DayWindow): List<TemperatureNumber> {
    val byTime = forecast.samples.associateBy { it.instant }
    val span = (location.start..location.end step MARK_EVERY_HOURS).map { mark ->
        mark to (markTemperature(byTime, dayWindow, mark) ?: return emptyList())
    }
    // The day's high and low come from the same block averages, over every mark of the day.
    val day = MARK_HOURS.mapNotNull { mark -> markTemperature(byTime, dayWindow, mark)?.let { mark to it } }
    val high = day.maxByOrNull { it.second }
    val low = day.minByOrNull { it.second }
    return buildList {
        add(TemperatureNumber(location.start, span.first().second.roundToInt(), NumberKind.START))
        add(TemperatureNumber(location.end, span.last().second.roundToInt(), NumberKind.END))
        if (high != null && high.first in location.start..location.end) {
            add(TemperatureNumber(high.first, high.second.roundToInt(), NumberKind.HIGH))
        }
        if (low != null && low.first > location.start && low.first < location.end) {
            add(TemperatureNumber(low.first, low.second.roundToInt(), NumberKind.LOW))
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

/** Hours between marks. */
const val MARK_EVERY_HOURS = 2

/** The marks of a day: odd hours, so the first and last blocks (0–2, 22–24) still have all three hours. */
val MARK_HOURS = 1..23 step MARK_EVERY_HOURS

/** Every mark of the shared axis is a dot in every row, except marks a shown number sits on. */
fun hourDots(axis: Axis, placed: List<PlacedNumber>): List<Int> {
    val taken = placed.mapTo(HashSet()) { it.number.hour }
    return (axis.start..axis.end step MARK_EVERY_HOURS).filterNot { it in taken }
}

/** Widest number such that numbers on neighbouring marks, [markStep] apart, still keep [gap] between them. */
fun maxNumberWidth(markStep: Float, gap: Float): Float = (markStep - gap).coerceAtLeast(0f)

/**
 * Rows sit at the bottom: the last row's bottom is the bottom edge ([height]) and each row above it is one
 * [rowHeight] plus [gap] higher, so every row has the same gap below it. Returns row bottoms, top row first.
 */
fun stackedRowBottoms(count: Int, rowHeight: Float, gap: Float, height: Float): List<Float> =
    (0 until count).map { height - (count - 1 - it) * (rowHeight + gap) }
