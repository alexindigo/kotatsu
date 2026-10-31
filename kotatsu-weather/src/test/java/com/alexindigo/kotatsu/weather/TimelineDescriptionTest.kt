package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime
import java.util.Locale

class TimelineDescriptionTest {
    private val now = ZonedDateTime.parse("2026-10-31T08:30:00-07:00[America/Los_Angeles]")
    private val place = Location("Home", "Fixture", 10.0, 20.0, start = 7, end = 19)
    private val series = ForecastSeries((0..48).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(h * 3600L), if (h % 24 in 12..16) 66.0 else -2.0, 2, null) }, now.toInstant())
    private val strings = TimelineDescriptionStrings("%1\$s, %2\$s to %3\$s, %4\$d to %5\$d degrees, %6\$s",
        "No forecast", "Last updated at %1\$s", mapOf(Condition.CLEAR to "clear", Condition.CLOUDS to "cloudy",
            Condition.WIND to "windy", Condition.FOG to "foggy", Condition.RAIN to "rain", Condition.SNOW to "snow",
            Condition.THUNDERSTORM to "thunderstorm"))
    private fun model(forecast: ForecastSeries? = series) = buildTimeline(WidgetConfig(listOf(place)),
        listOf(forecast), emptyList(), now)
    private fun describe(model: TimelineModel, use24: Boolean = true) =
        timelineDescription(model, Locale.US, use24, strings)

    @Test fun freshRowUsesSelectedSpanAndTheDrawnTemperatureRange() {
        assertEquals("Home, 07:00 to 19:00, -2 to 66 degrees, cloudy", describe(model()))
        assertEquals("Home, 7:00 AM to 7:00 PM, -2 to 66 degrees, cloudy", describe(model(), false))
    }

    @Test fun staleRowRetainsForecastAndIncludesOnlyTheShownUpdateTime() {
        val old = series.copy(fetchedAt = now.withHour(7).withMinute(10).toInstant().minusSeconds(24 * 3600))
        assertEquals(RowState.STALE, model(old).rows.single().state)
        assertEquals("Home, 07:00 to 19:00, -2 to 66 degrees, cloudy. Last updated at 07:10", describe(model(old)))
        assertEquals("Home, 7:00 AM to 7:00 PM, -2 to 66 degrees, cloudy. Last updated at 7:10 AM", describe(model(old), false))
        assertFalse(describe(model(old).copy(lastUpdatedAt = null)).contains("updated"))
    }

    @Test fun missingAndIncompleteRowsSayNoForecastWithoutInventingNumbersOrConditions() {
        assertEquals("Home, No forecast", describe(model(null)))
        assertEquals("Home, No forecast", describe(model(series.copy(samples = emptyList()))))
        assertEquals("No forecast", describe(model().copy(rows = emptyList())))
    }

    @Test fun rowsStayInModelOrderAndUseTheirOwnSpansNotTheSharedAxis() {
        val second = place.copy(label = "School", start = 9, end = 15)
        val model = buildTimeline(WidgetConfig(listOf(place, second)), listOf(series, null), emptyList(), now)
        assertEquals("Home, 07:00 to 19:00, -2 to 66 degrees, cloudy. School, No forecast", describe(model))
        val both = buildTimeline(WidgetConfig(listOf(place, second)), listOf(series, series), emptyList(), now)
        assertTrue(describe(both).contains("School, 09:00 to 15:00"))
    }

    @Test fun everyConditionHasItsOwnWordEvenWhenStale() {
        assertEquals(Condition.entries.toSet(), strings.conditions.keys)
        val base = model()
        for ((condition, word) in strings.conditions) {
            val row = base.rows.single().copy(condition = condition, state = RowState.STALE, stale = true)
            assertEquals("Home, 07:00 to 19:00, -2 to 66 degrees, $word", describe(base.copy(rows = listOf(row))))
        }
    }

    @Test fun localizedDescriptionParticipatesInPublicationEvenWithoutAStatusLine() {
        val previous = PublishedTimeline(model(), 344, 112, description = describe(model()))
        assertFalse(shouldPublish(previous, previous, ReconcileReason.UPDATE))
        assertTrue(shouldPublish(previous, previous.copy(description = describe(model(), false)), ReconcileReason.UPDATE))
    }
}
