package com.alexindigo.kotatsu.weather

import org.junit.Assert.*
import org.junit.Test
import java.time.ZonedDateTime

class SettlingTimelineTest {
    private val now = ZonedDateTime.parse("2026-10-31T10:30:00Z")
    private val config = WidgetConfig(listOf(Location("Home", "Fixture", 71.0, 80.0)))
    private val model = buildTimeline(config, emptyList(), emptyList(), now)

    @Test fun onlyAnExplicitHiddenHintSelectsSettling() {
        assertFalse(model.forHostVisibility(null).settling)
        assertFalse(model.forHostVisibility(true).settling)
        assertTrue(model.forHostVisibility(false).settling)
        assertEquals(model, model.forHostVisibility(false).forHostVisibility(true))
    }

    @Test fun hiddenVariantRetainsEveryLayoutAndForecastFact() {
        val hidden = model.forHostVisibility(false)
        assertEquals(model, hidden.copy(settling = false))
        assertEquals(model.rows, hidden.rows)
        assertEquals(model.needleHour, hidden.needleHour)
        assertEquals(model.lastUpdatedAt, hidden.lastUpdatedAt)
    }

    @Test fun hiddenVariantIsPublishedEvenForAnOrdinaryUnchangedDataTrigger() {
        val normal = PublishedTimeline(model, 344, 112)
        val hidden = normal.copy(model = model.forHostVisibility(false))
        assertTrue(shouldPublish(normal, hidden, ReconcileReason.UPDATE))
        assertFalse(shouldPublish(hidden, hidden, ReconcileReason.JOB_DONE))
        assertTrue(shouldPublish(hidden, normal, ReconcileReason.OPTIONS_CHANGED))
    }
}
