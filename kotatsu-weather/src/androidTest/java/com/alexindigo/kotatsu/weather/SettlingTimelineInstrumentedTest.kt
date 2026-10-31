package com.alexindigo.kotatsu.weather

import android.graphics.Color
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZonedDateTime

@RunWith(AndroidJUnit4::class)
class SettlingTimelineInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = ZonedDateTime.parse("2026-10-31T10:30:00Z")
    private val place = Location("Home", "Settling fixture", 75.321, 83.123, start = 7, end = 23)
    private val config = WidgetConfig(listOf(place, place.copy(label = "School", id = "settling-school")))
    private val series = ForecastSeries((0..72).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(h * 3600L), 64.0, 2, null) }, now.minusHours(2).toInstant())

    @Test fun settlingChangesOnlyRowInkAndNeedleWithIdenticalGeometryStatusAndDescription() {
        val model = buildTimeline(config, listOf(series, series), emptyList(), now)
        val hidden = model.forHostVisibility(false)
        val width = (344 * context.resources.displayMetrics.density).toInt()
        val height = (160 * context.resources.displayMetrics.density).toInt()
        assertNotNull(model.needleHour)
        assertNotNull(model.lastUpdatedAt)
        assertEquals(TimelineRenderer.layout(context, model, width, height), TimelineRenderer.layout(context, hidden, width, height))
        assertEquals(WeatherPresentation.description(context, model), WeatherPresentation.description(context, hidden))
        val normal = TimelineRenderer.render(context, model, width, height)
        val settling = TimelineRenderer.render(context, hidden, width, height)
        val noNeedle = TimelineRenderer.render(context, model.copy(needleHour = null), width, height)
        val greyControl = TimelineRenderer.render(context, model.copy(needleHour = null,
            rows = model.rows.map { it.copy(stale = true) }), width, height)
        assertFalse("Normal needle and row ink must change", normal.sameAs(settling))
        assertFalse("Row ink changes independently of the needle", noNeedle.sameAs(settling))
        assertTrue("Exact existing grey row rendering without a needle, status untouched", greyControl.sameAs(settling))
        var black = 0
        var grey = 0
        for (y in 0 until height) for (x in 0 until width) {
            if (settling.getPixel(x, y) == Color.BLACK) black++
            if (settling.getPixel(x, y) == Color.rgb(160, 160, 160)) grey++
        }
        assertEquals("No black label/number/icon/dot survives settling", 0, black)
        assertTrue(grey > 0)
    }

    @Test fun everyPublishWhileHiddenStaysSettlingAndVisiblePulseRestoresNormal() {
        val previous = WidgetReconciler.clock
        WidgetReconciler.clock = Clock.fixed(now.toInstant(), now.zone)
        try { WeatherHostFixture().use { host ->
            RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture offline") }
            assertEquals(ForecastStore.Result.ACCEPTED, ForecastStore(context).accept(place, true, Parsed.Valid(series),
                now.minusHours(2).toInstant(), now.zone))
            host.configure(config)
            host.awaitPublishedModel("Normal picture arrives") { !it.settling && it.rows.all { row -> row.state == RowState.FRESH } }
            val description = host.description.get()
            instrumentation.runOnMainSync { host.manager.updateAppWidgetOptions(host.id, Bundle().apply {
                putBoolean(WidgetReconciler.OPTION_HOST_VISIBLE, false)
            }) }
            host.awaitPublishedModel("Hidden options publishes settling") { it.settling }
            for ((index, reason) in listOf(ReconcileReason.UPDATE, ReconcileReason.DEADLINE, ReconcileReason.JOB_DONE,
                    ReconcileReason.TIMEZONE_CHANGED).withIndex()) {
                val at = now.plusMinutes((index + 1) * 16L)
                WidgetReconciler.clock = Clock.fixed(at.toInstant(), now.zone)
                instrumentation.runOnMainSync { WidgetReconciler.reconcile(context, reason) }
                host.awaitPublishedModel("$reason delivers its new hidden picture") { it.settling && it.needleHour == at.hour + at.minute / 60.0 }
            }
            assertEquals(description, host.description.get())
            instrumentation.runOnMainSync { host.manager.updateAppWidgetOptions(host.id, Bundle().apply {
                putBoolean(WidgetReconciler.OPTION_HOST_VISIBLE, true)
            }) }
            host.awaitPublishedModel("Visible pulse restores normal picture") { !it.settling }
        } } finally { WidgetReconciler.clock = previous }
    }
}
