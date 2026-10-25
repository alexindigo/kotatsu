package com.gezimos.katapult

import android.app.AppOpsManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.ActionServiceSheet
import com.gezimos.katapult.util.AccessibilityFeature
import com.gezimos.katapult.util.PrefsManager
import com.gezimos.katapult.util.SensitiveAccess
import com.gezimos.katapult.util.SensitiveChoice
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LockscreenRecoveryInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private class TestApplication(base: Context, private val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    private fun withModel(test: (MainViewModel) -> Unit) {
        val name = "f6-${UUID.randomUUID()}"
        val raw = context.getSharedPreferences(name, Context.MODE_PRIVATE)
        val owner = ViewModelStore()
        lateinit var model: MainViewModel
        try {
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, raw))
                owner.put("f6", model)
                model.screen = Screen.HOME
            }
            test(model)
        } finally {
            instrumentation.runOnMainSync { owner.clear() }
            deleteTestPreferences(context, name)
        }
    }

    @Test fun musicAndMessageAlonePromptWhileOptOutAndInactivePowerSelectionDoNot() = withModel { model ->
        instrumentation.runOnMainSync {
            model.checkLockscreenService(false)
            assertFalse(model.showLockscreenReEnable)
            model.prefs.lockscreenMusicWidget = true
            model.checkLockscreenService(false)
            assertTrue(model.showLockscreenReEnable)
            model.disableLockscreenServiceFeatures()
            assertFalse(model.prefs.lockscreenMusicWidget)
            model.checkLockscreenService(true)
            model.prefs.lockscreenMessage = "Keep my message"
            model.checkLockscreenService(false)
            assertTrue(model.showLockscreenReEnable)
            model.disableLockscreenServiceFeatures()
            assertEquals("Keep my message", model.prefs.lockscreenMessage)
            model.checkLockscreenService(true)
            model.prefs.setSensitiveChoice(SensitiveChoice.ACCESSIBILITY_FEATURES, false)
            model.checkLockscreenService(false)
            assertFalse(model.showLockscreenReEnable)
            model.prefs.setSensitiveChoice(SensitiveChoice.ACCESSIBILITY_FEATURES, true)
            model.prefs.lockscreenMessage = null
            model.prefs.screensaverEnabled = false
            model.prefs.screensaverOnPower = true
            model.checkLockscreenService(false)
            assertFalse(model.showLockscreenReEnable)
        }
    }

    @Test fun switchAskedForBeforeSetupTurnsOnOnlyOnceTheServiceRuns() = withModel { model ->
        instrumentation.runOnMainSync {
            assertFalse(model.prefs.lockscreenWidget)
            model.requestActionService(AccessibilityFeature.WIDGET)
            assertEquals(AccessibilityFeature.WIDGET, model.actionServiceSetup?.feature)
            model.beginActionServiceSetup()
            assertNull(model.actionServiceSetup)
            model.checkLockscreenService(false)
            assertFalse("Applied before the service runs", model.prefs.lockscreenWidget)
            val applied = model.actionServiceApplied
            model.checkLockscreenService(true)
            assertTrue(model.prefs.lockscreenWidget)
            assertEquals(applied + 1, model.actionServiceApplied)
            model.checkLockscreenService(true)
            assertEquals("Applied twice", applied + 1, model.actionServiceApplied)
            // A sheet dismissed without opening settings asks for nothing.
            model.requestActionService(AccessibilityFeature.MEDIA)
            model.actionServiceSetup = null
            model.checkLockscreenService(true)
            assertFalse(model.prefs.lockscreenMusicWidget)
        }
    }

    @Test fun dismissedWarningRearmsOnlyAfterServiceWasObservedEnabledEvenOutsideHome() = withModel { model ->
        instrumentation.runOnMainSync {
            model.prefs.lockscreenMusicWidget = true
            model.checkLockscreenService(false)
            assertTrue(model.showLockscreenReEnable)
            model.resolveLockscreenReEnable()
            repeat(3) { model.checkLockscreenService(false); assertFalse(model.showLockscreenReEnable) }
            model.screen = Screen.SETTINGS
            model.checkLockscreenService(true)
            model.checkLockscreenService(false)
            assertFalse(model.showLockscreenReEnable)
            model.screen = Screen.HOME
            model.checkLockscreenService(false)
            assertTrue(model.showLockscreenReEnable)
            model.checkLockscreenService(true)
            assertFalse(model.showLockscreenReEnable)
            model.checkLockscreenService(false)
            assertTrue(model.showLockscreenReEnable)
        }
    }

    @Test fun renderedTurnOffActionClearsMusicAndExistingRowsButKeepsMessageAndScreensaver() = withModel { model ->
        var activity: MainActivity? = null
        try {
            instrumentation.runOnMainSync {
                model.prefs.lockscreenWidget = true
                model.prefs.lockscreenMusicWidget = true
                model.prefs.lockscreenMessage = "Keep this message"
                model.prefs.hideStatusBarClock = true
                model.prefs.screensaverEnabled = true
                model.prefs.screensaverOnPower = true
                model.prefs.doubleTapAction = PrefsManager.DOUBLE_TAP_LOCK
                model.checkLockscreenService(false)
            }
            activity = instrumentation.startActivitySync(Intent(Intent.ACTION_MAIN).apply {
                setClassName(context.packageName, MainActivity::class.java.name)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }) as MainActivity
            val current = activity
            instrumentation.runOnMainSync { current.setContent { ActionServiceSheet(model) } }
            val ui = PlatformUiDriver(instrumentation)
            val titles = listOf(R.string.lockscreen_widget, R.string.lockscreen_music_widget,
                R.string.lockscreen_message, R.string.hide_status_bar_clock,
                R.string.double_tap_action, R.string.screensaver_on_power)
            assertEquals(titles, model.lockscreenServiceFeatureTitles())
            for (title in titles) ui.await("Recovery sheet did not name ${context.getString(title)}") {
                ui.has(context.getString(title))
            }
            ui.click(context.getString(R.string.lockscreen_reenable_disable))
            ui.await("Recovery off action did not reach stored features") {
                !model.showLockscreenReEnable && !model.prefs.lockscreenMusicWidget && !model.prefs.lockscreenWidget
            }
            assertFalse(model.prefs.screensaverOnPower)
            assertFalse(model.prefs.hideStatusBarClock)
            assertEquals(PrefsManager.DOUBLE_TAP_OFF, model.prefs.doubleTapAction)
            assertEquals("Keep this message", model.prefs.lockscreenMessage)
            assertTrue(model.prefs.screensaverEnabled)
            instrumentation.runOnMainSync {
                model.prefs.doubleTapAction = PrefsManager.DOUBLE_TAP_BRIGHTNESS
                model.disableLockscreenServiceFeatures()
                assertEquals(PrefsManager.DOUBLE_TAP_BRIGHTNESS, model.prefs.doubleTapAction)
            }
        } finally {
            instrumentation.runOnMainSync { activity?.finish() }
        }
    }

    @Test fun ownWriteAppOpsCallbackAdvancesOnlyActionRevisionAndNotifiesViewModelConsumer() = withModel { model ->
        val listener = SensitiveAccess::class.java.getDeclaredField("appOpsListener").apply { isAccessible = true }
            .get(null) as AppOpsManager.OnOpChangedListener
        val before = SensitiveAccess.callActionRevision
        val collection = SensitiveAccess.callRevision
        val tick = model.policyRefresh
        listener.onOpChanged(AppOpsManager.OPSTR_WRITE_CALL_LOG, "other.app")
        instrumentation.waitForIdleSync()
        assertEquals(before, SensitiveAccess.callActionRevision)
        listener.onOpChanged(AppOpsManager.OPSTR_WRITE_CALL_LOG, context.packageName)
        PlatformUiDriver(instrumentation).await("WRITE_CALL_LOG callback did not reach action reconciliation consumer") {
            model.policyRefresh > tick
        }
        assertEquals(before + 1, SensitiveAccess.callActionRevision)
        assertEquals(collection, SensitiveAccess.callRevision)
    }
}
