package com.alexindigo.kotatsu.weather

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class WeatherInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = ZonedDateTime.of(2026, 10, 16, 12, 30, 0, 0, ZoneId.of("America/Los_Angeles"))
    private val timestamp = now.toInstant().toEpochMilli()
    private val home = Location("Home", "Portola Valley", 37.3841, -122.2352)
    private val school = Location("School", "Campbell", 37.2872, -121.9499, 9, 19)
    private val config = WidgetConfig(listOf(home, school))
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun forecast() = WeatherJson.forecast(fixture("forecast.json"), timestamp)

    @Test fun parseForecastFixture() {
        val result = forecast()
        assertEquals(25, result.hours.size)
        assertEquals(54.0, result.hours.last().temperature, 0.0)
        // 23:00 mark = mean of 22:00, 23:00 and the next day's 00:00 (56, 55, 54).
        assertEquals(55, temperatureNumbers(home, result, now.toLocalDate()).single { it.kind == NumberKind.END }.value)
        assertEquals(Condition.CLOUDS, spanHours(home, result, now.toLocalDate()).maxOf { condition(it.code, it.gust, true) })
    }
    @Test fun parseGeocodingFixture() {
        val result = WeatherJson.places(fixture("geocoding.json"))
        assertEquals(2, result.size)
        assertEquals("Portola Valley, California, United States", result.first().display)
        assertEquals(-121.9499, result.last().longitude, .00001)
        assertTrue(WeatherJson.places("{}").isEmpty())
    }
    @Test fun renderFreshTwoRows() {
        val forecast = forecast()
        val bitmap = render("fresh-two-rows", listOf(forecast, forecast.copy(hours = forecast.hours.map { it.copy(code = 61) })))
        assertTrue(hasColor(bitmap, Color.BLACK))
        assertTrue(hasColor(bitmap, Color.rgb(128, 128, 128)))
        assertTrue(hasColor(bitmap, Color.rgb(160, 160, 160)))
        assertNeedleBetweenRows(bitmap)
    }
    @Test fun renderStaleTwoRows() {
        val old = forecast().copy(fetchedAt = timestamp - 13 * 60 * 60 * 1000L)
        val bitmap = render("stale-two-rows", listOf(old, old))
        assertFalse(hasColor(bitmap, Color.BLACK))
        assertTrue(hasColor(bitmap, Color.rgb(160, 160, 160)))
        assertNeedleBetweenRows(bitmap)
    }
    @Test fun renderFourRowsCondensed() {
        val forecast = forecast()
        val four = WidgetConfig(listOf(home, school, home.copy(label = "Gym"), school.copy(label = "Park")))
        val bitmap = render("four-rows", listOf(forecast, forecast, forecast, forecast), four)
        assertTrue(hasColor(bitmap, Color.BLACK))
        assertNeedleBetweenRows(bitmap)
    }
    @Test fun renderNoData() {
        val bitmap = render("no-data", listOf(null, null))
        assertFalse(hasColor(bitmap, Color.BLACK))
        assertTrue(hasColor(bitmap, Color.rgb(160, 160, 160)))
    }
    @Test fun configAndCacheStaySeparateByWidgetUnitsAndZone() {
        val store = WeatherStore(context)
        val id = 876543
        try {
            store.save(id, config)
            assertEquals(config, WeatherStore(context).config(id))
            val zone = "fixture/isolated"
            store.cache(home, true, zone, fixture("forecast.json"), timestamp)
            assertEquals(25, store.cached(home.copy(latitude = 37.384101), true, zone)!!.hours.size)
            assertNull(store.cached(home, false, zone))
            assertNull(store.cached(home, true, "$zone/other"))
            assertThrows(Exception::class.java) { store.cache(home, true, zone, "{}", timestamp + 1) }
            assertEquals(timestamp, store.cached(home, true, zone)!!.fetchedAt)
        } finally { store.delete(id) }
        assertTrue(store.config(id).locations.isEmpty())
    }
    @Test fun noWidgetMeansNoNetworkJobs() {
        assertTrue(WeatherRuntime.ids(context).isEmpty())
        WeatherRuntime.cancel(context)
        WeatherRuntime.schedule(context)
        WeatherRuntime.refresh(context, force = true)
        assertTrue(context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.isEmpty())
    }

    private fun render(name: String, forecasts: List<Forecast?>, widget: WidgetConfig = config): Bitmap {
        val density = context.resources.displayMetrics.density
        val bitmap = TimelineRenderer.render(context, widget, forecasts, (464 * density).toInt(), (112 * density).toInt(), now)
        // Instrumentation executes as the tested debug app, which owns this external directory.
        val directory = File(checkNotNull(context.getExternalFilesDir(null)), "renders")
        check(directory.isDirectory || directory.mkdirs()) { "Cannot create render evidence directory" }
        File(directory, "$name.png").outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        return bitmap
    }
    private fun hasColor(bitmap: Bitmap, color: Int): Boolean {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.any { it == color }
    }
    private fun assertNeedleBetweenRows(bitmap: Bitmap) {
        // One unbroken grey column crosses both bottom-aligned rows and the gap between them.
        val needle = Color.rgb(128, 128, 128)
        val longest = (0 until bitmap.width).maxOf { x ->
            var run = 0; var best = 0
            for (y in 0 until bitmap.height) { run = if (bitmap.getPixel(x, y) == needle) run + 1 else 0; best = maxOf(best, run) }
            best
        }
        val density = context.resources.displayMetrics.density
        assertTrue("needle run $longest px", longest > 20 * density)
    }
}
