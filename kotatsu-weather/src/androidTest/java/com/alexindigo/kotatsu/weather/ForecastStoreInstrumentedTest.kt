package com.alexindigo.kotatsu.weather

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.widget.RemoteViews
import android.widget.ImageView
import android.os.Bundle
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(AndroidJUnit4::class)
class ForecastStoreInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val prefs = context.getSharedPreferences("weather", 0)
    private val zone = ZoneId.of("America/Los_Angeles")
    private val now = ZonedDateTime.of(2026, 10, 16, 12, 30, 0, 0, zone)
    private val place = Location("Home", "Store fixture", 15.4321, 75.4321)
    private val store get() = ForecastStore(context)
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun legacyKey(zone: String) = "cache_" + MessageDigest.getInstance("SHA-256")
        .digest("${place.coordinateKey}|true|$zone".toByteArray()).joinToString("") { "%02x".format(it) }
    private fun isolated(block: () -> Unit) {
        val before = prefs.all
        try { block() } finally {
            val edit = prefs.edit()
            (prefs.all.keys - before.keys).forEach(edit::remove)
            before.forEach { (key, value) -> if (value is String) edit.putString(key, value) }
            assertTrue(edit.commit())
            assertEquals(before, prefs.all)
        }
    }
    private fun render(series: ForecastSeries, at: ZonedDateTime = now, location: Location = place) =
        TimelineRenderer.render(context, buildTimeline(WidgetConfig(listOf(location)), listOf(series), emptyList(), at), 464, 112)

    @Test fun offlineLegacyUpgradePreservesNumbersPixelsAndFetchTimeAndRemovesZoneKeys() = isolated {
        val raw = fixture("forecast.json") // Kept as the production v0.3 ISO+offset format.
        val envelope = JSONObject().put("data", raw).put("fetchedAt", now.toInstant().toEpochMilli()).toString()
        val current = legacyKey(zone.id)
        val other = legacyKey("Europe/London")
        prefs.edit().putString(current, envelope).putString(other, envelope).commit()
        val oldKeys = prefs.all.keys
        val result = store.read(place, true, zone)!!
        assertEquals(now.toInstant(), result.fetchedAt)
        assertEquals(Instant.parse("2026-10-16T07:00:00Z"), result.samples.first().instant)
        val day = DayWindow.of(now.toLocalDate(), zone)
        val numbers = temperatureNumbers(place, result, day)
        assertEquals(49, numbers.single { it.kind == NumberKind.START }.value)
        assertEquals(55, numbers.single { it.kind == NumberKind.END }.value)
        assertEquals(70, numbers.single { it.kind == NumberKind.HIGH }.value)
        val hourly = JSONObject(raw).getJSONObject("hourly")
        // Independent pre-upgrade oracle: these fixture arrays are wall hours 0…24 on an ordinary day.
        val expected = ForecastSeries(day.instants.mapIndexed { i, instant -> HourSample(instant,
            hourly.getJSONArray("temperature_2m").getDouble(i), hourly.getJSONArray("weather_code").getInt(i),
            hourly.getJSONArray("wind_gusts_10m").getDouble(i)) }, now.toInstant())
        assertTrue("Offline upgraded row must retain the old pixels", render(expected).sameAs(render(result)))
        assertFalse(prefs.contains(current))
        assertFalse(prefs.contains(other))
        assertEquals(1, (prefs.all.keys - oldKeys).count { it.startsWith("forecast_") })
        assertEquals(result, ForecastStore(context).read(place, true, zone))
    }

    @Test fun unreadableLegacyEnvelopeIsDroppedWithoutTouchingConfiguration() = isolated {
        val key = legacyKey(zone.id)
        for (raw in listOf("broken", "{}", JSONObject().put("fetchedAt", 123).put("data", fixture("forecast.json").replace("\"utc_offset_seconds\": -25200,", "")).toString())) {
            prefs.edit().putString(key, raw).commit()
            assertNull(store.read(place, true, zone))
            assertFalse(prefs.contains(key))
        }
    }

    @Test fun fallbackFixtureRendersFourHourAverageAndZoneSwitchReusesTheSeriesInProcess() = isolated {
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val at = ZonedDateTime.of(2026, 11, 1, 12, 30, 0, 0, zone)
            val parsed = OpenMeteoParser.parse(fixture("forecast-fallback.json")) as Parsed.Valid
            assertEquals(ForecastStore.Result.ACCEPTED, store.accept(place, true, parsed, at.toInstant(), zone))
            val stored = store.read(place, true)!!
            assertEquals(listOf(0, 1, 1, 2, 3), stored.samples.take(5).map { it.instant.atZone(ZoneId.systemDefault()).hour })
            val row = place.copy(start = 1)
            val day = DayWindow.of(at.toLocalDate(), zone)
            assertEquals(15, temperatureNumbers(row, stored, day).single { it.kind == NumberKind.START }.value)
            // A normal UTC day with these wall-hour values has the same start/end/high as the DST day.
            val utcDay = DayWindow.of(at.toLocalDate(), ZoneOffset.UTC)
            val oracle = ForecastSeries(utcDay.instants.mapIndexed { h, instant -> HourSample(instant,
                when (h) { 0 -> 0.0; 1 -> 15.0; else -> (h + 1) * 10.0 }, 0, null) }, at.toInstant())
            assertTrue(render(stored, at, row).sameAs(render(oracle, at.withZoneSameLocal(ZoneOffset.UTC), row)))
            val envelope = prefs.all
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val same = ForecastStore(context).read(place, true)!!
            assertEquals(stored, same)
            assertEquals(envelope, prefs.all) // No new key or network work.
            val utcNow = at.withZoneSameInstant(ZoneId.systemDefault())
            assertEquals(7, same.samples.first().instant.atZone(ZoneId.systemDefault()).hour)
            // UTC is missing the 00…06 portion of this row, so the consumer really resolves in the new zone.
            assertTrue(temperatureNumbers(row, same, DayWindow.of(utcNow.toLocalDate(), utcNow.zone)).isEmpty())
            assertTrue(render(same, utcNow, row).sameAs(render(same.copy(samples = emptyList()), utcNow, row)))
        } finally {
            TimeZone.setDefault(previous)
            assertEquals(previous.id, TimeZone.getDefault().id)
            assertEquals(previous.toZoneId(), ZoneId.systemDefault())
        }
    }

    @Test fun saturdayEveningCacheRendersSundayNumbersAtBothEndsOfTheDayAndRejectsIncompleteReply() = isolated {
        val saturday = LocalDate.of(2026, 10, 17)
        val evening = saturday.atTime(23, 50).atZone(zone)
        val first = saturday.atStartOfDay(zone).toInstant()
        val hourly = JSONObject().put("time", JSONArray()).put("temperature_2m", JSONArray())
            .put("weather_code", JSONArray()).put("wind_gusts_10m", JSONArray())
        repeat(DayWindow.forecastDays * 24) { h ->
            hourly.getJSONArray("time").put(first.plusSeconds(h * 3600L).epochSecond)
            hourly.getJSONArray("temperature_2m").put(60)
            hourly.getJSONArray("weather_code").put(0)
            hourly.getJSONArray("wind_gusts_10m").put(JSONObject.NULL)
        }
        val good = OpenMeteoParser.parse(JSONObject().put("hourly", hourly).toString())
        assertEquals(ForecastStore.Result.ACCEPTED, store.accept(place, true, good, evening.toInstant(), zone))
        val original = store.read(place, true, zone)!!
        val envelope = prefs.all
        for (local in listOf(saturday.plusDays(1).atTime(0, 5), saturday.plusDays(1).atTime(23, 30))) {
            val at = local.atZone(zone)
            val numbers = temperatureNumbers(place, store.read(place, true, zone)!!, DayWindow.of(at.toLocalDate(), zone))
            assertEquals(60, numbers.single { it.kind == NumberKind.START }.value)
            assertEquals(60, numbers.single { it.kind == NumberKind.END }.value)
            val bitmap = render(original, at)
            val blank = render(original.copy(samples = emptyList()), at)
            assertFalse("Row must render numbers, not No forecast", bitmap.sameAs(blank))
            if (at.hour == 0) {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                assertTrue("Fresh at 00:05", pixels.any { it == Color.BLACK })
            }
        }
        repeat(72) { hourly.getJSONArray("temperature_2m").put(it, JSONObject.NULL) }
        val partial = OpenMeteoParser.parse(JSONObject().put("hourly", hourly).toString())
        assertEquals(ForecastStore.Result.INCOMPLETE, store.accept(place, true, partial, evening.toInstant().plusSeconds(600), zone))
        assertEquals(original, store.read(place, true, zone))
        assertEquals(envelope.filterKeys { it.startsWith("forecast_") }, prefs.all.filterKeys { it.startsWith("forecast_") })
        assertEquals(ForecastStore.Result.MALFORMED, store.accept(place, true, OpenMeteoParser.parse("{}"), evening.toInstant().plusSeconds(1200), zone))
        assertEquals(envelope.filterKeys { it.startsWith("forecast_") }, prefs.all.filterKeys { it.startsWith("forecast_") })
        assertTrue(render(original).sameAs(render(store.read(place, true, zone)!!)))
    }

    @Test fun concurrentStoreInstancesCannotOverwriteBetterCoverageOrItsFetchedAt() = isolated {
        val complete = OpenMeteoParser.parse(fixture("forecast-fallback.json")) as Parsed.Valid
        val incomplete = Parsed.Valid(complete.series.copy(samples = complete.series.samples.take(12)))
        val at = Instant.parse("2026-11-01T20:00:00Z")
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val jobs = listOf(complete, incomplete).map { parsed -> pool.submit {
                check(start.await(5, TimeUnit.SECONDS))
                ForecastStore(context).accept(place, true, parsed, if (parsed == complete) at else at.plusSeconds(60), zone)
            } }
            start.countDown()
            jobs.forEach { it.get(10, TimeUnit.SECONDS) }
            assertEquals(complete.series.copy(fetchedAt = at), store.read(place, true, zone))
        } finally { pool.shutdownNow() }
    }

    @Test fun incompleteRefreshJobPublishesRetainedNumbersToTheRealWidgetHost() = isolated {
        val deviceZone = ZoneId.systemDefault()
        val previousClock = WidgetReconciler.clock
        val clock = java.time.Clock.fixed(Instant.now(), deviceZone)
        try {
        WidgetReconciler.clock = clock
        val at = ZonedDateTime.now(clock)
        val first = DayWindow.of(at.toLocalDate(), deviceZone).start
        val hourly = JSONObject().put("time", JSONArray()).put("temperature_2m", JSONArray())
            .put("weather_code", JSONArray()).put("wind_gusts_10m", JSONArray())
        repeat(72) { h ->
            hourly.getJSONArray("time").put(first.plusSeconds(h * 3600L).epochSecond)
            hourly.getJSONArray("temperature_2m").put(60)
            hourly.getJSONArray("weather_code").put(0)
            hourly.getJSONArray("wind_gusts_10m").put(JSONObject.NULL)
        }
        val root = JSONObject().put("utc_offset_seconds", at.offset.totalSeconds).put("hourly", hourly)
        assertEquals(ForecastStore.Result.ACCEPTED, store.accept(place, true, OpenMeteoParser.parse(root.toString()), at.toInstant(), deviceZone))
        val original = store.read(place, true, deviceZone)!!
        repeat(72) { hourly.getJSONArray("temperature_2m").put(it, JSONObject.NULL) }
        val incomplete = root.toString()
        val fetched = CountDownLatch(1)
        val previousFetcher = RefreshJobService.fetcher
        RefreshJobService.fetcher = { _, _, _ -> fetched.countDown(); incomplete }
        val latest = AtomicReference<Bitmap?>()
        val updates = AtomicInteger()
        val host = object : AppWidgetHost(context, 0x4B5751) {
            override fun onCreateView(context: Context, appWidgetId: Int, info: AppWidgetProviderInfo): AppWidgetHostView =
                object : AppWidgetHostView(context) {
                    override fun updateAppWidget(remoteViews: RemoteViews?) {
                        super.updateAppWidget(remoteViews)
                        (findViewById<ImageView>(R.id.timeline)?.drawable as? BitmapDrawable)?.bitmap?.let {
                            latest.set(it); updates.incrementAndGet()
                        }
                    }
                }
        }
        val initialIds = host.appWidgetIds.toSet()
        val id = host.allocateAppWidgetId()
        fun await(description: String, ready: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (!ready() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(25)
            assertTrue(description, ready())
        }
        instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
        try {
            val manager = AppWidgetManager.getInstance(context)
            assertTrue(manager.bindAppWidgetIdIfAllowed(id, ComponentName(context, WeatherWidgetProvider::class.java)))
            instrumentation.runOnMainSync {
                host.createView(context, id, manager.getAppWidgetInfo(id))
                host.startListening()
            }
            await("Initial provider bitmap") { updates.get() > 0 }
            assertTrue(prefs.edit().putString("widget_$id", ConfigJson.encode(WidgetConfig(listOf(place)))).commit())
            val density = context.resources.displayMetrics.density
            val width = (464 * density).toInt()
            val height = (112 * density).toInt()
            instrumentation.runOnMainSync {
                manager.updateAppWidgetOptions(id, Bundle().apply {
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 464)
                    putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 112)
                })
                WidgetReconciler.reconcile(context, ReconcileReason.UPDATE)
            }
            await("Hosted numbers before refresh") {
                latest.get()?.sameAs(TimelineRenderer.render(context, buildTimeline(WidgetConfig(listOf(place)), listOf(original), emptyList(), ZonedDateTime.now(clock)), width, height)) == true
            }
            val beforeUpdates = updates.get()
            WeatherRuntime.refresh(context, force = true)
            assertTrue("Job must use the incomplete fetcher", fetched.await(15, TimeUnit.SECONDS))
            await("Job completion and a new retained-data host bitmap") {
                context.getSystemService(android.app.job.JobScheduler::class.java).getPendingJob(WeatherRuntime.IMMEDIATE) == null &&
                    updates.get() > beforeUpdates && latest.get()?.sameAs(TimelineRenderer.render(context,
                          buildTimeline(WidgetConfig(listOf(place)), listOf(original), emptyList(), ZonedDateTime.now(clock)), width, height)) == true
            }
            assertEquals(original, store.read(place, true, deviceZone))
        } finally {
            WeatherRuntime.cancel(context)
            instrumentation.runOnMainSync { host.stopListening(); host.deleteAppWidgetId(id) }
            WeatherStore(context).delete(id)
            RefreshJobService.fetcher = previousFetcher
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            assertEquals(initialIds, host.appWidgetIds.toSet())
        }
        } finally { WidgetReconciler.clock = previousClock }
    }
}
