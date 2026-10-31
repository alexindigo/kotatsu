package com.alexindigo.kotatsu.weather

import java.util.Locale

/** Localized grammar is supplied by presentation; forecast meaning comes only from the drawn model. */
data class TimelineDescriptionStrings(
    val row: String,
    val noForecast: String,
    val lastUpdated: String,
    val conditions: Map<Condition, String>,
)

fun timelineDescription(model: TimelineModel, locale: Locale, use24Hour: Boolean,
    strings: TimelineDescriptionStrings): String {
    fun hour(value: Int): String = weatherTime(model.date.atTime(value, 0).atZone(model.zone).toInstant(),
        model.zone, locale, use24Hour)
    val rows = model.rows.map { row ->
        if (row.state == RowState.NO_DATA || row.numbers.isEmpty()) "${row.label}, ${strings.noForecast}"
        else String.format(locale, strings.row, row.label, hour(row.start), hour(row.end),
            row.numbers.minOf { it.value }, row.numbers.maxOf { it.value }, strings.conditions.getValue(row.condition))
    }
    val updated = model.lastUpdatedAt?.let {
        String.format(locale, strings.lastUpdated, weatherTime(it, model.zone, locale, use24Hour))
    }
    return (rows.ifEmpty { listOf(strings.noForecast) } + listOfNotNull(updated)).joinToString(". ")
}
