package com.alexindigo.kotatsu.weather

import android.content.res.Configuration
import android.os.LocaleList
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.ZonedDateTime
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class TimelineDescriptionInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val now = ZonedDateTime.parse("2026-10-31T08:30:00-07:00[America/Los_Angeles]")
    private val place = Location("Home", "Description fixture", 71.321, 82.123, start = 7, end = 19)
    private val series = ForecastSeries((0..48).map { h -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
        .toInstant().plusSeconds(h * 3600L), 64.0, 2, null) }, now.toInstant())

    @Test fun publishedRemoteViewsCarryFreshStaleAndNoDataDescriptionsAndRepublishForPhoneClock() {
        val previous = WidgetReconciler.clock
        WidgetReconciler.clock = Clock.fixed(now.toInstant(), now.zone)
        SystemTimeFixture().use { time ->
            val before = time.shell("settings get system time_12_24").trim()
            try {
                WeatherHostFixture().use { host ->
                    RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Fixture offline") }
                    val store = ForecastStore(context)
                    assertEquals(ForecastStore.Result.ACCEPTED,
                        store.accept(place, true, Parsed.Valid(series), now.toInstant(), now.zone))
                    host.configure(WidgetConfig(listOf(place)))
                    fun publish(expected: String) {
                        instrumentation.runOnMainSync { WidgetReconciler.reconcile(context, ReconcileReason.UPDATE) }
                        host.await("Description reaches the applied host ImageView: $expected") { host.description.get() == expected }
                    }
                    for ((setting, expected) in listOf("24" to "Home, 07:00 to 19:00, 64 to 64 degrees, cloudy",
                        "12" to "Home, 7:00 AM to 7:00 PM, 64 to 64 degrees, cloudy")) {
                        time.shell("settings put system time_12_24 $setting")
                        assertEquals(setting == "24", android.text.format.DateFormat.is24HourFormat(context))
                        publish(expected)
                    }
                    WidgetReconciler.clock = Clock.fixed(now.plusHours(13).toInstant(), now.zone)
                    publish("Home, 7:00 AM to 7:00 PM, 64 to 64 degrees, cloudy. Last updated at 8:30 AM")
                    host.configure(WidgetConfig(listOf(place.copy(latitude = 30.0))))
                    publish("Home, No forecast")
                    host.awaitPublishedModel("No-data picture and spoken description agree") { it.rows.single().state == RowState.NO_DATA }
                }
            } finally {
                WidgetReconciler.clock = previous
                time.shell(if (before == "null") "settings delete system time_12_24" else "settings put system time_12_24 $before")
                assertEquals(before, time.shell("settings get system time_12_24").trim())
            }
        }
    }

    @Test fun allNineLocalesDescribeEveryConditionAndMissingRowsWithoutEnglishFallback() {
        val expected = mapOf(
            "en" to listOf("clear", "cloudy", "windy", "foggy", "rain", "snow", "thunderstorm"),
            "cs" to listOf("jasno", "oblačno", "větrno", "mlha", "déšť", "sníh", "bouřka"),
            "de" to listOf("klar", "bewölkt", "windig", "neblig", "Regen", "Schnee", "Gewitter"),
            "es" to listOf("despejado", "nublado", "viento", "niebla", "lluvia", "nieve", "tormenta"),
            "fr" to listOf("ciel dégagé", "nuageux", "venteux", "brouillard", "pluie", "neige", "orage"),
            "it" to listOf("sereno", "nuvoloso", "ventoso", "nebbia", "pioggia", "neve", "temporale"),
            "nl" to listOf("helder", "bewolkt", "winderig", "mistig", "regen", "sneeuw", "onweer"),
            "pl" to listOf("bezchmurnie", "pochmurno", "wietrznie", "mgła", "deszcz", "śnieg", "burza"),
            "pt" to listOf("céu limpo", "nublado", "vento", "nevoeiro", "chuva", "neve", "trovoada"))
        val base = buildTimeline(WidgetConfig(listOf(place)), listOf(series), emptyList(), now)
        for ((language, words) in expected) {
            val localized = context.createConfigurationContext(Configuration(context.resources.configuration).apply {
                setLocales(LocaleList(Locale.forLanguageTag(language)))
            })
            for ((index, condition) in Condition.entries.withIndex()) {
                val model = base.copy(rows = listOf(base.rows.single().copy(condition = condition)))
                val description = WeatherPresentation.description(localized, model)
                assertTrue("$language/$condition: $description", description.endsWith(", ${words[index]}"))
                assertFalse(description.contains("%"))
                if (language != "en") assertFalse(description.contains("degrees"))
            }
            val empty = buildTimeline(WidgetConfig(listOf(place)), listOf(null), emptyList(), now)
            assertEquals("Home, ${localized.getString(R.string.no_forecast)}", WeatherPresentation.description(localized, empty))
            if (language != "en") assertNotEquals("No forecast", localized.getString(R.string.no_forecast))
            val old = base.copy(lastUpdatedAt = now.toInstant().minusSeconds(7200))
            assertTrue(WeatherPresentation.description(localized, old).endsWith(
                WeatherPresentation.lastUpdated(localized, old.lastUpdatedAt!!, old.zone)))
        }
    }
}
