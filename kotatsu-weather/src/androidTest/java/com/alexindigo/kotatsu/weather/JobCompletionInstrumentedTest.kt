package com.alexindigo.kotatsu.weather

import android.app.job.JobScheduler
import android.app.job.JobInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class JobCompletionInstrumentedTest {
    @Test fun absentForecastUncheckedFailureBacksOffAndLaterLocationRefreshes() = uncheckedFailureBacksOff(stale = false)

    @Test fun staleForecastUncheckedFailureBacksOffAndLaterLocationRefreshes() = uncheckedFailureBacksOff(stale = true)

    private fun uncheckedFailureBacksOff(stale: Boolean) {
        WeatherHostFixture().use { host ->
            val zone = ZoneId.systemDefault()
            val at = Instant.now()
            val place = Location("Failure", "Fixture", 18.2345, 78.2345)
            val later = Location("Later", "Fixture", 19.2345, 79.2345)
            val start = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val forecasts = ForecastStore(host.context)
            if (stale) {
                val parsed = Parsed.Valid(ForecastSeries((0..72).map { HourSample(start.plusSeconds(it * 3600L), 60.0, 0, null) }, at))
                assertEquals(ForecastStore.Result.ACCEPTED, forecasts.accept(place, true, parsed, at.minusSeconds(601), zone))
            }
            val retained = forecasts.read(place, true)
            val attempts = java.util.concurrent.CopyOnWriteArrayList<String>()
            val payload = """{"hourly":{"time":[${start.epochSecond}],"temperature_2m":[71],"weather_code":[0],"wind_gusts_10m":[null]}}"""
            RefreshJobService.fetcher = { location, _, _ ->
                attempts.add(location.coordinateKey)
                if (location.coordinateKey == place.coordinateKey) throw IllegalStateException("Fixture unchecked failure")
                payload
            }
            // Use the normal due path: a forced job could race a binding-triggered due job
            // and deliberately bypass the backoff that this test is meant to observe.
            val configs = WeatherStore(host.context)
            assertEquals(WeatherStore.Result.APPLIED, configs.add(host.id, place))
            assertEquals(WeatherStore.Result.APPLIED, configs.add(host.id, later))
            WeatherRuntime.refresh(host.context)
            val scheduler = host.context.getSystemService(JobScheduler::class.java)
            host.await("Every location records its outcome and the real job finishes") {
                val status = forecasts.snapshot(place, true).status
                status.failures == 1 &&
                    forecasts.snapshot(later, true).status.lastOutcome == RefreshOutcome.OK &&
                    scheduler.getPendingJob(WeatherRuntime.RETRY)?.extras?.getLong("at") == status.nextAttemptAt?.toEpochMilli()
            }
            val failed = ForecastStore(host.context).snapshot(place, true)
            assertEquals(retained, failed.series)
            assertEquals(RefreshOutcome.MALFORMED, failed.status.lastOutcome)
            assertEquals(1, failed.status.failures)
            assertEquals(failed.status.lastAttemptAt!!.plusSeconds(60), failed.status.nextAttemptAt)
            val retry = scheduler.getPendingJob(WeatherRuntime.RETRY)!!
            assertEquals(JobInfo.NETWORK_TYPE_ANY, retry.networkType)
            assertTrue("Retry latency ${retry.minLatencyMillis}", retry.minLatencyMillis in 55_000..60_000)
            assertEquals(failed.status.nextAttemptAt!!.toEpochMilli(), retry.extras.getLong("at"))
            assertEquals(listOf(place.coordinateKey, later.coordinateKey), attempts.toList())
            assertEquals(71.0, forecasts.read(later, true)!!.samples.single().temperature, 0.0)
            assertEquals(0, forecasts.snapshot(later, true).status.failures)
        }
    }

    @Test fun uncheckedFetchFailureStillFinishesTheRealScheduledJob() {
        WeatherHostFixture().use { host ->
            val zone = ZoneId.systemDefault()
            val at = Instant.now()
            val place = Location("Failure", "Fixture", 18.2345, 78.2345)
            val start = at.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val forecasts = ForecastStore(host.context)
            val parsed = Parsed.Valid(ForecastSeries((0..72).map { HourSample(start.plusSeconds(it * 3600L), 60.0, 0, null) }, at))
            assertEquals(ForecastStore.Result.ACCEPTED, forecasts.accept(place, true, parsed, at, zone))
            host.configure(WidgetConfig(listOf(place)))
            val fetched = CountDownLatch(1)
            RefreshJobService.fetcher = { _, _, _ -> fetched.countDown(); throw IllegalStateException("Fixture unchecked failure") }
            WeatherRuntime.refresh(host.context, force = true)
            assertTrue(fetched.await(15, TimeUnit.SECONDS))
            host.await("Unchecked failure finishes the immediate job") {
                host.context.getSystemService(JobScheduler::class.java).getPendingJob(WeatherRuntime.IMMEDIATE) == null
            }
            assertEquals(parsed.series, forecasts.read(place, true))
        }
    }
}
