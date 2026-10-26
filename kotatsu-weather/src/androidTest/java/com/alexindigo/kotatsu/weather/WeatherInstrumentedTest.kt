package com.alexindigo.kotatsu.weather

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import androidx.core.graphics.PathParser
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.ZoneId
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class WeatherInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = ZonedDateTime.of(2026, 10, 16, 12, 30, 0, 0, ZoneId.of("America/Los_Angeles"))
    private val day = DayWindow.of(now.toLocalDate(), now.zone)
    private val home = Location("Home", "Portola Valley", 37.3841, -122.2352)
    private val school = Location("School", "Campbell", 37.2872, -121.9499, 9, 19)
    private val config = WidgetConfig(listOf(home, school))
    private fun fixture(name: String) = instrumentation.context.assets.open(name).bufferedReader().use { it.readText() }
    private fun unixFixture(): String {
        val json = JSONObject(fixture("forecast.json"))
        val hourly = json.getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        hourly.put("time", JSONArray().apply { repeat(times.length()) {
            put(LocalDateTime.parse(times.getString(it)).toEpochSecond(ZoneOffset.ofHours(-7)))
        } })
        return json.toString()
    }
    private fun parse(json: String, fetchedAt: Instant = now.toInstant()) =
        (OpenMeteoParser.parse(json) as Parsed.Valid).series.copy(fetchedAt = fetchedAt)
    private fun forecast() = parse(unixFixture())

    private fun threeDayFixture(): String {
        val json = JSONObject(unixFixture())
        val hourly = json.getJSONObject("hourly")
        val firstDay = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        for (key in listOf("time", "temperature_2m", "weather_code", "wind_gusts_10m")) {
            val original = hourly.getJSONArray(key)
            hourly.put(key, JSONArray().apply {
                repeat(72) { hour -> put(if (key == "time") firstDay.plusSeconds(hour * 3600L).epochSecond else original.get(hour % 24)) }
            })
        }
        return json.toString()
    }

    @Test fun missingGustOrConditionKeepsTheTemperature() {
        val json = JSONObject(unixFixture())
        json.getJSONObject("hourly").getJSONArray("wind_gusts_10m").put(10, JSONObject.NULL)
        json.getJSONObject("hourly").getJSONArray("weather_code").put(11, JSONObject.NULL)
        val result = parse(json.toString())
        assertEquals(25, result.samples.size)
        val ten = result.samples.single { it.instant.atZone(now.zone).toLocalDateTime() == now.toLocalDate().atTime(10, 0) }
        assertEquals(55.0, ten.temperature, 0.0)
        assertNull(ten.gust)
        assertEquals(60.0, result.samples.single { it.instant.atZone(now.zone).hour == 11 }.temperature, 0.0)
        assertFalse(temperatureNumbers(home, result, day).isEmpty())
    }

    @Test fun partialReplyKeepsGoodForecastAndOriginalEnvelope() {
        val store = ForecastStore(context)
        val location = home.copy(latitude = 12.3456, longitude = 65.4321)
        val prefs = context.getSharedPreferences("weather", 0)
        val before = prefs.all
        try {
            store.accept(location, true, OpenMeteoParser.parse(threeDayFixture()), now.toInstant(), now.zone)
            val good = store.read(location, true, now.zone)!!
            val envelope = prefs.all
            val partial = JSONObject(threeDayFixture())
            val temperatures = partial.getJSONObject("hourly").getJSONArray("temperature_2m")
            for (hour in 12 until 72) temperatures.put(hour, JSONObject.NULL)
            assertEquals(ForecastStore.Result.INCOMPLETE, store.accept(location, true, OpenMeteoParser.parse(partial.toString()), now.toInstant().plusSeconds(60), now.zone))
            assertEquals(good, store.read(location, true, now.zone))
            assertEquals(now.toInstant(), store.read(location, true, now.zone)!!.fetchedAt)
            assertEquals(envelope.filterKeys { it.startsWith("forecast_") }, prefs.all.filterKeys { it.startsWith("forecast_") })
            for (hour in 0 until 12) temperatures.put(hour, JSONObject.NULL)
            store.accept(location, true, OpenMeteoParser.parse(partial.toString()), now.toInstant().plusSeconds(120), now.zone)
            assertEquals(envelope.filterKeys { it.startsWith("forecast_") }, prefs.all.filterKeys { it.startsWith("forecast_") })
            assertEquals(ForecastStore.Result.MALFORMED, store.accept(location, true, OpenMeteoParser.parse("{}"), now.toInstant().plusSeconds(180), now.zone))
            assertEquals(envelope.filterKeys { it.startsWith("forecast_") }, prefs.all.filterKeys { it.startsWith("forecast_") })
            assertTrue(TimelineRenderer.render(context, buildTimeline(WidgetConfig(listOf(location)), listOf(store.read(location, true)), emptyList(), now),
                464, 112).sameAs(TimelineRenderer.render(context, buildTimeline(WidgetConfig(listOf(location)), listOf(good), emptyList(), now), 464, 112)))
        } finally {
            prefs.edit().apply { (prefs.all.keys - before.keys).forEach { remove(it) } }.commit()
        }
    }

    @Test fun morningAfterEveningFetchHasAllSevenToTwentyThreeNumbers() {
        val evening = now.withHour(23).withMinute(50)
        val result = parse(threeDayFixture(), evening.toInstant())
        val tomorrow = evening.toLocalDate().plusDays(1)
        val tomorrowWindow = DayWindow.of(tomorrow, now.zone)
        val numbers = temperatureNumbers(home, result, tomorrowWindow)
        assertEquals(TemperatureNumber(7, 49, NumberKind.START), numbers.single { it.kind == NumberKind.START })
        assertEquals(TemperatureNumber(23, 56, NumberKind.END), numbers.single { it.kind == NumberKind.END })
        assertEquals(TemperatureNumber(15, 70, NumberKind.HIGH), numbers.single { it.kind == NumberKind.HIGH })
        assertFalse(numbers.any { it.kind == NumberKind.LOW })
        val byTime = result.samples.associateBy { it.instant }
        for (mark in 7..23 step 2) assertNotNull(markTemperature(byTime, tomorrowWindow, mark))
        assertTrue(temperatureNumbers(home, result.copy(samples = result.samples.take(48)), tomorrowWindow).isEmpty())
    }

    @Test fun weatherGlyphIs54PercentInsideUnchangedFrame() {
        // Frozen pre-resize frame geometry. The glyph must change without moving any frame pixels.
        val frame = PathParser.createPathFromPathData("M19.25,2.5C9.999,2.5 2.5,9.999 2.5,19.25V61.75C2.5,71.001 9.999,78.5 19.25,78.5H61.75C71.001,78.5 78.5,71.001 78.5,61.75V19.25C78.5,9.999 71.001,2.5 61.75,2.5H19.25ZM0,19.25C0,8.619 8.619,0 19.25,0H61.75C72.382,0 81,8.619 81,19.25V61.75C81,72.382 72.382,81 61.75,81H19.25C8.619,81 0,72.382 0,61.75V19.25Z")!!.apply { fillType = Path.FillType.EVEN_ODD }
        for (size in listOf(107, 192)) {
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            context.getDrawable(R.drawable.ic_kotatsu_weather)!!.apply {
                setBounds(0, 0, size, size)
                draw(Canvas(bitmap))
            }
            val expectedFrame = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            Canvas(expectedFrame).apply {
                scale(size / 81f, size / 81f)
                drawPath(frame, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK })
            }
            val inset = (size * 16f / 107f).toInt()
            var left = size; var top = size; var right = -1; var bottom = -1
            for (y in 0 until size) for (x in 0 until size) {
                if (x < inset || y < inset || x >= size - inset || y >= size - inset) {
                    assertEquals("Frame pixel $size/$x/$y", expectedFrame.getPixel(x, y), bitmap.getPixel(x, y))
                } else if (Color.alpha(bitmap.getPixel(x, y)) >= 128) {
                    left = minOf(left, x); top = minOf(top, y)
                    right = maxOf(right, x); bottom = maxOf(bottom, y)
                }
            }
            assertEquals("Weather glyph/$size", 0.54f,
                maxOf(right - left + 1, bottom - top + 1).toFloat() / size, 0.02f)
        }
    }

    @Test fun parseForecastFixture() {
        val result = forecast()
        assertEquals(25, result.samples.size)
        assertEquals(54.0, result.samples.last().temperature, 0.0)
        // 23:00 mark = mean of 22:00, 23:00 and the next day's 00:00 (56, 55, 54).
        assertEquals(55, temperatureNumbers(home, result, day).single { it.kind == NumberKind.END }.value)
        assertEquals(Condition.CLOUDS, spanHours(home, result, day).mapNotNull { condition(it.code, it.gust, true) }.maxOrNull())
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
        val bitmap = render("fresh-two-rows", listOf(forecast, forecast.copy(samples = forecast.samples.map { it.copy(code = 61) })))
        assertTrue(hasColor(bitmap, Color.BLACK))
        assertTrue(hasColor(bitmap, Color.rgb(128, 128, 128)))
        assertTrue(hasColor(bitmap, Color.rgb(160, 160, 160)))
        assertNeedleBetweenRows(bitmap)
    }
    @Test fun renderStaleTwoRows() {
        val old = forecast().copy(fetchedAt = now.toInstant().minusSeconds(13 * 60 * 60L))
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
    @Test fun configAndCacheStaySeparateByWidgetAndUnitsButNotZone() {
        val store = WeatherStore(context)
        val id = 876543
        try {
            context.getSharedPreferences("weather", 0).edit().putString("widget_$id", ConfigJson.encode(config)).commit()
            assertEquals(config, WeatherStore(context).config(id))
            val zone = "America/Los_Angeles"
            val forecasts = ForecastStore(context)
            forecasts.accept(home, true, OpenMeteoParser.parse(unixFixture()), now.toInstant(), ZoneId.of(zone))
            assertEquals(25, forecasts.read(home.copy(latitude = 37.384101), true)!!.samples.size)
            assertNull(forecasts.read(home, false))
            assertEquals(forecasts.read(home, true), forecasts.read(home, true, ZoneId.of("Asia/Tokyo")))
            assertEquals(ForecastStore.Result.MALFORMED, forecasts.accept(home, true, OpenMeteoParser.parse("{}"), now.toInstant().plusMillis(1), now.zone))
            assertEquals(now.toInstant(), forecasts.read(home, true)!!.fetchedAt)
        } finally { store.delete(id) }
        assertTrue(store.config(id)!!.locations.isEmpty())
    }
    @Test fun noWidgetMeansNoNetworkJobs() {
        assertTrue(WeatherRuntime.ids(context).isEmpty())
        WeatherRuntime.cancel(context)
        WeatherRuntime.schedule(context)
        WeatherRuntime.refresh(context, force = true)
        assertTrue(context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.isEmpty())
    }

    private fun render(name: String, forecasts: List<ForecastSeries?>, widget: WidgetConfig = config): Bitmap {
        val density = context.resources.displayMetrics.density
        val bitmap = TimelineRenderer.render(context, buildTimeline(widget, forecasts, emptyList(), now), (464 * density).toInt(), (112 * density).toInt())
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
