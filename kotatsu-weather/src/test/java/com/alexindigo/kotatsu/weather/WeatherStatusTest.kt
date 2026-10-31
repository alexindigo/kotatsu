package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class WeatherStatusTest {
    private val now = ZonedDateTime.parse("2026-10-30T08:30:00-07:00[America/Los_Angeles]")
    private val place = Location("Home", "Fixture", 10.0, 20.0)
    private val config = WidgetConfig(listOf(place, place.copy(label = "School")))
    private val series = ForecastSeries((0..48).map { HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(it * 3600L), 60.0, 0, null) }, now.toInstant().minusSeconds(3600))

    @Test fun noticeIsStrictlyOlderThanOneHourAndUsesOldestShownDataNotLatestAttemptOrSuccess() {
        fun model(old: ForecastSeries, at: ZonedDateTime = now) = buildTimeline(config, listOf(series, old),
            listOf(RefreshStatus(lastSuccessAt = now.toInstant()), RefreshPolicy.after(RefreshStatus(), RefreshOutcome.OFFLINE, now.toInstant())), at)
        assertNull(model(series).lastUpdatedAt)
        assertEquals(series.fetchedAt, model(series, now.plusNanos(1_000_000)).lastUpdatedAt)
        val old = series.copy(fetchedAt = now.toInstant().minusSeconds(4800))
        assertEquals(old.fetchedAt, model(old).lastUpdatedAt)
        assertNull(model(old.copy(samples = emptyList())).lastUpdatedAt)
        assertNull(model(old.copy(fetchedAt = now.toInstant().plusSeconds(600))).lastUpdatedAt)
    }

    @Test fun noticeDeadlineIsTheFirstMillisecondAfterAnHour() {
        assertEquals(now.toInstant().plusMillis(1), timelineValidUntil(listOf(series), emptyList(), now))
        assertTrue(timelineValidUntil(listOf(series), emptyList(), now.plusNanos(1_000_000)) > now.toInstant().plusMillis(1))
    }

    @Test fun clockPreferenceWinsOverLocaleAndPlaceAndUsesDeviceZone() {
        val updated = now.withHour(7).withMinute(10).toInstant()
        assertEquals("07:10", weatherTime(updated, now.zone, Locale.US, true))
        assertEquals("7:10 AM", weatherTime(updated, now.zone, Locale.US, false))
        assertEquals("14:10", weatherTime(updated, ZoneId.of("UTC"), Locale.US, true))
        assertEquals("12:10 AM", weatherTime(updated.minusSeconds(7 * 3600), now.zone, Locale.US, false))
        assertEquals("7:10 PM", weatherTime(updated.plusSeconds(12 * 3600), now.zone, Locale.US, false))
    }
}
