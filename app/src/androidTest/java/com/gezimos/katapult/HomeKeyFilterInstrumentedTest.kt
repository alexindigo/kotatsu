package com.gezimos.katapult

import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import android.os.SystemClock
import android.view.KeyEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.AndroidEventDelivery
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.lockscreen.ReplayDelivery
import com.gezimos.katapult.recent.HomeKeyFilterState
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class HomeKeyFilterInstrumentedTest {
    @get:Rule val usageAccess = UsageAccessRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)
    private val overlay = PlatformUiDriver(instrumentation, context.getString(R.string.recent_apps))
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun shell(command: String) =
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .boundedShell(command)

    private fun hardwareHome(count: Int = 1, scan: Int = 172) {
        // InputManager's shell keyevent injection skips InputReader's accessibility filter.
        // Use the owned emulator keyboard's real evdev stream, not a direct service callback.
        val devices = shell("su 0 getevent -pl").split("add device ")
        val keyboard = devices.single { it.contains("KEY_HOMEPAGE") }
        val path = Regex("/dev/input/event[0-9]+").find(keyboard)!!.value
        assertTrue(shell("cat /system/usr/keylayout/Generic.kl").contains("key 172   HOME"))
        repeat(count) {
            assertEquals("", shell("su 0 sendevent $path 1 $scan 1"))
            assertEquals("", shell("su 0 sendevent $path 0 0 0"))
            assertEquals("", shell("su 0 sendevent $path 1 $scan 0"))
            assertEquals("", shell("su 0 sendevent $path 0 0 0"))
            if (it < count - 1) SystemClock.sleep(140)
        }
    }

    @Test fun eligibilityLossAfterNativeDownDoesNotLeakALoneUpToAndroid() = RecentOverlayFixture().use { fixture ->
        fixture.settings()
        val origin = fixture.resumedTask()
        val keyboard = Regex("/dev/input/event[0-9]+").find(
            shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") })!!.value
        val marker = "ELIGIBILITY ${SystemClock.uptimeMillis()}"
        android.util.Log.i("KotatsuHomeProbe", marker)
        shell("su 0 sendevent $keyboard 1 172 1"); shell("su 0 sendevent $keyboard 0 0 0")
        try {
            ui.await("HOME DOWN was not consumed") {
                shell("logcat -d -v brief -s KotatsuHome:D KotatsuHomeProbe:I").substringAfter(marker)
                    .lineSequence().any { it.contains("action=0 consumed=true") }
            }
            main { fixture.home.viewModel.prefs.homeButtonRecentApps = false }
            SystemClock.sleep(600)
        } finally {
            shell("su 0 sendevent $keyboard 1 172 0"); shell("su 0 sendevent $keyboard 0 0 0")
        }
        SystemClock.sleep(650)
        assertEquals("An owned UP must not trigger Android's Home handler", origin, fixture.resumedTask())
        assertFalse(fixture.overlay.windowVisible())
    }

    @Test fun connectedFilterShowsOverlayWithoutActivityOrFocusChangeAndPreservesEveryServiceInfo() {
        val enabled = shell("settings get secure enabled_accessibility_services")
        val accessibility = shell("settings get secure accessibility_enabled")
        assertFalse("Preserve an already-enabled Action Service", enabled.contains(LockscreenWidgetService::class.java.name))
        val prefs = context.getSharedPreferences("katapult_prefs", 0)
        val keys = listOf("onboarding_complete", "collect_usage_stats", "home_button_recent_apps", "home_button_screenshot", "home_button_menu")
        val previous = keys.associateWith { prefs.all[it] }
        val launches = java.util.concurrent.CopyOnWriteArrayList<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                launches += Intent(intent)
                return null
            }
        }
        val field = LockscreenWidgetService::class.java.getDeclaredField("instance").apply { isAccessible = true }
        var home: MainActivity? = null
        instrumentation.addMonitor(monitor)
        try {
            prefs.edit().putBoolean("onboarding_complete", true).putBoolean("collect_usage_stats", true)
                .putBoolean("home_button_recent_apps", true).putBoolean("home_button_screenshot", false)
                .putBoolean("home_button_menu", false).commit()
            home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            val model = home.viewModel
            main { model.screen = Screen.HOME }
            val component = "${context.packageName}/${LockscreenWidgetService::class.java.name}"
            val value = if (enabled == "null" || enabled.isBlank()) component else "$enabled:$component"
            shell("settings put secure enabled_accessibility_services $value")
            shell("settings put secure accessibility_enabled 1")
            ui.await("Key filter did not connect", 15000) { HomeKeyFilterState.active && field.get(null) != null }
            val service = field.get(null) as LockscreenWidgetService
            main {
                val port = AndroidEventDelivery(service)
                val baseline = port.currentInfo()
                val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
                assertTrue(baseline.flags and flag != 0)
                assertTrue(baseline.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0)
                val static = port.staticInfo()
                val widened = port.widened(static)
                assertArrayEquals(arrayOf("com.android.systemui"), static.packageNames)
                assertNull(widened.packageNames)
                assertEquals(0L, widened.notificationTimeout)
                assertEquals(baseline.capabilities, widened.capabilities)
                assertEquals(baseline.flags, widened.flags)
                // Even an input missing the flag is repaired only on the applied copy.
                val missing = port.currentInfo().apply { flags = flags and flag.inv() }
                port.apply(missing)
                assertEquals(0, missing.flags and flag)
                assertTrue(service.serviceInfo.flags and flag != 0)
                val replay = ReplayDelivery(port) { throw it }
                val token = replay.begin()
                assertNull(service.serviceInfo.packageNames)
                assertTrue(service.serviceInfo.flags and flag != 0)
                assertTrue(replay.dispatched(token))
                replay.end(token)
                assertArrayEquals(static.packageNames, service.serviceInfo.packageNames)
                assertEquals(static.notificationTimeout, service.serviceInfo.notificationTimeout)
                assertEquals(static.flags, service.serviceInfo.flags)
                assertEquals(static.eventTypes, service.serviceInfo.eventTypes)
                assertEquals(static.feedbackType, service.serviceInfo.feedbackType)
                assertEquals(static.capabilities, service.serviceInfo.capabilities)
            }
            // While active, HOME intents must not be interpreted as another double.
            main { model.onHomeIntent(); model.onHomeIntent() }
            assertFalse(overlay.windowVisible())
            main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            ui.await("Origin subpage missing") { ui.has("Brightness level") }
            val resumed = shell("dumpsys activity activities").lineSequence().first { it.contains("mResumedActivity:") }
            val focused = shell("dumpsys window").lineSequence().first { it.contains("mCurrentFocus=") }
            launches.clear()
            java.io.File(context.getExternalFilesDir(null), "key-filter-system.txt").writeText(
                shell("dumpsys accessibility") + "\nINPUT\n" + shell("dumpsys input"))
            hardwareHome(2)
            ui.await("Filtered pair did not open Recent apps", 10000) { overlay.windowVisible() }
            assertEquals(resumed, shell("dumpsys activity activities").lineSequence().first { it.contains("mResumedActivity:") })
            assertEquals(focused, shell("dumpsys window").lineSequence().first { it.contains("mCurrentFocus=") })
            assertTrue("Opening must not launch any Activity", launches.isEmpty())
            assertFalse("Pair must not start Home between the app and Recent apps", launches.any { it.hasCategory(Intent.CATEGORY_HOME) })
            hardwareHome(scan = 158)
            ui.await("Back did not reveal underlying subpage") { ui.has("Brightness level") && !overlay.windowVisible() }
            hardwareHome(2)
            ui.await("Second filtered pair did not reopen") { overlay.windowVisible() }
            hardwareHome(2)
            ui.await("Filtered pair did not remove overlay") { !overlay.windowVisible() && ui.has("Brightness level") }
            assertEquals(resumed, shell("dumpsys activity activities").lineSequence().first { it.contains("mResumedActivity:") })
            // Other keys and onboarding/reorder do not enter the pending HOME state.
            val callback = LockscreenWidgetService::class.java.getDeclaredMethod("onKeyEvent", KeyEvent::class.java).apply { isAccessible = true }
            main {
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_VOLUME_DOWN)))
                model.prefs.homeButtonRecentApps = false
                assertTrue(HomeKeyFilterState.active)
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
                model.prefs.homeButtonRecentApps = true
                model.screen = Screen.ONBOARDING
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_HOME)))
                model.screen = Screen.HOME; model.reorderMode = true
                assertEquals(false, callback.invoke(service, KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_HOME)))
                model.reorderMode = false
            }
            val defaultHome = context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)!!.activityInfo.packageName
            val singleMarker = "SINGLE HOME ${SystemClock.uptimeMillis()}"
            android.util.Log.i("KotatsuHomeProbe", singleMarker)
            hardwareHome()
            ui.await("Single key did not reach Home") { ui.packageVisible(defaultHome) }
            val singleTrace = shell("logcat -d -v brief -s KotatsuHome:D KotatsuHomeProbe:I").substringAfter(singleMarker)
            val down = checkNotNull(Regex("eventTime=([0-9]+) action=0 consumed=true").find(singleTrace)).groupValues[1].toLong()
            val dispatched = checkNotNull(Regex("path=key single uptime=([0-9]+)").find(singleTrace)).groupValues[1].toLong()
            assertTrue("Single Home must wait from native DOWN, not device discovery", dispatched - down >= 500)
            SystemClock.sleep(600)
            main { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
            ui.await("Held-key origin missing") { ui.has("Brightness level") }
            val keyboard = Regex("/dev/input/event[0-9]+").find(
                shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") })!!.value
            shell("su 0 sendevent $keyboard 1 172 1"); shell("su 0 sendevent $keyboard 0 0 0")
            try {
                SystemClock.sleep(650)
                assertTrue("Held Home must stay at the origin until UP", ui.has("Brightness level"))
            } finally {
                shell("su 0 sendevent $keyboard 1 172 0"); shell("su 0 sendevent $keyboard 0 0 0")
            }
            ui.await("Held Home did not reach Home on UP") { ui.packageVisible(defaultHome) }
        } finally {
            try {
                try { instrumentation.removeMonitor(monitor) }
                finally { main { home?.finish() } }
            } finally {
                try {
                    try {
                        restoreSecureSetting("enabled_accessibility_services", enabled, ::shell)
                    } finally {
                        restoreSecureSetting("accessibility_enabled", accessibility, ::shell)
                    }
                    ui.await("Key-filter active state leaked after unbind", 15000) { !HomeKeyFilterState.active }
                    awaitActionServiceSettingsPersisted(enabled, ::shell)
                } finally {
                    prefs.edit().apply { previous.forEach { (key, value) ->
                        if (value == null) remove(key) else putBoolean(key, value as Boolean)
                    } }.commit()
                }
            }
        }
    }
}
