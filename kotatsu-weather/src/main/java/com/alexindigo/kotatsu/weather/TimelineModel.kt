package com.alexindigo.kotatsu.weather

import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.Instant
import java.time.temporal.ChronoUnit

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
    val validUntil: Instant,
    val lastUpdatedAt: Instant? = null,
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
        }, timelineValidUntil(stored, status, now), oldestShownUpdate(config, stored, now))
}

/** The oldest forecast actually shown: a newer success must not disguise another place's retained data. */
fun oldestShownUpdate(config: WidgetConfig, stored: List<ForecastSeries?>, now: ZonedDateTime): Instant? {
    val day = DayWindow.of(now.toLocalDate(), now.zone)
    return config.locations.mapIndexedNotNull { index, place -> stored.getOrNull(index)?.takeIf {
        temperatureNumbers(place, it, day).isNotEmpty()
    }?.fetchedAt }.minOrNull()?.takeIf { now.toInstant() > it.plusSeconds(3600) }
}

/** Expired boundaries have already been reflected in the model; never schedule an alarm in the past. */
fun timelineValidUntil(stored: List<ForecastSeries?>, status: List<RefreshStatus>, now: ZonedDateTime): Instant {
    val instant = now.toInstant()
    val nextStep = now.truncatedTo(ChronoUnit.MINUTES).minusMinutes((now.minute % 15).toLong()).plusMinutes(15).toInstant()
    val midnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone).toInstant()
    val candidates = mutableListOf(nextStep, midnight)
    stored.filterNotNull().forEach { series ->
        val notice = series.fetchedAt.plusSeconds(3600).plusMillis(1)
        candidates += notice
        val grey = series.fetchedAt.plusSeconds(12 * 3600)
        // isStale is strictly >12h, preserved from the existing presentation contract.
        candidates += if (grey.toEpochMilli() == instant.toEpochMilli()) Instant.ofEpochMilli(grey.toEpochMilli() + 1) else grey
        // Samples are points: the final sample is the right edge of the available forecast horizon.
        series.samples.lastOrNull()?.instant?.let(candidates::add)
    }
    status.mapNotNullTo(candidates) { it.nextAttemptAt }
    return candidates.filter { it > instant }.minOrNull()!!
}

enum class ReconcileReason { UPDATE, OPTIONS_CHANGED, TIME_SET, TIMEZONE_CHANGED, DEADLINE, JOB_DONE, CONFIG_CHANGED, DELETED, RESTORED }

data class PublishedTimeline(val model: TimelineModel, val width: Int, val height: Int, val statusText: String? = null)

fun shouldPublish(previous: PublishedTimeline?, next: PublishedTimeline, reason: ReconcileReason): Boolean =
    reason == ReconcileReason.OPTIONS_CHANGED || previous != next
