package com.gezimos.katapult

import android.app.KeyguardManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeKeyguardFixtureInstrumentedTest {
    @Test fun automationCommandsRejectShellSeparatorsRatherThanInjectingExtraKeys() {
        val command = "input keyevent KEYCODE_WAKEUP"
        assertEquals(command, NativeFixtureCommand.checked(command))
        assertTrue(runCatching { NativeFixtureCommand.checked("$command; input swipe 240 680 240 120 250") }
            .exceptionOrNull() is IllegalArgumentException)
    }

    @Test fun publicDisplayReadinessRequiresInteractiveAndOn() {
        val on = android.view.Display.STATE_ON
        assertFalse(NativeDisplayInputState.ready(false, on))
        for (state in listOf(android.view.Display.STATE_OFF, android.view.Display.STATE_DOZE,
            android.view.Display.STATE_DOZE_SUSPEND, android.view.Display.STATE_UNKNOWN)) {
            assertFalse(NativeDisplayInputState.ready(true, state))
        }
        assertTrue(NativeDisplayInputState.ready(true, on))
    }

    @Test fun repeatedReadinessAndWakePreserveFocusedNativePin() {
        NativeKeyguardFixture().use { fixture ->
            fixture.setPin()
            fixture.sleep()
            fixture.showPin()
            repeat(12) {
                assertTrue(fixture.inputReady())
                fixture.wake()
                assertTrue("Readiness must preserve native PIN ownership", fixture.pinVisible())
            }
            fixture.unlock()
        }
    }

    /** Same native-only rapid sleep/wake/swipe as the investigation. A lost touch must not
     * strand restoration: the owner observes display/PIN state and uses MENU, not touch probes. */
    @Test fun rapidWakeAndSwipeCannotStrandNativePinRestoration() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyguard = instrumentation.targetContext.getSystemService(KeyguardManager::class.java)
        NativeKeyguardFixture().use { fixture ->
            fixture.setPin()
            repeat(5) {
                fixture.sleep()
                fixture.replayWakeSwipeRace()
                fixture.unlock()
                assertTrue("Native display reports interactive and on", fixture.inputReady())
                assertFalse("Native PIN accepted after the raced swipe", keyguard.isKeyguardLocked)
            }
        }
        assertFalse("Fixture removed its PIN", keyguard.isDeviceSecure)
    }
}
