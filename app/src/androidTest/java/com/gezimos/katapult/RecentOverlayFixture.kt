package com.gezimos.katapult

import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.LockscreenWidgetService
import com.gezimos.katapult.recent.HomeKeyFilterState
import org.junit.Assert.*

/** Owns service activation, native input and preference restoration for one test. */
internal class RecentOverlayFixture : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    val ui = PlatformUiDriver(instrumentation)
    val overlay = PlatformUiDriver(instrumentation, context.getString(R.string.recent_apps))
    private val prefs = context.getSharedPreferences("katapult_prefs", 0)
    private val keys = listOf("home_button_recent_apps", "home_button_screenshot")
    private val before = keys.associateWith { prefs.all[it] }
    private val enabled = shell("settings get secure enabled_accessibility_services")
    private val accessibility = shell("settings get secure accessibility_enabled")
    private val instance = LockscreenWidgetService::class.java.getDeclaredField("instance").apply { isAccessible = true }
    val service: LockscreenWidgetService get() = instance.get(null) as LockscreenWidgetService
    lateinit var home: MainActivity
        private set
    init {
        assertFalse("Fixture must not replace an enabled Action Service", enabled.contains(LockscreenWidgetService::class.java.name))
        try {
            prefs.edit().putBoolean("home_button_recent_apps", true).putBoolean("home_button_screenshot", false).commit()
            home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync { home.viewModel.screen = Screen.HOME }
            val component = "${context.packageName}/${LockscreenWidgetService::class.java.name}"
            shell("settings put secure enabled_accessibility_services " + if (enabled == "null" || enabled.isBlank()) component else "$enabled:$component")
            shell("settings put secure accessibility_enabled 1")
            ui.await("Action Service did not connect", 15000) { HomeKeyFilterState.active && instance.get(null) != null }
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun shell(command: String): String = ParcelFileDescriptor.AutoCloseInputStream(
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    fun settings() {
        // One cross-app handoff can stamp the old app's pause and Settings' resume
        // in the same millisecond. Settle Settings first, then enter Display in a
        // separate transition: the fixture owns its strict usage-order precondition.
        instrumentation.runOnMainSync { context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Settings root not foreground") {
            ui.packageVisible("com.android.settings") && !ui.has("Brightness level")
        }
        instrumentation.runOnMainSync { context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)) }
        ui.await("Display settings not foreground") { ui.has("Brightness level") }
    }
    fun key(scan: Int, count: Int = 1) {
        val keyboard = shell("su 0 getevent -pl").split("add device ").single { it.contains("KEY_HOMEPAGE") }
        val path = checkNotNull(Regex("/dev/input/event[0-9]+").find(keyboard)).value
        repeat(count) {
            shell("su 0 sendevent $path 1 $scan 1"); shell("su 0 sendevent $path 0 0 0")
            shell("su 0 sendevent $path 1 $scan 0"); shell("su 0 sendevent $path 0 0 0")
            if (it < count - 1) SystemClock.sleep(140)
        }
    }
    fun homeKey(count: Int = 1) = key(172, count)
    fun back() = key(158)
    fun open() {
        homeKey(2)
        try { ui.await("Recent apps overlay not attached", 10000) { overlay.windowVisible() } }
        catch (failure: AssertionError) { throw AssertionError(overlay.windowDescriptions(), failure) }
    }
    fun resumedTask(): String = shell("dumpsys activity activities").lineSequence().first { it.contains("mResumedActivity:") }.trim()
    fun focusedWindow(): String = shell("dumpsys window").lineSequence().first { it.contains("mCurrentFocus=") }.trim()
    override fun close() {
        try {
            try { restoreSecureSetting("enabled_accessibility_services", enabled, ::shell) }
            finally { restoreSecureSetting("accessibility_enabled", accessibility, ::shell) }
            ui.await("Action Service did not unbind", 15000) { !HomeKeyFilterState.active }
            ui.await("Unbind left Recent apps attached") { !overlay.windowVisible() }
            ui.await("Accessibility settings did not restore") {
                shell("settings get secure enabled_accessibility_services") == enabled &&
                    shell("settings get secure accessibility_enabled") == accessibility
            }
            awaitActionServiceSettingsPersisted(enabled, ::shell)
        } finally {
            try { if (::home.isInitialized) instrumentation.runOnMainSync { home.finish() } }
            finally {
                prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value as Boolean) } }.commit()
            }
        }
    }
}
