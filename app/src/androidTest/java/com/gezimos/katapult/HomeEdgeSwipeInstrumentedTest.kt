package com.gezimos.katapult

import android.app.Application
import android.app.Instrumentation
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.gezimos.katapult.lockscreen.*
import com.gezimos.katapult.ui.App
import com.gezimos.katapult.ui.settings.PaperIndexRenderer
import com.gezimos.katapult.ui.settings.edgeSwipesCategory
import org.junit.Assert.*
import org.junit.Test

/** Real Home input -> shared settings -> the existing side surface / Android camera handoff. */
class HomeEdgeSwipeInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val ui = PlatformUiDriver(instrumentation)

    private class TestApplication(base: Context, val store: SharedPreferences) : Application() {
        init { attachBaseContext(base) }
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences =
            if (name == "katapult_prefs") store else super.getSharedPreferences(name, mode)
    }

    private fun withHome(test: (MainViewModel, WidgetHostTestFixture, MainActivity) -> Unit) {
        WidgetHostTestFixture(context).use { fixture ->
            val owner = ViewModelStore()
            lateinit var model: MainViewModel
            instrumentation.runOnMainSync {
                model = MainViewModel(TestApplication(context.applicationContext, fixture.raw))
                owner.put("home-edges", model)
                model.screen = Screen.HOME
                model.prefs.edgeActions.enabled = true
                model.prefs.swipeUpAllApps = true
            }
            val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
            try {
                instrumentation.runOnMainSync { activity.setContent {
                    App(model, activity.imagePicker, activity.iconPicker, activity.configExporter,
                        activity.configImporter, activity.screensaverWallpaperPicker, {})
                } }
                ui.await("Home not rendered") { ui.has(context.getString(R.string.all_apps)) }
                test(model, fixture, activity)
            } finally { instrumentation.runOnMainSync { activity.finish(); owner.clear() } }
        }
    }

    private fun swipe(from: Float, to: Float, vertical: Boolean = false) {
        val bounds = context.getSystemService(android.view.WindowManager::class.java).maximumWindowMetrics.bounds
        val time = SystemClock.uptimeMillis()
        for (step in 0..20) {
            val value = from + (to - from) * step / 20f
            val event = MotionEvent.obtain(time, SystemClock.uptimeMillis(), when (step) {
                0 -> MotionEvent.ACTION_DOWN
                20 -> MotionEvent.ACTION_UP
                else -> MotionEvent.ACTION_MOVE
            }, if (vertical) bounds.width() / 2f else value * bounds.width(),
                if (vertical) value * bounds.height() else bounds.height() / 2f, 0)
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
            event.recycle()
            SystemClock.sleep(15)
        }
        instrumentation.waitForIdleSync()
    }

    @Test fun eitherEdgeUsesTheConfiguredSideScreenAndSharedCloseRules() = withHome { model, fixture, _ ->
        val provider = fixture.manager.installedProviders.single {
            it.provider.className == SideTestWidget::class.java.name
        }
        val id = fixture.bind(provider)
        fixture.raw.edit().putString(LockscreenWidgetStore.SCREENS,
            SideScreenList.encode(listOf(SideScreen(name = "Home shared widgets", slots = listOf(id, null))))).commit()
        for (edge in ScreenEdge.entries) {
            model.prefs.edgeActions.setAction(edge, EdgeAction.SIDE_SCREEN)
            fun open() {
                val homeWindow = ui.find(context.getString(R.string.all_apps))!!.windowId
                if (edge == ScreenEdge.LEFT) swipe(.005f, .65f) else swipe(.995f, .35f)
                ui.await("Configured side screen missing from $edge") { ui.has("Home shared widgets") && ui.has("Hosted item 0") }
                assertEquals("Home must reuse its Activity window", homeWindow, ui.find("Home shared widgets")!!.windowId)
            }
            open()
            ui.click(context.getString(R.string.side_close))
            ui.await("Close did not return Home") { ui.has(context.getString(R.string.all_apps)) }
            open()
            ui.back()
            ui.await("Back did not return Home") { ui.has(context.getString(R.string.all_apps)) }
            open()
            if (edge == ScreenEdge.LEFT) swipe(.65f, .05f) else swipe(.35f, .95f)
            ui.await("Outward close did not return Home") { ui.has(context.getString(R.string.all_apps)) }
        }
        swipe(.995f, .35f)
        ui.await("Side screen not reopened") { ui.has("Hosted item 0") }
        ui.click("Hosted item 0")
        ui.await("Widget activity did not open directly") { ui.has("Payload: row-0") }
        assertFalse(ui.has(context.getString(R.string.side_unlock)))
        ui.click(ui.find { it.text?.toString()?.equals("Finish fixture", true) == true }!!.text.toString())
        ui.await("Widget return did not close side screen") { ui.has(context.getString(R.string.all_apps)) }
    }

    @Test fun homeRequestClosesSideScreenWithoutPausingTheActivity() = withHome { model, _, activity ->
        for (edge in ScreenEdge.entries) {
            model.prefs.edgeActions.setAction(edge, EdgeAction.SIDE_SCREEN)
            if (edge == ScreenEdge.LEFT) swipe(.005f, .65f) else swipe(.995f, .35f)
            ui.await("Side screen did not open") { ui.has(context.getString(R.string.side_close)) }
            instrumentation.runOnMainSync {
                assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, activity.lifecycle.currentState)
                model.onHomeIntent()
            }
            ui.await("HOME must close the side screen without a pause") { ui.has(context.getString(R.string.all_apps)) }
        }
    }

    @Test fun settingsCanEnableHomeEdgesWithoutActionService() = withHome { model, _, _ ->
        assertFalse("This test requires Action Service off", LockscreenWidgetService.isEnabled(context))
        // Exercise the production Kompakt-only settings route on the stock emulator.
        val deviceModel = android.os.Build::class.java.getDeclaredField("MODEL").apply { isAccessible = true }
        val previousModel = deviceModel.get(null)
        deviceModel.set(null, "Kompakt")
        try {
            instrumentation.runOnMainSync {
                model.prefs.edgeActions.enabled = false
                model.observeActionService(true) // A switch must reconcile even a stale service observation.
                model.settingsPageRequest = "edges"
                model.navigateTo(Screen.SETTINGS)
            }
            ui.await("Actual edge settings did not open") { ui.has(context.getString(R.string.edge_home_enabled)) }
            fun toggleMaster() {
                val toggle = ui.findAncestor(context.getString(R.string.edge_swipes_help)) { it.isCheckable }
                assertNotNull("Master edge switch missing", toggle)
                assertTrue(toggle!!.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK))
            }
            toggleMaster()
            ui.await("Master edge switch must persist without Action Service") { model.prefs.edgeActions.enabled }
            ui.await("Enabling edges must still guide lock-screen setup") {
                model.actionServiceSetup?.feature == com.gezimos.katapult.util.AccessibilityFeature.EDGE_SWIPES &&
                    ui.has(context.getString(R.string.a11y_setup_title))
            }
            assertFalse("Switch must refresh the service observation", model.actionServiceNotice.serviceOn)
            assertFalse(LockscreenWidgetService.isEnabled(context))
            ui.back()
            ui.await("Dismiss setup without disabling Home edges") {
                model.actionServiceSetup == null && ui.has(context.getString(R.string.edge_home_enabled))
            }
            assertTrue(model.prefs.edgeActions.enabled)
            toggleMaster()
            ui.await("Master edge switch must turn Off") { !model.prefs.edgeActions.enabled }
            instrumentation.waitForIdleSync()
            assertNull("Turning edges Off must never request service setup", model.actionServiceSetup)
            assertFalse(ui.has(context.getString(R.string.a11y_setup_title)))
            toggleMaster()
            ui.await("Turning edges back On must persist and request setup") {
                model.prefs.edgeActions.enabled &&
                    model.actionServiceSetup?.feature == com.gezimos.katapult.util.AccessibilityFeature.EDGE_SWIPES &&
                    ui.has(context.getString(R.string.a11y_setup_title))
            }
            ui.back()
            ui.await("Setup did not dismiss") { model.actionServiceSetup == null }
            instrumentation.runOnMainSync { model.onHomeIntent() }
            ui.await("Home not restored") { ui.has(context.getString(R.string.all_apps)) }
            model.prefs.edgeActions.setAction(ScreenEdge.LEFT, EdgeAction.SIDE_SCREEN)
            swipe(.005f, .65f)
            ui.await("Home edge must work without Action Service") { ui.has(context.getString(R.string.side_close)) }
        } finally { deviceModel.set(null, previousModel) }
    }

    @Test fun cameraAndNothingFollowBothEdgeSettingsAndTheMasterSwitch() = withHome { model, _, _ ->
        val normal = instrumentation.addMonitor(IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Instrumentation.ActivityResult(0, null), true)
        val secure = instrumentation.addMonitor(IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
            Instrumentation.ActivityResult(0, null), true)
        try {
            for (edge in ScreenEdge.entries) {
                fun inward() { if (edge == ScreenEdge.LEFT) swipe(.005f, .65f) else swipe(.995f, .35f) }
                model.prefs.edgeActions.setAction(edge, EdgeAction.CAMERA)
                val before = normal.hits
                inward()
                assertEquals(before + 1, normal.hits)
                model.prefs.edgeActions.setAction(edge, EdgeAction.NOTHING)
                inward()
                assertEquals(before + 1, normal.hits)
                assertTrue(ui.has(context.getString(R.string.all_apps)))
                model.prefs.edgeActions.setAction(edge, EdgeAction.CAMERA)
                model.prefs.edgeActions.enabled = false
                inward()
                assertEquals(before + 1, normal.hits)
                model.prefs.edgeActions.enabled = true
            }
            assertEquals(0, secure.hits)
        } finally { instrumentation.removeMonitor(normal); instrumentation.removeMonitor(secure) }
    }

    @Test fun middleSwipesKeepBothExistingAppGestureModesAndShortEdgesDoNothing() = withHome { model, _, _ ->
        model.prefs.edgeActions.setAction(ScreenEdge.LEFT, EdgeAction.SIDE_SCREEN)
        model.prefs.edgeActions.setAction(ScreenEdge.RIGHT, EdgeAction.SIDE_SCREEN)
        swipe(.005f, .20f)
        swipe(.995f, .80f)
        assertTrue(ui.has(context.getString(R.string.all_apps)))
        swipe(.75f, .25f)
        assertEquals(Screen.HOME, model.screen)
        swipe(.75f, .25f, vertical = true)
        ui.await("Middle vertical swipe changed") { model.screen == Screen.ALL_APPS }
        instrumentation.runOnMainSync { model.screen = Screen.HOME; model.prefs.verticalAppGestures = false }
        ui.await("Home not restored") { ui.has(context.getString(R.string.all_apps)) }
        model.prefs.edgeActions.setAction(ScreenEdge.RIGHT, EdgeAction.NOTHING)
        swipe(.995f, .35f)
        assertEquals("Nothing must not fall through to horizontal All Apps", Screen.HOME, model.screen)
        swipe(.75f, .25f)
        ui.await("Existing middle horizontal All Apps gesture changed") { model.screen == Screen.ALL_APPS }
    }

    @Test fun homeSwitchDefaultsOnPersistsOffAndLeavesLockActionsEnabled() = withHome { model, fixture, activity ->
        val store = model.prefs.edgeActions
        assertTrue(store.homeEnabled)
        // The stock emulator is not a Kompakt; render the same category without its device route gate.
        instrumentation.runOnMainSync { activity.setContent {
            var revision by remember { mutableIntStateOf(0) }
            @Suppress("UNUSED_VARIABLE") val observed = revision
            PaperIndexRenderer(edgeSwipesCategory(store.enabled, store.homeEnabled,
                store.action(ScreenEdge.LEFT), store.action(ScreenEdge.RIGHT), false,
                { store.enabled = it; revision++ }, { store.homeEnabled = it; revision++ },
                { edge, action -> store.setAction(edge, action); revision++ }), {})
        } }
        val label = context.getString(R.string.edge_home_enabled)
        fun state() = ui.findAncestor(label) { it.isCheckable }?.stateDescription?.toString()
        ui.await("Home switch must display On by default") { state() == context.getString(com.gezimos.katapult.paper.R.string.paper_on) }
        ui.click(label)
        ui.await("Home switch did not display/persist Off") {
            !store.homeEnabled && state() == context.getString(com.gezimos.katapult.paper.R.string.paper_off)
        }
        assertFalse(EdgeActionStore(fixture.raw).homeEnabled)
        assertTrue("Home Off must leave the lock-screen master On", store.enabled)
        assertEquals(EdgeAction.SIDE_SCREEN, store.action(ScreenEdge.LEFT))
        assertEquals(EdgeAction.CAMERA, store.action(ScreenEdge.RIGHT))
        instrumentation.runOnMainSync { activity.setContent {
            App(model, activity.imagePicker, activity.iconPicker, activity.configExporter,
                activity.configImporter, activity.screensaverWallpaperPicker, {})
        } }
        ui.await("Home not restored") { ui.has(context.getString(R.string.all_apps)) }
        val camera = instrumentation.addMonitor(IntentFilter(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
            Instrumentation.ActivityResult(0, null), true)
        try {
            swipe(.005f, .65f)
            assertTrue("Side screen must stay closed with Home Off", ui.has(context.getString(R.string.all_apps)))
            swipe(.995f, .35f)
            assertEquals(0, camera.hits)
            assertEquals(Screen.HOME, model.screen)
        } finally { instrumentation.removeMonitor(camera) }
    }
}
