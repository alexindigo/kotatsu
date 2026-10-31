package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class RenderValidityTest {
    private val at = ZonedDateTime.parse("2026-10-26T12:01:00Z")
    private fun series(end: Instant = at.toInstant().plusSeconds(7200), fetched: Instant = at.toInstant()) =
        ForecastSeries(listOf(HourSample(end, 60.0, null, null)), fetched)

    @Test fun nextNeedleStepIsStrictlyTheNextQuarterEvenAtABoundary() {
        assertEquals(Instant.parse("2026-10-26T12:15:00Z"), timelineValidUntil(emptyList(), emptyList(), at))
        assertEquals(Instant.parse("2026-10-26T12:30:00Z"), timelineValidUntil(emptyList(), emptyList(), at.withMinute(15)))
    }

    @Test fun earliestGreyCoverageOrRetryBoundaryWinsAndExpiredOnesCannotLoop() {
        val now = at.toInstant()
        assertEquals(now.plusSeconds(5), timelineValidUntil(listOf(series(fetched = now.minusSeconds(12 * 3600 - 5))), emptyList(), at))
        assertEquals(now.plusSeconds(3), timelineValidUntil(listOf(series(end = now.plusSeconds(3))), emptyList(), at))
        val retry = RefreshStatus(nextAttemptAt = now.plusSeconds(2))
        assertEquals(now.plusSeconds(2), timelineValidUntil(listOf(series(end = now.plusSeconds(3))), listOf(retry), at))
        val expired = series(end = now.minusSeconds(1), fetched = now.minusSeconds(13 * 3600))
        assertEquals(Instant.parse("2026-10-26T12:15:00Z"), timelineValidUntil(listOf(expired), listOf(retry.copy(nextAttemptAt = now.minusSeconds(1))), at))
        assertEquals(now.plusMillis(1), timelineValidUntil(listOf(series(fetched = now.minusSeconds(12 * 3600))), emptyList(), at))
        assertEquals(now.plusMillis(1), timelineValidUntil(listOf(series(fetched = now.minusSeconds(12 * 3600))), emptyList(), at.plusNanos(1)))
    }

    @Test fun midnightChangesTheDateAndDstStepsFollowActualInstants() {
        val midnight = ZonedDateTime.parse("2026-10-31T23:59:00-07:00[America/Los_Angeles]")
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), timelineValidUntil(emptyList(), emptyList(), midnight))
        val fall = ZonedDateTime.parse("2026-11-01T01:59:00-07:00[America/Los_Angeles]")
        assertEquals(Instant.parse("2026-11-01T09:00:00Z"), timelineValidUntil(emptyList(), emptyList(), fall))
        val spring = ZonedDateTime.parse("2026-03-08T01:59:00-08:00[America/Los_Angeles]")
        assertEquals(Instant.parse("2026-03-08T10:00:00Z"), timelineValidUntil(emptyList(), emptyList(), spring))
        val kathmandu = midnight.withZoneSameInstant(ZoneId.of("Asia/Kathmandu"))
        assertEquals(Instant.parse("2026-11-01T07:00:00Z"), timelineValidUntil(emptyList(), emptyList(), kathmandu))
        val newYork = midnight.withZoneSameInstant(ZoneId.of("America/New_York"))
        val config = WidgetConfig(listOf(Location("Home", "Fixture", 10.0, 20.0)))
        val old = buildTimeline(config, emptyList(), emptyList(), midnight)
        val changed = buildTimeline(config, emptyList(), emptyList(), newYork)
        assertNotEquals(old.date, changed.date)
        assertNotEquals(old.zone, changed.zone)
        assertNotEquals(old, changed)
    }

    @Test fun coalescingSkipsUnchangedModelsButEveryOptionsPulseRepublishes() {
        val model = buildTimeline(WidgetConfig(emptyList()), emptyList(), emptyList(), at)
        val next = PublishedTimeline(model, 464, 112)
        for (reason in ReconcileReason.entries) {
            assertTrue(shouldPublish(null, next, reason))
            assertEquals(reason == ReconcileReason.OPTIONS_CHANGED, shouldPublish(next, next.copy(), reason))
        }
        assertTrue(shouldPublish(next, next.copy(height = 160), ReconcileReason.UPDATE))
        assertTrue(shouldPublish(next.copy(statusText = "Last updated at 07:10"),
            next.copy(statusText = "Last updated at 7:10 AM"), ReconcileReason.TIME_SET))
        assertTrue(shouldPublish(next, next.copy(model = model.copy(date = model.date.plusDays(1))), ReconcileReason.DEADLINE))
    }
}
