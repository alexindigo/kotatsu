package com.alexindigo.kotatsu.weather

import android.app.job.JobInfo
import android.app.job.JobScheduler
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.Clock

@RunWith(AndroidJUnit4::class)
class RefreshInstrumentedTest {
    @Test fun realFailedJobPersistsStatusAndSchedulesDelayedNetworkRetryThenSuccessClearsIt() {
        val previousClock = WidgetReconciler.clock
        val clock = Clock.fixed(Instant.now(), ZoneId.systemDefault())
        try {
        WidgetReconciler.clock = clock
        WeatherHostFixture().use { host ->
            val context = host.context
            val place = Location("Retry", "Fixture", 19.2345, 79.2345)
            val config = WidgetConfig(listOf(place, place.copy(label = "Shared", id = "shared")))
            val zone = clock.zone
            val at = clock.instant()
            val start = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val parsed = Parsed.Valid(ForecastSeries((0..72).map { HourSample(start.plusSeconds(it * 3600L), 60.0, 0, null) }, at))
            val store = ForecastStore(context)
            assertEquals(ForecastStore.Result.ACCEPTED, store.accept(place, true, parsed, at, zone))
            val retained = store.read(place, true)!!
            val fetches = java.util.concurrent.atomic.AtomicInteger()
            RefreshJobService.fetcher = { _, _, _ -> fetches.incrementAndGet(); throw IOException("Fixture offline") }
            host.configure(config)
            WeatherRuntime.refresh(context, force = true)
            val scheduler = context.getSystemService(JobScheduler::class.java)
            host.await("Offline state and retry job") {
                store.snapshot(place, true).status.lastOutcome == RefreshOutcome.OFFLINE &&
                    scheduler.getPendingJob(WeatherRuntime.RETRY) != null && scheduler.getPendingJob(WeatherRuntime.IMMEDIATE) == null
            }
            val failed = ForecastStore(context).snapshot(place, true)
            assertEquals(retained, failed.series)
            assertEquals(1, fetches.get()) // Shared coordinate+units fetched once, not once per row.
            assertEquals(1, failed.status.failures)
            assertEquals(at, failed.status.lastSuccessAt)
            assertEquals(failed.status.lastAttemptAt!!.plusSeconds(60), failed.status.nextAttemptAt)
            val retry = scheduler.getPendingJob(WeatherRuntime.RETRY)!!
            assertEquals(JobInfo.NETWORK_TYPE_ANY, retry.networkType)
            assertTrue("Retry latency ${retry.minLatencyMillis}", retry.minLatencyMillis in 55_000..60_000)
            assertEquals(failed.status.nextAttemptAt!!.toEpochMilli(), retry.extras.getLong("at"))
            val expected = buildTimeline(config, List(2) { retained }, List(2) { failed.status }, java.time.ZonedDateTime.now(clock))
            host.await("Failure keeps hosted numbers and appearance") {
                host.latest.get()?.sameAs(TimelineRenderer.render(context, expected, host.width, host.height)) == true
            }
            RefreshJobService.fetcher = { _, _, _ ->
                val hourly = org.json.JSONObject()
                for (key in listOf("time", "temperature_2m", "weather_code", "wind_gusts_10m")) {
                    hourly.put(key, org.json.JSONArray().apply { parsed.series.samples.forEach { sample -> put(when (key) {
                        "time" -> sample.instant.epochSecond
                        "temperature_2m" -> sample.temperature
                        "weather_code" -> 0
                        else -> org.json.JSONObject.NULL
                    }) } })
                }
                org.json.JSONObject().put("hourly", hourly).toString()
            }
            WeatherRuntime.refresh(context, force = true)
            host.await("Success clears failure and removes retry") {
                val status = ForecastStore(context).snapshot(place, true).status
                status.lastOutcome == RefreshOutcome.OK && status.failures == 0 && status.nextAttemptAt == null &&
                    scheduler.getPendingJob(WeatherRuntime.IMMEDIATE) == null && scheduler.getPendingJob(WeatherRuntime.RETRY) == null
            }
            val success = ForecastStore(context).snapshot(place, true).status
            assertNotNull(success.lastAttemptAt)
            assertEquals(success.lastAttemptAt, success.lastSuccessAt)
        }
        } finally { WidgetReconciler.clock = previousClock }
    }
}
