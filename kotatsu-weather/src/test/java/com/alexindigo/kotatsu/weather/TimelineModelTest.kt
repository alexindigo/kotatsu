package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class TimelineModelTest {
    private val now = ZonedDateTime.parse("2026-10-16T12:30:00-07:00[America/Los_Angeles]")
    private val home = Location("Home", "Fixture", 10.0, 20.0, id = "home")
    private val school = home.copy(label = "School", start = 9, end = 19, id = "school")
    private val series = ForecastSeries((0..24).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone).toInstant().plusSeconds(h * 3600L),
        when (h) { 7 -> 30.0; 15 -> 90.0; else -> 60.0 }, 2, null) }, now.toInstant())

    @Test fun fixtureModelsHaveExactlyTheOldDecisionsForFreshStaleNoDataAndFourRows() {
        val numbers = listOf(TemperatureNumber(7, 50, NumberKind.START), TemperatureNumber(23, 60, NumberKind.END),
            TemperatureNumber(15, 70, NumberKind.HIGH))
        val expected = TimelineRow("Home", RowState.FRESH, null, false, numbers, Condition.CLOUDS, RefreshStatus())
        val model = buildTimeline(WidgetConfig(listOf(home)), listOf(series), emptyList(), now)
        assertEquals(TimelineModel(now.toLocalDate(), now.zone, Axis(7, 23), 12.5, listOf(expected)), model)
        val old = series.copy(fetchedAt = now.toInstant().minusSeconds(13 * 3600))
        assertEquals(model.copy(rows = listOf(expected.copy(state = RowState.STALE, stale = true))),
            buildTimeline(WidgetConfig(listOf(home)), listOf(old), emptyList(), now))
        assertEquals(model.copy(rows = listOf(expected.copy(state = RowState.NO_DATA, noDataReason = NoDataReason.NOT_FETCHED,
            stale = true, numbers = emptyList(), condition = Condition.CLEAR))),
            buildTimeline(WidgetConfig(listOf(home)), listOf(null), emptyList(), now))
        val config = WidgetConfig(listOf(home, school, home.copy(label = "Gym"), school.copy(label = "Park")))
        val four = buildTimeline(config, List(4) { series }, emptyList(), now)
        assertEquals(listOf("Home", "School", "Gym", "Park"), four.rows.map { it.label })
        assertEquals(four.rows[0].copy(label = "Gym"), four.rows[2])
        assertEquals(four.rows[1].copy(label = "Park"), four.rows[3])
        assertEquals(Axis(7, 23), four.axis)
    }

    @Test fun incompleteFreshRowPreservesBlackLabelAndFailureIsCarriedWithoutChangingNumbers() {
        val failed = RefreshPolicy.after(RefreshStatus(), RefreshOutcome.OFFLINE, now.toInstant())
        val model = buildTimeline(WidgetConfig(listOf(home)), listOf(series), listOf(failed), now)
        assertEquals(failed, model.rows.single().status)
        assertEquals(buildTimeline(WidgetConfig(listOf(home)), listOf(series), emptyList(), now).rows.single().numbers, model.rows.single().numbers)
        val row = buildTimeline(WidgetConfig(listOf(home)), listOf(series.copy(samples = emptyList())), emptyList(), now).rows.single()
        assertEquals(RowState.NO_DATA, row.state)
        assertEquals(NoDataReason.MISSING_HOURS, row.noDataReason)
        assertFalse(row.stale)
        assertNull(buildTimeline(WidgetConfig(listOf(home)), listOf(series), emptyList(), now.withHour(0)).needleHour)
    }
}
