package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset

class RefreshPolicyTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")

    @Test fun backoffDoublesCapsAndSuccessClearsFailures() {
        var status = RefreshStatus(lastSuccessAt = now.minusSeconds(600))
        for ((index, minutes) in listOf(1L, 2L, 4L, 8L, 16L, 30L, 30L, 30L).withIndex()) {
            status = RefreshPolicy.after(status, RefreshOutcome.OFFLINE, now)
            assertEquals(index + 1, status.failures)
            assertEquals(now.plusSeconds(minutes * 60), status.nextAttemptAt)
            assertEquals(now.minusSeconds(600), status.lastSuccessAt)
        }
        assertEquals(RefreshStatus(now, now, RefreshOutcome.OK), RefreshPolicy.after(status, RefreshOutcome.OK, now))
        assertEquals(now.plusSeconds(1800), RefreshPolicy.after(status.copy(failures = Int.MAX_VALUE), RefreshOutcome.OFFLINE, now).nextAttemptAt)
    }

    @Test fun freshnessIsStrictlyTenMinutesAndFailureDeadlineTakesPriority() {
        val series = ForecastSeries(emptyList(), now.minusSeconds(600))
        assertNull(RefreshPolicy.nextAttempt(series, RefreshStatus(), now))
        assertTrue(RefreshPolicy.due(series, RefreshStatus(), now.plusMillis(1)))
        assertTrue(RefreshPolicy.due(null, RefreshStatus(), now))
        val failed = RefreshPolicy.after(RefreshStatus(), RefreshOutcome.OFFLINE, now)
        assertFalse(RefreshPolicy.due(null, failed, now.plusSeconds(59)))
        assertTrue(RefreshPolicy.due(null, failed, now.plusSeconds(60)))
        assertEquals(failed.nextAttemptAt, RefreshPolicy.nextAttempt(series, failed, now))
    }

    @Test fun outcomesDistinguishTransportHttpFormatAndCoverage() {
        assertEquals(RefreshOutcome.OFFLINE, RefreshPolicy.failure(IOException("offline")))
        assertEquals(RefreshOutcome.HTTP(503), RefreshPolicy.failure(WeatherHttpException(503)))
        assertEquals(RefreshOutcome.MALFORMED, RefreshPolicy.failure(IllegalStateException("unchecked fetch failure")))
        assertEquals(RefreshOutcome.MALFORMED, RefreshPolicy.outcome(OpenMeteoParser.parse("{}"), null, now, ZoneOffset.UTC))
        val retained = ForecastSeries(listOf(HourSample(now, 60.0, null, null)), now)
        assertEquals(RefreshOutcome.INCOMPLETE, RefreshPolicy.outcome(Parsed.Valid(retained.copy(samples = emptyList())), retained, now, ZoneOffset.UTC))
        assertEquals(RefreshOutcome.OK, RefreshPolicy.outcome(Parsed.Valid(retained), null, now, ZoneOffset.UTC))
    }
}
