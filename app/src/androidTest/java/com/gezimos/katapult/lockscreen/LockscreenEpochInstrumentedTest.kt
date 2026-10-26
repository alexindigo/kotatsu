package com.gezimos.katapult.lockscreen

import android.app.KeyguardManager
import android.app.UiAutomation
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.WidgetHostTestFixture
import com.gezimos.katapult.WeatherHostViewProbe
import com.gezimos.katapult.util.PrefsManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Actual service lifecycle and cross-APK options delivery; positive Kompakt box display is device-only. */
@RunWith(AndroidJUnit4::class)
class LockscreenEpochInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (!ready() && SystemClock.elapsedRealtime() < end) Thread.sleep(25)
        assertTrue(message, ready())
    }

    private fun service(): LockscreenWidgetService? = LockscreenWidgetService::class.java
        .getDeclaredField("instance").apply { isAccessible = true }.get(null) as? LockscreenWidgetService

    private fun <T> main(read: () -> T): T {
        var outcome: Result<T>? = null
        instrumentation.runOnMainSync { outcome = runCatching(read) }
        return outcome!!.getOrThrow()
    }

    private fun visibility(service: LockscreenWidgetService) = LockscreenWidgetService::class.java
        .getDeclaredField("visibility").apply { isAccessible = true }.get(service) as LockscreenVisibility

    @Test fun mainFailureReturnsToTestThreadAndRunsRestoration() {
        val testThread = Thread.currentThread()
        val expected = AssertionError("Fixture main-thread failure")
        var restored = false
        val failure = runCatching {
            try { main<Unit> { throw expected } } finally {
                assertSame(testThread, Thread.currentThread())
                restored = main { true }
            }
        }.exceptionOrNull()
        assertSame(expected, failure)
        assertTrue("Restoration ran and the main looper remains usable", restored)
    }

    @Test fun sleepWakeWithPinNeverShowsAnUnobservedOverlay() {
        val enabled = shell("settings get secure enabled_accessibility_services").trim()
        val accessibility = shell("settings get secure accessibility_enabled").trim()
        val disabled = shell("locksettings get-disabled").trim()
        val raw = context.getSharedPreferences("katapult_prefs", 0)
        val before = raw.all
        assertFalse("Fixture must start without a credential", context.getSystemService(KeyguardManager::class.java).isDeviceSecure)
        var pinSet = false
        var failure: Throwable? = null
        fun restore(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        try {
            PrefsManager(context).lockscreenMessage = "Epoch test"
            pinSet = true
            assertEquals("Pin set to '2468'", shell("su 0 locksettings set-pin 2468").trim())
            await("PIN credential active") { context.getSystemService(KeyguardManager::class.java).isDeviceSecure }
            val component = "${context.packageName}/${LockscreenWidgetService::class.java.name}"
            val list = if (enabled == "null" || enabled.isBlank()) component else "$enabled:$component"
            shell("settings put secure enabled_accessibility_services $list")
            shell("settings put secure accessibility_enabled 1")
            await("Service connected") { service() != null }
            val service = service()!!
            val first = main { visibility(service).epoch }
            shell("input keyevent KEYCODE_SLEEP")
            await("Screen off observed by service") {
                !context.getSystemService(PowerManager::class.java).isInteractive && !main { visibility(service).visible }
            }
            assertFalse(main { visibility(service).visible })
            shell("input keyevent KEYCODE_WAKEUP")
            await("New screen-on epoch") { main { visibility(service).epoch > first } }
            // Some API31 images also expose notification_panel. A positive observation is allowed,
            // but neither a visible state nor an attached overlay may precede this epoch's proof.
            val end = SystemClock.elapsedRealtime() + 2300
            while (SystemClock.elapsedRealtime() < end) {
                main {
                    val state = visibility(service)
                    if (state.visible) assertEquals(state.epoch, state.observedBareAt)
                    for (field in listOf("overlay", "musicOverlay", "messageOverlay", "weatherOverlay", "widgetsOverlay")) {
                        val view = LockscreenWidgetService::class.java.getDeclaredField(field)
                            .apply { isAccessible = true }.get(service)
                        if (view != null) { assertTrue(field, state.visible); assertEquals(state.epoch, state.observedBareAt) }
                    }
                }
                Thread.sleep(25)
            }
            shell("input swipe 240 680 240 120 200")
            await("PIN pad input focused") {
                val focus = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                try { focus?.className?.contains("EditText") == true } finally { @Suppress("DEPRECATION") focus?.recycle() }
            }
            await("PIN observation hides overlays") { main { !visibility(service).visible } }
            main {
                for (field in listOf("overlay", "musicOverlay", "messageOverlay", "weatherOverlay", "widgetsOverlay")) {
                    assertNull(field, LockscreenWidgetService::class.java.getDeclaredField(field)
                        .apply { isAccessible = true }.get(service))
                }
            }
        } catch (error: Throwable) {
            failure = error
        } finally {
            restore {
                // Clearing the stored credential does not dismiss an already displayed PIN bouncer.
                // Unlock through that credential first, then remove it and restore the old policy.
                if (pinSet && context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) {
                    shell("input keyevent KEYCODE_WAKEUP")
                    shell("input keyevent KEYCODE_MENU")
                    await("Restoration PIN input focused") {
                        val focus = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                        try { focus?.className?.contains("EditText") == true } finally { @Suppress("DEPRECATION") focus?.recycle() }
                    }
                    shell("input text 2468")
                    shell("input keyevent KEYCODE_ENTER")
                    await("Restoration unlocked keyguard") { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
                }
                if (pinSet) assertEquals("Lock credential cleared", shell("su 0 locksettings clear --old 2468").trim())
                await("PIN credential restored") { !context.getSystemService(KeyguardManager::class.java).isDeviceSecure }
            }
            restore { shell("locksettings set-disabled $disabled") }
            restore { shell("input keyevent KEYCODE_WAKEUP"); shell("wm dismiss-keyguard") }
            for ((key, value) in listOf("enabled_accessibility_services" to enabled, "accessibility_enabled" to accessibility)) {
                restore {
                shell(if (value == "null") "settings delete secure $key" else "settings put secure $key $value")
                assertEquals(value, shell("settings get secure $key").trim())
                }
            }
            restore {
            val edit = raw.edit()
            if (before.containsKey("lockscreen_message")) edit.putString("lockscreen_message", before["lockscreen_message"] as String)
            else edit.remove("lockscreen_message")
            assertTrue(edit.commit())
            }
            restore {
            assertEquals(disabled, shell("locksettings get-disabled").trim())
            await("Keyguard restored unlocked") { !context.getSystemService(KeyguardManager::class.java).isKeyguardLocked }
            await("Service disconnected after restoration") { service() == null }
            }
        }
        failure?.let { throw it }
    }

    @Test fun newVisibleEpochDeliversWeatherRemoteViewsToTheRealHost() {
        WidgetHostTestFixture(context).use { fixture ->
            val weather = "com.alexindigo.kotatsu.weather.debug"
            val info = fixture.providers.singleProvider(weather)
            val widget = LockscreenWidget(fixture.bind(info), LockscreenWidgetSize.M)
            val host = fixture.host
            val timeline = context.packageManager.getResourcesForApplication(weather).getIdentifier("timeline", "id", weather)
            val view = main { host.listen(true); LockscreenWidgetHost.updateSize(context, widget); host.createView(context, widget.id, info) }
            try {
                val original = WeatherHostViewProbe(context, view, timeline).awaitSizedBitmap()
                // Distinct host-only marker: only a new RemoteViews application can restore the provider image.
                val marker = Bitmap.createBitmap(original.width, original.height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.MAGENTA) }
                val state = LockscreenVisibility()
                main {
                    view.findViewById<ImageView>(timeline).setImageBitmap(marker)
                    state.beginEpoch()
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                    assertTrue((view.findViewById<ImageView>(timeline).drawable as BitmapDrawable).bitmap.sameAs(marker))
                    state.recheck(true) { false }
                    LockscreenWidgetHost.pulseVisible(context, state, listOf(widget))
                }
                await("Epoch options pulse reached Weather and applied new RemoteViews") { main {
                    val received = (view.findViewById<ImageView>(timeline)?.drawable as? BitmapDrawable)?.bitmap
                    received != null && !received.sameAs(marker) && received.sameAs(original)
                } }
                assertEquals(emptyList<Int>(), main { state.widgetsToPulse(listOf(widget.id)) })
            } finally { main { host.listen(false); host.releaseViews() } }
        }
    }
}
