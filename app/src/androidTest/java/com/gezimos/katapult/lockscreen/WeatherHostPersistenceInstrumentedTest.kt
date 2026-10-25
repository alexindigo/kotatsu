package com.gezimos.katapult.lockscreen

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.MainActivity
import com.gezimos.katapult.PlatformUiDriver
import com.gezimos.katapult.WidgetHostTestFixture
import com.gezimos.katapult.WidgetProviderTestFixture
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The separate APK is exercised through the launcher's real host and public Weather controls. */
@RunWith(AndroidJUnit4::class)
class WeatherHostPersistenceInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val weather = "com.alexindigo.kotatsu.weather.debug"
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    @Test fun weatherEditsSurviveRealHostRebindingAndPermissionDeath() {
        val retained = InstrumentationRegistry.getArguments().getString("retainWeatherFixture") == "true"
        // A retained acceptance fixture deliberately outlives instrumentation. Normal tests own a
        // scoped selection and all allocations, with a synchronous teardown barrier.
        val fixture = if (retained) null else WidgetHostTestFixture(context, applicationPreferences = true)
        try { WidgetProviderTestFixture(context).use { exerciseHost(it) } } finally { fixture?.close() }
    }

    private fun exerciseHost(providers: WidgetProviderTestFixture) {
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        val ui = PlatformUiDriver(instrumentation)
        val host = LockscreenWidgetHost.get(context)
        val info = providers.singleProvider(weather)
        val before = activity.viewModel.lockscreenWidgets.rows.map { it.widget.id }.toSet()
        var id: Int? = null
        fun locate(lat: Int, lon: Int) {
            shell("cmd location providers set-test-provider-location gps --location $lat,$lon --accuracy 5")
            ui.click("Add location"); ui.click("Add current location")
            ui.await("Current location was not added") { ui.has("Current location, $lat.000, $lon.000\n7:00 to 23:00") }
        }
        fun openIcon() {
            context.startActivity(context.packageManager.getLaunchIntentForPackage(weather)!!)
            ui.await("Weather icon did not open settings") { ui.has("Add location") }
        }
        fun hasTwoPlaces() = ui.has("Current location, 10.000, 20.000\n7:00 to 23:00") &&
            ui.has("Current location, 30.000, 40.000\n7:00 to 23:00")
        try {
            ui.await("Launcher startup missing") { ui.has("Finish") || ui.has("Apps") }
            if (ui.has("Finish")) ui.click("Finish")
            shell("pm grant $weather android.permission.ACCESS_COARSE_LOCATION")
            shell("pm grant $weather android.permission.ACCESS_FINE_LOCATION")
            shell("cmd location set-location-enabled true")
            shell("appops set 2000 android:mock_location allow")
            shell("cmd location providers add-test-provider gps")
            shell("cmd location providers set-test-provider-enabled gps true")
            instrumentation.uiAutomation.adoptShellPermissionIdentity("android.permission.BIND_APPWIDGET")
            instrumentation.runOnMainSync { activity.addLockscreenWidget(info) }
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            ui.await("Real host did not open configure") { ui.has("Add location") }
            locate(10, 20)
            ui.click("Done")
            ui.await("Host did not retain configured widget") {
                activity.viewModel.lockscreenWidgets.rows.any { it.widget.id !in before }
            }
            id = activity.viewModel.lockscreenWidgets.rows.single { it.widget.id !in before }.widget.id
            openIcon()
            locate(30, 40)
            assertTrue(hasTwoPlaces())
            // Back is not a save/cancel boundary anymore. A fresh app-icon entry must read both.
            ui.back()
            openIcon(); ui.await("Back lost a place") { hasTwoPlaces() }; ui.click("Done")

            // Settings' Edit callback reaches this exact MainActivity/host path, including RESULT_OK.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync { activity.editLockscreenWidget(id!!) }
            ui.await("Reconfigure lost a place") { hasTwoPlaces() }
            ui.click("Current location, 30.000, 40.000\n7:00 to 23:00")
            ui.click("Remove location")
            locate(30, 40)
            assertTrue(hasTwoPlaces())
            ui.click("Done")

            instrumentation.runOnMainSync {
                host.listen(true)
                LockscreenWidgetHost.updateSize(context, LockscreenWidget(id!!, LockscreenWidgetSize.M))
                val view = host.createView(activity, id!!, info)
                assertEquals(listOf(0, 0, 0, 0), listOf(view.paddingLeft, view.paddingTop, view.paddingRight, view.paddingBottom))
                val timeline = context.packageManager.getResourcesForApplication(weather).getIdentifier("timeline", "id", weather)
                assertNotNull("Actual host consumed the provider bitmap", view.findViewById<ImageView>(timeline)?.drawable)
                assertFalse("Companion widget has no editor click action", view.performClick())
                host.listen(false); host.releaseViews(); host.listen(true)
                assertNotNull(host.createView(activity, id!!, info).findViewById<ImageView>(timeline)?.drawable)
            }
            shell("su 0 am broadcast -a android.appwidget.action.APPWIDGET_UPDATE -n $weather/com.alexindigo.kotatsu.weather.WeatherWidgetProvider --eia appWidgetIds $id")
            openIcon(); ui.await("Provider update lost a place") { hasTwoPlaces() }
            // Revoke from outside Weather: Android kills that process, just as one-time revocation does.
            context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.waitForIdleSync()
            val oldPid = shell("pidof $weather").trim()
            assertTrue(oldPid.isNotEmpty())
            shell("pm revoke $weather android.permission.ACCESS_FINE_LOCATION")
            shell("pm revoke $weather android.permission.ACCESS_COARSE_LOCATION")
            ui.await("Permission revocation did not kill Weather", 10_000) { shell("pidof $weather").trim() != oldPid }
            openIcon(); ui.await("Permission-death recreation lost a place") { hasTwoPlaces() }; ui.click("Done")
        } finally {
            instrumentation.uiAutomation.dropShellPermissionIdentity()
            instrumentation.runOnMainSync {
                // Optional retained fixture supports reboot/update acceptance outside instrumentation.
                if (InstrumentationRegistry.getArguments().getString("retainWeatherFixture") != "true") {
                    id?.let { activity.viewModel.lockscreenWidgets.remove(it) }
                }
                host.listen(false)
                activity.finish()
            }
            instrumentation.waitForIdleSync()
            shell("cmd location providers remove-test-provider gps")
        }
        instrumentation.sendStatus(0, android.os.Bundle().apply { putInt("weatherWidgetId", id!!) })
    }
}
