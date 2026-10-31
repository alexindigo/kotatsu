package com.alexindigo.kotatsu.weather

import android.content.Context
import android.text.format.DateFormat
import java.time.Instant
import java.time.ZoneId

/** Shared localized presentation of persisted refresh facts; neither consumer invents fetch state. */
object WeatherPresentation {
    fun time(context: Context, instant: Instant, zone: ZoneId): String = weatherTime(instant, zone,
        context.resources.configuration.locales[0], DateFormat.is24HourFormat(context))

    fun lastUpdated(context: Context, instant: Instant, zone: ZoneId): String =
        context.getString(R.string.last_updated_at, time(context, instant, zone))

    fun editor(context: Context, snapshot: ForecastStore.Snapshot, zone: ZoneId): String {
        val updated = snapshot.series?.fetchedAt?.let { lastUpdated(context, it, zone) }
            ?: context.getString(R.string.not_updated_yet)
        val reason = when (val outcome = snapshot.status.lastOutcome) {
            RefreshOutcome.OFFLINE -> context.getString(R.string.refresh_offline)
            is RefreshOutcome.HTTP -> context.getString(R.string.refresh_server_error, outcome.code)
            RefreshOutcome.INCOMPLETE -> context.getString(R.string.refresh_incomplete)
            RefreshOutcome.MALFORMED -> context.getString(R.string.refresh_invalid)
            RefreshOutcome.OK -> context.getString(R.string.refresh_ok)
            null -> context.getString(R.string.refresh_waiting)
        }
        val next = snapshot.status.nextAttemptAt?.let { context.getString(R.string.next_try_at, time(context, it, zone)) }
            ?: context.getString(R.string.next_try_scheduled)
        return listOf(updated, reason, next).joinToString("\n")
    }
}
