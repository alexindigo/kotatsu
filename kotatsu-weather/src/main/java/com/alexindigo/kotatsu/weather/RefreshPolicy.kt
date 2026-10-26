package com.alexindigo.kotatsu.weather

import java.io.IOException
import java.time.Duration
import java.time.Instant

sealed interface RefreshOutcome {
    data object OK : RefreshOutcome
    data object OFFLINE : RefreshOutcome
    data class HTTP(val code: Int) : RefreshOutcome
    data object MALFORMED : RefreshOutcome
    data object INCOMPLETE : RefreshOutcome
}

data class RefreshStatus(
    val lastSuccessAt: Instant? = null,
    val lastAttemptAt: Instant? = null,
    val lastOutcome: RefreshOutcome? = null,
    val failures: Int = 0,
    val nextAttemptAt: Instant? = null,
)

class WeatherHttpException(val code: Int) : IOException("Weather server returned $code")

/** All fetch timing and outcome decisions, independent of Android scheduling and persistence. */
object RefreshPolicy {
    private val freshness = Duration.ofMinutes(10)

    fun after(previous: RefreshStatus, outcome: RefreshOutcome, now: Instant): RefreshStatus {
        if (outcome == RefreshOutcome.OK) return RefreshStatus(now, now, outcome)
        val failures = (previous.failures.toLong() + 1).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val minutes = minOf(30L, 1L shl minOf(failures - 1, 5))
        return RefreshStatus(previous.lastSuccessAt, now, outcome, failures, now.plusSeconds(minutes * 60))
    }

    /** Future retries remain scheduled; healthy fresh data needs no immediate job. */
    fun nextAttempt(stored: ForecastSeries?, status: RefreshStatus, now: Instant): Instant? =
        status.nextAttemptAt ?: if (stored == null || Duration.between(stored.fetchedAt, now) > freshness) now else null

    fun due(stored: ForecastSeries?, status: RefreshStatus, now: Instant): Boolean =
        nextAttempt(stored, status, now)?.let { !it.isAfter(now) } == true

    fun failure(error: Exception): RefreshOutcome = when (error) {
        is WeatherHttpException -> RefreshOutcome.HTTP(error.code)
        is IOException -> RefreshOutcome.OFFLINE
        else -> RefreshOutcome.MALFORMED
    }

    fun outcome(parsed: Parsed, retained: ForecastSeries?, now: Instant, zone: java.time.ZoneId): RefreshOutcome = when {
        parsed !is Parsed.Valid -> RefreshOutcome.MALFORMED
        !shouldReplaceForecast(retained, parsed.series, DayWindow.of(now.atZone(zone).toLocalDate(), zone)) -> RefreshOutcome.INCOMPLETE
        else -> RefreshOutcome.OK
    }
}
