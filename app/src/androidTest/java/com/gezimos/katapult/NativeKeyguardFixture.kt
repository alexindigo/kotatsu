package com.gezimos.katapult

import android.app.KeyguardManager
import android.app.UiAutomation
import android.hardware.display.DisplayManager
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*

/** Owns native wake/input readiness and, when requested, the isolated emulator's PIN/policy. */
class NativeKeyguardFixture(private val unlockBeforeClear: NativeKeyguardFixture.() -> Unit = { unlock() }) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val automation get() = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
    private val keyguard = context.getSystemService(KeyguardManager::class.java)
    private val power = context.getSystemService(PowerManager::class.java)
    private val display = context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)
    private val disabled = shell("locksettings get-disabled")
    private var pinSet = false

    private fun shell(command: String) = ParcelFileDescriptor.AutoCloseInputStream(
        automation.executeShellCommand(NativeFixtureCommand.checked(command))).bufferedReader().use { it.readText().trim() }

    private fun await(message: String, ready: () -> Boolean) {
        val end = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < end) {
            if (ready()) return
            SystemClock.sleep(25)
        }
        throw AssertionError(message)
    }

    fun setPin() {
        check(!pinSet)
        assertFalse("Fixture must start without a credential", keyguard.isDeviceSecure)
        // Own restoration even if setup fails after the native credential was changed.
        pinSet = true
        assertEquals("Pin set to '2468'", shell("su 0 locksettings set-pin 2468"))
        await("Fixture PIN active in credential and keyguard services") { keyguard.isDeviceSecure && keyguard.isKeyguardSecure }
    }

    fun sleep() {
        shell("input keyevent KEYCODE_SLEEP")
        await("Native screen did not sleep") { !power.isInteractive }
    }

    /** Observation only. Even an acknowledged DOWN/CANCEL can reset native PIN through
     * SystemUI falsing; display state is not proof of focus or of a touch consumer. */
    fun inputReady(): Boolean = NativeDisplayInputState.ready(power.isInteractive, display.state)

    fun wake() {
        if (!power.isInteractive) shell("input keyevent KEYCODE_WAKEUP")
        await("Native display did not become interactive and on", ::inputReady)
    }

    /** Regression-only replay of the old caller's lost-touch interval, not a readiness path. */
    internal fun replayWakeSwipeRace() {
        check(pinSet)
        shell("input keyevent KEYCODE_WAKEUP")
        shell("input swipe 240 680 240 120 250")
    }

    fun pinVisible(): Boolean {
        val focus = automation.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        return try {
            focus?.refresh() == true && focus.isVisibleToUser &&
                focus.packageName?.toString() == "com.android.systemui" &&
                focus.viewIdResourceName == "com.android.systemui:id/pinEntry" && focus.className?.contains("EditText") == true
        } finally { @Suppress("DEPRECATION") focus?.recycle() }
    }

    fun showPin() {
        wake()
        val ui = PlatformUiDriver(instrumentation)
        await("Native keyguard did not acquire its drawn input window") {
            keyguard.isKeyguardLocked && power.isInteractive && display.state == Display.STATE_ON &&
                // Other SystemUI windows (notably ScreenshotAnimation) share the package,
                // but do not own keyguard input and must never receive the fixture's MENU.
                (pinVisible() || ui.find { it.viewIdResourceName == "com.android.systemui:id/notification_panel" } != null)
        }
        if (!pinVisible()) shell("input keyevent KEYCODE_MENU")
        await("Native SystemUI PIN input absent", ::pinVisible)
    }

    fun unlock() {
        if (pinSet) {
            showPin()
            shell("input text 2468")
            shell("input keyevent KEYCODE_ENTER")
        } else {
            assertFalse("Cannot dismiss an unowned credential", keyguard.isDeviceSecure)
            wake()
            shell("wm dismiss-keyguard")
        }
        await("Native credential did not unlock") { !keyguard.isKeyguardLocked }
    }

    override fun close() {
        var failure: Throwable? = null
        fun restore(action: () -> Unit) { try { action() } catch (error: Throwable) {
            if (failure == null) failure = error else failure!!.addSuppressed(error)
        } }
        if (pinSet) {
            restore { if (keyguard.isKeyguardLocked) unlockBeforeClear() }
            restore {
                assertEquals("Lock credential cleared", shell("su 0 locksettings clear --old 2468"))
                await("PIN credential restored") { !keyguard.isDeviceSecure }
                pinSet = false
                // SystemUI can retain the old PIN bouncer after LockSettings clears its
                // credential. A new wake reconciles that window with the restored policy.
                sleep()
            }
        }
        restore {
            shell("locksettings set-disabled $disabled")
            assertEquals(disabled, shell("locksettings get-disabled"))
        }
        restore { unlock() }
        failure?.let { throw it }
    }

    companion object {
        fun wakeAndUnlock() = NativeKeyguardFixture().use { it.unlock() }
    }
}

internal object NativeDisplayInputState {
    fun ready(interactive: Boolean, displayState: Int): Boolean =
        interactive && displayState == Display.STATE_ON
}

/** UiAutomation uses Runtime.exec(String), not a shell. In particular, `input keyevent`
 * treats following tokens as more keys: an accidental compound swipe can inject SYSRQ. */
internal object NativeFixtureCommand {
    fun checked(command: String): String {
        require(command.none { it in ";|&\n\r`" }) { "UiAutomation requires one command, without shell separators" }
        return command
    }
}
