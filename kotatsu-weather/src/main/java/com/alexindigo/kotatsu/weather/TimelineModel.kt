package com.alexindigo.kotatsu.weather

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

enum class RowState { FRESH, STALE, NO_DATA }
enum class NoDataReason { NOT_FETCHED, MISSING_HOURS }

data class TimelineRow(
    val label: String,
    val state: RowState,
    val noDataReason: NoDataReason?,
    // A fresh but incomplete response historically keeps a black label with grey "No forecast".
    val stale: Boolean,
    val numbers: List<TemperatureNumber>,
    val condition: Condition,
    val status: RefreshStatus,
)

data class TimelineModel(
    val date: LocalDate,
    val zone: ZoneId,
    val axis: Axis,
    val needleHour: Double?,
    val rows: List<TimelineRow>,
)

/** The only owner of row meaning. Rendering needs neither forecast samples nor a clock. */
fun buildTimeline(
    config: WidgetConfig, stored: List<ForecastSeries?>, status: List<RefreshStatus>, now: ZonedDateTime,
): TimelineModel {
    val day = DayWindow.of(now.toLocalDate(), now.zone)
    val axis = sharedAxis(config.locations)
    val hour = now.hour + now.minute / 60.0
    return TimelineModel(day.date, day.zone, axis, hour.takeIf { it in axis.start.toDouble()..axis.end.toDouble() },
        config.locations.mapIndexed { index, location ->
            val forecast = stored.getOrNull(index)
            val numbers = forecast?.let { temperatureNumbers(location, it, day) }.orEmpty()
            val stale = forecast == null || isStale(forecast.fetchedAt.toEpochMilli(), now.toInstant().toEpochMilli())
            TimelineRow(location.label,
                if (numbers.isEmpty()) RowState.NO_DATA else if (stale) RowState.STALE else RowState.FRESH,
                if (forecast == null) NoDataReason.NOT_FETCHED else if (numbers.isEmpty()) NoDataReason.MISSING_HOURS else null,
                stale, numbers, forecast?.let { series -> spanHours(location, series, day)
                    .mapNotNull { condition(it.code, it.gust, config.fahrenheit) }.maxOrNull() } ?: Condition.CLEAR,
                status.getOrElse(index) { RefreshStatus() })
        })
}
