package com.alexindigo.kotatsu.weather

import android.os.Bundle
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.ZonedDateTime

/** Cross-APK fixture commands execute in the provider's process, with its real preference cache. */
@RunWith(AndroidJUnit4::class)
class SettlingFixtureInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val targetContext = instrumentation.targetContext

    @Test fun executeAcknowledgedFixtureCommand() {
        assumeTrue("Invoked by the launcher fixture with a command/token", arguments.containsKey("command"))
        RefreshJobService.fetcher = { _, _, _ -> throw java.io.IOException("Settling fixture offline") }
        var outcome: Result<Bundle>? = null
        instrumentation.runOnMainSync { outcome = runCatching { command() } }
        instrumentation.addResults(outcome!!.getOrThrow())
    }

    private fun command(): Bundle {
        val token = requireNotNull(arguments.getString("token"))
        require(token.matches(Regex("[a-zA-Z0-9-]+")))
        val command = arguments.getString("command")
        val backup = File(targetContext.filesDir, "settling-fixture-$token.json")
        val prefs = targetContext.getSharedPreferences("weather", 0)
        WeatherRuntime.cancel(targetContext)
        val result = Bundle()
        when (command) {
            "seed" -> {
                check(!backup.exists()) { "Fixture token already owns a snapshot" }
                val before = prefs.all
                check(before.values.all { it is String }) { "Unexpected Weather preference type" }
                // Save before any mutation. A separate restore command also works after a failed seed.
                backup.writeText(JSONObject(before).toString())
                val id = arguments.getString("widgetId")!!.toInt()
                check(id in WeatherRuntime.ids(targetContext)) { "Widget is not bound to Weather" }
                val now = ZonedDateTime.now(WidgetReconciler.currentClock())
                val home = Location("Home", "Public settling fixture", 76.555, 84.666, start = 1, end = 23)
                val store = WeatherStore(targetContext)
                store.delete(id)
                check(store.add(id, home) == WeatherStore.Result.APPLIED)
                check(store.add(id, home.copy(label = "School")) == WeatherStore.Result.APPLIED)
                val series = ForecastSeries((-2..74).map { hour -> HourSample(now.toLocalDate().atStartOfDay(now.zone)
                    .toInstant().plusSeconds(hour * 3600L), 64.0, 2, null) }, now.minusHours(2).toInstant())
                val forecasts = ForecastStore(targetContext)
                check(forecasts.accept(home, true, Parsed.Valid(series), series.fetchedAt, now.zone) == ForecastStore.Result.ACCEPTED)
                check(forecasts.recordFailure(home, true, RefreshOutcome.OFFLINE, now.toInstant(), now.zone))
                // Acknowledge what the same owners used by broadcasts/jobs actually read, not XML bytes.
                check(store.config(id)!!.locations.map { it.label } == listOf("Home", "School"))
                check(forecasts.snapshot(home, true, now.zone).series == series)
                WidgetReconciler.reconcile(targetContext, ReconcileReason.OPTIONS_CHANGED)
                val model = checkNotNull(WidgetReconciler.publishedModel(id))
                check(model.rows.map { it.label } == listOf("Home", "School"))
                check(model.rows.all { row -> row.state == RowState.FRESH && row.numbers.isNotEmpty() && row.numbers.all { it.value == 64 } })
                val description = WeatherPresentation.description(targetContext, model)
                result.putString("description", Base64.encodeToString(description.toByteArray(Charsets.UTF_8), Base64.NO_WRAP))
                result.putString("widgetId", id.toString())
            }
            "restore" -> {
                if (backup.exists()) {
                    val original = JSONObject(backup.readText())
                    val before = original.keys().asSequence().associateWith { original.getString(it) }
                    val edit = prefs.edit().clear()
                    before.forEach { (key, value) -> edit.putString(key, value) }
                    check(edit.commit()) { "Could not restore Weather fixture" }
                    check(prefs.all == before) { "Weather fixture restoration differs" }
                    check(backup.delete()) { "Could not retire restored fixture snapshot" }
                }
                WidgetReconciler.reconcile(targetContext, ReconcileReason.UPDATE)
            }
            else -> error("Unknown fixture command: $command")
        }
        result.putString("ack", "$command:$token")
        return result
    }
}
