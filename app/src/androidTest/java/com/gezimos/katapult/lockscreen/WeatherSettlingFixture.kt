package com.gezimos.katapult.lockscreen

import android.os.ParcelFileDescriptor
import android.util.Base64
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import java.util.UUID

/** An acknowledged provider-side seed; no external writes to live SharedPreferences files. */
internal class WeatherSettlingFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val weather = "com.alexindigo.kotatsu.weather.debug"
    private val provider = "$weather/com.alexindigo.kotatsu.weather.WeatherWidgetProvider"
    private val token = UUID.randomUUID().toString()
    private var ownsSeed = false

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    fun seed(widgetId: Int): String {
        // Exercise the adverse binding interleaving deliberately: Weather has read its pre-seed state.
        shell("su 0 am broadcast -f 0x20 -a android.appwidget.action.APPWIDGET_UPDATE -n $provider --eia appWidgetIds $widgetId")
        ownsSeed = true
        val result = command("seed", "-e widgetId $widgetId")
        assertEquals(widgetId.toString(), result.getValue("widgetId"))
        // Android ends instrumentation by stopping the target; restart its ordinary provider after commit.
        shell("su 0 am broadcast -f 0x20 -a android.appwidget.action.APPWIDGET_UPDATE -n $provider --eia appWidgetIds $widgetId")
        return String(Base64.decode(result.getValue("description"), Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun command(command: String, extra: String = ""): Map<String, String> {
        val output = shell("am instrument -w -r -e command $command -e token $token $extra " +
            "-e class com.alexindigo.kotatsu.weather.SettlingFixtureInstrumentedTest " +
            "$weather.test/androidx.test.runner.AndroidJUnitRunner")
        val result = output.lineSequence().filter { it.startsWith("INSTRUMENTATION_RESULT: ") }.associate { line ->
            val (key, value) = line.removePrefix("INSTRUMENTATION_RESULT: ").split('=', limit = 2)
            key to value
        }
        assertTrue("Weather-side fixture command failed: $output", output.contains("INSTRUMENTATION_CODE: -1"))
        assertEquals("Weather-side acknowledgement: $output", "$command:$token", result["ack"])
        return result
    }

    override fun close() {
        if (!ownsSeed) return
        command("restore")
        ownsSeed = false
        shell("su 0 am broadcast -f 0x20 -a android.appwidget.action.APPWIDGET_UPDATE -n $provider")
    }
}
