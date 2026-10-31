package com.alexindigo.kotatsu.weather

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.DateFormatSymbols
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class WeatherStatusInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = ZonedDateTime.parse("2026-10-30T08:30:00-07:00[America/Los_Angeles]")
    private val places = listOf("Palo Alt", "Portola", "Oakland", "HalfMoon").mapIndexed { i, label ->
        Location(label, "$label fixture", 11.0 + i, 21.0 + i)
    }
    private val updated = now.withHour(7).withMinute(10).toInstant()
    private val series = ForecastSeries((0..72).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(h * 3600L), when (h % 24) { 13 -> 90.0; 23 -> 54.0; else -> 58.0 }, 2, null) }, updated)

    private fun <T> withClockPreference(block: ((Boolean) -> Unit) -> T): T {
        SystemTimeFixture().use { time ->
            val before = time.shell("settings get system time_12_24").trim()
            try {
                return block { use24 ->
                    time.shell("settings put system time_12_24 ${if (use24) 24 else 12}")
                    assertEquals(use24, android.text.format.DateFormat.is24HourFormat(context))
                }
            } finally {
                time.shell(if (before == "null") "settings delete system time_12_24" else "settings put system time_12_24 $before")
                assertEquals(before, time.shell("settings get system time_12_24").trim())
            }
        }
    }

    /** Resource grammar is independent of the clock oracle; neither expected value calls the formatter. */
    private fun timedMessagePattern(target: Context, resource: Int): Regex {
        val marker = "__CLOCK_VALUE__"
        val parts = target.getString(resource, marker).split(marker)
        assertEquals("One clock placeholder in ${target.resources.getResourceName(resource)}", 2, parts.size)
        return Regex(Regex.escape(parts[0]) + "(.+)" + Regex.escape(parts[1]))
    }

    private fun assertTimedMessage(target: Context, actual: String, resource: Int,
        hour: Int, minute: Int, use24: Boolean) {
        val message = timedMessagePattern(target, resource).matchEntire(actual)
        assertNotNull("Expected localized resource wrapper: $actual", message)
        val clock = message!!.groupValues[1]
        val locale = target.resources.configuration.locales[0]
        val shape = if (use24) Regex("([01][0-9]|2[0-3]):([0-5][0-9])") else {
            val period = DateFormatSymbols.getInstance(locale).amPmStrings[if (hour < 12) 0 else 1]
            Regex("([1-9]|1[0-2]):([0-5][0-9])[\\s\\u00a0\\u202f]+" + Regex.escape(period))
        }
        val time = shape.matchEntire(clock)
        assertNotNull("Expected ${if (use24) "24h" else "12h"} clock shape for $locale: $clock", time)
        val expectedHour = if (use24) hour else (hour % 12).takeIf { it != 0 } ?: 12
        assertEquals("Clock hour", expectedHour, time!!.groupValues[1].toInt())
        assertEquals("Clock minute", minute, time.groupValues[2].toInt())
    }

    @Test fun smallStatusTextHasAtLeastLabelTrackingInTheRenderedInk() = withClockPreference { set24 ->
        val display = context.resources.displayMetrics
        val model = buildTimeline(WidgetConfig(places.take(3)), List(3) { series }, emptyList(), now)
        val width = (344 * display.density).toInt()
        val height = (112 * display.density).toInt()
        val layout = TimelineRenderer.layout(context, model, width, height)
        for (use24 in listOf(false, true)) {
            set24(use24)
            val text = WeatherPresentation.lastUpdated(context, updated, now.zone)
            assertTimedMessage(context, text, R.string.last_updated_at, 7, 10, use24)
            val plain = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                typeface = context.resources.getFont(R.font.lato_regular)
                fontFeatureSettings = "tnum"
                textSize = 1000f
            }
            val font = plain.fontMetrics
            val status = planStatusText(layout, width.toFloat(), display.density, display.scaledDensity,
                plain.measureText(text) / 1000, font.top / 1000, font.bottom / 1000)
            plain.textSize = status.textSize
            val minimumWidth = plain.measureText(text) + .05f * status.textSize * (text.codePointCount(0, text.length) - 1)
            val bitmap = TimelineRenderer.render(context, model, width, height)
            var left = width
            var right = -1
            for (y in 0 until layout.rowTops.first().toInt()) for (x in 0 until width) {
                if (bitmap.getPixel(x, y) != Color.WHITE) { left = minOf(left, x); right = maxOf(right, x) }
            }
            assertTrue("Status ink is too tightly spaced: ${right - left + 1} < $minimumWidth",
                right - left + 1 >= minimumWidth - 2)
        }
    }

    @Test fun reservedStatusNeverMovesRowsAndEveryLocaleFitsInItsOwnLane() = withClockPreference { set24 ->
        val density = context.resources.displayMetrics.density
        for (use24 in listOf(false, true)) {
            set24(use24)
            for (language in listOf("en", "cs", "de", "es", "fr", "it", "nl", "pl", "pt")) {
                val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(language)))
                })
                for (heightDp in listOf(64, 112, 160)) for (count in 1..4) {
                    val config = WidgetConfig(places.take(count))
                    val old = buildTimeline(config, List(count) { series }, emptyList(), now).copy(needleHour = null)
                    val fresh = old.copy(lastUpdatedAt = null)
                    val width = (344 * density).toInt()
                    val height = (heightDp * density).toInt()
                    val plan = TimelineRenderer.layout(localized, old, width, height)
                    assertEquals(plan, TimelineRenderer.layout(localized, fresh, width, height))
                    val shown = TimelineRenderer.render(localized, old, width, height)
                    val hidden = TimelineRenderer.render(localized, fresh, width, height)
                    var statusInk = 0
                    var lastStatusY = -1
                    for (y in 0 until height) for (x in 0 until width) {
                        if (y >= plan.rowTops.first().toInt()) assertEquals("Rows moved $language/$heightDp/$count", hidden.getPixel(x, y), shown.getPixel(x, y))
                        if (y < plan.rowTops.first().toInt()) {
                            assertEquals(Color.WHITE, hidden.getPixel(x, y))
                            if (shown.getPixel(x, y) != Color.WHITE) {
                                statusInk++
                                lastStatusY = maxOf(lastStatusY, y)
                                assertTrue("Status outside lane $language/$use24/$heightDp/$count x=$x y=$y lane=${plan.statusTop}..${plan.statusTop + plan.statusHeight}",
                                    y >= plan.statusTop - 1 && y < plan.statusTop + plan.statusHeight)
                                assertTrue("Status at edge", x > 0 && x < width - 1)
                                assertNotEquals(Color.BLACK, shown.getPixel(x, y))
                            }
                        }
                    }
                    assertTrue("No status ink $language/$heightDp/$count", statusInk > 0)
                    assertTrue("Caption gap lost $language/$heightDp/$count", plan.rowTops.first() - (lastStatusY + 1) >= plan.statusGap - 1)
                }
                for ((outcome, reason) in listOf(RefreshOutcome.OFFLINE to R.string.refresh_offline,
                    RefreshOutcome.HTTP(503) to R.string.refresh_server_error,
                    RefreshOutcome.INCOMPLETE to R.string.refresh_incomplete, RefreshOutcome.MALFORMED to R.string.refresh_invalid)) {
                    val status = RefreshPolicy.after(RefreshStatus(lastSuccessAt = updated), outcome, now.toInstant())
                    val text = WeatherPresentation.editor(localized, ForecastStore.Snapshot(series, status), now.zone)
                    val lines = text.lines()
                    assertEquals(3, lines.size)
                    assertTimedMessage(localized, lines[0], R.string.last_updated_at, 7, 10, use24)
                    assertEquals(if (outcome is RefreshOutcome.HTTP) localized.getString(reason, 503)
                        else localized.getString(reason), lines[1])
                    assertTimedMessage(localized, lines[2], R.string.next_try_at, 8, 31, use24)
                }
                for (hour in listOf(0, 12, 19, 23)) {
                    val timestamp = now.withHour(hour).withMinute(10).toInstant()
                    assertTimedMessage(localized, WeatherPresentation.lastUpdated(localized, timestamp, now.zone),
                        R.string.last_updated_at, hour, 10, use24)
                }
            }
        }
    }

    @Test fun providerAndEditorShowRetainedUpdateReasonAndNextTryAndRespectPhoneClock() = withClockPreference { set24 ->
        val previous = WidgetReconciler.clock
        WidgetReconciler.clock = Clock.fixed(now.toInstant(), now.zone)
        var editor: ConfigureActivity? = null
        try {
            WeatherHostFixture().use { host ->
                // Both jobs and the editor consume the same persisted facts. Keep the fixture retry in the future.
                RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture offline") }
                val store = ForecastStore(context)
                assertEquals(ForecastStore.Result.ACCEPTED, store.accept(places[0], true, Parsed.Valid(series), updated, now.zone))
                assertTrue(store.recordFailure(places[0], true, RefreshOutcome.HTTP(503), now.toInstant(), now.zone))
                host.configure(WidgetConfig(places.take(1)))
                for (use24 in listOf(true, false)) {
                    set24(use24)
                    instrumentation.runOnMainSync { WidgetReconciler.reconcile(context, ReconcileReason.TIME_SET) }
                    val model = host.awaitPublishedModel("Retained timestamp reaches hosted bitmap") { it.lastUpdatedAt == updated }
                    assertTimedMessage(context, WeatherPresentation.lastUpdated(context, model.lastUpdatedAt!!, model.zone),
                        R.string.last_updated_at, 7, 10, use24)
                    editor = instrumentation.startActivitySync(Intent(context, ConfigureActivity::class.java)
                        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, host.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as ConfigureActivity
                    instrumentation.waitForIdleSync()
                    val texts = mutableListOf<String>()
                    fun collect(view: View) {
                        if (view is TextView) texts += view.text.toString()
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                    instrumentation.runOnMainSync { collect(editor!!.window.decorView) }
                    val details = texts.single { timedMessagePattern(context, R.string.last_updated_at).matches(it.lineSequence().first()) }.lines()
                    assertEquals(3, details.size)
                    assertTimedMessage(context, details[0], R.string.last_updated_at, 7, 10, use24)
                    assertEquals(context.getString(R.string.refresh_server_error, 503), details[1])
                    assertTimedMessage(context, details[2], R.string.next_try_at, 8, 31, use24)
                    instrumentation.runOnMainSync { editor!!.finish() }
                    editor = null
                }
            }
        } finally {
            instrumentation.runOnMainSync { editor?.finish() }
            WidgetReconciler.clock = previous
        }
    }

    /**
     * Opt-in review artifacts, not content assertions. With the owned API31 emulator running in
     * arch-niri-kotatsu-devbase-kotatsu-wabc on ark.home, use the sanitized kotatsu-guest environment:
     * export TMPDIR=/srv/kotatsu/evidence/wabc/tmp
     * /srv/kotatsu/tools/gradle-9.3.1/bin/gradle -p /srv/kotatsu/source/wabc --offline --no-daemon --max-workers=2 \
     *   -Porg.gradle.java.installations.auto-download=false \
     *   -Porg.gradle.java.installations.paths=/usr/lib/jvm/java-21-openjdk -Pandroid.builder.sdkDownload=false \
     *   -Pandroid.injected.androidTest.leaveApksInstalledAfterRun=true \
     *   -Pandroid.experimental.testOptions.uninstallIncompatibleApks=false \
     *   -Pandroid.testInstrumentationRunnerArguments.captureScreenshots=true \
     *   -Pandroid.testInstrumentationRunnerArguments.class=com.alexindigo.kotatsu.weather.WeatherStatusInstrumentedTest#captureFullFrameSizeCountAndClockMatrixAndLayoutTable \
     *   :kotatsu-weather:connectedDebugAndroidTest
     * Omit captureScreenshots (the normal gate) to skip before changing settings or creating files.
     */
    @Test fun captureFullFrameSizeCountAndClockMatrixAndLayoutTable() {
        assumeTrue("Review captures require captureScreenshots=true",
            InstrumentationRegistry.getArguments().getString("captureScreenshots") == "true")
        captureReviewArtifacts()
    }

    private fun captureReviewArtifacts() = withClockPreference { set24 ->
        val directory = File(context.getExternalFilesDir(null), "renders/abc-gap").apply { mkdirs() }
        val display = context.resources.displayMetrics
        val table = StringBuilder("heightDp,count,reserveStatus,fontSp,pitchDp,rowHeightDp,statusDp,statusGapDp\n")
        val activity = instrumentation.startActivitySync(Intent(context, WeatherRenderActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)) as WeatherRenderActivity
        try {
            for (heightDp in listOf(64, 112, 160)) for (count in 1..4) {
                val config = WidgetConfig(places.take(count))
                val model = buildTimeline(config, List(count) { series }, emptyList(), now)
                val width = (344 * display.density).toInt()
                val height = (heightDp * display.density).toInt()
                for (slot in listOf(false, true)) {
                    val plan = TimelineRenderer.layout(context, model, width, height, slot)
                    table.append("$heightDp,$count,$slot,${plan.textSize / display.scaledDensity},${plan.rowPitch / display.density},${plan.rowHeight / display.density},${plan.statusHeight / display.density},${plan.statusGap / display.density}\n")
                }
                for (use24 in listOf(false, true)) {
                    set24(use24)
                    for (state in listOf("fresh", "old", "stale")) {
                        val shown = when (state) {
                            "fresh" -> model.copy(lastUpdatedAt = null)
                            "stale" -> buildTimeline(config, List(count) { series.copy(fetchedAt = updated.minusSeconds(13 * 3600)) }, emptyList(), now)
                            else -> model
                        }
                        val bitmap = TimelineRenderer.render(context, shown, width, height)
                        instrumentation.runOnMainSync { activity.show(bitmap) }
                        instrumentation.waitForIdleSync()
                        val screenshot = stableFullFrame()
                        // Every filename segment comes from the fixed size/count/state loops, never runner input.
                        File(directory, "$heightDp-$count-$state-${if (use24) "24h" else "12h"}.png").outputStream().use {
                            assertTrue(screenshot.compress(Bitmap.CompressFormat.PNG, 100, it))
                        }
                        if (heightDp == 112 && count == 3 && state == "old" && use24) {
                            // A separate full-frame capture for direct comparison with the user's screenshot.
                            instrumentation.waitForIdleSync()
                            stableFullFrame().let { comparison ->
                                File(directory, "112-3-comparison-24h.png").outputStream().use {
                                    assertTrue(comparison.compress(Bitmap.CompressFormat.PNG, 100, it))
                                }
                            }
                        }
                    }
                }
            }
            File(directory, "layout.csv").writeText(table.toString())
        } finally { instrumentation.runOnMainSync { activity.finish() } }
    }

    /** Deliver a settled frame, never the Activity's first draw or an in-flight window transition. */
    private fun stableFullFrame(): Bitmap {
        val deadline = android.os.SystemClock.uptimeMillis() + 5000
        var previous = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        while (android.os.SystemClock.uptimeMillis() < deadline) {
            Thread.sleep(100)
            instrumentation.waitForIdleSync()
            val current = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            val stable = previous.sameAs(current)
            previous.recycle()
            if (stable) return current
            previous = current
        }
        previous.recycle()
        throw AssertionError("Emulator frame did not settle before capture")
    }
}
