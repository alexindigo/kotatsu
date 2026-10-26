package com.alexindigo.kotatsu.weather

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class ReconcilerInstrumentedTest {
    private val place = Location("Home", "Midnight fixture", 18.3456, 78.3456)
    private val config = WidgetConfig(listOf(place))

    private fun seed(host: WeatherHostFixture, before: ZonedDateTime): ForecastSeries {
        val start = before.toLocalDate().atStartOfDay(before.zone).toInstant()
        val series = ForecastSeries((0..72).map { h -> HourSample(start.plusSeconds(h * 3600L),
            if (h < 24) 50.0 else 70.0, 0, null) }, before.withHour(23).withMinute(50).withSecond(0).toInstant())
        assertEquals(ForecastStore.Result.ACCEPTED, ForecastStore(host.context).accept(place, true, Parsed.Valid(series), series.fetchedAt, before.zone))
        return series
    }

    private fun expectHosted(host: WeatherHostFixture, now: ZonedDateTime): TimelineModel {
        val snapshot = ForecastStore(host.context).snapshot(place, true, now.zone)
        val expected = buildTimeline(config, listOf(snapshot.series), listOf(snapshot.status), now)
        return host.awaitPublishedModel("Published model date and hosted bitmap for $now") { it == expected }
    }

    @Test fun deterministicDeadlinePublishesTomorrowsNumbersAndCoalescesUntilAnOptionsPulse() {
        val previous = WidgetReconciler.clock
        val before = ZonedDateTime.parse("2026-10-17T23:59:00-07:00[America/Los_Angeles]")
        WidgetReconciler.clock = Clock.fixed(before.toInstant(), before.zone)
        try {
            WeatherHostFixture().use { host ->
                RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture offline") }
                seed(host, before)
                host.configure(config)
                val old = expectHosted(host, before)
                val after = before.plusMinutes(2)
                WidgetReconciler.clock = Clock.fixed(after.toInstant(), after.zone)
                host.context.sendBroadcast(Intent(host.context, WeatherWidgetProvider::class.java).setAction(WeatherRuntime.REFRESH))
                host.await("Due offline fetch completes before coalescing proof") {
                    WidgetReconciler.publishedModel(host.id)?.rows?.singleOrNull()?.status?.lastOutcome == RefreshOutcome.OFFLINE
                }
                val new = expectHosted(host, after)
                assertEquals(before.toLocalDate().plusDays(1), new.date)
                assertEquals(70, new.rows.single().numbers.single { it.kind == NumberKind.START }.value)
                assertEquals(70, new.rows.single().numbers.single { it.kind == NumberKind.END }.value)
                assertNotEquals(old.rows.single().numbers, new.rows.single().numbers)
                assertEquals(RowState.FRESH, new.rows.single().state)
                // Drain the actual publication callback, then assert unchanged reconciliation does not publish.
                host.instrumentation.waitForIdleSync()
                val count = host.updates.get()
                host.instrumentation.runOnMainSync { WidgetReconciler.reconcile(host.context, ReconcileReason.UPDATE) }
                host.instrumentation.waitForIdleSync()
                assertEquals(count, host.updates.get())
                host.pulse()
                host.await("Identical options pulse republishes") { host.updates.get() > count }
                assertTrue(host.latest.get()!!.sameAs(TimelineRenderer.render(host.context, new, host.width, host.height)))
            }
        } finally { WidgetReconciler.clock = previous }
    }

    @Test fun realSystemClockMidnightDeadlineAndZoneBroadcastReachTheHostedPicture() {
        assertNull("Use the real system clock", WidgetReconciler.clock)
        SystemTimeFixture().use { time ->
            time.disableAutomaticTime()
            time.setZone("UTC")
            WeatherHostFixture().use { host ->
                RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture offline") }
                val before = LocalDate.now(ZoneId.of("UTC")).atTime(23, 59, 30).atZone(ZoneId.of("UTC"))
                seed(host, before)
                host.configure(config)
                time.setTime(before.toInstant().toEpochMilli())
                expectHosted(host, before)
                // No explicit provider intent here: real RTC deadline crosses midnight naturally.
                host.awaitPublishedModel("Natural midnight alarm publishes the next date and numbers", 120_000) {
                    it.date == before.toLocalDate().plusDays(1) &&
                        it.rows.single().numbers.singleOrNull { n -> n.kind == NumberKind.START }?.value == 70
                }
                val count = host.updates.get()
                time.setZone("America/Los_Angeles")
                var changed = host.awaitPublishedModel("System timezone broadcast republishes cached local-day numbers") {
                    it.zone.id == "America/Los_Angeles" && it.date == before.toLocalDate() && host.updates.get() > count
                }
                assertFalse(changed.rows.single().numbers.isEmpty())
                val scheduler = host.context.getSystemService(android.app.job.JobScheduler::class.java)
                host.await("Offline midnight retry scheduled") { scheduler.getPendingJob(WeatherRuntime.RETRY) != null }
                val originalRetry = scheduler.getPendingJob(WeatherRuntime.RETRY)!!
                val attemptAt = originalRetry.extras.getLong("at")
                time.setTime(System.currentTimeMillis() - 5 * 60_000)
                changed = host.awaitPublishedModel("TIME_SET remaps retry delay and reaches hosted picture") { model ->
                    scheduler.getPendingJob(WeatherRuntime.RETRY)?.let {
                        it.extras.getLong("at") == attemptAt && it.minLatencyMillis >= 5 * 60_000
                    } == true && model.needleHour != changed.needleHour
                }
                val dump = time.shell("dumpsys alarm")
                android.util.Log.i("ReconcilerAlarm", dump)
                // Only live alarm headers, excluding historical/statistical dumps for the same action.
                val alarmHeaders = dump.lineSequence().filter { it.contains("Alarm{") && it.contains(host.context.packageName) }.toList()
                assertEquals("Exactly one package alarm: $alarmHeaders", 1, alarmHeaders.size)
                assertTrue("Non-wakeup RTC: $alarmHeaders", alarmHeaders.single().contains("type 1 "))
                assertTrue("Deadline retained by system", alarmHeaders.single().contains("origWhen ${changed.validUntil.toEpochMilli()}"))
            }
        }
    }
}
