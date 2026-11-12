package com.gezimos.katapult

import android.content.Intent
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
    private val keys = listOf("home_button_recent_apps", "home_button_screenshot", "home_button_menu")
    private val before = keys.associateWith { prefs.all[it] }
    private var accessibility: ActionServiceSettingsFixture? = null
    val service: LockscreenWidgetService get() = checkNotNull(accessibility).service
    lateinit var home: MainActivity
        private set
    init {
        try {
            prefs.edit().putBoolean("home_button_recent_apps", true).putBoolean("home_button_screenshot", false)
                .putBoolean("home_button_menu", false).commit()
            home = instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            instrumentation.runOnMainSync { home.viewModel.screen = Screen.HOME }
            accessibility = ActionServiceSettingsFixture.on()
        } catch (failure: Throwable) { close(); throw failure }
    }
    fun shell(command: String): String =
        instrumentation.getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            .boundedShell(command)
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
            try { accessibility?.let { instrumentation.runOnMainSync { it.service.onInterrupt() } } }
            finally { accessibility?.close() }
            ui.await("Unbind left Recent apps attached") { !overlay.windowVisible() }
        } finally {
            try { if (::home.isInitialized) instrumentation.runOnMainSync { home.finish() } }
            finally {
                prefs.edit().apply { before.forEach { (key, value) -> if (value == null) remove(key) else putBoolean(key, value as Boolean) } }.commit()
            }
        }
    }
}
